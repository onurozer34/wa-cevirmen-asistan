package com.example.waevirmenasistan.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.waevirmenasistan.R
import com.example.waevirmenasistan.engine.TranslationOutcome

class OverlayController(
    private val context: Context,
    private val onTranslateRequested: (String, Rect?) -> Unit,
    private val onInstructionSubmitted: (String) -> Unit,
    private val onInsertReplyRequested: (String) -> Unit,
    private val onOpenPanelRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var launcherView: View? = null
    private var floatBtnView: View? = null
    private var topPanelView: View? = null

    private var topPanelParams: WindowManager.LayoutParams? = null
    var isTopPanelOpen: Boolean = false
        private set
    private var isMinimized = false

    private var latestForeignReply = ""
    private var pendingTranslateText = ""
    private var pendingTranslateBounds: Rect? = null

    private var savedPanelX: Int? = null
    private var savedPanelY: Int? = null
    private var savedCardsHeightPx: Int? = null

    private val autoHideFloatRunnable = Runnable { hideFloatTranslateButton() }

    private val screenWidth: Int
        get() = context.resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = context.resources.displayMetrics.heightPixels

    private val statusBarApproxPx: Int
        get() = (32 * context.resources.displayMetrics.density).toInt()

    fun showLauncherPill() {
        if (isTopPanelOpen) {
            hideLauncherPill()
            return
        }
        if (launcherView != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_launcher_pill, null)
        view.setOnClickListener { onOpenPanelRequested() }

        val density = context.resources.displayMetrics.density
        val boxPx = (30 * density).toInt()
        val params = WindowManager.LayoutParams(
            boxPx,
            boxPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = statusBarApproxPx + (64 * density).toInt()
        }

        runCatching {
            windowManager.addView(view, params)
            launcherView = view
        }
    }

    fun hideLauncherPill() {
        launcherView?.let { v -> runCatching { windowManager.removeView(v) } }
        launcherView = null
    }

    fun showFloatTranslateButton(messageText: String, targetBounds: Rect?) {
        pendingTranslateText = messageText
        pendingTranslateBounds = targetBounds
        hideFloatTranslateButton()

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_float_button, null)
        view.setOnClickListener {
            hideFloatTranslateButton()
            if (pendingTranslateText.isNotBlank()) {
                onTranslateRequested(pendingTranslateText, pendingTranslateBounds)
            }
        }

        val density = context.resources.displayMetrics.density
        val btnHeightPx = (34 * density).toInt()
        val minY = statusBarApproxPx + (60 * density).toInt()

        var yPos = if (targetBounds != null && targetBounds.width() > 0) {
            (targetBounds.top - (btnHeightPx / 2)).coerceAtLeast(minY)
        } else {
            minY + (18 * density).toInt()
        }

        if (isTopPanelOpen && topPanelView != null) {
            val panelLoc = IntArray(2)
            topPanelView!!.getLocationOnScreen(panelLoc)
            val panelBottom = panelLoc[1] + topPanelView!!.height
            if (panelBottom > 0 && yPos < panelBottom + (8 * density).toInt()) {
                yPos = panelBottom + (8 * density).toInt()
            }
        }

        val xPos = if (targetBounds != null && targetBounds.width() > 0) {
            targetBounds.left.coerceIn((10 * density).toInt(), (screenWidth - (130 * density).toInt()).coerceAtLeast(10))
        } else {
            (16 * density).toInt()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xPos
            y = yPos
        }

        runCatching {
            windowManager.addView(view, params)
            floatBtnView = view
            mainHandler.removeCallbacks(autoHideFloatRunnable)
            mainHandler.postDelayed(autoHideFloatRunnable, 12_000L)
        }
    }

    fun hideFloatTranslateButton() {
        mainHandler.removeCallbacks(autoHideFloatRunnable)
        floatBtnView?.let { v -> runCatching { windowManager.removeView(v) } }
        floatBtnView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    fun openOrUpdateTopPanel() {
        hideLauncherPill()
        if (topPanelView == null) {
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_top_panel, null)
            val density = context.resources.displayMetrics.density
            val sideMarginPx = (8 * density).toInt()
            val panelWidthPx = (screenWidth - sideMarginPx * 2).coerceAtLeast((260 * density).toInt())

            val defaultX = sideMarginPx
            val defaultY = statusBarApproxPx + (52 * density).toInt()

            val params = WindowManager.LayoutParams(
                panelWidthPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedPanelX ?: defaultX
                y = savedPanelY ?: defaultY
            }

            savedCardsHeightPx?.let { h ->
                val cards = view.findViewById<LinearLayout>(R.id.cardsContainer)
                cards.layoutParams = cards.layoutParams.apply { height = h }
            }

            bindTopPanelListeners(view, panelWidthPx)

            runCatching {
                windowManager.addView(view, params)
                topPanelView = view
                topPanelParams = params
            }
        }

        isTopPanelOpen = true
    }

    fun closeTopPanel() {
        setInputFocusable(false)
        topPanelView?.let { v -> runCatching { windowManager.removeView(v) } }
        topPanelView = null
        topPanelParams = null
        isTopPanelOpen = false
        isMinimized = false
    }

    fun hideAllOverlays() {
        hideFloatTranslateButton()
        hideLauncherPill()
        closeTopPanel()
    }

    fun showLoading(statusMsg: String) {
        openOrUpdateTopPanel()
        val v = topPanelView ?: return
        isMinimized = false
        v.findViewById<View>(R.id.panelBody).visibility = View.VISIBLE
        v.findViewById<ImageButton>(R.id.btnMinimize).setImageResource(R.drawable.ic_chevron_up)
        val statusView = v.findViewById<TextView>(R.id.txtStatusLine)
        statusView.setTextColor(Color.parseColor("#69416F"))
        statusView.text = statusMsg
        statusView.visibility = View.VISIBLE
        v.findViewById<TextView>(R.id.txtApplyLabel).text = "Hazırlanıyor"
        v.findViewById<View>(R.id.btnApplyInstruction).isEnabled = false
    }

    fun showResult(
        outcome: TranslationOutcome,
        statusMsg: String? = null,
        clearInstructionInput: Boolean = false
    ) {
        openOrUpdateTopPanel()
        val v = topPanelView ?: return

        latestForeignReply = outcome.replyInCustomerLanguage

        v.findViewById<TextView>(R.id.txtSourceLang).text = outcome.detectedLang.uppercase()
        v.findViewById<TextView>(R.id.txtTurkishTranslation).text = outcome.turkishTranslation
        v.findViewById<TextView>(R.id.txtForeignReply).text = outcome.replyInCustomerLanguage

        val divider = v.findViewById<View>(R.id.replyDivider)
        val meaningView = v.findViewById<TextView>(R.id.txtReplyTrMeaning)
        if (outcome.replyTurkishMeaning.isNotBlank()) {
            meaningView.text = outcome.replyTurkishMeaning
            meaningView.visibility = View.VISIBLE
            divider.visibility = View.VISIBLE
        } else {
            meaningView.visibility = View.GONE
            divider.visibility = View.GONE
        }

        v.findViewById<ScrollView>(R.id.scrollTranslation).scrollTo(0, 0)
        v.findViewById<ScrollView>(R.id.scrollReply).scrollTo(0, 0)

        if (clearInstructionInput) {
            v.findViewById<EditText>(R.id.edtInstruction).setText("")
        }
        v.findViewById<TextView>(R.id.txtApplyLabel).text = "Uygula"
        v.findViewById<View>(R.id.btnApplyInstruction).isEnabled = true

        val statusView = v.findViewById<TextView>(R.id.txtStatusLine)
        if (!statusMsg.isNullOrBlank()) {
            statusView.setTextColor(Color.parseColor("#69416F"))
            statusView.text = statusMsg
            statusView.visibility = View.VISIBLE
        } else {
            statusView.visibility = View.GONE
        }
    }

    fun updateStatus(message: String, isError: Boolean = true) {
        openOrUpdateTopPanel()
        val v = topPanelView ?: return
        v.findViewById<TextView>(R.id.txtApplyLabel).text = "Uygula"
        v.findViewById<View>(R.id.btnApplyInstruction).isEnabled = true
        val statusView = v.findViewById<TextView>(R.id.txtStatusLine)
        statusView.setTextColor(Color.parseColor(if (isError) "#B42318" else "#69416F"))
        statusView.text = message
        statusView.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTopPanelListeners(view: View, panelWidthPx: Int) {
        val density = context.resources.displayMetrics.density
        val headerDrag = view.findViewById<View>(R.id.headerDragArea)
        val resizeHandle = view.findViewById<View>(R.id.resizeHandle)
        val cardsContainer = view.findViewById<LinearLayout>(R.id.cardsContainer)

        val edt = view.findViewById<EditText>(R.id.edtInstruction)
        val btnApply = view.findViewById<LinearLayout>(R.id.btnApplyInstruction)
        val btnMin = view.findViewById<ImageButton>(R.id.btnMinimize)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
        val body = view.findViewById<View>(R.id.panelBody)

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                setInputFocusable(false)
                true
            } else {
                false
            }
        }

        // 2D free dragging from top header (allows pushing off left, right, and bottom edges)
        var dragStartX = 0
        var dragStartY = 0
        var touchStartRawX = 0f
        var touchStartRawY = 0f
        headerDrag.setOnTouchListener { _, event ->
            val p = topPanelParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = p.x
                    dragStartY = p.y
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartRawX).toInt()
                    val dy = (event.rawY - touchStartRawY).toInt()
                    val keepEdgePx = (52 * density).toInt()
                    p.x = (dragStartX + dx).coerceIn(-(panelWidthPx - keepEdgePx), screenWidth - keepEdgePx)
                    p.y = (dragStartY + dy).coerceIn(statusBarApproxPx, screenHeight - (42 * density).toInt())
                    savedPanelX = p.x
                    savedPanelY = p.y
                    runCatching { windowManager.updateViewLayout(view, p) }
                    true
                }
                else -> false
            }
        }

        // Bottom vertical resize grip (drag UP to shrink, drag DOWN to expand)
        var resizeStartY = 0f
        var resizeStartHeight = 0
        resizeHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartY = event.rawY
                    resizeStartHeight = cardsContainer.height.takeIf { it > 0 } ?: cardsContainer.layoutParams.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - resizeStartY).toInt()
                    val minH = (84 * density).toInt()
                    val maxH = (screenHeight * 0.58f).toInt().coerceAtLeast(minH + 40)
                    val nextH = (resizeStartHeight + dy).coerceIn(minH, maxH)
                    cardsContainer.layoutParams = cardsContainer.layoutParams.apply { height = nextH }
                    savedCardsHeightPx = nextH
                    cardsContainer.requestLayout()
                    true
                }
                else -> false
            }
        }

        val scrollTouchListener = View.OnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        view.findViewById<ScrollView>(R.id.scrollTranslation).setOnTouchListener(scrollTouchListener)
        view.findViewById<ScrollView>(R.id.scrollReply).setOnTouchListener(scrollTouchListener)

        edt.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                setInputFocusable(true)
                mainHandler.postDelayed({
                    edt.requestFocus()
                    imm.showSoftInput(edt, InputMethodManager.SHOW_IMPLICIT)
                }, 40)
            }
            false
        }

        edt.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                setInputFocusable(false)
                true
            } else {
                false
            }
        }

        val submitInstruction = {
            val txt = edt.text?.toString()?.trim().orEmpty()
            setInputFocusable(false)
            if (txt.isNotEmpty()) {
                onInstructionSubmitted(txt)
            }
        }

        edt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitInstruction()
                true
            } else {
                false
            }
        }

        btnApply.setOnClickListener { submitInstruction() }

        btnMin.setOnClickListener {
            isMinimized = !isMinimized
            setInputFocusable(false)
            body.visibility = if (isMinimized) View.GONE else View.VISIBLE
            btnMin.setImageResource(if (isMinimized) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
        }

        btnClose.setOnClickListener {
            closeTopPanel()
            showLauncherPill()
        }

        view.findViewById<ImageButton>(R.id.btnWriteToBox).setOnClickListener {
            if (latestForeignReply.isNotBlank()) {
                setInputFocusable(false)
                onInsertReplyRequested(latestForeignReply)
            } else {
                updateStatus("Önce bir mesaj çevirin veya cevap hazırlayın.", isError = true)
            }
        }
    }

    private fun setInputFocusable(focusable: Boolean) {
        val view = topPanelView ?: return
        val params = topPanelParams ?: return
        val currentlyFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (currentlyFocusable == focusable) return

        params.flags = if (focusable) {
            (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        runCatching {
            windowManager.updateViewLayout(view, params)
            if (!focusable) {
                val edt = view.findViewById<EditText>(R.id.edtInstruction)
                imm.hideSoftInputFromWindow(edt.windowToken, 0)
                edt.clearFocus()
            }
        }
    }
}
