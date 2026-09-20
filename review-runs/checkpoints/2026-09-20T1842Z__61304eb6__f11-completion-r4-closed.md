# F11 completion re-review — R4 disposition

Date: 2026-09-20

## Exact reviewed state

- Exact final remote implementation SHA: `61304eb6f11b10ac66057a1978d5b1f8f75019b0`.
- Original F11 base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`.
- Canonical F11 review: `8b5b064c63fc04de9b2d18346954ab5dfdec625e`.

## Disposition

### F11-R4 — CLOSED at source / remediation accepted

The original R4 defect was process-death non-idempotence in immediate Download reconciliation because each replay created a UUID-distinct unique-work name.

Exact final source closes that contract:

1. ordinary Download triggers still use the existing independent identity:
   `DownloadWorker-<request UUID>` with ordinary REPLACE semantics;
2. restore reconciliation derives:
   `restoreSuffix = restore-<operationId>`;
3. immediate restore work uses:
   `DownloadWorker-restore-<operationId>`;
4. delayed restore WorkManager work uses:
   `scheduledDownload-restore-<operationId>-<startTime>`;
5. restore-owned work uses `ExistingWorkPolicy.KEEP`;
6. replay of the same durable Restore operation therefore addresses the same unique-work owner instead of adding a UUID-distinct sibling;
7. `DownloadWorker.doWork()` returns `Result.retry()` while RestoreGate remains active, preventing an accepted restore-owned worker from executing the Reset-targeted graph during RECONCILING.

Thus accepted enqueue → same-operation replay converges on one current unfinished unique-work responsibility.

## Regression-contract separation

Normal user/worker triggers retain their prior random request identity and ordinary semantics. The idempotent identity is scoped only to the Restore-reconciliation path.

R3's alarm/handoff liveness residual is separate: it concerns failure to transfer a pending future scheduler carrier after exact-alarm publication failure, not duplicate immediate Download WorkRequests.

## Test evidence

`acceptedRestoreSchedulingReplaysWithOneCurrentOwner()` observes actual WorkManager unfinished state for an operation-scoped restore unique-work name before and after same-operation recovery and reported PASS in the implementation-agent run.

The test exercises the delayed non-alarm WorkManager branch rather than the exact immediate branch, so implementation-agent runtime evidence is not relabeled as exhaustive independent runtime proof. The immediate source branch nevertheless uses the same explicit operation-scoped identity/KEEP contract and has no remaining random-name restore path.

## Canonical reconciliation

- F11-R4 is CLOSED.
- No new root/count.
- F11 overall remains NOT_CLEAN because R1/R2/R3 remain open.

## Evidence confidence

- Source closure: independently reviewed at exact `61304eb6...`.
- Runtime result: implementation-agent evidence only.

INDEPENDENT EXECUTION: NOT EXECUTED
