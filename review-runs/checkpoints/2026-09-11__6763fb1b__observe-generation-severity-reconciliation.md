# Canonical reconciliation — Observe generation authority severity at 6763fb1b

- Exact implementation SHA / contiguous CLEAN Review Basis: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Prior canonical review checkpoint: `37d8007b8907274e0d72e2ee4d045d0124eb98d8`
- Scheduled evidence being reconciled: `d915b214394cb2aceff712bd9c32e82d1d173e0d`
- Plan reference: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Master Plan canonical SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Master Plan modified: NO
- Implementation branch modified: NO
- Global verdict: `NOT_CLEAN`

## Reconciliation decision

ADOPT the scheduled semantic reclassification of the already-counted `BUG-OBSERVE-HANDOFF-01` root from P2 to P0.

This is not a new root and does not change the total number of canonical blocker roots. It establishes a stronger concrete impact for the existing Observe generation-ownership/revocation defect.

Canonical count change relative to the authoritative handoff immediately before this reconciliation:

- before: `P0 2 / P1 3 / P2 26`
- severity transfer: `P0 +1 / P2 -1`
- after: `P0 3 / P1 3 / P2 25`
- root-count delta: `0`

The contiguous independently CLEAN Review Basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`; this review changes disposition/severity of an already-open root, not implementation correctness of the accepted P2-B repair.

## Exact-current-source proof

The historical scheduled checkpoint was based on `c2294c87...`, so its conclusion was not imported automatically. The same authority chain was freshly revalidated on exact current source `6763fb1b...`.

### 1. Reconfiguration/STOP becomes durable before cancellation is a fence

`ObserveSourcesViewModel.insertUpdate()` persists an update through `repository.update(item)` and only afterward invokes `repository.observeTask(item)`. `stopObserving()` similarly sets STOPPED and persists it before calling `repository.cancelObservationTaskByID(item.id)`.

`ObserveSourcesRepository.observeTask()` first calls `cancelObservationTaskByID()` and then enqueues `OBSERVE<id>` with `ExistingWorkPolicy.REPLACE`. `cancelObservationTaskByID()` merely issues WorkManager cancellation calls (`cancelUniqueWork` / tag cancellation); it does not await their returned Operations or establish a durable source-generation fence.

Therefore a previously admitted worker may continue after a newer source configuration or STOPPED state has already become durable.

### 2. Ordinary worker has no immutable generation/revision witness

`ObserveSourceWorker.runSourceWork()` receives only the numeric source id for ordinary recurring work and loads `item = repo.getByID(sourceID)` once. The config fingerprint validation is conditional on the special notification-confirmation handoff (`handoffId` plus `INPUT_CONFIG_FINGERPRINT`); normal Observe work has no equivalent generation/revision token.

The worker then retains and mutates that loaded `ObserveSourcesItem` through the run.

### 3. Stale worker can overwrite current source state and publish a successor

`updateRunStatus()` mutates the retained `item` and calls `repo.update(item)`. `finishRunAndSchedule()` likewise mutates the retained object, calls `repo.update(item)`, and then enqueues a new `OBSERVE<sourceID>` successor.

`ObserveSourcesRepository.update()` delegates ordinary ACTIVE updates to DAO `update(item)`, and `ObserveSourcesDao.update()` is a full-row Room `@Update(onConflict = REPLACE)` with no expected revision/config/status predicate.

Thus an old worker that continues after edit/STOP may write its stale full-row snapshot back over the newer durable source state and may schedule a successor from the stale generation.

### 4. The stale generation reaches a destructive History/file boundary

The impact is stronger than scheduling/state liveness. While operating on the retained old `item`, `ObserveSourceWorker` enters the `syncWithSource` branch when enabled, derives missing links from the old source snapshot/result authority, selects History records, and calls `HistoryFileDeletionEngine.execute(validation)` inside `HistoryReferenceMutationCoordinator.withLock`, followed by History-record removal.

That deletion path revalidates History file-reference snapshots and cross-record references, but it does not re-read the Observe source or verify that the worker's source generation/configuration remains current before the file deletion. Consequently a stale worker can delete a History media file after a newer edit or STOP has already revoked the generation that supplied the absence decision.

This supplies the concrete destructive-authority chain required for P0 severity:

`durable newer Observe configuration/STOP`
→ `old worker survives asynchronous cancellation`
→ `no current-generation witness/revalidation`
→ `old syncWithSource absence decision`
→ `HistoryFileDeletionEngine.execute()`
→ `user media deletion under revoked authority`.

## Root relation

Keep this as the existing `BUG-OBSERVE-HANDOFF-01` root: Observe configuration/scheduling generation ownership, supersession and revocation.

It remains distinct from:

- `BUG-OBSERVE-01` (source-result completeness/failed extraction becoming authoritative absence), even though both can reach destructive History synchronization;
- `BUG-OBSERVE-SOURCE-IDENTITY-01` (durable semantic source uniqueness/identity across source rows);
- P2-K command/source-token identity;
- P2-B archive/producer identity.

No new root is added.

## Acceptance direction retained

A repair must give Observe configurations an immutable durable generation/revision identity and require ordinary workers to carry it. Reconfiguration, STOP and delete must durably supersede/revoke the prior generation. Current-generation validation must guard source-row writes, successor publication, Download publication, and absence-sensitive/destructive History/file effects; stale full-row status writes should be replaced by revision-scoped/CAS-style mutations or an equivalent fence.

## Result

- `BUG-OBSERVE-HANDOFF-01`: OPEN, severity `P0`
- semantic root delta: `0`
- canonical count: `P0 3 / P1 3 / P2 25`
- CLEAN basis: unchanged at `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- overall gate: `NOT_CLEAN`

INDEPENDENT EXECUTION: NOT EXECUTED
