package com.pocketchat.app.models

import android.content.Context

/**
 * FR-043: rolling on-device time-to-first-token/tokens-per-second averages,
 * keyed per model filename — measured locally from real generations (never
 * a published benchmark number), and never leaves the device (NFR-001).
 * Uses its own SharedPreferences file rather than the shared "pocketchat"
 * one: unlike that file's small fixed key set, this has two dynamically-
 * named keys per model, which doesn't fit that file's existing shape as
 * cleanly.
 */
object ResponseStatsStorage {
    private const val PREFS_NAME = "pocketchat_response_stats"

    // Exponential moving average: simplest way to keep a "rolling average"
    // per model without also having to persist a sample count. Weights the
    // last few generations most heavily while still smoothing out one-off
    // outliers (e.g. a cold start right after a model load).
    private const val ALPHA = 0.3f

    data class Stats(val ttftSeconds: Float, val tokensPerSecond: Float)

    fun record(context: Context, modelFilename: String, ttftSeconds: Float, tokensPerSecond: Float) {
        val prefs = prefs(context)
        val existing = stats(context, modelFilename)
        val newTtft = existing?.let { it.ttftSeconds * (1 - ALPHA) + ttftSeconds * ALPHA } ?: ttftSeconds
        val newTps = existing?.let { it.tokensPerSecond * (1 - ALPHA) + tokensPerSecond * ALPHA } ?: tokensPerSecond
        prefs.edit()
            .putFloat(ttftKey(modelFilename), newTtft)
            .putFloat(tpsKey(modelFilename), newTps)
            .apply()
    }

    /** Null until at least one generation has completed for this model. */
    fun stats(context: Context, modelFilename: String): Stats? {
        val prefs = prefs(context)
        val ttft = prefs.getFloat(ttftKey(modelFilename), -1f)
        val tps = prefs.getFloat(tpsKey(modelFilename), -1f)
        return if (ttft < 0f || tps < 0f) null else Stats(ttft, tps)
    }

    private fun ttftKey(filename: String) = "ttft_$filename"
    private fun tpsKey(filename: String) = "tps_$filename"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
