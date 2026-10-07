# Manual correctness review — eda6a758 — L1 durability/recovery deep — start

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: IN_PROGRESS
manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: e5f61954c20a56515d0677e9bf018061d43dbe64

implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
implementation_branch: checkpoint/pre-baseline-review
review_branch_start: e5f61954c20a56515d0677e9bf018061d43dbe64

protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
master_plan_branch_tip_at_start: 8491528b730abea17de22013bca4288e1549a39e
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
governing_checklist_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3

write_scope: REVIEW_REMediation_ONLY
private_handoff_write: PROHIBITED_BY_USER_FOR_THIS_RUN
persisted_prompt_write: PROHIBITED_BY_USER_FOR_THIS_RUN
production_source_write: NO

## Review basis and lifecycle

No resumable manual-review IN_PROGRESS checkpoint exists for implementation eda6a758.
The latest eda6a758 manual checkpoints are FINAL:
- L6 repository-wide reopen final;
- L5 platform-contract final.

This run is therefore a new same-SHA review pass. It does not reuse either prior verdict.

## Governing sequence

BASELINE L1-L6
-> recompute triggered modules/contracts
-> close blocker-relevant triggered obligations
-> primary DEEP lens L1 Durability & recovery
-> preserve L2/L3/L4 as remaining not-yet-DEEP on eda6a758 unless this run proves otherwise

lens_coverage_current_sha:
- L1: BASELINE -> DEEP IN_PROGRESS
- L2: BASELINE planned; not DEEP on this SHA
- L3: BASELINE planned; not DEEP on this SHA
- L4: BASELINE planned; not DEEP on this SHA
- L5: DEEP from prior FINAL run, but current baseline will be recomputed
- L6: DEEP from prior FINAL run, but current baseline will be recomputed

primary_deep_lens: L1 Durability & recovery
primary_deep_selection_reason: R1/R2 — current confirmed repository roots include recovery/discoverability and durable-handoff failures; L1 is the first not-yet-DEEP lens on eda6a758 directly owning those unresolved contracts.
remaining_not_yet_deep: L2,L3,L4
next_not_yet_deep_lens: L2
next_lens_selection_reason: provisional R5 tie-break after L1 unless materially new evidence changes relevance.

## Trigger map at start

1. WorkManager/scheduler durable handoff
   - Module/contract: scheduler handoff + request/acceptance/recovery ownership
   - Status: TRIGGERED / IN_PROGRESS
   - Roots/evidence: BUG-SCHEDULER-WINDOW-01, BUG-SCHEDULER-RESTORE-01, WorkManagerHandoffCarrier paths

2. Durable recovery carriers/journals
   - Module/contract: recovery discovery closure + carrier-loss matrix
   - Status: TRIGGERED / IN_PROGRESS
   - Surfaces: DownloadExecutionRecovery, DownloadProducerRecovery, DownloadWorkerOwnershipRecovery,
     PublicationRecoveryJournal, TerminalExecutionRecovery, HistoryReplacementTerminalRecovery

3. Restore/import persistence changes executable behavior
   - Module/contract: persistent setting/import/restore consumer/effect closure
   - Status: TRIGGERED / IN_PROGRESS
   - Surfaces: BackupRestoreParser, RestoreMutationAdmission, RestoreTransactionCoordinator,
     updater/source restore and scheduler restore

4. Destructive filesystem/reference cleanup
   - Module/contract: mutation-boundary revalidation + durable ownership
   - Status: TRIGGERED / IN_PROGRESS
   - Surfaces: HistoryFileDeletion, cleanup journals/coordinator, HistoryReferenceMutationCoordinator

5. Background/worker async platform completion
   - Module/contract: async request vs completion / foreground establishment
   - Status: TRIGGERED / IN_PROGRESS
   - Root/evidence: WORKER-FOREGROUND-COMPLETION-01 and format worker siblings

6. Observe/HardSub durable generation ownership
   - Module/contract: generation fencing + post-insert/recovery ownership
   - Status: TRIGGERED / IN_PROGRESS
   - Surfaces: ObserveSourcesRepository/ObserveSourceWorker, HardSubScanWorker

## Execution

Independent source review: IN_PROGRESS.
Independent runtime/build/test execution: NOT STARTED; GitHub-only review environment for this run.

INDEPENDENT EXECUTION: NOT EXECUTED
