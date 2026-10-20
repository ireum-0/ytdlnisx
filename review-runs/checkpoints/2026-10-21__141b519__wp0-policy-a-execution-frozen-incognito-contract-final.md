# WP-0 policy A — frozen incognito at execution admission (FINAL contractual preflight)

checkpoint_status: FINAL
checkpoint_kind: INDEPENDENT_REVIEWER_CONTRACT_DECISION
manual_review_run: NO
review_parent_sha: 352b45c4e374bc7a5690c593901465c1b0256748
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_implementation_tree: 4903b4ef3949e15d2f2967e474805f54e3385a0e
pinned_plan_sha: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_sha: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
prior_wp0_blob: 4e81a6888fbeb0c82f27d271e9fbc16be7223edc
user_approved_policy: A_EXECUTION_FROZEN
root_id: BUG-INCOGNITO-01
root_status: OPEN
finding_count_delta: 0
canonical_active_open_p2: 8
canonical_active_ambiguous_p2: 0
canonical_clean: NO
source_modified: NO
device_tests_executed: NO
new_implementation_prompt_authorized_by_user: YES_SEPARATE_USER_REQUEST
implementation_or_device_launched: NO

## A. Exact product decision

The user explicitly selected policy **A: incognito is frozen when a download execution starts**. The exact attempt-level privacy, logging, producer fingerprint and History behavior is determined by the accepted/claimed download's incognito field. A later UI action must not claim to change an already running attempt; it must not silently mutate an Active/PostProcessing attempt or promise to retroactively remove an existing log/History/effect. No forward-effective live privacy contract is authorized.

The pre-execution configuration flow may still change incognito for **current, eligible Processing** rows. Non-selection UI mode targets all current Processing; explicit-selection mode targets only the distinct user-selected IDs that are currently Processing. Explicitly selecting zero entries is a no-op, never a bulk operation. An empty eligible set has all-incognito=false and an actual change count of zero. A mixed set is **not** all-incognito. The toggled UI must report the truthful committed result; do not use stale icon alpha, a prior UI selection, or a launch of asynchronous work as success.

If a selected ID has transitioned from Processing to Queued, Scheduled, Active, PostProcessing, Deleted, or a different incarnation before the authorized statement/transaction, the update must not affect that now-ineligible row. The state classification and mutation are to be bounded to the same admitted effective set, under the existing Restore mutation admission contract. If full-incarnation/selection targeting introduces an unapproved new durable identity primitive, STOP and return evidence; do not silently broaden the production model. A retry/reconfigure that creates a new attempt may carry a newly committed configuration only through existing valid preparation/admission paths. Do not overwrite the old owner's privacy/fingerprint or corrupt existing historical outputs.

## B. Direct source boundary proving the correction can be scoped

At exact source 141b519:
- [DownloadDao Processing configuration methods](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/DownloadDao.kt#L143-L195) segregate `status='Processing'` configuration from running states.
- [DownloadDao claimed execution](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/DownloadDao.kt#L446-L511) transitions `Queued`/`Scheduled` to `Active` and materializes the execution-ID-owned Download row atomically in a Room transaction.
- [Worker admission to runner](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L1366-L1416) binds the claimed `DownloadItem` to the attempt.
- [Worker privacy in fingerprint](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L1540-L1553), [logging](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L2556-L2574) and [History](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt#L3480-L3490) uses that held `downloadItem.incognito`. The A decision intentionally preserves those boundaries.
- [ViewModel](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt#L3105-L3120) currently calculates existential `>0` rather than nonempty-all, while [DAO](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/DownloadDao.kt#L1238-L1248) updates all selected IDs regardless of status.
- [Adapter](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/adapter/ConfigureMultipleDownloadsAdapter.kt#L64-L79) collapses explicit empty to null; [Dialog](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/downloadcard/DownloadMultipleBottomSheetDialog.kt#L386-L399) and [incognito click](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/ui/downloadcard/DownloadMultipleBottomSheetDialog.kt#L526-L549) use late off-main selection and icon state.

The existing worker claim/status boundary makes a targeted **Processing-only** edit plausible. Its real concurrent ordering with transitions, application restore admission, reconfiguration, resumed/requeued execution and previously persisted state must still be verified by the separately authorized implementer and direct regression tests; source review here did NOT independently execute them.

## C. Approved narrow implementation envelope — separate prompt required

**In-scope for WP-1:** incognito-only selection-mode capture, correct nonempty-all predicate over the same current eligible set, a Processing-scoped existing-ID conditional DB mutation (prefer an atomic Room transaction where necessary to couple read/write effects), accurate committed-result UI, and direct positive/negative/restart/History/log/fingerprint evidence. Preserve existing global selection behavior for other download actions, existing worker execution frozen semantics, Restore admission, all supported persisted rows, prior accepted closures and unrelated BG01-05 contracts.

**Out-of-scope absent new authorization:** BG04 Result writer correction, DownloadWorker fingerprint/history/logging policy rewrite, schema/migration/new durable authority, new emulator/device operations, protected worktree cleanup, CI changes and unrelated remediation. Do not promise in-flight privacy changes. If a user action races a Processing-to-Queued/Active transition, reject or skip the no-longer-eligible row and surface truthful effect status; never write stale captured state to an Active owner.

**Required focused cases:** selection off/all Processing; explicit empty no-op; mixed/nonempty-all/zero; duplicates, nonexistent IDs, stale/recreated IDs, status-transition to queued/active, concurrent selection edits, DB/Restore rejection; committed UI and false-success prevention; pre-claim configuration carried into worker History/log/fingerprint; post-claim attempted toggle cannot change active attempt History/log/fingerprint, even across restart/requeue. Any in-flight test must use actual queued→claimed production path, not a fake worker-held object.

**Execution gate:** permitted safe source/build/JVM/owned tests may run under a separately persisted implementation prompt. Android instrumentation is **not** implicitly authorized by this contract decision. Current V55 AVD guest/remote runner state remains NOT_VERIFIED; future Android target/install/app-data scope must be explicitly authorized and proved. If mandatory final-SHA Android verification is unavailable, the prompt shall report exactly which methods were NOT_REACHED and not declare CLOSED or CLEAN. Do not split an already-authorized/safe chained verification step artificially; do not cross an unapproved device boundary.

**Current official finding:** BUG-INCOGNITO-01 OPEN; eight active P2 unresolved. No new root, no status/count adjustment, no canonical Clean basis. This checkpoint is a source/product contract decision and does not itself launch the implementation agent.
