# Manual correctness review — e3ae6f04 — tooling L2 DEEP intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: a4cec1cb9dd632b9d917c1f7dd19f379aa4d84a4

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
run_mode: manual_trigger_3
review_parent_sha: a4cec1cb9dd632b9d917c1f7dd19f379aa4d84a4
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

Governance is frozen for this manual run.

## Current disposition

Overall remains NOT_CLEAN.

Fresh source review confirms:
- BUG-DOWNLOAD-01 remains OPEN P2 on the current SHA.
- BUG-TOOLING-01 has a same-root exact-source-evidence residual and must not remain FIXED-CLOSED.
- no new finding ID is created by this residual.

Active production-remediation counts remain:
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=13

Tooling counts under the current evidence become:
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=1

Independent execution: NOT EXECUTED.

## BUG-TOOLING-01 same-root residual

The prior closure proved rejection of persistent non-ignored untracked files and alternate non-demo Gradle launchers. It did not prove that the execution tree remains the exact committed tree for the whole verification gate.

Current source sequence:

1. Invoke-Verification resolves HEAD/tree and calls Get-RemediationTrackedTreeState before the gate sequence.
2. A gate then invokes the real repository-local Gradle wrapper against the mutable implementation worktree.
3. The implementation has no worktree lock, immutable materialization, read-only execution snapshot, or equivalent mechanism that fences behavior-relevant source/test/config mutation for the gate lifetime.
4. Get-RemediationTrackedTreeState is called only after the gate returns.
5. Therefore a tracked modification or non-ignored untracked input can appear after the pre-gate check, participate in Gradle compilation/test execution, and be reverted/removed before the post-gate check.
6. Both point checks can report clean.
7. verification.json can still record evidenceKind=exact_source_verification, candidateSha=e3ae6f04..., candidateTree=<committed tree>, and PASS gates.
8. Complete-Wave later checks the then-current clean worktree and the JSON SHA/tree binding, but it has no surviving carrier proving the historical execution interval was immutable.

This is not a distinct tooling root. It breaks the same BUG-TOOLING-01 invariant:
an artifact labeled exact_source_verification must prove that the requested gate executed the exact committed candidate tree with the authorized launcher.

The required correction must preserve the already-fixed persistent-untracked and canonical-gradlew checks while adding an execution-lifetime identity barrier. A pre/post cleanliness snapshot alone is insufficient because it cannot detect a transient write that disappears before the second snapshot.

## Fresh production baseline — BUG-DOWNLOAD-01

At current SHA e3ae6f04, DownloadWorker.shouldStopForUserRequest() still does:

- runCatching { dao.getNullableDownloadById(id) }.getOrNull()
- latest == null contributes to lostExecutionOwnership
- shouldStopForDownloadExecution() returns true immediately when lostExecutionOwnership is true.

A Room read exception is therefore collapsed into the same value used for authoritative row absence/ownership loss and can become a normal STOP. The exact Current / Revoked / Indeterminate distinction remains absent.

The finalizer also contains a separate getOrNull reread of the Download row. Its exact effect differs from shouldStopForUserRequest and remains part of the same authoritative-read audit rather than proof that every read site has identical behavior.

## Trigger map

- Tooling exact-source identity/provenance contract: TRIGGERED — OPEN. Consumer closure reviewed across Get-RemediationTrackedTreeState -> Preflight-Wave -> Invoke-Verification -> verification.json -> Complete-Wave. Execution-lifetime tree identity remains unresolved.
- Tooling launcher identity: TRIGGERED — PASS at source level. Normal mode resolves/requires repository-local gradlew.bat; ToolingDemoMode remains tooling_demo and Complete-Wave requires exact_source_verification.
- Tooling destructive/history boundary: TRIGGERED by completion/push path — baseline PASS. No force/rebase/amend/reset/clean path was found; Push uses normal git push after ref/ancestry checks.
- Production BUG-DOWNLOAD-01 authoritative observation/preservation: TRIGGERED — OPEN. Room read failure is not typed separately from absence/ownership loss.
- Conditional production modules from the tooling-only semantic delta: no additional blocker-relevant module is established solely by the tooling patch; production open roots remain governed separately.

## Lens coverage for current SHA

- L1 Durability & recovery: BASELINE — evidence directories are per-run and recovery evidence is retained; production BUG-DOWNLOAD-01 still has an indeterminate-read recovery gap.
- L2 Identity & provenance: DEEP — primary lens. Same-root BUG-TOOLING-01 residual confirmed.
- L3 Concurrency & authority: BASELINE — transient worktree mutation is the concurrency mechanism that exposes the L2 residual; remote implementation push remains normal fast-forward only.
- L4 Destructive ownership: BASELINE — tooling contains no cleanup/reset/history rewrite path; production open P2s remain unresolved outside this tooling-focused DEEP pass.
- L5 Platform contract closure: BASELINE — Windows repository-local gradlew.bat path is enforced in normal mode; demo mode remains distinct.
- L6 Cross-feature semantic propagation: BASELINE — stricter persistent tree-state helper is consumed by preflight, verification, and completion, but point-in-time propagation does not close the execution-lifetime identity gap.

primary_deep_lens: L2 Identity & provenance
primary_deep_selection_reason: R1 — the material current tooling root is exact execution-tree/launcher provenance, and the newly confirmed residual directly violates that identity claim.

remaining_not_yet_deep:
- L1
- L3
- L4
- L5
- L6

next_not_yet_deep_lens: L3 Concurrency & authority
next_lens_selection_reason: the same-root residual is realized through an unfenced concurrent mutation interval; if the SHA is reviewed again, L3 is the highest-relevance remaining lens.

## Remaining scope before FINAL

- re-check the full tooling producer/carrier/consumer path for any additional same-root bypass before finalizing;
- confirm no distinct new tooling semantic root is required;
- reconcile the prior BUG-TOOLING-01 closure checkpoint explicitly;
- fresh-check implementation and review refs;
- append FINAL checkpoint without switching the pinned implementation/governance basis.
