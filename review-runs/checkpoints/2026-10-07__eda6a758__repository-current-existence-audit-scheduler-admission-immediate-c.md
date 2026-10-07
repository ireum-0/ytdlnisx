# Repository current-existence audit — scheduler/admission immediate dispositions C

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 90f7c4a0865cf47a81811bc375bc21f1bc7619a1
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-SCHEDULER-05 — VERIFIED_CLOSED

Historical root:
API 24-30 were falsely treated as incapable of exact alarms because the capability helper returned false
when the API-31 canScheduleExactAlarms() query did not exist.

Current exact source:
- AlarmScheduler.canSchedule delegates to ExactAlarmCapabilityPolicy.canSchedule;
- the policy requires AlarmManager availability and then returns true for apiLevel < 31;
- only API 31+ invokes canScheduleExactAlarms().

Exact current rule:
alarmManagerAvailable && (apiLevel < 31 || canScheduleExactAlarms()).

Disposition:
BUG-SCHEDULER-05 = VERIFIED_CLOSED.

The historical supported pre-Android-12 false-capability path is no longer present.

## BUG-ADMISSION-01 — VERIFIED_CLOSED

Historical root:
claimDownloadForWorker() could commit Active/E1, publish process-local owner E1, then a separate
fallible getNullableDownloadById() could throw before worker cleanup bookkeeping was attached, leaving a
same-process ghost owner.

Current exact source closes that specific post-claim persistence gap:
- DownloadDao.claimDownloadForWorkerAndRead() is a Room @Transaction;
- inside that one transaction it executes the exact claim CAS and then materializes the claimed row;
- if the claim changes zero rows it returns null;
- if the post-claim materialization read fails or cannot prove the exact executionId, the transaction
  throws and Room rolls back the claim rather than leaving Active/E1 committed;
- claimDownloadThroughProductionAdmission does not publish DownloadWorkerExecutionOwners.claim(E1) until
  claimDownloadForWorkerAndRead has returned a successfully materialized exact row.

Thus the historical interleaving
  durable Active/E1 commit -> post-claim Room reread failure -> false-live process owner
is no longer possible through this path.

Disposition:
BUG-ADMISSION-01 = VERIFIED_CLOSED.

This disposition is limited to the recorded post-claim Room materialization root. It does not claim that
all later actor-start failures are impossible; any distinct later production throwable boundary would
require its own evidence/root.

## Immediate audit result

candidate_ids_audited_here: 2
verified_open_here: 0
verified_closed_here: 2

Lower-bound inventory progress:
- candidate IDs: 136
- audited: 67
- verified closed/currently not reproduced: 57
- verified open: 10
- not yet audited inside lower bound: 69

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
