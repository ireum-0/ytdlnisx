# BUG-OBSERVE-04 — alias reconciliation with canonical BUG-OBSERVE-HANDOFF-01

Date: 2026-09-12

## Exact review basis

- Independently CLEAN implementation basis: `36b43464b8106d90d672ba94718ccee58f38974f`
- Broader-registry source: `review/remediation:TASKS.md`, historical P2 `BUG-OBSERVE-04`
- Canonical existing root: P0 `BUG-OBSERVE-HANDOFF-01`
- Prior canonical P0 checkpoint: `4ae8e764da1f8456de9184e6ca674bad03a31821`
- Moving overnight implementation/candidate diffs inspected or relied on: **NO**

## Verdict

**Do not promote or count `BUG-OBSERVE-04` separately. It is an alias/subcase of existing canonical P0 `BUG-OBSERVE-HANDOFF-01`.**

Canonical count delta: **0**.

Resulting canonical count remains **P0 2 / P1 2 / P2 29** after the separate `BUG-OBSERVE-03` promotion.

CLEAN basis remains `36b43464b8106d90d672ba94718ccee58f38974f`.

## Exact current-source overlap

At exact `36b43464...`, `ObserveSourceWorker` still loads one mutable `ObserveSourcesItem` for the run and persists worker progress through `ObserveSourcesRepository.update(item)`.

For ACTIVE sources that repository path reaches a full-row Room update. No current ordinary Observe generation/revision token is carried by the normal `ObserveSourceWorker` request; the ordinary request carries only the numeric source id.

`finishRunAndSchedule()` also persists the retained item and then publishes another ordinary `ObserveSourceWorker` under the same `OBSERVE<sourceId>` unique-work namespace without proving that the worker's configuration generation remains current.

Therefore the historical `BUG-OBSERVE-04` sequence remains technically reachable:

`worker E1 loads source snapshot`
→ `user edit E2 commits newer configuration`
→ old worker E1 survives asynchronous cancellation`
→ E1 writes its stale full-row source snapshot`
→ E1 can also enqueue/replace a successor under the shared unique-work name.

## Why this is not a new semantic root

Canonical P0 `BUG-OBSERVE-HANDOFF-01` already explicitly owns this exact authority failure:

- ordinary Observe configuration generation identity;
- edit/reconfigure supersession;
- STOP/delete revocation;
- stale source-row write prevention;
- generation-aware/CAS worker persistence;
- validation before correctness-relevant mutation;
- recurring successor publication authority;
- process-death reconstruction of the same generation fence.

Its recorded concrete P0 chain already includes an older worker surviving a durable newer edit/STOP, writing stale full-row state, performing effects under revoked authority, and republishing a successor.

The broader-registry `BUG-OBSERVE-04` required result—monotonic source revision/generation, worker requests bound to that revision, stale-write rejection, and stale-successor prevention—is therefore the same correction boundary, not an independent durable authority root.

## Relation to separately promoted BUG-OBSERVE-03

Keep `BUG-OBSERVE-03` distinct.

`BUG-OBSERVE-HANDOFF-01` / historical `BUG-OBSERVE-04` asks **which generation is authorized**.

`BUG-OBSERVE-03` asks whether the **current valid generation's durable ACTIVE intent actually acquires and retains a WorkManager carrier**, including enqueue acceptance failure/process-death/startup recovery.

A system may have perfect generation fencing and still lose the only current carrier; conversely, it may have perfect carrier recovery while allowing an obsolete generation to overwrite the current one. Therefore those two roots remain separate.

## Count reconciliation

- historical P2 `BUG-OBSERVE-04`: alias/subcase, no additional count;
- canonical P0 `BUG-OBSERVE-HANDOFF-01`: remains OPEN and counted once;
- promoted P2 `BUG-OBSERVE-03`: remains a separate newly counted liveness/handoff root.

INDEPENDENT EXECUTION: NOT EXECUTED
