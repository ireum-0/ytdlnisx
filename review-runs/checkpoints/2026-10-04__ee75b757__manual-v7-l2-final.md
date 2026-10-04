# Manual correctness review — ee75b757 — L2 identity/provenance (final)

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 9748128c35525fb7dd3811f13a4a2df9f4548e7d
review_parent_sha: 06586361f0bec2d7f759540c89893c9effe39717
intermediate_checkpoint: review-runs/checkpoints/2026-10-04__ee75b757__manual-v7-l2-intermediate.md
intermediate_commit: 06586361f0bec2d7f759540c89893c9effe39717

implementation_sha: ee75b75786b8b6182dfc31946b6325b20294e73b
master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
master_plan_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
ledger_reference: 899328bc91e4008e39a658387396a0106c8666ec
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob: a3d864e29ad471eff4994446c0fb5c43a8905bf1

overall_verdict: NOT_CLEAN
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05
new_finding_ids: NONE
count_change: 0
independent_execution: NOT_EXECUTED

## Findings

BUG-UPDATER-02 remains OPEN P2.

Current backup emits updater generation/committed/pending preferences because
BackupSettingsUtil still classifies them as portable. Restore normalization uses
the same predicate and both Merge and Reset consume the validated settings.
UpdateUtil.committedMatches() still accepts generation/source equality as runtime
proof.

Therefore cross-device generation equality remains false provenance. A source
backup and destination can independently use the same numeric generation while
representing different runtime histories. Imported desired+committed equality
can suppress required destination reconciliation.

BUG-UPDATER-03 remains OPEN P2 and its same-root schema mismatch is broader than
the previously recorded blank-String example.

BackupRestoreParser validates generic declared preference types but has no
key-specific ytdlp_source schema.

Consequently:
- blank String ytdlp_source is accepted although UpdateUtil rejects a present
  blank source;
- ytdlp_source declared as Int with a parseable value also passes parser
  validation and can be persisted by Merge/Reset, while UpdateUtil consumes the
  same key through String preference semantics.

The correction boundary is therefore:
present ytdlp_source must satisfy the updater's exact key-specific storage/value
contract before any restore mutation. This is the same BUG-UPDATER-03 root, not
a new finding.

BUG-HISTORY-05 remains OPEN P2 on fresh baseline source: valid duplicate cleanup
still deletes duplicate playlist cross-refs without materializing the union on
retained History.

BUG-UPDATER-04 remains CLOSED; no current L3 bypass was established.

## Trigger/module status

- Module C external representation/provenance: FAIL — BUG-UPDATER-02/03.
- Module F persisted generation identity: FAIL — BUG-UPDATER-02.
- Module H executable configuration contract: FAIL — BUG-UPDATER-03.
- Module E shared runtime generation: PASS for the closed BUG-UPDATER-04 contract.
- destructive relationship preservation: FAIL — BUG-HISTORY-05.
- platform baseline sampled current source: PASS.

## L1-L6 coverage for ee75b757

- L1 Durability & recovery: BASELINE / FAIL
- L2 Identity & provenance: DEEP / FAIL
- L3 Concurrency & authority: DEEP / PASS
- L4 Destructive ownership: BASELINE / FAIL
- L5 Platform contract closure: BASELINE / PASS
- L6 Cross-feature semantic propagation: BASELINE / FAIL

primary_deep_lens: L2 Identity & provenance
primary_deep_selection_reason: R1/R5 — two of three open roots directly occupy identity/provenance, and frozen-basis L1 evidence already exists without changing manual lens accounting.
remaining_not_yet_deep: L1,L4,L5,L6
next_not_yet_deep_lens: L1 Durability & recovery
next_lens_selection_reason: R1 — BUG-UPDATER-02 still owns unresolved startup/recovery convergence.

## Review retrospective

This is a new same-SHA manual run. The previous ee75b757 manual L3 run was FINAL.
No resumable manual IN_PROGRESS run existed.

At trigger start a separate debug/test package-isolation implementation agent was
active, so review initially continued only on CLEAN_REVIEW_BASIS without
inspecting that active wave. The agent later stopped on its authorized environment
boundary without moving the implementation ref. Once IMPLEMENTATION_AGENT_CURRENTLY_WORKING
became NO, this manual run was opened on the unchanged ee75b757 basis.

The run directly reread current backup portability, restore normalization,
Merge/Reset settings publication, UpdateUtil desired/committed consumers, and
fresh baseline samples for L1/L4/L5/L6.

Existing preference tests cover generic malformed values but not updater-specific
source storage type/value validation or cross-device updater provenance.

Duplicate settings keys were considered but no distinct production failure was
established; that candidate remains NOT_VERIFIED with no count impact.

No tests were independently executed.

## Checklist evolution

No new checklist or lens-policy rule is required.

Existing v7 rules already require:
- external identity and provenance must not be inferred from locally meaningless
  numeric equality;
- executable persisted configuration must satisfy the authoritative consumer's
  schema before mutation;
- restore normalization must close producer/carrier/consumer contracts rather
  than defer malformed state to later consumers.

BUG-UPDATER-03's remediation contract should explicitly include wrong declared
storage type in addition to blank String source.

## Checkpoint summary

manual_review_run_status: FINAL
implementation_sha: ee75b75786b8b6182dfc31946b6325b20294e73b
production_active_scope_p0: 0
production_active_scope_p1: 0
production_active_scope_p2: 3
open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05
primary_deep_lens: L2
remaining_not_yet_deep: L1,L4,L5,L6
next_lens_hint: L1
new_finding_ids: NONE
known_good_baseline: NOT_CREATED
independent_execution: NOT_EXECUTED

INDEPENDENT EXECUTION: NOT EXECUTED
