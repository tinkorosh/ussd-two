package com.example.ussdhelper.data

import android.content.Context
import android.content.SharedPreferences
import com.example.ussdhelper.model.UssdAction
import org.json.JSONArray

class UssdActionRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActions(): MutableList<UssdAction> {
        val jsonString = prefs.getString(KEY_ACTIONS, null)
        if (jsonString.isNullOrBlank()) {
            val defaults = getDefaultActions()
            saveActions(defaults)
            return defaults
        }

        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<UssdAction>()
            for (i in 0 until jsonArray.length()) {
                list.add(UssdAction.fromJson(jsonArray.getJSONObject(i)))
            }
            if (list.isEmpty()) {
                val defaults = getDefaultActions()
                saveActions(defaults)
                defaults
            } else {
                list
            }
        } catch (e: Exception) {
            val defaults = getDefaultActions()
            saveActions(defaults)
            defaults
        }
    }

    fun saveActions(actions: List<UssdAction>) {
        val jsonArray = JSONArray()
        actions.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_ACTIONS, jsonArray.toString()).apply()
    }

    fun addAction(action: UssdAction) {
        val list = getActions()
        list.add(0, action)
        saveActions(list)
    }

    fun updateAction(action: UssdAction) {
        val list = getActions()
        val index = list.indexOfFirst { it.id == action.id }
        if (index != -1) {
            list[index] = action
            saveActions(list)
        }
    }

    fun deleteAction(id: String) {
        val list = getActions()
        list.removeAll { it.id == id }
        saveActions(list)
    }

    fun resetToDefaults(): List<UssdAction> {
        val defaults = getDefaultActions()
        saveActions(defaults)
        return defaults
    }

    fun addPresetCarrier(carrier: String): List<UssdAction> {
        val current = getActions()
        val presets = when (carrier.lowercase()) {
            "safaricom" -> listOf(
                UssdAction(title = "Safaricom Balance", code = "*100#", category = "Balance", description = "Check airtime and core balance"),
                UssdAction(title = "M-Pesa Menu", code = "*334#", category = "Money", description = "Send money, pay bill, buy goods"),
                UssdAction(title = "Buy Data Bundles", code = "*544#", category = "Data", description = "Browse internet package options"),
                UssdAction(title = "Account Info", code = "*144#", category = "Utilities", description = "Check remaining bundle quotas")
            )
            "ethio telecom" -> listOf(
                UssdAction(title = "Ethio Balance", code = "*999#", category = "Balance", description = "Check airtime balance"),
                UssdAction(title = "Buy Internet Packages", code = "*777#", category = "Data", description = "Daily, weekly and monthly data"),
                UssdAction(title = "Telebirr Financial", code = "*127#", category = "Money", description = "Telebirr mobile wallet menu"),
                UssdAction(title = "Voice Packages", code = "*804#", category = "Data", description = "Voice combo packages"),
                UssdAction(title = "Customer Services", code = "*800#", category = "Utilities", description = "Self-care and SIM management")
            )
            else -> listOf(
                UssdAction(title = "Check Balance", code = "*123#", category = "Balance", description = "General balance check"),
                UssdAction(title = "Recharge Voucher", code = "*121#", category = "Money", description = "Scratch card top-up"),
                UssdAction(title = "Data Packages", code = "*131#", category = "Data", description = "Internet plans and activations"),
                UssdAction(title = "Check Device IMEI", code = "*#06#", category = "Utilities", description = "Hardware identity code")
            )
        }

        // Avoid adding duplicate codes
        for (preset in presets) {
            if (current.none { it.code == preset.code }) {
                current.add(preset)
            }
        }
        saveActions(current)
        return current
    }

    private fun getDefaultActions(): MutableList<UssdAction> {
        return mutableListOf(
            UssdAction(
                title = "Check Airtime Balance",
                code = "*999#",
                simSlot = 1,
                category = "Balance",
                description = "Quick query for remaining main airtime"
            ),
            UssdAction(
                title = "Buy Data Packages",
                code = "*777#",
                simSlot = 1,
                category = "Data",
                description = "Purchase daily, weekly, or unlimited data"
            ),
            UssdAction(
                title = "Mobile Wallet / Telebirr",
                code = "*127#",
                simSlot = 1,
                category = "Money",
                description = "Access financial services and cash transfer"
            ),
            UssdAction(
                title = "Self-Service & Info",
                code = "*800#",
                simSlot = 0,
                category = "Utilities",
                description = "Account overview and operator services"
            ),
            UssdAction(
                title = "Voice & Combo Bundles",
                code = "*804#",
                simSlot = 0,
                category = "Data",
                description = "Minutes and SMS bundles"
            )
        )
    }

    companion object {
        private const val PREFS_NAME = "ussd_helper_prefs"
        private const val KEY_ACTIONS = "saved_ussd_actions"
    }
}
