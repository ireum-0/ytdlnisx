# WP0B10 — independent completion review: expanded R/L and R/E source-order CONTRACT_GAP

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW_SOURCE_SEMANTIC_STOP
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: 9a327934ac0136c1c3ee718a94a5dc6959e4bf80
reviewed_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
private_full_report_commit: bbb4fc2f73ba5ce3a30e0ff8aed694f08fe9cbe2
private_full_report_path: ytdlnisx/reports/2026-10-22__WP0B10__wp0b10-20261022-536a25c4__FULL_REPORT.md
private_full_report_blob: 71bb078d534615f93e17ad2017ed4c99c3fdba50
task_instance_id: WP0B10_PROGRESS_FAIR_LOCK_A_INTEGRATION_20261022_141B519_01
prompt_first_add_commit: ae43437ef8ea4ddd2cbff3b90e27e506d39c16d2
prompt_blob: 5be2c121f770c588d612f059593c2b379001f3c8
launch_handoff_commit: f5e7fef979e036a637d3a11138df8c50102e7709
intent_commit: 3a57e7f48b0c836e812e7d39f7d4aab62eea0932
intent_blob: a8f4f41eb94c06580456ad52084f60ba67b9977d
claim_commit: 4668277e326e5b5fe0a8a4e9fb41f4452de8b2b4
claim_blob: 7766a3b49ddd852f18f49c6594160a97f166d8ce
claimant_id: 536a25c461a02ae66c89a14a1068abcf
pinned_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
pinned_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
pinned_lens_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
independent_verdict: VALID_CONTRACT_GAP_R_L_AND_R_E_SOURCE_ORDER_UNCLOSED
lock_order_safe: NOT_VERIFIED
source_correction_newly_authored: NO
production_source_published: NO
implementation_tip_advanced: NO
new_test_methods: ZERO
gradle_invocations: ZERO
current_wave_unit_methods_executed: ZERO
device_android_tests_executed: ZERO
local_existing_ten_file_draft: PRESERVED_REPORTED_RAW_HASH_VERIFICATION
local_draft_bytes_independently_accessible_by_reviewer: NO
same_pushed_sha_closure: NOT_REACHED
design_A_actual_writers: UNCONVERTED
full_implementation_ready: NO
new_canonical_root_count: ZERO
canonical_root_status_changes: ZERO
P2_OPEN: 4
P2_AMBIGUOUS: 4
P2_UNRESOLVED: 8
repository_OPEN: 53
repository_AMBIGUOUS: 4
repository_CLOSED: 95
CLEAN: NO
manual_L2_status: IN_PROGRESS_UNCHANGED
wp0b10_task: CONSUMED_STOPPED_NO_RELAUNCH
historical_wp0b05_index_cause: NOT_VERIFIED
private_handoff_updated: NO
new_prompt_persisted: NO

## Verified publication and frozen source

Read exact Full Report blob `71bb078d534615f93e17ad2017ed4c99c3fdba50` at private HEAD `bbb4fc2f73ba5ce3a30e0ff8aed694f08fe9cbe2`, exact persisted immutable prompt `5be2c121f770c588d612f059593c2b379001f3c8`, independently matching own intent and claim and protocol. Compared private from `f5e7fef979e036a637d3a11138df8c50102e7709` to `bbb4fc2f73ba5ce3a30e0ff8aed694f08fe9cbe2`: three commits ahead, zero behind, three added files only—own intent, claim and Full Report—and no preexisting modified or deleted path. Independently fetched each commit metadata: sole parents `f5e7fef -> 3a57e7f -> 4668277 -> bbb4fc2`, each with exactly one added expected path. Nontruncated private tree has 491 entries, exact report path/blob. This corroborates preservation of all previously tracked paths through GitHub compare; individually hashing all 475 old files or local report SHA256 was not repeated by reviewer.

At the final source check implementation remained exact `141b5199592369ab953f52c55ebdb72ba300f1a4`; no new production commit. Reported new isolated clone copied ten earlier WP0B09 files, checked their source hashes/fingerprint `b21f3eaef2f03a106fec0cda42d8444e223fa6a57c53a219a7bc0d46b015ea86`, made NO new changes, ran NO Gradle/test/device methods and preserved older scratch. Those local bytes and 35 artifact manifest entries are the agent's archived evidence; reviewer has NOT fetched the candidate or recomputed hashes. `git diff --check` in report is a static diagnostic only, not compile/test. There is no native build result in this wave. WP0B10's exact matching own intent+claim+STOP report supersedes the stale private capsule `PERSISTED_NOT_STARTED`, forbidding relaunch of this exhausted instance. This review checkpoint does not change handoff or authorize a new prompt.

## Independent source-level review of blocking edges

At pinned `141b5199592369ab953f52c55ebdb72ba300f1a4`, reviewer read the following real production sources and confirmed the referenced nesting:

**R→L from low-quality parent cancellation.** `LowQualityRedownloadManager.kt` Git blob `58f3e0647cc18222b1b2a96828bf64d03d525e3c`: `cancel()` outer `RestoreMutationAdmission.withOrdinaryMutation` encloses `repository.requestCancellation`. `LowQualityRedownloadRepository.kt` blob `c0dfb86fcb72c5cc494058075e33630f861efb71`: requestCancellation enters `withCurrentLinkedExecutionLeases`, takes/releases E snapshot and then awaits `withDownloadWorkerExecutionSideEffectLeases(executions)` while manager's R is retained, finally rechecks E before Room action. Thus an approach retaining a granted L to block on R is NOT safe against this already reachable R→wait L path. That is a **counterexample to the proposed retained-L algorithm**, not evidence all possible designs are impossible.

**E→R from user stop.** `CancelDownloadNotificationReceiver.kt` blob `82339ec726242c4ea522f30fea870b8699dd4489`, and `PauseDownloadNotificationReceiver.kt` blob `aed2a4278215959eadc7b51086823fe29ac6b33b`: under per-ID L and `withDownloadWorkerExecutionLock` E, each records durable `SEMANTIC_STOP_PENDING` and calls `DownloadRepository.convergeUserStopSemantic` before exiting E. `DownloadRepository.kt` blob `8125ef4b1a09872c83be876e4f98f14981553246` applies ordinary R around convergeUserStopSemantic. `DownloadViewModel.kt` blob `71a1b4931af7430ad09650b857e5a1f6d39a4c9d` has an analogous Pause All L→E→R call. Removing this R or shifting the durable pending action is NOT demonstrated safe without exact user-intent, Restore priority and recovery ownership proof.

**R→E from scheduled cancellation.** `CancelScheduledDownloadWorker.kt` blob `eac2ed1080e87940065d3c0844b0af460b240bbc`: inside `RestoreMutationAdmission.withOrdinaryMutation` R, `SCHEDULE_END` admission checks and `WorkManager.cancelAllWorkByTag(...).result.get(...)` precede `withDownloadWorkerExecutionLock` E around active/PostProcessing snapshot. Separate Notification stop E→R can conflict even if different Download IDs. Crucially, scheduled worker's later per-download L stage is OUTSIDE the initial R block; the report explicitly rejected an initial R→L suspicion there. This correction is validated.

**Candidate-only R→E History edge.** The report also documents a R→E request inside prior uncommitted WP0B09 `DownloadWorker.withOwnedHistoryMutation`. That code is NOT at the remote production SHA and was not independently fetched as a current Git tree. Do NOT treat it as deployed. The shipped scheduled R→E vs Notification E→R edge alone supports the independent source-order concern, while the inherited local candidate's History admission would require separate correction before publication.

These opposing edges support an *adversarial wait-cycle scenario*. No Android deadlock, exhaustive reachability/owner correlation, actual full execution outcome, complete global lock graph or alternative source-proof is claimed. Whether these paths constitute one existing semantic root or aliases to previously counted findings is NOT_VERIFIED; do not open/close/double-count a root merely from this source counterexample. There is no independently established new shipped commit.

## Incomplete Design A and next protected boundary

WP0B10 did not create a working Design A implementation: existing Room65 `pending_undo_carriers` text fields and old writers/readers were unchanged. The typed receipt/representation CAS copied from earlier local drafts is unconnected to actual removal/cancellation producers or semantic effects. Nineteen direct `DownloadRepository` carrier deletes plus one `LowQualityRedownloadRepository` delete remain unconverted, as do stronger linked revokers, accepted first-choice generation, stale resolver/ID incarnation, Room effect+terminal co-commit, v1/F fallback exact ACK, terminal-only cold discovery, legacy Room58–65, selected/unselected/pointer-first Restore conflicts and bounded GC. A first-state writer must not be deployed ahead of all terminal-aware consumers. No Android/real Room observer or current-wave Kotlin tests ran, so every claimed closure remains NOT_VERIFIED or NOT_REACHED.

The next **recommendation only, NOT authorized new prompt** is a separately authorized caller-level lock admission redesign with an exact source-level proof for low-quality manager R-held linked L, notification/PauseAll E-held ordinary R, scheduled R-held E, Worker History and Restore M→R, and the terminal/native/WorkManager/History effects. Must preserve first durable user stop and cancellation recovery responsibility, short E, exact owner/execution/generation/Restore-pointer revalidation after releases, and finite two-actor or three-actor progress. No new global lock, broad Worker rewrite, migration, skipped reference guard, retry-by-timeout or unbounded polling without separate authorization. Only after safe source correction and source-bound test PASS can the SAME authorized implementation wave continue with Design A and guarded normal FF publication/exact-pushed-SHA closure. If the graph cannot be closed under existing authorization, STOP at the exact need for expanded authority.

Independent review disposition: `VALID_CONTRACT_GAP_R_L_AND_R_E_SOURCE_ORDER_UNCLOSED`, `FULL_IMPLEMENTATION_READY=NO`, `NOT_CLEAN`. Canonical P2 4 OPEN + 4 AMBIGUOUS = 8 unresolved, repo 53 OPEN/4 AMBIGUOUS/95 CLOSED; manual L2 review independently remains IN_PROGRESS. Historical WP0B05 index collision cause NOT_VERIFIED. Reviewer appends this evidence only, not production code, private routing or a new execution task.
