# Manual correctness review — eda6a758 — L1 durability/recovery deep — intermediate A

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: IN_PROGRESS
manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: e5f61954c20a56515d0677e9bf018061d43dbe64
review_parent_sha: 207148030b8615fb72979d00c358909348773461

pinned_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
newer_implementation_head_observed: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
newer_implementation_inspected: NO
newer_implementation_review_status: UNREVIEWED
run_basis_switched: NO

protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3

write_scope: REVIEW_REMediation_ONLY
private_handoff_write: NO
persisted_prompt_write: NO
production_source_write: NO

primary_deep_lens: L1 Durability & recovery
lens_status: IN_PROGRESS
completed_scope:
- WorkManager handoff carrier lifecycle and recovery discovery
- Download execution/producer/publication/native-marker recovery discovery
- Restore transaction durable phases and scheduler-restore handoff
- History destructive file/reference mutation boundary
- cleanup occurrence journal/debt replay and exact-target deletion
- HardSub generation/admission path
- HistoryDateFetch durable operation/enqueue path
remaining_scope:
- Observe durable generation/recurrence ownership
- worker foreground-completion durability baseline
- cross-check L1 findings against existing semantic-root lineage
- complete BASELINE L1-L6 and triggered-module disposition matrix

## Confirmed same-root residual — BUG-SCHEDULER-01

classification: SAME_ROOT_RESIDUAL
canonical_root: BUG-SCHEDULER-01
severity: P2
new_root_count_delta: 0
repository_open_count_delta: 0
active_download_count_delta: 0

Exact production chain at pinned eda6a758:

1. AlarmScheduler/receiver fallback creates an exact WorkManagerHandoffCarrier for scheduler START or END.
2. enqueue acceptance promotes that carrier to ACCEPTED with exact requestId/generation/boundary identity.
3. DownloadWorker / CancelScheduledDownloadWorker retire that exact scheduler carrier only after successful
   semantic completion.
4. A worker exception before retirement can therefore leave the carrier ACCEPTED while WorkManager marks
   its request FAILED; external cancellation can likewise leave the accepted request CANCELLED.
5. WorkManagerHandoffRecovery.reconcileCarrier() has explicit FAILED/CANCELLED retry ownership for
   OBSERVE_RETRY_DOWNLOAD, AUTOMATIC_KEYWORD_SYNC and TERMINAL_COMMAND, and recurrence retry handling for
   OBSERVE_RECURRENCE.
6. Scheduler START/END has no corresponding accepted FAILED/CANCELLED retry branch.
7. After the shared unfinished-work guard, an accepted scheduler carrier with a finished failed/cancelled
   request falls through the generic accepted-carrier deletion path.
8. The durable daily boundary owner can therefore disappear without semantic completion, replacement, or
   successor re-arm.

Why this is not a new root:
- TASKS.md BUG-SCHEDULER-01 already owns the persistent daily scheduler/restart/re-arm invariant:
  the scheduler setting must survive repeated daily cycles and restart without requiring a later
  user/queue mutation.
- Losing an accepted-but-failed exact boundary carrier is another failure mode of that same recurrence
  and durable boundary-ownership contract.

Correction boundary:
- for current scheduler START/END accepted requests that finish FAILED/CANCELLED without exact semantic
  retirement, preserve/retry the exact carrier instead of deleting it;
- retry must remain fenced by current handoff/generation/boundary authority so a superseded E1 cannot
  resurrect after settings/Restore publishes E2;
- successful exact worker terminal retirement remains authoritative and must not be replayed;
- process-death recovery must distinguish failed/cancelled current request from superseded/retired
  boundary ownership;
- add deterministic START and END controls for accepted->FAILED and accepted->CANCELLED, process restart,
  settings supersession, restore supersession, and success-retirement non-replay.

## Existing L1 roots revalidated, no count change

BUG-DATE-03 — VERIFIED_OPEN P2:
- durable HistoryDateFetch operation/items are committed first;
- HistoryDateFetchManager.enqueue() calls enqueueUniqueWork(KEEP) but does not observe/await Operation.result;
- startup reconcile repeats the same unobserved enqueue boundary;
- enqueue failure can leave durable nonterminal operation state with no confirmed execution carrier.
Relationship: existing root; count delta 0.

BUG-HARDSUB-GENERATION-01 — VERIFIED_OPEN P2:
- prepareHardSub creates durable exact handoff/request generation;
- HardSubScanWorker does not consume handoffId/requestId/generation;
- stale superseded worker can continue History scan-state writes and Download publication;
- countPendingByPlaylistMarker() and insert() are separate, so overlapping generations can both reserve
  the same History replacement.
Relationship: existing checkpoint-only representative root; count delta 0.

BUG-SCHEDULER-RESTORE-01 — VERIFIED_OPEN P2:
- Restore owns durable phase/recovery machinery and awaits scheduler fallback acceptance;
- the already-established final-image scheduler validation/preferences-to-external-owner convergence
  defect remains the governing open root. No additional Restore lifecycle root was established in this pass.

## Negative L1 checks completed

Download recovery discovery:
- opaque/unavailable PublicationRecoveryJournal and DownloadProducerRecovery namespaces are not treated as
  empty;
- running rows, committed replacement rows, journal rows, native-process rows, orphan journal ids,
  orphan native processes, native marker candidates, producer records and pending primary-success
  authorities are represented in recovery discovery;
- exact execution leases/rechecks prevent stale E1 cleanup from authorizing effects against E2 in the
  inspected recovery path.

History destructive deletion:
- selected-record stored-target snapshots are revalidated before deletion and again before History row
  removal;
- retained sibling references are excluded under HistoryReferenceMutationCoordinator;
- History insert/restore/replacement and video-folder path migration use the same relationship mutation
  coordinator;
- raw-file and content-document gateways revalidate immediately before external deletion.
No additional destructive-reference root established in this pass.

Cleanup durability:
- occurrence target/effect journal is durable and exact-generation/cadence/occurrence bound;
- failed/incomplete independently committing effects retain IN_PROGRESS journal responsibility;
- exact Cancelled/Error target identity is revalidated in the Room deletion transaction by
  id/status/operationId/executionId/downloadStartTime;
- cache cleanup binds exact captured roots/suffix ownership and does not reinterpret retry against a new
  mutable cache_path.
No additional cleanup durability root established in this pass.

INDEPENDENT EXECUTION: NOT EXECUTED
