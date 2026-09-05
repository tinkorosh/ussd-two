package com.example.ussdhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton

class UssdAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var themedContext: Context? = null

    private val timeoutRunnable = Runnable {
        Log.d(TAG, "USSD session timeout reached. Dismissing overlay.")
        removeOverlay()
    }

    private val ALLOWED_PACKAGES = setOf(
        "com.android.phone",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.sh.smart.caller",
        "com.android.server.telecom"
    )

    companion object {
        private const val TAG = "UssdAccessService"

        @Volatile
        var isAppInitiatedCall = false
            private set

        @Volatile
        var currentCode: String = ""
            private set

        @Volatile
        var currentSimSlot: Int = 0
            private set

        @Volatile
        var activeInstance: UssdAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = activeInstance != null

        fun notifyDialInitiated(code: String, simSlot: Int) {
            isAppInitiatedCall = true
            currentCode = code
            currentSimSlot = simSlot
            Log.d(TAG, "Dial initiated: $code on slot $simSlot")
            activeInstance?.let { service ->
                service.mainHandler.post {
                    service.startOverlaySession(code, simSlot)
                }
            }
        }
    }

    private fun getThemedContext(): Context {
        if (themedContext == null) {
            themedContext = ContextThemeWrapper(this, R.style.Theme_UssdHelper)
        }
        return themedContext!!
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        activeInstance = this
        Log.d(TAG, "UssdAccessibilityService onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.packageNames = ALLOWED_PACKAGES.toTypedArray()
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 100
            serviceInfo = info
            Log.d(TAG, "UssdAccessibilityService onServiceConnected configured")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring serviceInfo in onServiceConnected", e)
        }
    }

    fun startOverlaySession(code: String, simSlot: Int) {
        ensureOverlayCreated(focusable = false)
        updateHeader(code, simSlot)
        showLoadingState("Initiating USSD Session...", "Dialing $code via SIM ${if (simSlot > 0) simSlot else 1}")
        resetTimeout()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackageName = event.packageName?.toString() ?: ""
        // Do not intercept interactions with our own application window
        if (eventPackageName == packageName) return

        // CRITICAL SAFETY: Strictly ignore any non-telephony package
        if (!ALLOWED_PACKAGES.contains(eventPackageName)) return

        // CRITICAL SAFETY: Only intercept if our app explicitly initiated dialer fallback
        if (!isAppInitiatedCall) return

        Log.d(TAG, "onAccessibilityEvent: type=${event.eventType}, package=$eventPackageName, class=${event.className}")

        val dialogNode = event.source ?: findDialogRoot() ?: return
        val text = extractUssdText(event, dialogNode)

        if (!text.isNullOrBlank()) {
            val isRunning = isRunningText(text)
            val isUssd = isRunning || isUssdContent(text, dialogNode)

            if (isUssd) {
                ensureOverlayCreated(focusable = false)
                updateHeader(currentCode, currentSimSlot)
                resetTimeout()

                if (isRunning) {
                    showLoadingState("Processing Request...", "Communicating with mobile operator...")
                } else {
                    showMenuState(text, dialogNode)
                }
            }
        }
    }

    private fun isRunningText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("running") ||
                lower.contains("sending") ||
                lower.contains("connecting") ||
                lower.contains("ussd code running") ||
                lower.contains("please wait")
    }

    private fun isUssdContent(text: String, root: AccessibilityNodeInfo): Boolean {
        // Check for numbered list menu items (e.g. 1. Internet, 2. Voice)
        if (Regex("""(?m)^([\d]+|\*+|#+)[\.\)\:\-\s]\s*.+""").containsMatchIn(text)) {
            return true
        }
        // Check for USSD input field
        if (findInputField(root) != null) {
            return true
        }
        // Check for common carrier USSD keywords
        val lower = text.lowercase()
        val keywords = listOf("gebeta", "ethio", "telebirr", "safaricom", "balance", "birr", "etb", "airtime", "bundle", "package", "welcome to")
        return keywords.any { lower.contains(it) }
    }

    private fun extractUssdText(event: AccessibilityEvent, root: AccessibilityNodeInfo): String? {
        // 1. If event has text list (very common in system dialog events)
        if (!event.text.isNullOrEmpty()) {
            val validTexts = event.text
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotBlank() && !isIgnoredSystemLabel(it) }
            if (validTexts.isNotEmpty()) {
                val joined = validTexts.joinToString("\n")
                if (joined.length > 3) {
                    return joined
                }
            }
        }

        // 2. Search common resource IDs across OEMs (AOSP, Samsung, Huawei, Xiaomi, Transsion)
        val candidateIds = listOf(
            "android:id/message",
            "com.android.phone:id/message",
            "com.google.android.dialer:id/message",
            "com.samsung.android.incallui:id/message",
            "com.sh.smart.caller:id/message",
            "android:id/text1",
            "android:id/custom"
        )
        for (id in candidateIds) {
            val node = findByResourceId(root, id)
            val text = node?.text?.toString()?.trim()
            if (!text.isNullOrBlank() && !isIgnoredSystemLabel(text)) {
                return text
            }
        }

        // 3. Search recursively for all TextView nodes that contain text
        val textViews = mutableListOf<AccessibilityNodeInfo>()
        collectTextViews(root, textViews)
        val textCandidates = textViews
            .mapNotNull { it.text?.toString()?.trim() }
            .filter { it.length > 3 && !isIgnoredSystemLabel(it) }

        if (textCandidates.isNotEmpty()) {
            return textCandidates.maxByOrNull { it.length }
        }

        return null
    }

    private fun isIgnoredSystemLabel(text: String): Boolean {
        val lower = text.lowercase().trim()
        val ignored = listOf("ok", "cancel", "send", "dismiss", "done", "close", "input", "phone", "carrier", "sim 1", "sim 2")
        return ignored.contains(lower)
    }

    private fun collectTextViews(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.className?.toString()?.contains("TextView") == true ||
            node.className?.toString()?.contains("EditText") == true) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            collectTextViews(node.getChild(i), list)
        }
    }

    private fun findDialogRoot(): AccessibilityNodeInfo? {
        try {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    val r = w.root ?: continue
                    val pkg = r.packageName?.toString() ?: ""
                    // Look for system/telecom dialogs, ignore our own overlay
                    if (pkg != packageName) {
                        if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM ||
                            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                            return r
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error looking through windows: ${e.message}")
        }
        val fallback = rootInActiveWindow
        if (fallback?.packageName?.toString() != packageName) {
            return fallback
        }
        return null
    }

    private fun ensureOverlayCreated(focusable: Boolean = false) {
        if (overlayView == null) {
            try {
                val ctx = getThemedContext()
                val inflater = LayoutInflater.from(ctx)
                overlayView = inflater.inflate(R.layout.ussd_overlay, null)

                val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                        if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                if (windowManager == null) {
                    windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                }
                windowManager?.addView(overlayView, params)
                setupOverlayListeners()
                Log.d(TAG, "Overlay successfully inflated and attached to WindowManager")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create accessibility overlay view", e)
            }
        } else {
            updateOverlayFocus(focusable)
        }
    }

    private fun updateOverlayFocus(focusable: Boolean) {
        overlayView?.let { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            val flagsWithoutFocus = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            params.flags = if (focusable) flagsWithoutFocus else (flagsWithoutFocus or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.w(TAG, "Error updating overlay layout params: ${e.message}")
            }
        }
    }

    private fun setupOverlayListeners() {
        val view = overlayView ?: return

        // Tap on outer scrim dismisses overlay
        view.findViewById<View>(R.id.overlayRootLayout)?.setOnClickListener {
            cancelUnderlyingSession()
            removeOverlay()
        }

        // Close button in header
        view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener {
            cancelUnderlyingSession()
            removeOverlay()
        }

        // Cancel button
        view.findViewById<MaterialButton>(R.id.overlayCancelBtn)?.setOnClickListener {
            cancelUnderlyingSession()
            removeOverlay()
        }

        // Done button in info result
        view.findViewById<MaterialButton>(R.id.btnDismissOk)?.setOnClickListener {
            dismissUnderlyingAlert()
            removeOverlay()
        }

        // Copy button in info result
        view.findViewById<MaterialButton>(R.id.btnCopyMessage)?.setOnClickListener {
            val menuTextView = view.findViewById<TextView>(R.id.overlayMenuText)
            val textToCopy = menuTextView?.text?.toString() ?: ""
            if (textToCopy.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("USSD Response", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Response copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // Input field & Send button
        val inputField = view.findViewById<EditText>(R.id.overlayInputField)
        val sendBtn = view.findViewById<MaterialButton>(R.id.overlaySendInputBtn)

        val onSend = {
            val reply = inputField?.text?.toString()?.trim() ?: ""
            if (reply.isNotEmpty()) {
                inputField?.text?.clear()
                showLoadingState("Sending Reply...", "Executing: $reply")
                injectReply(reply)
                updateOverlayFocus(false)
            }
        }

        sendBtn?.setOnClickListener { onSend() }
        inputField?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                onSend()
                true
            } else {
                false
            }
        }
    }

    private fun updateHeader(code: String, simSlot: Int) {
        overlayView?.let { view ->
            val codeChip = view.findViewById<TextView>(R.id.overlayCodeChip)
            val carrierBadge = view.findViewById<TextView>(R.id.overlayCarrierBadge)

            if (code.isNotBlank()) {
                codeChip?.text = code
                codeChip?.visibility = View.VISIBLE
            } else {
                codeChip?.visibility = View.GONE
            }

            carrierBadge?.text = when (simSlot) {
                1 -> "SIM 1"
                2 -> "SIM 2"
                else -> "AUTO"
            }
        }
    }

    private fun showLoadingState(title: String = "Connecting...", subtitle: String = "System USSD dialog is covered and secured") {
        overlayView?.let { view ->
            view.findViewById<View>(R.id.loadingLayout)?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.contentLayout)?.visibility = View.GONE
            view.findViewById<TextView>(R.id.loadingStatusTitle)?.text = title
            view.findViewById<TextView>(R.id.loadingStatusSubtitle)?.text = subtitle
        }
    }

    private fun showTimeoutState() {
        overlayView?.let { view ->
            view.findViewById<View>(R.id.loadingLayout)?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.contentLayout)?.visibility = View.GONE
            view.findViewById<TextView>(R.id.loadingStatusTitle)?.text = "Session Timed Out"
            view.findViewById<TextView>(R.id.loadingStatusSubtitle)?.text = "The carrier took too long to respond. Tap Cancel to dismiss."
        }
    }

    private fun showMenuState(rawText: String, rootNode: AccessibilityNodeInfo) {
        overlayView?.let { view ->
            val loadingLayout = view.findViewById<View>(R.id.loadingLayout)
            val contentLayout = view.findViewById<View>(R.id.contentLayout)
            val menuTextView = view.findViewById<TextView>(R.id.overlayMenuText)
            val container = view.findViewById<LinearLayout>(R.id.overlayOptionsContainer)
            val infoResultLayout = view.findViewById<View>(R.id.overlayInfoResultLayout)
            val inputLayout = view.findViewById<View>(R.id.overlayInputLayout)

            loadingLayout?.visibility = View.GONE
            contentLayout?.visibility = View.VISIBLE

            val parsed = parseMenuAndTitle(rawText)
            menuTextView?.text = parsed.first

            container?.removeAllViews()

            // Check if there is an input field in the active USSD dialog
            val systemInputNode = findInputField(rootNode)
            val hasSystemInput = systemInputNode != null

            if (hasSystemInput) {
                inputLayout?.visibility = View.VISIBLE
                updateOverlayFocus(true)
            } else {
                inputLayout?.visibility = View.GONE
                updateOverlayFocus(false)
            }

            if (parsed.second.isNotEmpty()) {
                // We have numbered menu options (1. Option A, 2. Option B)
                infoResultLayout?.visibility = View.GONE
                val inflater = LayoutInflater.from(getThemedContext())
                for ((code, label) in parsed.second) {
                    val btn = inflater.inflate(R.layout.item_ussd_button, container, false) as MaterialButton
                    btn.text = "$code. $label"
                    btn.setOnClickListener {
                        showLoadingState("Selecting Option $code...", "Option: $label")
                        injectReply(code)
                    }
                    container?.addView(btn)
                }
            } else {
                // Alert or Informational USSD (No numbered items)
                infoResultLayout?.visibility = View.VISIBLE
            }
        }
    }

    private fun resetTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, 12000) // 12 seconds safe timeout
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(timeoutRunnable)
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: ${e.message}")
            }
            overlayView = null
        }
        isAppInitiatedCall = false
    }

    private fun cancelUnderlyingSession() {
        val root = findDialogRoot() ?: return
        val sysCancelBtn = findByResourceId(root, "android:id/button2")
            ?: findByResourceId(root, "com.android.phone:id/cancel")
            ?: findByResourceId(root, "com.sh.smart.caller:id/cancel")
            ?: findNodeByText(root, "Cancel")
            ?: findNodeByText(root, "CANCEL")
            ?: findNodeByText(root, "Dismiss")
        sysCancelBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun dismissUnderlyingAlert() {
        val root = findDialogRoot() ?: return
        val sysOkBtn = findByResourceId(root, "android:id/button1")
            ?: findByResourceId(root, "android:id/button2")
            ?: findByResourceId(root, "com.android.phone:id/ok_button")
            ?: findByResourceId(root, "com.sh.smart.caller:id/ok")
            ?: findNodeByText(root, "OK")
            ?: findNodeByText(root, "Done")
            ?: findNodeByText(root, "Dismiss")
        sysOkBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun injectReply(reply: String) {
        val root = findDialogRoot() ?: return
        val input = findInputField(root)

        if (input != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
            }
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        // Slight delay ensures the Accessibility text argument is fully committed
        mainHandler.postDelayed({
            val refreshedRoot = findDialogRoot() ?: root
            val sendBtn = findByResourceId(refreshedRoot, "android:id/button1")
                ?: findByResourceId(refreshedRoot, "com.android.phone:id/send_button")
                ?: findByResourceId(refreshedRoot, "com.sh.smart.caller:id/send_button")
                ?: findNodeByText(refreshedRoot, "Send")
                ?: findNodeByText(refreshedRoot, "SEND")
                ?: findNodeByText(refreshedRoot, "OK")
            sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }, 120)
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findByResourceId(root, "com.android.phone:id/input_field")
            ?: findByResourceId(root, "android:id/input")
            ?: findByResourceId(root, "com.sh.smart.caller:id/input_field")
            ?: findByClassName(root, "android.widget.EditText")
    }

    private fun findNodeByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val matches = root.findAccessibilityNodeInfosByText(text)
        return matches.firstOrNull { it.isClickable } ?: matches.firstOrNull()
    }

    private fun findByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.toString() == className) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByClassName(child, className)
            if (found != null) return found
        }
        return null
    }

    private fun parseMenuAndTitle(text: String): Pair<String, List<Pair<String, String>>> {
        val options = mutableListOf<Pair<String, String>>()
        val titleBuilder = StringBuilder()
        val regex = Regex("""^([\d]+|\*+|#+)[\.\)\:\-\s]\s*(.+)$""")

        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (TextUtils.isEmpty(trimmed)) continue
            val match = regex.find(trimmed)
            if (match != null) {
                options.add(match.groupValues[1] to match.groupValues[2])
            } else {
                if (titleBuilder.isNotEmpty()) titleBuilder.append("\n")
                titleBuilder.append(trimmed)
            }
        }

        var title = titleBuilder.toString()
        if (title.isBlank()) {
            title = if (options.isNotEmpty()) "Select an Option" else "USSD Information"
        }
        return title to options
    }

    private fun findByResourceId(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val matches = node.findAccessibilityNodeInfosByViewId(id)
        return if (matches.isNotEmpty()) matches[0] else null
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        activeInstance = null
    }
}
