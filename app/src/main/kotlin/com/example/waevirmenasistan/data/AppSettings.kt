package com.example.waevirmenasistan.data

import android.content.Context

enum class AiProvider(
    val id: String,
    val label: String,
    val defaultModel: String,
    val baseUrl: String,
    val models: List<Pair<String, String>>
) {
    GEMINI(
        id = "gemini",
        label = "Google Gemini API",
        defaultModel = "gemini-3.5-flash-lite",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        models = listOf(
            "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite (En Stabil)",
            "gemini-3.6-flash" to "Gemini 3.6 Flash (Dengeli)",
            "gemini-3.8-flash" to "Gemini 3.8 Flash",
            "gemini-4-hydrogen" to "Gemini 4 Hydrogen",
            "gemini-4-neon" to "Gemini 4 Neon (Ücretli Plan)"
        )
    ),
    OPENAI(
        id = "openai",
        label = "OpenAI",
        defaultModel = "gpt-4o-mini",
        baseUrl = "https://api.openai.com/v1",
        models = listOf(
            "gpt-4o-mini" to "GPT-4o Mini",
            "gpt-4.1-mini" to "GPT-4.1 Mini",
            "gpt-4o" to "GPT-4o"
        )
    ),
    GROQ(
        id = "groq",
        label = "Groq",
        defaultModel = "llama-3.3-70b-versatile",
        baseUrl = "https://api.groq.com/openai/v1",
        models = listOf(
            "llama-3.3-70b-versatile" to "Llama 3.3 70B",
            "llama-3.1-8b-instant" to "Llama 3.1 8B Instant"
        )
    ),
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter",
        defaultModel = "google/gemini-2.5-flash",
        baseUrl = "https://openrouter.ai/api/v1",
        models = listOf(
            "google/gemini-2.5-flash" to "Gemini 2.5 Flash",
            "openai/gpt-4o-mini" to "GPT-4o Mini"
        )
    ),
    CUSTOM(
        id = "custom",
        label = "Özel OpenAI Uyumlu",
        defaultModel = "gpt-4o-mini",
        baseUrl = "",
        models = emptyList()
    );

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

enum class ReplyTone(val id: String, val label: String) {
    WARM_PROFESSIONAL("warm_professional", "Sıcak & Profesyonel"),
    FORMAL("formal", "Resmi & Kurumsal"),
    FRIENDLY("friendly", "Samimi & Doğal"),
    CONCISE("concise", "Kısa & Net");

    companion object {
        fun fromId(id: String?): ReplyTone = entries.firstOrNull { it.id == id } ?: WARM_PROFESSIONAL
    }
}

data class AppSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val model: String = "gemini-3.5-flash-lite",
    val customBaseUrl: String = "",
    val tone: ReplyTone = ReplyTone.WARM_PROFESSIONAL,
    val businessName: String = DEFAULT_BUSINESS_NAME,
    val businessContext: String = DEFAULT_BUSINESS_CONTEXT,
    val websiteUrl: String = DEFAULT_WEBSITE_URL,
    val websiteKnowledge: String = "",
    val websiteUpdatedAt: Long = 0L,
    val instantClickTranslate: Boolean = true
) {
    companion object {
        const val DEFAULT_BUSINESS_NAME = "Mor Media Works"
        const val DEFAULT_WEBSITE_URL = "https://www.istanbulphotoshoot.com/"
        val DEFAULT_BUSINESS_CONTEXT = """
            • Individual Photosession: 100€/saat (min. 1 saat çekim, 10 fotoğrafa kadar renk düzenleme, tüm ham kareler dahil).
            • Flying Dress Photo Shooting (En Popüler): 150€/saat (min. 1 saat, 10 fotoğrafa kadar renk düzenleme, tüm ham kareler + seçilen 1 mekânda ücretsiz uçuşan elbise ve elbise asistanı; Ortaköy, Roof Validehan, Roof Galata Terrace).
            • Love Story: 200€/saat (min. 1 saat, 20 fotoğrafa kadar renk düzenleme, tüm ham kareler dahil).
            • Full Day Photo Shoot: 500€/gün (min. 6 saat, 30 fotoğrafa kadar renk düzenleme, tüm ham kareler ve özel paket; özel mekân giriş ücretleri hariç).
            • Özel Teras Girişleri (kişi başı): Roof Validehan (30 dk) 75€, Roof Galata Terrace 75€. Açık alanlar (Ortaköy, Beşiktaş, Karaköy, Galata Kulesi/Köprüsü, İstiklal, Taksim, Eminönü, Sirkeci, Sultanahmet, Süleymaniye, Balat) ücretsizdir.
            • Teslimat & Çalışma: En iyi saatler gündoğumu (06:30-08:30) ve gün batımı (Golden Hour). Tüm orijinal fotoğraflar (150-300+ kare) çekim hemen sonrası telefona veya bulut bağlantısıyla aktarılır; seçilen retouch kareler birkaç gün içinde teslim edilir. Sürpriz evlilik teklifi ve video çekimleri WhatsApp üzerinden planlanır.
            • Stüdyo & İletişim: Bereketzade, Kart Çınar Sk. No:4/4, Beyoğlu/İstanbul | +90 532 382 17 34
        """.trimIndent()
    }

    fun hasApiKey(): Boolean = apiKey.trim().length > 6

    fun needsWebsiteSync(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (websiteUrl.isBlank()) return false
        if (websiteKnowledge.isBlank()) return true
        val twentyFourHoursMs = 24L * 60L * 60L * 1000L
        return (nowMs - websiteUpdatedAt) > twentyFourHoursMs
    }

    fun activeBadgeLabel(): String {
        return if (hasApiKey()) {
            provider.label.substringBefore(' ')
        } else {
            "API Yok"
        }
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("waai_prefs", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val provider = AiProvider.fromId(prefs.getString("provider", AiProvider.GEMINI.id))
        val storedContext = prefs.getString("businessContext", "")?.trim().orEmpty()
        val effectiveContext = if (storedContext.isNotEmpty()) storedContext else AppSettings.DEFAULT_BUSINESS_CONTEXT

        val migratedInstant = prefs.getBoolean("instant_v24_default_applied", false)
        val instantClick = if (!migratedInstant) {
            prefs.edit()
                .putBoolean("instantClickTranslate", true)
                .putBoolean("instant_v24_default_applied", true)
                .apply()
            true
        } else {
            prefs.getBoolean("instantClickTranslate", true)
        }

        return AppSettings(
            provider = provider,
            apiKey = prefs.getString("apiKey", "") ?: "",
            model = prefs.getString("model", provider.defaultModel) ?: provider.defaultModel,
            customBaseUrl = prefs.getString("customBaseUrl", "") ?: "",
            tone = ReplyTone.fromId(prefs.getString("tone", ReplyTone.WARM_PROFESSIONAL.id)),
            businessName = AppSettings.DEFAULT_BUSINESS_NAME,
            businessContext = effectiveContext,
            websiteUrl = AppSettings.DEFAULT_WEBSITE_URL,
            websiteKnowledge = prefs.getString("websiteKnowledge", "") ?: "",
            websiteUpdatedAt = prefs.getLong("websiteUpdatedAt", 0L),
            instantClickTranslate = instantClick
        )
    }

    fun saveInstantClick(enabled: Boolean) {
        prefs.edit()
            .putBoolean("instantClickTranslate", enabled)
            .putBoolean("instant_v24_default_applied", true)
            .apply()
    }

    fun save(settings: AppSettings) {
        val finalContext = settings.businessContext.trim().ifEmpty { AppSettings.DEFAULT_BUSINESS_CONTEXT }
        prefs.edit()
            .putString("provider", settings.provider.id)
            .putString("apiKey", settings.apiKey.trim())
            .putString("model", settings.model.trim())
            .putString("customBaseUrl", settings.customBaseUrl.trim())
            .putString("tone", settings.tone.id)
            .putString("businessName", AppSettings.DEFAULT_BUSINESS_NAME)
            .putString("businessContext", finalContext)
            .putString("websiteUrl", AppSettings.DEFAULT_WEBSITE_URL)
            .putString("websiteKnowledge", settings.websiteKnowledge.trim())
            .putLong("websiteUpdatedAt", settings.websiteUpdatedAt)
            .putBoolean("instantClickTranslate", settings.instantClickTranslate)
            .putBoolean("instant_v24_default_applied", true)
            .apply()
    }
}
