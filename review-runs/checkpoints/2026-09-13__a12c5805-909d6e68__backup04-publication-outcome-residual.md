# F4 / BUG-BACKUP-04 — completed implementation independent review

- Review base: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`
- Completed implementation head: `909d6e68a12e84b78455ec600bdc7a9b1727e0e2`
- Reported parent and independently verified parent: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`
- Implementation branch remote HEAD independently verified exact: `909d6e68a12e84b78455ec600bdc7a9b1727e0e2`
- Governing root: F4 / existing P1 `BUG-BACKUP-04`
- Verdict: **NOT_CLEAN — focused review-fix required**
- Canonical count delta: `0`
- Canonical blockers remain: **P0 2 / P1 1 / P2 29**
- Contiguous independently CLEAN basis remains: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`

## Confirmed corrected F4 surfaces

The completed change is one commit ahead of the review base and changes exactly:

- `BackupSettingsUtil.kt`
- `SettingsViewModel.kt`
- `BackupSettingsUtilTest.kt`
- `BackupSettingsProductionWiringTest.kt`

Independent exact-source review confirms the following improvements:

1. Backup capture helpers now preserve ordinary capture failures as typed `Result.failure` instead of collapsing them to successful empty `JsonArray` values. `CancellationException` is explicitly rethrown by the new capture wrappers.
2. Production selected-category composition consumes helper results with `getOrThrow()`, so a helper failure cannot be mistaken for an empty selected category.
3. The downloads snapshot captures History plus automatic-keyword rules/keywords/matches/assignments inside one Room transaction.
4. Keyword group rows/members and Youtuber groups/members/relations/metadata are captured inside Room transactions.
5. Custom-thumbnail capture uses the same snapshotted History rows rather than rereading History later. A nonblank required thumbnail path must resolve to a readable file and `readBytes()` must succeed; otherwise backup fails.
6. Genuine empty selected state remains representable as a successful empty capture.
7. The persisted backup format remains version 3; F4 did not introduce a format bump.
8. Backup-directory creation, temp-file creation/write and absent publication output are now surfaced as failures instead of unconditional success.

These changes resolve the original helper-local empty-success collapse and the earlier mixed-time Room rereads at source level.

## Remaining blocker — final publication result is not operation-local

`SettingsViewModel.backupInternal()` now attempts to detect partial `FileUtil.moveFile()` publication failure by:

`moveFile(...) -> FileUtil.consumeLastMoveFailureDetails() -> movedPaths.firstOrNull()`.

However `FileUtil.lastMoveFailureDetails` is one process-global `@Volatile` mutable slot shared by every `moveFile()` invocation. `moveFile()` clears this global slot at the beginning of each call and writes it only as a side channel when that call encountered one or more per-file failures. `consumeLastMoveFailureDetails()` separately reads and clears the same slot after `moveFile()` has returned.

The failure result is therefore not bound to the exact backup publication operation.

Concrete race:

1. Backup publication A moves from the backup staging directory.
2. A publishes at least one output but another source publication fails, so A records `lastMoveFailureDetails = A-error` and returns a non-empty output list.
3. Before `SettingsViewModel` consumes the sidecar, unrelated concurrent `FileUtil.moveFile()` B starts.
4. B clears the global `lastMoveFailureDetails = null` at its own start.
5. A then calls `consumeLastMoveFailureDetails()` and receives `null`.
6. Because A already has a non-empty `movedPaths`, `firstOrNull()` is returned and the backup reports success despite A's partial publication failure.

The inverse interference is also possible: another move's failure can be consumed by the backup and falsely attributed to A.

This directly violates F4's final backup write/move-failure requirement and the v6 identity/semantic-carrier rule: an operation's publication outcome cannot be carried by an unkeyed process-global side channel that another operation can reset or overwrite.

This is a residual of the existing `BUG-BACKUP-04` root, not a new blocker/root.

## Required correction boundary

Use an operation-local publication result for the backup move. The exact implementation is open, but correctness requires one of the following equivalent properties:

- `moveFile` (or a narrowly scoped strict variant) throws for any failure belonging to that exact invocation before returning success; or
- the exact invocation returns a typed result containing its own outputs and failures.

Do not make F4 correctness depend on `consumeLastMoveFailureDetails()` or another unkeyed shared mutable sidecar.

Preserve existing `FileUtil.moveFile` behavior for unrelated consumers unless their contract is deliberately migrated and reviewed. Prefer an additive strict/per-call API if that minimizes semantic-contract blast radius.

After the review-fix, re-review the full original F4 range `a12c5805... -> new final SHA`, not only the last commit.

## Verification evidence state

Implementation-agent evidence at exact `909d6e68...` reports:

- `BackupSettingsUtilTest`: 4/4 PASS;
- `BackupSettingsProductionWiringTest`: 3/3 PASS on API 36 x86_64 emulator;
- full JVM: 617/617 PASS;
- KSP PASS;
- debug Kotlin compile PASS;
- android-test Kotlin compile PASS;
- `git diff --check` PASS.

Those results are evidence, not independent reviewer execution. The current focused tests also do not exercise the newly identified concurrent/global publication-outcome race. The eventual final F4 candidate still needs exact-final-SHA production-path evidence for final publication failure, plus the governing F4 fault matrix as applicable.

No F4 closure and no CLEAN-basis advance is permitted at `909d6e68...`.

INDEPENDENT EXECUTION: NOT EXECUTED