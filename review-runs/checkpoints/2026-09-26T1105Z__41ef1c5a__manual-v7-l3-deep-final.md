# Manual correctness review — 41ef1c5a — FINAL

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: `4192d2cf8e07c1eb722a91210bf44d2ff84cc4a7`

checkpoint_kind: FINAL
run_mode: manual_trigger_3
review_parent_for_final: `cd7cda6c4dd41da4ac02877bb83aed13a494dbe0`

## Frozen basis

implementation_sha: `41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a`
implementation_parent: `225eafd1904a6500d1e1aa6db2aa37d26e10650b`
implementation_branch: `checkpoint/pre-baseline-review`

plan_tip: `2145847a1054da28398b730b9be0ca728668f967`
ledger_tip: `b98d315006fa19fc6f22b017f43a91899db5fb81`
master_plan_commit: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
review_checklist_v7_adoption: `b98d315006fa19fc6f22b017f43a91899db5fb81`
review_checklist_v7_blob: `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`
review_lens_policy_adoption: `822ffe6a9cd45b951550fcb559557f0cf0798610`
review_lens_policy_blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
protocol_blob: `c6cac5f3d7ad10dddb68343f6684e95ae915366c`

This manual run started while private handoff
`a1df7ce80fd6fb1bbd74b5253db90da774a80d6a` recorded
`IMPLEMENTATION_AGENT_CURRENTLY_WORKING=NO`. During the run the handoff advanced exactly one
compatible commit to `189aff8a74a656addd43f8737b854a76d07acdeb`, changing only that flag to
`YES`. The implementation remote remained exactly 41ef1c5a through FINAL. The run therefore
predates the active implementation wave and remains frozen on 41ef1c5a. No active-wave diff or
uncommitted implementation state was inspected.

## Independent verdict

scope_verdict: **NOT_CLEAN**
overall_remediation_gate: **NOT_CLEAN**

BUG-TERMINAL-11 remains:

`SOURCE-FIXED / PRODUCTION-CELL-PRESENT / HARNESS-QUIESCENCE-INCOMPLETE`

No new production P0/P1/P2 finding ID was confirmed.

Canonical blocker totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

The pinned 41ef1c5a change is test-only. Exact production semantics are unchanged from 225eafd1.
This run independently re-read the production path rather than reusing the prior verdict.

## Findings

### Existing BUG-TERMINAL-11 closure gap remains open

The direct generation-2 malformed-provider production-wiring fixture is correctly shaped:
- it seeds current-format marker bytes;
- it seeds a generation-2 TERMINAL_DISPATCH carrier;
- it includes both empty-valued and bare provider-option forms;
- it runs real startup reconciliation;
- it asserts exact carrier supersession, malformed non-admission, row/command preservation, and
  valid sibling convergence.

The test still lacks a deterministic async quiescence boundary.

Production reconciliation collects runnable Terminal handoff IDs in a Room transaction, then
publishes each with `convergenceScope.launch`. The new fixture calls `awaitEnqueue()` with the
default `count = 1`, although two positive sibling enqueues are expected. It then immediately
checks both siblings and the negative claim that malformed work was never enqueued.

That is insufficient for a negative asynchronous property:
- one valid sibling can satisfy the wait while the second valid dispatch is still pending;
- under a regression, a malformed enqueue could also arrive after the negative assertion;
- one observed passing schedule does not prove all launched dispatches have crossed the observation
  window.

The class already exposes the required bounded primitives:
- `awaitEnqueue(count)`;
- `settleEnqueueWindow()`.

Closure still requires awaiting the full positive sibling count and then applying a bounded
settle/drain before the malformed negative assertions.

### Production semantics remain fail-closed

No same-root production residual was found.

The exact source path proves:
1. new Terminal insertion materializes provider authority before durability;
2. Terminal row and exact TERMINAL_DISPATCH carrier are staged in one Room transaction;
3. the carrier independently records current format generation 2, exact command, fingerprint,
   request identity and boundary;
4. `TerminalCommandMetadata.strip()` rejects bare/empty/repeated/unsupported owned metadata and
   rejects a present provider value that is not a typed ProviderTree;
5. `classifyDurableResult()` converts owned-metadata parse failure into typed `Malformed`,
   preventing one bad row from throwing out of the reconciliation batch;
6. startup reconciliation marks an exact malformed outstanding carrier SUPERSEDED and preserves the
   Terminal row/command;
7. enqueue publication revalidates current durable owner/authority immediately before
   `enqueueUniqueWork`;
8. a stale or late WorkRequest is revalidated at the worker boundary and again immediately before
   execution-registry/native admission;
9. Terminal-owned metadata is stripped before the native planner can interpret it.

Therefore generation 2 cannot override malformed provider metadata, and malformed persisted state
cannot acquire native or publication authority merely because an exact/late scheduler request
exists.

### No production bug from reconcile() returning before Terminal enqueue completion

The detached publication is intentional ownership, not a synchronous completion contract in the
reviewed startup path. `App.onCreate()` launches handoff reconciliation in application scope after
scheduler-transition recovery and does not perform a dependent authority-changing mutation on the
assumption that all Terminal enqueue jobs have completed.

The test, however, does make a negative observation immediately after an incomplete wait. The
production contract and the test observation contract are therefore different at exactly this
point.

### New confirmed P0/P1/P2 finding IDs

**0**

## Trigger map

### Module B — External scheduler handoff and observation
**TRIGGERED / SOURCE PASS / TEST-EVIDENCE GAP**

Exact durable carrier/request identity is preserved through request construction, enqueue
revalidation, WorkManager UUID, worker input, worker admission, supersession, and retry ownership.
The remaining gap is the new fixture's observation of detached dispatch completion.

### Module C — External representation / schema / authority projection
**TRIGGERED / PASS for this root**

Owned provider metadata is not accepted merely because it is syntactically present. Bare/empty
forms become malformed, and a value must classify as ProviderTree to become provider authority.
Current malformed state is never projected into raw/native or provider publication authority.

Destination-side SAF grant liveness remains separately owned by canonical BUG-BACKUP-11 and is not
reclassified by this run.

### Module F — Persisted schema-generation compatibility
**TRIGGERED / SOURCE PASS / EXECUTION-EVIDENCE OPEN**

The source uses an independently durable generation discriminator in the carrier rather than marker
bytes alone. The direct generation-2 malformed-carrier fixture now exists, but its negative
production-wiring observation is not closure-grade until the async dispatch set is quiescent.

### Module H — Persisted executable configuration fan-out
**TRIGGERED / PASS source-semantically**

Reviewed producer/consumer graph:
`TerminalCommandIntentMaterializer`
-> `TerminalItem` + TERMINAL_DISPATCH carrier
-> startup reconciliation
-> WorkRequest input
-> `TerminalDownloadWorker` admission
-> Terminal metadata stripping
-> planner/native/publication boundary.

Malformed state remains fail-closed through the graph.

### v7 asynchronous request / completion
**TRIGGERED / OPEN only in test evidence**

Production does not equate the detached dispatch request with completion. The new test currently
does not wait for all expected positive dispatches before a negative claim.

### v7 sibling isolation
**TRIGGERED / SOURCE PASS / TEST-EVIDENCE GAP**

Durable classification is per-row and owned-metadata failure is typed instead of escaping the row
loop. The fixture correctly places valid siblings after malformed rows, but must deterministically
observe both async positive dispatches before proving the malformed negatives.

### v7 thread-affinity
**NOT_APPLICABLE to this delta**

The reviewed startup reconciliation and convergence publication execute off the UI thread, and
41ef1c5a introduces no production lock/synchronous bridge.

### invalid-identity-transformation propagation
**NOT_TRIGGERED**

41ef1c5a is test-only and does not introduce or prove a new invalid production normalization or
equality transformation.

## Full L1-L6 review

### L1 — Durability & recovery — DEEP revalidated

- current writer stages Terminal row and carrier atomically;
- carrier generation 2 is independent of user-controlled marker bytes;
- malformed metadata is stable non-authority across restart/reconciliation;
- malformed current carriers become durable SUPERSEDED tombstones rather than runnable owners;
- missing current carrier does not let marker text synthesize generation-2 authority;
- downstream execution/publication recovery cannot be reached by a malformed request because
  admission fails first.

L1 source result for this root: **PASS**.
BUG-TERMINAL-11 remains open only because required actual production-wiring evidence is incomplete.

### L2 — Identity & provenance — BASELINE

Rechecked exact identities:
- Terminal row command;
- carrier command payload;
- SHA-256 command fingerprint;
- source ID / boundary;
- handoff ID / generation ID / request UUID;
- carrier sourceConfigurationGeneration;
- WorkRequest UUID and copied semantic fields.

Generation does not erase malformed metadata provenance. No new identity/provenance residual was
confirmed.

### L3 — Concurrency & authority — DEEP

Primary DEEP lens for this run.

Authority race review:
- malformed carrier supersession is committed before detached handoff publication;
- `performAttempt()` revalidates current generation/semantic authority/durable owner immediately
  before enqueue publication;
- superseded Terminal tombstones are retained when WorkInfo is null because null is not proof that a
  late enqueue cannot accept;
- worker admission validates the exact request and malformed durable authority before native setup;
- worker revalidates again immediately before registry admission, so cancellation/supersession that
  wins in between cannot be overtaken by stale work;
- independent valid sibling dispatches may complete in either order, which is safe in production
  but makes a one-enqueue test wait insufficient;
- the startup caller does not consume `reconcile()` as a completion barrier for a dependent
  destructive/publication mutation.

L3 source result: **PASS for BUG-TERMINAL-11**.
L3 evidence result: **NOT_CLOSED** until the test establishes full sibling enqueue + bounded
quiescence before negative assertions.

Primary DEEP selection reason:
**R1 + R2**. The only remaining current-root cell is async dispatch/quiescence, and external
scheduler/sibling-isolation completion evidence is the directly implicated unresolved obligation.

### L4 — Destructive ownership — BASELINE

Malformed state receives no native/publication authority. Reconciliation preserves the Terminal
row and exact command and supersedes only the exact outstanding carrier. No new destructive-owner
root was found in this delta.

### L5 — Platform contract closure — BASELINE

The malformed generation-2 cell is refused before native/provider execution. 41ef1c5a adds no
production platform contract. Existing SAF grant-liveness concerns belong to BUG-BACKUP-11 and
remain separate.

### L6 — Cross-feature semantic propagation — BASELINE

The stricter malformed-provider semantic was traced across materialization, durable representation,
startup recovery, scheduler handoff, worker admission and planner stripping. No consumer was found
that reinterprets malformed metadata as executable authority.

## Terminal fault matrix

- generation-2 marker + empty provider token + exact outstanding carrier:
  **SOURCE SAFE / TEST NEGATIVE NOT YET QUIESCENT**
- generation-2 marker + bare provider token + exact outstanding carrier:
  **SOURCE SAFE / TEST NEGATIVE NOT YET QUIESCENT**
- malformed row + current carrier:
  exact carrier is superseded; row/command preserved.
- malformed row + missing carrier:
  no generation-2 runnable owner is reconstructed.
- malformed stale/late WorkRequest:
  worker authority gate refuses before registry/native.
- valid siblings after malformed rows:
  source loop remains independent; fixture must await both positive dispatches.
- process restart:
  malformed authority is re-derived from durable state; no mutable preference can authorize it.

Matrix status:
**NOT_CLOSED due harness quiescence only.**

## Cross-attempt / live-owner matrix

- malformed carrier superseded before enqueue publication:
  source-safe; exact tombstone remains a revocation barrier.
- enqueue races with supersession:
  final enqueue precheck plus worker admission prevents stale execution authority.
- WorkInfo temporarily absent for superseded Terminal:
  tombstone retained; absence is not treated as completion proof.
- valid sibling dispatches:
  independent owners; no source-level sibling stranding found.
- active implementation wave begun after this run was pinned:
  excluded from this run; no active-wave diff inspected.

Matrix status for the pinned source:
**SOURCE-CLOSED for BUG-TERMINAL-11; verification gate remains open.**

## Verification evidence

Pinned implementation:
`41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a`

Implementation-agent evidence already attached to the canonical same-SHA review:
- focused class: 28/28;
- connected aggregate: 79/79;
- JVM: 68/68;
- compileDebugKotlin: PASS;
- compileDebugAndroidTestKotlin: PASS;
- git diff --check: PASS.

Those claims are not independent execution, and a passing schedule cannot substitute for the
missing quiescence proof.

Independent execution:
**NOT EXECUTED**

## Review retrospective

The useful distinction in this review is between source authority and asynchronous test
observation.

The production source has multiple independent stale-authority barriers, including a final
pre-enqueue revalidation and two worker-side admission checks. The direct generation-2 malformed
fixture is therefore the correct production cell, but its negative assertion is only meaningful
after every expected positive background dispatch has had a bounded opportunity to complete.

The existing helper comments already state that background reconciliation needs a bounded
observation window. The new test accidentally weakened that rule by waiting for only one of two
positive siblings.

This is a test-harness closure defect, not evidence for widening production changes.

## Checklist evolution

No checklist or lens-policy change is proposed.

Checklist v7 already contains the rules needed to detect this gap:
- asynchronous request is not completion;
- sibling isolation applies to sequential and concurrent batches;
- Module B requires exact scheduler handoff/observation semantics;
- Module F requires actual production-wiring coverage for persisted-generation cells;
- CLEAN requires triggered modules and actual production wiring to be closed.

No new governance gap was identified.

## Lens coverage / effectiveness

lens_coverage_current_sha:
- L1: **DEEP**
- L2: BASELINE
- L3: **DEEP**
- L4: BASELINE
- L5: BASELINE
- L6: BASELINE

primary_deep_lens:
**L3 Concurrency & authority**

primary_deep_selection_reason:
**R1 + R2**

remaining_not_yet_deep:
- L2
- L4
- L5
- L6

next_not_yet_deep_lens:
**L2 Identity & provenance**

next_lens_selection_reason:
The current async authority cell has now received L3 DEEP. If this exact SHA is manually reviewed
again without materially new evidence, L2 is the nearest remaining lens to the generation/provider
provenance boundary. This is a continuation hint, not a lock.

## Checkpoint summary

Same-run intermediate:
- `review-runs/checkpoints/2026-09-26T1100Z__41ef1c5a__manual-v7-l3-deep-intermediate.md`
- commit `cd7cda6c4dd41da4ac02877bb83aed13a494dbe0`

Final state at prewrite reconciliation:
- implementation remote still exactly 41ef1c5a;
- implementation agent is active on a wave that began after this run was pinned;
- active-wave work was not inspected;
- ledger/governance unchanged;
- review branch advanced only by this run's append-only intermediate;
- BUG-TERMINAL-11 remains source-fixed with harness quiescence incomplete;
- new P0/P1/P2 IDs: 0;
- canonical totals unchanged: P0=0 / P1=0 / P2=19;
- L3 promoted to DEEP;
- remaining lenses: L2/L4/L5/L6;
- overall verdict: NOT_CLEAN.
