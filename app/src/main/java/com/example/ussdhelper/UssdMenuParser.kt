package com.example.ussdhelper

data class ParsedUssdMenu(
    val title: String,
    val options: List<Pair<String, String>>,
    val hasConfirmOne: Boolean,
    val isPinPrompt: Boolean
)

object UssdMenuParser {
    private val standardOptionRegex = Regex("""^([\d]+|\*+|#+)\s*[\.\)\:\-]\s*(.+)$""")
    private val spaceOptionRegex = Regex("""^([\d]+|\*+|#+)\s+([A-Za-z].+)$""")
    private val confirmPressRegex = Regex("""(?i)(?:to\s+confirm\s*,?\s*press|press|enter|reply\s+with|reply)\s+([\d]+)\s*(?:to\s+confirm)?""")

    fun parse(text: String): ParsedUssdMenu {
        val options = mutableListOf<Pair<String, String>>()
        val titleBuilder = StringBuilder()
        var hasConfirmOne = false

        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // 1. Check for confirmation phrases like "To confirm press 1"
            if (trimmed.contains("confirm", ignoreCase = true) && trimmed.contains("1")) {
                hasConfirmOne = true
                options.add("1" to "Confirm (Press 1)")
                continue
            }

            // 2. Check standard numbered/starred options e.g. "1. INTERNET", "**. MAIN MENU", "*. BACK"
            val stdMatch = standardOptionRegex.find(trimmed)
            if (stdMatch != null) {
                options.add(stdMatch.groupValues[1] to stdMatch.groupValues[2])
                continue
            }

            // 3. Space-separated options e.g. "1 INTERNET"
            val spaceMatch = spaceOptionRegex.find(trimmed)
            if (spaceMatch != null) {
                options.add(spaceMatch.groupValues[1] to spaceMatch.groupValues[2])
                continue
            }

            // 4. Other confirm press patterns
            val confirmMatch = confirmPressRegex.find(trimmed)
            if (confirmMatch != null && confirmMatch.groupValues[1] == "1") {
                hasConfirmOne = true
                options.add("1" to "Confirm (Press 1)")
                continue
            }

            // Otherwise line belongs to title / description
            if (titleBuilder.isNotEmpty()) titleBuilder.append("\n")
            titleBuilder.append(trimmed)
        }

        var title = titleBuilder.toString()
        if (title.isBlank()) {
            title = if (options.isNotEmpty()) "Select an option" else "Carrier Message"
        }

        val isPinPrompt = text.contains("pin", ignoreCase = true) || text.contains("password", ignoreCase = true)

        return ParsedUssdMenu(
            title = title,
            options = options,
            hasConfirmOne = hasConfirmOne,
            isPinPrompt = isPinPrompt
        )
    }
}
