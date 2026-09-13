# BUG-OBSERVE-HANDOFF-01 — exact CLEAN-basis revalidation at 90afaec1

Date: 2026-09-13

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior exact-basis checkpoint: `cf69d7e7a1bf7d895f6a493338f042fff42b273a` at `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- F10+F16+F17 implementation wave is active from exact completed start SHA `973424909fd97de758b62f639967c8bae7c0bad7`; no in-progress implementation commit/diff after that SHA was inspected or used as evidence.

## Verdict

**NOT_CLEAN — existing P0 `BUG-OBSERVE-HANDOFF-01` remains OPEN at exact CLEAN basis `90afaec1...`.**

- Canonical count delta: `0`.
- Reconciled canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Overall remains `NOT_CLEAN`.

## Intervening-range reconciliation

Exact compare `a12c58055fff51b104f8b56fd53b534b8d7e5df4..90afaec157607669ea32fa41877e7f0efcdcca86` is 12 commits ahead with no divergence.

The changed production surface is backup-focused (`SettingsViewModel`, `BackupSettingsUtil`, `FileUtil`) plus a three-line `HistoryDao` addition. The range does **not** modify:

- `ObserveSourcesItem.kt`;
- `ObserveSourcesDao.kt`;
- `ObserveSourcesRepository.kt`;
- `ObserveSourcesViewModel.kt`;
- `ObserveSourceWorker.kt`.

Therefore the prior ordinary-Observe generation/revocation root is not implicitly changed by the intervening range. Exact current source was still re-read at `90afaec1...` rather than relying on diff alone.

## Exact current production evidence

### 1. Ordinary Observe configuration still has no immutable generation/revision

`ObserveSourcesItem` remains keyed by auto-generated numeric Room `id` and contains mutable schedule/template/runtime fields, but no immutable ordinary Observe configuration generation/revision.

`ObserveSourcesRepository.observeTask()` still cancels prior work and enqueues `ObserveSourceWorker` with input data containing only the numeric source ID.

Thus a WorkManager request is not durably bound to the exact source configuration generation that authorized it.

### 2. Edit/STOP/delete still rely on asynchronous cancellation instead of durable execution-generation revocation

`ObserveSourcesViewModel.insertUpdate()` persists the edited row and then calls `repository.observeTask(item)`.

`stopObserving()` persists `STOPPED` and then requests WorkManager cancellation.

Delete requests cancellation and then deletes/revokes the source row.

These are stronger than purely process-local state, but no immutable generation is carried by every already-running ordinary worker. A previously started worker can therefore outlive a newer durable edit/STOP/delete decision until it reaches its next mutation boundary.

### 3. Worker source-row persistence is still stale full-row authority

`ObserveSourcesDao.update()` remains full-row `@Update(onConflict = REPLACE)` without an expected-generation/current-generation CAS predicate.

`ObserveSourceWorker.updateRunStatus()` mutates its retained `ObserveSourcesItem` and calls `repo.update(item)`.

`finishRunAndSchedule()` likewise mutates and persists the retained item before publishing the next recurring work request.

Concrete stale-state sequence remains reachable:

`E1 loads source/config`
→ `E2 edits or STOPs the same source and commits newer durable state`
→ cancellation of E1 is not an immutable semantic fence
→ `E1 updateRunStatus()` or `finishRunAndSchedule()` persists its retained full row
→ older configuration/runtime state can overwrite or revive newer source state.

### 4. Recurring successor publication is still generation-unfenced

`finishRunAndSchedule()` builds the successor `ObserveSourceWorker` with only `INPUT_SOURCE_ID` and publishes unique work `OBSERVE<sourceId>` using `ExistingWorkPolicy.REPLACE`.

There is no exact current-generation check immediately before this publication.

A surviving old worker can therefore republish recurring responsibility after a newer edit/STOP superseded the configuration that authorized that worker.

### 5. Positive Download publication remains a same-root consumer

The duplicate-admission closure remains preserved, but it governs final duplicate/admission identity rather than Observe producer generation.

Ordinary Observe execution still has no current-generation token to validate immediately before durable Download admission/publication. A stale worker can therefore construct Download intent from an obsolete retained source/template after newer durable Observe configuration has superseded it.

This remains a consumer of `BUG-OBSERVE-HANDOFF-01`, not a new root.

### 6. Destructive History/file mutation remains generation-unfenced

The prior F3 `SourceSnapshot` completeness/authority correction and History target/reference mutation protections remain conceptually separate and preserved.

They prove source-membership evidence and record/file identity at their respective boundaries. They do not establish that the **producer Observe configuration generation** performing the mutation is still current.

Because ordinary Observe has no immutable generation and no current-generation revalidation immediately before destructive mutation, a surviving stale worker can still reach a destructive History/file path using authority derived from an obsolete source configuration.

### 7. Special confirmed-retry fingerprint is still narrow

The confirmed retry/handoff path carries `INPUT_CONFIG_FINGERPRINT` and rejects an explicitly mismatched current fingerprint.

That is a valid specialized fence for that notification/handoff semantic decision, but ordinary Observe scheduling, ordinary worker execution, source-row persistence, destructive reconciliation, positive Download publication, and recurring successor publication do not inherit an equivalent immutable generation contract.

## Root/count reconciliation

- Keep `BUG-OBSERVE-HANDOFF-01` counted once as P0.
- Historical `BUG-OBSERVE-04` remains an alias/subcase of this root.
- Keep distinct from CLOSED F3 / `BUG-OBSERVE-01` source-membership completeness.
- Keep distinct from P2 `BUG-OBSERVE-02` worker-owned runtime-field overwrite semantics.
- Keep distinct from P2 `BUG-OBSERVE-03` recurring scheduler acceptance/recovery debt.
- No new root is added by this revalidation.

Canonical count delta: `0`.

## Stable correction boundary

The prior correction boundary remains implementation-ready and unchanged:

1. add a durable immutable ordinary Observe configuration generation/revision;
2. bind every ordinary request to its exact generation;
3. edit/reconfigure, STOP and delete durably supersede/revoke older generations;
4. fence worker source-row persistence by expected/current generation and status rather than stale full-row authority;
5. perform exact current-generation validation immediately before positive Download admission/publication, destructive History/file mutation, and recurring successor publication;
6. stale workers terminate/converge without restoring source state or publishing new work;
7. restart/process death preserves or reconstructs the same generation fence;
8. preserve F3 SourceSnapshot completeness, History target/reference revalidation, membership retry revocation, special confirmed-retry carrier semantics and duplicate-admission closure.

INDEPENDENT EXECUTION: NOT EXECUTED