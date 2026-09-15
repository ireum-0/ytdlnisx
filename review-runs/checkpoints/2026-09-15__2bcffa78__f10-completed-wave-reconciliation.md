# F10 completed-wave reconciliation — 2026-09-15 — `2bcffa78`

## Authority

- Implementation branch: `checkpoint/pre-baseline-review`
- Review base: `c4f630a8699662ebc07d94caa006d70b9779091d`
- Reviewed completed head: `2bcffa78116aa086c645f029f8abeaef0d51b659`
- Verified chain: exactly one straight commit, `c4f630a8 -> 2bcffa78`, merge base exactly `c4f630a8`, ahead 1 / behind 0.
- Commit: `fix: preserve exact cleanup successor ownership`
- Defect-ID: `BUG-CLEANUP-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: Review Checklist v6.
- Prior expanded-scope F10 checkpoint: `eba6bc093eec63b989572d5a22d4c7dab1d1dbb7`.

## Verdict

**F10 / `BUG-CLEANUP-01`: NOT_CLEAN — existing P2 root remains OPEN.**

The commit source-level closes the previously prompted D1/D2 ownership subcases, but it does not close all same-root production paths established before completion.

## D1/D2 durability/ownership — source-level fixed

The final source introduces a durable active-occurrence slot in addition to pending scheduling debt.

Confirmed source properties:

- accepted pending debt is atomically promoted to an active exact occurrence;
- failed promotion does not stop recovery ownership;
- an accepted active occurrence remains durable until its exact successor is durably published;
- D1 -> D2 publication removes old pending/active state and publishes exact D2 in one SharedPreferences commit;
- a failed D1 -> D2 commit leaves D1 durable and a fallback replay owner may represent only the exact immediate D2;
- stale D1 late callbacks cannot overwrite a newer persisted occurrence because `completedOccurrenceAt` must match the durable predecessor;
- restart reconciliation can derive exact D2 from durable active D1 rather than recomputing from current wall-clock time;
- UNKNOWN WorkManager discovery remains non-authoritative and does not directly authorize cancel/replace.

Disposition of the previously prompted volatile-D2 / failed-clear / D1-shadowing / D1-resurrection frontier: **source-level CLOSED at `2bcffa78`**, subject to the still-open broader F10 root and unexecuted instrumentation.

## Same-root residual A — missing-generation bootstrap first-write failure remains

Production `reconcileSuspending()` still handles enabled cadence + missing generation by constructing one atomic bootstrap editor containing generation, anchor, and initial pending debt.

If `commitAuthority(bootstrapEditor)` returns `false`, the function immediately returns.

Consequences in the running process:

- persisted cadence remains enabled;
- no generation is published;
- no scheduling debt is published;
- no WorkManager occurrence is published;
- no process-local replay/retry owner is started.

`App.onCreate()` launches cleanup reconciliation as a one-shot IO coroutine. Boolean commit failure does not throw, so the outer startup catch does not create a retry owner. The enabled schedule can remain unscheduled until another process start/manual transition.

This is the same F10 durable-recurring-schedule convergence root.

## Same-root residual B — Download Settings Reset still bypasses coordinator authority

Ordinary cleanup ListPreference changes use `CleanupSchedulePreferenceController` and the suspend coordinator transition.

The screen-level Reset path still calls generic `BaseSettingsFragment.resetPreferences()` directly. That helper removes visible preference keys using `SharedPreferences.apply()` and re-applies XML defaults. The cleanup preference default is disabled/empty.

The reset path does not:

- call `CleanupScheduleCoordinator.configure()`;
- acquire `destructiveEffectMutex`;
- atomically retire generation/pending/active debt;
- synchronously establish durable disable authority under the coordinator protocol;
- cancel the stable cleanup WorkManager chain through the coordinator.

Concrete race: reset can publish the cleanup preference as disabled while an already-admitted destructive cleanup effect still owns `destructiveEffectMutex`, because reset does not participate in that mutex. The destructive body can therefore continue after the user-visible disable/reset transition. Activity recreation is not Application process restart and does not itself invoke startup reconciliation.

This is a production consumer-closure failure of the same F10 disable/authority root.

## Same-root residual C — successor-only handoff retry re-executes successful destructive cleanup

`CleanUpLeftoverDownloads.doWork()` still executes `withCurrentDestructiveEffect { ... deleteCancelled(); deleteErrored(); delete DOWNLOAD_TEMP ... }` before calling `scheduleSuccessor()` on every WorkManager attempt.

If cleanup succeeds but successor publication/acceptance returns `false`, the worker still returns `Result.retry()` while retry budget remains.

On the retry, there is no durable occurrence-level marker saying the destructive effect for this exact occurrence already succeeded, so the worker enters the destructive cleanup body again before retrying successor handoff.

Because `deleteCancelled()` and `deleteErrored()` re-read the current sets, rows that become Cancelled/Error after the first successful cleanup can be deleted by a retry caused only by scheduling/debt handoff failure. This is not an intentional cleanup-failure retry; it is re-execution of a successful authoritative destructive effect because a later scheduling sidecar failed.

The new active-occurrence marker preserves scheduling ownership but is not consumed by the worker as a durable destructive-effect-completed barrier.

This remains the same F10 P2 root.

## Preserved behavior

- Calendar DAILY/WEEKLY/MONTHLY calculation remains present; monthly remains calendar-month based.
- Ordinary settings transition remains asynchronous/non-main-thread-blocking.
- Generation/cadence fencing and the destructive-effect mutex remain present for coordinator-owned transitions.
- Disable/supersession through `configure()` still atomically retires pending/active debt with the new authority before asynchronous cancellation.
- No F20 source was changed; prior F20 exact provider-authority / opaque document-ID source semantics remain preserved.
- No F17/F18 implementation surface was changed by this F10-only commit.

## Execution evidence

Implementation-agent evidence reports:

- `git diff --check`: PASS;
- committed-range diff check: PASS;
- `:app:compileDebugKotlin -x lint`: PASS;
- `:app:compileDebugAndroidTestKotlin`: PASS;
- F10 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE;
- F20 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE.

GitHub has no combined status checks for exact SHA `2bcffa78...`. The reviewer did not independently execute builds or instrumentation.

## Count / basis

- F10 remains one existing P2 root; count delta: `0`.
- Canonical blockers remain **P0 2 / P1 0 / P2 21**.
- Overall: `NOT_CLEAN`.
- Contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- F11 / `BUG-BACKUP-03` remains blocked and must not begin.

INDEPENDENT EXECUTION: NOT EXECUTED