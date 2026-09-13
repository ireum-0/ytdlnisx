# F5 / BUG-BACKUP-02 — independent closure

## Reviewed state
- Exact implementation HEAD: `f20833d6d74134ec52a33c6cdb4a862784d9c4bf`
- F5 production semantics were independently source-reviewed as clean at exact `1bae1eafea08942f11e6df30e4a13a515dda621c`.
- The verification-hardening range `1bae1eaf... -> f20833d6...` changes only F5 instrumentation for this root; no F5 production restore semantics changed.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict
`BUG-BACKUP-02`: **CLEAN / CLOSED** at exact `f20833d6...`.

## Source-semantic closure

The reviewed production restore path preserves the F5 invariant:
- backup-local History ID is not used as final filesystem ownership identity;
- custom thumbnails first enter a restore-owned UUID staging namespace;
- destination History identity is allocated before final thumbnail binding;
- final live names are destination-owned and collision-resistant;
- publication is no-overwrite/exclusive;
- a publication failure cannot bind the failed destination path;
- failed History insertion and failed publication clean restore-owned staging/publication artifacts as applicable;
- unrelated pre-existing destination state is not overwritten by backup-local numeric filename equality.

## Required execution evidence now satisfied

The exact-final instrumentation matrix adds and reports successful execution of both previously missing F5 fault windows through `BackupRestoreThumbnailProductionWiringTest`:

1. **Destination publication failure after staging**
   - the expected live thumbnail directory is occupied by a regular file;
   - restore fails;
   - the newly restored History row does not retain a failed custom-thumbnail path;
   - the pre-existing obstruction bytes remain unchanged;
   - restore staging is cleaned.

2. **History insertion failure after valid staging**
   - a SQLite `BEFORE INSERT ON history ... RAISE(ABORT)` trigger forces the real Room/SQLite insertion boundary to fail;
   - restore fails;
   - the failed History row does not appear;
   - no live restored-thumbnail file remains;
   - restore staging is cleaned;
   - unrelated pre-existing History state remains.

The existing same-old-ID Reset/Merge, extension/content variation, invalid payload, and backup-local-path-clearing tests remain in the same exact-final class. Reported exact-final result: `BackupRestoreThumbnailProductionWiringTest` 5/5 PASS on API 36 x86_64 emulator.

## Regression / dependency assessment
- F4 remains independently OPEN for a distinct same-second backup artifact-identity residual; that does not invalidate this restore-thumbnail ownership closure.
- F6/F7 semantics are separate.
- No new F5 blocker was found in the final verification-only range.

## Canonical consequence
- P2 count: `28 -> 27`.
- Resulting canonical blockers at this sequential point: `P0 2 / P1 1 / P2 27`.
- Contiguous independently CLEAN basis does **not** advance because F4 remains open in the cumulative backup wave.

INDEPENDENT EXECUTION: NOT EXECUTED