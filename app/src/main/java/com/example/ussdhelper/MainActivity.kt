package com.example.ussdhelper // ⚠️ Check that this matches your actual package name

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.enableAccessibilityBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Dial *999# targeting SIM 1
        findViewById<Button>(R.id.dial999Btn).setOnClickListener {
            dialUssd("*999#", simSlot = 1)
        }

        // Dial *777# targeting SIM 2
        findViewById<Button>(R.id.dial777Btn).setOnClickListener {
            dialUssd("*777#", simSlot = 2)
        }

        // Custom USSD dialer buttons
        val customInput = findViewById<android.widget.EditText>(R.id.customUssdInput)
        findViewById<Button>(R.id.customDialSim1Btn)?.setOnClickListener {
            val code = customInput?.text?.toString()?.trim()
            if (code.isNullOrEmpty()) {
                android.widget.Toast.makeText(this, "Please enter a USSD code (e.g. *999#)", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                dialUssd(code, simSlot = 1)
            }
        }

        findViewById<Button>(R.id.customDialSim2Btn)?.setOnClickListener {
            val code = customInput?.text?.toString()?.trim()
            if (code.isNullOrEmpty()) {
                android.widget.Toast.makeText(this, "Please enter a USSD code (e.g. *777#)", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                dialUssd(code, simSlot = 2)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val statusText = findViewById<android.widget.TextView>(R.id.accessibilityStatusText) ?: return
        val enableBtn = findViewById<Button>(R.id.enableAccessibilityBtn)
        val isEnabled = isAccessibilityServiceEnabled()

        if (isEnabled) {
            statusText.text = "● Service Active & Ready"
            statusText.setTextColor(android.graphics.Color.parseColor("#34D399"))
            enableBtn?.text = "Accessibility Active (Tap to Manage)"
        } else {
            statusText.text = "● Setup Required — Tap to Enable"
            statusText.setTextColor(android.graphics.Color.parseColor("#FBBF24"))
            enableBtn?.text = "Enable Accessibility Service"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "${packageName}/${UssdAccessibilityService::class.java.canonicalName}"
        val expectedShortName = "${packageName}/${UssdAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(expectedComponentName, ignoreCase = true) ||
                component.equals(expectedShortName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun dialUssd(ussdCode: String, simSlot: Int) {
        val permissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1)
            return
        }

        // 1. Tell our Accessibility Service that this USSD call is initiated by our app
        UssdAccessibilityService.isAppInitiatedCall = true

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(ussdCode)))

        val targetSlotIndex = simSlot - 1
        val activeSubscriptions = try {
            subscriptionManager.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            null
        }

        val targetSubInfo = activeSubscriptions?.find { it.simSlotIndex == targetSlotIndex }
        val phoneAccounts = try {
            telecomManager.callCapablePhoneAccounts
        } catch (e: SecurityException) {
            null
        }

        var matchedHandle: android.telecom.PhoneAccountHandle? = null

        if (phoneAccounts != null && phoneAccounts.isNotEmpty()) {
            if (targetSubInfo != null) {
                val targetSubIdStr = targetSubInfo.subscriptionId.toString()
                val targetIccId = targetSubInfo.iccId ?: ""
                val targetSlotStr = targetSlotIndex.toString()

                // Advanced dual-SIM identifier matcher (supports subIds, physical slots, and ICCIDs)
                matchedHandle = phoneAccounts.find { handle ->
                    val handleId = (handle.id ?: "").lowercase()
                    handleId == targetSubIdStr ||
                            handleId == targetSlotStr ||
                            handleId == "slot$targetSlotStr" ||
                            handleId == "sim$targetSlotStr" ||
                            (targetIccId.isNotEmpty() && handleId.contains(targetIccId.lowercase()))
                }
            }

            // Only attach the account handle extra if we successfully matched SIM 2.
            // If matchedHandle is null, we do NOT attach EXTRA_PHONE_ACCOUNT_HANDLE
            // to prevent the OS from forcing the call back to SIM 1 (Default).
            if (matchedHandle != null) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, matchedHandle)
            }
        }

        // Pass subscription ID directly using the active subId (4 for SIM 1, 3 for SIM 2)
        if (targetSubInfo != null) {
            val targetSubId = targetSubInfo.subscriptionId
            intent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", targetSubId)
            intent.putExtra("subscription", targetSubId)
            intent.putExtra("subId", targetSubId)
        }

        // OEM fallbacks for physical slot mapping (Slot 0 for SIM 1, Slot 1 for SIM 2)
        intent.putExtra("com.android.phone.extra.slot", targetSlotIndex)
        intent.putExtra("com.android.phone.force.slot", true)
        intent.putExtra("simSlot", targetSlotIndex)
        intent.putExtra("slotId", targetSlotIndex)
        intent.putExtra("simId", targetSlotIndex)

        startActivity(intent)
    }
}