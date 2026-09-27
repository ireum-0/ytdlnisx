# Manual correctness review — 7d6a7b7c — tooling CAS L3 DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 809da59918b121b9454e6a2167e375d96738cf37

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 809da59918b121b9454e6a2167e375d96738cf37
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

The lens-policy adoption is in forward ancestry of the current ledger tip.
Governance is fixed for this manual run.

## Implementation range / history integrity

Reviewed implementation range:
52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a..7d6a7b7c445d9e45297032fa0521a1fc1d732eb9

The implementation branch is a strict one-commit forward advance.

Changed paths are exactly:
- tools/remediation/Complete-Wave.ps1
- tools/remediation/Test-ExecutionLifetimeProvenance.ps1
- tools/remediation/README.md

Android production source changed: NO.

No amend/rebase/squash/history rewrite is present in the reviewed implementation
range.

## Current independent disposition

Overall: NOT_CLEAN.

Tooling:
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

## BUG-TOOLING-01 — closure preserved

The reviewed one-commit range does not weaken exact-source execution.

Current completion still requires the exact candidate execution-lifetime
contract produced by the detached run-scoped Git worktree, including:
- tested SHA/tree identity;
- run-owned materialization identity;
- clean pre/post execution tree state;
- canonical materialized gradlew.bat for non-diff gates;
- per-gate exact lifetime provenance.

The acceptance harness still contains the synchronized transient source-worktree
mutation cell proving that the gate consumes the committed value from the
detached exact candidate materialization.

No current source evidence reopens BUG-TOOLING-01.

## BUG-TOOLING-02 — closure independently revalidated

Protocol Section 1.1 now explicitly authorizes only the narrow exact expected-old
CAS form when:
- the destination ref and exact old SHA are explicit;
- the source is the exact tested SHA;
- the resulting update is independently proven strictly fast-forward;
- lease failure causes STOP with no retry/reconciliation;
- successful update is followed by exact remote equality and 0/0 verification.

Current Complete-Wave implements that contract end-to-end.

### Authority producer

Get-FinalPushAuthority:
1. revalidates local HEAD == TestedSha;
2. revalidates local candidate tree == tested tree;
3. rejects tracked/non-ignored-untracked local inputs;
4. re-reads review/remediation and validates recorded-tip ancestry plus explicit
   forward-tip acknowledgement when required;
5. reads the implementation destination last;
6. accepts only destination == TestedSha or destination == ExpectedRemoteBaseSha;
7. when destination == expected base, independently proves destination is an
   ancestor of TestedSha;
8. only then emits:
   - authorizedPushSourceObjectId = TestedSha;
   - authorizedPushExpectedOldObjectId = exact observed destination;
   - authorizedPushLeaseArgument =
     --force-with-lease=refs/heads/<implementation-ref>:<exact-old-sha>.

### Mutation boundary

Before invoking Git, Complete-Wave checks that:
- authorized source is exactly TestedSha;
- authorized old object is exactly the accepted destination SHA;
- destinationIsAncestorOfTestedSha is true;
- lease argument exactly names the full destination ref and exact accepted old
  SHA.

The actual write uses one command-equivalent argument sequence:

git push
  --force-with-lease=refs/heads/<implementation-ref>:<accepted-old-sha>
  <remote>
  <TestedSha>:refs/heads/<implementation-ref>

Because the accepted old destination is proven an ancestor of TestedSha, the
authorized mutation is forward-only even though the exact-old lease supplies
the CAS guard.

### Writer-race failure path

If another writer changes the destination after the final accepted observation,
the exact-old lease rejects the update.

On push timeout/nonzero exit:
- status becomes PUSH_REJECTED_NO_RECONCILIATION;
- no retry or reconciliation path follows;
- completion exits non-success.

No alternate target-update command exists in this completion path.

### Successful publication

After a successful target update:
- the implementation ref is re-read;
- it must equal TestedSha exactly;
- ahead/behind TestedSha...remote must be 0/0.

### Deterministic final-boundary coverage

The acceptance harness now has 15 unique acceptance IDs.

The new final-boundary cell constructs B -> C -> X:
- final authority observes and accepts B;
- the Git shim blocks at the actual target push invocation, after authority
  observation;
- a competing writer advances the remote B -> C;
- the target update resumes with exact-old lease B and immutable source X;
- the target update must fail;
- exactly one target push attempt must be logged;
- remote must remain C;
- local tested candidate remains X;
- no retry is allowed.

This closes the previously uncovered after-observation writer interval rather
than merely re-testing movement visible to the final ls-remote.

No supported current evidence establishes a BUG-TOOLING-02 residual.

## Review-ref race boundary

Current protocol treats review/remediation as an append-only semantic baseline,
not an atomic branch lock, unless a hard review-tip gate is explicitly set.

The implementation re-reads review/remediation before the destination read and
requires ancestry/explicit forward acknowledgement as governed by Section 3.5.
The protocol requires a fresh review-tip check before writes but does not require
the implementation mutation to atomically CAS both review/remediation and the
implementation destination as one transaction.

Accordingly this run does not create a new finding merely because
review/remediation can advance after its final observation. A later review-tip
movement is handled by the protocol's forward-ancestry reconciliation model;
the exact implementation destination itself is the ref protected by the CAS.

## Fresh production baseline — BUG-DOWNLOAD-01

Current production source at 7d6a7b7c still contains the established
authoritative-read root.

### Stop predicate

DownloadWorker.shouldStopForUserRequest() still does:

runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()

For a nonblank exact execution token:
- a Room read exception becomes null;
- null contributes to lostExecutionOwnership=true;
- shouldStopForDownloadExecution can return ordinary STOP.

An indeterminate authoritative read is therefore still collapsed into the same
representation as proven row absence/revocation.

### Child finalizer

The finalizer again reads the Download row through
runCatching(...).getOrNull().

A read exception becomes null, making stillOwnsAttempt=false for a nonblank
execution token. The branch intended for a newer owner can then release this
child's exact process-local execution/process ownership and bookkeeping.

### Recovery contrast

DownloadExecutionRecovery has durable row/journal/marker/producer carriers and
its recovery loop retains responsibility across read failures. The worker-side
authoritative reads above do not first transfer the indeterminate observation
into that recovery model.

This remains BUG-DOWNLOAD-01. No new production finding ID is created.

## Trigger/module status

- exact-source execution-tree identity/provenance:
  TRIGGERED / CLOSED — BUG-TOOLING-01.
- final implementation-ref mutation authority:
  TRIGGERED / CLOSED — BUG-TOOLING-02.
- exact-old CAS race at the actual write boundary:
  TRIGGERED / PASS at source level.
- lease-failure retry/reconciliation:
  TRIGGERED / PASS; failure stops without a second target update.
- forward-only proof:
  TRIGGERED / PASS; accepted old destination must be an ancestor of TestedSha.
- post-publication exact equality:
  TRIGGERED / PASS; exact TestedSha and 0/0 required.
- protected history/workspace:
  TRIGGERED / PASS at source level; no reset/clean/rebase/amend/squash/general
  force/implicit lease/leading-plus path is introduced.
- production exact live-owner preservation:
  TRIGGERED / OPEN — BUG-DOWNLOAD-01.
- newly triggered schema/ABI/external-representation production module from
  this tooling-only range:
  NONE.

## L1-L6 baseline

### L1 Durability & recovery — BASELINE

Unique evidence runs, atomic JSON publication, retained exact candidate
materialization, fail-closed missing evidence, and no-retry lease failure remain
present. No new L1 tooling root found.

BUG-DOWNLOAD-01 remains open because an indeterminate authoritative read can
retire live responsibility instead of entering exact durable recovery.

### L2 Identity & provenance — BASELINE

BUG-TOOLING-01 remains closed.

For BUG-TOOLING-02, TestedSha, exact accepted old destination, exact destination
ref, and lease argument are carried explicitly into completion evidence.

No new identity/provenance root found.

### L3 Concurrency & authority — DEEP

Primary lens.

The prior after-observation writer race is now guarded by exact-old CAS.
The accepted old destination is independently proven a forward ancestor of the
exact tested source. Lease failure is a hard stop with no retry.

No same-root residual or new tooling L3 root is currently established.

Production BUG-DOWNLOAD-01 remains an L3 authority problem because indeterminate
DB observation is interpreted as ownership loss.

### L4 Destructive ownership — BASELINE

The narrow Section 1.1 lease does not authorize non-forward rewrite in this
implementation:
- source is exact TestedSha;
- accepted old destination must be its ancestor;
- plain force/implicit lease/leading-plus/ref rewrite mechanisms are absent;
- lease failure is not reconciled by rewriting another writer's state.

No new destructive-ownership tooling root found.

### L5 Platform contract closure — BASELINE

The changed write path is Git/PowerShell tooling only. Existing short Windows
materialization path and canonical launcher contract remain intact.

Independent runtime execution was not performed by this reviewer.

### L6 Cross-feature semantic propagation — BASELINE

The CAS authority contract propagates through:
final authority producer
-> completion evidence fields
-> actual push arguments
-> failure status
-> final remote equality check
-> deterministic acceptance fixture.

No additional consumer lacking the new exact-old authority carrier was found in
the reviewed completion path.

## Primary DEEP lens

primary_deep_lens: L3 Concurrency & authority

primary_deep_selection_reason:
R1/R3 — this new SHA exists specifically to close the final concurrent-writer
authority interval at the durable implementation-ref mutation boundary.

remaining_not_yet_deep:
- L1
- L2
- L4
- L5
- L6

next_not_yet_deep_lens: L4 Destructive ownership

next_lens_selection_reason:
L4 is the highest-relevance remaining lens because the new CAS mechanism uses a
narrow force-with-lease spelling whose safety depends on proving the resulting
mutation is still strictly forward-only and non-rewriting.

## Remaining scope before FINAL

- fresh-check implementation and review refs;
- reconcile only compatible forward review movement if any;
- append FINAL on this fixed implementation/governance basis;
- update private handoff review tip/current review checkpoint without changing
  tooling closure or the dependency-eligible BUG-DOWNLOAD-01 next action unless
  new evidence appears.
