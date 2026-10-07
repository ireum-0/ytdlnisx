# Scheduler owner-race broader verification — handoff test failures classified

Date: 2026-10-07

record_kind: IMPLEMENTATION_STOP_REPORT_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_remote_head: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: 95f1662d8f2cb56b829bf946e511e16eb4f83ee8
protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
remote_implementation_changed: NO
canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8
canonical_clean_status: NOT_CLEAN

## Stop report received

The exact-alarm capability continuation reached the persisted broader-failure boundary.

Reported execution:
- focused production rerun: 5 PASS, 0 FAIL;
- broader JVM: 49 PASS, 0 FAIL;
- Scheduler/Restore Android: 34 PASS, 0 FAIL;
- WorkManager/handoff Android: 15 PASS, 2 FAIL;
- total: 105 executions, 103 PASS, 2 FAIL, 0 errors/skips;
- previously sealed JVM 8 PASS and Android owner-race 2 PASS were reused without rerun.

Protected local candidate remained:
- HEAD eda6a7589af3a19a97eb38e869b47dabaf74388b
- committed tree ca8d9b8af59d03681663de9ac9588b6e6f5e3e4a
- parent db29f63ce169176b4c8ade4cec01f66cc0307ec8
- dirty tree 7795ce45446bbeb627e803021b79d71d3549b34d
- 19 unstaged paths
- byte-identical empty index
- git diff --check PASS
- no new source edits, commits, or publication

Evidence report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-scheduler-exact-alarm-20261007-p1kTCR/SCHEDULER_EXACT_ALARM_CAPABILITY_STOP_REPORT.md

Temporary exact-alarm debug capability was reported restored.
All verification processes finished. Emulator remains running.

## Failure 1 — schedulerOrdinaryCarrierMutationCompletesBeforeRestorePublication

Observed:
- F11HandoffCarrierMutationAdmissionProductionWiringTest
- assertion line 270
- completed Reset returned with the previously created scheduler handoff carrier absent.

Exact source review:
- the helper ordinaryWriterWins asserts every ordinary carrier created before Restore publication must remain
  present after the completed Reset;
- the test plan is a settings Reset;
- scheduler settings are portable preference state under BackupSettingsUtil.isPortablePreferenceKey;
- settings Reset clears/replaces the portable preference image;
- Restore post-commit explicitly supersedes pre-Restore scheduler-settings transition authority through
  SchedulerSettingsTransitionCoordinator.supersedeForRestore;
- historical F11 scheduler review establishes that scheduler preference state and its external authority are
  one Restore-owned semantic domain and that stale pre-Restore scheduler owners must not survive as current
  authority merely because their ordinary publication happened first.

Therefore the generic survival assertion at line 270 over-generalizes the ordinary-writer rule across
semantic domains. For scheduler state, ordinary publication completing before Restore publication proves
ordering, but it does not prove that the pre-Reset scheduler carrier must survive the later authoritative
settings Reset.

Classification:
FAILURE_1=TEST_CONTRACT_STALE
FAILURE_1_PRODUCTION_ROOT=NOT_ESTABLISHED
FAILURE_1_OWNER_RACE_RESIDUAL=NOT_ESTABLISHED
FAILURE_1_SAME_ROOT_STATUS=TEST_ONLY_CONTRACT_ALIGNMENT_REQUIRED

Authorized correction:
- test-only;
- do not change production scheduler/Restore behavior;
- replace the scheduler-specific post-Reset survival expectation with a contract-specific final-authority
  assertion that proves the completed settings Reset owns the final scheduler state and that the old
  pre-Reset scheduler carrier is not treated as required current authority;
- preserve the existing hard-sub and observe-retry semantics where their state is outside this settings-only
  Reset ownership boundary;
- do not weaken the ordinary-vs-Restore admission ordering assertions.

A simple global removal of the line-270 assertion is not sufficient. The scheduler case must retain a
positive final-authority assertion.

## Failure 2 — observeRetryResetWinsBeforeOrdinaryCarrierMutation

Observed:
- F11HandoffCarrierMutationAdmissionProductionWiringTest
- assertion line 298
- publicationEntered.await(20s) timed out.

Exact test/source boundary:
- resetWins starts RestoreTransactionCoordinator.begin asynchronously;
- the ordinary observe-retry writer is created only inside
  RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting;
- line 298 waits for that Restore-publication hook before the writer is launched;
- therefore this failure occurred before the contested ordinary observe-retry carrier mutation began.

The current evidence cannot distinguish:
1. Restore begin returned before ownership/publication with a typed outcome;
2. Restore begin was blocked before withRestorePublication;
3. a residual test-environment/staging/operation-lock condition prevented publication;
4. an instrumentation timing issue occurred.

No production observe-retry mutation failure is proven by this timeout.

Classification:
FAILURE_2=INCONCLUSIVE_NOT_VERIFIED
FAILURE_2_PRODUCTION_ROOT=NOT_ESTABLISHED
FAILURE_2_EXISTING_ROOT_RESIDUAL=NOT_VERIFIED
FAILURE_2_RERUN_WITHOUT_NEW_OBSERVABILITY=FORBIDDEN

## Authorized diagnostic boundary

No production source edit is authorized.

A narrow test-only diagnostic/correction wave is authorized in
F11HandoffCarrierMutationAdmissionProductionWiringTest.kt and, only if strictly required for observability,
adjacent existing test seams without production semantic change.

For failure 2, add bounded observability sufficient to distinguish the pre-publication states above:
- record whether RestoreTransactionCoordinator.beforeActivePublicationForTesting was reached;
- retain the existing restorePublicationAuthorityAcquiredForTesting signal;
- if publication is not reached within the bounded wait, inspect whether the reset Deferred has already
  completed and capture its exact RestoreOutcome/exception instead of reporting only a latch timeout;
- record whether an active Restore pointer exists at that point;
- record the exact phase reached by the Reset when available;
- do not increase timeouts as the sole correction;
- do not convert a missing publication into a pass.

After the diagnostic harness materially changes the observable state, one isolated rerun of
observeRetryResetWinsBeforeOrdinaryCarrierMutation is authorized.

If it proves an environment/test-isolation condition and no production semantic defect:
- make only the minimum test-harness correction justified by that evidence;
- rerun the exact method once after the correction.

If it proves Restore production behavior contradicts the established admission/authority contract:
- STOP before production edit and return exact evidence for independent classification.

## Continuation after test-only closure

After the scheduler stale assertion is corrected and the observe-retry timeout is classified/closed:
- run the exact F11HandoffCarrierMutationAdmissionProductionWiringTest class/partition;
- require all methods in that partition to execute and pass;
- rerun the governing WorkManager/handoff Android partition and require the prior 15 PASS plus both
  corrected/diagnosed failures to close with zero failures;
- prior sealed focused 5/5, broader JVM 49/49, Scheduler/Restore Android 34/34, JVM 8/8 and owner-race
  Android 2/2 need not be repeated before commit while the production candidate remains byte-identical,
  unless the diagnostic establishes a material reason they are no longer attributable.

Then continue the already-authorized source/scope audit, logical commit, normal fast-forward publication,
and exact-published-SHA closure. Exact-final-SHA closure must execute the applicable governing partitions
again as required by the persisted remediation contract.

Any production edit need, new semantic root, unresolved timeout cause, protected-state mismatch,
governance/ref/history mismatch, or non-fast-forward publication requirement is a mandatory STOP.

INDEPENDENT_REVIEW_REQUIRED=YES
