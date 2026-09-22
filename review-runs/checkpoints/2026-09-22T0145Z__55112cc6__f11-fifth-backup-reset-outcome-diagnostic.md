# F11 fifth-wave BackupReset active-worker failure — outcome observability diagnostic required

Date: 2026-09-22

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local fifth-wave candidate:
`0172cfe16ba97915b5d3e05cb4274877c861ba53`

Reported parent:
`08aa2656715c19f14afda244c5879a66c398d8ba`

Reported relation:
4 ahead / 0 behind

No push occurred.

Governing review before this classification:
`1a67c8ffefd1b7e318309eef1d20b56a79a3fdee`

## Reported exact-SHA evidence

On exact candidate `0172cfe...`:

PASS:
- corrected preference race method 1/1;
- full preference-admission class 6/6;
- scheduler transition 9/9;
- scheduler external authority 12/12;
- real WorkManager handoff 3/3;
- WorkManager handoff 13/13;
- Restore alarm fallback 2/2;
- third-remediation regression 9/9.

First valid failure:

`BackupResetTransactionProductionWiringTest`
- 26 discovered;
- 26 executed;
- 0 skipped;
- 25 passed;
- 1 failed.

Failing method:

`activeConflictingWorkerMustQuiesceBeforeResetApplies`

Failure point:

`assertTrue(reset.await() is RestoreOutcome.Completed)`

Reported logcat evidence:
- cleanup WorkManager id `e93fa2d6-d756-40ff-9f8f-63f387077a10`;
- generation `325307de-c781-4cf7-9137-182338520ca1`;
- WorkInfo reached `CANCELLED`.

No rerun occurred.
Later gates were correctly short-circuited.

## Authoritative remote test contract

Do NOT inspect the unpushed local candidate diff.

At authoritative remote `55112cc6...`, the exact BackupReset method:

1. creates existing history state;
2. configures a real cleanup WorkManager occurrence with zero initial delay;
3. blocks the cleanup worker at `CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting`;
4. begins Restore concurrently;
5. waits for Restore ownership publication;
6. verifies old data is still authoritative while Restore owns the gate;
7. verifies a new ordinary Download admission is rejected;
8. releases the blocked cleanup worker;
9. awaits the Restore result;
10. requires `RestoreOutcome.Completed`;
11. verifies imported history is authoritative.

This is intentionally a production-boundary test of Restore quiescence against a real asynchronous WorkManager owner.

## Current observability gap

The exact test currently performs:

`assertTrue(reset.await() is RestoreOutcome.Completed)`

and discards the actual non-Completed outcome.

That loses the exact distinction among:

- `RestoreOutcome.RejectedBeforeOwnership(reason)`;
- `RestoreOutcome.RecoveryPending(operationId, phase, reason)`;
- `RestoreOutcome.CommittedReconciliationPending(operationId, reason)`.

Production `RestoreTransactionCoordinator.drive()` has materially different meaning for these outcomes.

On an exception:
- before DATA_COMMITTED/RECONCILING it returns `RecoveryPending` with exact phase and reason;
- at DATA_COMMITTED/RECONCILING it returns `CommittedReconciliationPending` with exact reason;
- `RestoreOperationStore.recordError` persists the failure reason into the active journal when possible.

Therefore the current assertion is insufficient to tell whether the failure came from:
- pre-commit WorkManager quiescence;
- file/publication/application phase;
- post-commit reconciliation;
- CleanupScheduleCoordinator reconciliation after settings Restore;
- another recoverable phase.

The reported fact that the original cleanup WorkInfo reached `CANCELLED` does not by itself prove Restore quiescence or later reconciliation succeeded.

## Historical evidence

This same method has an earlier preserved valid failure in the fourth-wave verification history at checkpoint:

`review-runs/checkpoints/2026-09-21T1410Z__70e5e016__backup-reset-active-worker-controlled-discriminator.md`

That earlier checkpoint authorized an unchanged-tree rerun.

That rerun authorization is NOT valid under current `REVIEW_PROTOCOL §16.2`, which now forbids rerunning a valid failed semantic test on an unchanged tree merely to seek green.

Historical later PASS evidence remains evidence only and does not erase the current exact-SHA failure.

Also preserved:
- BackupReset 26/26 PASS was reported on earlier fifth-wave candidate `6df11bcc...`;
- from `08aa2656...` to `0172cfe...`, the reported new commit is test-only in the preference-admission class;
- this does not establish the current BackupReset failure as nondeterministic, because the current valid failure must still be classified from its own evidence.

## Current classification

**VALID EXECUTED FAILURE / PRODUCTION ATTRIBUTION UNRESOLVED / OUTCOME-OBSERVABILITY GAP.**

Specifically:
- F11-R2 production regression: NOT ESTABLISHED;
- BackupReset production regression: NOT ESTABLISHED;
- harness defect conclusively established: NOT YET;
- infrastructure failure conclusively established: NOT YET;
- PASS: NO;
- unchanged-tree rerun: NOT AUTHORIZED;
- canonical blocker-count delta: 0.

The current failure must remain preserved permanently.

## Authorized diagnostic correction

Authorize exactly ONE forward TEST-ONLY diagnostic commit on top of reported local candidate:

`0172cfe16ba97915b5d3e05cb4274877c861ba53`

Authorized file ONLY:

`app/src/androidTest/java/com/ireum/ytdl/database/BackupResetTransactionProductionWiringTest.kt`

No production source change is authorized.

The correction must preserve the semantic test and add observability only.

### Required outcome capture

Replace the opaque boolean-style final outcome check with explicit capture:

- await the Restore once;
- preserve the exact outcome subtype;
- preserve all outcome fields;
- still require the outcome to be `RestoreOutcome.Completed`.

Do not accept any pending/rejected subtype.

### Required durable Restore diagnostics on non-Completed outcome

Before failing the assertion, preserve where available:

- `RestoreGate.isRestoreInProgress(context)`;
- `RestoreOperationStore.load(context)` success/failure;
- active operation id;
- journal phase;
- journal `lastError`;
- journal `quiescedWorkTags`;
- whether active pointer exists;
- current history URLs;
- current `f11_reset_marker` preference value/presence.

### Required WorkManager diagnostics

Preserve both:
- `WorkManager.getWorkInfosByTag(CleanupScheduleCoordinator.TAG)`;
- `WorkManager.getWorkInfosForUniqueWork(CleanupScheduleCoordinator.WORK_NAME)`.

For each WorkInfo preserve:
- UUID;
- state;
- tags;
- runAttemptCount;
- relevant input identity:
  - cleanup generation;
  - cadence;
  - occurrence timestamp when available.

Diagnostics must distinguish finished `CANCELLED` from any unfinished ENQUEUED/RUNNING/BLOCKED owner.

### Required phase timeline

Record at minimum:
1. cleanup configured;
2. cleanup admission entered;
3. Restore gate observed active;
4. ordinary Download admission rejected;
5. cleanup admission released;
6. Restore result returned;
7. diagnostic snapshot captured.

No arbitrary sleeps.
No retry loop.

## Semantic assertions must remain

The corrected test must still require:
- cleanup worker reaches the controlled admission point;
- Restore publishes authority while old history remains;
- ordinary Download admission fails while Restore owns the gate;
- after cleanup release, Restore returns `Completed`;
- final history equals only the imported row.

Do not weaken `Completed` into a pending outcome.
Do not auto-recover a pending Restore inside this method merely to make it pass.
Do not cancel extra work after failure before capturing diagnostics.
Do not increase timeouts as the primary correction.

## Commit policy

Add exactly one forward test-only commit.

Required trailers:

`Defect-ID: BUG-BACKUP-03`
`Reviewed-Checkpoint: <this checkpoint SHA>`
`Review-Finding: F11-BACKUP-RESET-OUTCOME-DIAGNOSTIC`
`Canonical-Defect-Delta: 0`

No amend.
No rebase.
No squash.
No force-push.
No history rewrite.

## Verification after correction

Because the test tree changes, rerun is then authorized.

On the new exact committed SHA:

1. `git diff --check`;
2. AndroidTest compile as required;
3. run only:
   `BackupResetTransactionProductionWiringTest.activeConflictingWorkerMustQuiesceBeforeResetApplies`;
4. if PASS, run the full `BackupResetTransactionProductionWiringTest` class once.

If focused FAIL:
- STOP;
- do not rerun;
- preserve exact RestoreOutcome subtype/fields;
- preserve journal phase/lastError/gate state;
- preserve cleanup WorkInfo snapshots;
- preserve history and marker state;
- no production correction is authorized until independent classification.

If full class FAIL:
- STOP on first valid failure;
- do not automatically isolate or rerun it.

If full class PASS 26/26:
- preserve the original current failure as a valid historical failure;
- classify only the diagnostic-corrected execution as PASS;
- continue the still-missing fifth-wave final-SHA gates on the SAME new exact SHA.

## Exact-final-SHA consequence

The diagnostic commit changes androidTest source.

Therefore prior execution on `0172cfe...` is historical evidence only for final-SHA closure.

After focused/full BackupReset PASS, all governing fifth-wave final-SHA focused/neighboring/broader gates must be valid on the new exact final SHA, including:
- preference admission 6/6;
- Cleanup 69/69;
- Automatic-keyword;
- History undo;
- LowQuality;
- frozen-27 exact identity reconciliation;
- broad F11 exact identity set.

No unchanged-tree green-seeking reruns.

## Push consequence

Only the exact tested five-commit candidate may be normally pushed if every mandatory gate completes validly.

No history rewrite.

Independent exact-source re-review remains required after push.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
