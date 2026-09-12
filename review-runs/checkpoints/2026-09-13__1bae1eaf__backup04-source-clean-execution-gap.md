# BUG-BACKUP-04 — final-wave independent review

Date: 2026-09-13

## Exact review state

- Previous independently CLEAN contiguous basis: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Original F4 implementation: `909d6e68a12e84b78455ec600bdc7a9b1727e0e2`.
- F4 review-fix commit: `dafa3616d7ff4a98605b31d459d7de77dc61639f`.
- Final completed multi-defect wave HEAD independently verified at remote: `1bae1eafea08942f11e6df30e4a13a515dda621c`.
- Exact implementation comparison `909d6e68...1bae1eaf`: five commits ahead, zero behind.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F4 / `BUG-BACKUP-04`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**SOURCE_CLEAN / EXECUTION_EVIDENCE_INCOMPLETE — F4 remains OPEN and is not yet independently CLOSED.**

Canonical blocker-count delta: `0`.

Canonical count at this boundary remains **P0 2 / P1 1 / P2 29**.

The contiguous CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.

## Source-semantic closure

The full F4 cumulative final source was re-read, including the original `909d6e68...` correction and the later review-fix/final test follow-up.

The previously confirmed publication residuals are corrected:

1. `SettingsViewModel.backupInternal()` creates one exact `saveFile` and passes only `sourceFiles = listOf(saveFile)` to the move operation. A stale sibling in the shared `Backups` staging directory cannot donate publication authority to this backup invocation.
2. `FileUtil.MoveFileResult(paths, failures)` carries the exact invocation's publication outcome. F4 no longer relies on `consumeLastMoveFailureDetails()` or the unkeyed process-global failure sidecar.
3. Backup fails if the invocation-local failure list is non-empty and requires exactly one nonblank destination for the exact current source.
4. Helper capture failures remain typed and consumed through failure-propagating production composition; a genuine empty category remains successful empty state.
5. Downloads plus automatic-keyword Room state are captured under one Room transaction; keyword-group Room state and Youtuber relational Room state are likewise captured under Room transactions.
6. Custom-thumbnail capture uses the snapshotted History rows and a configured thumbnail must exist, be a readable file, and read successfully.
7. Serialization/file creation/write/publication failures remain failure paths.
8. Backup format remains version 3.

No remaining source-semantic path was found that reproduces the original F4 hidden-failure or publication-identity root at exact final HEAD `1bae1eaf...`.

## Execution-evidence gate

The implementation report provides exact-final-wave external evidence:

- `BackupSettingsProductionWiringTest`: 4/4 PASS;
- `BackupMoveResultProductionWiringTest`: 1/1 PASS;
- `BackupSettingsUtilTest`: 4/4 PASS;
- full JVM: 617/617 PASS, 0 failures/errors/skips;
- KSP PASS;
- debug Kotlin compile PASS;
- Android-test Kotlin compile PASS;
- debug/android-test APK assembly PASS;
- `git diff --check` PASS.

Those are useful external execution evidence, but the established F4 correction/test boundary also required deterministic evidence for concurrent mutation of related relational state and final write/move failure through the complete backup production boundary. The final test inventory contains strong exact-artifact, empty-state, missing-thumbnail, ordinary-success and invocation-local move-result coverage, but no deterministic concurrent-relational-mutation test and no direct injected final backup write-failure test through `SettingsViewModel.backup()`.

Under Review Checklist v6, semantic source closure alone is insufficient when required actual execution evidence for a triggered correctness boundary remains materially absent. F4 therefore cannot yet be independently CLOSED and the CLEAN basis cannot advance through this wave.

## Next required evidence

A focused additive verification-hardening wave should add deterministic production-path execution evidence for at least:

- concurrent relational mutation while a selected related Room category is captured, proving the emitted relational state is one transactionally coherent snapshot;
- final staging-file write failure through `SettingsViewModel.backup()` proving no successful artifact result;
- retain exact invocation-local move/publication failure coverage at final HEAD.

Production changes are not requested unless those tests expose a genuine semantic residual.

INDEPENDENT EXECUTION: NOT EXECUTED