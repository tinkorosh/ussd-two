package com.example.ussdhelper.model

import java.util.UUID
import org.json.JSONObject

data class UssdAction(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var code: String,
    var simSlot: Int = 0, // 0 = Auto / Ask, 1 = SIM 1, 2 = SIM 2
    var category: String = "Balance", // Balance, Data, Money, Voice, Utilities, Custom
    var description: String = "",
    var isFavorite: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("code", code)
        obj.put("simSlot", simSlot)
        obj.put("category", category)
        obj.put("description", description)
        obj.put("isFavorite", isFavorite)
        obj.put("createdAt", createdAt)
        return obj
    }

    companion object {
        fun fromJson(json: JSONObject): UssdAction {
            return UssdAction(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", "USSD Action"),
                code = json.optString("code", "*999#"),
                simSlot = json.optInt("simSlot", 0),
                category = json.optString("category", "Balance"),
                description = json.optString("description", ""),
                isFavorite = json.optBoolean("isFavorite", false),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
