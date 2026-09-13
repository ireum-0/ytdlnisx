# F8 / BUG-BACKUP-05 independent closure

## Scope

- Exact completed implementation HEAD: `a95868357ddd70ac990a79026daf8a571db1917b`
- Implementation range reviewed: `c67ecb66e3df954a225354632945be101a9d740d..a95868357ddd70ac990a79026daf8a571db1917b`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F8
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**CLEAN / CLOSED.**

Canonical blocker count changes from **P0 2 / P1 0 / P2 26** to **P0 2 / P1 0 / P2 25**.

The contiguous CLEAN basis does **not** advance beyond `90afaec157607669ea32fa41877e7f0efcdcca86`, because F10 remains open earlier in the cumulative implementation state.

## Exact-source closure

The final source establishes the F8 contract:

- all-category backup includes a `paused` payload;
- backup format advances once from 3 to 4 for the F8/F9 wire extension;
- format 3 remains admitted by the production parser;
- paused capture uses the repository backup projection and preserves deterministic `orderPosition` ordering;
- restore forces `Paused`, clears `executionId`, uses the existing F6 restore-ID remapping path, and inserts with `preserveOrderPosition = true`;
- paused restore is deliberately excluded from `startDownloadWorker()`;
- Reset deletes only paused rows for the paused category rather than deleting queued siblings;
- repeated Merge allocates new destination Download row IDs rather than trusting backup numeric IDs.

No new F4/F5/F6/F7 regression was found in the affected production composition.

## Execution evidence

External implementation-agent evidence at exact final `a9586835...` includes:

- `BackupPausedProductionWiringTest`: 4/4 PASS;
- `BackupSettingsProductionWiringTest`: 8/8 PASS;
- `BackupMoveResultProductionWiringTest`: 1/1 PASS;
- `BackupRestoreThumbnailProductionWiringTest`: 5/5 PASS;
- `BackupRestoreIdentityProductionWiringTest`: 6/6 PASS;
- `BackupPreferenceProductionWiringTest`: 3/3 PASS;
- full JVM: 623/623 PASS, 0 skipped/failures/errors;
- KSP, debug/android-test Kotlin compilation, debug/android-test APK assembly, and `git diff --check`: PASS;
- Room migration: not required.

The focused paused wiring covers paused-only capture/restore, order/operation/retry metadata, no worker enqueue, all-category inclusion, Reset sibling isolation, and repeated Merge.

Implementation-agent execution is evidence, not independent reviewer execution.

INDEPENDENT EXECUTION: NOT EXECUTED