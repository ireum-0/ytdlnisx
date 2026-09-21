# F11 fifth-wave Cleanup bootstrap failure — controlled discriminator

Date: 2026-09-22

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local fifth-wave candidate:
`6df11bccefc0e3e75ef8a3a7c4d6ef3dba6c3ac3`

Reported candidate parent chain:
- `c3ff9bc93ee93ba66fac9e194314da4058bd271c`
- `6df11bccefc0e3e75ef8a3a7c4d6ef3dba6c3ac3`

Remote remained unchanged and no push occurred.

Governing F11 review:
`711370908d232ab2334b7c7d8df9797edd636a0f`

## Reported exact-SHA verification evidence

On unchanged local candidate `6df11bcc...`, implementation-agent evidence reports PASS for:

- scheduler transition production wiring: 9/9;
- scheduler external authority: 12/12;
- real WorkManager handoff: 3/3;
- WorkManager handoff: 13/13;
- Restore alarm fallback: 2/2;
- third remediation / R1 regression: 9/9;
- preference admission: 6/6;
- Backup Reset: 26/26;
- Backup Paused: 4/4;
- Backup Preference: 4/4;
- Backup Settings: 8/8;
- Automatic-keyword: 8/8;
- History undo: 10/10;
- LowQuality persistence: 125/125.

Cleanup full class then produced a valid execution failure:
- discovered 69;
- executed 69;
- skipped 0;
- failed 1;
- method: `bootstrapReplayIsFencedByDisableAndSupersession`;
- exception: `java.lang.IllegalArgumentException: Required value was null`;
- first reported frame: `seedInitializedScheduleWithMissingGeneration(...:3783)`.

Later frozen-27 reconciliation and broader gates were correctly not executed.

## Exact remote test/source classification

Do NOT inspect the local fifth-wave candidate diff while it remains unpushed.

At authoritative remote `55112cc6...`, the Cleanup production-wiring test blob is:

`b1a3465689494e0a4648003a5fe12a212a6f8b93`

The failing method begins by invoking:

`seedInitializedScheduleWithMissingGeneration()`

before installing its intentional bootstrap authority-failure seam.

The helper:
1. sets long initial/replay delays;
2. writes legacy DAILY cadence;
3. calls production `CleanupScheduleCoordinator.reconcile(context)`;
4. immediately executes:
   `requireNotNull(preferences.getString("cleanup_leftover_downloads_generation", null))`;
5. only after obtaining that generation does it wait for pending→active acceptance, drain the main executor, cancel/drain WorkManager, and strip the generation/debt to construct the intended missing-generation fixture.

Therefore the reported exception occurs while constructing the fixture precondition, before the test reaches the semantic disable/supersession behavior it is meant to prove.

The exact production coordinator remains able to:
- migrate the legacy cleanup cadence into the dedicated critical store;
- atomically bootstrap generation/debt;
- retain a replay owner if a bootstrap/migration durability transition cannot be published.

The reported exception alone does not establish that these production semantics failed.

## Historical evidence

F10 / BUG-CLEANUP-01 was canonically closed at:

`3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`

Canonical closure checkpoint:
`review-runs/checkpoints/2026-09-19T014000Z__3072ce86__f10-canonical-closure.md`

That closure records:
- full Cleanup production-wiring class 69/69 PASS on exact final F10 SHA;
- no remaining source or harness blocker at closure;
- missing-generation fixture specifically strengthened for acceptance/main-executor/WorkManager quiescence.

In the later F11 verification history, this same current Cleanup class also has preserved evidence:
- original full-class failure;
- controlled exact-method repeat PASS 1/1;
- controlled full-class repeat PASS 69/69.

The user reports the present failing method is the same method associated with that preserved Cleanup failure.

Thus the method is already known to cross async/order-sensitive fixture state and has prior valid pass evidence after a controlled unchanged-tree discriminator.

## Current classification

**VALID EXECUTED FAILURE REQUIRING ONE CONTROLLED UNCHANGED-TREE ORDER/STATE DISCRIMINATOR.**

More specifically:
- production Cleanup semantic regression: NOT ESTABLISHED;
- fifth-wave F11-R2 regression: NOT ESTABLISHED;
- inherited frozen failure: NO;
- PASS: NO;
- harness defect conclusively established: NOT YET;
- infrastructure failure conclusively established: NOT YET;
- canonical blocker-count delta: 0.

The current failure must remain preserved permanently.

## Authorized discriminator

No source/test edit is authorized.

On exact unchanged local SHA:

`6df11bccefc0e3e75ef8a3a7c4d6ef3dba6c3ac3`

run only:

`CleanupScheduleCoordinatorProductionWiringTest.bootstrapReplayIsFencedByDisableAndSupersession`

exactly once.

Before/around the run preserve, where observable without source edits:
- dedicated critical-store initialized/version state;
- dedicated cadence;
- generation;
- pending/active generation;
- authority/effect commit test seams;
- durability-fence/replay-owner reset state;
- unfinished cleanup WorkInfo;
- whether failure again occurs before the intentional `authorityCommitOverrideForTesting = { false }` seam is installed.

### If the isolated method FAILs again

STOP.

Do not rerun again.

Report:
- exact exception/frame;
- dedicated critical-store version/cadence/generation/pending/active state at failure if available;
- WorkManager state;
- whether any commit-failure seam was active;
- whether replay/bootstrap ownership existed;
- teardown/setup diagnostics.

No production or test correction is authorized by this checkpoint.

A repeated failure requires a new independent classification before any edit.

### If the isolated method PASSes

Run the complete:

`CleanupScheduleCoordinatorProductionWiringTest`

exactly once on the same unchanged SHA.

### If the full class FAILs

STOP on the first valid failure.

Preserve exact method/order/state diagnostics.

Do not automatically isolate/rerun it.

### If the full class PASSes 69/69

Classify the current 1/69 failure as controlled order/state-dependent nondeterminism in this verification wave.

Do not erase the original failure.

Then continue the still-unexecuted fifth-wave gates on the SAME exact SHA:
- frozen-27 exact identity reconciliation;
- established broad F11 union or exact identity-equivalent partitioned execution;
- all remaining exact-final-SHA/pre-push gates required by the fifth-wave prompt.

No source/test/config change may occur.

## Push consequence

Only if:
- isolated discriminator PASS;
- full Cleanup class PASS 69/69;
- every later mandatory exact-SHA gate completes validly;
- no new semantic failure exists;
- live refs still satisfy the fifth-wave pre-push contract;

may the already-existing exact two-commit candidate be normally pushed.

No amend.
No rebase.
No extra verification commit.
No force-push.
No history rewrite.

Even after successful push:
- do not claim F11 CLEAN;
- independent exact-source re-review of the pushed fifth-wave range remains required.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
