# F11 ee7eea00 startup-path implication + current broad-manifest coverage expansion

Date: 2026-09-23

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local candidate:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Reported parent:
`87b05af9da2d51717594cebddcdaf32be93a4621`

Reported relation:
7 ahead / 0 behind

No push occurred.

Prior governing review:
`0437e6df9925b3040ce9c200de797e34cc58e929`

## Preserved exact-SHA evidence

Reported PASS on exact ee7eea00 before the stop:
- production Kotlin compile;
- androidTest Kotlin compile;
- focused BackupReset race 1/1;
- Cleanup acceptance 1/1;
- targeted Cleanup regressions 8/8;
- BackupReset 26/26;
- Cleanup 69/69;
- Scheduler transition 9/9;
- Scheduler external authority 12/12;
- Real WorkManager handoff 3/3.

Blocked gate:
`com.ireum.ytdl.work.WorkManagerHandoffProductionTest`

Result:
- FAIL BEFORE EXECUTION;
- 0 tests;
- instrumentation startup crash.

No tests were run during the evidence-forensics continuation.

## Startup event correlation

Reported evidence now uniquely correlates the failed attempt:
- app process start around 09:42:03;
- target package `com.ireum.ytdl`;
- PID 19469;
- tombstone timestamp 2026-09-22 09:42:50.697776900+0000;
- ActivityManager subsequently records process death/instrumentation crash;
- UTP exact class filter/package/runner remains valid.

The retained main-thread tombstone reaches:
- `com.ireum.ytdl.App.onCreate+0`;
- `Instrumentation.callApplicationOnCreate`;
- ART class loading/verification/initialization frames.

A separate startup coroutine stack reaches:
- `DBManager.getInstance`;
- `WorkManagerHandoffRecovery.database`;
- `WorkManagerHandoffRecovery.reconcile`;
- `App$onCreate$5.invokeSuspend`.

The final local candidate includes startup-order/recovery changes somewhere in the seven-commit range from remote `55112cc6...` to local `ee7eea00...`.

No retained evidence provides:
- initiating Java/Kotlin exception;
- exact lock owner/wait cycle;
- exact durable Restore/handoff state at crash;
- proof that the later SIGABRT stack is the initiating failure rather than crash-dump follow-on.

## Startup classification

Current classification:

**CANDIDATE PRODUCTION STARTUP PATH IMPLICATED / LOWER-LEVEL ROOT CAUSE UNRESOLVED.**

For workflow routing this remains category:

**B — CANDIDATE PRODUCTION STARTUP FAILURE.**

Important limitation:
- this establishes that the failed instrumentation attempt died while executing candidate production startup/recovery paths;
- it does NOT yet establish a specific new production defect, deadlock, lock cycle, or ee7eea00 seventh-commit regression;
- do not modify production until exact local source/range attribution narrows the cause.

Unchanged-tree semantic rerun remains NOT authorized.

Canonical defect-count delta: 0.

## Historical broad manifest

The historical 307-method manifest remains unrecoverable as a method-level provenance artifact.

The retained historical scope anchor does establish a whole-class 16-class filter.

On exact current candidate ee7eea00, those same 16 classes currently enumerate:
- 307 unique test identities;
- duplicates 0;
- inherited 0;
- ignored/disabled 0;
- unresolved runtime identities 0.

The equality `307` is an observed current property only, not recovery of historical method provenance.

## Fifth-wave coverage gap

The historical 16-class scope predates the fifth-wave scheduler-settings transition/restart consumer.

Current fifth-wave verification includes:

`com.ireum.ytdl.work.F11SchedulerSettingsTransitionProductionWiringTest`

Reported current identities:
9.

That class is not present in the retained historical 16-class filter.

Therefore:

`CURRENT_EQUIVALENT_MANIFEST_STATUS = BLOCKED_COVERAGE_GAP`

is correct for the 16-class / 307 candidate.

The 16-class set may remain a historical scope anchor, but it is not sufficient as the current fifth-wave closure manifest.

## Required current-manifest expansion audit

Before accepting a new current broad manifest:

1. audit the ENTIRE local seven-commit range:
   `55112cc6d5234e44b785fe655007a7fb58ac0553..ee7eea001462b77e88a201ed2f26c2385048d421`

2. enumerate every added/removed/renamed Android instrumentation test class and method relevant to F11;

3. identify which additions are materially part of the fifth-wave changed semantic surface;

4. preserve the retained historical 16 classes unchanged as the baseline scope anchor;

5. add every current materially required F11 test class/identity introduced after that historical scope was frozen;

6. do NOT add unrelated F12+ or unrelated regression classes;

7. do NOT force any target count.

If the only required addition is the reported 9-test scheduler-settings transition class, the expected current closure candidate becomes:

- 17 classes;
- 316 unique identities.

But **17 / 316 is not yet independently accepted by this checkpoint**. It must be proven by the exact full-range identity audit first.

## Next source-classification requirement

Because ee7eea00 is still local-only, do not infer exact source root cause from remote `55112cc6...`.

The next evidence-only source analysis must inspect exact local committed source and commit attribution without editing it.

At minimum:
- list all seven local commits and parents;
- identify which commit(s) changed `App.kt`;
- identify which commit(s) changed `WorkManagerHandoffRecovery.kt`, its scheduler-transition dependencies, DB initialization/recovery order, or startup gating;
- inspect the exact ee7eea00 startup call graph around the captured stacks;
- enumerate every potentially blocking/suspending wait/lock/database initialization in that path;
- prove or reject a lock/wait cycle;
- distinguish old remote behavior from fifth-wave candidate behavior;
- distinguish seventh cleanup-quiescence commit effects from earlier fifth-wave scheduler-transition commits.

No source edit.
No semantic rerun.
No push.

## Push consequence

Push remains blocked because:
1. WorkManagerHandoffProductionTest has no valid nonzero execution on ee7eea00;
2. candidate production startup root cause remains unresolved;
3. current broad closure manifest is not yet independently accepted.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
