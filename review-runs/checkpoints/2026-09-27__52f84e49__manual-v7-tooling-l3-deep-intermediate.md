# Manual correctness review — 52f84e49 — tooling L3 DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 17dcfb5175ffa63a692bf0e3d66f42b4445b420a

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: 17dcfb5175ffa63a692bf0e3d66f42b4445b420a
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

Governance is fixed for this manual run.

## Implementation range / scope

Reviewed implementation range:
e3ae6f0475f172f537eeedf27d42a18faa79692e..52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a

The range is forward-only and contains two tooling commits:
- c08bd73f0e852564e0e2a44c9bb71f77a04e8cd4
- 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a

Changed files are confined to:
- tools/remediation/Complete-Wave.ps1
- tools/remediation/Invoke-Verification.ps1
- tools/remediation/README.md
- tools/remediation/Test-ExecutionLifetimeProvenance.ps1

Android production source changed: NO.

## Independent verdict

NOT_CLEAN.

Current tooling disposition:
- BUG-TOOLING-01: FIXED-CLOSED at 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a.
- BUG-TOOLING-02: OPEN P2 / SAME-ROOT RESIDUAL.
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=1
- new_finding_ids=0

Production active-remediation counts remain:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=13

INDEPENDENT EXECUTION: NOT EXECUTED

## BUG-TOOLING-01 closure — independently revalidated

Normal exact-source verification now:
1. validates the mutable source worktree at the exact candidate;
2. creates a run-scoped detached Git worktree at the tested candidate SHA;
3. verifies materialized HEAD/tree/clean state;
4. executes Gradle and diff gates from that detached materialization;
5. records gate working directory, launcher, candidate SHA/tree, pre/post materialization state, and execution times;
6. requires every gate's execution-lifetime provenance to PASS;
7. Complete-Wave revalidates the lifetime proof, materialization/run identity, exact candidate SHA/tree, canonical launcher and per-gate proof.

The synchronized acceptance harness mutates the original source worktree after
the initial observation while the gate consumes the committed value from the
detached candidate worktree. This matches the intended BUG-TOOLING-01
correction boundary.

Persistent non-ignored-untracked rejection, tracked-dirty rejection,
canonical source gradlew.bat policy, demo-only alternate launcher behavior, and
no-cleanup preservation remain represented in current source.

No supported current evidence establishes a remaining BUG-TOOLING-01 residual.

## BUG-TOOLING-02 same-root residual — independently revalidated

Complete-Wave materially improves the prior defect:
- local HEAD/tree/worktree are re-read before Push;
- review authority is re-read;
- destination implementation ref is read last;
- the Push source is the immutable TestedSha object, not symbolic HEAD;
- local-head and destination movements visible to those final reads fail closed.

The remaining authority interval is after the last accepted destination read and
before the branch update itself.

Current sequence:

destination = ExpectedRemoteBaseSha A
-> Get-FinalPushAuthority accepts A
-> no further destination authority observation occurs
-> normal push of exact TestedSha C to the destination
-> post-push remote equality check requires C.

For a multi-commit candidate A -> B -> C, a concurrent writer can update the
destination from A to B after the final destination observation. A later normal
fast-forward push of C can still succeed because B is an ancestor of C. The
post-push remote equals C with 0/0, so the intervening writer race is no longer
distinguishable.

The acceptance harness confirms only movement visible to the second/final
ls-remote observation. Its destination mode pauses on that observation, lets a
writer move the ref, and then verifies that the re-read blocks Push. It does not
place the competing update after the accepted final observation and before the
actual push command.

This remains BUG-TOOLING-02 because the root is unchanged:
final remote mutation authority is not atomically bound to the destination
identity that was authorized.

The current governing constraints also remain relevant:
- repository mutation requires fresh ref authority when another writer may have
  changed the ref;
- stale mutation candidates require revalidation at the final mutation boundary;
- implementation agents own normal fast-forward push only;
- force-push/history rewrite remains prohibited.

The current source contains no compare-and-swap/lease-equivalent destination
condition at the actual branch update. Whether a compliant normal-forward-only
mechanism exists is therefore not proven by this implementation. The current
persisted follow-up correctly requires STOP/governance reconciliation rather
than weakening either authority or no-force rules if no compliant mechanism can
be established.

No new finding ID is created.

## Fresh production baseline — BUG-DOWNLOAD-01

Current source at 52f84e49 still contains both authoritative-read collapses.

1. shouldStopForUserRequest():
   runCatching { dao.getNullableDownloadById(id) }.getOrNull()
   converts DB read failure into null; null contributes to
   lostExecutionOwnership and can produce ordinary STOP.

2. child finalizer:
   the same getOrNull pattern converts an indeterminate read to null;
   stillOwnsAttempt becomes false for a nonblank exact execution token;
   the branch intended for a newer owner can release this child's exact
   process-local execution/process ownership and bookkeeping.

No new production ID is created. This remains BUG-DOWNLOAD-01.

## Trigger map

- exact-source execution-tree identity/provenance:
  TRIGGERED / CLOSED — BUG-TOOLING-01.
- final remote mutation authority / writer race:
  TRIGGERED / OPEN — BUG-TOOLING-02.
- protected workspace/history:
  TRIGGERED / PASS at source level; tooling introduces no reset/clean/rebase/
  amend/squash/force path and retains detached worktrees rather than cleaning
  protected work.
- execution evidence consumer closure:
  TRIGGERED / PASS for BUG-TOOLING-01; Invoke-Verification producer and
  Complete-Wave consumer use the same lifetime contract.
- tooling platform/path contract:
  TRIGGERED / source-level PASS; the materialization path was shortened and
  remains under ignored build/remediation-worktrees/<run-id>; actual independent
  runtime execution was not performed by this reviewer.
- production exact live-owner preservation:
  TRIGGERED / OPEN — BUG-DOWNLOAD-01.
- newly triggered schema/ABI/external-representation module from the tooling
  range: NONE.

## L1-L6 coverage

- L1 Durability & recovery: BASELINE
  Unique evidence runs, retained materialization, atomic JSON publication, and
  fail-closed missing evidence remain present. No new L1 root found.
- L2 Identity & provenance: BASELINE
  BUG-TOOLING-01 exact execution-tree identity is closed; BUG-TOOLING-02 still
  carries a final destination-identity authority gap at write time.
- L3 Concurrency & authority: DEEP
  Primary lens. BUG-TOOLING-02 residual confirmed end-to-end.
- L4 Destructive ownership: BASELINE
  No force/rewrite/cleanup path exists, but the normal remote branch mutation
  still requires the exact final authority represented by BUG-TOOLING-02.
- L5 Platform contract closure: BASELINE
  Windows path shortening and canonical launcher behavior are coherent at source
  level; independent execution remains NOT EXECUTED.
- L6 Cross-feature semantic propagation: BASELINE
  The new lifetime contract propagates from verification producer to completion
  consumer and acceptance harness. The remaining final-write authority contract
  is localized to Complete-Wave's branch mutation boundary.

primary_deep_lens: L3 Concurrency & authority
primary_deep_selection_reason:
R1/R3 — the only open current-change tooling root is a concurrent destination
writer race at the final branch mutation boundary.

remaining_not_yet_deep:
- L1
- L2
- L4
- L5
- L6

next_not_yet_deep_lens: L4 Destructive ownership
next_lens_selection_reason:
After L3, L4 has the strongest direct relevance because the remaining root
authorizes a durable remote branch mutation and must preserve exact ownership
without force/rewrite.

## Remaining scope before FINAL

- re-check the current implementation/review refs;
- confirm no later canonical review supersedes this run while it is in progress;
- append FINAL on the same pinned implementation/governance basis;
- update handoff only as needed to point to this manual review while preserving
  the existing BUG-TOOLING-02 follow-up and BUG-TOOLING-01 closure.
