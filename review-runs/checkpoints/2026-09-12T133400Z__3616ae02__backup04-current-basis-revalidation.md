# BUG-BACKUP-04 — exact CLEAN-basis revalidation

Date: 2026-09-12

## Exact review state

- Fixed contiguous independently CLEAN basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Root: F4 / existing P1 `BUG-BACKUP-04`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Prior exact-basis F4 checkpoint: `5ba6ff2deadbfcc6ebc1e2331e536742bc777bf5` at `93d01d2a...`.
- Exact remote `8c5db3c7...` duplicate-admission execution verification is running separately; this F4 decision uses only `3616ae02...`.

## Verdict

**OPEN / NOT_CLEAN — existing P1 `BUG-BACKUP-04` remains confirmed at exact CLEAN basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30** while duplicate-admission closure awaits exact-SHA execution.
- CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- F4 remains a hard prerequisite for F8/F9 and F11 / P0 `BUG-BACKUP-03`.

## Intervening-range verification

The exact range `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c..3616ae02e56995e795cc52f3074d8c3d1cd2e330` is the CACHE-01/CACHE-02 remediation range. It does not modify `BackupSettingsUtil.kt` or `SettingsViewModel.kt` backup capture composition. Exact final `3616ae02...` production source was re-read.

## Exact current production evidence

### 1. Helper-local failures still collapse into ordinary empty arrays

`BackupSettingsUtil` still uses local `runCatching { ... }` and falls through to `return JsonArray()` for multiple capture helpers, including settings, History, cookies, command templates, shortcuts, search history, Observe sources, keyword groups/members, and Youtuber groups/members/relations/metadata.

A DAO/read/serialization/conversion failure can therefore become the same ordinary empty `JsonArray` as a genuinely empty successful category.

`SettingsViewModel.backup()` wraps each selected category in an outer `runCatching` and only returns `Result.failure` when that outer boundary sees an exception. It cannot observe an exception that a helper has already erased into a normal empty array.

Concrete chain remains:

`selected category`
→ `capture/read/serialization failure`
→ `helper-local catch`
→ `ordinary empty JsonArray`
→ `outer selected-category boundary sees success`
→ `backup artifact creation continues`
→ overall success may be returned.

This violates F4's governing invariant: an empty captured category must mean a successful capture of genuinely empty state.

### 2. Required custom-thumbnail payload failure still becomes omission

For selected `downloads`, `SettingsViewModel.backup()` captures History and then separately calls `backupCustomThumbnails()`.

That helper rereads History and uses `mapNotNull`; for a nonblank custom-thumbnail path it silently returns null when the file is missing/not a file/unreadable, and `runCatching { file.readBytes() }.getOrNull()` also turns a read failure into omission.

The backup can therefore retain History state that references a custom thumbnail while omitting its category-owned thumbnail payload and still continue toward successful artifact creation.

### 3. Related state is still captured at mixed times

Related state is read through separate DAO/preferences calls rather than one adequate consistency boundary, including:

- keyword groups + members + visibility preference;
- Youtuber groups + members + relations + metadata + visibility preferences;
- History + a later History reread for custom thumbnails + later automatic-keyword rules/keywords/matches/assignments.

Concurrent mutation can therefore produce a syntactically successful backup that does not correspond to one coherent selected-category snapshot.

## Root/count reconciliation

These remain subcases/evidence of the existing P1 `BUG-BACKUP-04`, not new blockers:

- swallowed selected-category exceptions;
- required-thumbnail omission;
- mixed-time relational capture.

F4 remains distinct from restore-wide F11 / P0 `BUG-BACKUP-03` and from F5/F6/F7/F8/F9 restore/schema/identity roots.

## Stable correction boundary retained

The implementation boundary remains:

1. selected-category capture exposes explicit success/failure; helper failure cannot masquerade as ordinary empty success;
2. genuinely empty state remains a valid empty capture;
3. category-owned required file/read failure fails the selected category/artifact unless an explicit safe partial-backup contract is designed;
4. semantically related Room state is captured under an adequate consistency boundary;
5. final artifact serialization/write/move failures remain truthfully propagated;
6. do **not** introduce backup format 4 merely to close F4; a format change is warranted only if the chosen persisted contract actually requires it;
7. focused evidence covers true-empty capture, per-category fault injection, required-thumbnail failure, concurrent relational mutation, and final artifact publication failure.

## Dependency consequence

F4 remains OPEN and continues to block F8/F9 and F11. The F11 Extra High plan remains premature while F4/F5/F6/F7/F8/F9/F10 prerequisites are open.

INDEPENDENT EXECUTION: NOT EXECUTED
