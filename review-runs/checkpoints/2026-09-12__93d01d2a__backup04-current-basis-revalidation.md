# BUG-BACKUP-04 — selected-category capture current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Implementation branch: `checkpoint/pre-baseline-review`.
- Verification-only CACHE-02 wave remains active at exact implementation SHA `3616ae02e56995e795cc52f3074d8c3d1cd2e330`; no post-basis implementation diff was used for this exploratory review.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F4 / `BUG-BACKUP-04`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- F4 is a hard prerequisite/authority boundary for F11 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P1 `BUG-BACKUP-04` remains OPEN at exact canonical CLEAN basis `93d01d2a...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 31**.
- CLEAN Review Basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

The exact range `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71 -> 93d01d2afbce2cfa62dc17fad4478416b3d7cf6c` is two commits ahead and changes only `AutomaticKeywordRuleEngine.kt` and `AutomaticKeywordRulePersistenceTest.kt`. It does not modify `BackupSettingsUtil.kt`, `SettingsViewModel.kt` backup/custom-thumbnail capture, or establish a backup-wide consistency boundary. Exact `93d01d2a...` source was nevertheless re-read directly.

## Exact current production evidence

### 1. Selected-category capture failure still collapses to ordinary empty success

`BackupSettingsUtil` still wraps many selected-category capture helpers in local `runCatching { ... }` and falls through to `return JsonArray()` after failure. This remains true for settings, History/downloads, cookies, templates, shortcuts, search history, Observe sources, keyword groups/members, and Youtuber groups/members/relations/metadata.

Therefore a DAO/read/serialization/conversion failure can still become exactly the same value as a genuinely empty successful capture.

`SettingsViewModel.backup()` still wraps each selected category in an outer `runCatching` and returns `Result.failure` only when that outer boundary observes an exception. It cannot observe an exception already erased by the helper into a normal empty `JsonArray`.

Concrete chain remains:

`selected category`
→ `capture/read/serialization failure`
→ `helper-local catch`
→ `ordinary empty JsonArray`
→ `outer category boundary observes success`
→ `artifact write/move continues`
→ `Result.success(path)` may be returned.

This violates F4's invariant that an empty captured category means a successful capture of genuinely empty state.

### 2. Required custom-thumbnail payload can still disappear without failing backup

For selected `downloads`, `SettingsViewModel.backup()` still captures History first and later calls `backupCustomThumbnails()`.

That second capture silently omits a nonblank custom-thumbnail reference when the file is missing, not a file, unreadable, or `readBytes()` fails. The omission is represented with `mapNotNull`/`getOrNull`, not a typed capture failure.

The backup can therefore retain a History custom-thumbnail reference while omitting the backup-owned thumbnail payload and still report overall success.

### 3. Related state is still captured at mixed times

Related Room/preferences state is still read through multiple independent calls rather than one adequate snapshot/consistency boundary. Examples remain:

- keyword groups + members + visibility preference;
- Youtuber groups + members + relations + metadata + visibility preferences;
- History + a later History reread for custom thumbnails + later automatic-keyword rules/keywords/matches/assignments.

Concurrent mutation can therefore produce a syntactically successful artifact that does not correspond to one coherent selected-category state.

## Root reconciliation

This remains one existing P1 root `BUG-BACKUP-04`; count delta is zero. Swallowed selected-category exceptions, required-thumbnail omission, and mixed-time relational capture remain subcases/evidence of F4, not new blocker roots.

It remains distinct from restore-wide P0 `BUG-BACKUP-03` and from F5/F6/F7/F8/F9 portable-schema/identity roots.

## Stable correction boundary

The correction boundary remains implementation-ready when selected:

1. selected-category capture must expose explicit success/failure; helper failures cannot become ordinary empty success;
2. genuinely empty state remains a successful empty capture;
3. required category-owned file payload failure must fail the selected category/artifact or use an explicit safe partial-backup contract that cannot masquerade as complete success;
4. semantically related Room rows must be captured under an adequate consistency boundary;
5. final artifact write/move failures remain truthfully propagated;
6. no backup format bump is required solely for typed capture failure unless the chosen persisted partial-backup contract itself changes the wire format;
7. focused evidence must include true-empty capture, per-category failure, required-thumbnail failure, concurrent relational mutation, and final artifact publication failure.

## F11 consequence

F4 remains OPEN. Together with fresh/current F5-F9 OPEN dispositions and the already exact-`93d01d2a...` F10 OPEN checkpoint, F11 `BUG-BACKUP-03` remains unequivocally `BLOCKED_BY_HARD_PREREQUISITES`. Do not issue its Extra High planning/implementation phase yet.

INDEPENDENT EXECUTION: NOT EXECUTED