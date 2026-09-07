# PocketChat — Manual QA Audit

Tracks on-device verification of every **shipped** (`Done`) requirement in `REQUIREMENTS.md`. This exists because CI only proves the Kotlin/C++ compiles and produces an APK (NFR-008) — it says nothing about whether a feature actually behaves correctly on a real device, since this dev environment has no Android SDK/emulator. This document is the missing verification layer.

**Process:** we go a couple of items at a time. For each round: I give exact tap-by-tap steps and the expected result; you run them on-device and report back what actually happened (pass, fail, or "something else happened — here's what"). I update the Status column and, for any real failure, file a Linear bug referencing the FR/NFR code before we move to the next round.

**Scope:** only requirements marked `Done` in REQUIREMENTS.md get a QA round — `Idea`/`Deferred`/`Not started`/`Planned` items have nothing built yet to test. A few `Done (by decision)` items are process/documentation decisions, not on-device behavior — those get a one-line doc-inspection check instead of device steps (marked accordingly below).

**Build under test:** record the commit hash and CI run of whatever APK you actually install, each time you install a new one — a QA pass is only meaningful pinned to a specific build.

Current build under test: `017a73a` (CI run [34074403696](https://github.com/O-xix/Project-Pocketchat/actions/runs/34074403696))

---

## Status legend
`☐` not tested · `✅` pass · `❌` fail (Linear bug filed) · `⚠️` pass with a caveat (noted inline) · `⏭️` skipped (not shipped yet / doc-only, see note)

## Checklist

| Round | Code(s) | Area | Status |
|---|---|---|---|
| 1 | FR-036, FR-006 | First launch: onboarding, bundled model (no download required) | ☐ |
| 2 | FR-001, FR-002 | Basic chat generation + terminal UI | ☐ |
| 3 | FR-015, FR-035, NFR-013 | Safety filter blocks a known marker with a specific reason | ☐ |
| 4 | FR-028 | Stop generation mid-response; text restored into input, editable | ☐ |
| 5 | FR-038 | Long-press a message → copy / share | ☐ |
| 6 | FR-040 | `[search]` live scrollback search | ☐ |
| 7 | FR-029, FR-022 | `[clear]` → forced summary → post-session review banner | ☐ |
| 8 | FR-010 | Memory-update progress line (extracting facts / summarizing) | ☐ |
| 9 | FR-011, FR-026 | Memory viewer: browse profile/summaries; search box | ☐ |
| 10 | FR-023, FR-033 | Per-summary annotation; delete a summary / a profile fact | ☐ |
| 11 | FR-013 | Relevance (FTS5) retrieval picks a topical old memory over recent ones | ☐ |
| 12 | FR-014, FR-020 | Memory voice picker; memory on/off toggle (+ confirm on the off direction) | ☐ |
| 13 | FR-031, FR-032 | Persona/system-prompt override; Settings screen overall | ☐ |
| 14 | FR-039, FR-021 | About/license screen; debug log export | ☐ |
| 15 | FR-041 | Slash commands: `/help /clear /settings /models /memory /memory search /about /search` + fallback rule | ☐ |
| 16 | FR-003, FR-037 | Model manager: download / activate / delete; free-storage check | ☐ |
| 17 | FR-004, FR-043 | RAM-tier recommendation; per-model response-time stats | ☐ |
| 18 | FR-005, NFR-004, NFR-005, NFR-019 | Resumable download: pause/resume, disconnect handling, thousandths precision, queueing | ☐ |
| 19 | FR-012 | BYOM: custom URL + local file (SAF) import, GGUF validation | ☐ |
| 20 | FR-034 | Model switch preserves + replays visible transcript as real context | ☐ |
| 21 | FR-042 | Background generation keeps running + completion notification | ☐ |
| 22 | NFR-012 | Kill the backgrounded process (BOOM) → transcript restored on reopen | ☐ |
| 23 | NFR-010 | Long conversation exceeds context window without hard-failing | ☐ |
| 24 | NFR-011 | Thermal/battery throttling status line (best-effort — hard to force) | ☐ |
| 25 | NFR-018 | Confirmation pattern consistency across all destructive actions | ☐ |
| 26 | NFR-021 | Error messages name a specific cause (cross-cutting spot-check) | ☐ |
| 27 | NFR-001, NFR-002 | No network traffic except user-initiated downloads (needs a network monitor) | ☐ |
| 28 | NFR-003 | APK size under 1.5GB | ☐ |
| 29 | NFR-009 | Public repo + AGPLv3 license present | ☐ |
| 30 | NFR-007, NFR-016, NFR-017, NFR-020, NFR-008 | Doc-only decisions — sanity-check, no device steps | ⏭️ |

**Not in scope for this audit** (nothing shipped yet to test): FR-016, FR-017, FR-018, FR-019, FR-024, FR-025, FR-027, FR-030, NFR-015.

---

## Round 1 — First launch: onboarding (FR-036) + bundled model (FR-006)

### One-time setup (only needed the first time you install this specific build)
1. If PocketChat is already installed from an earlier build, **uninstall it completely first** — onboarding only shows on a truly fresh install (`SettingsStorage.isOnboardingCompleted` defaults to false only when there's no existing app data at all). Long-press the app icon → App info → Uninstall, or Settings → Apps → PocketChat → Uninstall.
2. Get the APK onto the device (transfer `app-debug.apk` via USB/cloud/etc., or download it directly from the Actions run page in the device's browser).
3. Tap the APK file to install. If blocked, Android will prompt to allow installs from that source (browser/file manager) — allow it, then retry the tap.
4. Open PocketChat.

### FR-036 — Onboarding
1. **Expect:** the very first screen is *not* the chat screen — it's headed `pocketchat> welcome`, with body text mentioning everything runs on-device / no accounts / no servers / no telemetry.
2. Below that, confirm two sections are present and interactive:
   - **memory** — showing "on — extracting facts and summarizing sessions" by default, with a `[turn off]` action.
   - **memory voice** — showing `[neutral] (active)` and `[reflective]`, both tappable.
3. Tap `[reflective]` — confirm it becomes the active one (label flips to `[reflective] (active)`, `[neutral]` loses its `(active)` suffix). Tap `[neutral]` again to set it back.
4. Confirm a line near the bottom says something like "all of this — and more — can be changed later in [settings]".
5. Tap `[continue]`.
6. **Expect:** you land on the normal chat screen.
7. **Regression check:** fully close the app (swipe it away from recents) and reopen it. **Expect:** you go straight to chat — onboarding does *not* show again.

### FR-006 — Bundled model, no first-run download required
1. Right after onboarding (or on that first chat screen), **without touching `[models]`**, check whether the input area is enabled (not greyed out) and whatever status line is showing does **not** say something like "no model available — open [models] and download one".
2. Type a short message (e.g. "hello") and send it.
3. **Expect:** the model begins responding (streaming text appears) without ever having downloaded anything — confirms a model was already bundled/extracted at install time, not fetched over network.
4. Optional: put the device in Airplane Mode first, then repeat step 2-3 — if it still generates a response with zero connectivity, that's an even stronger confirmation FR-006 + FR-001 have no hidden network dependency for basic chat.

### Report back
For each of the two items, tell me: pass / fail, and if anything looked different from "Expect" (exact wording you saw, what didn't appear, anything that crashed or hung). Once you report, I'll mark the checklist and we'll move to Round 2 (basic chat generation + terminal UI look-and-feel).
