# Manual correctness review — 256a5cf5 — L2 provenance deep (intermediate)

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 13275c98ee6a0803be60757ef14f32e3e2b43bb1
review_parent_sha: 13275c98ee6a0803be60757ef14f32e3e2b43bb1
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c

master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3

## Resume/new-run decision

The immediately preceding manual run for this exact implementation/governance basis is FINAL, not resumable.
No implementation agent is active in the current private handoff.
This is therefore a new same-SHA manual review run, preserving the prior FINAL evidence while recomputing triggers and reviewing source semantics again.

## Current independent state

Verdict remains NOT_CLEAN while this run is in progress.

Existing canonical production root:
- BUG-UPDATER-02: OPEN P2 / SAME-ROOT residual.

No new finding ID has been established in this run.

## Source-semantic evidence completed

Current exact-source trace confirms the existing updater provenance residual:

1. `BackupSettingsUtil.isPortablePreferenceKey()` does not exclude:
   - ytdlp_source_generation
   - ytdlp_committed_source_generation
   - ytdlp_committed_source
   - ytdlp_committed_result
   - ytdlp_pending_source_generation
   - ytdlp_pending_source
2. Backup settings therefore serialize those destination-local updater authority carriers.
3. `BackupRestoreParser.normalize()` accepts them because it delegates portability to the same predicate.
4. Merge restore applies accepted settings directly and durably to destination SharedPreferences.
5. Reset restore clears/replays portable settings, so the same imported authority can replace destination-local values.
6. `UpdateUtil.committedMatches()` proves only desired generation/source equality against those SharedPreferences carriers.
7. `UpdateUtil.updateOnStartup(false)` may return ALREADY_UP_TO_DATE when imported committed state matches imported desired state and no imported pending marker exists.
8. `MainActivity` invokes `updateOnStartup(automaticYtdlpUpdates)`, so the false provenance can suppress destination runtime reconciliation after restore.

This is the already-canonical BUG-UPDATER-02 root, not a new semantic root.

## Trigger map

- Module C — external representation / backup portability: FAIL, existing BUG-UPDATER-02 root.
- Module F — persisted generation/provenance compatibility: FAIL, existing BUG-UPDATER-02 root.
- Module H — persisted executable configuration fan-out: OPEN; source intent remains portable while runtime-authority carriers must be destination-local, and all write/import/consumer paths still need closure.
- Module E — shared runtime/resource replacement: OPEN; updater pending/committed generation ordering and restart behavior still need end-to-end closure.
- Restore process-death durability and preference/Room journal replay: BASELINE source review in progress; no second root established yet.

## Lens coverage current SHA

- L1 Durability & recovery: BASELINE / IN_PROGRESS
- L2 Identity & provenance: DEEP / IN_PROGRESS
- L3 Concurrency & authority: BASELINE
- L4 Destructive ownership: BASELINE
- L5 Platform contract closure: DEEP from prior same-SHA run
- L6 Cross-feature semantic propagation: DEEP / FAIL from prior same-SHA run

primary_deep_lens: L2 Identity & provenance
primary_deep_selection_reason:
R1 — the current confirmed residual is directly an identity/provenance failure: foreign source-device updater generation and committed/pending provenance are accepted as proof about the destination runtime. L2 therefore outranks the prior continuation hint while this root remains open.

remaining_not_yet_deep:
- L1
- L3
- L4

next_not_yet_deep_lens:
L1 Durability & recovery

## Remaining scope

- complete L2 producer/carrier/consumer/recovery/final-effect trace for updater source/generation/pending/committed state;
- inspect all restore/startup and manual source-selection paths needed to prove the narrow correction boundary;
- close Module H and Module E blocker-relevant cells or leave exact gaps;
- finish BASELINE L1-L6 recount against the current exact source;
- reject or establish any additional root only with production proof;
- fresh-check implementation and review refs before the FINAL checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
