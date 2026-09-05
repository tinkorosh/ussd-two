package com.example.ussdhelper

import com.example.ussdhelper.model.SimCardInfo
import com.example.ussdhelper.model.UssdAction
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun simCardInfo_labelFormatting() {
        val sim1 = SimCardInfo(
            slotIndex = 0,
            displayName = "SIM 1",
            carrierName = "Ethio telecom",
            subscriptionId = 1,
            isAvailable = true
        )
        assertEquals("Ethio telecom", sim1.displayLabel)

        val simEmpty = SimCardInfo(
            slotIndex = 1,
            displayName = "SIM 2",
            carrierName = "",
            subscriptionId = -1,
            isAvailable = false
        )
        assertEquals("SIM 2", simEmpty.displayLabel)
    }

    @Test
    fun ussdAction_propertiesIntegrity() {
        val action = UssdAction(
            id = "act-1",
            title = "Check Balance",
            code = "*999#",
            simSlot = 1,
            category = "Balance",
            description = "Query remaining credit"
        )

        assertEquals("act-1", action.id)
        assertEquals("Check Balance", action.title)
        assertEquals("*999#", action.code)
        assertEquals(1, action.simSlot)
        assertEquals("Balance", action.category)
        assertEquals("Query remaining credit", action.description)
    }

    @Test
    fun ussdMenu_parsingRegexMatchesOptions() {
        val rawUssd = """
            Ethio Telecom Services
            1. Airtime Balance
            2. Buy Packages
            3. Telebirr
            4. Exit
        """.trimIndent()

        val regex = Regex("""^([\d]+|\*+|#+)\.\s*(.+)$""")
        val options = mutableListOf<Pair<String, String>>()
        val titleBuilder = StringBuilder()

        for (line in rawUssd.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val match = regex.find(trimmed)
            if (match != null) {
                options.add(match.groupValues[1] to match.groupValues[2])
            } else {
                if (titleBuilder.isNotEmpty()) titleBuilder.append("\n")
                titleBuilder.append(trimmed)
            }
        }

        assertEquals("Ethio Telecom Services", titleBuilder.toString())
        assertEquals(4, options.size)
        assertEquals("1" to "Airtime Balance", options[0])
        assertEquals("2" to "Buy Packages", options[1])
        assertEquals("3" to "Telebirr", options[2])
        assertEquals("4" to "Exit", options[3])
    }
}
