package com.example.ussdhelper.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ussdhelper.UssdAccessibilityService
import com.example.ussdhelper.model.SimCardInfo

object SimManager {
    private const val TAG = "SimManager"

    fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCallPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun detectSimCards(context: Context): List<SimCardInfo> {
        val simList = mutableListOf<SimCardInfo>()

        if (!hasPhoneStatePermission(context)) {
            // Return basic placeholder slots if permission not yet granted
            simList.add(SimCardInfo(slotIndex = 0, displayName = "SIM 1 (Permission Needed)", carrierName = "", subscriptionId = 1, isAvailable = false))
            simList.add(SimCardInfo(slotIndex = 1, displayName = "SIM 2 (Permission Needed)", carrierName = "", subscriptionId = 2, isAvailable = false))
            return simList
        }

        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val activeList: List<SubscriptionInfo>? = subscriptionManager?.activeSubscriptionInfoList

            if (activeList != null && activeList.isNotEmpty()) {
                // Track detected slots (0 and 1)
                val bySlot = activeList.associateBy { it.simSlotIndex }

                for (slot in 0..1) {
                    val info = bySlot[slot]
                    if (info != null) {
                        val carrier = info.carrierName?.toString() ?: ""
                        val display = info.displayName?.toString() ?: "SIM ${slot + 1}"
                        val number = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                subscriptionManager.getPhoneNumber(info.subscriptionId)
                            } else {
                                @Suppress("DEPRECATION")
                                info.number ?: ""
                            }
                        } catch (e: Exception) {
                            ""
                        }
                        simList.add(
                            SimCardInfo(
                                slotIndex = slot,
                                displayName = display,
                                carrierName = carrier,
                                subscriptionId = info.subscriptionId,
                                iccId = info.iccId ?: "",
                                number = number,
                                isAvailable = true
                            )
                        )
                    } else {
                        simList.add(
                            SimCardInfo(
                                slotIndex = slot,
                                displayName = "Empty Slot ${slot + 1}",
                                carrierName = "No SIM card detected",
                                subscriptionId = -1,
                                isAvailable = false
                            )
                        )
                    }
                }
            } else {
                // Fallback to TelephonyManager single slot info
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val opName = telephonyManager?.networkOperatorName ?: telephonyManager?.simOperatorName ?: "Active SIM"
                simList.add(
                    SimCardInfo(
                        slotIndex = 0,
                        displayName = opName,
                        carrierName = opName,
                        subscriptionId = 1,
                        isAvailable = true
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting SIM cards", e)
            simList.add(SimCardInfo(slotIndex = 0, displayName = "SIM 1", carrierName = "Carrier Info Unavailable", subscriptionId = 1, isAvailable = true))
        }

        return simList
    }

    /**
     * Executes a USSD code natively in-app via TelephonyManager.sendUssdRequest (Android 8.0+ / API 26+).
     * This keeps the app completely in foreground, preventing the phone dialer from launching or closing the app.
     */
    @SuppressLint("MissingPermission")
    fun executeUssdInApp(
        context: Context,
        ussdCode: String,
        simSlot: Int = 0,
        onSuccess: (returnMessage: CharSequence) -> Unit,
        onError: (failureCode: Int, message: String) -> Unit
    ) {
        if (!hasCallPermission(context)) {
            onError(-1, "CALL_PHONE permission is required for executing USSD.")
            return
        }

        try {
            val tm = getTelephonyManagerForSlot(context, simSlot)
            val callback = object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager?,
                    request: String?,
                    returnMessage: CharSequence?
                ) {
                    onSuccess(returnMessage ?: "Request completed with no message.")
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager?,
                    request: String?,
                    failureCode: Int
                ) {
                    val errorMsg = when (failureCode) {
                        TelephonyManager.USSD_RETURN_FAILURE -> "Carrier rejected background USSD or session expired."
                        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "Telephony service unavailable."
                        else -> "USSD failed (code $failureCode). Carrier may require interactive dialer."
                    }
                    onError(failureCode, errorMsg)
                }
            }

            tm.sendUssdRequest(ussdCode, callback, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException calling sendUssdRequest", e)
            onError(-1, "Permission error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling sendUssdRequest", e)
            onError(-1, "Execution error: ${e.localizedMessage}")
        }
    }

    @SuppressLint("MissingPermission")
    fun getTelephonyManagerForSlot(context: Context, simSlot: Int): TelephonyManager {
        val defaultTm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (simSlot !in 1..2) {
            return defaultTm
        }

        val targetSlotIndex = simSlot - 1
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subscriptionManager != null && hasPhoneStatePermission(context)) {
                val list = subscriptionManager.activeSubscriptionInfoList
                val subInfo = list?.firstOrNull { it.simSlotIndex == targetSlotIndex }
                if (subInfo != null && subInfo.subscriptionId != -1) {
                    return defaultTm.createForSubscriptionId(subInfo.subscriptionId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create TelephonyManager for slot $simSlot: ${e.message}")
        }

        return defaultTm
    }

    /**
     * Dials a USSD code, directing to a specific SIM slot (1 or 2) when possible.
     * simSlot: 0 = Default / Not specified, 1 = SIM 1 (Slot 0), 2 = SIM 2 (Slot 1)
     */
    fun dialUssd(context: Context, ussdCode: String, simSlot: Int = 0) {
        // Encode USSD string (e.g. *999# -> *999%23)
        val encodedCode = Uri.encode(ussdCode)
        val callUri = Uri.parse("tel:$encodedCode")
        
        // Signal accessibility service immediately so overlay covers the screen BEFORE system popup!
        UssdAccessibilityService.notifyDialInitiated(ussdCode, simSlot)

        val intent = Intent(Intent.ACTION_CALL, callUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        if (simSlot in 1..2) {
            val targetSlotIndex = simSlot - 1 // 0 for SIM 1, 1 for SIM 2
            applySimSlotToIntent(context, intent, targetSlotIndex)
        }

        try {
            context.startActivity(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "CALL_PHONE permission required for direct dialing", e)
            // Fallback to ACTION_DIAL if CALL_PHONE is denied
            val dialIntent = Intent(Intent.ACTION_DIAL, callUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dial USSD code", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun applySimSlotToIntent(context: Context, intent: Intent, targetSlotIndex: Int) {
        val targetSlotStr = targetSlotIndex.toString()

        // Multiple OEM extra variations for physical slot mapping
        intent.putExtra("simSlot", targetSlotIndex)
        intent.putExtra("slot", targetSlotIndex)
        intent.putExtra("slotId", targetSlotIndex)
        intent.putExtra("simId", targetSlotIndex)
        intent.putExtra("com.android.phone.extra.slot", targetSlotIndex)
        intent.putExtra("com.android.phone.force.slot", true)
        intent.putExtra("simnum", targetSlotIndex)
        intent.putExtra("phone_type", targetSlotIndex)
        intent.putExtra("slot_id", targetSlotIndex)
        intent.putExtra("com.android.phone.DialingMode", targetSlotIndex)

        // Check active subscriptions to get subscriptionId and iccId
        var targetSubId: Int? = null
        var targetIccId = ""
        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subscriptionManager != null && hasPhoneStatePermission(context)) {
                val list = subscriptionManager.activeSubscriptionInfoList
                val subInfo = list?.firstOrNull { it.simSlotIndex == targetSlotIndex }
                if (subInfo != null) {
                    targetSubId = subInfo.subscriptionId
                    targetIccId = subInfo.iccId ?: ""
                    intent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subInfo.subscriptionId)
                    intent.putExtra("subscription", subInfo.subscriptionId)
                    intent.putExtra("subId", subInfo.subscriptionId)
                    intent.putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE", subInfo.subscriptionId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set Subscription ID: ${e.message}")
        }

        // TelecomManager PhoneAccountHandle matching
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null && hasPhoneStatePermission(context)) {
                val callCapableAccounts = telecomManager.callCapablePhoneAccounts
                if (callCapableAccounts != null && callCapableAccounts.isNotEmpty()) {
                    val targetSubIdStr = targetSubId?.toString() ?: ""
                    val matchedHandle = callCapableAccounts.find { handle ->
                        val handleId = (handle.id ?: "").lowercase()
                        (targetSubIdStr.isNotEmpty() && handleId == targetSubIdStr) ||
                                handleId == targetSlotStr ||
                                handleId == "slot$targetSlotStr" ||
                                handleId == "sim$targetSlotStr" ||
                                (targetIccId.isNotEmpty() && handleId.contains(targetIccId.lowercase()))
                    } ?: callCapableAccounts.getOrNull(targetSlotIndex)

                    if (matchedHandle != null) {
                        intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, matchedHandle)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set PhoneAccountHandle: ${e.message}")
        }
    }
}
