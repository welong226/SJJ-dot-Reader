package com.sjianjun.reader.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareChallengeDetectorTest {

    @Test
    fun detectsCfMitigatedHeader() {
        val headers = mapOf(
            "Server" to "cloudflare",
            "cf-mitigated" to "challenge",
        )
        assertTrue(CloudflareChallengeDetector.isChallenge(403, headers, ""))
    }

    @Test
    fun detectsClassicChallengeHtml() {
        val body = """
            <html><title>Just a moment...</title>
            <script>window._cf_chl_opt={}</script>
            <div id="challenge-error-title">Attention Required! | Cloudflare</div>
            </html>
        """.trimIndent()
        assertTrue(
            CloudflareChallengeDetector.isChallenge(
                503,
                mapOf("server" to "cloudflare"),
                body,
            )
        )
    }

    @Test
    fun ignoresNormalForbiddenPage() {
        assertFalse(
            CloudflareChallengeDetector.isChallenge(
                403,
                mapOf("server" to "nginx"),
                "<html><body>Forbidden</body></html>",
            )
        )
    }

    @Test
    fun ignoresSuccessfulPage() {
        assertFalse(
            CloudflareChallengeDetector.isChallenge(
                200,
                mapOf("server" to "cloudflare"),
                "<html><body>小说正文</body></html>",
            )
        )
    }

    @Test
    fun hasClearanceCookie() {
        assertTrue(
            CloudflareChallengeDetector.hasClearanceCookie(
                "session=abc; cf_clearance=xyz.123; other=1"
            )
        )
        assertFalse(CloudflareChallengeDetector.hasClearanceCookie("session=abc; other=1"))
        assertFalse(CloudflareChallengeDetector.hasClearanceCookie(null))
        assertFalse(CloudflareChallengeDetector.hasClearanceCookie("cf_clearance="))
    }

    @Test
    fun ignoresGenericCloudflareForbiddenWithoutChallengeBody() {
        // Must not open WebView for every CF-fronted 403 (would blank/spam verification).
        assertFalse(
            CloudflareChallengeDetector.isChallenge(
                403,
                mapOf("server" to "cloudflare"),
                "<html><body>Forbidden</body></html>",
            )
        )
    }
}
