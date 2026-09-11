# BUG-DOWNLOAD-HANDOFF-01 — current-basis ordinary Download WorkManager handoff revalidation

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `a27bdfa65db21df91820b098588aa2b1c9fb7bd7` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P1 `BUG-KEYWORD-01`
- Moving implementation diff inspected or relied on: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

Exact `aa1616a2... -> 9c5191c3...` comparison shows the established ordinary handoff owner files `DownloadRepository`, `WorkManagerHandoffCarrier` / `WorkManagerHandoffRecovery`, `DownloadExecutionRecovery`, `App`, and `MainActivity` did not change in the F3 range. `ObserveSourceWorker` did change and remains a real ordinary queue producer, so its final current-basis composition was rechecked.

## Verdict

**NOT_CLEAN — existing P2 `BUG-DOWNLOAD-HANDOFF-01` remains OPEN at `9c5191c3...`.**

- Canonical blocker-count delta: **0**
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`
- `BUG-QUEUE-03` remains alias/subcase evidence of this same root, not a separate blocker.

## 1. Ordinary runnable Download still precedes exact WorkManager ownership

The established `DownloadRepository.startDownloadWorker()` implementation is unchanged across the basis advance.

For ordinary triggers it still constructs a DownloadWorker request and calls WorkManager enqueue/unique-work publication without a durable ordinary queue handoff carrier that binds the runnable Download transition to exact request acceptance.

The returned asynchronous WorkManager operation is not the durable semantic barrier for the already-persisted runnable rows, and producer responsibility is not retained through an exact accepted request identity.

Therefore the invariant remains broken:

`durable runnable/Queued Download`
→ `enqueue request issued`
→ **gap before accepted/recoverable WorkManager ownership**.

## 2. Current Observe positive publication remains a real consumer of that gap

F3 changed `ObserveSourceWorker` and introduced `startObserveDownloads(...)` as a narrow production/test seam.

In ordinary production the seam defaults to null and delegates to:

`downloadRepo.startDownloadWorker(queuedItems, context)`.

The Observe worker persists/updates queued Download rows before this downstream worker-start call. Thus the production path still has the same ordering:

`Download row durable as runnable`
→ `startDownloadWorker()` invoked`
→ no exact ordinary accepted-handoff carrier.

The new Observe production tests improve F3 semantic coverage but do not convert ordinary Download enqueue invocation into recoverable ownership.

## 3. Generic WorkManager handoff recovery still does not own ordinary Download queue generations

The generic durable handoff carrier/recovery code is unchanged in the intervening range.

Its established one-shot handoff kinds cover scheduler boundaries, HardSub scan, and special Observe retry semantics. There is still no ordinary Download queue request/generation kind used by `startDownloadWorker()`.

Therefore `WorkManagerHandoffRecovery.reconcile()` cannot reconstruct a lost ordinary queue handoff by exact runnable Download generation.

## 4. Download execution recovery still starts after queue admission

`DownloadExecutionRecovery` is unchanged in the intervening range. Its recovery domain remains already-established execution/finalization/native/publication debt, including Active/PostProcessing and exact execution-owned artifacts.

A Download stranded in ordinary `Queued` state before any DownloadWorker admission has no execution id/native generation/journal proving a worker ever accepted it.

That state therefore remains outside the established execution-recovery ownership model.

## 5. Cold-start deterministic repair remains absent

`App` / `MainActivity` startup code did not change in the basis advance with respect to this root.

Startup invokes existing recovery coordinators but does not unconditionally inventory all runnable ordinary Download rows and prove/repair exact WorkManager queue ownership.

A later unrelated queue producer may happen to kick a DownloadWorker, but that is not deterministic ownership recovery for the earlier durable transition.

## Concrete failure sequence remains

1. producer persists or reclassifies Download D as runnable/Queued;
2. producer invokes `startDownloadWorker()`;
3. process death or enqueue failure occurs before WorkManager has durably accepted the request;
4. D survives as runnable, but no ordinary queue handoff carrier records exact pending/accepted ownership;
5. execution recovery does not discover D because execution never began;
6. generic handoff recovery has no ordinary Download kind;
7. cold startup does not deterministically repair every such D;
8. D can remain queued until an unrelated later action happens to establish another DownloadWorker.

## Root reconciliation

Keep this as existing canonical P2 `BUG-DOWNLOAD-HANDOFF-01`, counted once.

It owns ordinary durable runnable-queue transition -> WorkManager accepted/recoverable ownership, including first admission and requeue/resume/retry producers.

`BUG-QUEUE-03` remains a subcase/alias where the same durable queue transition lacks accepted handoff ownership.

Keep distinct from scheduler START/END durable handoff, which has its own exact carrier protocol, and from Download execution/finalization recovery after a worker has already acquired execution authority.

## Correction boundary remains

1. pair every durable ordinary runnable-queue transition with an exact accepted request/generation carrier or equivalent durable recovery debt before producer responsibility ends;
2. observe WorkManager enqueue acceptance/failure rather than treating request issuance as a completed handoff;
3. or provide deterministic startup inventory/repair of every runnable row with exact idempotent worker-ownership semantics;
4. cover first queue admission plus requeue/resume/retry paths;
5. avoid duplicating already-live Download worker generations during repair;
6. keep scheduler-specific START/END handoff separate;
7. add production tests for enqueue failure, death before acceptance, cold-start repair, and duplicate recovery attempts.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
