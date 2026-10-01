# Manual correctness review — 256a5cf5 — L1 durability and recovery (intermediate)

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 588c79e062e03c33dab33f796d1e43fc009758af

implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c
master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY

overall_verdict: NOT_CLEAN
active_scope_p0: 0
active_scope_p1: 0
active_scope_p2: 1
active_scope_open_root: BUG-UPDATER-02

primary_deep_lens: L1 Durability & recovery
primary_deep_selection_reason: R1 — the adopted active root directly owns startup/restart convergence and durable updater recovery.

## L1 finding — BUG-UPDATER-02 same-root residual extended

Already-established cells remain:
1. source-device updater desired-generation/committed/pending authority is portable across backup/restore;
2. MainActivity can reach updater startup while active Reset recovery still owns RestoreGate; ordinary updater admission is rejected and the outer runCatching drops the failure without a same-process retry owner.

This run establishes an additional recovery cell of the same root:
3. MainActivity calls UpdateUtil.updateOnStartup() only when Download rows in Active/Queued are zero.
   If any Active/Queued row exists, updater startup returns without creating durable retry/deferred responsibility.
   The code has no later idle-transition owner that reissues startup reconciliation in the same process.

This matters independently of automatic update freshness:
- UpdateUtil uses durable pending generation/source to recover interrupted native update;
- desired/committed mismatch also requires startup reconciliation;
- but the durable marker does not itself schedule/re-own execution;
- when MainActivity's one-shot queue precondition is false, the pending/mismatch survives but no live recovery owner consumes it until another launch/manual update.

Restore makes the counterexample concrete:
- Reset post-commit reconciliation may reconstruct queued download responsibility and start restored runnable work;
- after Restore completes, a correctly sequenced updater startup may therefore observe queued work and skip;
- changed restored source with auto_update_ytdlp=false can then remain unconverged even after RestoreGate is released.

Relation: SAME_ROOT_RESIDUAL under BUG-UPDATER-02.
Canonical count impact: already counted, +0.

Required closure:
- retain the safety rule that updater mutation must not race active download/native use if that rule is required;
- but a blocked startup convergence attempt must acquire durable or live deferred responsibility and run after the blocking condition clears;
- changed restored source with auto_update_ytdlp=false must converge in the same process even when runnable Download work exists at initial post-Restore observation;
- interrupted updater pending state must likewise remain actively recoverable rather than requiring another app launch.

## Updater process-death matrix

- source selection commit fails -> caller throws; no false durable success.
- pending generation/source committed, then process dies before native update -> pending survives and can force retry if startup reconciliation is actually invoked.
- native update fails/returns ERROR -> pending remains.
- native update succeeds, process dies before committed provenance -> pending remains; retry is idempotent/reconciling.
- committed generation/source/result and matching pending retirement use one SharedPreferences editor commit.
- source changes while older request is active -> update mutex + desired-source recheck/SUPERSEDED ordering preserve request identity.
- open durability gap is execution ownership after startup deferral/rejection, not absence of a pending carrier.

## Trigger map

- Module C external representation/schema/authority projection: FAIL — BUG-UPDATER-02 foreign runtime authority; BUG-UPDATER-03 malformed portable source.
- Module E shared-generation promotion: FAIL — destination updater runtime convergence remains unowned after startup rejection/deferral.
- Module F persisted schema-generation compatibility: FAIL — portable generation/provenance is not destination-local proof.
- Module H persisted executable configuration fan-out: FAIL — BUG-UPDATER-03 blank source contract.
- Core recovery discovery/durability: FAIL — pending/mismatch is discoverable in preferences but MainActivity one-shot conditions can leave it without an active recovery owner.
- Core destructive identity/reference: FAIL repository-wide because BUG-HISTORY-05 remains known out-of-scope; no new destructive root in this run.

## Baseline L1-L6

- L1: DEEP / FAIL — BUG-UPDATER-02 startup recovery ownership gap.
- L2: DEEP / FAIL — prior same-SHA coverage, fresh provenance reread reconfirmed.
- L3: BASELINE — no new separate authority root established in this run.
- L4: DEEP / FAIL — BUG-HISTORY-05 pre-existing out-of-scope root from prior same-SHA run.
- L5: DEEP — prior same-SHA coverage; fresh platform/ABI/scheduler/Resume samples preserved.
- L6: DEEP / FAIL — updater restore/runtime propagation remains open.

Known out-of-scope repository P2 roots:
- BUG-UPDATER-03 — PRE_EXISTING_BASELINE_DEFECT
- BUG-HISTORY-05 — PRE_EXISTING_BASELINE_DEFECT

Preserved active/canonical closures were reread at their production mutation boundaries; no additional reopened canonical root was established.

remaining_not_yet_deep:
- L3

next_lens_hint: L3 Concurrency & authority

independent_execution: NOT_EXECUTED
