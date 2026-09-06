# CLAUDE.md

Guidance for Claude Code (and any other agent) working in this repository.

## Commit & PR message format

Every commit message and PR description in this repo follows this exact structure, each section present with its heading even when there's nothing to say (write "None" / "None known" rather than omitting a section):

1. **Layman's summary** — one to three sentences, plain language, no jargon. What changed and why it matters, understandable to someone with no engineering background.
2. **Technical summary** — what actually changed: which files/functions, the mechanism, the key implementation decisions. Specific enough that another engineer could review the diff with this as a map.
3. **Why this had to change** — the root cause or motivating problem. Reference the specific failure, requirement, or user report that made this necessary (a `REQUIREMENTS.md` FR/NFR code, a Linear ticket, a concrete bug).
4. **New downsides / limitations** — tradeoffs, edge cases, or known gaps this change knowingly introduces or leaves behind. Be honest here even when it's not flattering to the change.
5. **Recommendations for furthering this** — natural next steps or follow-up work this change sets up or motivates, if any.
6. **Testing guide** — specific, reproducible steps to verify the change: exact commands, expected output/behavior. Not "tests pass" — the actual steps a reviewer could run themselves.

Keep each section tight — this is a structured record for future readers (including future agents), not an essay. See `git log` for real examples of this format in practice, e.g. the NFR-010 KV-cache/context-truncation commit.
