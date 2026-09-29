# Manual correctness review — 7d6a7b7c — production L1 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: bfee01df19aadf5a173c65da4bd51367cb313ec5
checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 891bdb041f7816223ccd963bd6e688c9b6e23a7f
intermediate_commit: 891bdb041f7816223ccd963bd6e688c9b6e23a7f
implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9

master_plan: fada33a7eed86b1fa2c07065af66f14bf4d24714
plan_tip: 2145847a1054da28398b730b9be0ca728668f967
protocol_blob: d9d112148965c0e4151e653015842dc783f52916
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
ledger_tip: b98d315006fa19fc6f22b017f43a91899db5fb81

reported_local_candidate_sha: 5949f31120fcf8678256949a6eb8659dba589bfa
reported_local_candidate_status: UNPUBLISHED_NOT_SOURCE_VERIFIED

## Independent verdict

NOT_CLEAN.

Production counts remain P0=0 / P1=0 / P2=13.
Current canonical tooling count remains P2=1.

The latest canonical BUG-TOOLING-01 residual is based on an unpublished local
candidate. This manual run cannot source-review that candidate from GitHub, so
its exact implementation mechanics remain NOT_VERIFIED. The canonical OPEN P2
disposition is preserved rather than overwritten from the older remote SHA.

No new finding ID is created.

INDEPENDENT EXECUTION: NOT EXECUTED

## Findings

L1 Durability & recovery is DEEP on remote implementation 7d6a7b7c.

BUG-DOWNLOAD-01 remains OPEN P2. Current DownloadWorker still collapses
authoritative row-read failure to null at both the stop predicate and child
finalizer. The dedicated recovery subsystem has durable DB/journal/native/
producer carriers and a live retry owner, but these worker-side reads can bypass
that recovery contract and retire/stop authority on an indeterminate read.

BUG-LOCALADD-06 remains OPEN P2. Exact input-session admission has durable
session/work-owner state, but completed pending results still use one per-session
payload plus one singleton open-session continuation and one shared notification
identity. No production enumeration of all pending result sessions was found.

BUG-UPDATER-02 remains OPEN P2. UpdateUtil still relies on a process-local
updating flag without durable desired/current generation provenance. The source
preference is persisted before runtime update. Crash/failure can therefore leave
durable desired source state ahead of actual runtime state without restart
convergence authority.

BUG-HISTORY-04 remains OPEN P2. Duplicate discovery, assignment merge, and
record deletion are not one final identity-revalidated transaction. Failure or
process death can leave partially applied relationship state.

BUG-MIGRATION-01 remains OPEN P2. Physical source retirement can occur before
the new History path is durably published. No exact old-to-new recovery carrier
is established before that retirement boundary.

BUG-RUNTIME-01 remains OPEN P2. Runtime replacement still deletes the live
payload before a replacement generation is fully staged and verified. A crash
or materialization failure can remove the last usable generation without
restart-safe rollback authority.

BUG-TERMINAL-06 remains OPEN P2 / PARTIALLY FIXED. Terminal execution recovery
has durable exact witnesses and recovery carriers, but AppCacheManager's final
Terminal deletion predicate still depends on the process-local live registry.
Durable recovery responsibility can therefore become invisible to generic
cache deletion after process death or marker revocation.

BUG-COOKIE-03 remains OPEN P2. Current remote request construction can continue
without a cookie option when configuration intent survives but the runtime
projection path is unavailable. Cookie projection is asynchronous and lacks one
authoritative durable readiness/generation contract. The newer unpublished
candidate result reports the same semantic boundary; its exact local mechanics
remain NOT_VERIFIED here.

No additional independent L1 root was established.

## Review retrospective

This run reviewed the same remote SHA again rather than reusing prior L3/L4
verdicts.

The review traced durable producers, carriers, recovery consumers, restart
entrypoints, and final effects. The material pattern is consistent across the
existing roots: a durable or recoverable authority exists in some parts of the
system, but one downstream boundary either loses discoverability, publishes
desired state before committed state, retires old state before replacement
publication, or collapses uncertainty into absence.

The current remote implementation did not move during the run.

The unpublished 5949f311 candidate was deliberately not treated as source
evidence. Its newer canonical result remains workflow-authoritative evidence
only to the extent recorded by the canonical reconciliation checkpoint.

## Checklist evolution

No checklist or protocol change is required.

Existing L1 durable-carrier and recovery rules were sufficient to classify the
current paths and to avoid duplicating already-canonical roots.

Same-SHA DEEP coverage is now:
- L1 Durability & recovery
- L3 Concurrency & authority
- L4 Destructive ownership

L2, L5, and L6 remain not-yet-DEEP on this SHA.

primary_deep_lens: L1 Durability & recovery
remaining_not_yet_deep: L2, L5, L6
next_not_yet_deep_lens: L2 Identity & provenance

The L2 next-lens choice is driven by exact identity binding across LocalAdd
sessions, updater generations, cookie projection generations, migration
old/new path authority, and destination-local backup authority.

## Checkpoint summary

manual_review_run_status: FINAL
overall_verdict: NOT_CLEAN

implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
production_counts: P0=0 / P1=0 / P2=13
tooling_counts: P0=0 / P1=0 / P2=1
new_finding_ids: 0

primary_deep_lens: L1
same_sha_deep_coverage: L1, L3, L4
next_not_yet_deep_lens: L2

reported_local_candidate:
5949f31120fcf8678256949a6eb8659dba589bfa / UNPUBLISHED / NOT_VERIFIED

The existing autonomous sequential remediation prompt and current canonical
residual state are not modified by this manual review.

INDEPENDENT EXECUTION: NOT EXECUTED
