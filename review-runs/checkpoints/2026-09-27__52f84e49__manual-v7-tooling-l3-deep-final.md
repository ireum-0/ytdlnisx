# Manual correctness review — 52f84e49 — tooling L3 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 17dcfb5175ffa63a692bf0e3d66f42b4445b420a

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 2fb718d8d094d5952bae342ee7977724a3e42616
intermediate_checkpoint:
review-runs/checkpoints/2026-09-27__52f84e49__manual-v7-tooling-l3-deep-intermediate.md
intermediate_commit: 188d66ae1f229e8c24f70bbbb08e1a1d18210564

implementation_sha: 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a
implementation_parent: c08bd73f0e852564e0e2a44c9bb71f77a04e8cd4
implementation_branch: checkpoint/pre-baseline-review

## Pinned governance

- Master Plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
- plan/remediation: 2145847a1054da28398b730b9be0ca728668f967
- protocol blob: 71e2be79a50ec79051400f3b34f1eb4e91fcac2d
- checklist v7 adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
- checklist v7 blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
- lens policy adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
- lens policy blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- ledger/remediation: b98d315006fa19fc6f22b017f43a91899db5fb81

Pinned governance remained unchanged through the run.

Compatible review movement after run start:
- 5f4b6ba3d23fb05d74644b179532b8e4e5d29d59
  recorded the implementation-agent stop under the no-force/normal-fast-forward
  governance boundary.
- 2fb718d8d094d5952bae342ee7977724a3e42616
  independently confirmed that the implementation branch is unprotected,
  branch protection is disabled, repository rulesets are empty, and current
  workflows do not provide a serialized branch writer.

Those later checkpoints are compatible with this run and do not change the
pinned implementation or governance basis.

## Independent verdict

NOT_CLEAN.

Current tooling disposition:
- BUG-TOOLING-01: FIXED-CLOSED at 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a.
- BUG-TOOLING-02: OPEN P2 / SAME-ROOT RESIDUAL / GOVERNANCE BLOCKED.
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=1
- new_finding_ids=0

Production active-remediation counts remain:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=13

INDEPENDENT EXECUTION: NOT EXECUTED

## Findings

### BUG-TOOLING-01 — FIXED-CLOSED

Independent source review confirms the exact-source correction.

Normal verification materializes a run-scoped detached Git worktree at the
tested SHA, verifies its SHA/tree/clean state, and executes compile/JVM/
connected/diff gates from that worktree rather than the mutable implementation
worktree. Per-gate and whole-run execution-lifetime provenance is recorded.

Complete-Wave consumes that provenance and requires exact candidate SHA/tree,
run-owned materialization identity, clean execution state, canonical
materialized gradlew.bat identity, and PASS lifetime records for every required
gate.

The acceptance harness synchronizes a transient mutation in the original
source worktree and proves the gate consumes the committed value from the
detached materialization.

No supported current evidence establishes a remaining BUG-TOOLING-01 residual.

### BUG-TOOLING-02 — OPEN P2 / GOVERNANCE BLOCKED

The current implementation closes the prior mutable-HEAD subcase:
- local HEAD/tree/worktree are revalidated before Push;
- review and destination refs are re-read;
- destination is read last;
- Push uses exact TestedSha as its immutable source object.

The same-root final write-authority interval remains.

For a candidate chain A -> B -> C:

1. final destination observation accepts A;
2. a concurrent writer moves the destination A -> B after that observation;
3. the current normal fast-forward push of exact C is still accepted because B
   is an ancestor of C;
4. post-push equality and 0/0 checks observe C and therefore cannot prove that
   no intervening writer race occurred.

The current destination-race acceptance cell only covers movement visible to
the final destination read. It does not cover movement after the accepted read
and before the update.

Current repository capability review confirms there is no existing server-side
serialization boundary that closes this interval:
- checkpoint/pre-baseline-review is unprotected;
- branch protection is disabled;
- repository rulesets are empty;
- current workflows do not provide a serialized implementation-branch writer.

Pinned governance also prohibits force-push/history rewrite and authorizes
normal fast-forward push. Therefore an expected-old conditional write that
depends on force semantics is not currently authorized.

No presently established implementation-only path under tools/remediation/**
proves exact final write authority while preserving the current no-force /
normal-forward contract.

BUG-TOOLING-02 therefore remains OPEN P2 / GOVERNANCE BLOCKED.
No implementation prompt is currently authorized for this root.

### BUG-DOWNLOAD-01 — fresh current-SHA confirmation

Production source at 52f84e49 still contains the existing authoritative-read
root.

- shouldStopForUserRequest() converts
  dao.getNullableDownloadById(...) failure through getOrNull() to null; null is
  treated as lost execution ownership and can produce ordinary STOP.
- the child finalizer performs the same collapse; an indeterminate read can
  make stillOwnsAttempt false and release exact process-local ownership and
  bookkeeping as if a newer owner had been proven.

No new production finding ID is created.

## Trigger/module status

- exact-source execution-tree identity/provenance:
  TRIGGERED / CLOSED — BUG-TOOLING-01.
- final remote mutation authority / writer race:
  TRIGGERED / OPEN — BUG-TOOLING-02.
- protected workspace/history:
  TRIGGERED / PASS for no reset/clean/rebase/amend/squash/force path in the
  reviewed tooling range.
- execution evidence producer/consumer closure:
  TRIGGERED / PASS for BUG-TOOLING-01.
- final destination compare-and-swap / sole-writer authority:
  TRIGGERED / NOT AVAILABLE in current repository/governance configuration.
- tooling platform/path contract:
  TRIGGERED / source-level PASS; independent runtime execution NOT EXECUTED.
- production exact live-owner preservation:
  TRIGGERED / OPEN — BUG-DOWNLOAD-01.
- newly triggered schema/ABI/external-representation module:
  NONE.

## L1-L6 coverage

- L1 Durability & recovery: BASELINE
  unique evidence runs, retained materialization and fail-closed missing
  evidence remain present; no new L1 root found.
- L2 Identity & provenance: BASELINE
  BUG-TOOLING-01 is closed; BUG-TOOLING-02 retains destination authority
  identity at the actual write boundary.
- L3 Concurrency & authority: DEEP
  primary lens; final compatible-writer race and governance block confirmed.
- L4 Destructive ownership: BASELINE
  no force/rewrite/cleanup path exists; normal durable branch mutation remains
  blocked by BUG-TOOLING-02 final-authority requirements.
- L5 Platform contract closure: BASELINE
  short Windows materialization path and canonical launcher contract are
  coherent at source level.
- L6 Cross-feature semantic propagation: BASELINE
  lifetime provenance propagates from verification producer to completion
  consumer and acceptance harness; final write authority is localized to the
  completion mutation boundary.

primary_deep_lens: L3 Concurrency & authority
primary_deep_selection_reason:
R1/R3 — the only open current-change tooling root is the final destination
writer race at the durable branch mutation boundary.

remaining_not_yet_deep:
- L1
- L2
- L4
- L5
- L6

next_not_yet_deep_lens: L4 Destructive ownership
next_lens_selection_reason:
After L3, L4 has the strongest direct relevance because the unresolved root
controls a durable branch update and must preserve exact mutation ownership
without force or rewrite.

## Review retrospective

The prior completion review correctly closed BUG-TOOLING-01 and retained
BUG-TOOLING-02.

This manual run independently traced final source rather than relying on that
verdict. It confirmed the detached-worktree execution contract end-to-end and
then followed destination authority through the final ls-remote, exact-object
push, and post-push equality check.

The material new reconciliation during this run was governance, not source:
the implementation agent attempted the recorded follow-up, made no new
implementation change, and stopped because the obvious expected-old
conditional mechanism required write semantics outside the current no-force
contract. Subsequent repository-capability review found no existing protected
or serialized sole-writer mechanism that could close the gap.

The correct state is therefore not another implementation retry. It is an
explicit reviewer/governance stop.

## Checklist evolution

Checklist v7 and the current review protocol are sufficient to detect and
describe the defect.

No checklist rule change is required.

A future governance-compatible closure must first establish an exact
server-side mutation-authority mechanism that:
- prevents or detects an intervening writer after the last accepted authority
  observation;
- preserves normal forward history and the no-force/no-rewrite contract;
- is itself independently reviewed before tooling implementation resumes.

Until such authority exists, no implementation prompt should claim to close
BUG-TOOLING-02.

## Checkpoint summary

manual_review_run_status: FINAL
overall_verdict: NOT_CLEAN

implementation_sha:
52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a

production_counts:
P0=0 / P1=0 / P2=13

tooling_counts:
P0=0 / P1=0 / P2=1

BUG-TOOLING-01:
FIXED-CLOSED

BUG-TOOLING-02:
OPEN P2 / SAME-ROOT RESIDUAL / GOVERNANCE BLOCKED

new_finding_ids: 0

primary_deep_lens: L3
next_not_yet_deep_lens: L4

next_action_owner:
REVIEWER / GOVERNANCE

implementation_prompt_authorized:
NO

production BUG-DOWNLOAD-01 remains blocked until BUG-TOOLING-02 closes.

INDEPENDENT EXECUTION: NOT EXECUTED
