# Independent correctness review — F10/F16/F17 wave final verdict

- reviewed implementation branch: `checkpoint/pre-baseline-review`
- reviewed start SHA: `973424909fd97de758b62f639967c8bae7c0bad7`
- reviewed final SHA: `f6e7cf72e00c013ee9b770bf748cba7f38848256`
- exact remote final SHA match: verified
- exact commit distance: 5 commits, no divergence
- governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Final dispositions

### F10 / BUG-CLEANUP-01 — OPEN

The stale-debt/replay normal-success correction materially improves the schedule authority path, but a v6 first-write/non-exception persistence failure remains.

After the new cadence/generation authority commit succeeds, `retirePendingDebtLocked()` performs a second SharedPreferences commit. If that second commit returns false, `configure()` exits before cancelling superseded tagged WorkManager work and before establishing current-generation work. The settings listener cannot roll back the authority already committed by the coordinator. `CleanUpLeftoverDownloads` generation-fences successor publication but not the cleanup body before execution, so disable can leave an older occurrence able to perform cleanup after disable authority has won; enabled supersession can leave old work plus new durable preference authority while new scheduling responsibility is stranded until later reconciliation.

This is the same existing F10 P2 root, not a new root. Count delta: 0.

Controlling F10 reconciliation checkpoint: `2827b8dc360b5b3e9253038d1cc8be81eb81f152`.

### F16 / BUG-DATE-02 — CLOSED_AT_F6E7CF72

Retryable child state remains PENDING while retry authority remains; WorkManager returns retry during the budget; exhaustion/final failures transactionally terminalize remaining pending children and parent; normal finalization cannot produce COMPLETED when failed children exist; terminal WorkManager result and notification follow the persisted parent/child distribution. Manager reconnect/startup reconciliation reuses durable operation/child state and unique work identity.

Implementation-agent exact-SHA evidence reported and accepted as evidence: HistoryDateFetchPersistenceTest 10/10, HistoryDateFetchPolicyTest 16/16, HistoryDateFetchEnqueuePolicyTest 1/1, full JVM 626/626, compilation/APK assembly passes.

Count delta: -1.

Controlling F16 closure checkpoint: `7b37cad88b972f66779e679d7627b1e6369ae2a4`.

### F17 / BUG-HISTORY-01 — CLOSED_AT_F6E7CF72

HistoryUndoSnapshot owns the History row, keyword assignments, and playlist memberships. Capture/delete and restore are protected by HistoryReferenceMutationCoordinator plus Room transaction; replacement-row restore is rejected; bulk/record-only and file-backed database removal paths converge through relationship-aware transaction boundaries; the production HistoryFragment Undo path consumes the new snapshot. Transaction-failure tests prove no partial database graph commit. F18 current-rule recomputation remains a deliberately separate semantic root and is now unblocked.

Implementation-agent exact-SHA evidence reported and accepted as evidence: HistoryUndoPersistenceTest 6/6, HistoryFileDeletionTest 25/25, preservation instrumentation passes, full JVM 626/626, compilation/APK assembly passes.

Count delta: -1.

Controlling F17 closure checkpoint: `42dbf55771066d8cefc80d719d181787498de256`.

## Canonical reconciliation

Starting canonical count before this wave review: P0 2 / P1 0 / P2 23.

- F10 residual: same root, delta 0 -> P2 23.
- F16 closure: delta -1 -> P2 22.
- F17 closure: delta -1 -> P2 21.

Final canonical blocker count after this review: **P0 2 / P1 0 / P2 21**.

Overall remains `NOT_CLEAN`.

The contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`; the reviewed final SHA cannot become a clean basis while F10 and other earlier/open roots remain.

No Room migration was introduced by this wave. F11 and F18 were not implemented. F18 is now dependency-unblocked by F17 closure.

Earlier append-only f6e7cf72 checkpoints that called F10 source-semantic fixed are explicitly reconciled: their F16/F17 semantic observations remain consistent with these closures, but their F10 disposition is superseded by the later first-write persistence-failure proof.

INDEPENDENT EXECUTION: NOT EXECUTED
