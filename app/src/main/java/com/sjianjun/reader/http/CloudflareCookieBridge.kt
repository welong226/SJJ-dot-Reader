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
 */
object CloudflareCookieBridge {

    /** Default reuse window when CookieManager does not expose Max-Age (cf_clearance often ~30m). */
    private val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(25)

    private val cache = ConcurrentHashMap<String, Credentials>()
    private val hostLocks = ConcurrentHashMap<String, Any>()

    data class Credentials(
        val cookie: String,
        val userAgent: String,
        val expiresAtMs: Long,
        val host: String,
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAtMs

        /** Map shape expected by Python (Chaquopy). */
        fun toMap(): Map<String, String> = mapOf(
            "cookie" to cookie,
            "userAgent" to userAgent,
            "host" to host,
            "expiresAtMs" to expiresAtMs.toString(),
        )
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
        return creds.toMap()
    }

    /**
     * Reads [CookieManager] for [url] without showing UI. Updates cache when clearance is found.
     *
     * @param preferredUserAgent UA to associate with cookies (must match later Python requests).
     */
    @JvmStatic
    @JvmOverloads
    fun refreshFromCookieManager(url: String, preferredUserAgent: String? = null): Map<String, String>? {
        val host = hostOf(url) ?: return null
        val cookie = safeGetCookie(url) ?: return null
        if (!CloudflareChallengeDetector.hasClearanceCookie(cookie)) {
            return null
        }
        val ua = preferredUserAgent?.takeIf { it.isNotBlank() } ?: defaultWebViewUserAgent()
        val creds = Credentials(
            cookie = cookie,
            userAgent = ua,
            expiresAtMs = System.currentTimeMillis() + DEFAULT_TTL_MS,
            host = host,
        )
        cache[host] = creds
        Log.i("CloudflareCookieBridge refreshed cookies for $host")
        return creds.toMap()
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
                val uaHint = headerIgnoreCase(headers, "User-Agent")
                refreshFromCookieManager(url, uaHint)?.let { return it }
            } else {
                cache.remove(host)
            }
            return solveViaWebView(url, headers ?: emptyMap())
        }
    }

    /** Drop cached credentials for a host (e.g. after a failed retry). */
    @JvmStatic
    fun clearHost(hostOrUrl: String) {
        val host = hostOf(hostOrUrl) ?: hostOrUrl.lowercase()
        cache.remove(host)
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

    private fun solveViaWebView(url: String, headers: Map<String, String>): Map<String, String>? {
        ScriptTaskRegistry.throwIfCurrentCancelled()
        val host = hostOf(url) ?: return null
        val ua = headerIgnoreCase(headers, "User-Agent") ?: defaultWebViewUserAgent()
        val webHeaders = HashMap(headers)
        webHeaders["User-Agent"] = ua

        Log.i("CloudflareCookieBridge opening WebView for CF challenge: $url")
        try {
            // verificationKey empty + autoCfClearance: activity finishes when cf_clearance appears,
            // or when the user taps 完成 after an interactive Turnstile.
            WebViewVerificationActivity.startAndWaitResult(
                url = url,
                headerMap = webHeaders,
                verificationKey = "",
                autoCfClearance = true,
            )
        } catch (e: ScriptTaskCancelledException) {
            throw e
        } catch (e: Exception) {
            Log.e("CloudflareCookieBridge WebView solve failed for $host", e)
        }

        // Persist cookies from WebView immediately.
        flushCookies()

        val cookie = safeGetCookie(url)
        if (cookie.isNullOrBlank() || !CloudflareChallengeDetector.hasClearanceCookie(cookie)) {
            Log.e("CloudflareCookieBridge no cf_clearance after WebView for $host")
            return null
        }
        val creds = Credentials(
            cookie = cookie,
            userAgent = ua,
            expiresAtMs = System.currentTimeMillis() + DEFAULT_TTL_MS,
            host = host,
        )
        cache[host] = creds
        Log.i("CloudflareCookieBridge solved CF for $host")
        return creds.toMap()
    }

    private fun flushCookies() {
        try {
            val cm = CookieManager.getInstance()
            // flush is async on older APIs; post to main and briefly yield
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
        // Prefer app desktop UA setting; otherwise a stable Chrome desktop string so
        // Python requests match the WebView that earned cf_clearance.
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
