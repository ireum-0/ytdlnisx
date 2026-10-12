# BG-01/BG-02 — current source correction boundary and authorized prompt preflight

date: 2026-10-12
checkpoint_kind: REVIEWER_INDEPENDENT_SOURCE_BOUNDARY_AND_PROMPT_PREFLIGHT
checkpoint_status: FINAL
manual_review_run: NO
review_expected_parent: 0c69d6e4c70e08beac37d637fc19c27e30d9b3ac
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
governing_checklist_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
applicable_protocol_blob: 4107f1760d18f4a273ea25fb212b014701bc3bda
source_changed: NO
independent_execution: NOT_EXECUTED
canonical_root_delta: 0

## Current production source and prior correction reuse
The immutable `2026-10-06__db29f63__remaining-download-blockers-correction-boundaries.md` establishes distinct BG-01 captured-bundle lifecycle authority and BG-02 durable format-batch WorkManager handoff. Both remain OPEN on `db339e1`. Actual current source blobs: `DownloadViewModel.kt=9c817cec48f124782256cb8be4bc3d8385d218bc`, `DownloadDao.kt=e0b260e5d23f5d1f3b10fc51880bbbc5465ee9c1`, `WorkManagerHandoffCarrier.kt=d3ebded2745cfbd872c0d8b2334257613b5936af`, `WorkManagerHandoffCarrierDao.kt=21ddd491654512237eb7be7f50a7533c6f02abb2`, matching the old producer preflight. `WorkManagerHandoffRecovery.kt=81c17842377ab4800317da83e25d6cb0fc56d31f` has since changed for scheduler, so its new behavior must be audited before reuse.

BG-01: the producer captures `allProcessing`, selects `ids`, then performs an unbounded status-wide SQL UPDATE; a later Processing C outside the captured A/B bundle can be changed without worker/completion ownership. Preserve intentionally captured-but-unselected B; use exact captured IDs with current-row eligibility, refusal/barrier and no-recreation guards. Selected-only Saved is NOT the established contract.

BG-02: Saved publication precedes WorkManager `enqueueUniqueWork` without awaiting `Operation.result`; no exact durable batch payload/generation/request identity survives failure/crash. Durably stage lifecycle change plus recoverable exact format owner, observe acceptance, retry with a new exact UUID under the same semantic generation, and converge across restart/idempotent replay.

The previous `2026-10-06_GPT61_SOL_FMT_PRODUCER_BG01_BG02.md` is SUPERSEDED; old SHA/protocol/review anchor and its reported local 51 JVM/70 Android results cannot count as current exact published closure. Preserve its separate 16-file unstaged draft, all five other protected candidate worktrees, staging, ignored caches, APKs and evidence. Do not regress CLOSED WINDOW-01 or RESTORE-01. BG-03/04/05 and INCOGNITO-01 remain distinct OPEN roots.

## Authorized implementation-agent boundary
The user's explicit new prompt-authoring request authorizes an updated English BG-01/BG-02 producer/handoff remediation instruction and per-root exact-final-SHA execution closure in one continuous wave. Tests must prove real saved-state/durable-owner transaction, selected/other bundle membership, races, enqueue failures, restart, prior persisted-carrier compatibility, exact request CAS, and scheduler/Restore nonregression. Do not broaden the open worker outcome/configuration/cancel roots. Current protocol Section 6.2 requires an immutable full report-only push to `ireum-0/private:ytdlnisx-review/ytdlnisx/reports/` for success/failure/STOP; independent reviewer retains root decisions. No canonical status/count changes at this preflight.

next_action: REVIEWER_PERSIST_FINAL_BG01_BG02_PROMPT_THEN_VERIFY_LAUNCH_READY_HANDOFF
