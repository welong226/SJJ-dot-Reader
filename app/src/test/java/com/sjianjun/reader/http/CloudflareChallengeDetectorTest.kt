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
    fun bareCloudflare403OpensChallenge() {
        // 69shuba-style: CF signals in headers even when body is empty/opaque.
        assertTrue(
            CloudflareChallengeDetector.isChallenge(
                403,
                mapOf("server" to "cloudflare", "cf-ray" to "abc123-IAD"),
                "",
            )
        )
        assertTrue(
            CloudflareChallengeDetector.isChallenge(
                403,
                mapOf("Cf-Mitigated" to "challenge", "Server" to "cloudflare"),
                "<html><title>Just a moment...</title></html>",
            )
        )
    }

    @Test
    fun ignoresNonCloudflareForbidden() {
        assertFalse(
            CloudflareChallengeDetector.isChallenge(
                403,
                mapOf("server" to "nginx"),
                "<html><body>Forbidden</body></html>",
            )
        )
    }

    @Test
    fun usableContentHtmlRequiresBookSignals() {
        assertFalse(
            CloudflareChallengeDetector.isUsableContentHtml(
                "<html><title>Just a moment...</title><script>window._cf_chl_opt={}</script></html>",
            )
        )
        assertTrue(
            CloudflareChallengeDetector.isUsableContentHtml(
                """
                <html><head>
                <meta property="og:novel:book_name" content="撈屍人"/>
                <meta property="og:novel:author" content="某人"/>
                </head><body><div id="catalog"><ul><li><a href="/1">c1</a></li></ul></div></body></html>
                """.trimIndent(),
            )
        )
        assertTrue(
            CloudflareChallengeDetector.isUsableContentHtml(
                "<html><head><title>撈屍人</title></head><body>" +
                    ("章节目录 /book/83216/ ".repeat(200)) +
                    "</body></html>",
            )
        )
    }
}
