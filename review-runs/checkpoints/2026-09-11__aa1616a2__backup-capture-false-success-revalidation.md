# BUG-BACKUP-04 — exact-basis selected-category capture revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Newer implementation diff used as exploratory evidence: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F4
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior exact review: `review-runs/checkpoints/2026-09-11__6763fb1b__backup-capture-false-success-revalidation.md`

`6763fb1b... -> aa1616a2...` changed only the History duplicate-identity domain and did not touch backup capture production files. The root was nevertheless re-read against exact `aa1616a2...` source.

## Verdict

**NOT_CLEAN — existing P1 `BUG-BACKUP-04` is reconfirmed OPEN at `aa1616a2...`.**

- Count delta: **0**
- Canonical count remains **P0 3 / P1 3 / P2 25**
- CLEAN basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`

## Exact-source evidence

### Helper-local capture failures still become ordinary empty arrays

`BackupSettingsUtil` still wraps many selected-category capture helpers in local `runCatching { ... }`, discards the failure, and returns a fresh `JsonArray()`.

This remains true for categories including settings, History/downloads, cookies, command templates, shortcuts, search history, Observe sources, keyword groups/members, Youtuber groups/members/relations, and Youtuber metadata.

Therefore DAO read, preference conversion, Gson/JSON conversion, or item serialization failure can remain indistinguishable from a genuinely empty selected category.

`SettingsViewModel.backup()` has an outer per-category `runCatching` and would correctly return `Result.failure` if the helper propagated its exception, but helper-local erasure prevents that boundary from seeing these failures.

Concrete chain:

`selected category`
→ `capture/read/serialization exception`
→ `BackupSettingsUtil` local `runCatching` absorbs it
→ ordinary empty `JsonArray()`
→ backup JSON accepts the empty category
→ artifact creation/move continues
→ caller can receive successful backup path.

This violates F4's invariant: an empty captured category must mean successful capture of genuinely empty state.

### Required custom-thumbnail reads still silently disappear

For selected `downloads`, `SettingsViewModel.backup()` calls `backupCustomThumbnails()` after History capture.

That helper still walks History custom-thumbnail references using `mapNotNull` and silently omits a referenced thumbnail when the file is absent/not-readable or when `file.readBytes()` fails through `runCatching { ... }.getOrNull()`.

Thus the History payload may preserve a custom-thumbnail reference while the corresponding backup-owned thumbnail payload is absent, yet backup creation can still report success.

### Related Room data remains mixed-time capture

One logical selected category still performs multiple independent reads rather than one consistent snapshot boundary. Examples at exact aa include:

- keyword groups and keyword members captured by separate helper/DAO calls, then related visibility preferences separately;
- Youtuber groups, members, relations, metadata, and visibility preferences captured separately;
- downloads/History captured first, History read again for custom thumbnails, and automatic-keyword rules/keywords/matches/assignments queried separately afterward.

Concurrent relational mutation can therefore produce a superficially successful artifact whose related rows do not represent one coherent capture state.

## Root reconciliation

This remains the existing P1 `BUG-BACKUP-04` capture-boundary root. No new root is added for thumbnail omission or mixed-time related reads; both are established F4 subcases.

It remains distinct from downstream payload/restore defects `BUG-BACKUP-05`, `BUG-BACKUP-07`, and restore-wide `BUG-BACKUP-03`.

## Required correction boundary

- propagate/encode selected-category capture failure instead of collapsing it into empty success;
- preserve true-empty categories as successful empty capture;
- required category-owned file/read failures must block successful artifact creation or be represented by an explicit safe contract;
- capture semantically related Room state under a consistent boundary sufficient to prevent mixed-time false success;
- do not introduce a backup format bump solely for F4;
- retain truthful final artifact write/move failure reporting.

Required regression evidence remains per-category fault injection, true-empty category, serialization/read failure, required-thumbnail failure, concurrent relational mutation, and final artifact failure behavior.

## Verification note

No independent Gradle/JVM/instrumentation test was executed in this review.

INDEPENDENT EXECUTION: NOT EXECUTED
