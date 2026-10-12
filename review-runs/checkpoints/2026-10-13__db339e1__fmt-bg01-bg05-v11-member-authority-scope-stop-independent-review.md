# V11 independent review — BG04 member-authority ABA scope stop, no source mutation

date: 2026-10-13_KST
checkpoint_kind: IMPLEMENTATION_AGENT_MATERIAL_SCOPE_STOP_INDEPENDENT_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: c5265c77360f24d46c69e1636523807123e7d73d
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
applicable_protocol_blob: 4107f1760d18f4a273ea25fb212b014701bc3bda
governing_v10_review_checkpoint: review-runs/checkpoints/2026-10-13__db339e1__fmt-bg01-bg02-v10-unpublished-candidate-semantics-data-safety-independent-review.md
governing_v10_checkpoint_blob: add0e01ab731d0e92c76b161cc4a47e7f3ddfe57
execution_v11_prompt: ytdlnisx/prompts/2026-10-13_GPT61_SOL_FMT_BG01_BG05_V11_GATED_SINGLE_WAVE_REMEDIATION_EXACT_SHA.md
execution_v11_prompt_blob: b006b8708c6d53eb40f41f745b7d13b5f9fa4117
immutable_v11_full_report: ytdlnisx/reports/2026-10-13__FMT_BG01_BG05_V11_GATED_SINGLE_WAVE__fmt-bg01-bg05-v11-20261012-77818bc9__FULL_REPORT.md
immutable_v11_full_report_blob: 985b6b6d4ff5dccfb26504f05da03c86a09fbac1
private_report_only_archive_commit: 9a0b893dc44e7d9645fbeff286ebabdcfdf34815
private_archive_status: VERIFIED_NORMAL_FF_ONE_NEW_REPORT_ONLY
historical_private_handoff_blob: 0489973d8c0be3749ba9c5577d4504bc060351ca
implementation_git_head_changed: NO
protected_local_candidate_files: 17_NINE_MODIFIED_EIGHT_NEW_AGENT_REPORTED_UNCHANGED
protected_candidate_manifest_sha256: b8ef5fb31ebdc77141fc38e4ea0e90e86cd61d4eece5198ae95532be02171322
protected_index_staging: EMPTY_AGENT_REPORTED
protected_physical_index_sha256: 177c240e43c04fdd1a88d6c649d8dc5967aeda6147dd2670b5a7aae4f5c9e37b
builds: 0
implementation_source_edits: 0
implementation_commits: 0
implementation_pushes: 0
adb_queries: 0
device_installations: 0
android_instrumentation_attempts: 0
android_executed_methods: 0
jvm_executed_methods: 0
git_diff_check: NOT_REACHED
focused_and_105_method_gates: NOT_REACHED
exact_final_sha_closure: NOT_REACHED
first_material_stop: GATE_1_BG04_MEMBER_AUTHORITY_PERSISTENCE_CONTRACT_SCOPE
historical_sealed_manifest_entries_verified: 1954_AGENT_REPORTED_ZERO_MISMATCH
new_v11_evidence_entries: 14_AGENT_REPORTED_ZERO_MISMATCH
v8_in_v7_additions: 141_PRESERVED_AGENT_REPORTED
v8_live_stdout_growth: HISTORICAL_PLUS_188_BYTES_FROM_V10_NOT_NEW_V11_ATTRIBUTION
v8_live_logs_whole_file_identical: NOT_ASSERTED
agent_guest_data_preservation: NO_DEVICE_ACCESS_OR_MUTATION
data_safe_candidate_update: NOT_VERIFIED
candidate_exact_source_independently_github_inspected: NO_UNPUBLISHED_DIRTY_LOCAL_ONLY
base_download_repository_blob: 8125ef4b1a09872c83be876e4f98f14981553246
base_download_dao_blob: e0b260e5d23f5d1f3b10fc51880bbbc5465ee9c1
base_result_dao_blob: d6c22f6fd6c77f107dc134e7b189269163ac83df
base_result_model_blob: e0265756c7ab31e799a1ce694b98e35b3724c5f2
base_format_worker_blob: 3dee9181140cf760c9cde9dc651e5ea6b350d07c
bug_format_bg01: OPEN_P2
bug_format_bg02: OPEN_P2
bug_format_bg03: OPEN_P2
bug_format_bg04: OPEN_P2
bug_format_bg05: OPEN_P2
bug_incognito01: OPEN_P2
canonical_root_delta: 0
active_remediation_confirmed_open_p2: 6
active_remediation_ambiguous_p2: 0
active_remediation_unresolved_p2: 6
repository_wide_verdict: NOT_CLEAN
independent_execution: NOT_EXECUTED

## 1. Verified GitHub provenance and bounded stop

The reviewer fetched exact GitHub refs and immutable V11 Full Report blob `985b6b6d4ff5dccfb26504f05da03c86a09fbac1` at private head `9a0b893dc44e7d9645fbeff286ebabdcfdf34815`. GitHub comparison against `b0597c7ca5664c50de01f13fc08f6e5667c075bc` shows exactly one normal forward commit, adding only one immutable V11 Full Report (177 added lines, zero deletions). Independently re-read persisted V11 prompt, protocol and governing V10 FINAL checkpoint; implementation, review (before this checkpoint), plan and ledger tips were unchanged. There is no new implementation SHA. V11 reported Gate0 validated then a material Gate1 scope stop. No runtime/device/compilation attempts were reported.

The seventeen candidate files are UNPUBLISHED local dirty work. The agent's filename/hash and line-specific candidate descriptions are usable attributed evidence but are not independently GitHub-inspected production. The reviewed GitHub source at `db339e1` remains authoritative for confirming extant production wrong-effect paths; a local uncommitted draft cannot close any existing root or establish tested committed-SHA behavior. Agent evidence can nevertheless identify a material contract ambiguity making proposed implementation unsafe.

## 2. Independently checked extant producer/consumer/authority edges

On the published implementation head, `DownloadRepository.kt:680-716` has a real producer `transitionFormatNotificationCandidateToProcessing` that admits a current Saved, blank-executionId/operationId, retryAttempt0 Download row under ordinary mutation + a worker execution lock, reloads it, and applies `updateForQueueIfSnapshot`. `DownloadDao.kt:754-783` checks the current state against the supplied snapshot at that instant and writes a copied row with `executionId=""`, but provides no monotonic persisted download incarnation or durable proof of every intervening transition. Therefore this producer can alter member lifecycle state without necessarily changing any of those blank identity fields. The existence of this genuine reclassification edge is directly independently verified from GitHub; the subsequent Saved return via the unpublished `FormatBatchHandoff.stage` is established only by the V11 protected-local-source report.

The published `UpdateMultipleDownloadsFormatsWorker.kt:46-82` reads a Download row and Result cache before a format fetch, then may write the earlier Result object with `resDao.update`, load the current Download, copy its **current** status/execution/issue fields into the prior snapshot, and call `updateIfExecutionOwned` with the newly read execution token. `DownloadDao.kt:697-715` validates this supplied token against the current row and may full-row `updateRaw`, but this does not prove that the initial format worker itself owned that token across the external fetch interval. Its blank fallback `updateWithoutUpsert` at `DownloadDao.kt:970-987` checks barriers/debt but not member incarnation. `ResultDao.kt:50-51` performs a general `@Update` without generation/freshness checks; `ResultItem.kt:14-36` carries no persisted format-write revision. These source facts confirm the current canonical BG04 stale-write authority root and the independently visible absence of a Result freshness witness. Existing BG03 swallowed per-item errors and BG05 class/tag-wide cancellation also remain on the same unchanged published worker; these are existing roots, not new ones.

The V11 report's concrete H1/R1 vs H2 member-identity ABA scenario is a plausible and material *candidate-specific* interleaving: H1 starts formats work, the same D is Saved -> Processing -> Saved under ordinary authorized producers, and blanks/operation/retry values can coincide at the endpoints. Exact H1 handoff+request UUID proves **which WorkRequest** is acting, not **which incarnation of D** it may still update. An endpoint-only equality check on repeated values cannot separate two such histories. **Reviewer disposition: stop is justified** because the currently authorized prompt forbids unreviewed shared durable ownership expansion and anti-ABA workarounds; a speculative current-snapshot comparison cannot satisfy the recorded contract. This does not prove schema65 or a new column is necessarily required, and the unpublished H2 stage path was not independently inspected on GitHub. The exact design is NOT_VERIFIED, not secretly approved.

The candidate's other-only completion notification, BG02 payload corruption/recovery, BG03 per-item success truthfulness, BG05 exact cancel target and post-Restore stale external side effects remain unresolved. Root counts do not increment for this BG04 subcase or the candidate BG01 notification residual.

## 3. Member-authority contract that must be independently reconciled before correction

Any separately authorized follow-up must first make explicit and verify:

- **Producers/renewal:** every path that stages Saved -> Processing, Processing -> Saved, queues retry/reconfigure, replaces/restores/imports/deletes/recreates Download/Result and advances/revokes an authority; do not assume exact identities never repeat. Specify the exact committed boundary at which member authority becomes different from the prior incarnation, including blank-token rows.
- **Durable discriminator or supported proof:** produce an observable persistent distinction for otherwise identical endpoints, or provide an exact source proof under all legal producers that no same-key old/new collision is possible. If no distinction can be established, STOP; do not approve a mere endpoint equality predicate. A per-WorkRequest UUID alone is not sufficient evidence of per-member authority.
- **Carriers and consumers:** connect the format batch's durable ownership record to each captured member or an equivalent existing genuinely durable revocation authority; validate at each Download and Result mutation and in final side effect/notification. Guard Result cache separately from Download writes, with the requisite freshness/source policy. Do not borrow a later executionId, broad-tag cancellation, or a process-local in-memory mutex as durable member proof.
- **Persistence/recovery/migration:** determine how old pre-change and oldest supported nonterminal writer states (at least historical schema61/63 and draft64) behave after upgrade/startup/retry/recovery/Restore; specify a safe limited migration/legacy admission or a concrete unsupported-case stop. If an incompatible schema/migration/shared ownership protocol is necessary, require explicit new scope before coding, not an implementation agent self-expansion.
- **Concurrency/negative evidence:** exact observed ordering of H1 network suspension, D reclassification, H2 staging, late R1 completion, Result write, Restore supersession and restart. A test must assert old H1 does **not** mutate the newer D or Result; R2/current owner is permitted and actual owed retry/convergence remains. Control fault seams by identity and lifetime; no test green by suppressing new work.

Proof is a bounded source-semantic contract review first; this checkpoint authorizes no particular implementation, schema version, data-state mutation, new prompt, install or test. The requirements are recommendations for an explicitly approved next work item, not speculative implementation instructions. The exact next feasible step is a reviewer-authored scope reconciliation of per-member durable authority and write-fencing with an evidenced producer/writer/recovery inventory. If existing authority suffices with exact source proof, a narrow existing-contract fix may be separately authorized; otherwise authorize a minimal new persisted contract with compatibility plan. Neither is automatically authorized by V11 stop.

## 4. Evidence preservation, execution limits and workflow

V11 reports fourteen new evidence records; 1,954 sealed historical manifest entries with zero mismatches; all original seventeen candidate files and empty physical index unchanged; other worktrees, caches and four preserved APKs unchanged. The known 141 V8-in-V7 additions remain; V10's extra 188 live-log bytes were already present, and old sealed-prefix snapshots were reportedly unchanged. These local machine/hash results are agent-reported only. They are consistent with a Gate1 no-write stop, but no independent host/device verification was performed. No code build, `git diff --check`, JVM/Android test, ADB, installation, source commit/push or exact-final-SHA execution gate was reached. `NOT_REACHED` is not PASS, FAIL or proof of blocked host process.

Canonical roots retain one status each: BG01/BG02/BG03/BG04/BG05 and INCOGNITO01 OPEN P2, six confirmed OPEN, zero AMBIGUOUS, six unresolved. V11 added no independent production root or published correction; repository remains NOT_CLEAN. A candidate-only concern does not create a duplicate BG04 root.

The private `NEXT_CHAT.md` blob `0489973d8c0be3749ba9c5577d4504bc060351ca` retains V11 `PERSISTED_NOT_STARTED`, `PROMPT_EXECUTION_STATUS=PERSISTED_NOT_STARTED`, and owner IMPLEMENTATION_AGENT, although the immutable V11 report establishes execution and final STOP. This recorded launch-ready capsule is STALE and must NOT cause V11 to be launched again. Under protocol Section8.2, independent review-only authority permits this append-only reviewer checkpoint, not changes to launch-owner, prompt-status, task/scope, device/code authorizations or replacement prompt. No canonical bootstrap for completed V11 may be emitted until a separately authorized coherent new state is reconciled. Do not repair it by inventing an agent-start signal or silent prompt replacement.

INDEPENDENT EXECUTION: NOT EXECUTED
