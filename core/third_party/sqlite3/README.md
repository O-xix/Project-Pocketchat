# Vendored SQLite amalgamation

`sqlite3.c` + `sqlite3.h` are the official single-file amalgamation build,
placed in the public domain by the SQLite authors. Used for FR-013's FTS5
relevance search (`core/memory/`).

- **Version:** 3.53.4 (release `sqlite-amalgamation-3530400.zip`)
- **Source:** https://sqlite.org/2026/sqlite-amalgamation-3530400.zip
- **SHA3-256:** `628a44cfe82c66aed1ccbbe85a562d2e33ebe64b3288981ed76285612227934e`
  (matches the hash published on sqlite.org's own download page at fetch time)

## Why vendored instead of linked against the system library

The original plan was to link the OS-provided SQLite (Android and iOS both
ship one) rather than add a new dependency. That works for the desktop/Termux
build (`find_package(SQLite3)` finds real dev headers there), but the Android
NDK exposes `libsqlite3.so` as a linkable stub *without* bundling `sqlite3.h`
— discovered when this broke CI's CMake configure step outright
(`Could NOT find SQLite3 (missing: SQLite3_INCLUDE_DIR SQLite3_LIBRARY)`),
not assumed in advance.

Vendoring the amalgamation sidesteps needing NDK header support entirely and
guarantees identical FTS5 availability on every Android API level this app
targets (NFR-014's floor is API 26), rather than only on whichever versions
happen to bundle FTS5-enabled system SQLite with public headers.

`shell.c` and `sqlite3ext.h` from the same release archive aren't needed here
(CLI tool and loadable-extension support, respectively) and aren't vendored.
