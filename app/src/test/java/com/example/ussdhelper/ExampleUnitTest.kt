package com.example.ussdhelper

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testUserConfirmationMenuParsing() {
        val rawUssd = """
            You have chosen to purchase a package
            To confirm press 1
            To cancel press other key
            **. Main menu
        """.trimIndent()

        val parsed = UssdMenuParser.parse(rawUssd)

        assertTrue(parsed.hasConfirmOne)
        assertEquals("You have chosen to purchase a package\nTo cancel press other key", parsed.title)

        // Options should include both "1" (Confirm) and "**" (Main menu)
        val optionCodes = parsed.options.map { it.first }
        assertTrue(optionCodes.contains("1"))
        assertTrue(optionCodes.contains("**"))

        val confirmOption = parsed.options.first { it.first == "1" }
        assertEquals("Confirm (Press 1)", confirmOption.second)
    }

    @Test
    fun testPinPromptDetection() {
        val pinPrompt = "Please enter your 4-digit Telebirr PIN to approve the transaction:"
        val parsed = UssdMenuParser.parse(pinPrompt)

        assertTrue(parsed.isPinPrompt)
        assertEquals("Please enter your 4-digit Telebirr PIN to approve the transaction:", parsed.title)
        assertTrue(parsed.options.isEmpty())
    }

    @Test
    fun testMultiStepMenus() {
        val menu = """
            1. INTERNET
            2. VOICE
            3. SOCIAL MEDIA
            *. BACK
            **. MAIN MENU
        """.trimIndent()

        val parsed = UssdMenuParser.parse(menu)
        assertEquals(5, parsed.options.size)
        assertEquals("1", parsed.options[0].first)
        assertEquals("INTERNET", parsed.options[0].second)
        assertEquals("*", parsed.options[3].first)
        assertEquals("**", parsed.options[4].first)
    }
}