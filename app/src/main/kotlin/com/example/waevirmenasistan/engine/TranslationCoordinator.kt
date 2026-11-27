package com.example.waevirmenasistan.engine

import com.example.waevirmenasistan.data.AppSettings

class TranslationCoordinator(
    private val llmEngine: LlmApiEngine = LlmApiEngine()
) {
    fun translateAndReply(
        settings: AppSettings,
        messageText: String,
        customerName: String,
        chatContext: List<ChatTurn>,
        onSettingsRefreshed: ((AppSettings) -> Unit)? = null
    ): TranslationOutcome {
        require(settings.hasApiKey()) {
            "API anahtarı girilmemiş. Lütfen uygulamadan API anahtarınızı kaydedin."
        }
        val activeSettings = ensureWebsiteKnowledge(settings, onSettingsRefreshed)
        return llmEngine.runTurn(
            settings = activeSettings,
            messageText = messageText,
            customerName = customerName,
            chatContext = chatContext
        )
    }

    fun refineWithInstruction(
        settings: AppSettings,
        instruction: String,
        messageText: String,
        customerName: String,
        chatContext: List<ChatTurn>,
        targetLang: String,
        currentReplyForeign: String,
        currentReplyTurkish: String,
        onSettingsRefreshed: ((AppSettings) -> Unit)? = null
    ): TranslationOutcome {
        require(settings.hasApiKey()) {
            "API anahtarı girilmemiş. Lütfen uygulamadan API anahtarınızı kaydedin."
        }
        val activeSettings = ensureWebsiteKnowledge(settings, onSettingsRefreshed)
        return llmEngine.runTurn(
            settings = activeSettings,
            messageText = messageText,
            customerName = customerName,
            chatContext = chatContext,
            instruction = instruction,
            currentReplyForeign = currentReplyForeign,
            currentReplyTurkish = currentReplyTurkish,
            targetLang = targetLang
        )
    }

    private fun ensureWebsiteKnowledge(
        settings: AppSettings,
        onSettingsRefreshed: ((AppSettings) -> Unit)?
    ): AppSettings {
        if (!settings.needsWebsiteSync()) return settings
        return runCatching {
            val extracted = WebsiteKnowledgeFetcher.fetchAndExtract(settings.websiteUrl)
            val updated = settings.copy(
                websiteKnowledge = extracted,
                websiteUpdatedAt = System.currentTimeMillis()
            )
            onSettingsRefreshed?.invoke(updated)
            updated
        }.getOrDefault(settings)
    }
}
