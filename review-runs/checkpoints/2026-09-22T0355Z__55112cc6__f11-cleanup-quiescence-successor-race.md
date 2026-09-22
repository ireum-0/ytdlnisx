# F11 fifth-wave BackupReset active-cleanup successor race — production quiescence residual confirmed

Date: 2026-09-22

Authoritative remote implementation HEAD:
\`55112cc6d5234e44b785fe655007a7fb58ac0553\`

Reported exact local candidate:
\`87b05af9da2d51717594cebddcdaf32be93a4621\`

Reported parent:
\`ff7d1a57ad68a53e4debdd9c670908c4935c9e93\`

Reported relation:
6 ahead / 0 behind

No push occurred.

Governing review before this classification:
\`7faf0d88f0c2c140d539f313d0b723d07deb6898\`

## Reported exact-SHA evidence

On exact candidate \`87b05af9...\`:

- androidTest Kotlin compile: PASS;
- focused \`activeConflictingWorkerMustQuiesceBeforeResetApplies\`: PASS 1/1;
- full \`BackupResetTransactionProductionWiringTest\`: 26 executed / 25 passed / 1 failed / 0 skipped.

Failing method:

\`activeConflictingWorkerMustQuiesceBeforeResetApplies\`

Exact outcome:

- subtype: \`RestoreOutcome.RecoveryPending\`;
- operationId: \`bb85b646-b860-4802-94e6-3759b9bfcf50\`;
- phase: \`PREPARED\`;
- reason / journal.lastError: \`Conflicting WorkManager work did not quiesce\`;
- RestoreGate: active;
- active pointer: exists;
- RestoreOperationStore.load: succeeded;
- journal.quiescedWorkTags: null;
- history remained the pre-Reset row;
- \`f11_reset_marker\`: absent.

Cleanup evidence:

Controlled request:
- id \`ac52cb05-22e5-4b37-8716-3b786f305e06\`;
- state RUNNING / attempt 1;
- generation \`16b59af5-72fe-45af-8746-c44fec284b36\`;
- cadence \`daily\`;
- occurrence \`1790136471304\`.

Failure snapshot:
- a different request id \`03a221de-801f-4470-8a27-c6a42ba04a09\`;
- state ENQUEUED / attempt 0;
- SAME generation \`16b59af5-72fe-45af-8746-c44fec284b36\`;
- SAME cadence \`daily\`;
- next occurrence \`1790222871304\`.

Reported logcat confirms the first request reached CANCELLED before the second request was scheduled.

All later fifth-wave gates were correctly NOT EXECUTED.

## Authoritative source findings

Do not treat the unpushed local candidate as authoritative source.
The production race is independently visible at authoritative remote \`55112cc6...\`.

### Restore quiescence

For a settings Reset, \`RestoreTransactionCoordinator.quiesce(...)\`:

1. runs only after the durable active Restore pointer is published;
2. calls \`CleanupScheduleCoordinator.prepareForSettingsReset(context)\`;
3. includes \`CleanupScheduleCoordinator.TAG\` in the conflicting WorkManager tag set;
4. calls \`cancelAllWorkByTag(TAG)\` and waits for cancellation-operation acceptance;
5. polls until every WorkInfo under the tag is terminal;
6. fails PREPARED with \`Conflicting WorkManager work did not quiesce\` if a new unfinished request remains.

### Cleanup preparation is not a publication fence

\`CleanupScheduleCoordinator.prepareForSettingsReset(...)\` currently:
- acquires \`destructiveEffectMutex\`;
- enters the coordinator lock;
- ensures the critical store exists;
- mirrors cadence;
- returns true.

It does NOT:
- make ordinary cleanup successor publication Restore-aware;
- stop/fence the replay owner against active Restore;
- establish an authority distinction between ordinary cleanup publication and Restore-owned post-commit reconciliation.

### Worker gate is checked only at worker entry

\`CleanUpLeftoverDownloads.doWork()\` checks:

\`RestoreGate.isRestoreInProgress(...)\`

only at the beginning of \`doWork()\`.

The worker may then block at the production-boundary cleanup admission seam.

After that boundary, \`CleanupScheduleCoordinator.withCurrentDestructiveEffect(...)\` validates cleanup generation/occurrence ownership but does not consult Restore ownership.

Therefore a worker that started before Restore publication can reach the destructive-effect admission after Restore has become active.

### Successor publication ignores Restore ownership

After a consumed cleanup occurrence, the worker calls:

\`CleanupScheduleCoordinator.scheduleSuccessor(...)\`

Current \`scheduleSuccessor(...)\`:
- checks current cleanup generation/cadence;
- persists exact successor debt;
- starts/retains replay responsibility;
- enqueues the next tagged cleanup WorkRequest;
- awaits acceptance.

It does not consult Restore ownership.

The coordinator's replay/reconciliation source also contains no RestoreGate-aware ordinary-publication fence.

Therefore cancellation of the original cleanup request is not a closed quiescence boundary:
an already-started cleanup actor or its replay responsibility can publish a new same-generation cleanup owner after Restore's cancellation request.

This exactly matches the captured transition:

RUNNING current occurrence
→ Restore active
→ current request CANCELLED
→ same-generation next occurrence ENQUEUED
→ PREPARED quiescence timeout.

## Plan reconciliation

Pinned Master Plan F11 / BUG-BACKUP-03 explicitly requires:

- conflicting worker quiescence/gating;
- post-commit scheduling/reconciliation;
- deterministic process-death recovery;
- no worker sees partial restore;
- scheduling failure represented honestly and retried idempotently;
- worker-race tests.

The current behavior violates the conflicting-worker quiescence/gating requirement.

## Classification

**CONFIRMED PRODUCTION RACE — F11 WORKER-QUIESCENCE / CLEANUP-SUCCESSOR PUBLICATION RESIDUAL.**

This is part of existing:

\`BUG-BACKUP-03\`

Do NOT create a new canonical defect ID.
Canonical defect-count delta: \`0\`.

This is not a reopened F10 scheduling defect:
- the cleanup chain's cadence/generation semantics remain an F10 concern and must be preserved;
- the newly proven failure is specifically that F11 Restore cannot establish a closed quiescence boundary against that otherwise-valid recurring owner.

This is not merely test flakiness:
- the exact source admits the observed successor publication;
- the captured new WorkRequest has the same generation/cadence and the next occurrence identity;
- the active Restore journal remained PREPARED with the exact quiescence failure.

Historical PASS runs and the earlier "controlled order/state-dependent nondeterminism" classification do not erase this production race.
That historical classification is superseded for root-cause attribution of this method.

## Required correction properties

Authorize a narrow forward production correction on top of the reported local candidate \`87b05af9...\`.

The correction must establish a CLOSED Restore boundary for cleanup ordinary work while preserving legitimate Restore-owned post-commit reconciliation.

Required invariants:

1. **No cleanup destructive admission after Restore owns the gate**
   - a cleanup worker that started before Restore publication but reaches destructive-effect admission afterward must not execute a new cleanup effect under ordinary authority;
   - it must not publish a successor;
   - behavior must remain retry/recovery-safe rather than silently discarding durable cleanup responsibility.

2. **No ordinary successor publication during active Restore**
   - \`scheduleSuccessor\` must not persist/publish a new ordinary successor after Restore authority is active;
   - the check must be ordered with the coordinator's lock/quiescence handshake so a check-before-publication race is still caught by later Restore cancellation.

3. **No replay/reconcile republish during active Restore**
   - process-local replay/bootstrap/debt recovery must not recreate ordinary cleanup WorkManager ownership while Restore is PREPARED/QUIESCED/FILES_READY/APPLYING/DATA_COMMITTED;
   - an already-running replay attempt must either complete before the Restore preparation barrier and be caught by cancellation, or observe Restore ownership and refrain from later publication.

4. **Restore-owned RECONCILING remains allowed**
   - a blanket \`RestoreGate\` no-op on all reconciliation is insufficient;
   - Restore's post-commit \`RECONCILING\` phase must be able to restore exactly one valid cleanup owner when settings Reset requires it;
   - use the existing exact \`RestoreReconciliationAuthority\` contract or an equivalently strict current-operation/current-phase authority;
   - ordinary startup/replay callers must not be able to use the Restore-authorized path.

5. **Process-death semantics remain valid**
   - active Restore pointer/journal remains the durable authority across restart;
   - startup already awaits Restore recovery before ordinary cleanup reconciliation and that ordering must remain intact;
   - do not introduce a process-local-only correctness fence.

6. **F10 semantics remain intact**
   - exactly one logical cleanup schedule;
   - current generation/cadence/occurrence identity;
   - calendar cadence;
   - no duplicate successor;
   - durable debt/replay semantics preserved;
   - no broad cancellation of unrelated work.

## Authorized source surface

Production files that MAY be changed, only as required by the minimal design:

- \`app/src/main/java/com/ireum/ytdl/work/CleanupScheduleCoordinator.kt\`
- \`app/src/main/java/com/ireum/ytdl/work/CleanUpLeftoverDownloads.kt\`
- \`app/src/main/java/com/ireum/ytdl/database/RestoreTransactionCoordinator.kt\`

Regression-test files that MAY be changed:

- \`app/src/androidTest/java/com/ireum/ytdl/database/BackupResetTransactionProductionWiringTest.kt\`
- \`app/src/androidTest/java/com/ireum/ytdl/work/CleanupScheduleCoordinatorProductionWiringTest.kt\`

Do not change other files without a new independent classification.

No Room schema/migration change.
No Gradle/dependency change.

## Required proof

At minimum prove on the new exact committed SHA:

A. The captured active-worker race:
- current cleanup occurrence reaches controlled admission;
- Restore becomes active;
- ordinary Download admission is rejected;
- original cleanup is released/cancelled;
- no same-generation successor/replay owner appears before quiescence completes;
- Restore returns Completed;
- imported state is authoritative.

B. Restore-owned cleanup recovery:
- after settings Reset reaches RECONCILING, the exact current Restore authority may recreate/reconcile the cleanup schedule;
- after completion there is exactly one valid current cleanup owner when cadence is enabled;
- stale/pre-Reset owner does not survive.

C. Ordinary replay fence:
- an ordinary replay/reconcile path cannot publish cleanup work while Restore is active;
- after Restore completes, normal reconciliation can resume.

D. Existing F10 coverage:
- the current Cleanup coordinator production-wiring class remains valid;
- no cadence/generation/debt/occurrence regression.

## Commit / execution policy

Astra xhigh may choose the smallest implementation that proves these invariants after reading exact source.

It may use local compile/static checks inside the one authorized uncommitted patch before committing.

Create exactly ONE new forward production-remediation commit on top of \`87b05af9...\`.

Required trailers:

\`Defect-ID: BUG-BACKUP-03\`
\`Reviewed-Checkpoint: <this checkpoint SHA>\`
\`Review-Finding: F11-CLEANUP-QUIESCENCE-SUCCESSOR-RACE\`
\`Review-Severity: P0\`
\`Canonical-Defect-Delta: 0\`

After commit:
- exact-SHA compile;
- focused active-worker race first;
- targeted Restore-owned cleanup-recovery/replay-fence tests;
- full BackupReset class;
- full Cleanup coordinator class;
- then all governing fifth-wave final-SHA gates.

First valid semantic failure short-circuits.
No unchanged-tree green-seeking rerun.
No production auto-fix after a semantic failure without new independent classification.

## Push consequence

Only the exact tested seven-commit candidate may be normally pushed if every mandatory final-SHA gate completes validly.

No amend/rebase/squash/force-push/history rewrite.

Independent exact-source re-review remains required after push.

CLEAN basis remains:
\`90afaec157607669ea32fa41877e7f0efcdcca86\`

INDEPENDENT EXECUTION: NOT EXECUTED
