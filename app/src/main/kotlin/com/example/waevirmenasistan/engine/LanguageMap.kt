package com.example.waevirmenasistan.engine

object LanguageMap {
    private val NAMES_TR = mapOf(
        "en" to "İngilizce",
        "de" to "Almanca",
        "fr" to "Fransızca",
        "es" to "İspanyolca",
        "it" to "İtalyanca",
        "nl" to "Hollandaca",
        "ar" to "Arapça",
        "ru" to "Rusça",
        "pt" to "Portekizce",
        "pl" to "Lehçe",
        "uk" to "Ukraynaca",
        "ro" to "Rumence",
        "el" to "Yunanca",
        "ja" to "Japonca",
        "ko" to "Korece",
        "zh" to "Çince",
        "zh-cn" to "Çince",
        "zh-tw" to "Çince (Geleneksel)",
        "fa" to "Farsça",
        "sv" to "İsveççe",
        "da" to "Danca",
        "no" to "Norveççe",
        "fi" to "Fince",
        "cs" to "Çekçe",
        "hu" to "Macarca",
        "bg" to "Bulgarca",
        "sr" to "Sırpça",
        "hr" to "Hırvatça",
        "hi" to "Hintçe",
        "id" to "Endonezce",
        "he" to "İbranice",
        "iw" to "İbranice",
        "az" to "Azerice",
        "ka" to "Gürcüce",
        "tr" to "Türkçe"
    )

    fun getNameTr(code: String?): String {
        if (code.isNullOrBlank()) return "Yabancı Dil"
        val norm = code.trim().lowercase()
        val base = norm.substringBefore('-')
        return NAMES_TR[norm] ?: NAMES_TR[base] ?: norm.uppercase()
    }
}

data class ChatTurn(
    val senderMe: Boolean,
    val text: String
)

data class TranslationOutcome(
    val detectedLang: String,
    val languageNameTr: String,
    val turkishTranslation: String,
    val replyInCustomerLanguage: String,
    val replyTurkishMeaning: String,
    val engineUsed: String,
    val warning: String? = null
)
