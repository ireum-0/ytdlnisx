# BUG-BACKUP-02 — final-wave independent review

Date: 2026-09-13

## Exact review state

- Previous independently CLEAN contiguous basis: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- F5 implementation commit: `53ced7312a5a0173ab1f406c83bb16b71ad4cd1a`.
- Final completed wave HEAD independently verified at remote: `1bae1eafea08942f11e6df30e4a13a515dda621c`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F5 / `BUG-BACKUP-02`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**SOURCE_CLEAN / EXECUTION_EVIDENCE_INCOMPLETE — existing P2 `BUG-BACKUP-02` remains OPEN.**

Canonical blocker-count delta: `0`.

Canonical count remains **P0 2 / P1 1 / P2 29** at this boundary.

The contiguous CLEAN basis remains exact `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.

## Source-semantic closure

The exact final restore implementation no longer uses backup-local History numeric IDs as live filesystem ownership identity.

Current production composition establishes:

1. custom-thumbnail payloads are decoded into a UUID-scoped restore staging namespace;
2. imported History rows are inserted with destination-owned database identity before a final custom-thumbnail pathname is bound;
3. when a backup payload exists for the old History ID, the History row is initially inserted without binding the backup-local thumbnail pathname;
4. final publication uses a collision-resistant destination-owned name of the form `history_<destinationHistoryId>_<uuid>.<ext>`;
5. final creation is exclusive/no-overwrite rather than ordinary overwrite of a shared `restored_<oldId>` pathname;
6. the destination History row is updated only with the exact new destination-owned path;
7. failed binding removes the newly published file, and staging cleanup runs in the restore cleanup path;
8. when no portable thumbnail payload exists, a backup-local custom-thumbnail pathname is not retained as live destination ownership.

`HistoryKeywordAssignmentRepository.insertHistory()` ultimately obtains a fresh destination History identity from the destination Room database, so two independent restores that reuse the same backup-local History ID do not share final filesystem identity.

No source-semantic reproduction of the original overwrite/alias root was found at exact final HEAD `1bae1eaf...`.

## Execution evidence

The implementation report provides exact-final-wave external evidence including `BackupRestoreThumbnailProductionWiringTest` 3/3 PASS and the cumulative full/build verification.

The exact production-wiring tests cover:

- Reset followed by Merge using the same backup-local History ID while changing thumbnail content and extension;
- preservation of the first imported row's bytes/path after the later restore;
- unique destination-owned paths;
- rejection of an invalid thumbnail payload without binding History or leaving a published thumbnail;
- clearing a nonportable backup-local custom-thumbnail path when no portable payload owns it.

However the pinned F5 execution plan also requires explicit failure evidence for the restore file-write/publication boundary and History insertion failure. The final test inventory does not inject either a destination thumbnail write/publication failure or a History insertion failure after staging. Therefore the cleanup/rollback branches for those required fault windows are source-reviewed but not actually executed by the provided final evidence.

Under Review Checklist v6, the required actual execution evidence remains incomplete, so F5 is not yet independently CLOSED.

## Next required evidence

Add deterministic production-path fault injection for at least:

- destination thumbnail publication/write failure after staging, proving no History row is left bound to a bad path and owned temporary artifacts are cleaned;
- destination History insertion failure with a staged thumbnail present, proving no live thumbnail is published/bound and staging is cleaned.

Production changes are not requested unless those tests reveal a semantic residual.

INDEPENDENT EXECUTION: NOT EXECUTED