package com.example.waevirmenasistan.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.waevirmenasistan.engine.ChatTurn

object WhatsAppNodeHelper {

    private val TIMESTAMP_REGEX = Regex("^\\d{1,2}:\\d{2}(\\s?(AM|PM|ÖÖ|ÖS))?$", RegexOption.IGNORE_CASE)
    private val DATE_DIVIDER_REGEX = Regex("^(bugün|dün|today|yesterday|heute|gestern|\\d{1,2}\\s+[a-zçğıöşü]+\\s+\\d{4})$", RegexOption.IGNORE_CASE)
    private val SYSTEM_BANNER_REGEX = Regex("(uçtan uca şifrel|end-to-end encrypted|okunmamış mesaj|unread message)", RegexOption.IGNORE_CASE)

    data class TappedMessage(
        val text: String,
        val bounds: Rect
    )

    fun findComposeInput(root: AccessibilityNodeInfo?, screenHeight: Int, screenWidth: Int): AccessibilityNodeInfo? {
        if (root == null) return null

        val directIds = listOf("com.whatsapp:id/entry", "com.whatsapp.w4b:id/entry")
        for (id in directIds) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
            if (!found.isNullOrEmpty()) {
                return found.firstOrNull { it.isEditable } ?: found.firstOrNull()
            }
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, 1200) { node ->
            if (node.isEditable && node.className?.toString()?.contains("EditText") == true) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.bottom > (screenHeight * 0.45).toInt() && rect.width() > (screenWidth * 0.28).toInt()) {
                    candidates.add(node)
                }
            }
        }
        return candidates.maxByOrNull {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.bottom
        }
    }

    fun isInChatScreen(root: AccessibilityNodeInfo?, screenHeight: Int, screenWidth: Int): Boolean {
        if (root == null) return false
        if (findComposeInput(root, screenHeight, screenWidth) != null) return true

        // Fallback: check WhatsApp chat toolbar / voice note button when compose box is collapsed
        val chatIndicators = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp:id/voice_note_btn",
            "com.whatsapp.w4b:id/voice_note_btn",
            "com.whatsapp:id/input_attach_button",
            "com.whatsapp.w4b:id/input_attach_button"
        )
        return chatIndicators.any { id ->
            !root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty()
        }
    }

    private fun getMessageAreaBounds(root: AccessibilityNodeInfo?, screenHeight: Int, screenWidth: Int): Pair<Int, Int> {
        val headerBottom = (screenHeight * 0.10).toInt()
        val composeNode = findComposeInput(root, screenHeight, screenWidth)
        val composeTop = if (composeNode != null) {
            val r = Rect()
            composeNode.getBoundsInScreen(r)
            (r.top - 8).coerceAtLeast((screenHeight * 0.30).toInt())
        } else {
            (screenHeight * 0.92).toInt()
        }
        return headerBottom to composeTop
    }

    fun extractCustomerName(root: AccessibilityNodeInfo?, screenHeight: Int): String {
        if (root == null) return ""
        val ids = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )
        for (id in ids) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
            val txt = found?.firstOrNull()?.text?.toString()?.trim()
            if (!txt.isNullOrEmpty()) return txt
        }

        val topLimit = (screenHeight * 0.14).toInt()
        val headerTexts = mutableListOf<String>()
        collectNodes(root, 220) { node ->
            if (node.className?.toString()?.contains("TextView") == true) {
                val txt = node.text?.toString()?.trim().orEmpty()
                if (txt.isNotEmpty() && !TIMESTAMP_REGEX.matches(txt)) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.top in 20..topLimit && rect.height() > 10) {
                        headerTexts.add(txt)
                    }
                }
            }
        }
        return headerTexts.firstOrNull().orEmpty()
    }

    fun extractTappedMessage(
        source: AccessibilityNodeInfo?,
        root: AccessibilityNodeInfo?,
        screenHeight: Int,
        screenWidth: Int
    ): TappedMessage? {
        if (source == null) return null

        val (minTop, maxBottom) = getMessageAreaBounds(root, screenHeight, screenWidth)
        val srcRect = Rect()
        source.getBoundsInScreen(srcRect)

        if (source.isEditable || source.viewIdResourceName?.endsWith(":id/entry") == true) {
            return null
        }

        // Ignore huge outer containers (e.g. entire RecyclerView or screen root)
        val isLargeContainer = srcRect.height() > (screenHeight * 0.55).toInt() &&
            (source.className?.toString()?.contains("RecyclerView") == true ||
                source.className?.toString()?.contains("ListView") == true)
        if (isLargeContainer) {
            return null
        }

        if (srcRect.bottom < minTop || srcRect.top > maxBottom) {
            return null
        }

        // 1. Direct TextView check (message_text, caption, or obfuscated TextView)
        if (source.className?.toString()?.contains("TextView") == true && !isNonMessageViewId(source.viewIdResourceName)) {
            val directText = cleanMessageCandidate(source.text?.toString())
            if (directText != null) {
                return TappedMessage(directText, srcRect)
            }
        }

        // 2. Priority check for WhatsApp's known message_text / caption view IDs inside tapped bubble
        findPreferredMessageNode(source, minTop, maxBottom)?.let { return it }

        // 3. Search child TextViews inside tapped bubble container (excluding quoted message blocks)
        val childFound = findMessageTextInside(source, minTop, maxBottom)
        if (childFound != null) {
            return childFound
        }

        // 4. Walk up up to 4 ancestors (covers tapping timestamp, status icon, padding, or nested span)
        var parent = source.parent
        var depth = 0
        while (parent != null && depth < 4) {
            val pClass = parent.className?.toString().orEmpty()
            if (pClass.contains("RecyclerView") || pClass.contains("ListView")) break
            val pRect = Rect()
            parent.getBoundsInScreen(pRect)
            if (pRect.height() > (screenHeight * 0.55).toInt()) break

            findPreferredMessageNode(parent, minTop, maxBottom)?.let { return it }
            val found = findMessageTextInside(parent, minTop, maxBottom)
            if (found != null) return found
            parent = parent.parent
            depth++
        }

        return null
    }

    private fun findPreferredMessageNode(
        container: AccessibilityNodeInfo,
        minTop: Int,
        maxBottom: Int
    ): TappedMessage? {
        val preferredIds = listOf(
            "com.whatsapp:id/message_text",
            "com.whatsapp.w4b:id/message_text",
            "com.whatsapp:id/caption",
            "com.whatsapp.w4b:id/caption"
        )
        for (id in preferredIds) {
            val nodes = container.findAccessibilityNodeInfosByViewId(id)
            val hit = nodes?.firstOrNull()
            val cleaned = cleanMessageCandidate(hit?.text?.toString())
            if (hit != null && cleaned != null) {
                val r = Rect()
                hit.getBoundsInScreen(r)
                if (r.bottom >= minTop && r.top <= maxBottom) {
                    return TappedMessage(cleaned, r)
                }
            }
        }
        return null
    }

    fun findVisibleIncomingMessages(
        root: AccessibilityNodeInfo?,
        screenHeight: Int,
        screenWidth: Int,
        limit: Int = 4
    ): List<TappedMessage> {
        if (root == null) return emptyList()
        val (minTop, maxBottom) = getMessageAreaBounds(root, screenHeight, screenWidth)
        val seen = LinkedHashSet<String>()
        val incoming = mutableListOf<TappedMessage>()

        collectNodes(root, 1200) { node ->
            if (node.className?.toString()?.contains("TextView") == true && !isNonMessageViewId(node.viewIdResourceName)) {
                val cleaned = cleanMessageCandidate(node.text?.toString())
                if (cleaned != null) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.bottom >= minTop && rect.top <= maxBottom && rect.height() > 6) {
                        val isMe = rect.left > (screenWidth * 0.25).toInt() && rect.right > (screenWidth * 0.78).toInt()
                        if (!isMe && seen.add(cleaned)) {
                            incoming.add(TappedMessage(cleaned, rect))
                        }
                    }
                }
            }
        }
        return incoming.sortedBy { it.bounds.bottom }.takeLast(limit)
    }

    fun findLatestIncomingMessage(root: AccessibilityNodeInfo?, screenHeight: Int, screenWidth: Int): TappedMessage? {
        return findVisibleIncomingMessages(root, screenHeight, screenWidth, limit = 1).lastOrNull()
    }

    /**
     * If a customer sent consecutive messages in a burst ("flood") with no reply from us
     * in between, groups that unbroken customer burst into a single multi-line message
     * so translation and AI reply cover all parts cleanly.
     */
    fun expandCustomerFlood(
        root: AccessibilityNodeInfo?,
        tappedText: String,
        tappedBounds: Rect?,
        screenHeight: Int,
        screenWidth: Int
    ): String {
        val turns = scrapeVisibleChatTurnsWithBounds(root, screenHeight, screenWidth)
        if (turns.isEmpty()) return tappedText

        val targetIndex = findMatchingTurnIndex(turns, tappedText, tappedBounds)
        if (targetIndex < 0) return tappedText

        return groupFloodAroundIndex(
            turns = turns.map { it.senderMe to it.text },
            targetIndex = targetIndex,
            fallback = tappedText
        )
    }

    fun groupFloodAroundIndex(
        turns: List<Pair<Boolean, String>>,
        targetIndex: Int,
        fallback: String = ""
    ): String {
        if (targetIndex !in turns.indices) return fallback
        val (senderMe, targetText) = turns[targetIndex]
        if (senderMe) return targetText

        val group = ArrayDeque<String>()
        group.add(targetText)

        var i = targetIndex - 1
        while (i >= 0 && !turns[i].first && group.size < 8) {
            group.addFirst(turns[i].second)
            i--
        }

        var j = targetIndex + 1
        while (j < turns.size && !turns[j].first && group.size < 8) {
            group.addLast(turns[j].second)
            j++
        }

        return group.joinToString("\n")
    }

    data class PositionedTurn(
        val senderMe: Boolean,
        val text: String,
        val bounds: Rect
    )

    fun findMatchingTurnIndex(
        turns: List<PositionedTurn>,
        tappedText: String,
        tappedBounds: Rect?
    ): Int {
        if (tappedBounds != null && tappedBounds.width() > 0) {
            val byBounds = turns.indexOfFirst { turn ->
                turn.text == tappedText && Rect.intersects(turn.bounds, tappedBounds)
            }
            if (byBounds >= 0) return byBounds
        }
        return turns.indexOfLast { it.text == tappedText }
    }

    private fun scrapeVisibleChatTurnsWithBounds(
        root: AccessibilityNodeInfo?,
        screenHeight: Int,
        screenWidth: Int
    ): List<PositionedTurn> {
        if (root == null) return emptyList()
        val (minTop, maxBottom) = getMessageAreaBounds(root, screenHeight, screenWidth)
        val items = mutableListOf<PositionedTurn>()

        collectNodes(root, 1200) { node ->
            if (node.className?.toString()?.contains("TextView") == true && !isNonMessageViewId(node.viewIdResourceName)) {
                val cleaned = cleanMessageCandidate(node.text?.toString())
                if (cleaned != null) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.bottom >= minTop && rect.top <= maxBottom && rect.height() > 6) {
                        val isMe = rect.left > (screenWidth * 0.25).toInt() && rect.right > (screenWidth * 0.78).toInt()
                        items.add(PositionedTurn(senderMe = isMe, text = cleaned, bounds = rect))
                    }
                }
            }
        }

        val sorted = items.sortedBy { it.bounds.top }
        val deduped = ArrayList<PositionedTurn>(sorted.size)
        for (item in sorted) {
            val prev = deduped.lastOrNull()
            if (prev != null && prev.text == item.text && kotlin.math.abs(prev.bounds.top - item.bounds.top) < 12) {
                continue
            }
            deduped.add(item)
        }
        return deduped
    }

    fun scrapeVisibleChatContext(
        root: AccessibilityNodeInfo?,
        screenHeight: Int,
        screenWidth: Int,
        limit: Int = 10
    ): List<ChatTurn> {
        if (root == null) return emptyList()
        val (minTop, maxBottom) = getMessageAreaBounds(root, screenHeight, screenWidth)
        val items = mutableListOf<Pair<Int, ChatTurn>>()

        collectNodes(root, 1200) { node ->
            if (node.className?.toString()?.contains("TextView") == true && !isNonMessageViewId(node.viewIdResourceName)) {
                val cleaned = cleanMessageCandidate(node.text?.toString())
                if (cleaned != null) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.bottom >= minTop && rect.top <= maxBottom && rect.height() > 6) {
                        val isMe = rect.left > (screenWidth * 0.25).toInt() && rect.right > (screenWidth * 0.78).toInt()
                        items.add(rect.top to ChatTurn(senderMe = isMe, text = cleaned.take(500)))
                    }
                }
            }
        }

        return items
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
            .takeLast(limit)
    }

    fun insertReplyIntoWhatsAppInput(
        root: AccessibilityNodeInfo?,
        replyText: String,
        screenHeight: Int,
        screenWidth: Int
    ): Boolean {
        val inputNode = findComposeInput(root, screenHeight, screenWidth) ?: return false
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replyText)
        }
        return inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findMessageTextInside(container: AccessibilityNodeInfo, minTop: Int, maxBottom: Int): TappedMessage? {
        val list = mutableListOf<TappedMessage>()
        collectNodes(container, 120) { node ->
            if (node.className?.toString()?.contains("TextView") == true && !isNonMessageViewId(node.viewIdResourceName)) {
                val cleaned = cleanMessageCandidate(node.text?.toString())
                if (cleaned != null) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.bottom >= minTop && r.top <= maxBottom && r.height() > 6) {
                        list.add(TappedMessage(cleaned, r))
                    }
                }
            }
        }
        return list.maxByOrNull { it.text.length }
    }

    private fun isNonMessageViewId(viewId: String?): Boolean {
        if (viewId.isNullOrEmpty()) return false
        val id = viewId.lowercase()
        return id.endsWith(":id/date") ||
            id.endsWith(":id/status") ||
            id.endsWith(":id/quoted_title") ||
            id.endsWith(":id/quoted_name") ||
            id.endsWith(":id/quoted_text") ||
            id.endsWith(":id/quoted_message_holder") ||
            id.endsWith(":id/name_in_group_tv") ||
            id.endsWith(":id/conversation_contact_name") ||
            id.endsWith(":id/conversation_contact_status") ||
            id.endsWith(":id/entry")
    }

    private fun cleanMessageCandidate(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.length < 2) return null
        if (TIMESTAMP_REGEX.matches(t)) return null
        if (DATE_DIVIDER_REGEX.matches(t)) return null
        if (SYSTEM_BANNER_REGEX.containsMatchIn(t)) return null
        return t
    }

    private fun collectNodes(root: AccessibilityNodeInfo, maxNodes: Int, visitor: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val cur = queue.removeFirst()
            visited++
            visitor(cur)
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
    }
}
