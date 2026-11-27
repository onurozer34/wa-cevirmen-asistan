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
    val businessName: String = "",
    val businessContext: String = "",
    val websiteUrl: String = "",
    val websiteKnowledge: String = "",
    val websiteUpdatedAt: Long = 0L,
    val instantClickTranslate: Boolean = false
) {
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
        return AppSettings(
            provider = provider,
            apiKey = prefs.getString("apiKey", "") ?: "",
            model = prefs.getString("model", provider.defaultModel) ?: provider.defaultModel,
            customBaseUrl = prefs.getString("customBaseUrl", "") ?: "",
            tone = ReplyTone.fromId(prefs.getString("tone", ReplyTone.WARM_PROFESSIONAL.id)),
            businessName = prefs.getString("businessName", "") ?: "",
            businessContext = prefs.getString("businessContext", "") ?: "",
            websiteUrl = prefs.getString("websiteUrl", "") ?: "",
            websiteKnowledge = prefs.getString("websiteKnowledge", "") ?: "",
            websiteUpdatedAt = prefs.getLong("websiteUpdatedAt", 0L),
            instantClickTranslate = prefs.getBoolean("instantClickTranslate", false)
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("provider", settings.provider.id)
            .putString("apiKey", settings.apiKey.trim())
            .putString("model", settings.model.trim())
            .putString("customBaseUrl", settings.customBaseUrl.trim())
            .putString("tone", settings.tone.id)
            .putString("businessName", settings.businessName.trim())
            .putString("businessContext", settings.businessContext.trim())
            .putString("websiteUrl", settings.websiteUrl.trim())
            .putString("websiteKnowledge", settings.websiteKnowledge.trim())
            .putLong("websiteUpdatedAt", settings.websiteUpdatedAt)
            .putBoolean("instantClickTranslate", settings.instantClickTranslate)
            .apply()
    }
}
