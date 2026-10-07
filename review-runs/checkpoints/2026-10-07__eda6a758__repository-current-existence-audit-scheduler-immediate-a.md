# Repository current-existence audit — scheduler immediate dispositions A

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 1db930118ec3108b3f2b08df06e40b8dff311906
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-SCHEDULER-01 — VERIFIED_OPEN P2, recurrence remainder only

Historical BUG-SCHEDULER-01 combined two mechanisms:
1. incorrect daily-window membership across midnight;
2. one-shot daily start/end alarms were not re-armed for the next cycle.

The membership half was later split into the current semantic root
BUG-SCHEDULER-WINDOW-01 and must not be counted twice here.

The independent recurrence half still exists on exact eda6a758:
- AlarmScheduler.scheduleWithinOrdinaryMutation() publishes one next START and one next END boundary;
- ScheduleAlarmReceiver only converts the exact START handoff into its WorkManager successor;
- CancelScheduleAlarmReceiver only converts the exact END handoff into its WorkManager successor;
- DownloadWorker retires the consumed scheduler START carrier;
- CancelScheduledDownloadWorker retires the consumed scheduler END carrier;
- neither terminal consumer re-arms the next daily START/END pair;
- no automatic post-boundary producer was found that publishes the following day's pair solely because
  the prior pair fired.

Disposition:
BUG-SCHEDULER-01 recurrence remainder = VERIFIED_OPEN P2.

Counting rule:
- do not count its historical midnight/window subcase separately; that semantic responsibility belongs
  to BUG-SCHEDULER-WINDOW-01;
- count only the still-independent persistent daily recurrence root here.

Required direction:
after consuming a current daily boundary, durably publish/recover the next logical daily boundary pair
(or use a persistent periodic authority with equivalent exact semantics), without requiring a later
queue/settings mutation.

## BUG-SCHEDULER-03 — VERIFIED_OPEN P2

Historical root:
with individual AlarmManager scheduling enabled, only the earliest future Scheduled group gets a
one-shot alarm and no successor is armed after it fires.

Current exact source still reproduces it:
- DownloadRepository.startDownloadWorkerInternal groups future Scheduled rows;
- when use_alarm_for_scheduling=true it calls AlarmScheduler.scheduleAt() only for
  futureScheduleGroups.keys.min();
- scheduleAt publishes one START-boundary handoff/alarm using the shared individual-schedule request
  identity;
- ScheduleAlarmReceiver converts that one boundary to a DownloadWorker;
- DownloadWorker retires the consumed carrier at completion;
- no successor discovery/re-arm of the next future Scheduled start group occurs after that worker/alarm
  consumption.

Therefore T1 can execute while later T2 remains durably Scheduled without a carrier unless another
producer mutation happens to call scheduling again.

Disposition:
BUG-SCHEDULER-03 = VERIFIED_OPEN P2.

Required direction:
maintain one durable earliest-individual-schedule owner whose successful consumption atomically or
recoverably discovers/publishes the next future Scheduled group, including restart and deletion/reschedule
of the current earliest group.

## BUG-SCHEDULER-04 — VERIFIED_OPEN P2

Historical root:
daily scheduler END cancels every WorkManager request tagged "download", including unrelated future
individual delayed schedules.

Current exact source still reproduces it:
- ordinary WorkManager-backed individual delayed DownloadWorker requests use tag "download";
- scheduler START handoff DownloadWorker also uses tag "download";
- CancelScheduledDownloadWorker still executes
  WorkManager.getInstance(context).cancelAllWorkByTag("download").result.get(...);
- after this global cancellation it only reconciles current Active/PostProcessing rows;
- untouched future Scheduled rows whose delayed requests were cancelled are not reconstructed there.

Thus daily-window shutdown can still destroy the only carrier of a later individual Scheduled item while
leaving its durable row unchanged.

Disposition:
BUG-SCHEDULER-04 = VERIFIED_OPEN P2.

Required direction:
separate daily-window execution authority from future individual schedule carriers, cancel only the
exact owned scheduler generation, and reconstruct any intentionally invalidated future carrier from the
still-current Scheduled row.

## Immediate audit result

candidate_ids_audited_here: 3
verified_open_here: 3
verified_closed_here: 0

Lower-bound inventory progress:
- candidate IDs: 136
- audited: 63
- verified closed/currently not reproduced: 54
- verified open: 9
- not yet audited inside lower bound: 73

Verified-open non-canonical repository roots so far:
- BUG-PLAYER-01 — P2
- BUG-QUEUE-01 — P3
- BUG-QUEUE-05 — P2
- BUG-DATE-03 — P2
- BUG-QUEUE-02 — P2
- BUG-QUEUE-03 — P2
- BUG-SCHEDULER-01 recurrence remainder — P2
- BUG-SCHEDULER-03 — P2
- BUG-SCHEDULER-04 — P2

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
