# Repository reopened-root postmortems

Date: 2026-10-07
checkpoint_kind: REPOSITORY_REOPEN_POSTMORTEM
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: cddd4930e66aacdfbd18bf84dc5c9ae6822cd990
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
canonical_scope_change: NONE

## BUG-SCHEDULER-WINDOW-01

POSTMORTEM_CLASSIFICATION=FALSE_CLOSURE
PRIMARY_CAUSE=FINAL_EFFECT_COMPOSITION_MISS
SECONDARY_CAUSE=TEST_CONTRACT_ERROR

previous_closure_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
previous_closure_evidence: review-runs/checkpoints/2026-10-06__eda6a758__bug-scheduler-window-01-exact-sha-closure-reviewed.md
why_closed_then: circular minute membership, normalized boundary Calendar values, unchanged consumer reuse and exact-final execution all passed the then-applied closure review.
missed_path_final_effect: inclusive end-minute membership was not composed with the external END alarm/handoff firing at the beginning of that same minute.
current_bad_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
current_reversal_evidence: review-runs/checkpoints/2026-10-06__eda6a758__manual-v7-l6-repository-wide-reopen-final.md
last_known_good: NOT_VERIFIED
first_known_bad: eda6a7589af3a19a97eb38e869b47dabaf74388b
introducing_commit: INTRODUCING_COMMIT_NOT_VERIFIED
CHECKLIST_GAP=NO
REVIEW_EXECUTION_GAP=YES
TEST_CONTRACT_GAP=YES

The same exact SHA declared closed still permits the END owner to fire at HH:mm:00 while contains()
continues to authorize the configured end minute through :59.999. The committed tests accepted both sides
of that contradiction instead of proving one composed final-effect contract.

Current correction boundary:
- one end-boundary contract across contains(), nextEnd(), AlarmManager publication, durable handoff notBeforeAt,
  DownloadViewModel and DownloadWorker;
- deterministic boundary coverage for :00.000, :00.001, :59.999, following minute, overnight, end=23:59,
  start=end, and scheduling invoked during the end minute.

## WORKER-FOREGROUND-COMPLETION-01

POSTMORTEM_CLASSIFICATION=FALSE_CLOSURE
PRIMARY_CAUSE=ASYNC_COMPLETION_MISS
SECONDARY_CAUSE=TEST_CONTRACT_ERROR

previous_closure_sha: NOT_VERIFIED
previous_closure_evidence: NOT_VERIFIED_EXACT_CHECKPOINT
why_closed_then: later L5 evidence states the former closure treated foreground request invocation as sufficient rather than owning acknowledged completion.
missed_path_final_effect: setForegroundAsync() completion/failure could remain unresolved while cleanup, cache movement, or background-format correctness-relevant effects began.
current_bad_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
current_reversal_evidence: review-runs/checkpoints/2026-10-07__eda6a758__manual-v7-l5-platform-contract-final.md
historical_open_evidence: review-runs/checkpoints/2026-09-20T0541Z__90afaec1__worker-foreground-completion-exact-basis-revalidation.md
last_known_good: NOT_VERIFIED
first_known_bad: 90afaec157607669ea32fa41877e7f0efcdcca86_OR_EARLIER
introducing_commit: INTRODUCING_COMMIT_NOT_VERIFIED
CHECKLIST_GAP=NO
REVIEW_EXECUTION_GAP=YES
TEST_CONTRACT_GAP=YES

A later regression is not proven. The root was already demonstrably open at exact basis 90afaec1, and the
current L5 review finds the same request-vs-completion gap. Safe CoroutineWorker controls show that awaiting
foreground establishment is representable by the existing architecture.

Current correction boundary:
- own successful foreground establishment before the first correctness-relevant effect;
- classify setup failure/cancellation explicitly;
- preserve retry/cancellation semantics without duplicate effects;
- require deterministic production-path coverage proving zero effect after foreground-establishment failure.

## Accounting

No new semantic root is created by these postmortems.
Repository-wide derived total remains 148: 57 OPEN, 91 CLOSED.
The active download scope remains unchanged.
production_source_changed=NO
implementation_prompt_changed=NO
