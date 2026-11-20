package com.sjianjun.reader.http

/**
 * Detects Cloudflare challenge / interstitial pages from HTTP status, headers, and HTML snippets.
 * Used by the Python requests hook (via [CloudflareCookieBridge]) so book sources do not need
 * per-site CF handling.
 *
 * 69shuba and similar sites often return 403 with `cf-mitigated: challenge`, `Server: cloudflare`,
 * and a "Just a moment..." body. Bare 403s that still carry CF signals must open the WebView —
 * waiting for rich HTML alone previously let [raise_for_status] fail the user with no UI.
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
        "cdn-cgi/challenge",
        "challenges.cloudflare.com",
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
        val hasCfRay = !headerMap["cf-ray"].isNullOrBlank()
        val looksLikeCf = looksLikeCfServer || hasCfRay ||
            headerMap.containsKey("cf-mitigated") ||
            headerMap.keys.any { it.startsWith("cf-") }

        // Official / classic challenge pages: 403/503 with CF signals and/or challenge HTML.
        if (statusCode in CHALLENGE_STATUS_CODES) {
            if (bodyLooksLikeChallenge) return true
            // Bare 403/503 from Cloudflare (headers only, empty/opaque body) — still open WebView.
            if (looksLikeCf) return true
        }

        // Some CF setups return 200 with an interstitial; require strong body markers.
        if (bodyLooksLikeChallenge && (
                body.contains("cf-browser-verification", ignoreCase = true) ||
                    body.contains("_cf_chl_opt", ignoreCase = true) ||
                    body.contains("cdn-cgi/challenge-platform", ignoreCase = true) ||
                    body.contains("challenges.cloudflare.com", ignoreCase = true) ||
                    (looksLikeCf && body.contains("just a moment", ignoreCase = true))
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
