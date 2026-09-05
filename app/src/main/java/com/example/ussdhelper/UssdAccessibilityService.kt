package com.example.ussdhelper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

class UssdAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    companion object {
        // Flag to restrict overlay drawing to app-initiated calls only
        @Volatile var isAppInitiatedCall = false
        @Volatile var activeDialedCode: String = ""
        @Volatile var activeDialedPin: String = ""
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackageName = event.packageName?.toString() ?: ""

        // 1. Clean up: If we transition to an unrelated package, dismiss the overlay
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventPackageName != "com.android.phone" &&
                eventPackageName != "com.example.ussdhelper" &&
                !eventPackageName.contains("inputmethod") &&
                eventPackageName != "com.android.systemui") {
                removeOverlay()
                return
            }
        }

        // 2. Process events from phone process
        if (eventPackageName == "com.android.phone") {
            // ONLY proceed if the USSD call was initiated inside our app
            if (!isAppInitiatedCall) return

            val root = rootInActiveWindow ?: event.source ?: return
            val messageNode = findByResourceId(root, "android:id/message")
            val text = messageNode?.text?.toString()

            if (!text.isNullOrBlank()) {
                ensureOverlayCreated()

                if (text.contains("running", ignoreCase = true)) {
                    showLoadingState()
                } else {
                    showMenuState(text)
                }
            }
        }
    }

    private fun ensureOverlayCreated() {
        if (overlayView == null) {
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.ussd_overlay, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.CENTER
            windowManager?.addView(overlayView, params)

            setupOverlayListeners()
        }
    }

    private fun setupOverlayListeners() {
        val view = overlayView ?: return

        // Cancel / Dismiss
        view.findViewById<Button>(R.id.overlayCancelBtn)?.setOnClickListener {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val sysCancelBtn = findCancelButton(activeRoot)
                sysCancelBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            removeOverlay()
        }

        val customInput = view.findViewById<EditText>(R.id.overlayCustomInput)
        val sendCustomBtn = view.findViewById<Button>(R.id.overlaySendCustomBtn)
        val btnToggleCustomInput = view.findViewById<Button>(R.id.btnToggleCustomInput)
        val customInputSection = view.findViewById<View>(R.id.customInputSection)

        // Toggle custom input visibility
        btnToggleCustomInput?.setOnClickListener {
            if (customInputSection?.visibility == View.VISIBLE) {
                customInputSection.visibility = View.GONE
                btnToggleCustomInput.text = "⌨ Custom Input / Keypad ▾"
                updateWindowFocusable(false)
            } else {
                customInputSection?.visibility = View.VISIBLE
                btnToggleCustomInput.text = "⌨ Hide Custom Input ▲"
            }
        }

        // Make window focusable when user interacts with custom input so soft keyboard works
        customInput?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                updateWindowFocusable(true)
            }
        }
        customInput?.setOnClickListener {
            updateWindowFocusable(true)
        }

        // Send custom input
        fun submitCustomInput() {
            val text = customInput?.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                showLoadingState()
                injectReply(text)
                customInput?.setText("")
                updateWindowFocusable(false)
            }
        }

        sendCustomBtn?.setOnClickListener {
            submitCustomInput()
        }

        customInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitCustomInput()
                true
            } else {
                false
            }
        }

        // On-screen numeric keypad bindings
        fun appendDigit(char: String) {
            val current = customInput?.text?.toString() ?: ""
            customInput?.setText(current + char)
            customInput?.setSelection(customInput.text.length)
        }

        fun deleteDigit() {
            val current = customInput?.text?.toString() ?: ""
            if (current.isNotEmpty()) {
                customInput?.setText(current.dropLast(1))
                customInput?.setSelection(customInput.text.length)
            }
        }

        view.findViewById<Button>(R.id.keypad1)?.setOnClickListener { appendDigit("1") }
        view.findViewById<Button>(R.id.keypad2)?.setOnClickListener { appendDigit("2") }
        view.findViewById<Button>(R.id.keypad3)?.setOnClickListener { appendDigit("3") }
        view.findViewById<Button>(R.id.keypad4)?.setOnClickListener { appendDigit("4") }
        view.findViewById<Button>(R.id.keypad5)?.setOnClickListener { appendDigit("5") }
        view.findViewById<Button>(R.id.keypad6)?.setOnClickListener { appendDigit("6") }
        view.findViewById<Button>(R.id.keypad7)?.setOnClickListener { appendDigit("7") }
        view.findViewById<Button>(R.id.keypad8)?.setOnClickListener { appendDigit("8") }
        view.findViewById<Button>(R.id.keypad9)?.setOnClickListener { appendDigit("9") }
        view.findViewById<Button>(R.id.keypad0)?.setOnClickListener { appendDigit("0") }
        view.findViewById<Button>(R.id.keypadStar)?.setOnClickListener { appendDigit("*") }
        view.findViewById<Button>(R.id.keypadBackspace)?.setOnClickListener { deleteDigit() }
    }

    private fun updateWindowFocusable(focusable: Boolean) {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val newFlags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (params.flags != newFlags) {
            params.flags = newFlags
            windowManager?.updateViewLayout(view, params)
        }
    }

    private fun showLoadingState() {
        updateWindowFocusable(false)
        overlayView?.let { view ->
            view.findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE
            view.findViewById<View>(R.id.contentLayout).visibility = View.GONE
        }
    }

    private fun getSavedPinForSession(): String {
        if (activeDialedPin.isNotBlank()) return activeDialedPin.trim()
        val prefs = getSharedPreferences("ussd_prefs", Context.MODE_PRIVATE)
        val dialed = activeDialedCode.trim()

        // 1. Direct match by dialed code
        val directPin = prefs.getString("pin_for_$dialed", null)
        if (!directPin.isNullOrBlank()) return directPin.trim()

        // 2. Lookup in custom presets
        val jsonStr = prefs.getString("custom_presets_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val code = obj.optString("code", "").trim()
                    val pin = obj.optString("pin", "").trim()
                    if (pin.isNotEmpty()) {
                        if (code.equals(dialed, ignoreCase = true) ||
                            (dialed.isNotEmpty() && dialed.startsWith(code.trimEnd('#')))) {
                            return pin
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallbacks for standard presets
        if (dialed.startsWith("*999")) {
            val def999 = prefs.getString("pin_for_*999#", null)
            if (!def999.isNullOrBlank()) return def999.trim()
        }
        if (dialed.startsWith("*777")) {
            val def777 = prefs.getString("pin_for_*777#", null)
            if (!def777.isNullOrBlank()) return def777.trim()
        }

        return ""
    }

    private fun showMenuState(rawText: String) {
        overlayView?.let { view ->
            val loadingLayout = view.findViewById<View>(R.id.loadingLayout)
            val contentLayout = view.findViewById<View>(R.id.contentLayout)
            val menuTextView = view.findViewById<TextView>(R.id.overlayMenuText)
            val container = view.findViewById<LinearLayout>(R.id.overlayOptionsContainer)
            val cancelBtn = view.findViewById<Button>(R.id.overlayCancelBtn)
            val quickConfirmCard = view.findViewById<View>(R.id.quickConfirmCard)
            val btnQuickConfirmOne = view.findViewById<Button>(R.id.btnQuickConfirmOne)
            val quickPinCard = view.findViewById<View>(R.id.quickPinCard)
            val btnQuickAutoPin = view.findViewById<Button>(R.id.btnQuickAutoPin)
            val customInputSection = view.findViewById<View>(R.id.customInputSection)
            val btnToggleCustomInput = view.findViewById<Button>(R.id.btnToggleCustomInput)
            val actionsSectionTitle = view.findViewById<TextView>(R.id.actionsSectionTitle)

            loadingLayout.visibility = View.GONE
            contentLayout.visibility = View.VISIBLE

            // Ensure custom input section is collapsed by default
            customInputSection.visibility = View.GONE
            btnToggleCustomInput.text = "⌨ Custom Input / Keypad ▾"

            val parsed = UssdMenuParser.parse(rawText)
            menuTextView.text = parsed.title

            // 1. Check for Confirmation prompt ("To confirm press 1")
            val isConfirmPrompt = parsed.hasConfirmOne ||
                rawText.contains("confirm press 1", ignoreCase = true) ||
                rawText.contains("press 1 to confirm", ignoreCase = true) ||
                rawText.contains("to confirm enter 1", ignoreCase = true) ||
                rawText.contains("to confirm reply 1", ignoreCase = true)

            if (isConfirmPrompt) {
                quickConfirmCard.visibility = View.VISIBLE
                btnQuickConfirmOne.setOnClickListener {
                    showLoadingState()
                    injectReply("1")
                }
            } else {
                quickConfirmCard.visibility = View.GONE
            }

            // 2. Check for PIN prompt and saved PIN
            val isPinPrompt = parsed.isPinPrompt ||
                rawText.contains("pin", ignoreCase = true) ||
                rawText.contains("የይለፍ", ignoreCase = true) ||
                rawText.contains("password", ignoreCase = true)

            val savedPin = getSavedPinForSession()
            if (isPinPrompt && savedPin.isNotEmpty()) {
                quickPinCard.visibility = View.VISIBLE
                btnQuickAutoPin.text = "🔑 ENTER PIN (••••)"
                btnQuickAutoPin.setOnClickListener {
                    showLoadingState()
                    injectReply(savedPin)
                }
            } else {
                quickPinCard.visibility = View.GONE
            }

            // Section title & cancel button label
            if (parsed.options.isEmpty() && !isConfirmPrompt && (!isPinPrompt || savedPin.isEmpty())) {
                cancelBtn.text = "Dismiss"
                actionsSectionTitle.text = "DIRECT INPUT"
                // If there are literally no menu options and no special buttons, show custom input
                customInputSection.visibility = View.VISIBLE
                btnToggleCustomInput.text = "⌨ Hide Custom Input ▲"
            } else {
                cancelBtn.text = "Cancel Session"
                actionsSectionTitle.text = "SELECT AN ACTION"
            }

            container.removeAllViews()

            val inflater = LayoutInflater.from(this)
            for ((code, label) in parsed.options) {
                val btn = inflater.inflate(R.layout.item_ussd_button, container, false) as Button
                btn.text = if (label.startsWith(code, ignoreCase = true)) label else "$code. $label"
                btn.setOnClickListener {
                    showLoadingState()
                    injectReply(code)
                }
                container.addView(btn)
            }
        }
    }

    private fun removeOverlay() {
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
        }
        // Reset the flags so future manual dials aren't hijacked
        isAppInitiatedCall = false
        activeDialedCode = ""
        activeDialedPin = ""
    }

    private fun injectReply(reply: String) {
        val root = rootInActiveWindow ?: return
        val input = findInputNode(root)
        val sendBtn = findSendButton(root)

        if (input != null) {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                reply
            )
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findByResourceId(root, "android:id/input_field")
            ?: findByResourceId(root, "com.android.phone:id/input_field")
        if (byId != null) return byId

        return findFirstEditableNode(root)
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findByResourceId(root, "android:id/button1")
            ?: findByResourceId(root, "com.android.phone:id/button1")
        if (byId != null) return byId

        return findNodeByText(root, "Send")
            ?: findNodeByText(root, "OK")
            ?: findNodeByText(root, "Reply")
    }

    private fun findCancelButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = findByResourceId(root, "android:id/button2")
            ?: findByResourceId(root, "com.android.phone:id/button2")
        if (byId != null) return byId

        return findNodeByText(root, "Cancel")
            ?: findNodeByText(root, "Dismiss")
    }

    private fun findByResourceId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val list = root.findAccessibilityNodeInfosByViewId(viewId)
        return if (list != null && list.isNotEmpty()) list[0] else null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = node.findAccessibilityNodeInfosByText(text)
        return if (list != null && list.isNotEmpty()) list[0] else null
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}
