# Manual correctness review — 256a5cf5 — L2 provenance deep (final)

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 13275c98ee6a0803be60757ef14f32e3e2b43bb1
review_parent_sha: c39b0f462d05844743ea788d2f75926c9e1cf716
intermediate_checkpoint: review-runs/checkpoints/2026-10-01__256a5cf5__manual-v7-l2-deep-updater-provenance-intermediate.md
intermediate_commit: c39b0f462d05844743ea788d2f75926c9e1cf716
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c

master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3

## Independent verdict

NOT_CLEAN.

Active-remediation canonical counts remain:
- P0=0
- P1=0
- P2=1

The active blocker remains:
- BUG-UPDATER-02 — OPEN P2 / SAME-ROOT residual.

This run establishes one additional repository-wide production finding outside the
currently adopted active-remediation inventory:
- BUG-UPDATER-03 — P2 / PRE_EXISTING_BASELINE_DEFECT / OUT_OF_SCOPE_CURRENT_ACTIVE_INVENTORY.

Therefore BUG-UPDATER-03 does not increment CANONICAL_P2 under the governing
active-remediation count semantics. It requires explicit future inventory/scope
adoption before it can affect canonical active-scope counts.

Known-Good Baseline remains NOT_CREATED. BUG-UPDATER-02 already blocks the
current active scope independently of BUG-UPDATER-03.

## Findings

### 1. BUG-UPDATER-02 — same-root residual: restore-recovery startup can consume and lose reconciliation

Classification:
- severity: P2
- relation: SAME_ROOT_RESIDUAL
- active scope: YES
- canonical count impact: already counted; +0
- introduced/pre-existing attribution: unchanged from canonical BUG-UPDATER-02

The prior L6 checkpoint proved foreign source-device updater generation,
committed-result and pending carriers can cross backup/restore and be accepted as
destination-runtime proof. Fresh exact-source review reconfirms that root:
`BackupSettingsUtil.isPortablePreferenceKey()` does not exclude the updater
authority keys; parser normalization uses that same predicate; Merge persists the
accepted values directly; Reset clears/replays portable settings; and
`UpdateUtil.committedMatches()` trusts the durable preference generation/source
tuple without independently proving the destination runtime.

This L2 run adds a concrete restart/recovery subcase of the same invariant.

Production trace:
1. `App.onCreate()` starts `RestoreTransactionCoordinator.recover()` asynchronously.
2. Activity creation is not blocked on that deferred recovery.
3. `MainActivity` launches its own IO coroutine and calls
   `UpdateUtil.updateOnStartup(automaticYtdlpUpdates)`.
4. `MainActivity` has no `RestoreGate` or restore-recovery ordering reference.
5. `UpdateUtil.beginMutation()` enters
   `RestoreMutationAdmission.withOrdinaryMutation()`.
6. If the durable Reset owner is still active, ordinary mutation throws
   `IllegalStateException("Restore transaction is active")`.
7. The MainActivity startup body is wrapped in `runCatching`; the failure is
   consumed and no durable retry/reconciliation owner is created.
8. Reset recovery may then complete and clear the gate in the same process, but
   the startup updater attempt is not re-issued.
9. If the restored desired source changed, especially with
   `auto_update_ytdlp=false`, destination runtime convergence can therefore be
   delayed until another app launch or manual action.

This is not a second root: it is the recovery/final-effect form of the same
BUG-UPDATER-02 requirement that changed restored source intent must converge to
destination-local updater generation/runtime provenance.

Narrow correction requirement in addition to the already-persisted prompt:
- startup updater reconciliation must be ordered after active Reset recovery, OR
  an equivalent durable/retry owner must guarantee the updater reconciliation
  runs after RestoreGate release in the same startup process;
- do not weaken RestoreMutationAdmission to permit updater mutation during Reset;
- preserve ordinary source-generation and SUPERSEDED ordering;
- add a deterministic production-wiring regression that starts with an active
  Reset recovery, restores a changed source, keeps automatic yt-dlp updates
  disabled, and proves one destination-local post-recovery reconciliation rather
  than a swallowed gate rejection.

### 2. BUG-UPDATER-03 — blank restored yt-dlp source is accepted as durable executable configuration

Classification:
- severity: P2
- relation: NEW_DISTINCT_ROOT
- attribution: PRE_EXISTING_BASELINE_DEFECT
- active scope: NO
- canonical count impact: +0 under current inventory semantics

Current exact-source production trace:
1. `ytdlp_source` is intentionally portable user intent.
2. `BackupRestoreParser.normalize()` filters by portability and then calls
   `validateSettings()`.
3. `validateSettings()` accepts every `String` value without a key-specific
   semantic check; there is no `ytdlp_source` validation.
4. A legacy/current/typed restore payload can therefore carry
   `BackupSettingsItem(key="ytdlp_source", value="", type="String")`.
5. Merge persists that blank value through its settings loop; Reset persists it
   through `publishPreferences()`.
6. Current `UpdateUtil.readDesiredSourceLocked()` sees that the key exists and
   explicitly rejects blank source with `check(it.isNotBlank())`.
7. Startup invokes that path under a swallowed `runCatching`, while manual
   updater actions also depend on the same desired-source state.

The restore boundary therefore accepts durable executable configuration that its
authoritative consumer rejects.

Baseline attribution is supported by exact historical source at reviewed
checkpoint `73d3836665f5f2e6e232e327eef1d968054d0539`:
- the historical SettingsViewModel restore loop also persisted arbitrary String
  settings without key-specific source validation;
- the historical UpdateUtil consumed `ytdlp_source` directly and routed
  unrecognized/blank values into the custom `--update-to <channel>@latest`
  path rather than rejecting them at restore admission.

The invalid portable source contract therefore predates the current remediation;
it is not attributed to the current updater-generation fix.

Narrow future correction boundary:
- at the backup/restore validation authority, a present `ytdlp_source` must be
  semantically valid at least as a nonblank source after the exact product
  normalization policy is established;
- do not silently reinterpret malformed blank imported intent as `stable`;
- reject malformed payload before restore mutation;
- cover current, legacy and typed restore entrypoints, Merge and Reset;
- preserve valid built-in and valid nonblank custom source values.

No implementation change is authorized or made by this review-only run.

## Review retrospective

This is a new same-SHA manual run. The immediately preceding manual run for
256a5cf5 was FINAL, so it was not resumed.

The full governing checklist and trigger map were recomputed against the exact
unchanged source. The prior continuation hint was L1, but R1 selects L2 for this
run because the still-open canonical root is directly a provenance/identity
failure: foreign source-device generation/committed/pending state is being
accepted as proof about a different destination runtime.

Fresh producer/carrier/consumer/final-effect review did not merely reuse the
prior L6 verdict. It re-read the current backup portability predicate, restore
parser, Merge preference writer, durable Reset coordinator, mutation admission,
UpdateUtil generation/provenance state machine, Application recovery ordering,
MainActivity startup consumer, source-selection UI path and the existing updater
production-wiring test surface.

The stronger startup-recovery counterexample extends BUG-UPDATER-02 without
double-counting it. Module-H review also exposed BUG-UPDATER-03 as a distinct
input-contract root, and historical source established its pre-existing
attribution before classification.

No production source, test, configuration, ledger or governance file was changed
by this review. No tests were independently executed.

Historical execution evidence remains historical only:
- exact-wave/campaign evidence previously recorded on 256a5cf5 remains preserved;
- final-heavy H1 previously recorded 694 PASS / 0 FAIL / 0 SKIP / 0 ERROR and
  BUILD SUCCESSFUL, with null exit-code metadata preserved as UNKNOWN;
- none of that is claimed as independent execution by this run.

## Trigger/module status

- Module C — external representation / backup portability: FAIL.
  - BUG-UPDATER-02 foreign updater authority carriers are portable.
  - BUG-UPDATER-03 malformed blank portable source is admitted.
- Module F — persisted generation/provenance compatibility: FAIL.
  - BUG-UPDATER-02 permits foreign generation/committed/pending provenance to
    stand in for destination runtime proof.
- Module H — persisted executable configuration fan-out: FAIL.
  - restored ytdlp source intent reaches updater execution without a matching
    semantic validation contract; BUG-UPDATER-03 is the distinct root.
- Module E — shared runtime/resource replacement: FAIL / correction required.
  - BUG-UPDATER-02 lacks a same-startup convergence owner when updater admission
    loses to an active Reset recovery.

No other newly established blocker-relevant conditional-module root was found in
this run.

## Terminal / cross-attempt / recovery matrix

Current updater state-machine source preserves these positive properties:
- ordinary `selectSource()` durably advances generation on source change;
- stale expected generation is SUPERSEDED;
- pending mutation survives native failure;
- startup retries a desired/committed mismatch when RestoreGate is not active;
- same-generation overlapping update requests coalesce behind the update mutex.

Open cells:
- foreign restored committed/pending authority can still suppress destination
  reconciliation: BUG-UPDATER-02;
- updater startup admission rejected by an active Reset has no same-process
  retry/convergence owner: BUG-UPDATER-02 same-root recovery residual;
- blank restored executable source intent is admitted by the restore boundary:
  BUG-UPDATER-03.

## L1-L6 coverage

- L1 Durability & recovery: BASELINE / FAIL cell under BUG-UPDATER-02 startup-recovery convergence.
- L2 Identity & provenance: DEEP / FAIL.
- L3 Concurrency & authority: BASELINE; active-Reset/updater ordering defect is
  owned by existing BUG-UPDATER-02 and no second L3 root was established.
- L4 Destructive ownership: BASELINE; no new destructive-ownership root established.
- L5 Platform contract closure: DEEP from prior same-SHA run; fresh baseline
  recount found no new platform-contract root in the reviewed updater/restore path.
- L6 Cross-feature semantic propagation: DEEP / FAIL from prior same-SHA run;
  fresh consumer recount reconfirmed the updater propagation failure and its
  startup final effect.

primary_deep_lens: L2 Identity & provenance
primary_deep_selection_reason:
R1 — the active root directly confuses source-device updater provenance with
destination runtime authority, and Module-H inspection additionally exposed an
invalid executable-source identity admitted by restore.

remaining_not_yet_deep:
- L1
- L3
- L4

next_not_yet_deep_lens:
L1 Durability & recovery

next_lens_selection_reason:
R1/R2 — after this L2 turn, the strongest remaining same-SHA risk is durable
post-restore convergence, including the exact startup recovery cell identified
above.

## Checklist evolution

No checklist, lens-policy or Review Protocol change is required.

Existing v7 rules were sufficient:
- Module C caught the external backup representation mismatch;
- Module F caught destination-vs-source generation provenance;
- Module H caught executable configuration fan-out;
- retry/restart/recovery and consumer/final-effect closure rules exposed the
  swallowed active-Restore startup admission.

Implementation-prompt/test coverage should be tightened for BUG-UPDATER-02 with
the active-Reset cold-start scenario, but that is a prompt refinement rather
than governance evolution.

## Checkpoint summary

manual_review_run_status: FINAL
overall_active_remediation_verdict: NOT_CLEAN
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
production_active_scope_p0: 0
production_active_scope_p1: 0
production_active_scope_p2: 1
active_finding: BUG-UPDATER-02
active_finding_relation: SAME_ROOT_RESIDUAL
new_repository_finding: BUG-UPDATER-03
new_repository_finding_severity: P2
new_repository_finding_attribution: PRE_EXISTING_BASELINE_DEFECT
new_repository_finding_active_scope: NO
new_finding_ids_this_run: 1
primary_deep_lens: L2
next_lens_hint: L1
known_good_baseline: NOT_CREATED
independent_execution: NOT_EXECUTED
