# Repository current-existence audit — BUG-TERMINATE-01 and lower-bound completion

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: a31da34b30092dbfb1b7ce348f900d08cfc4587d
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-TERMINATE-01 — VERIFIED_CLOSED

Historical root:
after the user selected "do not show again", the no-confirmation Terminate action skipped the durable
Active/PostProcessing repair performed by the confirmation branch and immediately called exitProcess().

Current MainActivity:
- confirmation branch enumerates current Active/PostProcessing rows and awaits
  requeueActiveDownloadsForExit(ids) before exit;
- no-confirmation branch performs the same enumeration and awaits the same
  requeueActiveDownloadsForExit(ids) before exit;
- therefore the UI preference now changes confirmation presentation only, not shutdown-state semantics.

Current requeueActiveDownloadsForExit():
- runs in Dispatchers.IO + NonCancellable;
- captures only current Active/PostProcessing rows;
- for each exact execution it acquires the per-Download side-effect lease;
- persists DownloadExecutionRecovery responsibility before native shutdown;
- resolves any durable user-stop/history-finalization winner;
- otherwise proves exact native quiescence and only then requeues the running row through the repository;
- preserves committed-History finalization debt rather than turning it back into runnable work;
- any persistence/quiescence/requeue failure is collected;
- recovery scheduling is attempted for failed items;
- firstFailure is thrown after the pass.

Because MainActivity reaches finishAndRemoveTask()/exitProcess() only after the suspending helper returns,
a material shutdown failure prevents that normal user-facing exit path from falsely completing.

Startup additionally runs DownloadExecutionRecovery, so an already-abrupt external process death retains a
separate recovery owner rather than relying on the terminate UI.

Disposition:
BUG-TERMINATE-01 = VERIFIED_CLOSED.

## Registry-derived lower-bound audit completion

The 136-ID lower-bound population derived from:
- unique TASKS.md / TASKS_DELTA.md identifiers, plus
- later current-review identifiers absent from those registries

has now been fully current-existence audited on eda6a758.

Disposition counts for those 136 IDs:
- VERIFIED_CLOSED/currently-not-reproduced: 83
- VERIFIED_OPEN: 52
- SUPERSEDED_ALIAS: 1
- NOT_YET_AUDITED: 0

These counts describe the 136-ID lower-bound population only.

They are NOT the final project-wide canonical-root totals because checkpoint-only production findings
exist outside that population. Confirmed examples already identified include provisional
BUG-TERMINAL-11 and BUG-OBSERVE-HANDOFF-01, while some already-audited checkpoint-only roots such as
BUG-BACKUP-11, BUG-UPDATER-03 and BUG-HISTORY-05 also demonstrate that TASKS/TASKS_DELTA are not a
complete discovery registry.

Therefore:
REPOSITORY_FINDINGS_REGISTRY_DERIVED_AUDIT_STATUS=COMPLETE
REPOSITORY_DISCOVERED_CANONICAL_ROOTS_TOTAL=NOT_YET_VERIFIED
CHECKPOINT_ONLY_FINDING_DISCOVERY=REQUIRED

Current narrow download canonical remains unchanged:
P0=0 / P1=0 / P2=8.

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
