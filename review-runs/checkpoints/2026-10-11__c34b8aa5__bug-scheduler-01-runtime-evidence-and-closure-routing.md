# BUG-SCHEDULER-01 — recovered tool path does not close accepted-terminal scheduler recovery

Date: 2026-10-11
record_kind: INDEPENDENT_REVIEW_RUNTIME_EVIDENCE_AND_ROUTING
record_status: FINAL
manual_review_run: NO
review_parent_sha: 00feaa05e304053b2226507a595a3eadcfcfcc98
implementation_pinned_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
review_checklist_v6_blob: 7b553328dfcd9941d783658f49ecb16c71b98c56
existing_root: BUG-SCHEDULER-01 OPEN P2
new_root_count_delta: 0
active_download_count_delta: 0
semantic_closure: NOT_VERIFIED
repository_clean: NOT_CLEAN

## New evidence and strict provenance

The user supplied an October 11 report from a recovered Codex alpha.20 Windows session: on emulator-5554 the installed debug instrumentation runner actually executed exactly one target method, F11HandoffCarrierMutationAdmissionProductionWiringTest#schedulerResetWinsBeforeOrdinaryCarrierMutation, yielding OK (1 test), ADB exit code 0, and no reported sandbox or launch failure. There were no reported Git/project mutations, rebuild, installation, or retry. This is credible user-supplied runtime execution-path evidence, not this reviewer's independently captured device output.

That method checks a Restore Reset versus ordinary carrier mutation boundary. It is **not** the seven-case accepted-terminal scheduler START/END failure/cancellation/supersession matrix. The installed APK's exact production source SHA, the protected three-file local BUG-SCHEDULER-01 draft and all twelve draft regressions were not independently verified by this device report. Historical Java Gradle wrapper lock WinError 5 cause remains NOT_VERIFIED. A successful ADB invocation does not establish original Gradle-wrapper startup or full 138-case exact-final-SHA closure.

## Exact published source recheck

At c34b8aa57e01803c9960e4ad873d1ed5b68e019c, app/src/main/java/com/ireum/ytdl/work/WorkManagerHandoffRecovery.kt reconcileCarrier() lines 1461-1510 handle accepted-terminal FAILED/CANCELLED OBSERVE_RETRY_DOWNLOAD, AUTOMATIC_KEYWORD_SYNC, and TERMINAL_DISPATCH, and OBSERVE_RECURRENCE terminal state, but do not handle scheduler SCHEDULE_START or SCHEDULE_END FAILED/CANCELLED. The scheduler absent/unfinished WorkInfo retention rules do not apply to terminal failure/cancellation; generic dao.deleteAccepted(handoffId, requestId) remains reachable. This confirms the same-root durable-carrier loss previously documented in review-runs/checkpoints/2026-10-08__c34b8aa5__bug-scheduler-01-accepted-terminal-loss-confirmed.md. Exact current generation/retained authority is checked in retryAfterFailure() and must be preserved; do not revive superseded requests. This is independently grounded in published source, not the unseen local draft.

## Protected local unpublished candidate and required closure

The previously recorded candidate was remote-base c34b8aa5, dirty-tree df2d52c20c6928bf74b7d1c4f230483189b5aa5b, three tracked unstaged paths and empty index; twelve draft methods remained unexecuted. Its current Windows existence, content, tree/index identities and semantic correctness remain NOT_VERIFIED by GitHub. Retain all protected worktrees/evidence and require exact local read-only revalidation before any reuse or modification. Never reset, clean, stash, overwrite, or recast a changed draft as the original candidate.

The user expressly wants BUG-SCHEDULER-01 correctness remediation to continue and has rejected routing the old October 8 wrapper-version-only diagnostic as the substantive next task. Historical prompt ytdlnisx/prompts/2026-10-08_GPT61_SOL_BUG_SCHEDULER_01_NORMAL_DH2_WRAPPER_STARTUP_PROBE.md remains immutable, but is superseded as next-action routing; no completion of that exact historical prompt is asserted.

## Reviewer-governed next action

Persist a new self-contained implementation assignment scoped to the confirmed accepted-terminal START/END failure/cancellation residual. Under exact live-local protected-candidate and current-host preflight, inspect the three-file draft and trace actual production producers, WorkInfo transitions, current-generation/Restore guards, DAO CAS, retry and retirement consumers; finish only the narrow source/test correction if needed. Validate distinct durable old ACCEPTED FAILED/CANCELLED pre-change state, and E2 supersession, rather than seeding all fixtures with the new writer. Require deterministic START, END, CANCELLED, settings/Restore E1->E2, successful retirement and absent/unfinished outcomes and all affected consumer/authority effects. Execute the preserved twelve-method focused gate and materially impacted suites only after valid build preflight. On first real environment, semantic or protected-state blocker STOP with evidence; do not repeat exhausted Windows probes. If all approved gates pass, publish only normal forward commits after fresh-tip and exact protected-state checks; perform full governing exact-new-SHA closure (historically 138 checks, or current dynamic governing set). Reviewer alone determines final finding closure; do not claim CLEAN from test outcomes.

CHAINED_CLOSURE_POLICY: REQUIRED for the planned production remediation wave (unless a material preflight/first-failure gate blocks it).
INDEPENDENT_LOCAL_EXECUTION: NOT_EXECUTED
