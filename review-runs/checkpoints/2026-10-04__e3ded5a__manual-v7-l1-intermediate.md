# Manual correctness review — e3ded5a — L1 durability/recovery (intermediate)

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: e28a67489ae6a71d59150417ba56b5d4edbfcf9f

implementation_sha: e3ded5accb08064d84f5d2ff1d2ea84b61cf6254
implementation_tree: 64c62411c0d458a4296333b2d1f12d91c1a2f536
implementation_parent_sha: 58e631394b3f5a868307fba8e9f8382436023949

master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
master_plan_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
ledger_reference: 899328bc91e4008e39a658387396a0106c8666ec
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob: 445f9cd6f2d57c47de88696a297ccc9dad23bfc5

active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots:
- BUG-UPDATER-02
- BUG-UPDATER-03
- BUG-HISTORY-05

primary_deep_lens: L1 Durability & recovery
primary_deep_selection_reason: R1/R2 — BUG-UPDATER-02 remains open specifically because old durable foreign provenance survives upgrade, which directly triggers persisted-generation/restart review.

## Baseline L1-L6

- L1 Durability & recovery: DEEP / FAIL — e3ded5a closes future restore/startup ownership cells but pre-e3 persisted foreign proof has no migration/epoch invalidation.
- L2 Identity & provenance: BASELINE / FAIL — pre-e3 foreign proof remains semantically unowned; BUG-UPDATER-03 key-specific ytdlp_source schema remains open.
- L3 Concurrency & authority: BASELINE / PASS — StartupYtdlpUpdateOwner now retains process-lifetime responsibility; runtime mutation still uses YtdlpRuntimeAuthority; no new lock inversion/bypass established.
- L4 Destructive ownership: BASELINE / FAIL — BUG-HISTORY-05 duplicate-only playlist membership loss remains present.
- L5 Platform contract closure: BASELINE / PASS — sampled minSdk/ABI/Resume/debug package boundaries do not establish a new production root.
- L6 Cross-feature semantic propagation: BASELINE / FAIL — old persisted updater proof can suppress startup runtime convergence; malformed restored ytdlp_source and History relationship loss remain externally visible.

## Trigger map

- Module F persisted generation / legacy state: FAIL — pre-e3 foreign committed/pending proof can survive upgrade because e3ded5a has no provenance epoch/migration.
- Module C external representation/provenance: PARTIAL — future updater provenance import is SOURCE_FIXED, but BUG-UPDATER-03 restore schema remains open.
- Module H persisted executable configuration: FAIL — BUG-UPDATER-03.
- Shared runtime generation / Module E: PASS for BUG-UPDATER-04 closure.
- Startup/recovery ownership: PASS for the newly added process-lifetime owner on newly produced state.
- Destructive relationship preservation: FAIL — BUG-HISTORY-05.
- Build/package/platform baseline: PASS on sampled current source; no canonical count impact.

## L1 direct source review

### Startup owner — previous one-shot cells source-fixed

Current App constructs StartupYtdlpUpdateOwner for process lifetime.

Its prerequisite path:
- awaits or retries runtime initialization;
- drives RestoreTransactionCoordinator.recover();
- requires RestoreGate clear;
- retries initialization if the original runtimeReadiness attempt failed;
- joins initial DownloadExecutionRecovery and calls reconcile again.

StartupYtdlpUpdateOwner:
- observes exact desired source/generation;
- retries after exceptions/cancellation without retiring responsibility;
- rechecks desired generation after prerequisites;
- invokes UpdateUtil.updateStartupGeneration();
- treats success as satisfied only when startupGenerationIsCommitted(requested) is true;
- follows SUPERSEDED to a newer generation;
- keeps a bounded retry loop plus preference wakeups;
- uses a 60-second idle recheck so pre-Android-R clear() silence cannot permanently lose responsibility.

MainActivity no longer owns the one-shot Active/Queued startup gate.

This closes the previously established runtime-readiness, stale-Active and queued-deferral one-shot-loss cells for newly produced state.

### Native/update durability

UpdateUtil still publishes exact pending source/generation before native mutation.
Native success is validated under YtdlpRuntimeAuthority before committed proof is persisted.
Committed publication requires:
- exact pending identity still matches;
- current desired source/generation still equals the request.

Restore supersession therefore cannot let an older successful updater publish proof for a newer restored generation.

Pending debt survives native failure/process death and the process-lifetime startup owner can consume it on restart.

### Future restore provenance — source-fixed

BackupSettingsUtil now delegates updater runtime-authority filtering to UpdateUtil.isDestinationLocalPreferenceKey().
Current/legacy/typed restore normalization uses the same portability predicate.
Merge/Reset source publication reconciles restored source intent against destination-local generation/proof.

Future source-device updater generation/committed/pending proof is no longer portable.

### Persisted-state upgrade residual — still open

e3ded5a contains no destination-local provenance epoch/schema marker and no startup migration that invalidates preexisting committed/pending proof created by a supported pre-e3 restore.

Reachable upgrade state:
- pre-e3 restore imported foreign desired generation + committed source/generation/result;
- desired and committed tuple match;
- app upgrades to e3ded5a;
- startup owner reads the same tuple;
- startupGenerationIsCommitted() can return true because committedMatches() has no provenance epoch requirement and no pending carrier;
- startup owner marks the generation satisfied without forcing destination runtime re-proof.

Filtering future backup/restore does not repair already persisted state.

This remains BUG-UPDATER-02 same-root residual; no new finding ID.

## Existing focused evidence

Historical e3ded5a evidence records:
- production compilation PASS;
- AndroidTest compilation PASS;
- focused JVM units 13 PASS / 0 FAIL / 0 skipped;
- runtime/device execution NOT_VERIFIED.

The current manual run did not independently rerun execution.

The persisted implementation prompt
ytdlnisx/prompts/2026-10-04_GPT61_SOL_BUG_UPDATER_02_PRECHANGE_PROVENANCE_MIGRATION.md
matches this residual and explicitly requires direct pre-change fixture seeding, durable/idempotent epoch migration, Restore ordering and fresh destination proof.
No prompt rewrite is required by this review.

new_finding_ids: NONE
count_change: 0
remaining_not_yet_deep: L2,L3,L4,L5,L6
next_not_yet_deep_lens: L2 Identity & provenance
next_lens_selection_reason: R1 — persisted foreign proof and BUG-UPDATER-03 keep identity/provenance directly relevant after L1.
independent_execution: NOT_EXECUTED
