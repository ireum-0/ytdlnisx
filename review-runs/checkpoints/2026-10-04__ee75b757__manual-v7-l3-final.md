# Manual correctness review — ee75b757 — L3 concurrency/authority (final)

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: f2c013894776aa017b9c9652964e39ad6c9186f1
review_parent_sha: 20e30b09c0220f391c5506ec8955532a06b77b38
intermediate_checkpoint: review-runs/checkpoints/2026-10-04__ee75b757__manual-v7-l3-intermediate.md
intermediate_commit: 20e30b09c0220f391c5506ec8955532a06b77b38

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
overall_verdict: NOT_CLEAN
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05
new_finding_ids: NONE
independent_execution: NOT_EXECUTED

## Findings

BUG-UPDATER-04:
- status: CLOSED / closure preserved at ee75b757.
- current source routes ordinary yt-dlp execution through YtdlpRuntimeAuthority consumer authority and mutation-classified/updater execution through exclusive mutation authority.
- anonymous mutation requests receive mutation-scoped identities before native execution.
- durable mutation publication debt is published, recovered fail-closed, validated, and retired only after exact runtime usability proof.
- ordinary consumers receive --no-update fencing and mutation-owned custom self-update avoids recursive consumer admission.
- sampled production callers are background/IO; the authority rejects main-thread waiting.
- no direct production bypass of the changed UpdateUtil/YTDLPUtil execution boundary was established.
- no AB/BA lock order between Restore admission and runtime authority was established.

BUG-UPDATER-02:
- remains OPEN P2.
- BackupSettingsUtil still treats updater generation/committed/pending authority carriers as portable.
- UpdateUtil still accepts persisted committed generation/source as runtime provenance.
- the ee75b757 runtime-authority correction does not close Restore/startup/queued-deferral convergence.

BUG-UPDATER-03:
- remains OPEN P2.
- BackupRestoreParser.validateSettings() still accepts String values without a key-specific nonblank ytdlp_source contract.

BUG-HISTORY-05:
- remains OPEN P2.
- valid duplicate cleanup still transfers keyword assignments but deletes duplicate playlist memberships without materializing their union on retained History.

Candidate not promoted to a finding:
- process death during custom self-update / partially published runtime recovery remains NOT_VERIFIED.
- current publication debt fails closed and ordinary updater flows retain durable pending source/generation responsibility.
- exact custom self-update restart convergence from a partially corrupt executable was not proven either safe or defective.
- no canonical count impact and no BUG-UPDATER-04 reopen is claimed from unsupported evidence.

Debug/test package isolation:
- remains a separate test-safety follow-up.
- it is not a canonical production correctness root and does not change P0/P1/P2.

## Trigger map

- shared yt-dlp generation / Module E: PASS for BUG-UPDATER-04 concurrency and publication fencing; custom process-death repair candidate remains NOT_VERIFIED without a new finding.
- concurrency/live-owner matrix: PASS for the changed updater-vs-consumer contract.
- thread affinity / blocking wait: PASS on traced production entrypoints; no reachable main-thread wait was established.
- lock order: PASS on traced Restore/updater/consumer paths; runtime authority is not nested with Restore admission across long native work.
- Module C external representation: FAIL due BUG-UPDATER-02/03.
- Module F persisted generation/provenance: FAIL due BUG-UPDATER-02.
- Module H persisted executable configuration: FAIL due BUG-UPDATER-03.
- destructive relationship preservation: FAIL due BUG-HISTORY-05.
- L5 platform-contract baseline: PASS; debug/test package isolation is tracked separately.

## L1-L6 coverage current SHA

- L1 Durability & recovery: BASELINE / FAIL
- L2 Identity & provenance: BASELINE / FAIL
- L3 Concurrency & authority: DEEP / PASS
- L4 Destructive ownership: BASELINE / FAIL
- L5 Platform contract closure: BASELINE / PASS
- L6 Cross-feature semantic propagation: BASELINE / FAIL

primary_deep_lens: L3 Concurrency & authority
primary_deep_selection_reason: R1/R3 — ee75b757 materially changes and closes the shared yt-dlp runtime authority root.
remaining_not_yet_deep: L1,L2,L4,L5,L6
next_not_yet_deep_lens: L1 Durability & recovery
next_lens_selection_reason: R1 — BUG-UPDATER-02 remains an open recovery/convergence root.

## Review retrospective

This is the first manual multi-lens review pinned to implementation ee75b757.

The review did not reuse the prior 256a5cf5 L3 verdict. It traced the new runtime authority, native barrier, updater path, ordinary consumer path, custom self-update path, startup initialization, sampled UI/ViewModel/Worker callers, exact process identities, durable mutation publication, recovery selector and final runtime validation.

The exact implementation range is a strict forward two-commit descendant of 256a5cf5.

Existing closure execution evidence (compile PASS, focused 4/4 PASS, full runtime-authority class 16/16 PASS) was treated as historical evidence only. This manual run did not independently execute tests.

## Checklist evolution

No new Review Checklist or lens-selection rule is required.

Checklist v7 already supplied the needed rules:
- revocable idle/zero observations are not leases;
- positive live ownership must be preserved;
- shared-generation promotion needs authority at the actual mutation boundary;
- durable recovery must remain discoverable;
- thread affinity is part of synchronization correctness;
- tests do not replace source-semantic closure.

The custom self-update crash candidate remains an explicit NOT_VERIFIED cell rather than being converted into a new rule or speculative root.

## Checkpoint summary

manual_review_run_status: FINAL
implementation_sha: ee75b75786b8b6182dfc31946b6325b20294e73b
production_active_scope_p0: 0
production_active_scope_p1: 0
production_active_scope_p2: 3
open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05
bug_updater_04: CLOSED_PRESERVED
primary_deep_lens: L3
remaining_not_yet_deep: L1,L2,L4,L5,L6
next_lens_hint: L1
new_finding_ids: NONE
known_good_baseline: NOT_CREATED
independent_execution: NOT_EXECUTED

INDEPENDENT EXECUTION: NOT EXECUTED
