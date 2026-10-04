# Manual correctness review — ee75b757 — L3 concurrency/authority (intermediate)

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: f2c013894776aa017b9c9652964e39ad6c9186f1

implementation_sha: ee75b75786b8b6182dfc31946b6325b20294e73b
implementation_parent_reviewed_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_range_relation: STRICT_FORWARD_2_COMMITS

master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
master_plan_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
ledger_reference: 899328bc91e4008e39a658387396a0106c8666ec
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob: a3d864e29ad471eff4994446c0fb5c43a8905bf1

active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots:
- BUG-UPDATER-02
- BUG-UPDATER-03
- BUG-HISTORY-05

primary_deep_lens: L3 Concurrency & authority
primary_deep_selection_reason: R1/R3 — the new implementation SHA materially changes the shared yt-dlp runtime authority and closes BUG-UPDATER-04, so concurrency/authority is the highest-risk changed contract.
remaining_not_yet_deep:
- L1
- L2
- L4
- L5
- L6
next_not_yet_deep_lens: L1
next_lens_selection_reason: R1 — BUG-UPDATER-02 remains an open durability/recovery root.

## Baseline L1-L6

- L1 Durability & recovery: BASELINE / FAIL — BUG-UPDATER-02 startup/Restore/queued deferral convergence remains open on ee75b757.
- L2 Identity & provenance: BASELINE / FAIL — BUG-UPDATER-02 foreign updater provenance and BUG-UPDATER-03 blank restored source remain open.
- L3 Concurrency & authority: DEEP / IN_PROGRESS — new YtdlpRuntimeAuthority and mutation/process barriers are under end-to-end review.
- L4 Destructive ownership: BASELINE / FAIL — BUG-HISTORY-05 playlist-membership loss remains open.
- L5 Platform contract closure: BASELINE / PASS so far — no new production platform-contract root established; debug/test package isolation is a separate test-safety follow-up and is not a canonical production root.
- L6 Cross-feature semantic propagation: BASELINE / FAIL — updater restore/runtime and History final-effect roots remain open.

## Trigger map

- Shared yt-dlp runtime generation / Module E: TRIGGERED / IN_PROGRESS.
  Material delta introduces a fair read/write runtime authority, reader fencing, durable mutation publication markers, mutation-classified request handling, and exact native debt recovery.
- Concurrency/live-owner matrix: TRIGGERED / IN_PROGRESS.
  Need to prove every production yt-dlp consumer/mutator enters the same authority and no reachable main-thread caller waits on it.
- Thread-affinity / lock-order: TRIGGERED / IN_PROGRESS.
  YtdlpRuntimeAuthority rejects main-thread waits; sampled App startup, RuntimeDiagnostics, DownloadWorker, TerminalDownloadWorker, ResultViewModel and UI call paths enter from IO/background contexts.
- Durable publication/recovery / L1 + Module E: TRIGGERED / IN_PROGRESS.
  Runtime mutation publication debt is durable and fail-closed; crash-window convergence is being checked against UpdateUtil pending debt and dependency update behavior.
- External representation / Module C: FAIL — BUG-UPDATER-02/03 remain.
- Persisted generation compatibility / Module F: FAIL — BUG-UPDATER-02 remains.
- Persisted executable config / Module H: FAIL — BUG-UPDATER-03 remains.
- Destructive relationship preservation: FAIL — BUG-HISTORY-05 remains.

## Current source findings

### BUG-UPDATER-04 closure re-proof

Current source now:
- routes ordinary execute through YtdlpRuntimeAuthority.withConsumer();
- routes mutation-classified execute and UpdateUtil updater work through withMutation();
- assigns anonymous mutation requests mutation-scoped identities;
- persists runtime publication intent before mutation completion;
- requires exact native debt recovery/quiescence before mutation;
- validates the resulting runtime before retiring publication debt;
- injects --no-update for non-mutation consumer execution;
- prevents mutation-owned code from recursively entering ordinary consumer admission;
- forbids waiting on the Android main thread.

No direct production YoutubeDL.getInstance().execute/YoutubeDL.execute bypass was found in the exact changed UpdateUtil/YTDLPUtil source; DownloadWorker and TerminalDownloadWorker use YoutubeDLCompat.

The previously confirmed updater-vs-Download delete/copy race is therefore not reproduced by current exact source.

### Existing open roots reconfirmed

BUG-UPDATER-02:
BackupSettingsUtil still does not exclude updater generation/committed/pending authority carriers; current UpdateUtil still trusts committed generation/source tuple. Startup/Restore/queued deferral semantics were not changed by ee75b757.

BUG-UPDATER-03:
BackupRestoreParser.validateSettings() still accepts every String value without ytdlp_source-specific nonblank validation.

BUG-HISTORY-05:
deleteDuplicateHistoryGroups() still transfers keyword assignments but deletes duplicate PlaylistItemCrossRef rows without unioning duplicate-only memberships onto retained History.

## Pending candidate under review

Crash during destructive dependency update can retain runtime publication debt. Current code fails closed and UpdateUtil pending generation/source provides a retry owner for ordinary updater flows. A half-written runtime-file scenario has not yet established a distinct or reopened root because the exact recovery path and custom updater behavior require final causal classification.

## Execution evidence

No tests were independently executed by this manual review.
Existing ee75b757 closure evidence records compilation PASS, focused 4/4 PASS and complete runtime-authority class 16/16 PASS, but those results are historical evidence for this run.

independent_execution: NOT_EXECUTED
