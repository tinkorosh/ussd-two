package com.example.ussdhelper.model

data class SimCardInfo(
    val slotIndex: Int, // 0 for SIM 1, 1 for SIM 2
    val displayName: String,
    val carrierName: String,
    val subscriptionId: Int,
    val iccId: String = "",
    val number: String = "",
    val isAvailable: Boolean = true
) {
    val displayLabel: String
        get() = when {
            carrierName.isNotBlank() && carrierName != "Android" -> carrierName
            displayName.isNotBlank() && displayName != "Android" -> displayName
            else -> "SIM ${slotIndex + 1}"
        }
}
