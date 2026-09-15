# F10 expanded-scope recheck — `c4f630a8`

- Exact frozen implementation SHA: `c4f630a8699662ebc07d94caa006d70b9779091d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing Checklist v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference: `899328bc91e4008e39a658387396a0106c8666ec`
- Review mode: fixed-start-SHA review while implementation diff remains frozen from post-start inspection.

## Verdict

`BUG-CLEANUP-01` remains `P2 / OPEN`.

The previously recorded D1/D2 successor durability residual is real, but the prior implementation prompt is too narrow to close the full F10 production root. Fresh exact-source consumer/recovery review at the same frozen implementation SHA confirms three additional same-root blocker-relevant subcases.

Count delta: `0` — these are additional F10 subcases, not new semantic roots.

Canonical blocker state remains `P0 2 / P1 0 / P2 21` and the contiguous CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Preserved existing F10 residual

The already-recorded residual remains valid:

- exact successor D2 may exist only as process-local fallback when its first durable debt write fails;
- worker retry is finite;
- failed debt clear can leave stale predecessor D1 persisted;
- `ensureReplayOwnerLocked()` currently prefers persisted debt over fallback and `isSchedulingDebtCurrent()` rejects fallback when another persisted debt exists;
- stale D1 can therefore shadow D2 and later be republished.

The current review does not replace that scope; it expands it.

## Additional same-root subcase A — missing-generation startup first-write failure still strands enabled cadence

Production `reconcileSuspending()` handles an enabled cadence with missing generation by building one atomic bootstrap editor containing generation, anchor, and initial debt. If that first `commitAuthority()` returns `false`, the function immediately returns.

The existing production-wiring test explicitly models this result: cadence remains enabled, generation/debt remain absent, and no WorkManager request exists.

`App.onCreate()` invokes cleanup reconciliation as a one-shot `applicationScope.launch(Dispatchers.IO)` call. The failed Boolean commit does not throw and no process-local retry/replay owner is established.

Concrete impact:

- durable cleanup cadence can remain enabled;
- no generation/debt/current cleanup work exists;
- the current process has no recovery owner;
- convergence is deferred until some future process start or unrelated explicit transition.

This remains the F10 first-write/recovery root. The bootstrap write being atomic prevents partial new authority, but does not by itself satisfy the recurring-schedule convergence invariant after an ordinary non-exception persistence failure.

Required closure:

- a missing-generation bootstrap first-write failure must remain honestly uncommitted;
- but the running process must retain a bounded/lifecycle-owned responsibility to retry that exact bootstrap transition while the same enabled cadence is still current;
- disable/supersession must fence that retry;
- no partial generation/debt may be published.

## Additional same-root subcase B — Download Settings reset bypasses coordinator authority

`DownloadSettingsFragment` routes ordinary cleanup ListPreference changes through `CleanupSchedulePreferenceController`, but the screen-level Reset action calls generic `resetPreferences(...)` and recreates the Activity.

`BaseSettingsFragment.resetPreferences()` removes every visible preference key through `SharedPreferences.Editor.apply()` and then re-applies XML defaults. `downloading_preferences.xml` defines `cleanup_leftover_downloads` default as the empty/disabled value.

The internal cleanup generation/anchor/pending-debt keys are not visible Preference keys, so this Reset path does not transition them. It also does not invoke `CleanupScheduleCoordinator.configure(null)` and does not participate in `destructiveEffectMutex` ordering or WorkManager cancellation.

Concrete consequences include:

1. the UI-visible cadence can become disabled while the prior WorkManager request remains scheduled;
2. generation/debt sidecars can remain from the old cadence;
3. the Reset transition is not serialized against an already-admitted destructive cleanup effect, unlike the accepted coordinator-owned disable contract;
4. Reset can race an in-flight asynchronous settings transition outside the controller's latest-request ordering, so the final preference/schedule state can depend on write order rather than the last semantic user transition.

This is a production consumer-closure gap in the same F10 schedule-authority root. The Master Plan explicitly requires `disable cancels`; generic Reset is another production path that disables the cleanup preference and therefore must not bypass the schedule owner.

Required closure:

- cleanup preference Reset must use the same coordinator-owned durable authority transition as ordinary disable (or an equivalent serialized reset-specific API);
- do not directly publish the disabled cleanup preference independently of generation/debt/WorkManager authority;
- preserve asynchronous/non-main-thread-blocking UI behavior and honest final visible state;
- Activity recreation must occur only after the cleanup reset transition has a well-defined durable outcome, or otherwise preserve an explicit recovery carrier.

## Additional same-root subcase C — successor-only retry re-executes an already-successful destructive cleanup occurrence

`CleanUpLeftoverDownloads.doWork()` performs the destructive cleanup body before `scheduleSuccessor()` on every worker attempt.

If cleanup succeeds but successor handoff returns `false`, the worker returns `Result.retry()` while `runAttemptCount < MAX_ATTEMPTS - 1`. A WorkManager retry re-enters `doWork()` from the beginning. There is no durable per-occurrence marker that says the destructive cleanup effect for this exact occurrence already completed.

Therefore a scheduling/debt/query/enqueue failure after successful cleanup causes the same logical occurrence's destructive cleanup to execute again on retry.

The destructive body queries the current Cancelled/Error sets (`deleteCancelled()` / `deleteErrored()`) on each attempt. A Download that becomes Cancelled/Error after the first successful cleanup but before a scheduling retry can therefore be deleted by that retry, even though the retry was caused only by successor handoff failure rather than cleanup failure.

This is the v6 post-commit/success-sidecar problem within the same F10 root: successful destructive work is reopened by a later scheduling-sidecar failure.

Required closure:

- distinguish `cleanup effect needs retry` from `cleanup succeeded; successor ownership still needs convergence`;
- once an exact occurrence's destructive cleanup has completed successfully, successor handoff recovery must not rerun that destructive body merely because scheduler/debt publication is unresolved;
- preserve process-death-safe exact successor recovery and bounded/intentional cleanup-failure retry semantics;
- do not solve this by simply increasing or making `MAX_ATTEMPTS` infinite.

## Lower-confidence / non-counted observations

Two adjacent observations were reviewed but are not promoted here to additional blocker roots or count changes:

- initial-enqueue acceptance uses `ContextCompat.getMainExecutor(context)` and then performs synchronous SharedPreferences debt clearing; this is a UI-thread I/O/latency hardening concern, but the current review does not establish an ANR-level concrete impact comparable to the previously closed runBlocking settings regression;
- foreground-notification setup precedes the worker's main cleanup try/catch and `setForegroundAsync()` completion is not awaited; this remains a post-carrier hardening candidate, but this review did not establish an ordinary production failure chain strong enough to change F10 scope/count.

Do not authorize unrelated work from these candidates without further evidence.

## Scope consequence for the active implementation wave

The implementation agent is already marked active from start SHA `c4f630a8`; no post-start implementation diff was inspected or relied on in this review.

On explicit completion, independent review must re-check the full original F10 scope including:

1. existing D1/D2 durable-clear/fallback-shadow/process-death residual;
2. missing-generation bootstrap first-write failure current-process recovery;
3. screen-level Reset participating in coordinator authority/cancellation/mutex/latest-transition semantics;
4. no repeat destructive cleanup after a successful cleanup when only successor handoff is unresolved;
5. prior A/B/C/D fixes, calendar cadence, exact occurrence identity, UNKNOWN scheduler behavior, disable/supersession fencing, settings nonblocking behavior, and startup/restart reconciliation.

If the active implementation does not cover the three newly confirmed subcases, F10 remains OPEN even if the previously prompted D1/D2 residual is corrected.

F11 remains blocked until F10 independently closes.

INDEPENDENT EXECUTION: NOT EXECUTED