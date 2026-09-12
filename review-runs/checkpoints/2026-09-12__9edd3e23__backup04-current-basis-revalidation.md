# BUG-BACKUP-04 — selected-category capture current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Remote implementation HEAD was independently verified as `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71` at session bootstrap.
- Prior exact current-basis checkpoint: `2026-09-11__9c5191c3__backup-capture-current-basis-revalidation.md`
- Prior carry-forward checkpoint: `2026-09-11__9c5191c3__backup-capture-current-basis-carry-forward.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F4 / `BUG-BACKUP-04`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- No in-progress implementation diff was inspected or relied on.

## Verdict

**NOT_CLEAN — existing P1 `BUG-BACKUP-04` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

- Canonical blocker-count delta: `0`
- Canonical blocker count remains: **P0 2 / P1 1 / P2 34**
- CLEAN Review Basis remains: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

Exact compare `9c5191c3539734fa1c9f1b63501def89f47b216a..9edd3e2344caa9da0e576611eeb7d69e3cfd2d71` is five commits ahead and changes only metadata/source-authority related production/tests (`DownloadDao`, `DownloadMetadataPatch`, `ResultRepository`, `AutomaticKeywordRuleSyncWorker`, `DownloadMetadataPublication`, `DownloadWorker`, `UpdateMultipleDownloadsDataWorker`, and associated tests).

It does not modify `BackupSettingsUtil.kt`, `SettingsViewModel.kt` backup/custom-thumbnail capture, or establish a backup-wide Room snapshot boundary. The prior F4 root therefore remains eligible for carry-forward, and the exact current backup source was re-read below.

## Exact current production evidence

### 1. Selected-category capture failure still collapses to ordinary empty success

At exact `9edd3e23...`, `BackupSettingsUtil` still contains helper-local `runCatching { ... }` control flow followed by `return JsonArray()` for many selected categories, including:

- settings;
- History/downloads;
- cookies;
- command templates;
- shortcuts;
- search history;
- Observe sources;
- keyword groups and members;
- Youtuber groups, members, relations, and metadata.

Thus DAO/read/serialization/conversion exceptions inside those helpers are erased and represented exactly like a successfully captured genuinely empty category.

`SettingsViewModel.backup()` still wraps each selected category in an outer `runCatching` whose `onFailure` returns `Result.failure`. That boundary cannot observe a helper failure after the helper has already converted it to an ordinary empty `JsonArray()`.

The false-success chain therefore remains concrete:

`selected category`
→ `read / serialization exception`
→ `helper-local catch`
→ `ordinary empty JsonArray`
→ `outer per-category boundary sees success`
→ `backup artifact continues to write/move`
→ `Result.success(path)` may be returned.

This violates F4's authority invariant: an empty captured category must mean successful capture of genuinely empty state, not an erased failure.

### 2. Required custom-thumbnail payload can still disappear without failing the backup

For selected `downloads`, `SettingsViewModel.backup()` still calls `backupCustomThumbnails()` after the first History capture.

`backupCustomThumbnails()` performs a second `historyRepository.getAll()` and then `mapNotNull`s every referenced custom thumbnail. A nonblank custom thumbnail reference is silently omitted when:

- the file does not exist;
- the path is not a file;
- it is not readable; or
- `file.readBytes()` throws, because `runCatching { file.readBytes() }.getOrNull()` becomes `return@mapNotNull null`.

No typed omission/failure is returned to `backup()`. Therefore the History JSON can retain a custom-thumbnail reference while the backup-owned thumbnail payload is absent, and the overall backup can still return success.

### 3. Semantically related state is still captured at mixed times

`SettingsViewModel.backup()` still captures related state through separate reads with no common Room transaction/snapshot contract. Current examples include:

- keyword groups, keyword group members, and the related visibility preference;
- Youtuber groups, members, relations, metadata, and visibility preferences;
- the first History capture, a later second History read for custom thumbnails, and later automatic-keyword rules/keywords/video matches/assignments.

Concurrent relational mutation between these reads can therefore produce a syntactically successful backup artifact that never represented one coherent selected-category state.

### 4. Final artifact failure boundary does not repair erased capture authority

The final file creation/write/move occurs only after category capture. Even if those final operations throw or otherwise fail, truthful final-artifact reporting cannot reconstruct a category failure that an earlier helper already erased into a normal empty array. This root is specifically about capture authority and remains upstream of final artifact publication.

## Root reconciliation

Keep this as one existing P1 root `BUG-BACKUP-04` with count delta `0`.

The following remain subcases/evidence of F4 rather than new roots:

- swallowed selected-category exceptions;
- required custom-thumbnail omission on read failure;
- mixed-time related-state capture.

It remains distinct from restore-wide P0 `BUG-BACKUP-03` and from separate portable-schema/identity backup roots already reconciled elsewhere.

## Stable correction boundary

The correction boundary remains stable and implementation-ready when selected:

1. selected-category capture must return an explicit success/failure contract; helper failures must not become ordinary empty success;
2. genuine empty state must remain a successful empty capture;
3. required category-owned file payload read failure must fail the selected category/artifact or use an explicit safe partial-backup contract that cannot masquerade as complete success;
4. semantically related Room rows must be captured under an adequate consistency boundary so the artifact represents a coherent snapshot;
5. preserve truthful final file write/move failure propagation;
6. no backup format bump is required solely to distinguish F4 capture success from failure unless the chosen explicit partial-backup contract itself changes the persisted format;
7. regression evidence must cover true-empty capture, per-category read/serialization failure, required-thumbnail failure, concurrent related-state mutation, and final artifact write/move failure.

## Dependency consequence for BUG-BACKUP-03

`BUG-BACKUP-04` remains an explicit prerequisite/companion authority boundary for restore-wide Reset safety: a reset/restore plan cannot claim a fully validated pre-mutation source artifact when selected-category backup capture can report false success or mixed-time state.

This checkpoint does not close or decrement either root and does not silently reorder the already-recorded Task 002 canonical implementation target.

INDEPENDENT EXECUTION: NOT EXECUTED
