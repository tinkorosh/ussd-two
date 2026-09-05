package com.example.ussdhelper // ⚠️ Check that this matches your actual package name

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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class UssdAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    companion object {
        // Flag to restrict overlay drawing to app-initiated calls only
        @Volatile var isAppInitiatedCall = false
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackageName = event.packageName?.toString() ?: ""

        // 1. Clean up: If we transition to another package, dismiss the overlay
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventPackageName != "com.android.phone" &&
                eventPackageName != "com.example.ussdhelper" &&
                !eventPackageName.contains("inputmethod") &&
                eventPackageName != "com.android.systemui") {
                removeOverlay()
                return
            }
        }

        // 2. Process events from com.android.phone
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

            overlayView?.findViewById<Button>(R.id.overlayCancelBtn)?.setOnClickListener {
                val activeRoot = rootInActiveWindow
                if (activeRoot != null) {
                    val sysCancelBtn = findByResourceId(activeRoot, "android:id/button2")
                        ?: findByResourceId(activeRoot, "android:id/button1")
                    sysCancelBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                removeOverlay()
            }
        }
    }

    private fun showLoadingState() {
        overlayView?.let { view ->
            view.findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE
            view.findViewById<View>(R.id.contentLayout).visibility = View.GONE
        }
    }

    private fun showMenuState(rawText: String) {
        overlayView?.let { view ->
            val loadingLayout = view.findViewById<View>(R.id.loadingLayout)
            val contentLayout = view.findViewById<View>(R.id.contentLayout)
            val menuTextView = view.findViewById<TextView>(R.id.overlayMenuText)
            val container = view.findViewById<LinearLayout>(R.id.overlayOptionsContainer)

            loadingLayout.visibility = View.GONE
            contentLayout.visibility = View.VISIBLE

            val parsed = parseMenuAndTitle(rawText)
            menuTextView.text = parsed.first

            val cancelBtn = view.findViewById<Button>(R.id.overlayCancelBtn)
            if (parsed.second.isEmpty()) {
                cancelBtn.text = "Dismiss"
            } else {
                cancelBtn.text = "Cancel Session"
            }

            container.removeAllViews()

            val inflater = LayoutInflater.from(this)
            for ((code, label) in parsed.second) {
                val btn = inflater.inflate(R.layout.item_ussd_button, container, false) as Button
                btn.text = "$code. $label"
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
        // Reset the flag so future manual dials aren't hijacked
        isAppInitiatedCall = false
    }

    private fun injectReply(reply: String) {
        val root = rootInActiveWindow ?: return
        val input = findByResourceId(root, "com.android.phone:id/input_field")
        val sendBtn = findByResourceId(root, "android:id/button1")

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

    private fun parseMenuAndTitle(text: String): Pair<String, List<Pair<String, String>>> {
        val options = mutableListOf<Pair<String, String>>()
        val titleBuilder = StringBuilder()
        val regex = Regex("""^([\d]+|\*+|#+)\.\s*(.+)$""")

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
            title = "Select an option"
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
    }
}