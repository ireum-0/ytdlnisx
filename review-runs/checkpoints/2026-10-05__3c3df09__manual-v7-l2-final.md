# Manual correctness review — 3c3df09 — L2 identity/provenance

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 48d23c3408b0cbeb0fe0f28168fecb86143f46a0
review_parent_sha: 48d23c3408b0cbeb0fe0f28168fecb86143f46a0

implementation_sha: 3c3df094b86554310bc2f5d4e15270234da45d6b
implementation_parent_sha: c38a753f90060dd45030dc8162cdf1c5e4782dfe
material_current_sha_change: TEST_ONLY_UpdateUtilProductionWiringTest_5_ADD_1_DEL
production_source_changed_from_parent: NO

master_plan_commit: fada33a7eed86b1fa2c07065af66f14bf4d24714
master_plan_sha256: 4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e
plan_branch_observed_head_at_run_start: 2145847a1054da28398b730b9be0ca728668f967
ledger_reference: 899328bc91e4008e39a658387396a0106c8666ec
ledger_branch_observed_head_at_run_start: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
private_handoff_head_at_run_start: dad68ad7c4b9e9057a5b30f74dabe56debb06fa4

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
reopened_same_root: BUG-UPDATER-02
reopened_same_root_residual: PRE_E3_RESTORED_DESTINATION_GENERATION_SURVIVES_WITHOUT_TOTAL_MIGRATION
bug_updater_03_refinement: ALREADY_PERSISTED_MALFORMED_SOURCE_REQUIRES_RECOVERY_NOT_ONLY_FUTURE_REJECTION
independent_execution: NOT_EXECUTED

## Independent verdict

3c3df09 remains NOT_CLEAN.

The exact current commit is one normal-forward test-only commit from c38a753. The stale updater runtime assertion was corrected to observe the actual Download recovery contract, and the canonical exact-SHA runtime evidence reports the full UpdateUtilProductionWiringTest class at 27 PASS / 0 FAIL / 0 skipped on API 36 ARM64. That test-only correction does not alter production semantics and the prior BUG-UPDATER-02 provenance-marker/source-runtime correction remains source-correct.

However, a deeper L2 review establishes a same-root legacy-state residual that invalidates the full BUG-UPDATER-02 closure.

e3ded5a made ytdlp_source_generation destination-local for future backup/restore and reconciled restored source intent against the destination generation. But supported pre-e3 restore semantics at 58e6313 could already write ytdlp_source_generation through the generic portable settings schema:
- BackupSettingsUtil.isPortablePreferenceKey() did not exclude ytdlp_* runtime/source-generation keys;
- BackupRestoreParser.validateSettings() accepted generic String/Boolean/Int/Long/Float/StringSet declarations per key without a generation-specific contract;
- Merge wrote each accepted item through the SharedPreferences.Editor method selected by the imported declared type.

The current c38/3c3 provenance migration deliberately removes legacy committed/pending proof and the old default epoch but preserves ytdlp_source_generation. Current future portability filtering prevents new imports but does not migrate already durable pre-e3 generation state.

This leaves supported legacy states that the current destination-local generation contract cannot consume or advance:
- wrong storage type, e.g. ytdlp_source_generation as String or Int: readDesiredSourceLocked() calls getLong(), so startup/manual updater admission fails before migration/native convergence;
- negative Long: getLong() succeeds but the current nonnegative check fails on every attempt;
- Long.MAX_VALUE: current read succeeds and same-source use is allowed, but selectSource() and changed-source Restore require an increment and fail with generation exhausted. An externally restored pre-e3 value can therefore impose destination generation exhaustion without being allocated by the destination.

StartupYtdlpUpdateOwner catches read failures and retries, but no retry mutates the malformed generation carrier. For wrong-type/negative state, migrateDestinationProvenance() is not reached because desiredSource() is read first. Current source-selection and Restore reconciliation also use typed destination-generation reads/casts and therefore do not provide a general recovery path.

This is not a new root. It is a same BUG-UPDATER-02 root residual: updater source generation is destination-local authority after e3ded5a, but a pre-e3 supported restore-produced generation carrier can survive the destination-local transition without a total migration. The prior exact-SHA runtime gate did not seed this legacy generation domain and therefore cannot close this source-semantic residual.

BUG-UPDATER-03 also remains open and requires one further same-root refinement. Current restore can itself persist malformed ytdlp_source state because shared validation does not require exactly String + nonblank. A wrong-type persisted source then breaks:
- UpdateUtil.readDesiredSourceLocked() through getString();
- UpdateUtil.selectSource() through its previous-source getString();
- a later Merge/Reset repair attempt because reconcileRestoredSource() casts snapshot[PREF_SOURCE] as String?.

Therefore a future BUG-UPDATER-03 patch that only rejects new malformed payloads is insufficient for durable closure. It must also provide a progress-making recovery/migration contract for malformed ytdlp_source states already persisted by the currently supported restore path. The recovery must not reinterpret malformed bytes as a valid custom source.

BUG-HISTORY-05 remains unchanged.

## Baseline L1-L6

- L1 Durability & recovery: BASELINE / FAIL.
  - BUG-UPDATER-02: pre-e3 wrong-type/negative desired generation can survive restart and repeatedly fail before a repair owner mutates the carrier; imported Long.MAX_VALUE can permanently block later source-generation advancement.
  - BUG-UPDATER-03: currently persisted wrong-type/blank source can survive restart and repeatedly fail authoritative consumers; wrong-type state can also prevent a later Restore from becoming the repair path.
  - BUG-HISTORY-05: duplicate-only playlist membership is durably lost by destructive dedupe.
- L2 Identity & provenance: DEEP / FAIL.
  - destination-local desired-generation identity is not fully migrated from supported pre-e3 external restore state;
  - portable ytdlp_source lacks exact String/nonblank schema and already-persisted invalid source identity has no total recovery contract.
- L3 Concurrency & authority: BASELINE / PASS.
  - no new concurrency/authority race was established in the current test-only delta;
  - updater mutation remains serialized/fenced by updateMutex, stateLock, RestoreMutationAdmission and YtdlpRuntimeAuthority with exact desired/pending revalidation;
  - History duplicate cleanup remains inside its relationship lock + Room transaction, although its destructive relationship semantics are incomplete.
- L4 Destructive ownership: BASELINE / FAIL.
  - BUG-HISTORY-05 remains: duplicate playlist refs are deleted without transferring duplicate-only membership to retained History.
- L5 Platform contract closure: BASELINE / PASS FOR ESTABLISHED BUG-UPDATER-02 RUNTIME PATH, SOURCE RESIDUAL OUTSIDE THAT GATE.
  - exact 3c3 runtime closure evidence for the corrected production-wiring class is canonical: 27/27 pass, 0 fail, 0 skip on API 36 ARM64;
  - the newly identified legacy-generation state was not part of that runtime fixture, so the runtime PASS does not override the source-semantic residual.
- L6 Cross-feature semantic propagation: BASELINE / FAIL.
  - malformed/foreign desired generation can block startup updater convergence and source changes;
  - malformed ytdlp_source affects Restore, source selection and startup/manual updater consumption;
  - duplicate History cleanup loses playlist membership visible to playlist/history features.

lens_coverage_current_sha:
- L1: BASELINE_FAIL
- L2: DEEP_FAIL
- L3: BASELINE_PASS
- L4: BASELINE_FAIL
- L5: BASELINE_PASS_WITH_NEW_SOURCE_RESIDUAL_OUTSIDE_PRIOR_GATE
- L6: BASELINE_FAIL

primary_deep_lens: L2 Identity & provenance
primary_deep_selection_reason: R1/R2 — two current updater roots directly concern durable source identity/provenance contracts, and Modules F/C/H remain failed on the exact producer/carrier/consumer boundaries. The new legacy-generation residual is specifically a destination-local identity that can originate from a pre-transition external producer.
remaining_not_yet_deep: L1,L3,L4,L5,L6
next_not_yet_deep_lens: L1 Durability & recovery
next_lens_selection_reason: R1/R2 — both updater residuals now include durable restart-stable states for which retry exists but cannot make progress; Module F compatibility/recovery remains directly implicated.

## Trigger map and conditional modules

- Module F — Persisted schema-generation compatibility: FAIL.
  - BUG-UPDATER-02 trigger: ytdlp_source_generation changed from pre-e3 portable/generic restore state to destination-local authority, but supported already-persisted representations are not totally migrated.
  - failed cells: wrong-type legacy generation; negative legacy Long; externally imported Long.MAX_VALUE exhaustion.
  - current migration removes provenance proof but intentionally preserves desired generation, so these cells survive.
  - BUG-UPDATER-03 trigger: current supported restore can already persist malformed source state; future validation must account for already durable bad state rather than only new input.
- Module C — External representation/schema/authority projection: FAIL.
  - pre-e3 generic settings restore could project destination-local generation authority;
  - current generic settings validation can project malformed portable ytdlp_source storage/value;
  - future filtering alone is not migration of already persisted state.
- Module H — Persisted executable configuration fan-out: FAIL.
  - ytdlp_source is executable updater configuration consumed by startup/manual/source-selection paths;
  - String/nonblank consumer contract is not enforced at restore validation;
  - already persisted malformed source can block multiple downstream owners.
- Module E — Referenced-artifact/shared-generation publication: PASS after valid desired identity is established.
  - current updater runtime mutation, native validation, exact pending/desired publication and private provenance proof remain source-correct;
  - this PASS does not authorize malformed desired source/generation state before updater admission.
- Other conditional modules: no new trigger from the 3c3 test-only delta was established.

## BUG-UPDATER-02 reopened same-root trace

### Pre-e3 producer

At 58e6313:
- ytdlp_source_generation is not in the nonportable preference set;
- no ytdlp_* runtime-prefix exclusion exists;
- generic restore validation accepts Long values including negative/Long.MAX_VALUE and accepts other generic-valid declared types;
- Merge writes the imported declaration directly with putString/putInt/putLong/etc.

Thus a supported old restore can durably create:
- generation as String "83";
- generation as Int 83;
- generation as Long -1;
- generation as Long.MAX_VALUE;
as well as an ordinary positive foreign Long.

### Current carrier/consumer

At 3c3:
- current backup/restore filtering correctly rejects future ytdlp_source_generation import;
- retirePreChangeProvenance() removes old proof/epoch but does not remove or normalize PREF_DESIRED_GENERATION;
- readDesiredSourceLocked() requires getLong() and generation >= 0;
- selectSource() requires getLong() and, on a change, previousGeneration < Long.MAX_VALUE;
- restoredSourceGeneration() requires previousGeneration >= 0 and, on a change, previousGeneration < Long.MAX_VALUE;
- reconcileRestoredSource() assumes snapshot[PREF_DESIRED_GENERATION] is Long.

The current unit contract explicitly allows same-source Long.MAX_VALUE but rejects changed-source allocation at Long.MAX_VALUE. That is valid for a genuinely destination-allocated exhausted counter, but pre-e3 restore could inject the same durable value without destination allocation.

### Recovery/discovery

- wrong-type/negative generation: StartupYtdlpUpdateOwner discovers responsibility, but update.desiredSource() throws before migrateDestinationProvenance() or native mutation; retry sees the same carrier.
- selectSource() does not repair wrong-type generation because its own typed reads precede the write.
- later Restore source reconciliation is not a total repair path because it reads/casts the previous destination generation under the same typed assumptions.
- future portability filtering prevents recurrence but does not repair the installed legacy graph.

Therefore current retry is discoverable but non-convergent.

### Required correction boundary

Do not re-import generation and do not weaken exact desired-generation matching.

The correction must add a bounded migration/compatibility path for supported pre-e3 desired-generation state before typed generation consumption can permanently block progress. It must:
- inspect the legacy durable representation without assuming Long storage;
- distinguish usable destination sequencing state from representations that cannot safely participate in the current generation contract;
- make wrong-type/negative state progress toward a valid destination-local generation rather than retry forever;
- address the pre-e3 externally injectable Long.MAX_VALUE exhaustion cell rather than treating it as unquestionably destination-origin;
- preserve source intent where it is valid;
- invalidate/re-prove committed/pending runtime proof whenever generation identity is repaired/rebased;
- be ordered against Restore/source publication under the existing authority locks;
- remain process-death/retry safe;
- seed direct pre-e3 fixtures rather than passing through the current writer.

A future proof may use a new migration generation/discriminator or another exact state transition, but current private provenance epoch=1 cannot by itself prove that the preserved desired generation was destination-origin: it was published after c38 retirement without normalizing that carrier.

## BUG-UPDATER-03 L2 refinement

### Current producer and durable mutation

BackupRestoreParser.normalize():
- keeps portable ytdlp_source;
- validateSettings() applies only generic type validation;
- String accepts blank/whitespace;
- Int/Long/Boolean/Float/StringSet are valid when their generic representation parses.

Merge and Reset then choose the SharedPreferences storage method from item.type.
reconcileRestoredSource() separately interprets BackupSettingsItem.value as the intended source string, so a wrong-type item can simultaneously:
- cause a destination generation advance/proof invalidation for source text such as "1";
- durably store the actual ytdlp_source key as Int 1 rather than String "1".

The restore may therefore complete with a durable source carrier that authoritative updater consumers cannot read.

### Downstream consumers and recovery

- readDesiredSourceLocked(): getString + nonblank.
- selectSource(): previous-source getString before replacement.
- reconcileRestoredSource(): snapshot[PREF_SOURCE] as String? before a future Restore commit.

A wrong-type durable source can therefore make startup/manual updater reads fail, source selection fail before repair, and future Merge/Reset source reconciliation fail before it can publish a repairing preference image.

A blank String avoids ClassCastException but still fails readDesiredSourceLocked() nonblank enforcement and can cause startup retries without an automatic correcting writer.

### Required correction boundary

The existing remediation-ready rule remains necessary:
- shared parser/plan validation must require ytdlp_source type exactly String and value nonblank before any Merge/Reset mutation;
- all current typed/programmatic/legacy supported input paths must converge on that validation.

It is not sufficient by itself.

Because current production can already persist malformed ytdlp_source, closure also requires an upgrade/recovery contract for already durable invalid state. That recovery must:
- run before typed source reads/casts can make the state permanently non-progressing;
- never reinterpret non-String or blank bytes as a valid custom source;
- establish a valid source/absence identity under an explicit existing/fail-safe contract;
- advance/reconcile desired generation as required and retire incompatible proof;
- permit subsequent startup/source-selection/Restore progress;
- be restart safe and covered with direct persisted-state fixtures.

This is a refinement of BUG-UPDATER-03, not a new root.

## BUG-HISTORY-05 recheck

Current HistoryKeywordAssignmentRepository.deleteDuplicateHistoryGroups():
- rereads exact duplicate identity under HistoryReferenceMutationCoordinator + Room transaction;
- copies keyword assignments to retained;
- materializes retained assignments;
- directly deletes PlaylistItemCrossRef rows for duplicate.id;
- deletes duplicate assignments and then History.

PlaylistDao still exposes getPlaylistItemsForHistory(), insertPlaylistItems(), and deletePlaylistItemsByHistoryIds(), but the production dedupe path does not transfer duplicate-only playlist membership before deletion.

BUG-HISTORY-05 remains OPEN P2 with the prior narrow correction/rollback contract unchanged.

## BUG-UPDATER-02 prior runtime-closure continuity

3c3 is a test-only child of c38:
- the stale Active recovery assertion was replaced by direct semantic checks that the old Active row is Queued, executionId is cleared, no Active/PostProcessing state remains, and the independent Queued row stays Queued;
- no production file changed.

The canonical runtime closure checkpoint records exact 3c3:
- SM-A546E;
- API 36;
- arm64-v8a;
- com.ireum.ytdl.debug;
- UpdateUtilProductionWiringTest: 27 PASS / 0 FAIL / 0 skipped.

That evidence remains valid for the exercised runtime contract and stale-test correction. The root is reopened only because newly established source-semantic legacy-generation state lies outside that fixture and outside the prior provenance-marker correction.

## Candidate rejection / root attribution

Candidate: current filtering makes ytdlp_source_generation destination-local, so old imported values can be ignored.
Rejected: filtering prevents new imports but does not mutate already durable pre-e3 state; current migration explicitly preserves desired generation.

Candidate: typed read failure is acceptable fail-closed behavior.
Rejected: the failure is caused by a supported pre-transition durable representation, repeats across restart, and has no progress-making owner. Fail-closed without convergence is not upgrade closure.

Candidate: Long.MAX_VALUE is merely a valid exhausted local counter.
Rejected for closure purposes: pre-e3 supported restore could inject that exact value before generation became destination-local, so current state cannot infer destination allocation from the bytes alone. The value can permanently prevent a later legitimate source change.

Candidate: classify the generation issue as BUG-UPDATER-03.
Rejected: BUG-UPDATER-03 is the portable ytdlp_source storage/value schema root. The generation residual is the destination-local source-generation authority transition implemented by e3ded5a and preserved by the BUG-UPDATER-02 migration sequence. Count the semantic root once by reopening BUG-UPDATER-02.

Candidate: parser-only validation fully closes BUG-UPDATER-03.
Rejected: currently supported production restore can already have persisted malformed source state; a future input gate does not repair existing durable carriers, and current typed consumers/restore reconciliation are not a recovery path.

## Execution/test evidence

canonical exact-3c3 runtime evidence:
- UpdateUtilProductionWiringTest 27 PASS / 0 FAIL / 0 skipped;
- API 36 ARM64 device;
- exact remote SHA 3c3df094b86554310bc2f5d4e15270234da45d6b.

current source/unit evidence:
- UpdateUtilSourceRestoreTest explicitly models Long.MAX_VALUE as same-source stable but changed-source generation exhaustion;
- current portability test confirms ytdlp_source_generation is nonportable for future backups;
- provenance migration tests exercise old default proof/epoch storage but seed desired generation as a normal Long and do not cover the supported pre-e3 generation representation domain;
- current restore validation tests do not establish recovery of an already persisted malformed ytdlp_source.

independent_execution:
- NOT_EXECUTED

Tests do not override the source-semantic legacy-state proof.

## Final recount

BUG-UPDATER-02: REOPENED P2, SAME_ROOT_RESIDUAL — pre-e3 restore-produced desired generation is not totally migrated into the destination-local generation contract.
BUG-UPDATER-03: OPEN P2, REFINED — future key-specific validation plus recovery of already persisted malformed source state are both required.
BUG-HISTORY-05: OPEN P2, unchanged.

canonical_p2 changes from 2 back to 3.
No new finding ID/root is created.

remaining_review_scope:
- current SHA still has L1,L3,L4,L5,L6 not yet DEEP;
- next DEEP hint is L1 Durability & recovery;
- BUG-UPDATER-02 requires a narrow legacy desired-generation correction before it can be closed again;
- BUG-UPDATER-03 remediation contract must include already-durable invalid source recovery;
- BUG-HISTORY-05 remains remediation-ready.
