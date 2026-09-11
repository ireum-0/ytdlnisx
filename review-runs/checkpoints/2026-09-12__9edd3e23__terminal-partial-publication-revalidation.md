# BUG-TERMINAL-04 — current-basis partial-publication revalidation

Date: 2026-09-12

## Exact review basis

- Independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Historical broader-registry item: P2 `BUG-TERMINAL-04` — do not report partially published Terminal cache output as success
- Governing protocol: `ireum-0/private:ytdlnisx-review:ytdlnisx/REVIEW_PROTOCOL.md`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna queue task during review: task 008 candidate; its moving diff was not inspected or relied on.

## Verdict

**CLOSED / historical broader-registry defect is not reproduced at exact CLEAN basis `9edd3e23...`.**

Canonical count delta: **0**.

Canonical count remains **P0 2 / P1 1 / P2 31**.

CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## Exact current production evidence

`TerminalDownloadWorker` now establishes exact publication/recovery state before cached Terminal output is moved to its final destination.

For cache-backed output publication:

1. a `PublicationRecoveryJournal` is advanced to `PUBLISHING` before publication;
2. `FileUtil.moveFile(...)` is called with exact source files from the current owned attempt;
3. reservation intent, unknown reservation state, rollback, exact destination reservation, and committed output are persisted through journal callbacks;
4. any journal write failure causes an exception rather than success;
5. the worker requires at least one authoritative published path;
6. it requires that no current-attempt source file remains stranded in staging;
7. the owned artifact manifest must be removed successfully before the success path can continue.

The worker therefore does not treat a partially moved set as successful merely because some destination files were published.

## Failure / process-death behavior

Partial-publication failure retains exact recovery evidence rather than deleting it as ordinary cleanup:

- published destination paths and journal state are copied into a Terminal recovery carrier;
- exact live ownership is revoked while staging artifacts/manifest are preserved;
- the worker immediately invokes `TerminalExecutionRecovery` and `TerminalPublicationRecovery` where possible;
- application startup performs the same Terminal execution/publication recovery pass, so surviving durable carriers remain discoverable after process death.

Unknown provider reservation state is explicitly quarantined instead of being assumed successful or safe to retry as a new ordinary attempt.

## Semantic success boundary

The Terminal semantic result is not committed merely when native execution or a subset of file moves succeeds.

The worker confirms Terminal-row deletion first, then marks `terminalSemanticCommit = true`, then marks the exact execution committed. Publication journal finalization is recorded as `COMMITTED`; later journal/marker retirement is convergence debt rather than evidence that a partial publication should be reported as a successful new attempt.

This ordering addresses the historical defect class: partial destination publication cannot be silently converted into ordinary Terminal success while exact staging/recovery state is lost.

## Root reconciliation

Do not add a blocker for historical `BUG-TERMINAL-04` at the current basis.

Keep distinct from:

- promoted P2 `BUG-TERMINAL-03`, which owns SAF/provider destination authority before native execution;
- existing `BUG-MOVE-01`, which concerns different Download publication semantics;
- existing Terminal post-output bookkeeping/recovery roots, which govern failures after an already-authoritative semantic publication result;
- generic Terminal enqueue-loss/liveness findings, which govern whether a durable Terminal intent obtains a WorkManager carrier at all.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
