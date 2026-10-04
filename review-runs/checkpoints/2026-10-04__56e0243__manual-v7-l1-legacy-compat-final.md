# Manual correctness review — 56e0243 — L1 durability/recovery legacy-state compatibility

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: e453c8df3801fc3d773572ebbb6b077b00eb1402
review_parent_sha: e453c8df3801fc3d773572ebbb6b077b00eb1402

implementation_sha: 56e02434c8be228d5ef0f4d5fb9a1a391e2e88c6
implementation_parent_sha: e3ded5accb08064d84f5d2ff1d2ea84b61cf6254

master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
master_plan_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
plan_branch_observed_head_at_run_start: 2145847a1054da28398b730b9be0ca728668f967
ledger_reference: 899328bc91e4008e39a658387396a0106c8666ec
ledger_branch_observed_head_at_run_start: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob: 445f9cd6f2d57c47de88696a297ccc9dad23bfc5

active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
overall_verdict: NOT_CLEAN
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots:
- BUG-UPDATER-02
- BUG-UPDATER-03
- BUG-HISTORY-05

new_finding_ids: NONE
same_root_residual: BUG-UPDATER-02
same_root_refinement: PRECHANGE_EPOCH_REPRESENTATION_DOMAIN_NOT_MIGRATABLE
independent_execution: NOT_EXECUTED

## Independent source verdict

56e0243 remains NOT_CLEAN.

The prior manual L2 run established one exact BUG-UPDATER-02 residual: a supported pre-change restore can persist ytdlp_provenance_epoch as Int value 1 together with foreign committed/pending proof, and 56e0243 then mistakes that graph for current destination proof.

This L1 run expands the same root through the full durable legacy representation domain.

At pre-change implementation 58e631394b3f5a868307fba8e9f8382436023949:
- BackupSettingsUtil.isPortablePreferenceKey() did not reserve or exclude ytdlp_provenance_epoch;
- BackupRestoreParser.validateSettings() allowed generic String, Boolean, Int, Long, Float and StringSet storage declarations for any portable key;
- both Merge and Reset preference publication selected the SharedPreferences storage method directly from the imported declaration.

Therefore supported pre-change restore could durably materialize ytdlp_provenance_epoch not only as Int 1, but also as:
- another Int value such as -1 or 2;
- String "1";
- Long 1;
- Boolean true;
- Float 1.0;
- a StringSet representation.

56e0243 migration does not normalize this legacy representation domain. It immediately reads:
preferences.getInt(PREF_PROVENANCE_EPOCH, 0)
then requires the value to be in 0..1.

Consequences:
- Int 1 remains the previously confirmed provenance-alias case: migration returns early and foreign proof may be accepted as current.
- Int values outside 0..1 fail before retirement and remain durable, so each later migration attempt encounters the same durable state.
- a non-Int legacy representation is incompatible with the typed getInt() read and has no conversion/removal fallback before that read; the same durable legacy state therefore remains a permanent migration blocker.
- StartupYtdlpUpdateOwner catches the repeated exception and retries, but no retry mutates the incompatible epoch carrier, so retry does not reconstruct a progress-making barrier.
- manual/worker updater requests enter the same migration boundary before native mutation and therefore do not bypass or repair the incompatible durable state.

This is one BUG-UPDATER-02 root, not new findings: supported pre-change provenance state can survive upgrade without a total, unambiguous migration into destination-local runtime proof semantics.

## Baseline L1-L6

- L1 Durability & recovery: DEEP / FAIL — migration is durable and restart-safe only for absent/Int-0 epoch and its own commit-failure windows. The supported pre-change epoch representation domain is not total: Int-1 aliases current proof, out-of-range Int fails permanently, and non-Int legacy values cannot reach retirement.
- L2 Identity & provenance: DEEP / FAIL (preserved from prior same-SHA run and rechecked) — epoch equality is not generation-exclusive provenance; BUG-UPDATER-03 remains open.
- L3 Concurrency & authority: BASELINE / PASS — migration remains fenced by RestoreMutationAdmission and stateLock; no new current-SHA race or lock-order defect was established.
- L4 Destructive ownership: BASELINE / FAIL — BUG-HISTORY-05 remains present; duplicate History cleanup deletes duplicate playlist memberships without transferring duplicate-only membership.
- L5 Platform contract closure: BASELINE / PASS WITH VERIFICATION GAP — no new platform/ABI blocker established; device/runtime migration acceptance remains NOT_VERIFIED.
- L6 Cross-feature semantic propagation: BASELINE / FAIL — updater startup/manual convergence can be permanently suppressed by incompatible legacy epoch state; BUG-UPDATER-03 and BUG-HISTORY-05 remain externally visible.

lens_coverage_current_sha:
- L1: DEEP_FAIL
- L2: DEEP_FAIL
- L3: BASELINE_PASS
- L4: BASELINE_FAIL
- L5: BASELINE_PASS_VERIFICATION_GAP
- L6: BASELINE_FAIL

primary_deep_lens: L1 Durability & recovery
primary_deep_selection_reason: R1/R2 — the confirmed same-root residual is a supported durable pre-change state whose migration/restart barrier is incomplete, directly owning L1 and triggered Module F.
remaining_not_yet_deep: L3,L4,L5,L6
next_not_yet_deep_lens: L4 Destructive ownership
next_lens_selection_reason: R1 — BUG-HISTORY-05 is a confirmed open root directly owned by destructive relationship preservation; L2 already has DEEP coverage and L3 currently has no failing baseline cell.

## Trigger map and conditional modules

- Module F — Persisted schema-generation compatibility: FAIL.
  - Trigger: new provenance epoch marker is interpreted over state produced by supported pre-change schema/restore.
  - Collision cell: pre-change Int 1 is indistinguishable from current epoch and can preserve foreign proof.
  - Domain cell: pre-change out-of-range Int and non-Int representations are accepted by old restore but cannot be migrated by the current Int-only 0..1 reader.
  - Required correction must define a total migration for every supported pre-change representation of this key, not only absent/0.
- Module C — External representation/schema/authority projection: FAIL for overall scope.
  - The old external backup/restore schema could materialize the future marker with multiple storage types.
  - Current filtering prevents new imports but cannot repair already durable old representations.
  - BUG-UPDATER-03 remains independently open for ytdlp_source String/nonblank schema.
- Module E — Referenced-artifact/shared-generation publication: PASS for the reviewed updater mutation path after migration succeeds.
  - runtime mutation remains under YtdlpRuntimeAuthority;
  - native success is validated;
  - committed proof publication rechecks exact pending + desired identity.
  - This PASS does not repair failure to reach the mutation path.
- Module H — Persisted executable configuration fan-out: FAIL.
  - BUG-UPDATER-03 remains open: malformed ytdlp_source storage/value can pass shared restore validation and fail the authoritative consumer.
- Modules A/B/D/G/I: NOT_TRIGGERED by the material updater migration delta in this run.

## L1 durability/recovery trace

### First durable observation and carrier

The durable legacy carrier is SharedPreferences itself. The current migration's first semantic read is the typed Int epoch read before any retirement mutation.

For absent or Int-0:
- retirement removes committed/pending proof with synchronous commit();
- a second synchronous commit publishes current epoch;
- process death between those commits leaves proof retired with epoch absent/0, so startup can retry safely.

For Int-1:
- no write occurs and the migration is considered complete;
- the pre-change provenance graph survives unchanged.

For incompatible/out-of-range legacy epoch:
- failure occurs before the first retirement write;
- the durable incompatible marker and foreign proof remain unchanged;
- restart reconstructs the same failing read/check, not a progress-making recovery obligation.

### Persistence and failure windows

- first retirement commit false: PASS — in-process unconfirmed guard prevents false epoch authority; restart sees durable old graph and retries.
- second epoch publication commit false: PASS — retirement is already durable; in-process unconfirmed guard denies proof; restart retries from retired graph.
- process death after retirement before epoch publication: PASS — old proof is already gone; absent/old epoch allows replay.
- active Reset: PASS — RestoreMutationAdmission rejects ordinary migration and owner retries after recovery.
- pre-change Int-1 marker: FAIL — no retirement occurs.
- pre-change out-of-range Int: FAIL — check fails before retirement on every attempt.
- pre-change non-Int marker: FAIL — typed epoch read cannot consume the legacy representation and no pre-read compatibility path removes/normalizes it.

### Recovery discoverability

StartupYtdlpUpdateOwner remains a process-lifetime retry owner, but discoverability alone is insufficient.

For absent/0 and transient commit failures, retries can mutate state toward completion.
For incompatible/out-of-range legacy epoch, every retry observes the same durable blocker and performs no authoritative retirement. The recovery owner is live but cannot make progress.
For Int-1 alias, no recovery debt is constructed at all because the graph is falsely classified current.

## Cross-attempt / live-owner matrix

- absent epoch -> migrate -> runtime re-proof: PASS.
- Int-0 epoch -> migrate -> runtime re-proof: PASS.
- first retirement write failure -> retry/restart: PASS.
- epoch publication write failure -> retry/restart: PASS.
- process death between migration commits -> retry: PASS.
- active Restore -> defer under admission -> retry after recovery: PASS.
- exact live updater owner / Restore supersession -> exact desired/pending revalidation preserves authority: PASS.
- pre-change Int-1 + foreign proof -> FAIL: old proof is treated as current.
- pre-change Int -1 or >1 -> FAIL: migration permanently rejects the durable carrier without retiring it.
- pre-change non-Int epoch -> FAIL: migration cannot consume the durable carrier and has no normalization branch.
- same-settings/manual/startup retry after either permanent legacy failure -> FAIL: retries reproduce the same blocker.

## Semantic-contract delta / consumer closure / authority-effect closure

trigger: YES
changed_boundary: ytdlp_provenance_epoch is now a durable gate for committed/pending runtime proof.

intended_contract:
- every supported pre-change updater provenance state must either be unambiguously recognized as genuinely current or be retired before current proof can authorize skip/coalescing/publication.

actual_contract:
- only absent/Int-0 legacy state is safely migrated;
- Int-1 aliases current state;
- other supported pre-change representations are not migrated.

reviewed production consumers/effects:
- UpdateUtil.migrateDestinationProvenance()
- UpdateUtil.retirePreChangeProvenance()
- UpdateUtil.destinationProvenanceEpochIsCurrent()
- committedMatches()
- pendingMutationMatches()
- startupGenerationIsCommitted()
- updateStartupGeneration()/updateYoutubeDL() common update path
- StartupYtdlpUpdateOwner retry loop
- current BackupSettingsUtil portability filter
- current BackupRestoreParser normalization
- Merge/Reset source publication
- pre-change BackupSettingsUtil/parser/Merge/Reset producers that created the durable compatibility domain

final_authority_effect:
- alias state can suppress required destination runtime re-proof;
- incompatible state can indefinitely prevent startup/manual convergence from reaching native mutation.

final_checkpoint_recount: FAIL — BUG-UPDATER-02 remains OPEN P2.

## Existing other open roots rechecked

BUG-UPDATER-03:
- current shared validateSettings() is still generic and does not require ytdlp_source to be declared String and nonblank;
- Merge still writes by declared type;
- root remains OPEN P2.

BUG-HISTORY-05:
- deleteDuplicateHistoryGroups() still copies keyword assignments, then directly deletes duplicate playlist rows before deleting History;
- duplicate-only playlist membership is still not materialized on the retained History row;
- root remains OPEN P2.

No fourth root was established.

## Candidate rejection / attribution

Candidate: treat out-of-range/non-Int legacy epoch as acceptable fail-closed behavior.
Rejection: fail-closed is insufficient when the state is a supported pre-change durable representation and the new migration owns upgrade convergence. Repeated failure preserves foreign/stale proof state and permanently prevents updater convergence; there is no user-independent recovery path.

Candidate: classify wrong-type epoch as BUG-UPDATER-03.
Rejection: BUG-UPDATER-03 concerns the authoritative ytdlp_source executable configuration schema. The epoch cases arise from BUG-UPDATER-02's newly introduced provenance migration over pre-change portable updater-runtime keys. They are same-root compatibility residuals of BUG-UPDATER-02.

## Verification evidence

reported_by_implementation_commit:
- production compilation: PASS
- AndroidTest compilation: PASS
- focused migration JVM tests: 8 PASS / 0 FAIL / 0 skipped
- git diff --check: PASS
- device/runtime acceptance: NOT_VERIFIED

independent_execution: NOT_EXECUTED

test_gap:
- current tests cover absent, Int-0, Int-1-as-assumed-current, Int-2 fail-closed, and the two commit-failure windows;
- they do not model the complete supported pre-change restore domain;
- unsupportedFutureEpochFailsClosedWithoutErasingItsState actually demonstrates a permanent blocker if Int-2 was created by the supported pre-change restore path;
- no direct old-representation fixture covers non-Int epoch types;
- no test proves that retry/restart converges from those durable legacy states.

## Required narrow correction boundary

BUG-UPDATER-02 correction must now satisfy both provenance uniqueness and total legacy migration:

1. enumerate every representation that the supported pre-change backup/restore path could persist for ytdlp_provenance_epoch;
2. do not trust Int-1 merely because it equals CURRENT_PROVENANCE_EPOCH when the old producer could have written it;
3. normalize or retire incompatible/out-of-range legacy representations before any current-epoch typed read can become a permanent retry blocker;
4. preserve source intent/desired generation while retiring ambiguous foreign committed/pending proof;
5. keep the existing first-retirement-write, epoch-publication-write, process-death, Restore-fencing and idempotence guarantees;
6. seed direct pre-change fixtures for at least colliding Int-1, representative out-of-range Int, and representative non-Int old representation, then prove restart/startup convergence.

remaining_review_scope:
- current SHA still has L3,L4,L5,L6 not yet DEEP.
- BUG-UPDATER-02 requires another correction before verification-only execution is meaningful.
- BUG-UPDATER-03 and BUG-HISTORY-05 remain separate open roots.
