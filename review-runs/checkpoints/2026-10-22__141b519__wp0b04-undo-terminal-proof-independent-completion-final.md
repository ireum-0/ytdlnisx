# WP0-B04 — independent review of Undo terminal-receipt source proof

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW_DIAGNOSTIC
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: 93535691a4dabd91ca406a69c291dbcc3e5ae81c
reviewed_implementation_sha: 141b5199592369ab953f52c55ebdb72ba300f1a4
implementation_changed: NO
private_final_report_commit: 492177133b3abe1cc367f503b52a1c591f081751
private_final_report_path: ytdlnisx/reports/2026-10-22__WP0B04__wp0b04-20261022-b917f445__FULL_REPORT.md
private_final_report_blob: d1994572356527f1707a4cf7d1dc2ee941b1d51e
task_instance_id: WP0B04_UNDO_TERMINAL_PROOF_20261022_141B519_01
prompt_origin_commit: ea79819bb271e8b6074c5d5e7897917c4c1e8d72
prompt_blob: 58c94387b839207220441d73fdda67394ad0db66
intent_commit: 1f00f84042656e25658346a0b4a9fdbc09337f06
intent_blob: 954eaade4197ebf08bca47c205685ff4cd2ee90e
claim_commit: 066e33e0c5feed1c9803f954cf59d8672d4a6f6b
claim_blob: 6627676b2fc39ea6501d20b2377c6dbd6af9d5bf
governing_protocol_blob: 67a7de97b31c8e44ecb524c1902c9e09fce9f678
governing_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
governing_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
previous_review_blob: c976c45214720d10f12b02901bd20f0a75a79b98
report_result: CONTRACT_GAP
independent_verdict: CONTRACT_GAP_CORROBORATED_WITH_FULL_ROOM_HISTORY_QUALIFICATION
full_implementation_ready: NO
first_stage_source_closed: NO
new_root_count: 0
canonical_root_status_changes: 0
P2_OPEN: 4
P2_AMBIGUOUS: 4
P2_UNRESOLVED: 8
repository_OPEN: 53
repository_AMBIGUOUS: 4
repository_CLOSED: 95
clean_basis: NOT_CLEAN
manual_L2_run: IN_PROGRESS_UNCHANGED
tests_run_by_reviewer: ZERO
tests_run_by_agent: ZERO_REPORTED
build_device: NOT_EXECUTED_REPORTED
production_code_modified: NO
new_implementation_prompt: NO
execution_handoff_updated: NO

## 1. GitHub-authoritative completion / history

The user supplied a private Full Report URL and commit. Independent readback returned exactly the report at the path above, blob `d1994572356527f1707a4cf7d1dc2ee941b1d51e`. The report's exact task ID/prompt SHA/source/review/protocol match the previously verified WP0B04 launch. Independently read the new intent and claim paths; same exact task, prompt, source and claimant identity, matching intent receipt included in claim.

GitHub compare from private launch-ready `b18098219037887687e8f381c5e55fe3904c0696` to `492177133b3abe1cc367f503b52a1c591f081751`: ahead=3, behind=0, only THREE added paths (one immutable intent, one matching claim, one new Full Report). Each exact commit has one parent equal to the immediately previous commit and only its specifically authorized single-file addition; private HEAD is the Full Report commit. This independently supports normal append-only history and no changed preexisting GitHub blob in this delta; the report's claim of 452 existing blobs preserved was NOT independently rehashed path-by-path, and claims about protected external/local worktrees remain agent-attributed.

The implementation tip remained `141b5199592369ab953f52c55ebdb72ba300f1a4` (no committed source outcome). The task's source-only stop boundary was reached. Its instance is **consumed**; historical stale `NEXT_CHAT.md` `PERSISTED_NOT_STARTED` does not permit relaunch or a bootstrap. This reviewer-owned completion review is not a manual trigger-`3` run, does not complete the separate manual L2 review and is not new agent-launch authorization.

## 2. Independent source-semantic corroboration

**VERIFIED_SOURCE: current schema and CAS are NOT terminal-receipt-aware.** `PendingUndoCarrier` holds token, kind, ownerId, authorityGeneration, resolutionIntent, presentationState, resolverGeneration, snapshotJson, timestamps; no actual effect disposition/terminal progress. `PendingUndoCarrierDao.get/getAll` return all rows with no lifecycle filter, `recordResolutionAttempt/recordResolution` check kind and intent but not a terminal generation/outcome, and `delete(token)` deletes on token alone. [PendingUndoCarrier.kt L7–47](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/models/PendingUndoCarrier.kt#L7-L47); [PendingUndoCarrierDao.kt L10–90](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/dao/PendingUndoCarrierDao.kt#L10-L90).

**VERIFIED_SOURCE: 20 direct deletion sites, plus upstream linked revocations.** A fresh read of exact pinned `DownloadRepository.kt` finds 19 direct `pendingUndoCarrierDao.delete` call expressions at lines 468,474,1972,1988,2007,2053,2075,2133,2164,3847,3854,3901,4063,4070,4105,4161,4168,4179,4224, and one at `LowQualityRedownloadRepository.kt:167`. The agent's scope count is thus corroborated for these two files; that alone is not an exhaustive proof of all transitive History/linked/Worker writers. The direct effects include no-child COMMIT, linked operation terminal/superseded, successful Undo restore, cancellation status/linked commit, targeted Restore clearing, and abandoned carrier cleanup. [DownloadRepository.kt L1966–2165](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1966-L2165); [L3816–4224](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L3816-L4224); [LowQualityRedownloadRepository.kt L88–187](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/LowQualityRedownloadRepository.kt#L88-L187).

**VERIFIED_SOURCE: Room effect and preference cleanup are not atomic.** `commitRemovalSnapshotLocked`, `restoreRemovalSnapshot`, `commitPendingCancellationLocked` consume exact carrier in a Room transaction; `restoreRemovalSnapshot` explicitly gates once-only reinsertion on carrier *presence*. `finishUndoResolverSuccess` subsequently drops process-local authority/snapshot and then best-effort `clearUndoIntentFallback`, which uses SharedPreferences commit and catches failure. The compatibility reader returns null for malformed/unreadable strings by `runCatching`. A Room effect can therefore commit while selected-looking fallback remains without durable actual-outcome receipt. [DownloadRepository.kt L1380–1444](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1380-L1444); [L1895–2012](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt#L1895-L2012). Merely retaining a terminal row with the old kind or using an undocumented sentinel would be unsafe while presence authorizes restore or recovery reads it as active. Preserve accepted intent independently of actual effect outcome (e.g. stronger parent/source may supersede it).

**SOURCE_SUPPORTED_INFERENCE / limitation: no unconditional full-observation indistinguishability proof.** Agent report corrected its own broad hypothesis: fallback-only accepted action with failed Room intent updates usually has a live preexisting Room carrier, whereas completed effect can delete it. With a healthy complete Room view, those two specific histories ordinarily DIFFER. The fallback-only projection or Room-read-error projection may be identical; both still require conservative refusal/UNKNOWN when the required proof cannot be read. Do NOT claim existence of a fully identical healthy database pair with opposite required outcomes, and do NOT infer completion from the absence of a carrier where provenance or stronger-authority resolution remains unknown. This qualification is material and retained by this independent verdict.

**SOURCE_SUPPORTED_INFERENCE / preferred candidate only:** existing unconstrained TEXT `kind` / `snapshotJson` can physically encode a distinct terminal kind and typed envelope without an SQL schema change. This does NOT prove all current consumers handle it safely. A truthful proof would require an exact accepted-source token/generation and original target envelope, Room-atomic effect+nonactionable receipt on every direct and stronger writer, active-only CAS and restore gate, raw fallback expected-value cleanup plus acknowledged durable removal, retained terminal discovery, generation-safe GC, legacy v1 UNKNOWN treatment, and complete reader/writer/recovery/Restore/Room58–65 test evidence. A new table/migration is neither established necessary nor authorized. Existing process-local R does not prove cross-process or all stronger linked writer exclusion. [RestoreMutationAdmission.kt L19–80](https://github.com/ireum-0/ytdlnisx/blob/141b5199592369ab953f52c55ebdb72ba300f1a4/app/src/main/java/com/ireum/ytdl/database/RestoreMutationAdmission.kt#L19-L80).

The report presents four storage/containment alternatives A–D and required negative/crash tests, explicitly unexecuted. The central gap has narrowed from abstract inventory to **exact-token, accepted-generation-specific, effect-atomic terminalization and all-consumer nonactionability/GC**. This gap is NOT a newly established independent shipped production wrong-effect root; it is an unproven future correction dependency of the already-OPEN P2 reconfiguration/Undo-Restore system.

## 3. Independently recommended bounded next proof

Before coding, source-close an explicit A-candidate terminal kind/typed-envelope lifecycle against each of the 20 direct delete callsites **and** all revokers and consumers beyond them. For each, name original accepted source generation, original owner and linked identity, true Room transaction that commits effect+receipt, effect disposition (including NO_EFFECT / SUPERSEDED with proof), whether old active snapshot is still actionable, recovery enumeration when only a terminal and fallback survive, exact fallback removal byte/generation check, ACK/GC CAS, old v1/Room58–65 UNKNOWN handling, and process R/other lock ordering.

Any pair of histories relying on old-v1 key and lost original snapshot that cannot be separated must remain UNKNOWN and refuse potentially destructive Restore; do not invent target/selected outcome from current numeric ID. Ask for a separate explicit approval before any new schema/table/global mutex or production implementation. If candidate A proves impossible, document a specific failure under a particular consumer/crash cut before evaluating B/C. No source-code patch, test/Gradle/device execution or new prompt is authorized solely by this completion checkpoint.

Retain all preexisting root dispositions, four OPEN P2 and four AMBIGUOUS P2, 8 total P2 unresolved and NOT_CLEAN; manual L2 remains IN_PROGRESS. WP0-B04 consumed; no canonical bootstrap. The active handoff still looks launch-ready for consumed WP0-B04 but is superseded by immutable matching report. A review-only completion assessment does not by itself authorize rewriting execution-routing fields in `NEXT_CHAT.md` (protocol §8.2). Future transition needs separate applicable authority.
