package com.pocketchat.app.models

enum class RamTier(val label: String, val minRamBytes: Long) {
    FLOOR("floor", 0L),
    MID("mid", 5L * 1024 * 1024 * 1024),
    HIGH("high", 9L * 1024 * 1024 * 1024),
    ;

    companion object {
        fun recommendedFor(totalRamBytes: Long): RamTier =
            entries.sortedByDescending { it.minRamBytes }.first { totalRamBytes >= it.minRamBytes }
    }
}

data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val quant: String,
    val url: String,
    val filename: String,
    val approxSizeBytes: Long,
    val tier: RamTier,
)

/**
 * A small, hand-verified starter list (each URL/size checked against Hugging
 * Face directly) — not the curated, benchmarked docs/models.md from Phase 6
 * of PLAN.md, just enough to make the model manager screen functional.
 */
object ModelCatalog {
    val entries: List<ModelCatalogEntry> = listOf(
        ModelCatalogEntry(
            id = "qwen2.5-0.5b-instruct-q4_k_m",
            displayName = "Qwen2.5 0.5B Instruct",
            quant = "Q4_K_M",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            approxSizeBytes = 491_400_032L,
            tier = RamTier.FLOOR,
        ),
        ModelCatalogEntry(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            displayName = "Qwen2.5 1.5B Instruct",
            quant = "Q4_K_M",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            approxSizeBytes = 1_117_320_736L,
            tier = RamTier.MID,
        ),
        ModelCatalogEntry(
            id = "qwen2.5-3b-instruct-q4_k_m",
            displayName = "Qwen2.5 3B Instruct",
            quant = "Q4_K_M",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            filename = "qwen2.5-3b-instruct-q4_k_m.gguf",
            approxSizeBytes = 2_104_932_768L,
            tier = RamTier.HIGH,
        ),
        // 7B+ GGUF repos on Hugging Face are typically split into multiple
        // shard files at this quant level, and core/inference only loads
        // single-file models today (llama_model_load_from_file, not the
        // _from_splits variant) — add a bigger tier once that's supported.
    )
}
