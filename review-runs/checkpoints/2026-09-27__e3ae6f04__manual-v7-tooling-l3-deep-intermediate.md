# Manual correctness review — e3ae6f04 — tooling L3 DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 22fcb536669aebb2bdb2f436bbfdab09b8d4a588

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 22fcb536669aebb2bdb2f436bbfdab09b8d4a588
implementation_sha: e3ae6f0475f172f537eeedf27d42a18faa79692e
implementation_parent: 21a04168286ae6562adbf219b8ea6a03984a2e25
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

Governance is fixed for this manual run.

## Current disposition

Overall: NOT_CLEAN.

Fresh review of the same implementation SHA did not reuse the prior L2 verdict.
The current run recomputed triggers and promoted L3 Concurrency & authority to DEEP.

Production active-remediation counts remain:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=13

Tooling state:
- BUG-TOOLING-01 remains OPEN P2 / SAME-ROOT RESIDUAL.
- BUG-TOOLING-02 is newly confirmed OPEN P2 / CURRENT-CHANGE TOOLING BLOCKER.
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=2

new_finding_ids: 1

INDEPENDENT EXECUTION: NOT EXECUTED

## BUG-TOOLING-01 — preserved open residual

The prior manual L2 run remains supported by fresh source review.

`Invoke-Verification.ps1` observes the committed tree before a Gradle gate and
again after the gate, but the gate executes against the mutable implementation
worktree between those point checks. No execution-lifetime tree-identity fence
exists.

A behavior-relevant tracked or non-ignored source/test/config mutation can
therefore appear after the initial observation, influence gate execution, and be
restored/removed before the post-gate observation. Both point checks can be
clean while the actual execution inputs differ from the recorded candidate
tree.

This stays BUG-TOOLING-01, not a new ID.

## BUG-TOOLING-02 — exact tested SHA is not the mutation authority used by Push

Severity: P2
Status: OPEN / CURRENT-CHANGE TOOLING BLOCKER
Root: final remote mutation authority is derived from mutable local HEAD after
the exact tested SHA was observed, instead of being bound to the tested object.

### Exact source path

`Complete-Wave.ps1`:

1. reads local `HEAD` into `$head`;
2. requires `$head -eq $TestedSha`;
3. binds verification evidence and required PASS gates to `$TestedSha`;
4. checks the implementation remote and review ref;
5. when Push is requested, later executes:

`git push <remote> HEAD:refs/heads/<ImplementationRef>`

There is no fresh local-HEAD equality check immediately before that write, and
the push refspec uses mutable `HEAD` rather than the already-validated
`$TestedSha`.

### Concrete race

A second writer or other local operation can advance the implementation
worktree HEAD after the initial `HEAD == TestedSha` check but before the push.

Then:

- verification evidence still describes TestedSha X;
- Complete-Wave's prior checks may all be PASS for X;
- `git push ... HEAD:...` resolves HEAD at push time to newer commit Y;
- if Y is a normal fast-forward descendant, Git may push Y successfully;
- only after the remote mutation does Complete-Wave read the remote and detect
  `remoteAfter != TestedSha`;
- the command then reports a post-push mismatch, but the unauthorized/unverified
  Y commit has already been published to the implementation branch.

A post-write mismatch is not a safe authority barrier. The final-SHA protocol
requires the remote implementation HEAD used for closure to be the exact tested
SHA; writer races are a hard-stop condition, not something that may first
mutate the remote and be diagnosed afterward.

### Why this is a new root, not BUG-TOOLING-01

BUG-TOOLING-01 owns execution-input provenance: whether an artifact labeled
`exact_source_verification` actually corresponds to the exact committed tree
and authorized launcher.

BUG-TOOLING-02 owns final remote mutation authority: whether the object being
pushed is the exact object whose verification and completion gates were
authorized.

The two can be corrected independently:
- an immutable execution tree can close BUG-TOOLING-01 while `git push HEAD`
  still exposes BUG-TOOLING-02;
- an exact-SHA push can close BUG-TOOLING-02 while the mutable verification
  interval still leaves BUG-TOOLING-01 open.

### Narrow correction boundary

- modify only `tools/remediation/**`;
- preserve normal fast-forward-only push and all no-rewrite rules;
- immediately before any Push-mode mutation, fresh-check the local candidate
  authority required by the protocol;
- never derive the push source from a mutable symbolic `HEAD` that may have
  changed since verification binding;
- the push refspec must identify the exact authorized tested object, or use an
  equivalent mechanism that cannot substitute a later local commit;
- if local candidate identity, destination ref, or governing review relation
  moves incompatibly, fail closed before the write;
- post-push equality remains verification, not the first line of defense;
- do not auto-reset, checkout, rebase, amend, clean, stash, or reconcile a
  changed local worktree.

### Required deterministic acceptance cell

Use a safe tooling fixture:

1. prepare tested candidate X and passing synthetic/tooling evidence bound to X;
2. allow Complete-Wave to finish its initial X checks;
3. before its Push write, advance local HEAD to a normal fast-forward child Y;
4. invoke/continue Push mode;
5. corrected behavior must refuse mutation before Y can be pushed as the
   tested candidate;
6. the implementation remote must remain at the authorized pre-push state
   unless the exact explicitly authorized X object is the mutation performed.

Also preserve:
- normal exact X push when local identity is unchanged;
- already-pushed-X behavior;
- destination-ref movement rejection/normal Git fast-forward protection;
- review-tip ancestry/acknowledgement behavior;
- no force/rebase/amend/reset/clean/history-rewrite path.

## Fresh production L3 baseline — BUG-DOWNLOAD-01

The current SHA still contains two authoritative-read collapses relevant to
positive-live-authority preservation.

### Stop predicate

`shouldStopForUserRequest()` converts
`dao.getNullableDownloadById(id)` failure through `runCatching(...).getOrNull()`.

For a nonblank exact execution token, null contributes to
`lostExecutionOwnership=true`; `shouldStopForDownloadExecution()` then
returns STOP before ordinary success finalization.

An indeterminate Room read is therefore treated as proof that the exact live
owner was revoked.

### Child finalizer

The finalizer again converts
`getNullableDownloadById(id)` failure to null.

For a nonblank expected execution token:
- `stillOwnsAttempt` becomes false;
- execution enters the branch documented as a newer attempt owning the row;
- process-local bookkeeping and `DownloadWorkerExecutionOwners` /
  `DownloadWorkerProcessOwners` can be released.

The process-local owner registry itself releases by exact
`(downloadId, executionId)`, and the resource lease is per Download ID. Those
mechanisms correctly protect newer E2 identities when the authoritative read
works. The defect is that an unreadable authoritative row is misclassified as
proof of E1 ownership loss.

No separate production L3 root is created; this remains BUG-DOWNLOAD-01.

## Trigger map

- tooling execution-tree identity/provenance:
  TRIGGERED / OPEN — BUG-TOOLING-01.
- tooling final remote mutation authority / writer race:
  TRIGGERED / OPEN — BUG-TOOLING-02.
- tooling launcher identity:
  TRIGGERED / PASS at source level — normal mode requires canonical repo-local
  gradlew.bat; demo remains tooling_demo.
- tooling destructive/history policy:
  TRIGGERED / PASS for rewrite prohibition — no force/rebase/amend/reset/clean
  path found. Normal push alone does not close BUG-TOOLING-02 because its
  source object is mutable HEAD.
- production exact live-owner preservation:
  TRIGGERED / OPEN — BUG-DOWNLOAD-01.
- production recovery discovery:
  TRIGGERED / existing recovery machinery present, but BUG-DOWNLOAD-01 read
  collapse can retire process-local authority before a typed indeterminate
  recovery owner is established.
- new conditional platform/schema/representation modules from this unchanged
  implementation SHA:
  no additional blocker-relevant module newly triggered by this L3 pass.

## Lens coverage for current SHA

- L1 Durability & recovery: BASELINE
- L2 Identity & provenance: DEEP from the immediately prior manual run
- L3 Concurrency & authority: DEEP in this run
- L4 Destructive ownership: BASELINE
- L5 Platform contract closure: BASELINE
- L6 Cross-feature semantic propagation: BASELINE

primary_deep_lens: L3 Concurrency & authority

primary_deep_selection_reason:
R1/R3 — BUG-TOOLING-01's remaining interval race and the newly exposed
Complete-Wave push-authority TOCTOU both directly cross concurrency/authority
boundaries on the current tooling SHA.

remaining_not_yet_deep:
- L1
- L4
- L5
- L6

next_not_yet_deep_lens: L1 Durability & recovery

next_lens_selection_reason:
With L2 and L3 now DEEP, L1 is the deterministic highest-relevance remaining
lens because both open production and tooling roots depend on whether exact
responsibility/evidence survives failure and restart rather than being retired
on an indeterminate observation.

## Remaining scope before FINAL

- re-check Complete-Wave source-object/destination-ref sequencing to ensure
  BUG-TOOLING-02 has no same-root sibling path requiring a broader correction;
- confirm no existing finding ID aliases this push-authority root;
- reconcile the existing BUG-TOOLING-01 implementation prompt with the newly
  separate BUG-TOOLING-02 correction without silently overwriting a persisted
  prompt;
- fresh-check implementation/review refs;
- append FINAL checkpoint on the fixed implementation/governance basis.
