package com.pocketchat.app.chat

/**
 * FR-041: opt-in slash-command input mode. [parseSlashCommand] only ever
 * recognizes an exact, whole-word command token (plus its trailing argument,
 * where the command takes one) — anything starting with "/" that isn't one
 * of these exact commands returns null and falls through to being sent as a
 * normal chat message, so a message that merely starts with "/" (a path, a
 * date like "9/6", etc.) is never silently swallowed.
 */
sealed interface SlashCommand {
    data object Clear : SlashCommand
    data object Settings : SlashCommand
    data object Models : SlashCommand
    data object About : SlashCommand
    data object Memory : SlashCommand
    data class MemorySearch(val query: String) : SlashCommand
    data class Search(val query: String) : SlashCommand
    data object Help : SlashCommand
}

private const val HELP_TEXT = """available commands:
/clear - clear the chat (same as [clear])
/settings - open settings
/models - open model manager
/memory - open the memory viewer
/memory search <query> - open the memory viewer with a search already run
/search <query> - search this chat's scrollback
/about - open the about screen
/help - show this list"""

fun helpText(): String = HELP_TEXT

fun parseSlashCommand(text: String): SlashCommand? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) return null

    val spaceIndex = trimmed.indexOf(' ')
    val head = if (spaceIndex == -1) trimmed else trimmed.substring(0, spaceIndex)
    val rest = if (spaceIndex == -1) "" else trimmed.substring(spaceIndex + 1).trim()

    return when (head) {
        "/clear" -> SlashCommand.Clear
        "/settings" -> SlashCommand.Settings
        "/models" -> SlashCommand.Models
        "/about" -> SlashCommand.About
        "/help" -> SlashCommand.Help
        "/memory" -> if (rest.startsWith("search ")) {
            val query = rest.removePrefix("search ").trim()
            if (query.isNotEmpty()) SlashCommand.MemorySearch(query) else SlashCommand.Memory
        } else {
            SlashCommand.Memory
        }
        "/search" -> if (rest.isNotEmpty()) SlashCommand.Search(rest) else null
        else -> null
    }
}
