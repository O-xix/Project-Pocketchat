# PocketChat — Functional & Non-Functional Requirements

Living spec. Every requirement gets a stable, unique code (`FR-NNN` / `NFR-NNN`) that never gets reused or renumbered, even if the requirement is later dropped — codes are the join key between this document, commit messages, and Linear issues (embed the code in the issue title so status/assignment can live in Linear while this document stays the rationale record).

**Workflow:** this document is the source of truth for *what* and *why*. Linear owns *status/assignment/sequencing*. When scope actually changes, edit this document first, then reflect it in Linear — don't let the two drift by editing Linear alone.

**Status values:** `Done` (shipped and verified), `Planned` (ticketed, not started), `Deferred` (deliberately postponed pending a trigger condition), `Idea` (raised, not yet committed to).

## Functional Requirements

| Code | Title | Status | PLAN.md Phase | Linear |
|---|---|---|---|---|
| FR-001 | Local on-device LLM chat inference | Done | 1 | — |
| FR-002 | Terminal-styled chat UI | Done | 2 | — |
| FR-003 | Model manager: download/activate/delete GGUF models | Done | 2 | — |
| FR-004 | RAM-tier detection with model recommendation | Done | 2 | — |
| FR-005 | Resumable, connectivity-aware model downloads | Done | 2 | — |
| FR-006 | Bundled default model (no first-run download required) | Done | 2, 6 | — |
| FR-007 | Persistent memory: model-generated fact extraction | Done | 3 | — |
| FR-008 | Persistent memory: rolling session summaries | Done | 3 | — |
| FR-009 | Memory injected as chat context automatically | Done | 3 | — |
| FR-010 | Memory-update progress monitoring UI | Done | 3 | — |
| FR-011 | Memory viewer screen (read-only) | Done | 3 | — |
| FR-012 | Bring-your-own-model (custom GGUF import) | Done | 2 | PRO-6 |
| FR-013 | Memory retrieval by relevance, not recency (FTS5) | Done | 3 | PRO-9 |
| FR-014 | Configurable memory voice/mode | Done | 3 | PRO-10 |
| FR-015 | Offline safety filtering — tier 1 (deterministic lexical filter) | Done | new | PRO-7 |
| FR-016 | iOS app | Not started | 4 | PRO-17 |
| FR-017 | Curated model-tiering docs (`docs/models.md`) | Not started | 6 | PRO-18 |
| FR-018 | Semantic/vector memory search | Deferred | 3 | PRO-11 |
| FR-019 | Memory export/backup (single zip via share sheet) | Idea | 3 | PRO-19 |
| FR-020 | Memory on/off toggle (privacy opt-out) | Done | 3 | PRO-20 |
| FR-021 | Manual debug/crash log export | Done | — | PRO-21 |
| FR-022 | Post-session summary review prompt (in-app) | Done | 3 | PRO-22 |
| FR-023 | User-authored annotations on summaries | Done | 3 | PRO-23 |
| FR-024 | Configurable summary scope (opt-in/threshold, not every session) | Idea | 3 | PRO-12 |
| FR-025 | System-level notification for summary-ready review | Idea | 3 | PRO-13 |
| FR-026 | Manual search box in memory viewer | Done | 3 | PRO-24 |
| FR-027 | Fact history/versioning for profile.txt | Idea | 3 | PRO-14 |
| FR-028 | Stop generation (ESC/stop button), edit-and-resubmit | Done | 1, 2 | PRO-25 |
| FR-029 | Clear/reset visible chat, with pre-clear summary | Done | 2, 3 | PRO-26 |
| FR-030 | Regenerate last response | Idea (uncommitted) | 2 | — |
| FR-031 | Custom system prompt / persona override | Done | 2 | PRO-15 |
| FR-032 | Dedicated Settings screen | Done | 2 | PRO-27 |
| FR-033 | Selective memory deletion (individual summary/fact) | Done | 3 | PRO-28 |
| FR-034 | Model switch replays transcript as real context, not just display | Done | 2 | PRO-29 |
| FR-035 | Specific explanation shown when a prompt is safety-blocked | Done | new | PRO-30 |
| FR-036 | First-launch onboarding/setup screen | Done | 2 | PRO-16 |
| FR-037 | Free-storage check before model download | Done | 2 | PRO-31 |
| FR-038 | Per-message copy/share actions | Done | 2 | PRO-32 |
| FR-039 | About/legal screen (license, version, source link) | Done | 2 | PRO-33 |
| FR-040 | Search within the live (not-yet-summarized) chat scrollback | Done | 2 | PRO-34 |
| FR-041 | Slash-command input mode (opt-in) | Done | 2 | PRO-35 |
| FR-042 | Background-resilient generation with completion notification | Done | 2 | PRO-36 |
| FR-043 | On-device response-time stats (TTFT + tokens/sec) | Done | 2, 6 | PRO-37 |

### FR-001 — Local on-device LLM chat inference
Chat generation runs entirely through `core/inference` (llama.cpp wrapper), with no network call in the generation path. Chat-template-based multi-turn prompting with KV-cache-aware incremental context (`prev_len` bookkeeping).
**Why:** the entire premise of PocketChat — see PLAN.md's "no servers, no cloud inference" framing and `docs/research-sovereign-on-device-llms.md`'s privacy-maximalist/austere-operator archetypes.

### FR-002 — Terminal-styled chat UI
Jetpack Compose, hand-built terminal aesthetic (black background, monospace, streaming token output), manual `Screen` enum navigation rather than navigation-compose.
**Why:** deliberate visual identity decision (PLAN.md "UI / Visual design"), not a technical constraint.

### FR-003 — Model manager: download/activate/delete GGUF models
In-app screen to browse a model catalog, download a `.gguf`, switch the active model, delete one to reclaim storage.
**Why:** PLAN.md Phase 2 — user has both a low-RAM floor device and a 12GB S25 Ultra, needs to opt into bigger models when hardware allows.

### FR-004 — RAM-tier detection with model recommendation
`RamTier` (FLOOR/MID/HIGH) computed from device RAM, used to recommend (not enforce — see NFR-014) a catalog entry.
**Why:** matches the hardware-tier tables in `docs/research-consumer-demand-and-engineering.md` — different chipsets/RAM classes need different default model sizes to hit the 15-20 tok/s "interactive" bar.

### FR-005 — Resumable, connectivity-aware model downloads
`Range`-header resumable HTTP downloads; pauses (not permanent-fails) on disconnect; retries indefinitely once `ConnectivityManager` reports the network is back, rather than blind timed polling; manual retry button.
**Why:** direct response to real spotty-mobile-network failures the user hit (21Mbps/133ms network, then a genuine DNS-resolution failure repeatedly pausing at ≤1%). Originally shipped with a retry cap; the cap was removed after the DNS-failure report because it doesn't match real-world outage durations.

### FR-006 — Bundled default model
App ships with a model already extracted/ready at first launch — no mandatory first-run download.
**Why:** user explicitly asked to make the app "a minimal download" experience out of the box, subject to the NFR-003 size budget.

### FR-007 — Persistent memory: fact extraction
After a session, the *loaded model itself* extracts durable facts about the user into `memory/profile.txt`.
**Why:** PLAN.md Phase 3 — keeps context small/relevant instead of replaying full transcripts.

### FR-008 — Persistent memory: rolling session summaries
Each session also produces a timestamped summary file under `memory/summaries/`.
**Why:** same as FR-007 — bounded, human-readable history instead of raw logs (PLAN.md non-goal: "no comprehensive raw chat-log storage").

### FR-009 — Memory injected as chat context automatically
`profile.txt` + summaries are built into a context string (`pc_memory_build_context`) and injected as system prompt at session start, with periodic in-session updates.
**Why:** the actual payoff of FR-007/008 — memory that isn't surfaced back into conversation isn't doing anything.
**Resolved:** the selection is now a *relevance* window (FTS5/BM25), not a pure *recency* one — see FR-013.

### FR-010 — Memory-update progress monitoring UI
Chat screen shows live phase (`extracting facts` / `summarizing`) and streamed text while a memory update runs, instead of a static "updating memory_" line.
**Why:** user-requested after trying the app — updates could previously look hung with no feedback.

### FR-011 — Memory viewer screen (read-only)
A screen to view `profile.txt` and all summaries, sorted newest-first.
**Why:** user-requested — wanted visibility into what the app has stored, paired with NFR-006 (no edit path).

### FR-012 — Bring-your-own-model
Accept a direct `.gguf` URL or local file (Storage Access Framework) instead of only the hardcoded catalog, with a GGUF-header validation check before treating it as loadable.
**Why:** `docs/research-consumer-demand-and-engineering.md` — BYOM is a named demand driver among advanced local-AI users; current `ModelCatalog.kt` is a 3-entry hardcoded list.
**Safety note:** FR-015's tier-1 filter operates on prompts at the `core/` level regardless of which model is active, so BYOM needs no additional per-model safety restriction beyond the GGUF-header validity check — see NFR-013.
**Shipped:** both input modes. A pasted URL (`ModelManagerViewModel.addCustomUrlModel`) constructs a `ModelCatalogEntry` with `approxSizeBytes = 0` (unknown ahead of time for an arbitrary link — resolved from the real `Content-Length` once the download's response headers arrive, same as the existing catalog download path) and reuses the existing resumable-download machinery unchanged. A local file (SAF, `ActivityResultContracts.OpenDocument()` via Compose's `rememberLauncherForActivityResult`) is copied into the app's own models directory — `nativeLoadModel` needs a real filesystem path, not a `content://` URI — then validated. **GGUF validation:** `isValidGgufFile` (`ModelStorage.kt`) checks the first 4 bytes match the GGUF spec's magic ("GGUF" in ASCII); an invalid file is deleted immediately with a specific error rather than left around to fail unhelpfully later inside `core/inference`. Both paths persist through `CustomModelStorage` — a plain JSON file living alongside the `.gguf` files it describes (matching `ChatStorage`'s plain-file pattern for a structured list) — merged with `ModelCatalog.entries` in `refresh()`, so custom entries get identical row treatment (download/activate/delete) to catalog ones. Deleting a custom entry also removes its `CustomModelStorage` record (a no-op for a real catalog entry) so it doesn't reappear as a phantom row.

### FR-013 — Memory retrieval by relevance, not recency
Replace `pc_memory_build_context`'s most-recent-N-summaries selection with a SQLite FTS5 (BM25) query against the current conversation, falling back to recency when there's no strong match. The `.txt` files remain the single source of truth; the FTS5 index is a derived, rebuildable artifact.
**Why:** the original intent behind memory was surfacing the user's *relevant* past experience, not just their *recent* one — a memory from months ago currently ages out of context even though the file is still there and the viewer can still show it (see FR-011). Chosen over vector/semantic search for now because FTS5 is OS-provided on both platforms (no new runtime, no download), whereas semantic search would add a competing compute pass on hardware where NFR-011 is still open — see FR-018.
**Shipped:** `pc_memory_build_context` now takes a `query` parameter (the current turn's message; `ChatViewModel.sendMessage()` passes the user's just-typed text, rebuilding the system prompt fresh every turn instead of once at model load). `relevant_summary_files()` (core/memory/pocketchat_memory.cpp) builds a throwaway **in-memory** (`:memory:`, never persisted to disk) SQLite FTS5 index of every summary's text on each call — corpus sizes here are small enough that rebuilding costs a negligible fraction of a millisecond, so there's no on-disk index format to ever migrate or let go stale. The query is tokenized, lowercased, and stopword-filtered (`sanitize_fts5_query`/`is_stopword`), then every remaining token is safely double-quoted and OR'd together so arbitrary chat text can never be misparsed as FTS5 query syntax. Falls back to the original recency-window selection whenever there's no strong match: FTS5 unavailable, no usable query terms after stopword-filtering, or zero matches. Verified locally (Termux's `libsqlite3` — confirmed FTS5-enabled) with a synthetic 5-summary corpus: topical queries correctly surfaced the one matching summary instead of the two most recent, and empty/punctuation-only/no queries correctly fell back to recency.
**Deviation from "ships with the OS, no new dependency":** the original plan was to link the system SQLite on both platforms rather than add a new dependency. That works on desktop/Termux (`find_package(SQLite3)` finds real dev headers there) but broke Android CI outright — the NDK exposes `libsqlite3.so` as a linkable stub but does not bundle `sqlite3.h`, so `find_package(SQLite3 REQUIRED)` failed at CMake-configure time (discovered by shipping this and watching CI fail, not assumed upfront; see the first CI run on commit `28b5d35`). Fixed by vendoring the official amalgamation (`core/third_party/sqlite3/`, public-domain single-file `sqlite3.c`/`sqlite3.h`, checksum-verified against sqlite.org's own published hash at fetch time) compiled with `SQLITE_ENABLE_FTS5` instead. This guarantees identical FTS5 availability on every Android API level this app targets (floor is API 26, NFR-014) rather than depending on NDK header support that turned out not to exist — a stronger outcome than the original system-linking plan would even have given, since Android's bundled SQLite only gained FTS5 around API 30 regardless. `SQLITE_OMIT_LOAD_EXTENSION` is also set (no legitimate need to load native extensions into an embedded per-device index).
**Known limitation:** annotation weighting aside (see FR-023), match quality beyond the top result isn't score-gated — any document sharing even one non-stopword token is included up to `max_summaries`, which can seat a weak match alongside a strong one rather than truly falling back to recency for the weak slots. Revisit with real usage evidence, not a guessed threshold, per this project's usual bar for tuning knobs like this.

### FR-014 — Configurable memory voice/mode
A setting controlling the extraction/summarization system prompt: neutral-factual (default) vs. an opt-in reflective/experience-validating mode.
**Why:** the original memory design assumed one voice (an assistant that validates the user's experience), but PocketChat's user archetypes diverge — a privacy-maximalist professional wants factual continuity, not emotional reflection; see the archetype table in `docs/research-sovereign-on-device-llms.md`.
**Related:** distinct from FR-020 (turning memory off entirely) — this changes memory's *tone*, FR-020 changes whether it *exists* for this user at all.
**Shipped:** `pc_memory_update_session` (core/memory) takes a new `pc_memory_voice` argument (`PC_MEMORY_VOICE_NEUTRAL` default, `PC_MEMORY_VOICE_REFLECTIVE`) and branches both the fact-extraction and summarization prompts on it. **Both branches still require "plain short lines" for profile.txt** — REFLECTIVE only changes each line's wording (warmer, validating) not the document's structure, since FR-033's per-line fact deletion depends on that structure regardless of voice; this constraint was caught and applied deliberately, not an oversight. `SettingsScreen.kt` exposes a basic two-option `[neutral]`/`[reflective]` picker per the ticket's explicit "keep it simple to start" scope, backed by `SettingsStorage.memoryVoice`. Compiles cleanly and the prompt-branching logic was reviewed directly (a simple string-selection `if`/`else`), but **not exercised against a real model** — this environment has no `.gguf` file to run actual inference with, so the two prompts' real output quality is unverified beyond code review.

### FR-015 — Offline safety filtering, tier 1
A deterministic Aho-Corasick lexical filter in `core/` (shared, not Android-only) screening prompts against known-hazardous markers before inference runs at all. Always-on, not user-toggleable in any build.
**Why:** PocketChat currently has zero safety layer beyond the base model's own alignment. `docs/research-sovereign-on-device-llms.md`'s defense-in-depth stack starts here as the cheapest tier; `docs/research-consumer-demand-and-engineering.md` separately confirms app-store review requires native moderation for local-inference apps. This tier alone does not catch jailbreaks/encoded prompts, and deliberately does not touch general creative-writing/roleplay content — it only matches literal weapon-synthesis/precursor-chemical markers, so it does not conflict with PLAN.md's "uncensored domain specialist" target archetype. See NFR-013 for the decision to stop the safety stack here for now.
**Shipped:** `core/safety/` implements a from-scratch Aho-Corasick automaton (trie + failure links, built once as a static instance) matching ~25 named markers across three categories (`chemical-weapons`, `biological-weapons`, `explosives`) — case-insensitive substring matching, no synthesis details/ratios/steps in the pattern list itself. Standalone, zero dependency on `core/inference`/llama.cpp, exercised via its own `pocketchat_safety_cli` REPL tool. Wired into Android via a JNI bridge (`nativeSafetyCheck`/`nativeSafetyLastCategory`) and a `PocketChatSafety` Kotlin wrapper, called synchronously in `ChatViewModel.sendMessage()` before the prompt is appended to the transcript or sent to the model. Verified: benign prompts pass clean, known markers (including mixed-case) are blocked with the correct category, and near-miss non-adjacent-word cases correctly stay clean (genuine substring matching, not fuzzy/proximity).

### FR-016 — iOS app
Swift/SwiftUI port of the same UI concepts, calling `core/` via an XCFramework.
**Why:** PLAN.md Phase 4; not started. `docs/research-consumer-demand-and-engineering.md`'s iOS-specific background-GPU-lockout / Core ML tiered-handoff findings apply directly here and should be designed fresh when this phase starts, not retrofitted.

### FR-017 — Curated model-tiering docs
`docs/models.md`: tested GGUF models per RAM tier with expected tokens/sec.
**Why:** PLAN.md Phase 6; not started. Should incorporate the throughput table in `docs/research-consumer-demand-and-engineering.md` as a starting reference, re-benchmarked on our actual target devices rather than taken at face value.
**Decision:** the catalog (FR-003) stays Qwen2.5-only until this phase — deliberately not expanding the architecture list ad hoc; do it once, properly, with real benchmarks.

### FR-018 — Semantic/vector memory search
Upgrade FR-013's keyword matching to meaning-based recall (e.g., "I got promoted" surfacing under a later "my career" query with no shared keywords), via either a bundled ONNX embedding model or reuse of `core/inference`'s existing llama.cpp embedding mode with a small embedding GGUF.
**Why deferred:** real compute/battery cost on top of the existing fact-extraction + summarization passes (see NFR-011), plus a second index (`sqlite-vec`, not OS-provided) that must stay consistent with the source files. **Trigger to un-defer:** real usage evidence after FR-013 ships that keyword matching is actually missing relevant memories often enough to justify the cost — not a timeline.

### FR-019 — Memory export/backup
A single-action export of `profile.txt` + all `summaries/*.txt` as one zip, sent through the platform share sheet.
**Why:** NFR-006 blocks in-app editing but shouldn't block the user taking their own data with them — consistent with the local-data-ownership principle behind NFR-001/002. Single-zip chosen over per-file share for simpler UX around the primary use case (backup/migration), at the cost of less granularity.

### FR-020 — Memory on/off toggle
A setting to disable the memory system entirely: no fact extraction, no summaries, no context injection.
**Why:** not every target archetype wants a persistent profile built even when it never leaves the device — a privacy-maximalist professional handling privileged/confidential material may want zero aggregation of what they discuss. Should be the user's explicit choice rather than assumed. Distinct from FR-014 (tone) — this is existence, not voice.
**Shipped:** `SettingsStorage.isMemoryEnabled` (default on) gates both `ChatViewModel.forceMemoryUpdate()` (a no-op while disabled — no fact extraction, no summaries) and `buildSystemPrompt()` (returns before ever calling `PocketChatMemory.buildContext` — no context injection). Disabling only stops *future* extraction/injection; it doesn't touch or hide already-stored `profile.txt`/summaries, which stay reachable via the memory viewer (FR-011) regardless — this is a forward-looking opt-out, not a delete (FR-033 already covers deletion). Exposed in `SettingsScreen.kt` (FR-032) with NFR-018 confirmation on the off-switching direction only — turning memory back on isn't destructive.

### FR-021 — Manual debug/crash log export
A user-initiated (never automatic) way to export a local debug/crash log, e.g. via share sheet, for attaching to a bug report.
**Why:** clarifies the boundary of NFR-001 (zero telemetry) — nothing leaves the device without the user directly initiating it, so this doesn't violate the zero-telemetry principle while still giving bug reports something better than free-text repro steps.
**Shipped:** a new `DebugLog` object appends timestamped lines to a plain `debug.log` in `context.filesDir`, capped at 512KB (oldest content trimmed first — recent context matters more for a bug report than full history). `PocketChatApplication` (a new `Application` subclass, registered in the manifest) chains a `Thread.setDefaultUncaughtExceptionHandler` at process start so a crash is logged even before any activity exists, then re-raises into the platform's own default handler so the crash still surfaces normally. Two existing `ChatViewModel` catch blocks (`loadModel()`, `sendMessage()`'s generation try/catch) now also log their exception's stack trace — a deliberately modest set of call sites rather than threading logging through every error path in the app. Export is a new "[export debug log]" action in `SettingsScreen.kt` (only shown once `DebugLog.hasContent()` is true), sharing the file via a `FileProvider` (`androidx.core.content.FileProvider`, new `res/xml/file_paths.xml` config, new `<provider>` manifest entry) rather than a raw `file://` URI, which API 24+ blocks for another app's share target.
**Limitations:** only two call sites are wired to log proactively (plus any uncaught crash) — most caught exceptions elsewhere in the app still fail silently as before. `log()` never throws by design, so a filesystem failure while logging is itself swallowed rather than surfaced.

### FR-022 — Post-session summary review prompt
Immediately after a session's summary finishes generating (`PC_MEMORY_PHASE_SUMMARIZING` completes), show the user an in-app prompt to review it, while the conversation is still fresh in their mind.
**Why:** identifies and attacks the actual weak point in FR-013 (relevance search) before it's built — search can only surface what the summary actually captured, and today that capture has zero human check. Reviewing right after the session is when the user is best positioned to notice a summary missed or mangled something. For now, this fires for every session (see FR-024 for the future opt-in/threshold variant) and is in-app only (see FR-025 for the future system-notification variant).
**Shipped:** `ChatViewModel.maybeUpdateMemory()` tracks the `SUMMARIZING`-phase text in a dedicated buffer (kept separate from the shared live-progress buffer specifically so a zero-output summarization pass can never leak stale `EXTRACTING_FACTS` text into the review prompt), and sets `ChatUiState.pendingSummaryReview` once the update completes with a non-empty summary — the same trimmed text `core/memory` just wrote to `summaries/<timestamp>.txt`. `ChatScreen.kt` renders it as a persistent banner (`SummaryReviewBanner`) between the scrollback and the input field, with a `[ok]` action that calls `dismissSummaryReview()`. Read-only, as scoped — no edit/annotate action on the banner itself; that lives in the memory viewer per FR-023.

### FR-023 — User-authored annotations on summaries
Lets the user attach their own note to a summary — stored as a separate, clearly-attributed field alongside the model's summary text, never merged into or overwriting it. Not limited to the one-time FR-022 review moment: the user can add, edit, or delete their own annotation any time from the memory viewer (FR-011), since it's their own content rather than the model's, and NFR-006's immutability guarantee only ever applied to the model-generated text.
**Why:** amends NFR-006's scope (see that entry) — the model's own summary stays immutable and auditable, but the user can supplement it with whatever the model missed, whenever they think of it, not just in a single narrow window right after the session ends. A user-flagged note is presumably the highest-signal text for future recall, so once FR-013 (FTS5 search) and FR-026 (manual search) ship, annotations should be indexed and weighted at least as highly as the model-generated text, likely higher.
**Explicitly out of scope:** regenerating/replacing a summary the user thinks is wrong — for now a bad summary gets a corrective annotation, not a rewrite. Revisit as its own future requirement only if annotation-only proves insufficient in practice.
**Shipped:** each annotation is a sibling `summaries/<timestamp>.annotation.txt` file (plain text, Android-side `MemoryViewerViewModel.saveAnnotation()`/`MemoryViewerScreen.kt`) — the model's own `<timestamp>.txt` is never opened for writing anywhere in this path. `MemoryViewerScreen` shows an inline editable field per summary, pre-filled with the current note (blank if none); saving blank text deletes the file, since "no note" and "empty note" are the same state. Rendered in `TermUser` (the same color already used for the user's own chat lines) specifically to stay visually distinct from the model's summary text per NFR-006's amendment. FR-013 having shipped first, `relevant_summary_files()` now indexes each summary's annotation in a second FTS5 column and ranks it at 3x the weight of the model's own text (`kAnnotationBm25Weight`) — verified locally: a summary whose own text shares no vocabulary with the query was correctly surfaced (and ranked above an incidental one-word match elsewhere) purely via its annotation. A matched annotation is also shown inline in the injected context itself (`[user note: ...]`, directly under its summary), not just used as an invisible ranking signal, since the whole point of a note is to actually reach the model, not just move a ranking score. FR-026 (manual search UI) doesn't exist yet, so "weighted in search results" for that surface is deferred until that ticket exists.
**Bug caught and fixed during implementation:** `fs::path::extension()` only strips the *last* extension component, so `<timestamp>.annotation.txt` also reports `.txt` and was initially being scanned in as a phantom extra "summary" alongside the real ones — corrupting both FTS5 ranking (double-counting the annotation's terms) and the recency fallback. Fixed with an explicit `is_annotation_file()` filename-suffix check in the directory scan; caught via local testing before this shipped, not left for CI/production to find.

### FR-024 — Configurable summary scope
Future control over which conversations actually get summarized — e.g. a length/turn-count threshold, or letting the user opt a specific conversation in/out — instead of FR-022's review prompt firing after every single session regardless of how trivial.
**Why:** raised as a real risk (review fatigue on short/trivial chats) but deliberately not designed in detail yet — ship FR-022 firing on every session first, see how it actually feels in use, then shape this based on that evidence rather than guessing at a threshold now.
**Status:** `Idea`, ticketed ahead of implementation per explicit request — see PRO-12. **Still not started as of the FR-026/FR-029/FR-033 pass:** PRO-12's own ticket text says "do not start until FR-022 has shipped and there's real signal that 'every session' is actually noisy in practice" — FR-022 has shipped, but no real usage exists yet in this environment (no device, no users) to produce that signal. Building a threshold now would be guessing at the exact number this ticket was written to avoid guessing at.

### FR-025 — System-level notification for summary-ready review
Upgrade FR-022's in-app-only prompt to a real Android system notification (status bar), so the user can review a summary even after leaving the app.
**Why:** in-app is sufficient for now since memory updates already run inline while the app is open (`maybeUpdateMemory` in `ChatViewModel`), but a system notification needs `POST_NOTIFICATIONS` permission handling and is real platform-integration work — deliberately sequenced after the in-app version proves the review flow is worth having at all.
**Status:** `Idea`, ticketed ahead of implementation per explicit request — see PRO-13. **Still not started as of the FR-026/FR-029/FR-033 pass:** same reasoning as FR-024 — this ticket's own text sequences it after the in-app version "proves the review flow is worth having at all," which needs real usage, not just FR-022 existing.

### FR-026 — Manual search box in memory viewer
A real search input in `MemoryViewerScreen` (FR-011), letting the user directly look up "that conversation about X" — distinct from FR-013, which only changes what the *model* automatically pulls into its own context. Reuses the same FTS5 index FR-013 builds.
**Why:** this is the actual answer to "how would a user find an old conversation" — FR-013 alone never surfaces anything to the user directly, it only changes the model's behavior. Without this, the user has no way to search their own history at all, only browse it chronologically (which the viewer already supports).
**Depends on:** FR-013's FTS5 index existing first — this is a UI layer on top of it, not a separate index.
**Shipped:** new `pc_memory_search`/`pc_memory_free_search_results` (core/memory) reuse `relevant_summary_files()` (FR-013/FR-023's FTS5+BM25 ranking, including annotation weighting) but — unlike `pc_memory_build_context` — never fall back to recency: a search box silently substituting unrelated recent entries would look broken, so zero matches is reported as zero matches. `MemoryViewerScreen.kt` gained a live search box at the top; a non-blank query replaces the normal profile/summaries view with search results (using the same `SummaryEntryRow`, so annotation editing and deletion both work on search results too), an empty query reverts to the normal view. Verified locally via `pocketchat_memory_cli -d <dir> -s <query>` (new flag, no model needed): a topical query returns exactly the matching summary, an unrelated query returns zero results.

### FR-027 — Fact history/versioning for profile.txt
Track changes to facts in `profile.txt` over time (e.g. a job change overwriting an old entry) instead of silently overwriting with no record of what changed or when.
**Why:** raised as a genuine want ("useful... at some point") but not urgent enough to design now — the immediate gap is overwritten facts have no visible history, which cuts against the "unrestricted view of your own experience" goal the same way FR-013 does for summaries. Old facts remain indirectly recoverable today by browsing old session summaries (FR-011), just not as a first-class changelog.
**Status:** `Idea`, ticketed ahead of implementation per explicit request — see PRO-14.

### FR-028 — Stop generation (ESC/stop button), edit-and-resubmit
Interrupt an in-progress generation via a stop button or ESC, matching the Claude Code pattern: the message that was being processed is restored into the editable input field (not committed as sent), so the user can revise it before resubmitting, rather than just halting with a dead partial response.
**Why:** currently there's no way to interrupt generation at all — the input just stays disabled until the model finishes on its own, even if the response has clearly gone off-track. The edit-and-resubmit behavior (vs. a plain stop) turns a wasted generation into a chance to correct course.
**Technical note:** `core/inference` has no cancellation hook today — llama.cpp's `abort_callback` isn't wired into `pc_generate_chat`. This needs real native plumbing (interrupting an in-flight blocking JNI call safely), not just a UI affordance — related to the pre-existing known limitation that `onCleared()` can race a still-running blocking JNI call.
**Shipped — this technical note turned out to overstate the gap:** `pc_generate_chat`'s existing per-token callback (`run_generation`'s loop already breaks when it returns 0 — this was never actually wired to anything, but the mechanism was already there) is sufficient for the common case: `ChatViewModel.stopGeneration()` sets a `@Volatile` flag the token callback checks every token, no new native API needed. `sendMessage()` tracks it, and on stop: `ctx.reset()` clears the KV cache (which otherwise holds the discarded partial reply — `pc_generate_chat` commits an early-stopped generation's bookkeeping as if it were a real completed turn), the user's own message is popped back out of `ChatUiState.messages`, and its exact text is handed back via a new one-shot `restoredInput` field that `InputPrompt` consumes via `LaunchedEffect`. A `[stop]` menu item appears only while an actual chat reply is streaming (not during a memory update or clear, which don't check the flag). **What's genuinely still not interruptible:** a single in-progress `llama_decode()` call — e.g. a slow prompt prefill right after a context reset — since the callback is only checked between decode calls, one per token. Wiring llama.cpp's separate, finer-grained `abort_callback` for that remains real, not-yet-done native work; in practice it matters only for that specific slow-prefill window, not steady-state token generation, which stops promptly.

### FR-029 — Clear/reset visible chat, with pre-clear summary
A manual `[clear]`-style action (alongside the existing `[models]`/`[memory]` menu items) that resets the visible scrollback. Before clearing, it runs the same summarization pipeline as a natural session end (FR-008), including FR-022's review prompt, so nothing is lost from memory just because the screen was reset.
**Why:** the single-continuous-chat decision assumed memory carries continuity across sessions, but there was no way to deliberately close out a topic and start fresh on-screen — the chat would just grow forever with no user-facing reset, relying entirely on NFR-010's under-the-hood truncation. Triggering a summary first keeps this consistent with FR-008/FR-022 rather than introducing a second, un-summarized way for a conversation to end.
**Shipped:** `ChatViewModel.maybeUpdateMemory()` was split into a threshold check plus `forceMemoryUpdate()` (the actual pipeline, unconditional) so `clearChat()` can force a final update regardless of how many messages have accumulated since the last one, then resets the native context, clears the visible transcript and its on-disk copy (NFR-012), and resets `lastMemoryUpdateIndex`. A `[clear]` menu item (`ChatScreen.kt`) triggers it through the new shared `ConfirmableMenuItem` (`ui/Terminal.kt`) two-tap pattern rather than deleting on a single tap. **No modal confirmation dialog:** the pre-clear summary already means nothing discussed is truly lost, just no longer on-screen, so the two-tap inline confirm was judged sufficient for now — a fuller NFR-018-style dialog (not yet built) can replace it later if that ticket wants consistency across more destructive actions.

### FR-030 — Regenerate last response
Re-run generation for the last assistant response with a new sampling seed, discarding the previous one.
**Why:** raised as a standard chat-app expectation, but usage value is genuinely uncertain — not committed to being built. **Status:** `Idea (uncommitted)`, no Linear ticket yet — revisit only if real usage or user feedback shows this is actually wanted, rather than building it speculatively.

### FR-031 — Custom system prompt / persona override
A setting letting the user directly define/override the chat's system prompt (e.g. a specific character or role), independent of FR-009's automatic memory-context injection.
**Why:** baseline persona continuity — the model already knowing how the user talks and what they know — is already served by FR-009 pulling from `profile.txt`/summaries, so this isn't needed just for that. This is for cases wanting an explicit persona layer on top (e.g. a roleplay character), which directly serves the "uncensored domain specialist" archetype's named want for custom personas and roleplay continuity.
**Shipped:** `SettingsStorage.personaOverride` (a plain string, null when unset) is edited via a free-text field in `SettingsScreen.kt`, mirroring FR-023's annotation-field pattern (blank save clears it). **Composition decision** (the ticket's own text left this "not yet designed"): the override *replaces* `BASE_SYSTEM_PROMPT` as the base text, but FR-009's memory context is still layered on top of either one — chosen because the ticket's own framing calls this "an explicit persona layer on top of" baseline continuity, not a replacement for it, so turning on a persona shouldn't silently turn off memory too. Whether it's global or could vary per FR-029-cleared session was also left open by the ticket; shipped as global (SharedPreferences, same as every other setting here) since nothing yet motivates per-session scoping.

### FR-032 — Dedicated Settings screen
A `[settings]` menu item (alongside the existing `[models]`/`[memory]` header items) consolidating every configurable option: memory on/off (FR-020), memory voice (FR-014), custom persona (FR-031), and anything added later — instead of bolting toggles onto whichever screen happens to be nearby.
**Why:** the number of configurable options grew during this requirements pass to the point that scattering them per-screen would actively hurt discoverability. Centralizing now avoids re-doing this later once even more settings exist.
**Shipped:** new `SettingsScreen.kt`/`SettingsViewModel.kt`, reached via a `[settings]` header menu item next to `[models]`/`[memory]`/`[clear]`. Hosts FR-020's toggle, FR-014's voice picker, and FR-031's persona field — all three backed by the new `SettingsStorage` (plain SharedPreferences, same file `ModelStorage` already uses). No new settings exist yet beyond these three, so this is the shape the ticket asked for without speculatively adding sections nothing populates.

### FR-033 — Selective memory deletion
Delete one specific summary or profile fact directly, without exporting/wiping everything (FR-019) or disabling memory entirely (FR-020).
**Why:** a "right to be forgotten" at the item level — if one captured fact or summary turns out to be sensitive, wrong, or just unwanted, the user shouldn't have to choose between living with it and nuking their entire memory history to get rid of it.
**Interacts with:** FR-013/FR-026 (FTS5 index) — deleting a source `.txt` needs to also remove it from the derived search index, not just leave a stale entry behind. **Already satisfied by construction:** because FR-013's index is a throwaway in-memory structure rebuilt fresh on every single call (never persisted — see FR-013's Shipped note), there is no stale index to clean up; deleting the source file is automatically reflected the next time anything searches or builds context. No extra code was needed for this.
**Shipped:** new `pc_memory_delete_summary(memory_dir, timestamp)` removes a summary's `.txt` and its `.annotation.txt` sibling together (an orphaned annotation with no parent summary makes no sense); new `pc_memory_delete_profile_fact(memory_dir, fact_text)` removes exactly the first `profile.txt` line whose trimmed text matches (profile.txt is one durable fact per line by construction — see `pc_memory_update_session`'s extraction prompt). Both treat "already gone"/"not found" as success, not an error. `MemoryViewerScreen.kt` shows a `[delete]` action per summary and per profile fact, using the new shared `ConfirmableMenuItem` two-tap pattern (see FR-029's Shipped note) rather than deleting on a single tap — judged warranted here specifically (unlike FR-029) because deletion is genuinely irreversible with no summary-based safety net. Verified locally: deleting a summary removes it from a subsequent `pc_memory_search`; deleting one profile fact by exact text leaves the other lines untouched.

### FR-034 — Model switch replays transcript as real context, not just display
When switching the active model (FR-003), the existing visible messages must be replayed into the newly loaded model's actual context (subject to its context window), not just left visually present in the Kotlin UI state while the new native context starts with no knowledge of them.
**Why:** `ChatViewModel`'s `uiState.messages` persisting across a model switch doesn't by itself guarantee the *model* remembers the conversation — `reloadModelIfChanged()` creates a fresh `pc_context` for the new model, and whether that gets the prior messages replayed into it needs verification against the current implementation, not assumed. Called out explicitly because "looks continuous" and "actually is continuous" are different things, and the gap between them would be a confusing, hard-to-notice bug (the user sees old messages on screen and reasonably expects the model to know about them).
**Shipped:** the actual bug was worse than hypothesized — `reloadModelIfChanged()` replaced the whole `ChatUiState` with a fresh default (`messages = emptyList()`), wiping the visible transcript outright on every switch, not just leaving it disconnected from the model. Fixed by `.copy()`-ing only the transient fields instead. No separate "replay" step was needed: `sendMessage()` already sends the full history every call, and a fresh context starts with `prev_len == 0`, so the next message after a switch naturally replays the whole preserved conversation via the existing incremental-context logic. Verified via CI (compiles, produces an APK); on-device confirmation of the actual runtime behavior is still open, since this environment has no device/emulator to run it on.

### FR-035 — Specific explanation shown when a prompt is safety-blocked
When FR-015's tier-1 filter blocks a prompt, tell the user their prompt was blocked and roughly why (e.g. "matched a restricted content pattern"), rather than a generic refusal or silent failure.
**Why:** consistent with NFR-011's philosophy of naming the actual trigger rather than a vague status — a legitimate user who hits a false positive (e.g. an austere-operator survival question brushing against a precursor-chemical pattern) needs enough information to understand what happened and rephrase, not just a dead end.
**Shipped — already, as it turns out:** this was implemented as part of FR-015/PRO-7's own work (`ChatViewModel.sendMessage()` sets `uiState.error = "prompt blocked — matched a restricted content pattern (${safety.category})"` when `PocketChatSafety.check()` flags a prompt), with a comment at the time explicitly noting this ticket by number and deliberately not expanding beyond a plain error message. This pass just formalizes that reality in tracking — no new code was needed. Deeper UX (distinct visual treatment, softer false-positive-specific wording) remains out of scope here, same as originally noted.

### FR-036 — First-launch onboarding/setup screen
A one-time first-launch screen surfacing the memory on/off (FR-020) and memory voice (FR-014) choices explicitly, instead of the user discovering them later in Settings (FR-032).
**Why:** deliberately not built now — first launch drops straight into chat with sensible defaults (memory on, neutral voice) to keep the minimal terminal-app feel and avoid a setup wizard fighting that aesthetic. Ticketed now because the need is real: as more first-run-relevant settings accumulate, an informed-choice-upfront screen becomes more valuable, but there's no urgency to build it before those settings themselves exist.
**Shipped:** the blocking condition noted above is resolved now that FR-020/FR-014/FR-032 (memory toggle, voice picker, Settings screen) all exist — `OnboardingScreen.kt` (new `com.pocketchat.app.onboarding` package) reuses the exact same `MemoryToggleSection`/`MemoryVoiceSection` composables `SettingsScreen.kt` already had (un-privatized for reuse, no logic duplicated), plus a short zero-telemetry/on-device blurb and a `[continue]` button. `SettingsStorage.isOnboardingCompleted`/`setOnboardingCompleted` (default `false`) gate a one-time check in `PocketChatApp.kt`: a fresh install routes to `Screen.Onboarding` first, `[continue]` marks it completed and never revisits it. Deliberately minimal per the original scope note — no persona/other settings included, just the two choices called out in the ticket.
**Limitation:** the check only runs once at process start (a `remember` seeded from `SettingsStorage` at composition), not re-evaluated afterward — an edge case where `SettingsStorage` is cleared externally mid-session (there's no in-app way to do this) wouldn't retroactively show onboarding again without a process restart. Not a real-world concern given how the flag is set.

### FR-037 — Free-storage check before model download
Before starting a model download (FR-003/FR-012), check available device storage against the catalog entry's `approxSizeBytes` (or the target file's reported size for BYOM) and warn if insufficient, rather than letting a multi-GB download fail partway through.
**Why:** a write failure discovered halfway through a long download on a slow connection (see FR-005's real-world spotty-network context) is a worse experience than an upfront warning that costs almost nothing to check.
**Shipped:** `ModelManagerViewModel.startDownloadNow()` checks `StatFs(modelsDir).availableBytes` against the *remaining* bytes needed (`approxSizeBytes` minus whatever a partial file already has — a resumed download isn't blocked by space it no longer needs), reusing the existing `Failed` status/UI (with a specific message, e.g. "need ~1.2gb more, only 300mb free") rather than inventing a new status type. Skipped entirely for FR-012's custom-URL entries before their real size is known (`approxSizeBytes == 0`) rather than guessing.

### FR-038 — Per-message copy/share actions
Let the user copy or share the text of an individual chat message (e.g. a long-press action), rather than only being able to export the whole memory record (FR-019, which covers `profile.txt`/summaries, not raw chat messages).
**Why:** standard chat-app expectation — e.g. pulling a code snippet or specific answer out of a response without retyping it by hand.
**Shipped:** `MessageLine`'s long-press (`Modifier.combinedClickable`) reveals `[copy]`/`[share]` for that one message — a flat text row, not a Material dropdown/context menu, consistent with every other action surface in this app. Copy uses `ClipboardManager`/`ClipData.newPlainText`; share uses a standard `Intent.ACTION_SEND` chooser. No new permissions needed for either.

### FR-039 — About/legal screen
A minimal in-app screen showing app version, AGPLv3 license text, and a link to the public source repository.
**Why:** standard practice for publicly distributing GPL-family-licensed binaries — makes the source-availability expectation easy to satisfy for anyone who receives the app, at very low build cost.
**Shipped:** `AboutScreen.kt` shows the app's `versionName` (via `PackageManager.getPackageInfo`), a tappable link to the public source repo (`Intent.ACTION_VIEW`, the device's own browser — a user-initiated action, not a network call this app makes itself, same NFR-001 reasoning as FR-021's export), and the full AGPLv3 text loaded from a new bundled asset (`assets/LICENSE.txt`, a copy of the repo-root `LICENSE` file) rather than a short notice-and-link, since the ticket calls for the license text itself. Reached via a new `[about]` item at the bottom of `SettingsScreen.kt`, and also via `/about` once FR-041 is enabled.
**Limitation:** `LICENSE.txt` is a committed copy of the root `LICENSE`, not generated at build time — if the root license text is ever revised, this asset needs a manual matching update (a build-time copy step would remove that risk, not done here since it's a small, static file).

### FR-040 — Search within the live chat scrollback
Search the current, still-open conversation directly, before it's ever summarized (FR-008) or reaches FR-026's archived-memory search. Reachable via a `[search]`-style header menu item by default; when FR-041 is enabled, also reachable as `/search <query>` typed directly in the chat input.
**Why:** in a single-continuous-chat design (see the "Chat threads" decision), a session can run long before FR-029's clear action ever fires — "find that thing I said earlier today" shouldn't require waiting for a summary to exist and then searching the archive for it. The header menu item is the permanent, always-available path; `/search` is an accelerator for users who opt into FR-041, not a replacement for it — turning slash commands off never removes functionality, only the shortcut.
**Shipped:** a `[search]` header menu item toggles a search box (new shared `TerminalTextField`, `ui/Terminal.kt`); a non-blank query filters `ChatUiState.messages` to a case-insensitive substring match, replacing the normal status/throttle/streaming lines (which describe what's happening *right now*, not a view of past messages) rather than coexisting with them. Deliberately plain substring matching, not an FTS5 index — the live scrollback is small enough in practice that FR-013/FR-026's index (built for the much larger *archived* memory corpus) would be pure overhead here. `/search` (FR-041) isn't wired up since that ticket doesn't exist yet — this ships only the permanent header-menu-item path the ticket calls the "always-available" one.

### FR-041 — Slash-command input mode (opt-in)
An opt-in input mode (toggle in Settings, FR-032) that recognizes a small fixed set of commands typed directly into the chat input — `/search <query>` (FR-040), `/clear` (FR-029), `/settings` (FR-032), `/memory` (opens the viewer, FR-011, or `/memory search <query>` for FR-026), `/about` (FR-039), `/models` (FR-003) — and routes them to the corresponding action instead of sending them to the model as a chat message. Includes a `/help` command listing what's available.
**Why:** fits PocketChat's terminal aesthetic (PLAN.md's UI design is explicitly terminal/recovery-mode-inspired) better than a search bar or a growing set of header menu items competing for space — commands are the native interaction pattern for that visual identity. Default **off**: target users legitimately type a literal leading `/` in normal messages often enough (technical topics, fractions like "1/4 cup," file paths) that always-on interception risks silently eating real input; it's a discoverable opt-in for users who want the accelerator, not a default behavior change.
**Fallback rule:** only an *exact* match against the fixed known command set intercepts input — anything else starting with `/` (recognized or not) is sent to the model unchanged as a normal message. No partial-match guessing, no rejection of "invalid" commands — a mistyped or coincidental leading slash never blocks or mangles what the user meant to say.
**Non-goal:** does not replace or hide the existing header menu items (`[models]`/`[memory]`/etc.) or FR-032's Settings screen — those remain the permanent, always-available paths regardless of this toggle's state; commands are strictly additive.
**Shipped:** new `SlashCommands.kt` (`parseSlashCommand(text): SlashCommand?`, a sealed interface: `Clear`/`Settings`/`Models`/`About`/`Memory`/`MemorySearch(query)`/`Search(query)`/`Help`) implements exactly the fallback rule above — only a whole command token (`text.trim().substringBefore(' ')`) exactly matching a known command intercepts; anything else, including any other leading-`/` text, returns `null` and falls through as a normal message. `SettingsStorage.isSlashCommandsEnabled` (default `false`) backs a new toggle in `SettingsScreen.kt`. `ChatScreen.kt`'s `handleSubmit()` checks the toggle only when actually submitting, so the check itself has zero cost when off. `PocketChatApp.kt`'s internal `Screen` type became a `sealed interface` (from a plain `enum`) so `MemoryViewer` can carry an optional `initialSearchQuery`, letting `/memory search <query>` land directly on FR-026 results (`MemoryViewerScreen`'s new `initialSearchQuery` param, applied via a one-shot `LaunchedEffect`). `/help` shows a static command list as a dismissible banner, reusing the same visual pattern as FR-022's summary-review banner.
**Limitation:** `/about` opened via slash command from Chat always backs out to Settings (not back to Chat) — `About`'s `onBack` is wired to a fixed destination rather than a navigation stack, since this app has none; a real back-stack is a larger change not warranted by this one edge case.

### FR-042 — Background-resilient generation with completion notification
If the user leaves the app while a response is generating, keep generating in an Android foreground service (with the persistent "generating..." notification Android requires for background CPU work) instead of pausing or losing it, and post a completion notification when the response is ready. Scoped to normal chat responses only for now — memory update passes (FR-010/FR-022) already have their own in-app progress and review flow and aren't included here.
**Why:** a real gap on slower hardware/larger models where a response can take a while — today there's no way to walk away and come back to a finished answer; leaving the app mid-generation either loses progress or leaves the user unsure whether it's still working. Reuses the `POST_NOTIFICATIONS` permission handling already needed for FR-025.
**Relationship to NFR-012:** this is a deliberate, controlled version of "surviving being backgrounded" — a foreground service with a visible notification is the Android-sanctioned way to keep CPU-bound work alive, as opposed to NFR-012's BOOM handling, which is about recovering gracefully *after* an uncontrolled kill.
**Shipped:** `GenerationForegroundService` is a minimal foreground service — its only job is to exist with an active `startForeground()` notification, which is what exempts the hosting *process* (where `ChatViewModel`'s actual generation coroutine runs, completely unchanged) from Android's background CPU/Doze restrictions. The generation pipeline itself was never relocated into service-owned code; a foreground service's mere presence with an active notification is the sanctioned exemption mechanism regardless of which code in the process is doing the work. `ChatViewModel` registers a `ProcessLifecycleOwner` observer: `onStop` (app backgrounded) starts the service only if a genuine chat response is in flight (a dedicated `isGeneratingChatResponse` flag, distinct from the broader `ChatUiState.isGenerating` that memory updates and `clearChat()` also set — scoped to normal chat responses only, per the ticket); `onStart` (app foregrounded again) stops it, since the user can now see completion themselves. The service is also stopped — before `maybeUpdateMemory()` runs — the instant the chat response itself finishes, matching the ticket's explicit "not memory-update passes" scope. A completion notification (`postCompletionNotification`) fires only if the app was still backgrounded when the response finished; tapping it reopens the app.
**Permission handling:** `POST_NOTIFICATIONS` (API 33+) is requested once, best-effort, from `MainActivity.onCreate()` with no rationale dialog — a denial just means both the "generating…" and completion notifications silently don't show (checked before each post; the foreground-service CPU exemption itself doesn't depend on the notification actually being visible, only on `startForeground()` being called).
**`foregroundServiceType="dataSync"`:** none of Android's predefined service types precisely fits "keep an on-device compute task running" — `dataSync` is the conventional choice other apps use for that general shape of background work. The API 34+ `specialUse` type would arguably be a more honest fit, but carries Play-Store-review-justification expectations that don't align with this project's CI-verified-builds-only distribution model (NFR-008); revisit if `dataSync`'s API 34 six-hour runtime cap or its "should actually be syncing data" framing ever becomes a real problem in practice.

### FR-043 — On-device response-time stats
Track and display, per model, a rolling average of time-to-first-token and tokens/sec from the device's own actual usage — shown per catalog entry in the model manager (FR-003), not a fixed published benchmark number.
**Why:** helps the user know what to expect before picking a model, especially relevant on lower-tier hardware (NFR-014) where the gap between models can be large. Measured locally from real on-device generations rather than Phase 6's curated benchmark figures (FR-017), since actual performance on this specific device is more useful than a generic number — and doesn't conflict with NFR-001's zero-telemetry principle, since the stats never leave the device.
**Shipped:** `ChatViewModel.sendMessage()` times from just before `generateChat()` is called to the first token (TTFT) and from the first token to the last (tokens/sec = `(tokenCount - 1) / elapsedSeconds`, excluding the first token itself so the rate reflects steady-state decode, not prefill latency) — measured regardless of how generation ends (completed, stopped, or erroring after some tokens), since a partial run still reflects real device throughput. `ResponseStatsStorage` keeps a simple exponential moving average per model filename (its own SharedPreferences file, since unlike the shared "pocketchat" settings file this has dynamically-named keys per model) — a "rolling average" without needing to separately persist a sample count. Shown per catalog entry in `ModelManagerScreen.kt`, which now also refreshes on every entry into that screen (a `LaunchedEffect(Unit)`) so stats recorded by a chat session since the last visit actually show up — the ViewModel instance otherwise persists across navigation and wouldn't have picked them up.

## Non-Functional Requirements

| Code | Title | Status | PLAN.md Phase | Linear |
|---|---|---|---|---|
| NFR-001 | Zero telemetry / zero incidental network calls | Done | — | — |
| NFR-002 | Fully local storage, no server dependency | Done | — | — |
| NFR-003 | Total app size budget: under 1.5GB | Done | 6 | — |
| NFR-004 | Downloads never permanently fail on disconnect | Done | 2 | — |
| NFR-005 | Download progress: thousandths precision, byte-based sizes | Done | 2 | — |
| NFR-006 | Memory is view-only in-app — no in-app edit/delete path | Done | 3 | — |
| NFR-007 | Shared cross-platform core (single C++ implementation) | Partial | 0, 4 | — |
| NFR-008 | CI-verified builds only, no local phone build as primary path | Done | 5 | — |
| NFR-009 | Open-source, public repo, AGPLv3 | Done | — | — |
| NFR-010 | Bounded KV-cache memory + graceful context-window handling | Done | 1 | PRO-5 |
| NFR-011 | Thermal/battery-aware generation throttling, with a specific status indicator | Done | 2 | PRO-8 |
| NFR-012 | Resilience to Android background kill (BOOM), full transcript preserved | Done | 2 | PRO-8 |
| NFR-013 | Native on-device content-safety, tier 1 only for now | Done | new | PRO-7 |
| NFR-014 | Minimum device spec floor: ~3GB usable RAM, soft warning only | Done | 0 | — |
| NFR-015 | Encryption at rest for models and memory files | Idea | — | PRO-38 |
| NFR-016 | Visual-only UI, no dedicated screen-reader support | Done (by decision) | — | — |
| NFR-017 | English-only, no localization scaffolding | Done (by decision) | — | — |
| NFR-018 | Destructive actions require confirmation | Done | 2, 3 | PRO-39 |
| NFR-019 | Model downloads are sequential, one at a time | Done | 2 | PRO-40 |
| NFR-020 | No memory format versioning for now (decision recorded) | Done (by decision) | 3 | — |
| NFR-021 | All user-facing errors state the specific cause | Done | 1, 2 | PRO-41 |

### NFR-001 — Zero telemetry / zero incidental network calls
No analytics, no automatic crash reporting to a third party, no background phone-home. The only network traffic PocketChat ever makes is a user-initiated model download; a user-initiated debug-log export (FR-021) doesn't violate this since nothing transmits without the user directly acting.
**Why:** PLAN.md's core premise; directly what `docs/research-sovereign-on-device-llms.md`'s "data sovereignty maximalist" and `docs/research-consumer-demand-and-engineering.md`'s privacy-driver sections describe as the primary adoption catalyst for this category of app.

### NFR-002 — Fully local storage, no server dependency
Models and memory files live in app-private storage only.
**Why:** PLAN.md "Storage" decision; enables NFR-001 and full offline operation (austere-environment archetype).

### NFR-003 — Total app size budget: under 1.5GB
Enforced in CI via a "Verify APK size budget" step.
**Why:** user-specified hard ceiling when requesting the bundled-model feature (FR-006) — "keeping the overall app smaller than 1.5 GB."

### NFR-004 — Downloads never permanently fail on disconnect
Transient/connectivity failures retry indefinitely, using `ConnectivityManager`-aware waiting instead of a capped retry count or blind timed polling.
**Why:** user hit a real DNS-resolution failure that kept pausing downloads at ≤1% under an earlier capped-retry implementation; the cap didn't match real outage durations on spotty mobile networks.

### NFR-005 — Download progress: thousandths precision, byte-based sizes
Percentage shown to three decimal places; size/status shown in raw bytes, not just a rounded MB/GB label.
**Why:** explicit user request while troubleshooting downloads on a 21Mbps/133ms connection — needed finer-grained feedback than a whole-percent progress bar gives.

### NFR-006 — Memory is view-only in-app, with additive-only annotation as the one exception
No text field, no save action, no code path anywhere that edits or overwrites the model-generated content of `profile.txt` or `summaries/*.txt`. **Amended:** FR-023 introduces user-authored annotations attached to a summary — these are additive and stored separately from the model's own text, never merged into or replacing it, and always rendered as visually distinct from the model's summary in the viewer. The model's own generated content remains exactly as immutable as originally specified; only a new, clearly-separate field is now writable.
**Why:** explicit original user requirement — "aggressively enforce no edits can be made by the user directly through the app" — file-level edits (adb, a file manager) remain possible outside the app, deliberately, but not through it. The FR-023 amendment was deliberately scoped narrowly (additive-only, never in-place editing) specifically to preserve this rationale — the model's record stays a pure, auditable trace of what it actually inferred; the user's own notes are a clearly-labeled second voice next to it, not a correction applied to it. FR-019 (export) doesn't touch this either — it's a read-out path, not a write-back one.

### NFR-007 — Shared cross-platform core
One C++ implementation (`core/inference`, `core/memory`) consumed by both the Android JNI bridge and (eventually) an iOS XCFramework — no duplicated model logic per platform.
**Why:** PLAN.md's stated rationale for not using Flutter/React Native — a second UI runtime doesn't reduce RAM pressure (the model dominates it either way), but a second *model logic* implementation would be pure duplicated risk.
**Status note:** marked `Partial` — true on the Android side today; the iOS half (FR-016) hasn't been built, so this requirement isn't fully exercised yet.

### NFR-008 — CI-verified builds only
Every native/Kotlin change is validated via GitHub Actions before being considered done — this Termux environment has no Android SDK/NDK to build locally.
**Why:** PLAN.md non-goal ("no local on-device APK/IPA compilation as the primary build path") plus a practical constraint of the dev environment.

### NFR-009 — Open-source, public repo, AGPLv3
Public GitHub repo, AGPLv3 license.
**Why:** aligns with `docs/research-consumer-demand-and-engineering.md`'s finding that users evaluating local-AI apps show subscription fatigue and prefer FOSS/one-time-purchase models over recurring paywalls — PocketChat already satisfies this without further action needed.

### NFR-010 — Bounded KV-cache memory + graceful context-window handling
Asymmetric KV-cache quantization (`q8_0` keys / `q4_0` values) instead of full FP16, and sliding-window truncation instead of hard-failing generation once `n_ctx_used + batch.n_tokens > n_ctx`.
**Why:** `docs/research-sovereign-on-device-llms.md` and `docs/research-consumer-demand-and-engineering.md` both identify uncontrolled FP16 KV-cache growth as the primary cause of OS-level OOM kills (`jetsam`/`lmkd`) and mid-conversation freezes — confirmed as a real gap in `core/inference/pocketchat_inference.cpp` (no cache-type override currently set; context-full case just fails rather than degrading).
**Shipped:** `pc_context_create()` now requests `type_k = Q8_0` / `type_v = Q4_0` (flash attention auto-enables since it's required for a quantized V cache). `pc_generate_chat()` drops the oldest non-system messages and rebuilds the prompt from scratch when the conversation doesn't fit, caching a `drop_hint` on the context so a long session doesn't redo the same retry every turn; `run_generation()` treats hitting the ceiling mid-response as a graceful stop rather than an error. Verified with a real Qwen2.5-0.5B model: a 10-turn conversation against a 256-token context (far too small to hold it) completed with zero errors, and a normal 2048-token conversation regression-tested clean.

### NFR-011 — Thermal/battery-aware generation throttling, with a specific status indicator
Monitor battery/thermal state during generation and scale down target throughput when the device is under stress, rather than running uncontrolled. Unlike memory-update progress (FR-010), this is user-visible: the terminal status line names the *actual detected trigger* (e.g. distinguishing "battery low" from "thermal high") rather than a generic "running slow" message.
**Why:** `docs/research-consumer-demand-and-engineering.md` — sustained inference can burn ~1% battery per response and trigger throttling; no runtime supervision exists for this today. A generic indicator would tell the user something's wrong without telling them what, which isn't actionable — naming the trigger is what makes it worth showing at all.
**Shipped:** `DeviceStressMonitor` (Android-only, `com.pocketchat.app.power`) does a point-in-time check at the start of every `sendMessage()` call — `PowerManager.currentThermalStatus` (API 29+; thermal throttling is simply unavailable below that) checked first since it's the more urgent condition, falling back to the `ACTION_BATTERY_CHANGED` sticky-intent battery percentage (only when not charging, threshold 15%). When either trips, that turn's `n_predict` is capped to 256 tokens instead of running unbounded, and `ChatUiState.throttleStatus` drives a terminal status line naming the actual trigger (e.g. "throttled — battery low (12%), shortening this response" vs. "device hot (severe)"). **Narrower than the ticket's "scale down target throughput" language:** this caps response *length*, not per-token generation *speed* — nothing in `core/inference` currently exposes a way to pace token generation from the Kotlin side, and re-checking only once per turn (not continuously mid-generation) was chosen over a live thermal listener to keep this a self-contained, testable slice.

### NFR-012 — Resilience to Android background kill (BOOM), full transcript preserved
Conversation state — the full visible chat transcript, not just extracted memory — survives the OS silently killing a backgrounded process with the model loaded, and restores seamlessly on resume. A background kill should be invisible to the user, not just recoverable at the memory level.
**Why:** `docs/research-consumer-demand-and-engineering.md`'s FOOM/BOOM distinction — losing mid-conversation messages on reopen would read as a crash to the user even if the durable profile/summaries are intact; the bar is "the user doesn't notice," not "the durable facts survived."
**Shipped:** `ChatStorage` (Android-only, plain `org.json` serialization — no new dependency) persists `ChatUiState.messages` to a file under `getExternalFilesDir("chat")`, written after every message the transcript gains (the user's turn before generation starts, the assistant's turn after it completes). `ChatViewModel.init` restores it before `loadModel()` runs. Active model selection already survived process death via `ModelStorage`'s `SharedPreferences`-backed active-model name (disk-backed regardless of process state) — no change needed there. **Known gap:** if the process dies mid-generation, the user's message is preserved but the in-flight reply is not auto-resumed on restart — the user sees their own message with no reply rather than a silently-continued response, which is an honest recovery state but not literally invisible. `lastMemoryUpdateIndex` is reset to the full restored length on recovery (rather than an unknown true value) to avoid ever re-summarizing/duplicating memory entries, at the cost of possibly under-summarizing a span that died before its scheduled update — deliberately the conservative direction given `maybeUpdateMemory()` is already best-effort.

### NFR-013 — Native on-device content-safety, tier 1 only for now
FR-015's deterministic filter is the current ceiling of the safety stack, always-on and non-toggleable in every build (no store-vs-sideload split). Later tiers (guard classifier model, representation-level circuit breaking, weight-level unlearning) are explicitly not committed to — revisit only if a real incident or app-store rejection makes tier 1 insufficient, not on a schedule.
**Why:** `docs/research-consumer-demand-and-engineering.md` confirms Apple's App Store Review Guidelines require content moderation for generative-AI apps, and for a local-inference app (no server to point to) that moderation has to be native and has to actually hold — a user-toggleable filter would likely fail review since it's defeatable. `docs/research-sovereign-on-device-llms.md`'s survival-vs-weaponization overlap makes tier 1 a genuine dual-use mitigation, not just a compliance checkbox — see FR-015 for why this doesn't conflict with the uncensored-specialist archetype. Tier 2+ (e.g. Llama Guard 3-1B-INT4 at ~440MB) was explicitly weighed against NFR-003's size budget and declined for now.
**Shipped:** see FR-015 for implementation detail — this NFR is the "stop here for now" ceiling decision, not separate code.

### NFR-014 — Minimum device spec floor: ~3GB usable RAM, soft warning only
The lowest supported tier targets devices with roughly 3GB usable RAM (~6-7 year old hardware). This is a recommendation, not an enforced gate — the app doesn't refuse to run below the floor, consistent with how model-tier recommendations already work (FR-004).
**Why:** PLAN.md's original hardware-floor decision — the user's explicit baseline for "who this app needs to run on" — paired with a deliberate choice not to lock out lower-RAM devices entirely.

### NFR-015 — Encryption at rest for models and memory files
Memory files (and optionally models, though models carry no user data) protected via platform-level encryption (e.g. Android `EncryptedFile`/Keystore) beyond default app-sandboxed storage.
**Why:** the data-sovereignty archetype's threat model (`docs/research-sovereign-on-device-llms.md`) explicitly includes confidentiality risk beyond network exposure — a lost or unlocked device is a real scenario for someone storing privileged legal or unredacted medical content, not just remote packet sniffing.
**Status:** `Idea` — raised and agreed on in principle, not yet scoped into a concrete implementation ticket (needs a decision on Keystore key management, and whether this applies to Android only or blocks equally on iOS's Data Protection APIs).

### NFR-016 — Visual-only UI, no dedicated screen-reader support
The terminal aesthetic (custom monospace font, manual `Screen`-enum navigation, no navigation-compose) does not get dedicated TalkBack/VoiceOver semantics work.
**Why:** explicit tradeoff accepted alongside the original terminal-UI visual-identity decision (PLAN.md "UI / Visual design") — revisit only if a specific accessibility need arises, rather than speculatively building it in now.

### NFR-017 — English-only, no localization scaffolding
No i18n string externalization or multi-language memory/chat prompt design for v1.
**Why:** premature given base model quality on non-English input varies significantly at the 1-3B parameter tier PocketChat targets (NFR-014) — localization work would be built on an uncertain foundation.

### NFR-018 — Destructive actions require confirmation
Deleting a model (FR-003), deleting an individual memory item (FR-033), and toggling memory off entirely (FR-020) all require an explicit confirmation dialog before executing — a single uniform interaction pattern rather than a per-feature decision.
**Why:** standard safeguard against accidental taps on irreversible or hard-to-reverse actions; applying it uniformly avoids inconsistent behavior across similar-risk actions as more of them get added over time.
**Shipped:** the uniform pattern is `ConfirmableMenuItem` (`ui/Terminal.kt`) — a two-tap inline confirm (`[delete]` reveals `[confirm delete]`/`[cancel]`) rather than a modal `AlertDialog`. This was FR-033's one-off addition, generalized here into the app's single shared confirmation primitive and applied to all three named actions: model deletion (`ModelManagerScreen.kt`, previously had **no confirmation at all** — a real gap this NFR closed, not just a formalization), memory-off toggling (`SettingsScreen.kt`, only the off direction), and memory-item deletion (already used it since FR-033). **Deliberately not a modal dialog:** every other screen in this app uses flat terminal-text composables with zero Material dialogs anywhere — introducing an `AlertDialog` for confirmation alone would be the only modal surface in the app, clashing with PLAN.md's explicit terminal/recovery-mode visual identity (FR-002) for no functional gain over the two-tap pattern already proven out by FR-029/FR-033.

### NFR-019 — Model downloads are sequential, one at a time
Only one model download runs at a time; starting a new one queues behind (or requires completing/canceling) any in-progress download, rather than running several resumable downloads concurrently.
**Why:** keeps the resumable-download state machine (FR-005) — already the hard-won piece of this app, built directly from real spotty-mobile-network failures — simpler to reason about, at the cost of a marginal convenience for a use case (bulk-downloading many models at once) that isn't the common case.
**Shipped:** the queueing variant, not the "requires completing/canceling" alternative — a new `ModelRowStatus.Queued(position)` and an `ArrayDeque` in `ModelManagerViewModel`. Starting a second download while one is active enqueues it instead of blocking the tap outright; the active download's job wraps `runDownload()` in a `finally` block that clears the "active" slot and starts the next queued entry on every exit path (success, permanent failure, or the user explicitly pausing/discarding) — pausing or discarding the current download is treated as freeing the slot on purpose, which reads as the expected behavior for a deliberate user action. A `[cancel]` action lets the user pull a not-yet-started entry back out of the queue.

### NFR-020 — No memory format versioning for now
`profile.txt`/`summaries/*.txt` carry no explicit format-version marker. This is a deliberate decision, not an oversight — recorded here so it doesn't get silently re-litigated.
**Why:** the plain-text format hasn't changed since Phase 3 shipped, so a versioning/migration scheme would be solving a problem that doesn't exist yet. **Revisit when:** the on-disk format is actually about to change (e.g. FR-027's fact-history work, or FR-013's FTS5 index needing a specific schema) — design versioning at that point, informed by the real change, rather than speculatively now.

### NFR-021 — All user-facing errors state the specific cause
Every user-facing failure — download errors (FR-005), generation failures, model-load failures, memory-update failures — names the specific cause rather than a generic message, applied as a project-wide rule rather than decided per-feature.
**Why:** generalizes a principle we'd already committed to in two specific cases — FR-035 (naming what triggered a safety block) and NFR-011 (naming what triggered thermal/battery throttling) — into a rule that applies everywhere, since a generic "something went wrong" is exactly as unhelpful for a failed download or a failed generation as it would be for either of those.
**Needs an audit, not just new code:** current error surfaces (e.g. `pc_last_error()`/`pc_memory_last_error()` native strings shown directly in the chat status line) haven't been evaluated against this bar and shouldn't be assumed compliant just because they show *some* text — a raw internal error string (e.g. a bare llama.cpp return code) isn't the same as a specific, user-legible cause.
**Shipped:** `core/inference` now captures the *first* `GGML_LOG_LEVEL_ERROR` line llama.cpp's own logger emits during a model-load/context-create call (previously discarded to stderr) and folds it into `pc_last_error()` — verified live: a missing model file now surfaces "No such file or directory" and a corrupted GGUF surfaces "failed to read key-value pairs," both previously indistinguishable behind a generic wrapper message. `llama_decode` failures now include the actual return code and a specific hint for the documented "no free KV slot" case. Android download failures (FR-005) now map HTTP status codes to plain explanations instead of a bare code. Deliberately left unchanged: `maybeUpdateMemory()`'s silent failure-swallowing — that's the original intentional design, not a specificity gap this NFR covers.
