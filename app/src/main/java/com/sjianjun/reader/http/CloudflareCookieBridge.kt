package com.sjianjun.reader.http

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.sjianjun.reader.App
import com.sjianjun.reader.module.verification.WebViewVerificationActivity
import com.sjianjun.reader.python.ScriptTaskCancelledException
import com.sjianjun.reader.python.ScriptTaskRegistry
import com.sjianjun.reader.utils.DESKTOP_USER_AGENT
import com.sjianjun.reader.utils.webUserAgent
import sjj.alog.Log
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bridges Android WebView Cloudflare clearance into Python `requests`.
 *
 * Flow:
 * 1. Reuse cached cookies + User-Agent for a host until expiry / next challenge.
 * 2. Prefer cookies already present in the shared [CookieManager] (in-app browser / prior solve).
 * 3. Only when a challenge is detected (or cookies missing) open
 *    [WebViewVerificationActivity] so interactive Turnstile can be completed by the user.
 * 4. Return cookie + UA together — clearance is bound to the UA that earned it.
 * 5. Also return the WebView HTML for the challenged URL when it is no longer a CF page,
 *    because Python `requests` often still fails CF after clearance (TLS fingerprint).
 */
object CloudflareCookieBridge {

    /** Default reuse window when CookieManager does not expose Max-Age (cf_clearance often ~30m). */
    private val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(25)

    private val CF_COOKIE_PREFIXES = arrayOf("cf_clearance", "cf_chl", "__cf_bm", "cf_chl_rc")

    private val cache = ConcurrentHashMap<String, Credentials>()
    private val hostLocks = ConcurrentHashMap<String, Any>()

    data class Credentials(
        val cookie: String,
        val userAgent: String,
        val expiresAtMs: Long,
        val host: String,
        /** Optional HTML captured from the CF WebView after clearance (avoids brittle TLS retries). */
        val html: String = "",
        val htmlUrl: String = "",
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAtMs

        /** Map shape expected by Python (Chaquopy). */
        fun toMap(includeHtml: Boolean = false): Map<String, String> {
            val out = linkedMapOf(
                "cookie" to cookie,
                "userAgent" to userAgent,
                "host" to host,
                "expiresAtMs" to expiresAtMs.toString(),
            )
            // HTML is only for the request that just finished CF WebView — never reuse from cache
            // (would serve a book page body for a later toc/chapter URL).
            if (includeHtml && html.isNotBlank()) {
                out["html"] = html
                out["htmlUrl"] = htmlUrl.ifBlank { "https://$host/" }
            }
            return out
        }
    }

    /**
     * Returns cached credentials for [url]'s host if present and not expired.
     * Does not open a WebView.
     */
    @JvmStatic
    fun getCachedCredentials(url: String): Map<String, String>? {
        val host = hostOf(url) ?: return null
        val creds = cache[host] ?: return null
        if (creds.isExpired()) {
            cache.remove(host, creds)
            return null
        }
        return creds.toMap(includeHtml = false)
    }

    /**
     * Reads [CookieManager] for [url] (and www / bare twin) without showing UI.
     * Updates cache when clearance is found.
     *
     * @param preferredUserAgent UA to associate with cookies (must match later Python requests).
     */
    @JvmStatic
    @JvmOverloads
    fun refreshFromCookieManager(url: String, preferredUserAgent: String? = null): Map<String, String>? {
        val host = hostOf(url) ?: return null
        val cookie = mergeCookiesForHost(url) ?: return null
        if (!CloudflareChallengeDetector.hasClearanceCookie(cookie)) {
            return null
        }
        // Clearance is bound to the WebView UA that earned it — never pair CookieManager
        // cookies with a book-source mobile UA (that makes CF reject the Python retry).
        val ua = defaultWebViewUserAgent()
        if (!preferredUserAgent.isNullOrBlank() && preferredUserAgent != ua) {
            Log.i("CloudflareCookieBridge ignoring source UA for CookieManager clearance")
        }
        val creds = Credentials(
            cookie = cookie,
            userAgent = ua,
            expiresAtMs = System.currentTimeMillis() + DEFAULT_TTL_MS,
            host = host,
        )
        cache[host] = creds
        // Mirror cache under the twin host (www ↔ bare) so either form reuses clearance.
        twinHost(host)?.let { twin -> cache.putIfAbsent(twin, creds.copy(host = twin)) }
        Log.i("CloudflareCookieBridge refreshed cookies for $host")
        return creds.toMap(includeHtml = false)
    }

    /**
     * Ensures usable credentials for [url]: cache → CookieManager → WebView challenge UI.
     * Must be called off the main thread (blocks while the verification activity runs).
     */
    @JvmStatic
    @JvmOverloads
    fun ensureCredentials(
        url: String,
        headers: Map<String, String>? = null,
        forceSolve: Boolean = false,
    ): Map<String, String>? {
        ScriptTaskRegistry.throwIfCurrentCancelled()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("ensureCredentials 必须在子线程中调用")
        }
        val host = hostOf(url) ?: return null
        val lock = hostLocks.getOrPut(host) { Any() }
        synchronized(lock) {
            if (!forceSolve) {
                getCachedCredentials(url)?.let { return it }
                twinHost(host)?.let { getCachedCredentials("https://$it/") }?.let { return it }
                val uaHint = headerIgnoreCase(headers, "User-Agent")
                refreshFromCookieManager(url, uaHint)?.let { return it }
            } else {
                cache.remove(host)
                twinHost(host)?.let { cache.remove(it) }
                // Drop stale clearance from the in-app browser so autoCfClearance waits for a
                // fresh cookie earned with this WebView UA (avoids blank instant-close).
                clearClearanceCookies(url)
            }
            return solveViaWebView(url, headers ?: emptyMap())
        }
    }

    /** Drop cached credentials for a host (e.g. after a failed retry). */
    @JvmStatic
    fun clearHost(hostOrUrl: String) {
        val host = hostOf(hostOrUrl) ?: hostOrUrl.lowercase()
        cache.remove(host)
        twinHost(host)?.let { cache.remove(it) }
        Log.i("CloudflareCookieBridge cleared cache for $host")
    }

    @JvmStatic
    fun clearAll() {
        cache.clear()
    }

    /**
     * Detect challenge from Python-friendly primitives (status, header map, body snippet).
     */
    @JvmStatic
    fun isChallenge(statusCode: Int, headers: Map<String, String>?, bodySnippet: String?): Boolean {
        return CloudflareChallengeDetector.isChallenge(statusCode, headers, bodySnippet)
    }

    /**
     * Expire CF clearance / challenge cookies for [url]'s host and www/bare twin.
     * Safe to call off the main thread (posts CookieManager work to main when needed).
     */
    @JvmStatic
    fun clearClearanceCookies(url: String) {
        val origins = cookieOrigins(url)
        if (origins.isEmpty()) return
        val work = Runnable {
            try {
                val cm = CookieManager.getInstance()
                for (origin in origins) {
                    val existing = cm.getCookie(origin) ?: continue
                    for (part in existing.split(';')) {
                        val name = part.substringBefore('=', missingDelimiterValue = "").trim()
                        if (name.isEmpty()) continue
                        if (CF_COOKIE_PREFIXES.any { name.equals(it, true) || name.startsWith(it) }) {
                            cm.setCookie(origin, "$name=; Max-Age=0; Path=/")
                        }
                    }
                }
                cm.flush()
            } catch (e: Exception) {
                Log.e("CloudflareCookieBridge clearClearanceCookies failed", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work.run()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    work.run()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(3, TimeUnit.SECONDS)
        }
    }

    private fun solveViaWebView(url: String, headers: Map<String, String>): Map<String, String>? {
        ScriptTaskRegistry.throwIfCurrentCancelled()
        val host = hostOf(url) ?: return null
        // Prefer system/WebView UA for solving — source UAs often mismatch WebView chrome and
        // cause blank CF pages. Store the UA that actually earned clearance for Python retry.
        val requestedUa = headerIgnoreCase(headers, "User-Agent")
        val ua = defaultWebViewUserAgent()
        val webHeaders = HashMap<String, String>()
        // Keep Accept-Language etc. if present, but force the WebView UA.
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Cookie", ignoreCase = true)) {
                webHeaders[k] = v
            }
        }
        webHeaders["User-Agent"] = ua
        if (requestedUa != null && requestedUa != ua) {
            Log.i("CloudflareCookieBridge using WebView UA instead of source UA for CF solve")
        }

        // Load the challenged URL itself (book page / search) so after Turnstile the WebView
        // lands on real content we can hand back to Python — requests TLS often still fails CF.
        val challengeUrl = url

        Log.i("CloudflareCookieBridge opening WebView for CF challenge: $challengeUrl")
        var pageHtml = ""
        var pageUrl = challengeUrl
        try {
            val json = WebViewVerificationActivity.startAndWaitResult(
                url = challengeUrl,
                headerMap = webHeaders,
                verificationKey = "",
                autoCfClearance = true,
            )
            parseWebViewResult(json)?.let { (finalUrl, html) ->
                pageUrl = finalUrl.ifBlank { challengeUrl }
                pageHtml = html
            }
        } catch (e: ScriptTaskCancelledException) {
            throw e
        } catch (e: Exception) {
            Log.e("CloudflareCookieBridge WebView solve failed for $host", e)
        }

        flushCookies()

        val cookie = mergeCookiesForHost(url) ?: mergeCookiesForHost(challengeUrl) ?: ""
        val hasClearance = CloudflareChallengeDetector.hasClearanceCookie(cookie)
        val usableHtml = pageHtml.takeIf { html ->
            CloudflareChallengeDetector.isUsableContentHtml(html)
        }.orEmpty()

        // Prefer usable WebView HTML for getDetails even if clearance is still settling —
        // previously returning null here discarded a fully loaded book page and left search blank.
        if (!hasClearance && usableHtml.isEmpty()) {
            Log.e("CloudflareCookieBridge no cf_clearance and no usable HTML after WebView for $host")
            return null
        }
        if (!hasClearance) {
            Log.i("CloudflareCookieBridge using WebView HTML without cf_clearance for $host")
        }

        val creds = Credentials(
            cookie = cookie,
            userAgent = ua,
            expiresAtMs = System.currentTimeMillis() + DEFAULT_TTL_MS,
            host = host,
            html = usableHtml,
            htmlUrl = pageUrl,
        )
        if (hasClearance) {
            cache[host] = creds
            twinHost(host)?.let { twin -> cache[twin] = creds.copy(host = twin) }
        }
        Log.i(
            "CloudflareCookieBridge solved CF for $host clearance=$hasClearance " +
                "htmlLen=${usableHtml.length} pageUrl=$pageUrl"
        )
        // includeHtml=true only on the live solve path (not cache hits).
        return creds.toMap(includeHtml = true)
    }

    private fun parseWebViewResult(json: String?): Pair<String, String>? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = org.json.JSONObject(json)
            val html = obj.optString("html", "")
            val finalUrl = obj.optString("url", "")
            if (html.isBlank()) null else finalUrl to html
        } catch (e: Exception) {
            Log.e("CloudflareCookieBridge parse WebView JSON failed", e)
            null
        }
    }

    private fun mergeCookiesForHost(url: String): String? {
        val merged = LinkedHashMap<String, String>()
        for (origin in cookieOrigins(url)) {
            val raw = safeGetCookie(origin) ?: continue
            for (part in raw.split(';')) {
                val trimmed = part.trim()
                if (trimmed.isEmpty() || '=' !in trimmed) continue
                val name = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim()
                if (name.isNotEmpty()) merged[name] = value
            }
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun cookieOrigins(url: String): List<String> {
        val host = hostOf(url) ?: return emptyList()
        val origins = LinkedHashSet<String>()
        origins.add(url)
        origins.add("https://$host/")
        origins.add("http://$host/")
        twinHost(host)?.let { twin ->
            origins.add("https://$twin/")
            origins.add("http://$twin/")
        }
        return origins.toList()
    }

    private fun twinHost(host: String): String? {
        return when {
            host.startsWith("www.") && host.length > 4 -> host.removePrefix("www.")
            '.' in host -> "www.$host"
            else -> null
        }
    }

    private fun flushCookies() {
        try {
            val cm = CookieManager.getInstance()
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cm.flush()
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    try {
                        cm.flush()
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(2, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            Log.e("CloudflareCookieBridge flush cookies failed", e)
        }
    }

    private fun safeGetCookie(url: String): String? {
        return try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            Log.e("CloudflareCookieBridge getCookie failed", e)
            null
        }
    }

    private fun defaultWebViewUserAgent(): String {
        // Match the WebView that will earn cf_clearance (desktop mode if user enabled it).
        webUserAgent()?.let { return it }
        return try {
            WebSettings.getDefaultUserAgent(App.app)
        } catch (_: Exception) {
            DESKTOP_USER_AGENT
        }
    }

    private fun headerIgnoreCase(headers: Map<String, String>?, name: String): String? {
        if (headers.isNullOrEmpty()) return null
        for ((k, v) in headers) {
            if (k.equals(name, ignoreCase = true) && v.isNotBlank()) return v
        }
        return null
    }

    private fun hostOf(url: String): String? {
        return try {
            val uri = URI(url)
            uri.host?.lowercase()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
