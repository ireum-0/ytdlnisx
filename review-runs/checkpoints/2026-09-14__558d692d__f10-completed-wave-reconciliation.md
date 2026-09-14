# YTDLnisX independent review — F10 completed-wave reconciliation

- Exact completed implementation HEAD reviewed: `558d692dc95557080abe32eeedb51574b982aa01`
- Review base for this review-fix wave: `0968c7dda0bb0673ca055156f760e64e4fc6b446`
- F10 implementation checkpoint: `1ce807f8a9276ef8e35b62cc2a9587289fc7e76d`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Checklist v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Finding: F10 / `BUG-CLEANUP-01`
- Severity: P2
- Verdict: `NOT_CLEAN`
- Relation: same existing F10 semantic root; no new blocker count
- Count delta: `0`

## Reconciliation of in-progress checkpoint

`review/remediation` contained a pre-verdict checkpoint at `b8e36d4e2516f692467d950cd858ba15e1e2010d` reviewing frozen in-progress implementation SHA `1ce807f8...`. Under `REVIEW_PROTOCOL.md` Section 5 / checkpoint precedence, it was non-canonical for the completed wave. This completed-result reconciliation supersedes it for F10 disposition.

## Closed subcase from the latest review-fix

The prior `0968c7dd...` residual is closed.

Enabled `configure()` now computes the first occurrence and commits, in one successful `SharedPreferences.Editor.commit()`:

- cadence authority;
- generation;
- monthly anchor;
- initial pending generation;
- initial pending cadence;
- initial pending anchor;
- initial pending occurrence.

It then enqueues using that already-persisted occurrence with `persistDebt=false`. Thus an initial enabled authority publication cannot succeed while its initial recovery debt fails in a later independent preference commit.

Previously accepted F10 behavior remains source-level preserved:

- calendar daily/weekly/monthly computation, including monthly anchor restoration after short months;
- generation/cadence fencing;
- disable/supersession fencing;
- destructive cleanup effect serialized with authority transition by the same coroutine `Mutex`;
- asynchronous lifecycle-owned settings consumer, so Preference callbacks do not synchronously wait for destructive cleanup;
- initial synchronous/asynchronous enqueue failures retain durable debt and the configure/reconcile paths start a process-local replay owner;
- late acceptance cannot clear a newer generation's debt;
- worker retry does not publish duplicate successors.

## Residual 1 — startup generation bootstrap still has a split durable transition

`reconcileSuspending()` still supports a production-reachable state where cleanup cadence is enabled but `PREF_GENERATION` is missing. This is the natural legacy/upgrade/recovery shape for a preference that predates the remediation generation keys.

In that branch it:

1. commits a newly generated `PREF_GENERATION` and `PREF_MONTHLY_ANCHOR_DAY`;
2. discovers there is no current stable-chain work;
3. cancels tagged cleanup work;
4. only then calls `enqueueNextLocked(... persistDebt = true)`;
5. `enqueueNextLocked()` performs a separate `SharedPreferences.commit()` for pending scheduling debt.

If step 5 returns `false`, `enqueueNextLocked()` returns `null`; no request is enqueued and `ensureReplayOwnerLocked()` is not reached. The enabled cadence/generation remain durable, but there is no durable debt for that generation and no process-local replay owner. The running process can therefore remain enabled but unscheduled until another reconciliation event/process start.

This is the same F10 persistence/recovery-convergence root, not a new finding.

Required correction: when reconcile must create missing generation authority for an enabled cadence, publish the generation/anchor and a matching initial debt tuple with the same fail-safe durability discipline as `configure()`, or otherwise prove an equivalent no-gap recovery protocol. Do not use best-effort rollback as the correctness boundary.

## Residual 2 — successor failure can exhaust worker retry without starting replay ownership

`enqueueNextLocked()` correctly leaves successor debt durable when enqueue throws or asynchronous acceptance fails. However `scheduleSuccessor()` does not call `ensureReplayOwnerLocked()` for that debt.

The worker reacts to `scheduleSuccessor()==false` by returning `Result.retry()` while retry budget remains. On the final attempt it returns `Result.failure()`.

Therefore, if successor enqueue/acceptance remains unavailable through the worker's retry budget and later becomes available while the app process stays alive:

- the current occurrence becomes terminal failed;
- the successor debt remains durable;
- no successor work exists;
- no process-local replay owner is running;
- the schedule does not converge until a later startup/manual reconciliation event.

That violates the durable recurring-schedule / recoverable scheduling responsibility invariant. It is another consumer/subcase of the existing F10 root, count delta `0`.

Required correction: ensure successor scheduling debt has a durable and live recovery owner after enqueue/acceptance failure, including after the occurrence exhausts WorkManager retry. Any replay must avoid treating the still-running current occurrence as proof that the successor debt is satisfied; it must converge the debt once that current occurrence ceases to be an unfinished owner.

## F11 consequence

F11 / `BUG-BACKUP-03` remains blocked because F10 is not independently closed. F11 must not start yet.

## Canonical blocker consequence

Before this review: `P0 2 / P1 0 / P2 20`.

F10 delta: `0`.

After F10 reconciliation: `P0 2 / P1 0 / P2 20`.

Contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

INDEPENDENT EXECUTION: NOT EXECUTED
