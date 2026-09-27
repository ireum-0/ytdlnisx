# Manual correctness review — 7d6a7b7c — tooling CAS L3 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 809da59918b121b9454e6a2167e375d96738cf37

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 65e2cb24de30c5d57aa4360959debdbe9e31e961
intermediate_checkpoint:
review-runs/checkpoints/2026-09-27__7d6a7b7c__manual-v7-tooling-cas-l3-deep-intermediate.md
intermediate_commit: 65e2cb24de30c5d57aa4360959debdbe9e31e961

implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
implementation_parent: 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a
implementation_branch: checkpoint/pre-baseline-review

## Pinned governance

- Master Plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
- plan/remediation: 2145847a1054da28398b730b9be0ca728668f967
- protocol blob: d9d112148965c0e4151e653015842dc783f52916
- checklist v7 adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
- checklist v7 blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
- lens policy adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
- lens policy blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- ledger/remediation: b98d315006fa19fc6f22b017f43a91899db5fb81

Governance remained fixed for the whole manual run.

## Independent verdict

NOT_CLEAN repository-wide because production remediation remains open.

Tooling disposition:
- BUG-TOOLING-01 = FIXED-CLOSED
- BUG-TOOLING-02 = FIXED-CLOSED at 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=0
- new_finding_ids=0

Production active-remediation counts remain:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=13

INDEPENDENT EXECUTION: NOT EXECUTED

## Findings

No new tooling finding was confirmed.

### BUG-TOOLING-01

FIXED-CLOSED remains supported.

Exact-source execution still uses the detached run-scoped candidate worktree,
per-gate/whole-run execution-lifetime provenance, exact SHA/tree binding,
canonical materialized launcher, and post-gate clean-state checks.

The current range does not weaken that contract.

### BUG-TOOLING-02

FIXED-CLOSED is independently supported.

The final implementation-ref mutation is now bound to:
- exact tested source object;
- exact freshly accepted old destination object;
- exact destination ref;
- independently proven old-destination -> TestedSha forward ancestry;
- exact expected-old Section 1.1 CAS lease.

The write uses the exact tested SHA as source and an exact-ref/exact-old
force-with-lease guard.

If another writer changes the destination after final observation, the lease
fails. Completion records PUSH_REJECTED_NO_RECONCILIATION and has no retry or
automatic reconciliation path.

The deterministic B -> C -> X final-boundary fixture pauses at the actual target
push invocation after B has been accepted, advances the remote to C, then
requires the B lease to reject X. Its pass condition requires one target update
attempt, nonzero push exit, no retry, remote retained at C, and local tested
candidate still X.

Successful publication is accepted only after exact remote equality with
TestedSha and 0/0 ahead/behind.

The protocol's narrow CAS exception is therefore implemented without granting a
non-forward history rewrite path.

### BUG-DOWNLOAD-01

OPEN P2 remains independently confirmed on the current SHA.

DownloadWorker.shouldStopForUserRequest() still collapses
dao.getNullableDownloadById(...) read failure through getOrNull() to null.
For a nonblank execution token, null can become lostExecutionOwnership and
ordinary STOP.

The child finalizer also collapses the authoritative read to null; that can make
stillOwnsAttempt false and release exact process-local execution/process
ownership as if transfer to a newer owner had been proven.

DownloadExecutionRecovery itself retains durable/live recovery responsibility
across recovery-loop read failures, so the worker-side collapse remains the
material gap.

No new production finding ID is created.

## Review retrospective

This run did not reuse the prior implementation-completion verdict as proof.

It independently traced:
candidate/ref authority
-> final local/review/destination observations
-> forward-ancestry proof
-> exact-old lease construction
-> actual target push arguments
-> lease-failure path
-> successful post-push equality
-> synchronized after-observation race fixture.

The previous BUG-TOOLING-02 residual is closed because the exact-old condition
now survives to the server-side ref update itself.

A review/remediation movement after its final observation is not promoted into a
new root under the pinned protocol: Section 3.5 treats review/remediation as an
append-only semantic baseline with fresh pre-write reconciliation, not as a
second ref that must be atomically CASed together with the implementation ref.

The current implementation SHA and review tip remained unchanged while this
manual run was in progress.

## Checklist evolution

No checklist or protocol change is required by this run.

Existing L3 concurrency/authority and L4 mutation-ownership rules, together with
Protocol Section 1.1, were sufficient to distinguish:
- prohibited non-forward force/rewrite;
- permitted exact-old CAS used only to guard an independently proven forward
  update.

The acceptance suite now contains 15 unique IDs including the actual
after-observation final-boundary writer race.

## Trigger/module status

- exact-source execution-tree identity/provenance:
  TRIGGERED / CLOSED — BUG-TOOLING-01
- final implementation-ref write authority:
  TRIGGERED / CLOSED — BUG-TOOLING-02
- actual write-boundary competing-writer race:
  TRIGGERED / PASS at source level
- lease failure retry/reconciliation:
  TRIGGERED / PASS — STOP, no retry
- forward-only ancestry:
  TRIGGERED / PASS
- post-publication exact equality:
  TRIGGERED / PASS
- protected history/workspace:
  TRIGGERED / PASS at source level
- production exact live-owner preservation:
  TRIGGERED / OPEN — BUG-DOWNLOAD-01
- new schema/ABI/external-representation trigger from this tooling-only range:
  NONE

## L1-L6 coverage

- L1 Durability & recovery: BASELINE
- L2 Identity & provenance: BASELINE
- L3 Concurrency & authority: DEEP
- L4 Destructive ownership: BASELINE
- L5 Platform contract closure: BASELINE
- L6 Cross-feature semantic propagation: BASELINE

primary_deep_lens: L3 Concurrency & authority
primary_deep_selection_reason:
R1/R3 — this SHA's semantic change is the server-side final writer authority
guard for a concurrent implementation-ref race.

remaining_not_yet_deep:
- L1
- L2
- L4
- L5
- L6

next_not_yet_deep_lens: L4 Destructive ownership
next_lens_selection_reason:
The narrow Section 1.1 lease uses force-with-lease spelling; the next review
should deeply re-check that every destructive/mutation path remains strictly
forward-only, exact-owner-scoped, and non-rewriting.

## Checkpoint summary

manual_review_run_status: FINAL
overall_verdict: NOT_CLEAN

implementation_sha:
7d6a7b7c445d9e45297032fa0521a1fc1d732eb9

tooling_counts:
P0=0 / P1=0 / P2=0

production_counts:
P0=0 / P1=0 / P2=13

BUG-TOOLING-01:
FIXED-CLOSED

BUG-TOOLING-02:
FIXED-CLOSED

BUG-DOWNLOAD-01:
OPEN P2 / REMEDIATION-READY

new_finding_ids: 0

primary_deep_lens: L3
next_not_yet_deep_lens: L4

next_action_owner:
IMPLEMENTATION_AGENT

next_dependency_eligible_root:
BUG-DOWNLOAD-01

INDEPENDENT EXECUTION: NOT EXECUTED
