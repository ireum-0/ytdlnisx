# Batch E correction — BUG-DATE-03 remains open on exact production basis

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT_CORRECTION
checkpoint_status: FINAL
review_parent_sha: 85f4b9d72865377b252c37733cc8af1cf1eecbc0
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

supersedes:
- only the BUG-DATE-03 disposition and batch aggregate counts in
  review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-batch-e-open-revalidation.md

## Correction reason

The prior Batch E BUG-DATE-03 classification incorrectly used review/remediation tree/search absence as
evidence that the production History date-fetch feature was absent.

That was the wrong tree for production-source existence.

Exact implementation eda6a758 contains:
- app/src/main/java/com/ireum/ytdl/work/HistoryDateFetchManager.kt
- app/src/main/java/com/ireum/ytdl/work/HistoryDateFetchWorker.kt
- App.onCreate startup reconciliation calling HistoryDateFetchManager.get(...).reconcile().

## BUG-DATE-03 — VERIFIED_OPEN P2

The historical failure path still exists on exact eda6a758:

1. HistoryDateFetchManager.startOrReconnect() first calls repository.createOrReconnect(), durably
   establishing/reusing the nonterminal operation and child state.
2. It then calls private enqueue(operationId).
3. enqueue() creates a one-time HistoryDateFetchWorker and calls
   WorkManager.enqueueUniqueWork(..., ExistingWorkPolicy.KEEP, request).
4. The returned WorkManager Operation is not observed or awaited.
5. HistoryDateFetchManager.reconcile() enumerates nonterminal operations and calls the same enqueue()
   implementation, so startup recovery also discards asynchronous enqueue failure.
6. No durable scheduler-acceptance/retry debt is established by this manager boundary.

Therefore asynchronous WorkManager enqueue failure after the durable operation commit can still leave a
nonterminal date-fetch operation without a confirmed carrier, and startup reconciliation can repeat the
same unobserved failure.

Current disposition:
BUG-DATE-03 = VERIFIED_OPEN P2.

Correction boundary remains the historical one:
- exact operationId/child snapshot is the semantic carrier;
- observe/await enqueue acceptance or persist exact retry/failure responsibility before representing
  ordinary RUNNING progress;
- current-process and startup reconciliation must both propagate/own enqueue failure;
- cancellation racing delayed enqueue must not resurrect a cancelled operation;
- exactly one accepted worker for one semantic operation.

## Preserved Batch E dispositions

BUG-OBSERVE-02 = VERIFIED_CLOSED.
BUG-QUEUE-05 = VERIFIED_OPEN P2.

## Corrected aggregate

Registry-derived/later-current lower-bound audit:
- candidate IDs: 136
- audited: 52
- verified closed/currently not reproduced: 48
- verified open: 4
- not yet audited inside lower bound: 84

Verified-open non-canonical repository roots found so far:
- BUG-PLAYER-01 — P2
- BUG-QUEUE-01 — P3
- BUG-QUEUE-05 — P2
- BUG-DATE-03 — P2

Current narrow download canonical remains P0=0 / P1=0 / P2=8.

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
