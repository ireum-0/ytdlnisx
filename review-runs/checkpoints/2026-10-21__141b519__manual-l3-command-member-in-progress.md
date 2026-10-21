# Manual correctness review 141b519 — command-member authority, continuation

date: 2026-10-21
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
checkpoint_status: IN_PROGRESS
manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 82cf949a6de745d1b4e678ec379034c8a0eb9fde
review_expected_parent: bdbe6bbc8b950da9e72d27b71d518f408cd7a04e
pinned_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
pinned_master_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
applicable_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
review_mode: SAME_PINNED_MANUAL_RUN_CONTINUATION
primary_deep_lens: L3_CONCURRENCY_AND_AUTHORITY
root_id: BUG-FORMAT-SOURCELESS-COMMAND-MEMBER-01
root_status: OPEN
root_severity: P2
new_semantic_root_delta_from_prior_checkpoint: +1
root_relation: DISTINCT_FROM_BG01_SELECTED_SCOPE_AND_FROM_BG03_BATCH_FAILURE_TRUTH
independent_execution: NOT_EXECUTED
source_changed: NO

## Second current production wrong-effect root: valid URL-less command member is mistaken for unproven payload

Original producer: `DownloadCommandFragment.kt:96-98` supports local .txt command entry by transforming the input into a command `Format` and intentionally blanking `downloadItem.url`. A command `DownloadItem` with blank URL is legitimate and is not a corrupt persisted row.

Entry and durable carrier: `DownloadMultipleBottomSheetDialog.kt:372-381` requests `DownloadViewModel.continueUpdatingFormatsOnBackground` (2923-2939) for selected processing items; `FormatBatchHandoff.capture` and `stage` (45-105) transition captured valid Processing rows to Saved and construct member authority including `url==""`, with durable `format_batch_payloads.authority` and `FORMAT_BATCH` handoff. `FormatRequestPolicy.capture` (46-57) already explicitly permits command types and records chosen command format, and `FormatRequestPolicy.choose` (19-20) handles non-audio/video with stored `command`.

Consumer: `FormatBatchHandoff.members` lines 115-121 requires every member pass `FormatAuthority.proven`. The predicate `FormatAuthority.kt:91-92` insists `member.url.isNotBlank()` without an exception for `DownloadType.command`; legitimate command member with blank URL necessarily fails, including when it is unselected but part of the all-processing captured bundle. In `UpdateMultipleDownloadsFormatsWorker.kt:52-59`, the exception is caught as `Unproven member payload` and calls `unprovenResult`; `FormatRecoveryIssues.carrierDispositionWithinTransaction` (100-120) creates durable user-action-required issue, holding the FORMAT_BATCH carrier, with no selected format publications.

Specific trigger: at least eleven Processing command/download rows with missing formats to expose the UI's background choice at `FormatSelectionBottomSheetDialog.kt:190-196`, including at least one legitimate command row whose URL was blanked from .txt input. An all-command capture, or a mixed capture of one URL-less command plus selected regular video, reaches this same proof failure. Selection of only a regular video does not avoid the issue because `stage` stores all captured members; `members` validates all. A single command-only batch staged directly through production ViewModel/DAO is another entry shape independent of the UI threshold.

Wrong effect: a previously valid user-authored command becomes Saved; the handoff is stuck requiring manual acknowledgement as if its origin were corrupt, and a selected unrelated regular row can lose batch progress because an unselected command failed a universal proof predicate. This is different from BG01's unjustified unselected status mutation: **even a correctly scoped chosen source-less command** cannot pass this authority predicate. Likewise it is different from BG03's partial network/item exception that is honestly pending; the whole valid payload is misclassified as malformed authority.

Proof classification: concrete source control-flow OPEN, not a reproduced Android run. Invariant: a member authority may require exact type-dependent source provenance, but an intentional legitimate blank command URL must not become automatically unproven solely for being blank. A fix requires explicit command authority and preserving original command execution/edit identity; never grant ID-only authority.

Deterministic regression: insert real command Processing member with blank URL and valid command Format; stage using production ViewModel, then invoke member parse/worker request with valid exact handoff and assert no `UNPROVEN_FORMAT_PAYLOAD` issue and truthful selected/other state; alongside mixed selected video + unselected command, deletion/recreate ID, malformed payload and missing identity cases. Existing recorded 41 format Android methods do not include a command-empty-URL acceptance test.

This checkpoint adds a second new current root to the same run. Do not reuse this evidence to double-count BG01 selected-membership, BG03 partial failure, or the independent global first-session mismatch root; final count reconciliation is deferred to FINAL. No implementation or private handoff write is authorized by this review.
