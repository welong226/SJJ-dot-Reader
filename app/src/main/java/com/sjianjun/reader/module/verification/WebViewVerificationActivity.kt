package com.sjianjun.reader.module.verification

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.sjianjun.reader.App
import com.sjianjun.reader.BaseActivity
import com.sjianjun.reader.R
import com.sjianjun.reader.databinding.ActivityVerificationBinding
import com.sjianjun.reader.http.CloudflareChallengeDetector
import com.sjianjun.reader.module.bookcity.AdBlock
import com.sjianjun.reader.module.bookcity.BLOCK_MODE_DOMAIN
import com.sjianjun.reader.module.bookcity.BLOCK_MODE_SUBDOMAIN
import com.sjianjun.reader.module.bookcity.CustomWebView
import com.sjianjun.reader.module.browser.BrowserTab
import com.sjianjun.reader.module.browser.TabHost
import com.sjianjun.reader.python.ScriptTaskCancelledException
import com.sjianjun.reader.python.ScriptTaskRegistry
import com.sjianjun.reader.utils.gson
import com.sjianjun.reader.view.click
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import org.apache.commons.text.StringEscapeUtils
import sjj.alog.Log
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * 人机验证页：直接承载浏览器的 [CustomWebView]（含其底部工具栏 / 地址栏 / 菜单），
 * 因此与书城独立浏览器外观完全统一；顶部额外提供一个“完成”按钮用于结束验证并把当前页面 HTML 回传脚本。
 *
 * 验证的“阻塞等待 + 回传 HTML + 脚本取消即关闭”语义全部保留在本次活动里（[resultMap] / [waitForResult] /
 * [ScriptTaskRegistry] 取消动作），未触碰 [com.sjianjun.reader.module.browser.TabRepository]，
 * 仅用一个本地 [BrowserTab] + [TabHost] 桩驱动 [CustomWebView]，不会污染用户的浏览器标签库。
 */
class WebViewVerificationActivity : BaseActivity(), TabHost {
    private lateinit var binding: ActivityVerificationBinding
    private val url: String by lazy { intent.getStringExtra(KEY_URL) ?: "" }
    private val headerMap: Map<String, String> by lazy {
        intent.getSerializableExtra(KEY_HEADER_MAP) as? Map<String, String> ?: mapOf()
    }
    private val postData: ByteArray? by lazy { intent.getByteArrayExtra(KEY_POST_DATA) }
    /** When true, finish automatically once CookieManager has a *fresh* cf_clearance for this URL. */
    private val autoCfClearance: Boolean by lazy {
        intent.getBooleanExtra(KEY_AUTO_CF_CLEARANCE, false)
    }
    private val mHandler = Handler(Looper.getMainLooper())
    private var runnable: EvalJsRunnable? = null
    private var finishing = false

    /**
     * Clearance cookie value present when the activity started (often from the in-app browser).
     * Auto-finish only when a *new* value appears — otherwise the page closes blank immediately.
     */
    private var initialClearanceValue: String? = null

    /** At least one non-blank document load completed (avoids finishing on empty about:blank). */
    @Volatile
    private var pageReady = false

    /** Last HTML fingerprint that looked usable; require two stable polls before auto-finish. */
    @Volatile
    private var lastUsableHtmlFingerprint: Int = 0

    @Volatile
    private var stableUsablePolls: Int = 0

    @Volatile
    private var destroyed = false

    /** 当前承载验证的本地标签（不入库，活动销毁即丢弃） */
    private var currentTab: BrowserTab? = null

    /**
     * Only force-close after a capture attempt if we already have usable HTML (or clearance).
     * Never mark finished with empty HTML in CF mode — that produced blank search results.
     */
    private val finishFallback by lazy {
        Runnable {
            if (destroyed) return@Runnable
            val resultKey = intent.getStringExtra(KEY_RESULT)
            val result = resultMap[resultKey]
            val html = result?.html.orEmpty()
            val usable = CloudflareChallengeDetector.isUsableContentHtml(html)
            val cleared = hasFreshCfClearanceCookie()
            if (autoCfClearance && !usable && !cleared) {
                Log.i("WebViewVerificationActivity finishFallback aborted: no usable HTML yet")
                finishing = false
                // Resume polling instead of returning empty to Python.
                runnable?.let { mHandler.postDelayed(it, 800) }
                return@Runnable
            }
            result?.finished = true
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = WeakReference(this)
        // 标记已启动：供 waitForResult 判断 activity 是否真的起来（后台启动受限等场景下不会触发）
        resultMap[intent.getStringExtra(KEY_RESULT)]?.started = true
        enableEdgeToEdge()
        binding = ActivityVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initView()

        if (url.isEmpty()) {
            Log.e("WebViewVerificationActivity No URL provided")
            finish()
            return
        }

        setupVerificationWebView()
    }

    private fun setupVerificationWebView() {
        val cwv = binding.customWebView
        // 人机验证期间禁止用户切换夜间模式（避免触发重建/脚本取消导致验证页退出）
        cwv.nightModeSwitchAllowed = false
        // CF challenge iframes load challenges.cloudflare.com — allow all cross-origin so AdBlock
        // "拒绝" / "询问" modes cannot blank the challenge (browser tab may already be allow-all).
        if (autoCfClearance) {
            cwv.crossOriginOverride = 1
            AdBlock.addWhiteHost("challenges.cloudflare.com", BLOCK_MODE_DOMAIN)
            AdBlock.addWhiteHost("cloudflare.com", BLOCK_MODE_SUBDOMAIN)
        }
        initialClearanceValue = clearanceCookieValue()
        // Start from about:blank so loadInitial does not race a second loadUrl(headers) and leave
        // a cancelled/blank document (common blank-white CF WebView symptom).
        val tab = BrowserTab(url = "about:blank", transient = true)
        currentTab = tab
        cwv.attach(tab, this)
        // CustomWebView 自带工具栏 / 地址栏 / 菜单，与书城浏览器完全统一；生命周期作用域用活动自身
        cwv.init(this, AdBlock)
        cwv.loadInitial()
        val wv = cwv.webView
        applyChallengeWebSettings(wv)
        val post = postData
        when {
            post != null -> wv?.postUrl(url, post)
            else -> {
                // User-Agent must be on WebSettings (navigator.userAgent); additionalHttpHeaders
                // alone are ignored by Chromium for UA and break CF challenges.
                val headers = headerMap.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                if (headers.isEmpty()) wv?.loadUrl(url) else wv?.loadUrl(url, headers)
            }
        }
        runnable = EvalJsRunnable(cwv, url)
        // Delay first poll so the document can paint; avoids finishing on empty HTML.
        mHandler.postDelayed(runnable!!, 1200)
    }

    private fun applyChallengeWebSettings(wv: WebView?) {
        if (wv == null) return
        val ua = headerMap.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (!ua.isNullOrBlank()) {
            wv.settings.userAgentString = ua
        }
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // Algorithmic darkening can blank CF challenge canvases / Turnstile widgets.
        if (autoCfClearance) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
                ) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeActivity?.get() === this) {
            activeActivity = null
        }
        destroyed = true
        runnable?.let { mHandler.removeCallbacks(it) }
        mHandler.removeCallbacks(finishFallback)
        val result = resultMap[intent.getStringExtra(KEY_RESULT)]
        result?.finished = true
        // 销毁 CustomWebView 内的 WebView，避免泄漏（cookie 由全局 CookieManager 保留，不影响会话）
        binding.customWebView.destroyWebView()
    }

    override fun onPause() {
        super.onPause()
        binding.customWebView.webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.customWebView.webView?.onResume()
    }

    private fun initView() {
        if (autoCfClearance) {
            binding.tvTitle.text = "Cloudflare 验证"
            binding.tvDesc.text = "Waiting… 看到书籍正文后点右上角「完成」；验证成功后也会自动关闭"
        }
        binding.btnDone.click {
            attemptFinish(userRequested = true)
        }
        setOnBackPressed {
            if (binding.customWebView.webView?.canGoBack() == true) {
                binding.customWebView.webView?.goBack()
                true
            } else {
                attemptFinish(userRequested = true)
                true
            }
        }
    }

    /** Current cf_clearance cookie value for the challenge URL and the live WebView URL. */
    private fun clearanceCookieValue(): String? {
        return try {
            val cm = CookieManager.getInstance()
            val urls = linkedSetOf(url)
            binding.customWebView.webView?.url?.let { urls.add(it) }
            for (u in urls) {
                if (u.isBlank() || u == "about:blank") continue
                val cookie = cm.getCookie(u) ?: continue
                val value = cookie.split(';').asSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("cf_clearance=", ignoreCase = true) }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (value != null) return value
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True when CookieManager has a cf_clearance that is new since this activity started.
     * Stale browser cookies must not auto-close a blank/unsolved challenge page.
     */
    private fun hasFreshCfClearanceCookie(): Boolean {
        val now = clearanceCookieValue() ?: return false
        return now != initialClearanceValue
    }

    private fun markPageReadyIfNeeded(content: String) {
        if (pageReady) return
        if (content.isBlank()) return
        // Ignore about:blank / empty shells.
        if (content.length < 32 && !content.contains('<', ignoreCase = true)) return
        pageReady = true
    }

    /**
     * Auto-finish when usable post-challenge HTML is stable across polls.
     * Clearance is helpful but not required — Python can parse WebView HTML directly.
     */
    private fun canAutoFinish(content: String): Boolean {
        if (!autoCfClearance) return false
        if (!pageReady) return false
        if (!CloudflareChallengeDetector.isUsableContentHtml(content)) return false
        val fp = content.length xor content.take(200).hashCode() xor content.takeLast(200).hashCode()
        if (fp == lastUsableHtmlFingerprint) {
            stableUsablePolls++
        } else {
            lastUsableHtmlFingerprint = fp
            stableUsablePolls = 1
        }
        return stableUsablePolls >= 2
    }

    private fun pickBestHtml(live: String, cached: String): String {
        val liveOk = CloudflareChallengeDetector.isUsableContentHtml(live)
        val cachedOk = CloudflareChallengeDetector.isUsableContentHtml(cached)
        return when {
            liveOk && cachedOk -> if (live.length >= cached.length) live else cached
            liveOk -> live
            cachedOk -> cached
            live.length >= cached.length -> live
            else -> cached
        }
    }

    /**
     * User「完成」/ back: decide from **live** WebView HTML (not stale poll cache).
     * Auto path calls [finishWithCurrentContent] only after [canAutoFinish].
     */
    private fun attemptFinish(userRequested: Boolean) {
        if (finishing) return
        if (!userRequested) return
        val wv = binding.customWebView.webView
        if (wv == null) {
            finishWithCurrentContent(userForced = true)
            return
        }
        // Small probe first — large outerHTML can be slow/truncated; og/catalog is enough to trust 完成.
        val probeJs = """
            (function(){
              var og = document.querySelector('meta[property="og:novel:book_name"],meta[property="og:novel:author"],meta[property="og:title"]');
              var cat = document.getElementById('catalog') || document.getElementById('catalogs');
              var t = document.title || '';
              var bodyLen = (document.body && document.body.innerHTML) ? document.body.innerHTML.length : 0;
              return JSON.stringify({
                title: t,
                og: og ? (og.getAttribute('content') || '') : '',
                catalog: !!cat,
                url: location.href || '',
                bodyLen: bodyLen
              });
            })();
        """.trimIndent()
        wv.evaluateJavascript(probeJs) { probeRaw ->
            if (destroyed || finishing) return@evaluateJavascript
            val probeJson = parseJsString(probeRaw)
            var pageLooksDone = false
            try {
                val obj = org.json.JSONObject(probeJson.ifBlank { "{}" })
                val title = obj.optString("title")
                val og = obj.optString("og")
                val catalog = obj.optBoolean("catalog")
                val href = obj.optString("url")
                val bodyLen = obj.optInt("bodyLen")
                val titleChallenge = title.contains("just a moment", ignoreCase = true) ||
                    title.contains("attention required", ignoreCase = true)
                pageLooksDone = og.isNotBlank() || catalog ||
                    (!titleChallenge && href.contains("/book/", ignoreCase = true) && bodyLen >= 1500) ||
                    (!titleChallenge && bodyLen >= 3000)
                Log.i(
                    "WebViewVerificationActivity 完成 probe og=${og.isNotBlank()} catalog=$catalog " +
                        "bodyLen=$bodyLen titleChallenge=$titleChallenge done=$pageLooksDone"
                )
            } catch (e: Exception) {
                Log.e("WebViewVerificationActivity 完成 probe parse failed", e)
            }
            val cachedUsable = CloudflareChallengeDetector.isUsableContentHtml(
                resultMap[intent.getStringExtra(KEY_RESULT)]?.html
            )
            val cleared = hasFreshCfClearanceCookie()
            if (!pageLooksDone && !cachedUsable && !cleared) {
                Log.i("WebViewVerificationActivity 完成 ignored: live page not usable yet")
                binding.tvDesc.text = "仍在验证中，请完成勾选或等待正文加载后再点「完成」"
                return@evaluateJavascript
            }
            finishWithCurrentContent(userForced = true)
        }
    }

    /**
     * Capture current WebView HTML and finish. In CF mode, refuse to close with empty/challenge HTML
     * unless [userForced] and we already verified the page looks done via [attemptFinish].
     */
    private fun finishWithCurrentContent(userForced: Boolean = false) {
        if (finishing) return
        finishing = true
        runnable?.let { mHandler.removeCallbacks(it) }
        val resultKey = intent.getStringExtra(KEY_RESULT)
        val wv = binding.customWebView.webView
        if (wv == null) {
            resultMap[resultKey]?.finished = true
            mHandler.removeCallbacks(finishFallback)
            if (!isFinishing && !isDestroyed) finish()
            return
        }
        wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            if (destroyed) return@evaluateJavascript
            val live = parseJsString(raw)
            val cached = resultMap[resultKey]?.html.orEmpty()
            var best = pickBestHtml(live, cached)
            var usable = CloudflareChallengeDetector.isUsableContentHtml(best)
            val cleared = hasFreshCfClearanceCookie()
            if (autoCfClearance && !usable && !cleared && !userForced) {
                Log.i("WebViewVerificationActivity capture not usable yet; keep waiting")
                finishing = false
                mHandler.removeCallbacks(finishFallback)
                runnable?.let { mHandler.postDelayed(it, 800) }
                return@evaluateJavascript
            }
            // Full outerHTML often truncates on large book pages — capture head+catalog only.
            if (autoCfClearance && !usable) {
                val compactJs = """
                    (function(){
                      var head = document.head ? document.head.innerHTML : '';
                      var cat = document.getElementById('catalog') || document.getElementById('catalogs');
                      var catHtml = cat ? cat.outerHTML : '';
                      return '<html><head>'+head+'</head><body>'+catHtml+'</body></html>';
                    })();
                """.trimIndent()
                wv.evaluateJavascript(compactJs) { compactRaw ->
                    if (destroyed) return@evaluateJavascript
                    val compact = parseJsString(compactRaw)
                    best = pickBestHtml(compact, best)
                    usable = CloudflareChallengeDetector.isUsableContentHtml(best)
                    commitFinish(resultKey, best, wv.url)
                }
                return@evaluateJavascript
            }
            commitFinish(resultKey, best, wv.url)
        }
        // Fallback only commits if usable HTML/clearance already present (see finishFallback).
        mHandler.postDelayed(finishFallback, 5000)
    }

    private fun commitFinish(resultKey: String?, html: String, pageUrl: String?) {
        resultMap[resultKey]?.let {
            if (html.isNotEmpty()) {
                it.html = html
                if (!pageUrl.isNullOrBlank()) it.url = pageUrl
            }
            it.finished = true
        }
        mHandler.removeCallbacks(finishFallback)
        if (!isFinishing && !isDestroyed) finish()
    }

    private fun parseJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return StringEscapeUtils.unescapeJson(raw).replace(QUOTE_REGEX, "")
    }

    // -----------------------------------------------------------------------------------------
    // TabHost 桩：验证页只承载单个标签，跨标签动作（新建/管理/退出等）在此按“单标签验证”语义处理。
    // 其中退出类动作等价于“完成验证”；其余多标签动作无操作，避免把验证页变成完整浏览器。
    // -----------------------------------------------------------------------------------------
    override val tabId: String get() = currentTab?.id ?: ""
    override val tabCount: Int get() = 1

    override fun newTab(url: String, transient: Boolean, sourceTabId: String) {
        // CF Turnstile may window.open; keep it in this WebView instead of dropping the popup.
        if (autoCfClearance && url.isNotBlank() && url != "about:blank") {
            binding.customWebView.webView?.loadUrl(url)
        }
    }

    override fun newTabForPopup(sourceTabId: String, popupWebView: WebView) {
        if (!autoCfClearance) return
        // Adopt popup content into the verification WebView by loading its URL if available.
        val popupUrl = popupWebView.url
        if (!popupUrl.isNullOrBlank() && popupUrl != "about:blank") {
            binding.customWebView.webView?.loadUrl(popupUrl)
        }
        runCatching { popupWebView.destroy() }
    }

    override fun closeTab(id: String) {
        // 单标签验证页，忽略
    }

    override fun closeActiveTab(): Boolean {
        // CF mode: do not dump empty results via toolbar/back-to-X — same path as「完成」.
        if (autoCfClearance) {
            attemptFinish(userRequested = true)
        } else {
            finishWithCurrentContent(userForced = true)
        }
        return false
    }

    override fun selectTab(id: String) {
    }

    override fun duplicateTab(id: String) {
    }

    override fun showTabsManager() {
    }

    override fun requestExit() {
        if (autoCfClearance) {
            attemptFinish(userRequested = true)
        } else {
            finishWithCurrentContent(userForced = true)
        }
    }

    override fun activeExit() {
        if (autoCfClearance) {
            attemptFinish(userRequested = true)
        } else {
            finishWithCurrentContent(userForced = true)
        }
    }

    override fun notifyTabChanged() {
    }

    override fun persistTabs() {
    }

    companion object {
        private val QUOTE_REGEX = "^\"|\"$".toRegex()
        private val KEY_URL = "url"
        private val KEY_HEADER_MAP = "header_map"
        private val KEY_POST_DATA = "post_data"
        private val KEY_VERIFICATION_KEY = "VerificationKey"
        private val KEY_AUTO_CF_CLEARANCE = "auto_cf_clearance"
        private val KEY_RESULT = "result"
        private val resultMap = mutableMapOf<String, Result>()

        /** 当前正在运行的验证页实例，脚本任务被取消时用它关掉页面 */
        @Volatile
        private var activeActivity: WeakReference<WebViewVerificationActivity>? = null

        /** 同一时刻只允许一个验证页运行；后续请求在锁上排队，等前一个 activity 完成后再启动。 */
        private val verificationLock = Any()

        /** activity 启动兜底超时：若届时 activity 仍未起来（如后台启动受限），放行后续请求，避免永久阻塞。 */
        private const val VERIFICATION_START_TIMEOUT_MS = 15 * 1000L

        fun postAndWaitResult(
            url: String,
            postData: String,
            verificationKey: String = "",
            autoCfClearance: Boolean = false,
        ): String? =
            synchronized(verificationLock) {
                Log.i("WebViewVerificationActivity postAndWaitResult URL: $url, postData: $postData")
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    throw IllegalStateException("startAndWaitResult 必须在子线程中调用")
                }
                // 调用前拦截：脚本任务已取消时不再弹出验证页，直接抛取消异常
                ScriptTaskRegistry.throwIfCurrentCancelled()
                val keyResult = UUID.randomUUID().toString()
                val intent = Intent(App.app, WebViewVerificationActivity::class.java).apply {
                    putExtra(KEY_URL, url)
                    putExtra(KEY_POST_DATA, postData.toByteArray())
                    putExtra(KEY_RESULT, keyResult)
                    putExtra(KEY_VERIFICATION_KEY, verificationKey)
                    putExtra(KEY_AUTO_CF_CLEARANCE, autoCfClearance)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val result = Result(url)
                resultMap[keyResult] = result
                GlobalScope.launch(Dispatchers.Main) {
                    // 等待开始前就被取消（例如脚本任务已取消）：不要再弹出验证页
                    if (result.finished) return@launch
                    Log.i("WebViewVerificationActivity start for $url")
                    App.app.startActivity(intent)
                }
                waitForResult(result, url)
                resultMap.remove(keyResult)
                Log.i("WebViewVerificationActivity result for $url is done")
                gson.toJson(Response(result.url, result.html))
            }

        @JvmStatic
        @JvmOverloads
        fun startAndWaitResult(
            url: String,
            headerMap: Map<String, String> = mapOf(),
            verificationKey: String = "",
            autoCfClearance: Boolean = false,
        ): String? =
            synchronized(verificationLock) {
                Log.i("WebViewVerificationActivity startAndWaitResult URL: $url, headerMap: $headerMap, autoCf=$autoCfClearance")
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    throw IllegalStateException("startAndWaitResult 必须在子线程中调用")
                }
                // 调用前拦截：脚本任务已取消时不再弹出验证页，直接抛取消异常
                ScriptTaskRegistry.throwIfCurrentCancelled()
                val keyResult = UUID.randomUUID().toString()
                val intent = Intent(App.app, WebViewVerificationActivity::class.java).apply {
                    putExtra(KEY_URL, url)
                    putExtra(KEY_HEADER_MAP, HashMap(headerMap))
                    putExtra(KEY_RESULT, keyResult)
                    putExtra(KEY_VERIFICATION_KEY, verificationKey)
                    putExtra(KEY_AUTO_CF_CLEARANCE, autoCfClearance)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val result = Result(url)
                resultMap[keyResult] = result
                GlobalScope.launch(Dispatchers.Main) {
                    // 等待开始前就被取消（例如脚本任务已取消）：不要再弹出验证页
                    if (result.finished) return@launch
                    Log.i("WebViewVerificationActivity start for $url")
                    App.app.startActivity(intent)
                }
                waitForResult(result, url)
                resultMap.remove(keyResult)
                Log.i("WebViewVerificationActivity result for $url is done")
                gson.toJson(Response(result.url, result.html))
            }

        /**
         * 阻塞等待验证页完成。
         * - activity 起来后（[Result.started]）不限时等待用户完成；
         * - activity 迟迟未起（后台启动受限等），超过 [VERIFICATION_START_TIMEOUT_MS] 放行，避免永久阻塞后续请求；
         * - 脚本任务被取消（例如退出阅读页）时立刻结束等待、关掉验证页并抛取消异常，让脚本退出。
         */
        private fun waitForResult(result: Result, url: String) {
            val cancelAction: () -> Unit = {
                Log.i("WebViewVerificationActivity cancelled for $url")
                result.cancelled = true
                result.finished = true
                Handler(Looper.getMainLooper()).post {
                    activeActivity?.get()?.let { activity ->
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.finish()
                        }
                    }
                }
            }
            val task = ScriptTaskRegistry.current()
            task?.addCancelAction(cancelAction)
            try {
                val startDeadline = System.currentTimeMillis() + VERIFICATION_START_TIMEOUT_MS
                while (!result.finished) {
                    if (!result.started && System.currentTimeMillis() > startDeadline) {
                        Log.e("WebViewVerificationActivity not started within timeout for $url")
                        break
                    }
                    Thread.sleep(500)
                }
            } finally {
                task?.removeCancelAction(cancelAction)
            }
            if (result.cancelled) {
                throw ScriptTaskCancelledException()
            }
        }

    }

    private inner class EvalJsRunnable(
        private val customWebView: CustomWebView,
        private val url: String,
        private val mJavaScript: String = "document.documentElement.outerHTML"
    ) : Runnable {
        var retry = 0

        override fun run() {
            if (destroyed || finishing) return
            // Never auto-finish from cookie alone — need usable HTML confirmed in handleResult.
            customWebView.webView?.evaluateJavascript(mJavaScript) {
                handleResult(it)
            }
        }

        private fun handleResult(result: String) = GlobalScope.launch {
            if (destroyed || finishing) return@launch
            if (!result.isBlank() && result != "null") {
                val content = parseJsString(result)
                markPageReadyIfNeeded(content)
                Log.i(
                    "js执行结果获取成功：$url ready=$pageReady len=${content.length} " +
                        "clearance=${hasFreshCfClearanceCookie()} usable=${CloudflareChallengeDetector.isUsableContentHtml(content)}"
                )
                val resultKey = intent.getStringExtra(KEY_RESULT)
                val res = resultMap[resultKey]
                res?.url = customWebView.webView?.url ?: url
                res?.html = content
                val verificationKey: String by lazy { intent.getStringExtra(KEY_VERIFICATION_KEY) ?: "" }
                if (verificationKey.isNotBlank() && content.contains(verificationKey)) {
                    res?.finished = true
                    mHandler.post { finish() }
                    return@launch
                }
                if (autoCfClearance && canAutoFinish(content)) {
                    Log.i("WebViewVerificationActivity auto-finish: stable usable HTML")
                    mHandler.post { finishWithCurrentContent(userForced = false) }
                    return@launch
                }
                // Keep polling in CF mode until clearance + usable HTML, or user taps 完成.
                if (autoCfClearance && !isDestroyed && !destroyed && !finishing) {
                    retry++
                    mHandler.postDelayed(this@EvalJsRunnable, 800)
                }
                return@launch
            }
            if (isDestroyed || destroyed || finishing) {
                return@launch
            }
            retry++
            mHandler.postDelayed(this@EvalJsRunnable, 1000)
        }

    }

    class Response(val url: String, val html: String)
    class Result(var url: String) {
        var html: String = ""

        @Volatile
        var finished: Boolean = false

        @Volatile
        var started: Boolean = false

        /** 是否因为脚本任务被取消而结束 */
        @Volatile
        var cancelled: Boolean = false
    }

}
