# Manual correctness review — 41ef1c5a — IN_PROGRESS

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: `4192d2cf8e07c1eb722a91210bf44d2ff84cc4a7`

checkpoint_kind: IN_PROGRESS
run_mode: manual_trigger_3

## Pinned basis

implementation_sha: `41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a`
implementation_parent: `225eafd1904a6500d1e1aa6db2aa37d26e10650b`
implementation_branch: `checkpoint/pre-baseline-review`

review_parent_at_run_start: `4192d2cf8e07c1eb722a91210bf44d2ff84cc4a7`
plan_tip: `2145847a1054da28398b730b9be0ca728668f967`
ledger_tip: `b98d315006fa19fc6f22b017f43a91899db5fb81`
master_plan_commit: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
review_checklist_v7_adoption: `b98d315006fa19fc6f22b017f43a91899db5fb81`
review_checklist_v7_blob: `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`
review_lens_policy_adoption: `822ffe6a9cd45b951550fcb559557f0cf0798610`
review_lens_policy_blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
protocol_blob: `c6cac5f3d7ad10dddb68343f6684e95ae915366c`

The run started from private handoff `a1df7ce80fd6fb1bbd74b5253db90da774a80d6a`, where
`IMPLEMENTATION_AGENT_CURRENTLY_WORKING=NO`. During this run that handoff advanced one
compatible commit to `189aff8a74a656addd43f8737b854a76d07acdeb`, changing only that flag to `YES`.
The implementation remote remained exactly `41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a`.
This explicit manual run therefore predates the active implementation wave and remains frozen on
41ef1c5a. Active-wave work is excluded and was not inspected.

## Review state

scope_verdict_so_far: **NOT_CLEAN**
overall_remediation_gate: **NOT_CLEAN**

Current canonical blocker totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

BUG-TERMINAL-11 remains:
`SOURCE-FIXED / PRODUCTION-CELL-PRESENT / HARNESS-QUIESCENCE-INCOMPLETE`.

No new production P0/P1/P2 root has been confirmed.

## Full-source review progress

The run did not use a diff-only review. Exact production source at the pinned SHA was traced through:
- Terminal input materialization and provider-shape validation;
- atomic Terminal row + TERMINAL_DISPATCH carrier creation;
- persisted generation and command/fingerprint provenance;
- startup reconciliation and malformed-state supersession;
- WorkManager request construction and exact request identity;
- enqueue-time authority revalidation;
- worker-boundary authority validation and immediate pre-admission revalidation;
- Terminal-owned metadata stripping and planner destination authority;
- downstream execution/publication boundaries sufficient to prove malformed state cannot reach them.

The pinned 41ef1c5a change itself is test-only.

### Current semantic conclusion

A generation-2 command with bare/empty or otherwise structurally unusable provider metadata is
classified as malformed before it can become current output authority. Startup reconciliation
marks an exact outstanding TERMINAL_DISPATCH carrier SUPERSEDED and preserves the Terminal row.
A stale/late WorkManager request is independently refused again at the worker boundary before
registry/native admission.

The remaining defect-closure gap is test observation, not a newly found production authority gap:
`generationTwoMalformedProviderCarrierIsSupersededAndSiblingsStillConverge()` calls
`awaitEnqueue()` with its default count of 1 even though two valid sibling dispatches are expected,
then immediately performs negative malformed-enqueue assertions. Because Terminal reconciliation
publishes handoffs through detached `convergenceScope.launch` jobs, that is not a deterministic
quiescence boundary for all launched dispatches. The class already has `awaitEnqueue(count)` and
`settleEnqueueWindow()`; the fixture must await both positive siblings and then use a bounded
settle/drain before negative assertions.

## Trigger map

- Module B — external scheduler handoff: TRIGGERED. Source semantics revalidated; exact carrier,
  request UUID, boundary, command, fingerprint, supersession, retry and worker admission remain
  exact. Closure evidence for the new negative async cell is not yet sufficient.
- Module C — external representation / authority projection: TRIGGERED. Structurally unusable
  provider metadata is typed malformed and cannot be projected as raw/provider execution authority.
  Existing provider-grant-liveness work under BUG-BACKUP-11 is separately owned and unchanged.
- Module F — persisted schema-generation compatibility: TRIGGERED / OPEN only for closure-grade
  execution evidence of the direct generation-2 malformed-carrier cell. The old/new durable
  generation discriminator itself remains source-semantically closed.
- Module H — persisted executable configuration fan-out: TRIGGERED. Producer -> row/carrier ->
  reconcile -> WorkRequest -> worker -> planner consumers were traced; malformed state remains
  fail-closed and per-row.
- v7 sibling-isolation obligation: TRIGGERED. Production loop is per row and malformed
  classification is typed/non-throwing for the owned metadata error; the new two-sibling test still
  lacks deterministic observation of all async dispatches.
- v7 asynchronous request/completion obligation: TRIGGERED. Production does not treat
  `reconcile()` return as enqueue completion; the test currently does so too early.
- v7 thread-affinity rule: NOT_APPLICABLE to this cell. Startup reconciliation and convergence
  publication run off the UI thread and no new blocking bridge was introduced by 41ef1c5a.
- invalid-identity-transformation propagation: NOT_TRIGGERED by this test-only delta.

## L1-L6 progress

- L1 Durability & recovery: **DEEP (revalidated)**. The durable generation proof lives in the
  carrier; malformed state is not upgraded by generation; row/carrier state survives process death
  and restart reconciliation without becoming runnable.
- L2 Identity & provenance: **BASELINE**. Exact command/fingerprint/request/boundary identity and
  malformed metadata provenance were rechecked.
- L3 Concurrency & authority: **DEEP candidate**. Enqueue publication, supersession, late-request
  behavior, worker double-check, sibling dispatch ordering, and startup caller semantics were
  traced. Source authority remains safe; the test lacks a complete quiescence barrier.
- L4 Destructive ownership: **BASELINE**. Malformed state obtains no publication/destructive
  authority; row/command are preserved and only the exact carrier is superseded.
- L5 Platform contract closure: **BASELINE**. The malformed cell is refused before native/provider
  publication; no new platform contract is required for the 41ef test-only delta.
- L6 Cross-feature semantic propagation: **BASELINE**. Materializer -> durable carrier -> scheduler
  -> worker -> planner propagation is consistent for this semantic.

Primary DEEP candidate:
**L3 Concurrency & authority**, by R1 + R2: the only remaining BUG-TERMINAL-11 closure cell is the
asynchronous dispatch/quiescence boundary, and Module B / sibling-isolation evidence is the directly
implicated unresolved surface.

remaining_not_yet_deep:
- L2
- L4
- L5
- L6

next_lens_hint_after_final:
**L2 Identity & provenance**, unless final reconciliation exposes materially new evidence.

## Remaining scope

- fresh-check implementation and review refs without inspecting active-wave diffs;
- reconcile any compatible forward movement;
- finalize the L3 DEEP disposition;
- append and verify the FINAL manual-review checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
