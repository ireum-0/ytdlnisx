# Independent Track A checkpoint — Observe generation authority severity reclassification

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Reclassification — existing `BUG-OBSERVE-HANDOFF-01` root is P0

This checkpoint does not add a second Observe scheduling/generation root. It expands the already-counted `BUG-OBSERVE-HANDOFF-01` with newly confirmed stale-consumer safety impact and raises that root from P2 to P0.

The original checkpoint already required that reconfiguration supersede an old Observe generation and that STOPPED/delete revoke it. The fixed-basis production source does not enforce that at the consumer mutation boundary.

### Producer ordering: new source state becomes durable before worker cancellation

For an existing source, `ObserveSourcesViewModel.insertUpdate()`:

1. writes the updated `ObserveSourcesItem` through `repository.update(item)`;
2. only afterward calls `repository.observeTask(item)`.

`observeTask()` itself first calls `cancelObservationTaskByID(id)` and then publishes replacement work. The cancellation calls are asynchronous WorkManager cancellation operations and their returned Operations are not observed.

`stopObserving()` has the same critical ordering:

1. mutate the source object to `STOPPED`;
2. durably update the row;
3. only afterward request cancellation of the old WorkManager generation.

Therefore there is an explicit interval where the new edit/STOP state is already the durable authority while the old worker can still execute.

### Stale full-row consumer

A normal `ObserveSourceWorker` loads the complete `ObserveSourcesItem` once near the beginning of `runSourceWork()`.

Only notification-confirmation handoffs carry and validate a config fingerprint. An ordinary Observe worker has no source revision/generation witness.

The worker then repeatedly mutates its stale in-memory object and performs full-row `repo.update(item)` calls from `updateRunStatus()` and `finishRunAndSchedule()`.

`ObserveSourcesRepository.update()` delegates an ACTIVE source to the DAO full-row `@Update`; it has no expected revision/status/config predicate.

Consequences after an edit/STOP wins first:

- stale URL/template/schedule/flags can overwrite the user's newer edit;
- a stale ACTIVE object can overwrite a newly persisted `STOPPED` row back to ACTIVE;
- `finishRunAndSchedule()` can then publish another `OBSERVE<id>` successor from the stale generation.

### Destructive stale-generation boundary

The impact is not limited to liveness or UI settings.

When the stale worker's snapshot has `syncWithSource = true`, it computes absent links from that old source snapshot and enters the History-file removal path. It validates current History file references against other History records, but it does **not** validate that the Observe source generation/configuration is still current before the destructive effect.

Inside the source-sync removal path the worker invokes `HistoryFileDeletionEngine.execute(validation)` before removing the corresponding History records.

Concrete sequence:

1. Observe generation E1 for source configuration A is already running and has loaded A into memory;
2. user edits A -> B, or presses Stop;
3. the new source row B/STOPPED commits;
4. WorkManager cancellation of E1 has not yet taken effect (and its Operation is not awaited);
5. E1 continues with stale configuration A;
6. E1 reaches `syncWithSource` absence processing and deletes a History media file using A's stale source result/processed-link authority;
7. the destructive mutation occurred after E1 had been superseded/revoked by the durable source state.

The same stale worker can subsequently overwrite source state with its old full-row object.

This is a concrete user-data destructive authority violation. Cooperative/asynchronous WorkManager cancellation is not a final generation fence because the DB update is deliberately ordered before the cancellation request and file deletion itself can occur before cancellation is observed.

## Semantic root / severity

Keep one root rather than counting a second finding:

`BUG-OBSERVE-HANDOFF-01` = Observe source generation ownership, including:

- ACTIVE durable state -> accepted/recoverable successor handoff;
- startup ownership repair;
- reconfiguration/STOP revocation;
- consumer-side generation validation before source-state writes;
- consumer-side generation validation before absence-sensitive/destructive History mutations.

The newly established destructive impact raises the root severity from **P2 to P0**.

This remains distinct from P0 `BUG-OBSERVE-01`, whose cause is a source extraction result that cannot distinguish AUTHORITATIVE/PARTIAL/FAILED. Both can reach the History deletion boundary, but the authorities are different: one is result completeness, the other is stale Observe generation ownership.

## Acceptance direction

- Give every Observe configuration/scheduling generation an immutable durable revision/generation identity.
- Every worker consumes the exact generation/revision it was admitted for, including ordinary recurring work, not only notification-confirmation handoffs.
- Reconfiguration and STOP/delete durably revoke/supersede the prior generation.
- Before every source-row mutation, successor publication, Download publication, and History destructive/absence mutation, revalidate the exact current Observe generation.
- Do not write a full stale source snapshot merely to update run status/history/count; use revision-scoped column updates or equivalent CAS semantics.
- A stale worker exits without source-state, queue, or file/History side effects.
- Preserve durable successor ownership/recovery from the original handoff finding.
- Tests must include edit-vs-running-worker, STOP-vs-running-worker, stale run-status write, stale successor scheduling, and stale syncWithSource deletion attempts.

## Recount

Previous canonical working count after `BUG-OBSERVE-SOURCE-IDENTITY-01`:

- `P0 2`
- `P1 3`
- `P2 25`

Reclassify the already-counted `BUG-OBSERVE-HANDOFF-01` root from P2 to P0. Root count is unchanged:

- `P0 3`
- `P1 3`
- `P2 24`

Overall verdict remains `NOT_CLEAN`.

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
