package com.sjianjun.reader.http

/**
 * Detects Cloudflare challenge / interstitial pages from HTTP status, headers, and HTML snippets.
 * Used by the Python requests hook (via [CloudflareCookieBridge]) so book sources do not need
 * per-site CF handling.
 */
object CloudflareChallengeDetector {

    private val SERVER_MARKERS = arrayOf("cloudflare", "cloudflare-nginx")

    private val BODY_MARKERS = arrayOf(
        "cf-browser-verification",
        "cf_chl_",
        "challenge-platform",
        "just a moment",
        "attention required! | cloudflare",
        "checking your browser",
        "enable javascript and cookies to continue",
        "cf-challenge",
        "_cf_chl_opt",
        "cdn-cgi/challenge-platform",
        "managed_challenge",
        "turnstile",
        "challenge-error-title",
        "challenge-error-text",
    )

    /**
     * @param statusCode HTTP status (403/503 are typical for CF challenges)
     * @param headers response headers (case-insensitive keys preferred but not required)
     * @param bodySnippet first few KB of response body (may be empty)
     */
    @JvmStatic
    fun isChallenge(statusCode: Int, headers: Map<String, String>?, bodySnippet: String?): Boolean {
        val headerMap = normalizeHeaders(headers)
        val cfMitigated = headerMap["cf-mitigated"]
        if (cfMitigated.equals("challenge", ignoreCase = true)) {
            return true
        }

        val body = bodySnippet.orEmpty()
        val bodyLooksLikeChallenge = BODY_MARKERS.any { body.contains(it, ignoreCase = true) }
        val server = headerMap["server"].orEmpty()
        val looksLikeCfServer = SERVER_MARKERS.any { server.contains(it, ignoreCase = true) }

        // 403/503 + challenge HTML (or CF server + strong interstitial markers).
        // Do not treat every Cloudflare-fronted 403 as a challenge — that would spam WebView.
        if (statusCode in CHALLENGE_STATUS_CODES && bodyLooksLikeChallenge) {
            return true
        }

        // Some CF setups return 200 with an interstitial; require strong body markers.
        if (bodyLooksLikeChallenge && (
                body.contains("cf-browser-verification", ignoreCase = true) ||
                    body.contains("_cf_chl_opt", ignoreCase = true) ||
                    body.contains("cdn-cgi/challenge-platform", ignoreCase = true) ||
                    (looksLikeCfServer && body.contains("just a moment", ignoreCase = true))
                )
        ) {
            return true
        }

        return false
    }

    /** True when [cookieHeader] already contains a usable cf_clearance cookie. */
    @JvmStatic
    fun hasClearanceCookie(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        return cookieHeader.split(';').any { part ->
            val name = part.substringBefore('=', missingDelimiterValue = "").trim()
            name.equals("cf_clearance", ignoreCase = true) &&
                part.substringAfter('=', missingDelimiterValue = "").trim().isNotEmpty()
        }
    }

    private fun normalizeHeaders(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, String>(headers.size)
        for ((k, v) in headers) {
            out[k.lowercase()] = v
        }
        return out
    }

    private val CHALLENGE_STATUS_CODES = intArrayOf(403, 503)
}
