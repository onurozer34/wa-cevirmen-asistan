package com.example.waevirmenasistan

import com.example.waevirmenasistan.accessibility.WhatsAppNodeHelper
import com.example.waevirmenasistan.data.AiProvider
import com.example.waevirmenasistan.data.AppSettings
import com.example.waevirmenasistan.engine.LanguageMap
import com.example.waevirmenasistan.engine.LlmJsonParser
import com.example.waevirmenasistan.engine.TranslationCoordinator
import com.example.waevirmenasistan.engine.WebsiteKnowledgeFetcher
import com.example.waevirmenasistan.update.GitHubUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineUnitTest {

    @Test
    fun `language map resolves Turkish names`() {
        assertEquals("Almanca", LanguageMap.getNameTr("de"))
        assertEquals("İngilizce", LanguageMap.getNameTr("en-US"))
        assertEquals("Arapça", LanguageMap.getNameTr("ar"))
    }

    @Test
    fun `llm json parser extracts markdown wrapped json`() {
        val raw = """
            ```json
            {
              "detectedLang": "de",
              "languageNameTr": "Almanca",
              "turkishTranslation": "Cumartesi müsait misiniz?",
              "replyInCustomerLanguage": "Ja, am Samstag sind wir um 15:00 Uhr frei.",
              "replyTurkishMeaning": "Evet, Cumartesi saat 15:00'te müsaitiz."
            }
            ```
        """.trimIndent()
        val parsed = LlmJsonParser.parseOutcome(raw, "Gemini (gemini-3.5-flash-lite)")
        assertEquals("de", parsed.detectedLang)
        assertEquals("Almanca", parsed.languageNameTr)
        assertTrue(parsed.replyInCustomerLanguage.contains("15:00"))
    }

    @Test
    fun `app settings validates api key presence`() {
        val noKey = AppSettings(provider = AiProvider.GEMINI, apiKey = "")
        assertFalse(noKey.hasApiKey())
        assertEquals("API Yok", noKey.activeBadgeLabel())

        val withKey = AppSettings(provider = AiProvider.GEMINI, apiKey = "AIzaSyTestKey123")
        assertTrue(withKey.hasApiKey())
        assertEquals("Google", withKey.activeBadgeLabel())
    }

    @Test
    fun `coordinator strictly requires api key without free fallback`() {
        val coordinator = TranslationCoordinator()
        val settings = AppSettings(apiKey = "")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            coordinator.translateAndReply(
                settings = settings,
                messageText = "Hello",
                customerName = "John",
                chatContext = emptyList()
            )
        }
        assertTrue(ex.message!!.contains("API anahtarı"))
    }

    @Test
    fun `website knowledge fetcher extracts clean pricing text from html`() {
        val html = """
            <html><body>
            <nav>Home Pricing Contact</nav>
            <main>
              <h1>Pricing List</h1>
              <p>Flying Dress Photo Shooting &euro;150 per hour</p>
              <script>console.log('ignore');</script>
            </main>
            <footer>Copyright</footer>
            </body></html>
        """.trimIndent()
        val text = WebsiteKnowledgeFetcher.extractCleanText(html)
        assertTrue(text.contains("Pricing List"))
        assertTrue(text.contains("€150 per hour"))
        assertFalse(text.contains("console.log"))
    }

    @Test
    fun `website knowledge fetcher discovers internal pricing and faq urls`() {
        val html = """
            <a href="/pricing/">Pricing</a>
            <a href="/tr/pricing/">TR Pricing</a>
            <a href="/booking/">Booking</a>
            <a href="https://external.com/pricing/">External</a>
        """.trimIndent()
        val urls = WebsiteKnowledgeFetcher.extractInternalInfoUrls("https://www.istanbulphotoshoot.com/", html)
        assertEquals(listOf(
            "https://www.istanbulphotoshoot.com/pricing/",
            "https://www.istanbulphotoshoot.com/booking/"
        ), urls)
    }

    @Test
    fun `flood grouping merges unbroken customer burst and stops at own reply`() {
        val turns = listOf(
            false to "Old question",
            true to "Sure, how can I help?",
            false to "Hi",
            false to "We are 2 people",
            false to "How much for flying dress?"
        )
        val grouped = WhatsAppNodeHelper.groupFloodAroundIndex(turns, targetIndex = 3)
        assertEquals("Hi\nWe are 2 people\nHow much for flying dress?", grouped)
    }

    @Test
    fun `github update checker parses release json and compares versions`() {
        val apiJson = """
            {
              "tag_name": "v2.3",
              "body": "versionCode: 14\nYeni ozellikler",
              "assets": [
                {
                  "name": "WA-Cevirmen-Asistan.apk",
                  "browser_download_url": "https://github.com/onurozer34/wa-cevirmen-asistan/releases/download/v2.3/WA-Cevirmen-Asistan.apk"
                }
              ]
            }
        """.trimIndent()
        val parsed = GitHubUpdateChecker.parseReleaseApiJson(apiJson)!!
        assertEquals("2.3", parsed.versionName)
        assertEquals(14, parsed.versionCode)
        assertTrue(GitHubUpdateChecker.compareSemanticVersions("2.3", "2.2") > 0)
        assertEquals(0, GitHubUpdateChecker.compareSemanticVersions("v2.2", "2.2"))

        val otaManifest = """
            {
              "versionCode": 15,
              "versionName": "2.4",
              "downloadUrl": "https://wa-cevirmen-ota.mor-media-drive-sync-bridge.workers.dev/WA-Cevirmen-Asistan.apk",
              "notes": "Cloudflare OTA build"
            }
        """.trimIndent()
        val parsedManifest = GitHubUpdateChecker.parseVersionJson(otaManifest)!!
        assertEquals("2.4", parsedManifest.versionName)
        assertEquals(15, parsedManifest.versionCode)
        assertEquals("https://wa-cevirmen-ota.mor-media-drive-sync-bridge.workers.dev/WA-Cevirmen-Asistan.apk", parsedManifest.downloadUrl)
    }
}
