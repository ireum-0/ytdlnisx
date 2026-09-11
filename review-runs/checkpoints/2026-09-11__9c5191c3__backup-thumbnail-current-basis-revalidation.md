# BUG-BACKUP-02 — restored-thumbnail staging current-basis revalidation

Date: 2026-09-11

## Exact basis
- CLEAN basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `d1d0e38c8fecf353c27407b059009d7c8c18cb49` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F5
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna `BUG-KEYWORD-01` review-fix #2 is in progress; no implementation commit/diff newer than `5da8bc3354f6cbafd23d08dbc602530a983be6af` was inspected.

## Verdict
**NOT_CLEAN / existing P2 `BUG-BACKUP-02` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change relation
The cumulative `aa1616a2... -> 9c5191c3...` F3 changes do not modify the custom-thumbnail restore implementation in `SettingsViewModel`. The exact current path was re-read before carrying the finding forward.

## Exact current source
`SettingsViewModel.restoreData()` still calls `restoreCustomThumbnails(data.customThumbnails)` at the start of the broad restore body, before imported History rows are inserted with fresh destination IDs and before `importedHistoryIdMap` exists.

`restoreCustomThumbnails()` still writes directly into the live custom-thumbnail directory using a filename derived from the backup-local History ID:

`restored_${item.historyId}.$extension`

It writes with `outFile.writeBytes(decoded)` through `runCatching` and has no collision-resistant import token or exclusive/no-overwrite publication rule.

Therefore the same collision sequence remains possible:

1. Backup A old History ID N restores thumbnail X to `restored_N.ext` and a destination History row points to that live path.
2. A repeated Merge or Backup B also contains old ID N with different content Y.
3. The later restore writes Y to the same live path before its own destination History identity is allocated.
4. The earlier successfully imported History row silently changes thumbnail content because it still points at that path.

The broad restore is wrapped in `runCatching`, but early thumbnail files are not managed as an isolated staging set with compensation. A later restore failure can therefore leave new live files behind or leave an earlier live import overwritten.

## Root reconciliation
This remains one canonical P2 `BUG-BACKUP-02` F5 root.

It remains distinct from:
- P1 `BUG-BACKUP-04` capture false-success;
- P0 `BUG-BACKUP-03` restore-wide commit/recovery atomicity;
- P2 `BUG-BACKUP-06` portable numeric-reference authority.

## Correction boundary carried forward
- decode/write into a collision-resistant import staging namespace;
- never use backup-local History ID as sole live filesystem ownership identity;
- bind staged content only after destination History identity is allocated through explicit old→new mapping;
- publish/rebind to a fresh destination-owned path without mutating prior imports;
- clean staged files on decode/write/insertion/later restore failure;
- repeated Merge and different backups reusing the same old ID/extension must remain isolated;
- preserve extension/content variants without path aliasing;
- coordinate with restore-wide `BUG-BACKUP-03` without merging the roots.

INDEPENDENT EXECUTION: NOT EXECUTED
