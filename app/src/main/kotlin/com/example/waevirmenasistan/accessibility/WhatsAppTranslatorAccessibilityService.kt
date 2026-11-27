package com.example.waevirmenasistan.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.waevirmenasistan.data.SettingsRepository
import com.example.waevirmenasistan.engine.TranslationCoordinator
import java.util.concurrent.Executors

class WhatsAppTranslatorAccessibilityService : AccessibilityService() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var coordinator: TranslationCoordinator
    private lateinit var overlay: OverlayController
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var keyguardManager: KeyguardManager

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeOriginalMessage: String = ""
    private var activeDetectedLang: String = "en"
    private var activeTurkishTranslation: String = ""
    private var activeForeignReply: String = ""
    private var activeReplyTurkish: String = ""

    private var isWhatsAppForeground = false
    private var lastClipboardText = ""

    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                isWhatsAppForeground = false
                overlay.hideAllOverlays()
            }
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!settingsRepo.isAssistantActive()) {
            overlay.hideAllOverlays()
            return@OnPrimaryClipChangedListener
        }
        if (!isWhatsAppForeground) return@OnPrimaryClipChangedListener
        val waRoot = findWhatsAppRoot() ?: return@OnPrimaryClipChangedListener
        if (!WhatsAppNodeHelper.isInChatScreen(waRoot, screenHeight, screenWidth)) return@OnPrimaryClipChangedListener

        val clipText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (clipText.length < 2 || clipText == lastClipboardText) return@OnPrimaryClipChangedListener
        if (clipText == activeForeignReply || clipText == activeTurkishTranslation) return@OnPrimaryClipChangedListener
        lastClipboardText = clipText

        val settings = settingsRepo.load()
        if (settings.instantClickTranslate) {
            overlay.hideFloatTranslateButton()
            handleTranslate(clipText, null)
        } else {
            overlay.showFloatTranslateButton(clipText, null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settingsRepo = SettingsRepository(this)
        coordinator = TranslationCoordinator()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        overlay = OverlayController(
            context = this,
            onTranslateRequested = { msg, bounds -> handleTranslate(msg, bounds) },
            onInstructionSubmitted = { inst -> handleInstruction(inst) },
            onInsertReplyRequested = { reply -> writeReplyToWhatsApp(reply) },
            onOpenPanelRequested = { handleOpenTopPanel() }
        )

        runCatching { registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipboardListener) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPkg = event.packageName?.toString().orEmpty()
        if (eventPkg == packageName || eventPkg.contains("inputmethod", ignoreCase = true)) {
            return
        }

        if (!settingsRepo.isAssistantActive()) {
            isWhatsAppForeground = false
            overlay.hideAllOverlays()
            return
        }

        if (keyguardManager.isKeyguardLocked) {
            isWhatsAppForeground = false
            overlay.hideAllOverlays()
            return
        }

        val waRoot = findWhatsAppRoot()
        if (waRoot == null) {
            isWhatsAppForeground = false
            overlay.hideAllOverlays()
            return
        }

        isWhatsAppForeground = true

        val inChat = WhatsAppNodeHelper.isInChatScreen(waRoot, screenHeight, screenWidth)
        if (!inChat) {
            overlay.hideAllOverlays()
            return
        }

        if (!overlay.isTopPanelOpen) {
            overlay.showLauncherPill()
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                overlay.hideFloatTranslateButton()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val tapped = WhatsAppNodeHelper.extractTappedMessage(event.source, waRoot, screenHeight, screenWidth)
                if (tapped != null) {
                    val settings = settingsRepo.load()
                    if (settings.instantClickTranslate) {
                        overlay.hideFloatTranslateButton()
                        handleTranslate(tapped.text, tapped.bounds)
                    } else {
                        overlay.showFloatTranslateButton(tapped.text, tapped.bounds)
                    }
                }
            }
        }
    }

    private fun findWhatsAppRoot(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        val activeRootPkg = activeRoot?.packageName?.toString().orEmpty()
        val isAllowedActiveRoot =
            activeRootPkg == "com.whatsapp" ||
                activeRootPkg == "com.whatsapp.w4b" ||
                activeRootPkg == packageName ||
                activeRootPkg.contains("inputmethod", ignoreCase = true)

        if (activeRootPkg.isNotEmpty() && !isAllowedActiveRoot) {
            return null
        }

        val activeWindows = windows.orEmpty()
        val appWindows = activeWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

        if (appWindows.isNotEmpty()) {
            val waWin = appWindows.firstOrNull { win ->
                val pkg = win.root?.packageName?.toString()
                pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"
            } ?: return null

            val otherActive = appWindows.any { win ->
                val pkg = win.root?.packageName?.toString()
                pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b" && (win.isActive || win.isFocused)
            }
            if (otherActive) return null

            return waWin.root
        }

        return if (activeRootPkg == "com.whatsapp" || activeRootPkg == "com.whatsapp.w4b") activeRoot else null
    }

    private fun handleOpenTopPanel() {
        val waRoot = findWhatsAppRoot() ?: return
        val latest = WhatsAppNodeHelper.findLatestIncomingMessage(waRoot, screenHeight, screenWidth)
        if (latest != null && activeOriginalMessage.isBlank()) {
            handleTranslate(latest.text, latest.bounds)
        } else {
            overlay.openOrUpdateTopPanel()
        }
    }

    private fun handleTranslate(messageText: String, messageBounds: android.graphics.Rect? = null) {
        val settings = settingsRepo.load()
        val waRoot = findWhatsAppRoot()
        val customerName = WhatsAppNodeHelper.extractCustomerName(waRoot, screenHeight)
        val contextTurns = WhatsAppNodeHelper.scrapeVisibleChatContext(waRoot, screenHeight, screenWidth)
        val groupedMessage = WhatsAppNodeHelper.expandCustomerFlood(
            root = waRoot,
            tappedText = messageText,
            tappedBounds = messageBounds,
            screenHeight = screenHeight,
            screenWidth = screenWidth
        )

        activeOriginalMessage = groupedMessage
        overlay.showLoading("Çevriliyor ve taslak hazırlanıyor...")

        executor.execute {
            try {
                val outcome = coordinator.translateAndReply(
                    settings = settings,
                    messageText = groupedMessage,
                    customerName = customerName,
                    chatContext = contextTurns,
                    onSettingsRefreshed = { updated -> settingsRepo.save(updated) }
                )
                mainHandler.post {
                    if (!isWhatsAppForeground) return@post
                    activeDetectedLang = outcome.detectedLang
                    activeTurkishTranslation = outcome.turkishTranslation
                    activeForeignReply = outcome.replyInCustomerLanguage
                    activeReplyTurkish = outcome.replyTurkishMeaning

                    overlay.showResult(
                        outcome = outcome,
                        statusMsg = null
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isWhatsAppForeground) return@post
                    overlay.updateStatus(
                        message = e.message ?: "API çevirisi sırasında hata oluştu.",
                        isError = true
                    )
                }
            }
        }
    }

    private fun handleInstruction(instruction: String) {
        val settings = settingsRepo.load()
        val waRoot = findWhatsAppRoot()
        if (activeOriginalMessage.isBlank()) {
            WhatsAppNodeHelper.findLatestIncomingMessage(waRoot, screenHeight, screenWidth)?.let {
                activeOriginalMessage = it.text
            }
        }
        val customerName = WhatsAppNodeHelper.extractCustomerName(waRoot, screenHeight)
        val contextTurns = WhatsAppNodeHelper.scrapeVisibleChatContext(waRoot, screenHeight, screenWidth)

        overlay.showLoading("Cümle harmanlanıp yeniden hazırlanıyor...")

        executor.execute {
            try {
                val outcome = coordinator.refineWithInstruction(
                    settings = settings,
                    instruction = instruction,
                    messageText = activeOriginalMessage.ifBlank { instruction },
                    customerName = customerName,
                    chatContext = contextTurns,
                    targetLang = activeDetectedLang,
                    currentReplyForeign = activeForeignReply,
                    currentReplyTurkish = activeReplyTurkish,
                    onSettingsRefreshed = { updated -> settingsRepo.save(updated) }
                )
                mainHandler.post {
                    if (!isWhatsAppForeground) return@post
                    activeDetectedLang = outcome.detectedLang
                    if (outcome.turkishTranslation.isNotBlank()) {
                        activeTurkishTranslation = outcome.turkishTranslation
                    }
                    activeForeignReply = outcome.replyInCustomerLanguage
                    activeReplyTurkish = outcome.replyTurkishMeaning

                    overlay.showResult(
                        outcome = outcome,
                        statusMsg = "Güncellendi — onaylamak için aşağı oka basın.",
                        clearInstructionInput = true
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isWhatsAppForeground) return@post
                    overlay.updateStatus(
                        message = e.message ?: "API talimat işlerken hata oluştu.",
                        isError = true
                    )
                }
            }
        }
    }

    private fun writeReplyToWhatsApp(replyText: String) {
        val ok = WhatsAppNodeHelper.insertReplyIntoWhatsAppInput(
            root = findWhatsAppRoot(),
            replyText = replyText,
            screenHeight = screenHeight,
            screenWidth = screenWidth
        )
        if (ok) {
            overlay.updateStatus("WhatsApp metin kutusuna aktarıldı.", isError = false)
        } else {
            overlay.updateStatus("WhatsApp metin kutusu bulunamadı.", isError = true)
        }
    }

    override fun onInterrupt() {
        isWhatsAppForeground = false
        overlay.hideAllOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        isWhatsAppForeground = false
        runCatching { unregisterReceiver(screenOffReceiver) }
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipboardListener) }
        overlay.hideAllOverlays()
        executor.shutdownNow()
    }

    companion object {
        @Volatile
        private var instance: WhatsAppTranslatorAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = "${context.packageName}/${WhatsAppTranslatorAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun syncActiveState(active: Boolean) {
            if (!active) {
                instance?.let { svc ->
                    svc.isWhatsAppForeground = false
                    svc.mainHandler.post { svc.overlay.hideAllOverlays() }
                }
            }
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
