# Manual correctness review — 256a5cf5 — L1 durability and recovery deep (final)

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 588c79e062e03c33dab33f796d1e43fc009758af
review_parent_sha: a5a01157377ca4ef58c3a227d5c2e85aa87cac36
intermediate_checkpoint: review-runs/checkpoints/2026-10-01__256a5cf5__manual-v7-l1-deep-updater-recovery-intermediate.md
intermediate_commit: a5a01157377ca4ef58c3a227d5c2e85aa87cac36

implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c
master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY

## Independent verdict

NOT_CLEAN.

Canonical active-remediation counts remain:
- P0=0
- P1=0
- P2=1

Active root:
- BUG-UPDATER-02 — OPEN P2 / SAME_ROOT_RESIDUAL.

Known repository-wide P2 findings outside the adopted active inventory:
- BUG-UPDATER-03 — PRE_EXISTING_BASELINE_DEFECT / active_scope=NO.
- BUG-HISTORY-05 — PRE_EXISTING_BASELINE_DEFECT / active_scope=NO.

No new distinct finding ID is created by this run.

Known-Good Baseline remains NOT_CREATED because BUG-UPDATER-02 remains open.

## Findings

### BUG-UPDATER-02 — same-root durability/recovery residual extended

Fresh exact-source L1 review reconfirms the two prior cells:

1. Backup/restore can import source-device updater generation/committed/pending carriers and allow
   foreign provenance to stand in for destination-runtime proof.

2. MainActivity can reach updater startup while durable Reset recovery still owns RestoreGate.
   UpdateUtil.beginMutation() correctly rejects ordinary mutation, but MainActivity consumes the
   exception under runCatching and creates no same-process retry owner.

This run establishes a third same-root recovery cell:

3. MainActivity only invokes UpdateUtil.updateOnStartup() when
   getDownloadsCountByStatus(Active, Queued) == 0.

If that predicate is false, there is no updater invocation, no durable deferral carrier, no
registered idle-transition retry owner and no same-process reconciliation owner.

The durable updater state machine itself is not the missing carrier:
- source selection uses commit() and fails rather than reporting false durable success;
- beginMutation() durably persists exact pending generation/source;
- a native failure leaves pending state;
- process death after pending publication and before native completion leaves discoverable debt;
- process death after native success but before committed provenance leaves pending debt for retry;
- committed generation/source/result and matching pending retirement are written in one editor
  commit;
- source changes are ordered by desired generation and updateMutex/SUPERSEDED handling.

The gap is the executor/recovery owner that consumes that durable debt after startup is blocked.

Concrete restore path:
- Restore post-commit reconciliation can reconstruct queued Download responsibility and enqueue
  runnable Download work before the restore is marked COMPLETE and its active pointer is retired.
- A corrected startup path that merely waits for Restore recovery can therefore still observe a
  queued row immediately afterward.
- MainActivity then skips updateOnStartup() because the count is nonzero.
- With auto_update_ytdlp=false there is no automatic freshness path that is allowed to substitute
  for the required desired/runtime reconciliation.
- When the queued/active work later becomes idle, current source has no event/reconciler that
  reissues the skipped startup convergence attempt.
- The changed desired source, desired/committed mismatch or durable pending mutation can therefore
  remain unconverged until another app launch or manual update.

This is SAME_ROOT_RESIDUAL, not a new root, because the canonical BUG-UPDATER-02 closure claimed
startup repairs durable desired/committed mismatch and the persisted pending carrier exists
specifically to recover interrupted updater mutation.

Canonical count impact: already counted; +0.

### BUG-UPDATER-03 — reconfirmed, out of active scope

BackupRestoreParser still accepts arbitrary String values without key-specific ytdlp_source
semantic validation, while UpdateUtil rejects a present blank ytdlp_source.

Classification remains:
- P2
- PRE_EXISTING_BASELINE_DEFECT
- active_scope=NO
- canonical active count impact=0

### BUG-HISTORY-05 — reconfirmed, out of active scope

Valid duplicate cleanup still copies keyword assignments but does not transfer duplicate-only
PlaylistItemCrossRef membership to the retained History row before deleting the duplicate
relationships.

Classification remains:
- P2
- PRE_EXISTING_BASELINE_DEFECT
- NEW_DISTINCT_ROOT relative to BUG-HISTORY-04
- active_scope=NO
- canonical active count impact=0

BUG-HISTORY-04 itself remains FIXED_CLOSED for its stale-candidate destructive-authority invariant.

## L1 process-death and recovery matrix

A. desired source persisted, no pending mutation:
- startup must compare desired vs committed and converge mismatch;
- current UpdateUtil can do so when invoked;
- MainActivity queue/Restore gates can leave it uninvoked.

B. exact pending mutation persisted, process dies before native update:
- pending survives;
- updateOnStartup() would retry even with automatic updates disabled;
- actual production startup can still skip invocation while Active/Queued exists.

C. native updater fails:
- exception/ERROR does not clear matching pending state;
- direct test proves a later updateOnStartup() can recover;
- production has no guaranteed later same-process call after a queue/Restore deferral.

D. native updater succeeds, process dies before committed provenance:
- durable pending remains the restart proof;
- same execution-owner gap applies at startup.

E. committed provenance published:
- matching pending is retired in the same SharedPreferences editor commit;
- no separate L1 root established at this boundary.

F. Reset journal/process death:
- RestoreOperationStore keeps plan/journal/active pointer under noBackupFilesDir;
- carrier publication is file-fsynced then atomically renamed;
- malformed active state is fail-closed;
- phase recovery re-enters PREPARED/QUIESCED/FILES_READY/APPLYING/DATA_COMMITTED/RECONCILING/COMPLETE;
- preference-commit failure is compensated in-process while the durable active journal remains the
  restart authority;
- post-commit reconciliation debt remains represented by DATA_COMMITTED/RECONCILING.
No distinct Restore coordinator durability root was established in this run.

## Trigger/module status

- Module A platform capability/admission: PASS for the freshly sampled scheduler/Resume supported
  range; no new L1 blocker.
- Module B external scheduler handoff: no new updater scheduler owner exists to close the startup
  deferral; existing Download restore scheduling remains separately recovery-owned.
- Module C external representation/schema/authority projection: FAIL — BUG-UPDATER-02 and
  BUG-UPDATER-03.
- Module D packaged resource/ABI provenance: PASS on fresh source sample.
- Module E shared-generation promotion: FAIL — updater runtime convergence remains unowned after
  startup rejection/deferral.
- Module F persisted schema-generation compatibility: FAIL — foreign updater generation/provenance
  is still portable.
- Module H persisted executable configuration fan-out: FAIL — BUG-UPDATER-03.
- Module I maintenance/live-owner namespace: PASS on fresh Terminal-cache sample.
- core destructive relationship preservation: FAIL repository-wide due known BUG-HISTORY-05,
  outside current active inventory.

## Preserved canonical closure recount

Fresh exact-source sampling did not establish a reopened canonical root among:
- BUG-DOWNLOAD-01
- BUG-LOCALADD-06
- BUG-SCHEDULER-05
- BUG-ABI-01
- BUG-HISTORY-04
- BUG-MIGRATION-01
- BUG-RUNTIME-01
- BUG-TERMINAL-06
- BUG-COOKIE-03
- BUG-BACKUP-11
- BUG-PAUSE-03
- BUG-RESUME-01
- BUG-TOOLING-01

Their governing source properties remain present: typed Download authority-read failure and exact
recovery responsibility, UUID LocalAdd pending discovery, pre-31 exact-alarm capability semantics,
arm64 runtime provenance, current-identity History deletion revalidation, reference-fenced
migration deletion, journaled exact-generation runtime installation, fail-closed Terminal cleanup,
serialized cookie projection and fail-closed request construction, destination-local command_path,
exact Pause-All execution leases, identity-bearing Resume/Retry PendingIntents, and exact materialized
Gradle launcher/distribution completeness checks.

## L1-L6 coverage

- L1 Durability & recovery: DEEP / FAIL.
- L2 Identity & provenance: DEEP / FAIL.
- L3 Concurrency & authority: BASELINE.
- L4 Destructive ownership: DEEP / FAIL.
- L5 Platform contract closure: DEEP.
- L6 Cross-feature semantic propagation: DEEP / FAIL.

primary_deep_lens: L1 Durability & recovery
primary_deep_selection_reason:
R1 — the adopted active root directly owns durable updater debt and restart/startup convergence.

remaining_not_yet_deep:
- L3

next_not_yet_deep_lens:
L3 Concurrency & authority

next_lens_selection_reason:
R1/R5 — L1 now closes the durability rotation for the current SHA; L3 is the sole remaining
not-yet-DEEP lens and is also relevant to overlap between updater native mutation, Restore authority
and Download runtime users.

## Review retrospective

This is a new same-SHA manual run. The preceding L4 manual run was FINAL and no resumable
IN_PROGRESS run existed at trigger start.

The full governing checklist and trigger map were recomputed. L1 was selected by R1 rather than
reusing prior verdicts because BUG-UPDATER-02's remaining same-SHA risk was durable recovery
ownership.

The review opened UpdateUtil's source-selection/pending/committed transitions, Restore's durable
operation store and phase recovery, App startup ordering, MainActivity's production startup gate,
Reset post-commit Download reconstruction and the existing updater/Reset production-wiring tests.

Existing UpdateUtil tests directly call updateOnStartup() and prove coordinator behavior once that
method is invoked. They do not prove the production MainActivity queue/Restore deferral eventually
reinvokes it.

No tests were independently executed in this run. Historical exact-SHA verification remains
historical evidence only.

The existing BUG-UPDATER-02 prompt was refined in place, without creating a duplicate prompt:
- private commit: e43c003b9ed0372569dd9cec9513fd0421a75ece
- prompt content blob: 25d032354d2c379cf4744679bbd39b75e44f8aa2
- added required queued-work startup deferral/recovery scenario.

No production source, production test, configuration, ledger or governance file was changed.

## Checklist evolution

No Review Checklist, lens-policy or Review Protocol change is required.

Existing v7 rules were sufficient:
- durable carrier does not prove recovery unless a surviving executor can discover and own it;
- asynchronous/conditional deferral is not completion;
- restart/recovery must remain owned across blocked attempts;
- consumer/final-effect closure requires proving the production entry path, not only direct helper
  invocation.

Implementation test evolution is required for BUG-UPDATER-02:
- exercise the real startup decision with Active/Queued blocking;
- transition to idle without recreating the app;
- prove exactly one deferred updater reconciliation occurs;
- combine this with changed restored source / auto_update_ytdlp=false and pending-mutation recovery.

## Checkpoint summary

manual_review_run_status: FINAL
overall_active_remediation_verdict: NOT_CLEAN
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
production_active_scope_p0: 0
production_active_scope_p1: 0
production_active_scope_p2: 1
active_finding: BUG-UPDATER-02
active_finding_relation: SAME_ROOT_RESIDUAL
active_finding_new_cell: STARTUP_ACTIVE_OR_QUEUED_DEFERRAL_HAS_NO_SAME_PROCESS_RECOVERY_OWNER
existing_out_of_scope_findings: BUG-UPDATER-03, BUG-HISTORY-05
new_finding_ids_this_run: 0
primary_deep_lens: L1
next_lens_hint: L3
known_good_baseline: NOT_CREATED
independent_execution: NOT_EXECUTED

INDEPENDENT EXECUTION: NOT EXECUTED
