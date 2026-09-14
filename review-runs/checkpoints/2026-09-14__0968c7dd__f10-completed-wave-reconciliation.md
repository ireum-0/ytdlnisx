# F10 completed-wave reconciliation at 0968c7dd

- Reviewed implementation HEAD: `0968c7dda0bb0673ca055156f760e64e4fc6b446`
- Review range: cumulative F10 scope through the completed review-fix wave from `09058d574d19d211cd0db36cbf47726bb8fa7dc5`
- Finding: F10 / `BUG-CLEANUP-01`
- Verdict: `NOT_CLEAN`
- Severity: P2
- Count delta: `0`
- Canonical blocker consequence: F10 remains one existing P2 root.
- CLEAN-basis consequence: no advance; contiguous CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## What is now closed

The review-fix removes the prior fix-induced main-thread blocking path. `CleanupScheduleCoordinator.configure()` and `reconcile()` are suspend functions; `DownloadSettingsFragment` hands preference requests to `CleanupSchedulePreferenceController`, whose callback returns `false` immediately and performs configuration from lifecycle-owned coroutine work on `Dispatchers.IO`. The generation-owned destructive-effect mutex remains the ordering boundary between authority transitions and an admitted destructive cleanup effect.

Rapid requests cancel the prior transition job and use a monotonic request id before applying the visible persisted value. If an older transition is already inside the non-suspending mutex body, it can finish first, but the newer transition then serializes after it and becomes final authority; if the older transition is still waiting for the mutex, cancellation prevents its admission. The production startup caller invokes reconciliation from `App.applicationScope.launch(Dispatchers.IO)`.

Thus the previously reviewed stale destructive-effect race and the review-induced synchronous UI wait are source-level closed at this HEAD.

## Residual: authority can commit without any schedule/debt owner

The full original F10 persistence/convergence scope still has a concrete failure sequence:

1. `CleanupScheduleCoordinator.configure()` durably commits the new cadence/generation/anchor authority and retires the previous generation's pending debt in one `SharedPreferences.commit()`.
2. For an enabled cadence it then calls `enqueueNextLocked()`.
3. `enqueueNextLocked()` performs a second `SharedPreferences.commit()` for the **new** pending generation/cadence/anchor/occurrence scheduling-debt tuple.
4. If this second commit returns `false`, `enqueueNextLocked()` returns `null` before any WorkManager enqueue.
5. Because the handle is `null`, `configure()` does not call `observeAcceptance()` or `ensureReplayOwnerLocked()` and returns `false`.
6. The first authority commit nevertheless remains durable. `CleanupSchedulePreferenceController` does not use the Boolean result to roll back authority; it reads the coordinator-owned preference after the transition and applies that persisted cadence to the `ListPreference`.
7. Result: the app can durably and visibly say DAILY/WEEKLY/MONTHLY while no current cleanup work and no durable pending scheduling-debt tuple/process-local replay owner exist. `App` startup reconciliation can repair this only on a later process startup; an already-running process can remain unscheduled indefinitely.

This is not a new semantic root. It is another persistence/convergence subcase of the already-open `BUG-CLEANUP-01` contract that preference changes must converge to exactly one correct cleanup schedule and remain recoverable across persistence failures.

## Required next correction

Make the enabled authority transition fail-safe across the authority-commit -> new-debt-publication boundary. After new enabled authority becomes durable, every failure window must still leave either:

- an accepted/current WorkManager occurrence, or
- durable discoverable scheduling debt with an active/restart recovery owner,

without falsely presenting a durable enabled cadence that has no path to in-process convergence. Preserve the current destructive-effect mutex ordering, asynchronous UI path, latest-request semantics, calendar cadence behavior, successor fencing, retry behavior, stale late-callback fencing, and startup reconciliation.

Add a deterministic seam/test for failure of the **new scheduling-debt persistence step after authority commit**, distinct from authority-commit failure and WorkManager enqueue failure.

## Preserved closures

No evidence in this F10 change reopens F17/F18 or prior F4/F5/F6/F7/F8/F9/F15/F16 closures.

INDEPENDENT EXECUTION: NOT EXECUTED