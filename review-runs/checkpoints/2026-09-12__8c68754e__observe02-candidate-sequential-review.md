# Task 003 / BUG-OBSERVE-02 — sequential independent candidate review

Date: 2026-09-12

## Exact reviewed state

- Current independently CLEAN canonical basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Candidate branch: `candidate/overnight-20260912-observe02`
- Candidate result SHA: `8c68754e703476811f6c70157350b901a1471e6c`
- Candidate required/base SHA: `36b43464b8106d90d672ba94718ccee58f38974f`
- Governing promotion checkpoint: `4c27b0588b632086085165882325e7961f675cd2`

Exact ancestry: candidate is one additive commit ahead of `36b43464...`, zero behind. Candidate vs canonical `9edd3e23...` is diverged by one commit each with merge base exactly `36b43464...`; no candidate integration into canonical history was found.

## Verdict

**CANDIDATE NOT_CLEAN.**

The candidate correctly separates ordinary form-owned configuration fields from worker-owned runtime/progress fields at the Room write boundary, but its production UI/ViewModel rewiring drops required existing edit side effects. It therefore cannot be replayed as-is onto canonical.

Canonical count delta: `0`. `BUG-OBSERVE-02` remains OPEN and already counted. Canonical count remains **P0 2 / P1 1 / P2 34**. CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## Correct portion

The new `ObserveSourcesDao.updateConfiguration()` transaction reloads the current row and preserves worker-owned runtime/lifecycle state including `status`, `runCount`, `runHistory`, `runInProgress`, `currentRunStatus`, and membership/progress lists except when the explicit processed-links reset action owns their reset. It also preserves managed-purpose identity. This directly addresses the original stale form-snapshot overwrite root.

The explicit Start path remains on the existing full lifecycle update, so intentional `runCount=0` is not accidentally removed.

## Blocking production regression

Before the candidate, an existing source edit enters `ObserveSourcesViewModel.insertUpdate(item)`. That existing branch performs all of the following:

1. `notificationUtil.cancelObserveRetryConfirmation(item.id)`;
2. persists the edited source;
3. calls `repository.observeTask(item)`, which cancels/replaces the existing unique/tagged ordinary Observe work so the edited cadence/source/template receives a replacement schedule;
4. reconciles automatic-keyword observation coverage.

The candidate changes `ObserveSourcesBottomSheetDialog` so every existing-row Save calls the new `ObserveSourcesViewModel.updateConfiguration(...)` instead of `insertUpdate(...)`.

The new `updateConfiguration(...)` only performs the configuration-only DB write plus automatic-keyword reconciliation. It does **not** cancel the existing retry-confirmation notification generation and does **not** call `repository.observeTask(...)` or otherwise replace the existing ordinary observation schedule.

Therefore an ordinary edit can durably change URL/cadence/template/configuration while the previously scheduled WorkManager carrier remains scheduled according to the pre-edit timing/configuration lifecycle. The edit contract regresses even though runtime columns are now preserved.

This is production-reachable, not a helper-only concern: the BottomSheet Save path is explicitly rewired away from the only existing edit method that performed those side effects.

This residual should not be misclassified as the already-counted P0 `BUG-OBSERVE-HANDOFF-01`. That P0 owns stale worker generation/revocation authority. Here the narrower candidate repair itself omits existing edit-side rescheduling/confirmation invalidation while attempting to fix P2 `BUG-OBSERVE-02`. The candidate cannot be accepted until ordinary edit semantics are preserved without restoring full-row runtime ownership.

## Required review-fix boundary

A corrected canonical implementation should keep the configuration-only DB ownership boundary, but the existing-source Save workflow must also preserve the non-DB edit side effects that `insertUpdate()` previously supplied:

- cancel obsolete retry-confirmation UI/generation state as appropriate;
- schedule/cancel-replace ordinary Observe work for the newly persisted configuration using the established edit semantics;
- preserve automatic-keyword coverage reconciliation;
- do not reintroduce a full-row write of worker-owned runtime fields;
- preserve explicit Start/Stop lifecycle ownership;
- do not attempt to solve the separate P0 generation/revocation root inside this P2 fix unless strictly necessary.

Add production-wiring coverage proving that a cadence/configuration edit both preserves runtime fields and invokes the replacement observation scheduling path, while explicit processed-link reset and Start behavior remain correct.

No automatic merge/cherry-pick/rebase/transplant is authorized.

INDEPENDENT EXECUTION: NOT EXECUTED