# BUG-BACKUP-02 — exact-basis restored-thumbnail staging revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Newer implementation diff used as exploratory evidence: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F5
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior exact review: `review-runs/checkpoints/2026-09-11__6763fb1b__backup-thumbnail-staging-revalidation.md`

`6763fb1b... -> aa1616a2...` changed only History duplicate-identity code/tests and did not touch restore custom-thumbnail production code. The root was nevertheless re-read on exact `aa1616a2...` source.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-02` is reconfirmed OPEN at `aa1616a2...`.**

- Count delta: **0**
- Canonical count remains **P0 3 / P1 3 / P2 25**
- CLEAN basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`

## Exact-source evidence

### Restored thumbnail files are materialized before destination History identity exists

`SettingsViewModel.restoreData()` still starts its restore body by calling:

`restoreCustomThumbnails(data.customThumbnails)`

before imported History rows are inserted with `id = 0L` and before the resulting destination History IDs are recorded in `importedHistoryIdMap`.

Thus the restore chooses and writes thumbnail paths before the destination row identity that should own the imported file exists.

### Backup-local History ID is still the live filename authority

`restoreCustomThumbnails()` writes into the live app custom-thumbnail directory and constructs the output filename as:

`restored_${item.historyId}.$extension`

where `item.historyId` is the backup-local History ID.

It then calls `outFile.writeBytes(decoded)` with no no-overwrite/exclusive-create guard and returns that live path in a map keyed by the same backup-local ID.

The later History insertion uses that already-written path; there is no collision-resistant import token and no rename/rebind based on the newly allocated destination History ID.

### Repeated Merge / another backup can overwrite an earlier successful import

Concrete sequence remains:

1. Backup A contains old History ID 7 and custom thumbnail X.jpg.
2. Merge A writes `custom_thumbs/restored_7.jpg` and later inserts destination History H101 pointing at that path.
3. A repeated Merge, or Backup B, also contains old History ID 7 with Y.jpg.
4. Restore writes to the same `restored_7.jpg` before allocating the new destination History row.
5. H101 still points at that path, so its previously successful thumbnail is silently changed to Y.

Destination History ID remapping therefore does not isolate filesystem ownership.

### Failed restore has no staged-file compensation

The broad restore is wrapped in `runCatching`, but the early thumbnail materialization is not tracked as an isolated staging set with failure cleanup/rollback. If a later History insertion, preference write, relation import, Download restore, or other restore step fails, already-written custom-thumbnail files remain.

A failed later Merge can therefore both return failure and leave an earlier imported live thumbnail overwritten. Restore-wide process-death/atomicity remains owned by P0 `BUG-BACKUP-03`; the collision/early-live-file mechanism is the distinct F5 root here.

## Root reconciliation

This remains existing P2 `BUG-BACKUP-02`, counted once.

It remains distinct from:

- P1 `BUG-BACKUP-04` capture false-success;
- P0 `BUG-BACKUP-03` restore-wide commit/recovery atomicity;
- F6 portable numeric-reference mapping.

## Required correction boundary

- decode/write into a collision-resistant import staging namespace with no-overwrite semantics;
- never use backup-local History ID as sole live filesystem ownership identity;
- bind a staged thumbnail only when the destination History row is allocated/inserted through explicit old→new mapping;
- publish/rebind to a fresh destination-owned path without mutating an existing live import;
- clean staged files on decode/write/insertion/later restore failure;
- repeated Merge and different backups reusing the same old ID/extension must remain isolated;
- preserve extension/content variants without path aliasing;
- coordinate with later restore-wide BUG-BACKUP-03 without merging/double-counting the roots.

Required regressions remain repeated Merge, same old ID across different backups, same-id/same-extension different content, extension variants, write failure, History insertion failure, later restore failure after staging, and Reset.

## Verification note

No independent Gradle/JVM/instrumentation test was executed in this review.

INDEPENDENT EXECUTION: NOT EXECUTED
