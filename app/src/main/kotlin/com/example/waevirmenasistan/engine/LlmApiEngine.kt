package com.example.waevirmenasistan.engine

import com.example.waevirmenasistan.data.AiProvider
import com.example.waevirmenasistan.data.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object LlmJsonParser {
    fun parseOutcome(raw: String, engineUsed: String): TranslationOutcome {
        val json = extractJsonObject(raw)
        val detected = json.optString("detectedLang", "en").trim().lowercase().ifEmpty { "en" }
        val langNameTr = json.optString("languageNameTr").trim().ifEmpty { LanguageMap.getNameTr(detected) }
        return TranslationOutcome(
            detectedLang = detected,
            languageNameTr = langNameTr,
            turkishTranslation = json.optString("turkishTranslation").trim(),
            replyInCustomerLanguage = json.optString("replyInCustomerLanguage").trim(),
            replyTurkishMeaning = json.optString("replyTurkishMeaning").trim(),
            engineUsed = engineUsed
        )
    }

    fun extractJsonObject(raw: String): JSONObject {
        val trimmed = raw.trim()
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) {
                JSONObject(trimmed.substring(start, end + 1))
            } else {
                throw IllegalStateException("AI yanıtı JSON formatında çözümlenemedi.")
            }
        }
    }
}

class LlmApiEngine {

    fun runTurn(
        settings: AppSettings,
        messageText: String,
        customerName: String,
        chatContext: List<ChatTurn>,
        instruction: String? = null,
        currentReplyForeign: String? = null,
        currentReplyTurkish: String? = null,
        targetLang: String? = null
    ): TranslationOutcome {
        val key = settings.apiKey.trim()
        require(key.isNotEmpty()) { "API anahtarı bulunamadı." }

        val systemInstruction = buildSystemPrompt(settings)
        val userPrompt = buildUserPrompt(
            messageText = messageText,
            customerName = customerName,
            chatContext = chatContext,
            instruction = instruction,
            currentReplyForeign = currentReplyForeign,
            currentReplyTurkish = currentReplyTurkish,
            targetLang = targetLang
        )

        return if (settings.provider == AiProvider.GEMINI) {
            callGemini(settings, key, systemInstruction, userPrompt)
        } else {
            callOpenAICompatible(settings, key, systemInstruction, userPrompt)
        }
    }

    private fun buildSystemPrompt(settings: AppSettings): String {
        val bizLines = buildList {
            if (settings.businessName.isNotBlank()) add("İşletme Adı: ${settings.businessName.trim()}")
            if (settings.businessContext.isNotBlank()) add("İşletme/Hizmet Bilgileri: ${settings.businessContext.trim()}")
        }
        return buildString {
            appendLine("Sen WhatsApp üzerinden yabancı müşterilerle yazışan bir işletme sahibinin kıdemli çok dilli iletişim asistanısın.")
            appendLine("Görevlerin:")
            appendLine("1) Müşterinin yabancı dildeki mesajını doğal, eksiksiz Türkçeye çevirmek.")
            appendLine("2) Müşterinin konuştuğu DİLDE (detectedLang) gönderilmeye hazır, akıcı ve nazik bir yanıt yazmak.")
            appendLine("3) Yazdığın yanıtın birebir Türkçe anlamını (replyTurkishMeaning) kullanıcıya göstermek.")
            appendLine("Varsayılan ton: ${settings.tone.label}.")
            if (bizLines.isNotEmpty()) {
                bizLines.forEach { appendLine(it) }
            }
            if (settings.websiteKnowledge.isNotBlank()) {
                appendLine()
                appendLine("Referans Web Sitesi Bilgileri (${settings.websiteUrl.ifBlank { "Web" }}):")
                appendLine("Müşteri fiyat, paket, süre veya konum sorduğunda aşağıdaki güncel web sitesi bilgilerini birebir esas al ve tutarları doğru aktar:")
                appendLine(settings.websiteKnowledge)
            }
            appendLine("Kurallar:")
            appendLine("- Müşteri mesajını satır atlayarak birden fazla parça (flood) halinde gönderdiyse hepsini sırayla eksiksiz Türkçeye çevir ve tüm noktaları tek bir akıcı cevapta yanıtla.")
            appendLine("- Cevabı doğrudan müşterinin kendi dilinde yaz.")
            appendLine("- Verilmeyen fiyat/tarih/adres bilgisi uydurma; eksikse nazikçe sor veya kullanıcının talimatını uygula.")
            appendLine("- Sadece geçerli JSON döndür.")
        }
    }

    private fun buildUserPrompt(
        messageText: String,
        customerName: String,
        chatContext: List<ChatTurn>,
        instruction: String?,
        currentReplyForeign: String?,
        currentReplyTurkish: String?,
        targetLang: String?
    ): String {
        return buildString {
            if (customerName.isNotBlank()) appendLine("Müşteri Adı: $customerName")
            if (chatContext.isNotEmpty()) {
                appendLine("\nSon Sohbet Bağlamı:")
                chatContext.takeLast(8).forEach { turn ->
                    appendLine("- ${if (turn.senderMe) "Ben" else "Müşteri"}: ${turn.text}")
                }
            }
            appendLine("\nOdaklanılan Müşteri Mesajı:\n\"\"\"$messageText\"\"\"")
            if (!instruction.isNullOrBlank()) {
                appendLine("\nHedef Dil: ${targetLang ?: "Müşteri mesajının dili"}")
                if (!currentReplyForeign.isNullOrBlank()) appendLine("Mevcut Taslak Cevap: \"\"\"$currentReplyForeign\"\"\"")
                if (!currentReplyTurkish.isNullOrBlank()) appendLine("Mevcut Taslağın Türkçe Anlamı: \"\"\"$currentReplyTurkish\"\"\"")
                appendLine("\nKULLANICININ DÜZENLEME / EKLEME TALİMATI:\n\"\"\"$instruction\"\"\"")
                appendLine("ÖNEMLİ: Mevcut taslak cevabın doğru ve beğenilen kısımlarını (selamlama, nezaket ve bağlam akışını) koru; kullanıcının yazdığı ek bilgiyi, düzeltmeyi veya cümleyi mevcut taslak ile DOĞAL ŞEKİLDE HARMANLAYIP tek akıcı bir cevap oluştur. Türkçe anlamını (replyTurkishMeaning) da güncelleyip kullanıcının onayına sun.")
            } else {
                appendLine("\nBu mesajı Türkçeye çevir ve müşteriye gönderilmeye hazır cevabı müşterinin dilinde oluştur.")
            }
        }
    }

    private class HttpStatusException(
        val statusCode: Int,
        message: String
    ) : IllegalStateException(message)

    private fun callGemini(
        settings: AppSettings,
        apiKey: String,
        systemInstruction: String,
        userPrompt: String
    ): TranslationOutcome {
        val preferred = settings.model.ifBlank { AiProvider.GEMINI.defaultModel }.trim()
        val candidates = listOf(
            preferred,
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.1-flash-lite",
            "gemini-3.8-flash",
            "gemini-4-hydrogen"
        ).distinct()

        var lastError: Exception? = null
        for ((idx, model) in candidates.withIndex()) {
            try {
                return callGeminiSingleModel(model, apiKey, systemInstruction, userPrompt)
            } catch (e: HttpStatusException) {
                lastError = e
                // Auth/bad-key errors fail immediately instead of retrying fallback models
                if (e.statusCode in listOf(400, 401, 403)) {
                    throw e
                }
                // Transient 503/429 or retired 404 -> wait briefly and try next model
                if (idx < candidates.lastIndex && e.statusCode in listOf(404, 429, 500, 502, 503, 504)) {
                    runCatching { Thread.sleep(250L) }
                    continue
                }
                throw e
            }
        }
        throw (lastError ?: IllegalStateException("Gemini API yanıt vermedi."))
    }

    private fun callGeminiSingleModel(
        model: String,
        apiKey: String,
        systemInstruction: String,
        userPrompt: String
    ): TranslationOutcome {
        val encodedModel = URLEncoder.encode(model, "UTF-8")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = URL("${AiProvider.GEMINI.baseUrl}/models/$encodedModel:generateContent?key=$encodedKey")

        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("detectedLang", JSONObject().put("type", "STRING"))
                put("languageNameTr", JSONObject().put("type", "STRING"))
                put("turkishTranslation", JSONObject().put("type", "STRING"))
                put("replyInCustomerLanguage", JSONObject().put("type", "STRING"))
                put("replyTurkishMeaning", JSONObject().put("type", "STRING"))
            })
            put("required", JSONArray(listOf("detectedLang", "languageNameTr", "turkishTranslation", "replyInCustomerLanguage", "replyTurkishMeaning")))
        }

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.35)
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
            })
        }

        val rawResponse = httpPost(url, payload.toString(), emptyMap())
        val root = JSONObject(rawResponse)
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini API boş yanıt döndürdü.")

        val rawText = buildString {
            for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }.trim()

        return LlmJsonParser.parseOutcome(rawText, "Gemini ($model)")
    }

    private fun callOpenAICompatible(
        settings: AppSettings,
        apiKey: String,
        systemInstruction: String,
        userPrompt: String
    ): TranslationOutcome {
        val base = if (settings.provider == AiProvider.CUSTOM) {
            settings.customBaseUrl.trim().trimEnd('/')
        } else {
            settings.provider.baseUrl
        }
        require(base.isNotEmpty()) { "Özel API için Base URL girilmemiş." }

        val model = settings.model.ifBlank { settings.provider.defaultModel }.trim()
        val url = URL("$base/chat/completions")
        val sysWithKeys = "$systemInstruction\nDöndüreceğin JSON anahtarları: detectedLang, languageNameTr, turkishTranslation, replyInCustomerLanguage, replyTurkishMeaning."

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.35)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sysWithKeys))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val rawResponse = httpPost(url, payload.toString(), mapOf("Authorization" to "Bearer $apiKey"))
        val root = JSONObject(rawResponse)
        val rawText = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
        if (rawText.isEmpty()) throw IllegalStateException("API boş yanıt döndürdü.")

        return LlmJsonParser.parseOutcome(rawText, "${settings.provider.label} ($model)")
    }

    private fun httpPost(url: URL, jsonBody: String, extraHeaders: Map<String, String>): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(jsonBody) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message").orEmpty().ifEmpty { text }
                } catch (_: Exception) {
                    text
                }
                throw HttpStatusException(code, "API HTTP $code: $msg")
            }
            text
        } finally {
            conn.disconnect()
        }
    }
}
