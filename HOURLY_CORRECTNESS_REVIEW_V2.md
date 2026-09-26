# Hourly Correctness Review v2 — Orchestration Protocol

Status: ACTIVE orchestration protocol for the scheduled independent correctness reviewer.

This document defines scheduling, pinning, resume, evidence, and repository-write behavior for the hourly reviewer. It does **not** replace or revise the Master Plan, Review Checklist v6, canonical finding registry, status overlay, or existing review evidence. If this protocol conflicts with a governing semantic artifact, the governing semantic artifact wins.

## 1. Purpose

The scheduled reviewer is a long-running independent correctness-review pipeline over `ireum-0/ytdlnisx`.

A scheduler invocation is only an execution opportunity. It is **not automatically a new logical review run**.

The protocol must:
- preserve exact production and governance provenance;
- resume incomplete work without restarting or duplicating it;
- keep semantic review independent from the implementation diff;
- preserve existing review history append-only;
- avoid turning stale ledger metadata into production truth;
- avoid parallel/sibling writers corrupting the review history;
- separate scope-specific closure from the overall remediation gate;
- preserve uncertainty as `NOT_VERIFIED` rather than inventing evidence.

Protocol version: `hourly-correctness-review-v2.1`.

Compatibility note: checkpoints written under `hourly-correctness-review-v2` remain valid historical evidence. A logical run frozen to an older protocol blob finishes under that blob; only NEW logical runs use v2.1.

## 2. Authority model

### Production truth

Only `checkpoint/pre-baseline-review@<exact SHA>` is authoritative for:
- Android production source;
- tests and build files;
- implementation behavior;
- whether an alleged defect exists at the reviewed checkpoint.

Never infer production truth from the ancestry/tree of `plan/remediation`, `review/remediation`, or `ledger/remediation`.

### Semantic governance

At the start of every logical review run:

1. read `plan/remediation/SOURCE_ARTIFACTS.md`;
2. identify the recorded Master Plan identity and SHA-256;
3. read `YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md` from the same frozen `plan/remediation` commit and use it only if its SHA-256 exactly matches the identity recorded by `SOURCE_ARTIFACTS.md`;
4. read the current governing review checklist identified by the repository, currently `REVIEW_CHECKLIST_V6_OPERATIONAL.md`;
5. bind exact branch/file/blob identities used by the run.

The registered Master Plan identity for this protocol's adoption is:
- file: `YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md`;
- SHA-256: `7f3a554a87eae50368edaf0a35f0fcb5d4b85aaea532bb818c90dbc45d90c5fa`.

If the frozen-plan Master Plan body is unavailable or its hash does not match, record `MASTER_PLAN_BODY_NOT_VERIFIED`; do not fall back to an unpinned copy, do not fabricate its content, and do not claim Master-Plan-complete closure.

### Semantic-source precedence and historical-snapshot rule

The Master Plan contains both durable normative rules and a historical session-handoff snapshot. Do **not** treat all statements in it as equally current.

Use this precedence:

1. pinned `checkpoint/pre-baseline-review@<SHA>` — production behavior truth;
2. currently adopted `REVIEW_CHECKLIST_V6_OPERATIONAL.md` — review method and CLEAN/evidence gate;
3. effective canonical registry/status: `TASKS.md + TASKS_DELTA.md + CURRENT_STATUS.md + exact later review/closure evidence` — current finding identity/status/count/ownership state;
4. Master Plan — durable correctness invariants, severity/gate policy, workflow discipline, hard dependencies, and historical remediation intent where not superseded;
5. this v2 protocol — orchestration only.

The Master Plan's embedded branch SHAs, active-defect counts, "current F1 state", immediate-next-actions, and F1-F22 implementation order are a historical snapshot. They MUST NOT override later canonical registry/status/review evidence and MUST NOT be used as the automatic current-basis sweep order unless a current repository artifact explicitly re-adopts that order for the present state.

When a Master Plan hard dependency or invariant applies to an exact current root and has not been superseded, preserve it. When current status/history has evolved beyond the Master Plan snapshot, use the current registry/review evidence for state and queue decisions while retaining the Master Plan's still-valid normative invariant.

### Registry and status history

Interpret canonical history using:
- `TASKS.md` as historical baseline registry;
- `TASKS_DELTA.md` as append-only post-split finding records;
- `CURRENT_STATUS.md` as later status overlay;
- exact cited evidence and later `review/remediation` checkpoints as review evidence.

Do not silently rewrite historical state when newer review evidence exists but the ledger/status overlay has not yet been reconciled. Record, where material:
- `ledger_recorded_state`;
- `latest_review_evidence_state`;
- any unresolved reconciliation gap.

Review/evidence establishes semantic decisions. A scheduled review must not perform ledger closure.

### Review history

`review/remediation` is the append-only review-evidence/history branch for this automation. Existing evidence is input for deduplication, current-basis revalidation, resume state, and lens history; it is never production source truth.

## 3. Immutable logical-run binding

Each new logical review run must create a unique `logical_run_id` and freeze:

- `policy_version = hourly-correctness-review-v2`;
- exact `protocol_path = HOURLY_CORRECTNESS_REVIEW_V2.md`;
- exact `protocol_blob_sha` and the `plan/remediation` commit that supplied it;
- exact `implementation_sha`;
- exact starting `review_history_basis_sha`;
- exact `plan_sha`;
- exact `ledger_sha`;
- Master Plan identity/hash state;
- governing checklist path/blob/hash or adoption identity;
- canonical registry/status blobs actually consulted.

These semantic pins, including the protocol blob itself, remain fixed for the logical run. If a newer protocol revision appears while a run is incomplete, finish/resume that run under its frozen protocol blob; apply the newer protocol only to a new logical run.

If `checkpoint/pre-baseline-review` advances while a logical run is in progress:
- finish or checkpoint the current run against its frozen implementation SHA;
- record `superseded_by_current_head=<new SHA>`;
- do not silently switch target SHA mid-run;
- the next logical run reviews the newer SHA.

A changing `review/remediation` HEAD used only as a write parent does **not** change the frozen semantic governance or implementation basis.

### Logical-run termination semantics

Checkpoint kind controls logical-run lifecycle independently of correctness verdict:

- `FINAL` ALWAYS closes that `logical_run_id`. It is never resumed, even when `scope_verdict=NOT_CLEAN`, `overall_remediation_gate=NOT_CLEAN`, verification gaps remain, or `exact_next_action` names future work.
- `INTERMEDIATE` is resumable only when it explicitly records `resume_safe=true` and intact frozen pins.
- `BLOCKED` is resumable only when it explicitly records `resume_safe=true` and the recorded blocker has been proven resolved without invalidating the frozen pins.
- `remaining_review_scope` in a FINAL checkpoint describes future review work for a NEW logical run; it does not make the closed run incomplete.

When the newest valid checkpoint for a logical run is FINAL, the next eligible scheduler invocation must create a NEW logical run if work remains.

## 4. Scheduler invocation, resume, and single-writer behavior

Before starting new review work, inspect v2 checkpoints for an incomplete logical run.

If a valid incomplete run exists with intact frozen pins:
- resume that logical run;
- do not create a second logical run for the same work merely because a new hourly invocation fired.

If another writer is visibly advancing the same logical run or an overlapping scheduled review:
- do not race it;
- re-fetch the latest state;
- resume only from its verified append-only progress, or no-op with the conflict/blocker recorded if safe ownership cannot be established.

Treat a writer as visibly active when, after the invocation's initial observation/pin, a relevant production or review HEAD advances during that same invocation with evidence overlapping the intended review work, or when an explicit repository in-progress/lease marker proves ownership. A recent timestamp by itself is **not** an active-writer lock. A completed checkpoint that already existed before the invocation is review evidence, not a reason to no-op indefinitely.

Start a new logical run only when:
- no resumable v2 run remains; and
- the intended review target is not already being processed by another active writer.

Never use force-push, amend, history rewrite, destructive rebase, or overwrite as a recovery mechanism.

### Scheduled-invocation minimum-progress contract

After the diagnostic sequence below has completed, every scheduled invocation that passes the minimal bootstrap gate MUST do one of the following before it ends:

1. complete one non-duplicative review unit and append a FINAL checkpoint; or
2. append an INTERMEDIATE checkpoint with `resume_safe=true` after establishing durable review progress; or
3. append a BLOCKED checkpoint naming the concrete blocker and exact stage.

A passing bootstrap gate followed by a silent no-op is forbidden. "No material change" is still a valid completed logical run and therefore still gets a compact FINAL checkpoint.

Do not wait to read the full historical registry before establishing durable progress. At startup, fetch only:
- the four branch HEADs;
- the current protocol path/blob;
- the latest v2 FINAL/INTERMEDIATE/BLOCKED checkpoint metadata needed to resolve lifecycle;
- the latest diagnostic receipt, if any.

Large governance bodies and broad registry history are fetched only after this minimum bootstrap has been durably observed or when the selected review unit actually requires them.

### Temporary diagnostic probe sequence — STARTUP_OBSERVABILITY_V1

This sequence exists to diagnose scheduled-run behavior. Execute exactly ONE not-yet-passed probe per scheduler invocation, in order. Each probe writes exactly one NEW append-only UTF-8 markdown diagnostic receipt under:

`review/remediation:review-runs/checkpoints/hourly-v2/diagnostics/`

Diagnostic receipts are orchestration evidence, not semantic review checkpoints, and do not alter finding status or lens effectiveness. They bind the observed implementation/plan/review/ledger HEADs, protocol blob, previous diagnostic receipt path/blob when present, probe result, and exact next probe.

A probe receipt filename must include UTC timestamp, implementation short SHA, and probe id. After writing, re-fetch the file and commit and verify ancestry exactly like a normal checkpoint.

#### PROBE-1 — BOOTSTRAP_WRITE

Purpose: test whether the scheduled environment can reach GitHub read/write before large-document preflight.

Read only:
- current four branch HEADs;
- current protocol blob;
- latest v2 checkpoint metadata sufficient to identify the latest FINAL/INTERMEDIATE/BLOCKED item;
- latest diagnostic receipt, if any.

Do NOT read Master Plan, full v6, TASKS, TASKS_DELTA, CURRENT_STATUS, or production source for this probe.

Append one diagnostic receipt with:
- `diagnostic_sequence=STARTUP_OBSERVABILITY_V1`;
- `probe_id=PROBE-1-BOOTSTRAP-WRITE`;
- observed HEADs/protocol blob;
- latest v2 checkpoint path/blob/kind if available;
- `result=PASS`;
- `exact_next_probe=PROBE-2-FINAL-TERMINATION`.

Then STOP the invocation. If this receipt appears, basic scheduled GitHub write capability and minimal execution budget are proven.

#### PROBE-2 — FINAL_TERMINATION

Purpose: test whether FINAL/NOT_CLEAN ambiguity was preventing new logical runs.

Read the latest valid v2 checkpoint and its run binding. Apply the lifecycle rules above.

Append one diagnostic receipt recording:
- the latest checkpoint path/blob;
- its `logical_run_id`, `checkpoint_kind`, `scope_verdict`, and `overall_remediation_gate`;
- `resolved_run_closed=true` iff checkpoint_kind is FINAL;
- whether a new logical run is eligible;
- the deterministic next DEEP lens if same-SHA progression applies;
- `result=PASS`;
- `exact_next_probe=PROBE-3-GOVERNANCE-PIN`.

Then STOP the invocation.

#### PROBE-3 — GOVERNANCE_PIN

Purpose: test whether governance preflight can complete within the scheduled environment.

Freeze the current implementation/plan/review/ledger heads. Then:
- read `SOURCE_ARTIFACTS.md`;
- read the Master Plan from the same frozen plan commit and verify its registered SHA-256;
- read the governing v6 checklist identity/body needed for review;
- resolve TASKS/TASKS_DELTA/CURRENT_STATUS blob identities, but do not read their full bodies unless necessary for identity verification.

Append one diagnostic receipt with:
- frozen heads;
- Master Plan blob and hash verification;
- checklist blob/adoption identity;
- registry/status blob identities;
- `result=PASS`;
- `exact_next_probe=PROBE-4-MINIMUM-REVIEW-UNIT`.

Then STOP the invocation.

#### PROBE-4 — MINIMUM_REVIEW_UNIT

Purpose: test whether the scheduled environment can perform real source review and persist semantic progress.

Using the frozen pins and prior v2 lens matrix:
- select the deterministic next DEEP lens;
- review one concrete, bounded, non-duplicative production scope relevant to that lens;
- do not attempt a repository-wide sweep;
- append a normal v2 FINAL or INTERMEDIATE checkpoint satisfying the normal schema.

The normal checkpoint must additionally record:
- `diagnostic_sequence=STARTUP_OBSERVABILITY_V1`;
- `probe_id=PROBE-4-MINIMUM-REVIEW-UNIT`;
- `probe_result=PASS`.

After a verified PROBE-4 normal checkpoint exists, the diagnostic sequence is COMPLETE. Future invocations follow the normal minimum-progress contract and MUST NOT create more diagnostic receipts for this sequence.

If any probe cannot complete due to transport/auth/read/write failure, return a user-facing diagnostic naming the exact probe and stage. If enough state exists to write safely, append a BLOCKED diagnostic receipt; otherwise make no repository claim.

## 5. Review strategy

### Full semantic obligation

Review Checklist v6 remains the operational semantic review gate.

Do not use a diff-only review. For every blocker-relevant candidate, trace the real production path and all triggered v6 modules through final correctness-relevant effects.

Tests do not substitute for semantic source review, and source inspection does not substitute for execution evidence when v6 requires actual execution.

### New implementation SHA

On the first v2 logical run for a new implementation SHA:
- execute the complete mandatory v6 review order applicable to the reviewed scope;
- perform at least `BASELINE` coverage for L1-L6;
- choose one lens for `DEEP` review;
- evaluate semantic-contract-delta triggers and all triggered v6 conditional modules;
- revalidate relevant canonical open findings against the new current basis.

### Repeated implementation SHA

For later logical runs on the same implementation SHA:
- do not reuse the previous verdict as the new verdict;
- re-evaluate all v6 gates/triggers applicable to the current scope;
- revalidate open/current-basis candidates and any previous `NOT_VERIFIED` blocker-relevant cells;
- promote one not-yet-DEEP lens for that SHA to `DEEP`;
- do not indiscriminately re-audit unchanged repository areas when v6 trigger analysis proves no material semantic contract change.

If all L1-L6 are already DEEP, select a lens using the deterministic selection rule below.

## 6. Finding order, correctness, severity, and deduplication

Correctness is decided before attribution.

For every serious candidate:
1. decide whether the pinned production behavior violates an invariant;
2. then classify/deduplicate it.

Deduplicate against:
- `TASKS.md`;
- `TASKS_DELTA.md`;
- `CURRENT_STATUS.md` and cited evidence;
- existing relevant `review/remediation` checkpoints.

Do not create a new root merely because a known root has a new residual/subcase. Do not merge distinct roots merely because they share a helper, feature, or symptom.

Do not claim `introduced`, `pre_existing`, regression, or ownership classification without exact diff/baseline evidence.

Severity handling:
- P0: always blocker-relevant and must be surfaced immediately;
- P1/P2: governed by the Master Plan/v6 CLEAN gate;
- P3: record and track, normally nonblocking unless governing evidence explicitly says otherwise.

Default work priority:
1. newly introduced or newly exposed P0/P1/P2 on the pinned SHA;
2. unresolved blocker/status-transition evidence from the latest valid review;
3. the same SHA's required lens-coverage progression and blind-spot search;
4. a different canonical open root when current review evidence supplies a meaningful next root or when the selected lens naturally targets it;
5. P3/nonblocking work.

Do not use the Master Plan's historical F1-F22 implementation order as the default current-basis sweep order.

### Same-SHA anti-starvation, lens progression, and current-basis coverage

Do not let one unchanged OPEN finding monopolize every scheduler invocation, but also do not reconstruct the entire historical registry merely to pick a different root.

For each implementation SHA, maintain:
- `current_basis_review_coverage` for canonical roots actually revalidated at that SHA;
- `lens_coverage_current_sha` as the primary same-SHA progress mechanism;
- enough scope history to avoid repeating an identical no-op review.

Existing pre-v2 exact-SHA review evidence may count for root queue/sweep context when the root, exact implementation SHA, and disposition are explicit and unambiguous; it does not become v2 lens/effectiveness history.

On a repeated SHA with no new implementation/status evidence:
1. select the next DEEP lens using the deterministic lens rule;
2. choose a concrete production scope that is relevant to that lens and was not already exhaustively DEEP-reviewed for the same SHA;
3. prefer unresolved `NOT_VERIFIED` cells, cross-feature propagation, or a different known open root when they give the selected lens meaningful work;
4. do not repeat the same root/path/evidence merely because the scheduler fired.

An explicit current review queue may select a different canonical root when it is current and exact-SHA-compatible. If there is no such usable queue, lens progression itself is sufficient; do **not** require a full effective-OPEN-registry reconstruction solely to choose the next hourly scope.

If an intentional canonical-root sweep is performed, build its candidate set from the effective current registry/status rather than the Master Plan's historical defect snapshot. Use stable canonical identifier order only when the effective candidate set is already explicitly available and no stronger current queue exists.

A new implementation SHA resets per-SHA lens coverage and root current-basis coverage. A root that remains OPEN may be revisited when new code/evidence/status appears, when an unresolved verification cell can be advanced, or when the selected DEEP lens gives a specific non-duplicative reason.

## 7. Scope verdict versus overall remediation gate

Never overload one `CLEAN` label.

Every final v2 checkpoint must contain:

- `scope_verdict: CLEAN | CLEAN_WITH_WAIVERS | NOT_CLEAN | COVERAGE_INCOMPLETE`;
- `overall_remediation_gate: CLEAN | CLEAN_WITH_WAIVERS | NOT_CLEAN | NOT_VERIFIED`.

`scope_verdict` applies only to the exact review scope/finding/change evaluated by that logical run.

`overall_remediation_gate` reflects the known canonical project-wide blocker state supported by the frozen evidence set.

Closing one finding does not make the overall project CLEAN while unrelated blockers remain.

For current-change P1/P2:
- CLEAN requires open = 0 and accepted/waived = 0, plus every execution/evidence requirement imposed by v6;
- CLEAN_WITH_WAIVERS requires open = 0 plus an explicit accepted/waived current-change P1/P2 with exact governing evidence;
- any open current-change P1/P2 => NOT_CLEAN.

P0 is always blocker-relevant. Do not invent a waiver.

If semantic source closure appears complete but required v6 execution evidence is missing, do not issue official CLEAN. Record the verification gap explicitly and keep the formal verdict fail-closed.

## 8. Lens coverage and effectiveness

Lens definitions:

- `L1 Durability & recovery`
- `L2 Identity & provenance`
- `L3 Concurrency & authority`
- `L4 Destructive ownership`
- `L5 Platform contract closure`
- `L6 Cross-feature semantic propagation`

For each implementation SHA maintain:

`lens_coverage_current_sha: {L1: NOT_RUN|BASELINE|DEEP, ..., L6: NOT_RUN|BASELINE|DEEP}`

A new SHA does not inherit DEEP coverage from an older SHA.

For v2 cumulative lens state:
- authoritative cumulative data starts with `policy_version=hourly-correctness-review-v2`;
- older checkpoints may be reused only when the required field is explicit and unambiguous;
- never infer modern lens state or numeric effectiveness from legacy prose.

DEEP selection order:
1. direct relevance to changed code/open blocker;
2. lowest verified global v2 DEEP count;
3. longest time since verified DEEP;
4. fixed deterministic tie-breaker L1 -> L2 -> L3 -> L4 -> L5 -> L6.

Effectiveness must not be reduced to a single score or finding-count ranking.

For each lens record explicit values or `NOT_VERIFIED` with reason for:
- `coverage_level`;
- `new_confirmed_findings`;
- `existing_finding_status_changes`;
- `confirmed_residuals_or_subcases`;
- `rejected_candidates_with_proof`;
- `not_verified_candidates`;
- `checklist_gaps_triggered`;
- `upstream_semantic_checks`;
- `cross_feature_propagation_hits`;
- `lens_scope_reviewed`.

Use one `primary_detecting_lens` for a finding/status change and optional `supporting_lenses`; never double-count.

## 9. Execution-evidence provenance

For every material test/execution claim, record origin:

- `CI`;
- `IMPLEMENTATION_AGENT`;
- `INDEPENDENT_REVIEWER`;
- `OTHER_EXACT_SOURCE`;
- `NOT_EXECUTED` / `NOT_VERIFIED`.

Evidence executed by another exact-SHA agent may be cited as evidence when provenance and exact SHA are verified, but never relabel it as independent reviewer execution.

If exact execution required by v6 is unavailable, preserve the gap; do not silently convert source-level confidence into PASS.

## 10. Checkpoint namespace and write allowlist

The ONLY repository writes permitted to this scheduled reviewer are NEW append-only UTF-8 markdown files under:

`review/remediation:review-runs/checkpoints/hourly-v2/`

This includes the temporary diagnostic subdirectory `review-runs/checkpoints/hourly-v2/diagnostics/` defined by `STARTUP_OBSERVABILITY_V1`.

Everything else is read-only, including:
- `checkpoint/pre-baseline-review`;
- `plan/remediation`;
- `ledger/remediation`;
- application source/tests/build files;
- `TASKS.md`, `TASKS_DELTA.md`, `CURRENT_STATUS.md`;
- checklist/governance files;
- existing checkpoint/evidence files.

Never update, overwrite, delete, rename, or move an existing checkpoint.

A finding, status transition, checklist gap, or proposed checklist change discovered by the scheduled reviewer is recorded in the new v2 checkpoint as evidence/proposal. The scheduled reviewer does not perform canonical ledger merge/closure.

## 11. When to write checkpoints

Do not create repository noise merely because time passed.

Write a v2 checkpoint when at least one of these is true:
- the invocation must end before the logical review run is complete;
- a new confirmed P0/P1/P2 or material status transition is established;
- a material DEEP-lens milestone is complete and needed for reliable resume;
- a blocker-relevant `NOT_VERIFIED` boundary prevents further safe progress;
- immediately before publishing the final logical-run verdict.

A short logical run may therefore have only one final checkpoint.

After `STARTUP_OBSERVABILITY_V1` completes, a scheduled invocation that passed minimal bootstrap may not end silently. If interrupted before semantic material state is established, append a compact BLOCKED checkpoint identifying the exact stop stage and whether the cause is `BUDGET_OR_TIMEOUT_SUSPECTED`, transport/auth failure, lifecycle ambiguity, or another concrete blocker. The only exception is failure before repository write capability itself can be established.

## 12. Required checkpoint schema

Every v2 checkpoint must include, as applicable:

- `policy_version`;
- `protocol_path` and exact `protocol_blob_sha`;
- `logical_run_id`;
- `checkpoint_kind: INTERMEDIATE | FINAL | BLOCKED`;
- exact frozen `implementation_sha`;
- `review_history_basis_sha`;
- frozen `plan_sha`;
- frozen `ledger_sha`;
- Master Plan identity/hash verification state;
- governing checklist path/blob/adoption identity;
- relevant registry/status blobs;
- `review_parent_sha` used for the write;
- completed review scope;
- remaining review scope;
- `scope_verdict`;
- `overall_remediation_gate`;
- current P0/P1/P2/P3 findings/status changes;
- separately owned/nonblocking findings;
- verification gaps;
- v6 terminal/cross-attempt/live-owner matrix state where triggered;
- triggered conditional modules and closure state;
- semantic-contract delta / consumer closure / authority-effect closure state;
- `lens_coverage_current_sha`;
- `primary_deep_lens`;
- `lens_selection_reason`;
- per-lens effectiveness raw fields;
- execution-evidence provenance;
- ledger-vs-review reconciliation gaps, if any;
- `superseded_by_current_head`, when applicable;
- `current_basis_review_coverage` and selected/next review focus where applicable;
- `exact_next_action`, preferably expressed as the next SHA-change review or the next deterministic DEEP-lens/scope step rather than an unverified root guess.

Missing required evidence is represented as `NOT_VERIFIED` plus reason, never by omitting the field or guessing.

## 13. Append-only race resilience and idempotence

Immediately before each write:
1. fresh-fetch `review/remediation` HEAD;
2. inspect new commits/checkpoints since the run's last verified write;
3. verify no conflicting/overlapping writer has invalidated the intended write;
4. use the fresh HEAD as `review_parent_sha`.

Filename format:

`<UTC timestamp>__<implementation-short-sha>__<logical-run-short-id>__<sequence>__<kind>.md`

Include enough collision-resistant material to avoid same-second collisions.

After writing:
- re-fetch the created file and commit;
- verify the commit is in current `review/remediation` ancestry;
- record/use the actual resulting blob/commit as the resume basis.

If a write response is ambiguous or a non-fast-forward/race occurs:
- do not force-push, amend, rebase, or modify an existing file;
- fresh-fetch HEAD;
- first search for an already-successful equivalent checkpoint for the same `logical_run_id + checkpoint_kind + sequence/content identity`;
- if equivalent evidence already exists, treat it as the successful write after verification;
- otherwise reassess new intervening evidence and, only if still semantically valid, append a new uniquely named checkpoint.

Retry append-only at most three times for transport/race recovery. After that, report exact failure stage and last verified HEAD.

## 14. Failure and incompleteness policy

Fail closed.

Use `COVERAGE_INCOMPLETE` or `NOT_VERIFIED` when required scope/evidence cannot be completed.

Do not reduce review scope merely to produce CLEAN before the invocation ends.

Do not turn:
- missing evidence into zero/false;
- prose into numeric effectiveness;
- legacy fields into modern L1-L6 state;
- request acceptance into asynchronous completion;
- absence into ownership/revocation proof;
- old ledger metadata into current production truth.

## 15. Final user-facing report

For a material result, report in this order:

1. `Independent verdict`
2. `Findings`
3. `Review retrospective`
4. `Checklist evolution`
5. `Checkpoint summary`

Clearly distinguish:
- exact reviewed implementation SHA;
- scope verdict;
- overall remediation gate;
- new findings;
- existing finding status changes;
- verification gaps;
- exact next action.

If a completed logical run has no material change from the prior valid run, keep the user-facing report concise while still recording the required final v2 checkpoint.

Every scheduled invocation MUST emit a concise user-facing execution result, including diagnostic-probe invocations and blocked/no-material-change runs. Do not intentionally suppress the response merely because there is no new finding.

