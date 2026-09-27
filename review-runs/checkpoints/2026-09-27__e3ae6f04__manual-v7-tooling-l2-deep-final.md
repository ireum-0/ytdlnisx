# Manual correctness review — e3ae6f04 — tooling L2 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: a4cec1cb9dd632b9d917c1f7dd19f379aa4d84a4

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 7c09493d56e4d56e65947276dabc9e3834f7e032
intermediate_checkpoint:
`review-runs/checkpoints/2026-09-27__e3ae6f04__manual-v7-tooling-l2-deep-intermediate.md`
intermediate_commit: 7c09493d56e4d56e65947276dabc9e3834f7e032

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

Governance remained fixed for the complete manual run.

## Independent verdict

NOT_CLEAN.

The prior BUG-TOOLING-01 closure at
`a4cec1cb9dd632b9d917c1f7dd19f379aa4d84a4`
is superseded for that finding's disposition by materially new exact-source
evidence from this manual run.

BUG-TOOLING-01 is:

`OPEN P2 / SAME-ROOT RESIDUAL / EXECUTION-LIFETIME TREE IDENTITY NOT PROVEN`

No new tooling finding ID is created.

The already accepted sub-fixes remain valid and must be preserved:
- persistent non-ignored untracked files are rejected by the exact-worktree checks;
- normal mode accepts only the canonical resolved repository-local `gradlew.bat`;
- ToolingDemoMode remains `tooling_demo` and cannot satisfy Complete-Wave exact-source evidence;
- completion/push remains normal fast-forward only and exposes no force/rebase/amend/reset/clean path.

Active production-remediation counts remain:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=13

Tooling open counts become:
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=1

Overall repository verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED

## Findings

### OPEN P2 — BUG-TOOLING-01 same-root residual

Governing invariant:

An artifact labeled `exact_source_verification` must prove that the requested
gate executed the exact committed candidate tree using the authorized real
repository-local launcher.

Current source now proves the launcher half and persistent-dirty-tree half, but
does not prove execution-lifetime tree identity.

Exact production/tooling path:

1. `Invoke-Verification.ps1` records exact HEAD/tree and calls
   `Get-RemediationTrackedTreeState()` before the gate sequence.
2. That helper rejects tracked changes and non-ignored untracked files at the
   instant it runs.
3. The requested Gradle gate then executes against the same mutable
   implementation worktree.
4. No worktree-lifetime lock, wrapper-owned immutable candidate materialization,
   read-only source snapshot, durable filesystem-change witness, or equivalent
   identity fence exists in the five `tools/remediation/**` files.
5. The next tree-state check occurs only after the gate returns.
6. A behavior-relevant tracked change or non-ignored source/test/config input
   can therefore appear after the pre-gate check, participate in the Gradle
   execution, and be reverted/removed before the post-gate check.
7. Both point-in-time checks can report clean even though the executed tree was
   not the recorded committed candidate tree for part of the gate.
8. `verification.json` can then carry
   `evidenceKind=exact_source_verification`, the committed candidate SHA/tree,
   and PASS gate records.
9. `Complete-Wave.ps1` later rechecks only the then-current worktree and JSON
   binding. It has no surviving proof that the historical gate interval used
   only the committed execution tree.

This is a supported correctness concern, not a new adversarial threat model:
the governing exact-final-SHA rule requires the execution tree itself to have
no uncommitted behavior-relevant source/test/config state. Current tooling
establishes point samples around a mutable execution interval, not that
interval invariant. No separate single-writer/exclusive-worktree contract was
found in current governance or tooling source.

Why this is the same root:

The original BUG-TOOLING-01 root was that `exact_source_verification` could
claim exact candidate provenance without proving the actual executed
tree/toolchain. The transient mutation window breaks the same exact-tree
provenance claim. It does not justify a second tooling ID.

Narrow correction boundary:

- modify only `tools/remediation/**`;
- preserve persistent non-ignored-untracked rejection and canonical
  `gradlew.bat` enforcement;
- establish an execution-lifetime identity barrier for every normal
  `exact_source_verification` gate;
- acceptable implementations may use a wrapper-owned isolated/materialized
  exact candidate execution tree, or another mechanism that proves/detects
  behavior-relevant mutation for the complete gate interval;
- pre/post cleanliness checks alone are not sufficient;
- ignored build/evidence output and ignored `local.properties` remain allowed
  without reading or publishing `local.properties` contents;
- no destructive cleanup, reset, stash consumption, history rewrite, alternate
  normal-mode Gradle launcher, or production-source change is authorized.

Required regression cell:

Synchronize a safe tooling fixture so a behavior-relevant tracked or
non-ignored source/test/config mutation occurs after the initial tree check and
is reverted/removed before the old post-gate check would run. The corrected
normal exact-source path must either execute an independently fixed exact
candidate materialization unaffected by that mutation or fail/refuse exact
source evidence. It must not emit a passing `exact_source_verification`
artifact for a gate whose execution inputs differed from the committed
candidate.

Also preserve/regress:
- persistent non-ignored untracked rejection;
- ignored evidence output control;
- ignored `local.properties` control;
- normal alternate/fake Gradle rejection;
- ToolingDemoMode fake Gradle preservation with `tooling_demo`;
- Complete-Wave rejection of demo evidence;
- normal fast-forward-only completion/push behavior.

### OPEN P2 — BUG-DOWNLOAD-01 fresh current-SHA confirmation

Fresh source review on e3ae6f04 independently reconfirmed the active production
root rather than reusing the prior verdict.

`DownloadWorker.shouldStopForUserRequest()` still does:

`runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()`

For a nonblank execution token, `latest == null` is interpreted as
`lostExecutionOwnership=true`, and
`shouldStopForDownloadExecution()` immediately returns true for lost
ownership. A Room read exception is therefore collapsed into the same value as
authoritative row absence/ownership revocation and can become an ordinary STOP.

The finalizer contains another same-root carrier loss:

`runCatching { dao.getNullableDownloadById(downloadItem.id) }.getOrNull()`

A read failure yields null, makes `stillOwnsAttempt=false` for a nonblank
expected execution id, enters the branch documented as "row now belongs to
another attempt", and releases this execution's process-local owner/bookkeeping.
That is an indeterminate observation being treated as proven ownership loss.

Other cleanup paths that perform direct DAO reads and propagate failure into
recovery are not collapsed into this subcase. The defect remains the
authoritative-read sites that convert read failure to absence/revocation.

The existing remediation-ready contract remains correct:
Current / Revoked / Indeterminate must be represented separately; Indeterminate
must not authorize normal STOP or process-local ownership retirement and must
retain/establish exact recovery responsibility.

## BASELINE L1-L6

### L1 Durability & recovery — BASELINE

Tooling evidence uses unique per-run ignored evidence directories and retained
failure artifacts. No new durability root was found in the tooling delta.

Production BUG-DOWNLOAD-01 remains open because an indeterminate authoritative
read can lose the exact live/recovery responsibility rather than preserving it.

### L2 Identity & provenance — DEEP

Primary DEEP lens.

Persistent worktree state and canonical launcher identity are materially
improved, but exact execution-tree provenance is still not established for the
whole gate lifetime. This is the confirmed BUG-TOOLING-01 residual.

### L3 Concurrency & authority — BASELINE

The exact tooling residual is enabled by the gap between point-in-time tree
checks and mutable gate execution. No separate L3 root is created; it is the
concurrency realization of the L2 provenance failure.

Completion still revalidates implementation/review refs and only performs a
normal fast-forward push.

### L4 Destructive ownership — BASELINE

No force, amend, rebase, squash, reset, clean, automatic stash consumption, or
other destructive worktree/history path was found in the tooling range.

### L5 Platform contract closure — BASELINE

Normal Windows verification resolves and requires repository-local
`gradlew.bat`. ToolingDemoMode remains explicitly separate. No additional
platform-contract blocker was found in this run.

### L6 Cross-feature semantic propagation — BASELINE

The strengthened persistent tree-state helper is consumed by Preflight,
Verification, and Completion. That consumer propagation is real, but all three
remain point-in-time consumers; propagation does not create a durable
execution-lifetime provenance carrier.

## Trigger map

- exact-source identity/provenance contract:
  TRIGGERED / OPEN — execution-lifetime tree identity gap above.
- exact Gradle launcher identity:
  TRIGGERED / PASS — canonical repo-local wrapper enforced in normal mode.
- verification-evidence consumer closure:
  TRIGGERED / OPEN — helper -> verification gate -> verification.json ->
  Complete-Wave traced; interval provenance is missing.
- tooling destructive/history boundary:
  TRIGGERED / PASS — forward-only normal push; no rewrite/cleanup mechanism.
- production BUG-DOWNLOAD-01 authoritative observation/recovery:
  TRIGGERED / OPEN — read failure still collapses into absence/revocation.
- additional production conditional modules caused by the tooling-only delta:
  NOT TRIGGERED by this tooling patch.

## Primary DEEP lens

primary_deep_lens: L2 Identity & provenance

primary_deep_selection_reason:
R1 — the reviewed current tooling root directly owns exact execution-tree and
launcher provenance. Fresh evidence showed the remaining gap is in the
execution-tree identity proof itself.

remaining_not_yet_deep:
- L1
- L3
- L4
- L5
- L6

next_not_yet_deep_lens: L3 Concurrency & authority

next_lens_selection_reason:
The same-root residual is realized through a concurrent mutation interval
between tree-state observations. On a later review of the same SHA, L3 is the
highest-relevance remaining lens under R1/R3.

## Review retrospective

The prior e3ae6f04 completion review correctly found and accepted two real
fixes: persistent non-ignored untracked inputs are no longer invisible, and an
alternate non-demo Gradle launcher no longer substitutes for the repository
wrapper.

Its closure argument was nevertheless narrower than the governing invariant:
it proved that verification/completion cannot PASS while a persistent dirty
input is present. The final-SHA contract concerns the execution tree used by
the gate, not only the worktree states sampled before and after it.

The manual run found this by tracing the proof carrier end-to-end rather than
reviewing only the last tooling diff:

committed SHA/tree
-> point-in-time cleanliness observation
-> mutable Gradle execution interval
-> post-gate point-in-time observation
-> verification.json exact-source label
-> Complete-Wave acceptance/push.

The missing interval proof is therefore a same-root residual, not a reason to
discard the accepted persistent-untracked or launcher corrections.

No distinct new tooling semantic root was confirmed.

## Checklist evolution

Checklist v7 and the current protocol are sufficient for this defect.

The existing L2 identity/provenance rules, L3 concurrency/authority review,
consumer-closure requirement, and protocol exact-final-SHA execution rule
already require the execution evidence to correspond to the exact committed
tree. No new checklist rule is needed from this run.

A future tooling acceptance matrix should keep a deterministic transient
mutation cell so this existing requirement is exercised directly rather than
being inferred from two point-in-time clean checks.

## Checkpoint summary

Pinned implementation remained exactly:
`e3ae6f0475f172f537eeedf27d42a18faa79692e`

Pinned governance remained unchanged.

Review branch advanced only by this manual run's intermediate checkpoint before
FINAL.

Manual run status: FINAL.

BUG-TOOLING-01:
`OPEN P2 / SAME-ROOT RESIDUAL`

BUG-DOWNLOAD-01:
`OPEN P2 / REMEDIATION-READY`

Production active-remediation count:
P0=0 / P1=0 / P2=13

Tooling open count:
P0=0 / P1=0 / P2=1

New finding IDs: 0

Independent execution: NOT EXECUTED
