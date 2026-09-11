# BUG-BACKUP-04 — selected-category capture current-basis revalidation

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `aefd324334105db0c008c2996e155d941b68ad4d` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F4 / `BUG-BACKUP-04`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna `BUG-KEYWORD-01` review-fix #2 is in progress. No implementation commit/diff newer than `5da8bc3354f6cbafd23d08dbc602530a983be6af` was inspected or relied upon.

## Verdict

**NOT_CLEAN / existing P1 `BUG-BACKUP-04` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change overlap

The cumulative `aa1616a2... -> 9c5191c3...` F3 work changed source-extraction / Observe / keyword files, not the backup-capture implementation. No intervening commit in that cumulative range changes `BackupSettingsUtil` or the `SettingsViewModel.backup()` / custom-thumbnail capture path.

The old finding is therefore eligible for carry-forward, but the exact current backup files were still re-read before recording that relation.

## Exact current source

### 1. Selected-category capture failures still collapse to empty success

At `9c5191c3...`, `BackupSettingsUtil` still uses helper-local `runCatching { ... }` blocks that return a fresh `JsonArray()` after any swallowed exception for many selected categories, including settings, History/downloads, cookies, command templates, shortcuts, search history, Observe sources, keyword groups/members, and Youtuber group/member/relation/meta data.

The caller `SettingsViewModel.backup()` has an outer per-category `runCatching` that would correctly return `Result.failure` if the helper propagated an exception. Because the helper converts the failure into an ordinary empty array first, that outer failure boundary cannot distinguish:

`successful capture of a genuinely empty category`

from

`DAO/read/serialization/conversion failure`.

The concrete false-success path remains:

`selected category`
→ capture/read/serialization exception
→ helper-local catch erases failure
→ ordinary empty `JsonArray()`
→ backup JSON accepts it
→ artifact write/move continues
→ caller may receive successful backup path.

This still violates F4's invariant that an empty captured category must mean successful capture of genuinely empty state.

### 2. Required custom-thumbnail payload still disappears on read failure

For selected `downloads`, `SettingsViewModel.backup()` still calls `backupCustomThumbnails()` after History capture.

That helper re-reads History rows and uses `mapNotNull` for custom-thumbnail payloads. A referenced thumbnail is silently omitted when:

- the path is missing;
- the file does not exist / is not a readable file; or
- `file.readBytes()` fails, because `runCatching { file.readBytes() }.getOrNull()` becomes another `mapNotNull` omission.

Thus a History payload may retain a custom-thumbnail reference while the corresponding backup-owned file payload is absent, and the overall backup can still report success.

### 3. Mixed-time related-state capture remains

The exact current caller still captures semantically related state via independent reads rather than one consistency boundary. Examples include:

- keyword groups, members, and related visibility preference;
- Youtuber groups, members, relations, metadata, and visibility preferences;
- History/download rows, a later History pass for thumbnails, and automatic-keyword rule/keyword/match/assignment rows queried separately.

Concurrent relational mutation can therefore still produce a superficially successful artifact that does not represent one coherent selected-category snapshot.

## Root reconciliation

This remains one canonical P1 root `BUG-BACKUP-04`.

- swallowed selected-category failure;
- required-thumbnail omission; and
- mixed-time related-state capture

remain F4 subcases/evidence, not separate new roots.

The root remains distinct from `BUG-BACKUP-05`, `BUG-BACKUP-07`, and restore-wide `BUG-BACKUP-03`.

## Correction boundary carried forward

The prior correction boundary remains valid:

1. selected-category capture failure must propagate or be represented by an explicit typed failure contract rather than ordinary empty success;
2. true-empty category remains a successful empty capture;
3. required category-owned file/read failure must block successful artifact creation or be represented by an explicit safe contract;
4. semantically related Room state must be captured under a consistency boundary sufficient to avoid mixed-time false-success artifacts;
5. no backup format bump is required solely for F4;
6. final artifact write/move failure reporting must remain truthful;
7. regression evidence should include per-category fault injection, true-empty capture, serialization/read failure, required-thumbnail failure, concurrent relational mutation, and final artifact failure.

## Carry-forward conclusion

No `aa1616a2... -> 9c5191c3...` intervening change repairs or materially alters this root. Existing P1 `BUG-BACKUP-04` therefore carries forward OPEN to the current independently CLEAN basis.

INDEPENDENT EXECUTION: NOT EXECUTED
