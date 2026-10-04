# Manual correctness review — ee75b757 — L2 identity/provenance (intermediate)

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 9748128c35525fb7dd3811f13a4a2df9f4548e7d

implementation_sha: ee75b75786b8b6182dfc31946b6325b20294e73b
implementation_tree_basis: unchanged_from_current_published_head

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
canonical_open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05

primary_deep_lens: L2 Identity & provenance
primary_deep_selection_reason: R1/R5 — two of three remaining canonical roots are updater identity/provenance contracts and L2 is not yet DEEP for this SHA.

## Baseline L1-L6

- L1 Durability & recovery: BASELINE / FAIL — BUG-UPDATER-02 startup/Restore/runtime-readiness/Download-recovery ownership remains open.
- L2 Identity & provenance: DEEP / FAIL — BUG-UPDATER-02 foreign runtime proof and BUG-UPDATER-03 key-specific source schema remain open.
- L3 Concurrency & authority: DEEP / PASS — BUG-UPDATER-04 closure remains preserved; no new L3 bypass established.
- L4 Destructive ownership: BASELINE / FAIL — BUG-HISTORY-05 still deletes duplicate-only playlist membership.
- L5 Platform contract closure: BASELINE / PASS — sampled exact-alarm/minSdk/Resume exported/ABI boundaries remain intact.
- L6 Cross-feature semantic propagation: BASELINE / FAIL — restore source/provenance and History relationship final effects remain open.

## L2 findings

### BUG-UPDATER-02

BackupSettingsUtil still treats destination-local updater authority keys as portable:
- ytdlp_source_generation
- ytdlp_committed_source_generation
- ytdlp_committed_source
- ytdlp_committed_result
- ytdlp_pending_source_generation
- ytdlp_pending_source

Current backup emits them; current restore normalization accepts them.
Merge and Reset both consume the same validated portable settings contract.
UpdateUtil.committedMatches() accepts desired generation/source equality against persisted committed generation/source as installed-runtime proof.

Cross-device numeric generation equality therefore remains false provenance.

Concrete state:
destination stable generation 8 + stable runtime;
source backup nightly generation 8 + committed nightly/8.
Restore can make desired and committed state both nightly/8 without proving the destination runtime became nightly; startup may treat it as already committed.

### BUG-UPDATER-03

BackupRestoreParser validates generic preference types but does not validate the key-specific ytdlp_source schema.

Known case:
- blank String source is accepted by restore;
- UpdateUtil rejects present blank source.

Additional same-root case:
- ytdlp_source declared as Int with value 1 passes generic Int validation;
- Merge/Reset can persist that key as Int;
- UpdateUtil consumes ytdlp_source as a String preference.

The canonical root is therefore key-specific restore/consumer schema mismatch, not only blank String acceptance.

Required source contract:
present ytdlp_source must be represented as String and satisfy the accepted source-value contract before any Merge/Reset mutation.

## Trigger/module status

- Module C external representation/provenance: FAIL — BUG-UPDATER-02/03.
- Module F persisted generation identity: FAIL — BUG-UPDATER-02.
- Module H persisted executable configuration: FAIL — BUG-UPDATER-03.
- Module E shared runtime generation: PASS for BUG-UPDATER-04 closure on this SHA.
- destructive relationship preservation: FAIL — BUG-HISTORY-05.
- platform-contract baseline: PASS on sampled current source.

## Test gap

Current preference tests cover generic malformed Int/Boolean/StringSet and unknown types, but not ytdlp-specific storage type/value validation.
Current restore tests do not prove updater authority keys are excluded from new backup/current/legacy/typed restore.
No focused regression proves cross-device generation collision cannot satisfy committedMatches().

new_finding_ids: NONE
count_change: 0
remaining_not_yet_deep: L1,L4,L5,L6
next_not_yet_deep_lens: L1 Durability & recovery
independent_execution: NOT_EXECUTED
