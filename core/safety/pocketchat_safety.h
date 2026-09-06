// Tier-1 offline safety filter (PLAN.md dual-use mitigation; see
// docs/research-sovereign-on-device-llms.md's defense-in-depth stack and
// REQUIREMENTS.md FR-015/NFR-013). A deterministic Aho-Corasick match
// against a small, curated list of known-hazardous markers, run before a
// prompt ever reaches the model. Cheap (sub-millisecond, no heap allocation
// beyond the returned category string) and deliberately narrow: it catches
// literal/near-literal mentions of named hazardous substances/devices, not
// jailbreaks, encoded prompts, or general "unsafe" content — later tiers
// (a guard classifier model, representation-level circuit breaking) are
// explicitly out of scope here. Standalone: no dependency on
// core/inference, safe to call before any model is loaded.
#ifndef POCKETCHAT_SAFETY_H
#define POCKETCHAT_SAFETY_H

#ifdef __cplusplus
extern "C" {
#endif

// Returns 1 if `text` matches a known-hazardous pattern, 0 if clean.
// Case-insensitive substring match. `text` must be non-null.
int pc_safety_check(const char * text);

// Category of the match from the most recent pc_safety_check() call that
// returned 1 (e.g. "explosives", "chemical-weapons"); empty string if that
// call found nothing, or none has run yet on this thread.
const char * pc_safety_last_category(void);

#ifdef __cplusplus
}
#endif

#endif // POCKETCHAT_SAFETY_H
