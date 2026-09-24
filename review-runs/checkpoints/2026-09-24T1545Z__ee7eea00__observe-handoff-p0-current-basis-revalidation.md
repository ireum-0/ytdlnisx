# BUG-OBSERVE-HANDOFF-01 — current-basis revalidation at ee7eea00

Date: 2026-09-24 +09:00

Exact independently CLEAN implementation basis reviewed:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Prior exact-basis P0 checkpoint:
`review-runs/checkpoints/2026-09-13__90afaec1__observe-handoff-p0-exact-basis-revalidation.md`

F11 final closure checkpoint:
`42aa62f9b781ac32a0f587041d86adc72e75ff75`

## Verdict

**NOT_CLEAN — existing P0 `BUG-OBSERVE-HANDOFF-01` remains OPEN at exact current CLEAN basis `ee7eea00...`.**

Canonical count delta: 0.

Current canonical totals remain:
- P0 = 1
- P1 = 0
- P2 = 23

Overall remains NOT_CLEAN.
CLEAN_REVIEW_BASIS remains `ee7eea001462b77e88a201ed2f26c2385048d421`.

## Current exact-source evidence

### No ordinary immutable Observe generation

`ObserveSourcesItem` still has no immutable ordinary configuration generation/revision.

`ObserveSourcesRepository.enqueueObservation(...)` builds ordinary
`ObserveSourceWorker` input with only the numeric source id and publishes
`OBSERVE<id>`.

The newer exact fingerprint support is used by the specialized confirmed-retry
handoff path; ordinary Observe scheduling does not carry an equivalent immutable
configuration generation.

### Edit / STOP / delete do not durably revoke a worker generation

`ObserveSourcesViewModel.insertUpdate()` writes the edited row, then calls
`repository.observeTask(item)`.

`stopObserving()` writes STOPPED, then requests WorkManager cancellation.

Delete requests cancellation and removes the row.

F11 adds Restore admission around repository mutations/scheduling, which is
important for Restore ordering, but it does not create an ordinary
configuration-generation fence between two non-Restore Observe decisions.

### Worker still republishes retained full-row state

`ObserveSourceWorker.updateRunStatus()` mutates the retained
`ObserveSourcesItem` and calls `repo.update(item)`.

`finishRunAndSchedule()` mutates run state/status on the retained item, writes
it through the same repository update path, and then publishes a successor
`ObserveSourceWorker` carrying only `INPUT_SOURCE_ID`.

There is no expected/current configuration-generation CAS at these final
mutation/publication boundaries.

### Same P0 sequence remains reachable

E1 loads old Observe config
→ E2 durably edits / STOPs / deletes the same source
→ asynchronous cancellation is not immutable semantic revocation
→ E1 reaches later row/status/download/destructive/successor publication
→ no ordinary config generation proves E1 is still current
→ stale authority can overwrite/revive state or publish work/effects from the
superseded configuration.

### F11 changes do not close this root

F11 strengthened:
- Restore-vs-ordinary admission;
- scheduler/cleanup Restore ownership;
- specialized confirmed-retry fingerprinting;
- WorkManager handoff durability for other exact boundaries.

Those mechanisms do not bind ordinary Observe workers to a durable source
configuration generation and do not provide current-generation CAS at every
ordinary worker final effect.

## Root reconciliation

Keep `BUG-OBSERVE-HANDOFF-01` as the sole remaining P0.

Historical `BUG-OBSERVE-04` remains an alias/subcase.

Keep distinct from:
- P2 `BUG-OBSERVE-02` — worker-owned runtime field overwrite contract;
- P2 `BUG-OBSERVE-03` — ordinary recurring successor enqueue acceptance/debt;
- specialized confirmed-retry handoff behavior.

## Required correction invariants

A correction must establish one coherent ordinary Observe authority model:

1. every ordinary source configuration has an exact durable generation/revision;
2. every ordinary worker/request is bound to that generation;
3. edit/reconfigure, STOP and delete durably supersede/revoke older generations;
4. worker-owned runtime publication cannot overwrite newer configuration fields;
5. immediately before positive Download admission/publication, destructive
   History/file mutation, source-row runtime publication, and recurring
   successor publication, the worker proves its exact generation is still
   current;
6. stale workers converge without reviving state or publishing new work;
7. process death/restart preserves or reconstructs the same fence;
8. preserve F3 source-membership completeness, History target/reference
   revalidation, membership-retry revocation, specialized confirmed-retry
   fingerprint semantics, duplicate admission, and F11 Restore admission.

Do not conflate this P0 with P2 recurring enqueue-acceptance debt. The P0
generation fence must be correct even if the separate P2 handoff debt remains
for a later closure.

INDEPENDENT EXECUTION: NOT EXECUTED
