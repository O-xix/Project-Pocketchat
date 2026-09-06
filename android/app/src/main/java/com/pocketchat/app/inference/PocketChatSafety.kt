package com.pocketchat.app.inference

/** Result of a [PocketChatSafety.check] call. */
data class SafetyResult(val blocked: Boolean, val category: String)

/**
 * Tier-1 lexical safety filter (see REQUIREMENTS.md FR-015/NFR-013): a
 * deterministic, offline check against a small curated list of known-hazardous
 * markers, run before a prompt ever reaches the model. Cheap and synchronous —
 * safe to call on the main thread, no model or context needed.
 */
object PocketChatSafety {

    /** Checks `text` against the lexical filter. Category is "" when not blocked. */
    fun check(text: String): SafetyResult {
        val blocked = PocketChatEngine.nativeSafetyCheck(text) != 0
        val category = if (blocked) PocketChatEngine.nativeSafetyLastCategory() else ""
        return SafetyResult(blocked, category)
    }
}
