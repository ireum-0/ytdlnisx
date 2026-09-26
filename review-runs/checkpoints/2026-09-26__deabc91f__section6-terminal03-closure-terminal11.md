# deabc91f Section 6 completion review — BUG-TERMINAL-03 closure / provisional BUG-TERMINAL-11

checkpoint_kind: SECTION6_COMPLETION_REVIEW
review_parent_sha: `39634c2dc2b8331a03f8eb12772a9cd7871edae2`
reviewed_implementation_sha: `deabc91ff37a06e861813a8b471ba356b4c6906f`
reviewed_parent_sha: `58c067b8a7a82320e9a369b77c642b025579d8c8`
protocol_blob: `c6cac5f3d7ad10dddb68343f6684e95ae915366c`
plan_ref: `2145847a1054da28398b730b9be0ca728668f967`
ledger_ref: `822ffe6a9cd45b951550fcb559557f0cf0798610`
governing_checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
governing_checklist_blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
lens_policy_adoption: `822ffe6a9cd45b951550fcb559557f0cf0798610`
lens_policy_blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
v7_shadow_candidate_blob: `fa08097e75cdb20b89e1dbe1149f7bec4022846a`

## Independent verdict

**NOT_CLEAN overall.**

**BUG-TERMINAL-03 is FIXED-CLOSED at exact implementation
`deabc91ff37a06e861813a8b471ba356b4c6906f`.**

The current implementation closes the persisted-generation provider-reauthorization
root that remained open at 58c067b8:

- genuine current-format Terminal rows are created with marker
  `--ytdlnisx-terminal-command-format=1` and an independently app-written
  TERMINAL_DISPATCH `sourceConfigurationGeneration = 2`;
- immediately prior/historical Terminal dispatches remain generation 1;
- `TerminalCommandMetadata.classifyDurable(command, formatGeneration)` trusts
  marker-only state as CurrentFormat only with independently durable generation
  >= 2;
- generation-1 exact-marker collisions are therefore Ambiguous, not CurrentFormat;
- generation-1 state with exact provider metadata or a valid authored native home
  remains SelfBound;
- startup recovery does not synthesize generation 2 from command text;
- missing-carrier marker-only rows are fail-closed rather than reconstructed;
- retry advances only requestId/attempt and preserves the carrier's
  `sourceConfigurationGeneration`;
- worker admission reads the generation from the exact durable carrier, not from
  the command under test;
- exact row/carrier/request/fingerprint ownership remains required;
- already-admitted execution witnesses remain owned by TerminalExecutionRecovery
  and are not replanned through a later mutable command_path.

The historical marker-collision sequence that held BUG-TERMINAL-03 open at
58c067b8 is therefore source-semantically closed.

However, the new-SHA governing v6 review confirmed a distinct persisted-generation
sibling-isolation defect, described below as provisional P2 BUG-TERMINAL-11.

`CLEAN_REVIEW_BASIS` does not advance.

## Findings

### FIXED-CLOSED — P2 BUG-TERMINAL-03

Root:
later mutable Terminal destination/provider state must not become execution
authority for an older durable Terminal intent whose original provider authority
is absent.

Closure proof at deabc91f:

1. Current writer materializes the command before row/carrier staging.
2. Row and TERMINAL_DISPATCH carrier still commit in one Room transaction.
3. Current writer stages carrier generation 2.
4. Historical/reconstructed state remains generation 1.
5. Marker-only generation 1 is Ambiguous.
6. Historical marker + exact provider is SelfBound.
7. Historical marker + authored native home is SelfBound.
8. Missing-carrier marker-only state receives no runnable reconstructed carrier.
9. Retry request rollover keeps the same generation proof.
10. Worker admission validates authority from the exact durable carrier before
    TerminalCommandPlanFactory can resolve current preferences.
11. Provider A remains embedded in genuine current-format provider-bound command
    identity across later preference B.
12. Existing execution recovery does not re-enter planner from an older admitted
    witness.

No same-root provider-reauthorization residual was found.

### New confirmed P2 — provisional BUG-TERMINAL-11

Title:
**Isolate malformed legacy Terminal metadata so one row cannot abort sibling startup recovery**

This is a distinct root from BUG-TERMINAL-03.

Concrete production path:

1. On a supported pre-marker writer such as
   `b6512ae21a08f76d161f537074ede7a0ffcc045c`,
   `TerminalViewModel.insert()` stores user command text unchanged and stages
   the same text into TERMINAL_DISPATCH.
2. Before the command-format option had application meaning, a user command could
   legally contain future Terminal-owned bytes such as:
   - bare `--ytdlnisx-terminal-command-format`;
   - repeated format markers;
   - an empty/unusable format value.
3. The Terminal remains nonterminal across update/process death.
4. On deabc91f startup, App invokes `WorkManagerHandoffRecovery.reconcile()`.
5. `reconcile()` calls `reconcileTerminalDispatches()`.
6. That function iterates all active Terminal rows inside one Room transaction.
7. For each row it calls `hasDurableTerminalOutputAuthority()`.
8. That calls `TerminalCommandMetadata.classifyDurable()` and then `strip()`.
9. `strip()` deliberately throws `IllegalArgumentException` for bare/repeated
   malformed format metadata; repeated/malformed provider metadata can also
   throw through `TerminalProviderDestinationOption.extract()`.
10. `reconcileTerminalDispatches()` has no per-row typed rejection/catch around
    this durable classification.
11. The exception aborts the whole Terminal reconciliation transaction and
    propagates out of `WorkManagerHandoffRecovery.reconcile()`.
12. App catches/logs only the whole WorkManager-handoff reconciliation failure.
13. A valid sibling Terminal that needs startup reconstruction/reconciliation is
    therefore not processed.
14. The same malformed durable row can reproduce the abort on every startup until
    the user manually removes it.

Strong sibling example:
- T1 is the malformed legacy row above.
- T2 is a valid legacy SelfBound row with no current carrier, whose authored
  native home or exact provider metadata would otherwise allow safe generation-1
  reconstruction.
- T1 throws before the transaction completes.
- T2 receives no reconstructed carrier and remains stranded.
- Repeated startup repeats the same failure.

This is fail-closed for T1's own execution, but it violates governing v6 sibling
isolation and recovery liveness because one invalid historical row can suppress
unrelated valid durable siblings.

Why it is distinct:
- BUG-TERMINAL-03 is destination/provider reauthorization of an older intent.
  This path does not need any newer provider and does not publish under wrong
  authority.
- BUG-TERMINAL-05 owns lost scheduler handoff for one valid Terminal task; it does
  not own batch-wide recovery abortion caused by another row's parse failure.
- BUG-TERMINAL-10 owns accepted-worker setup exceptions after foreground
  admission; this path occurs in startup reconciliation before worker setup.
- BUG-TERMINAL-06/07/08/09 own cache maintenance, cancellation/publication,
  descendant-native, and callback/setup families respectively.

Attribution:
**pre-existing on parent 58c067b8 and inherited by deabc91f; discovered in this
new-SHA review.**
The batch-abort behavior was introduced when durable legacy classification moved
Terminal-owned parsing into startup reconciliation; deabc91f does not create the
root but does not fix it.

Verification:
**SOURCE-LEVEL CONFIRMED / NOT EXECUTED.**

Required correction:
- preserve current insert-time strict rejection of malformed Terminal-owned
  metadata;
- for already-durable historical rows, convert malformed/repeated Terminal-owned
  metadata into an exact per-row non-runnable disposition rather than throwing
  out of the whole reconciliation batch;
- supersede/revoke only that row's outstanding exact carrier;
- preserve that row's command/log and existing user cancel/delete path;
- continue reconciling unrelated valid sibling rows in the same startup pass;
- worker admission must also fail closed for malformed durable metadata without
  letting an exception become authorization or an uncontrolled scheduler retry;
- do not reinterpret malformed legacy metadata as current format, SelfBound, or
  current command_path authority;
- do not globally weaken `TerminalCommandMetadata.strip()` if doing so would
  let malformed current input reach yt-dlp; a typed durable-classification
  wrapper/result is acceptable;
- preserve generation-2 BUG-TERMINAL-03 closure.

Required focused regression:
seed old rows directly, not through the new writer.

At minimum:
1. malformed/bare legacy T1 + valid self-bound missing-carrier T2:
   startup keeps T1 non-runnable and reconstructs/enqueues T2;
2. repeated-marker T1 + valid current generation-2 T2:
   T2 remains authoritative/runnable;
3. malformed provider metadata legacy T1 + valid sibling:
   sibling still converges;
4. malformed row with outstanding carrier:
   exact carrier is superseded and old WorkRequest cannot become current;
5. restart:
   malformed row remains stably non-runnable without re-aborting sibling
   reconciliation;
6. direct current insert with malformed Terminal-owned metadata remains rejected
   before durable row/carrier creation;
7. worker-boundary malformed legacy request returns non-authoritative/no-op or the
   governed failure result without planner/native execution.

### Existing separately owned findings

- provisional P2 BUG-BACKUP-11 remains confirmed and untouched;
- BUG-CANCEL-02 Terminal publication/cancellation subcase remains confirmed under
  its existing root;
- existing canonical Terminal/cache/setup findings remain outside this wave.

### Count reconciliation

Prior canonical totals from current handoff:
- P0 = 0
- P1 = 0
- P2 = 18

Changes in this review:
- BUG-TERMINAL-03: OPEN -> FIXED-CLOSED, existing root count does not decrement
  historical canonical discovery totals;
- provisional BUG-TERMINAL-11: new confirmed P2, +1;
- no new P0/P1.

Current canonical totals:
- P0 = 0
- P1 = 0
- P2 = 19

## Governing v6 review

### L1 — Durability & recovery — BASELINE

Positive:
- row + generation-2 carrier remain atomic for current inserts;
- retry preserves sourceConfigurationGeneration;
- missing-carrier marker-only state is stably fail-closed;
- execution witnesses remain separately recovery-owned.

Gap:
- malformed historical metadata can abort the full Terminal startup recovery
  batch and strand valid siblings.

Result:
**FAIL under provisional BUG-TERMINAL-11; BUG-TERMINAL-03 itself closed.**

### L2 — Identity & provenance — DEEP

Primary DEEP lens.

Selection:
- R1 direct current-root ownership: the reviewed correction changes exactly what
  proves Terminal command-format generation/provenance;
- R2 Module F is the central compatibility trigger;
- R3 the changed production boundary is the durable carrier generation plus
  `classifyDurable` predicate;
- no R4/R5 tie-break required.

Deep result:
- marker text alone is never current-generation provenance;
- carrier generation 2 is app-written durable proof;
- generation 1 exact-marker collision remains non-current;
- explicit provider/native authority remains self-bound independently;
- worker reads generation from exact carrier;
- request/fingerprint ownership remains exact;
- no provider-reauthorization identity residual found.

Result:
**PASS for BUG-TERMINAL-03.**
The new BUG-TERMINAL-11 is an exception/sibling-recovery root, not a false
generation-identity authorization.

### L3 — Concurrency & authority — BASELINE

- current row/carrier transaction remains atomic;
- request retry changes requestId while preserving handoff/generation semantics;
- stale generation-1 request cannot become a generation-2 current owner;
- missing-carrier marker text cannot mint a current owner;
- cancellation/supersession structure is unchanged by this patch.

Existing BUG-CANCEL-02 Terminal subcase remains separately open.

No new L3 root from the generation-proof change.

### L4 — Destructive ownership — BASELINE

No new destructive/publication mutation is added by deabc91f.
Generation proof gates scheduler/worker admission before planner/native output.

Existing Terminal cancellation/publication and maintenance findings remain
separately owned.

No new L4 root from this patch.

### L5 — Platform contract closure — BASELINE

No new Android platform capability or SAF API contract is introduced.
WorkManager request identity and persisted carrier semantics remain the relevant
platform handoff.

Provider publication still consumes the bound provider URI for a genuine
provider-bound Terminal.

Existing BUG-BACKUP-11 remains a separate SAF authority-provenance defect.

### L6 — Cross-feature semantic propagation — BASELINE

Producer/write/recovery inventory for the changed generation contract:
- ordinary Terminal producer: `TerminalViewModel.insert()`;
- materializer: `TerminalCommandIntentMaterializer`;
- row + dispatch carrier: one Room transaction;
- startup recovery materializer: `reconcileTerminalDispatches()`;
- retry: `advanceRetry()` preserves sourceConfigurationGeneration;
- worker admission: `isCurrentTerminalDispatchRequest()`;
- planner/native: downstream only after durable admission;
- Terminal execution recovery: existing witness prevents dispatch reconstruction;
- backup/restore does not write Terminal rows or TERMINAL_DISPATCH carriers;
- no Room schema field was added.

Current command_path backup/restore remains BUG-BACKUP-11 and is not treated as a
producer of Terminal row/carrier generation.

No new L6 generation-proof propagation gap beyond provisional BUG-TERMINAL-11's
recovery-batch exception.

### Lens coverage current SHA

- L1: BASELINE
- L2: DEEP
- L3: BASELINE
- L4: BASELINE
- L5: BASELINE
- L6: BASELINE

remaining_not_yet_deep:
- L1
- L3
- L4
- L5
- L6

next_not_yet_deep_lens:
**L1**

next_lens_selection_reason:
R1/R2 — provisional BUG-TERMINAL-11 directly concerns durable startup recovery
and sibling convergence.

## Trigger map

### Module B — External scheduler handoff
TRIGGERED.

For generation-proof contract:
**PASS.**

Exact carrier/request/fingerprint and worker admission remain closed.

Provisional BUG-TERMINAL-11 leaves startup batch recovery open, but it does not
weaken WorkManager request identity.

### Module C — External representation / authority projection
TRIGGERED by provider authority context.

BUG-TERMINAL-03:
**PASS** — historical incomplete state cannot acquire later provider authority.

BUG-BACKUP-11:
**FAIL separately** — restored provider locator still lacks destination-side
grant provenance.

### Module F — Persisted schema-generation compatibility
TRIGGERED.

Exact-marker generation collision:
**PASS** at deabc91f.

Old-state fixture quality:
**PASS for the implemented collision contract** — tests seed row/carrier directly
and distinguish generation 1 vs 2.

Additional old-state malformed metadata cell:
**FAIL / provisional BUG-TERMINAL-11** because historical parser-invalid
Terminal-owned bytes can abort the whole reconciliation batch.

### Module H — Persisted executable configuration fan-out
TRIGGERED.

BUG-TERMINAL-03 command/provider fan-out:
**PASS.**

BUG-BACKUP-11:
**FAIL separately.**

Malformed historical Terminal metadata:
**FAIL under provisional BUG-TERMINAL-11** at startup interpretation.

### Module I — Maintenance vs live-owner namespace
Not triggered by the deabc91f delta itself.

Existing BUG-CANCEL-02/Terminal maintenance-publication state remains open and
separately owned.

### Other modules
- Module A: no new trigger from deabc91f; existing BUG-BACKUP-11 platform/SAF
  authority remains separate.
- Module D: not triggered.
- Module E: not triggered by generation-proof change.
- Module G: not triggered; sourceConfigurationGeneration is semantic format
  provenance, not a recyclable external locator.

## Semantic-contract delta / consumer closure / authority-effect closure

Trigger:
**YES**

Changed boundary:
`TerminalCommandMetadata.classifyDurable(command, formatGeneration)` plus
TERMINAL_DISPATCH `sourceConfigurationGeneration`.

Old contract at 58c:
marker `=1` by itself could establish CurrentFormat.

New contract:
marker `=1` establishes CurrentFormat only with independently durable current
dispatch generation; otherwise only independently SelfBound command authority
survives.

Final discovered/reviewed production consumer set:
- `TerminalViewModel.insert()` -> current generation-2 staging;
- `stageTerminalDispatchWithinTransaction()`;
- `reconcileTerminalDispatches()`;
- `isCurrentTerminalDispatchAuthority()`;
- `retireStaleTerminalDispatchCarrier()`;
- `performAttempt()`;
- `retryAfterFailure()`;
- `reconcileCarrier()`;
- request building from exact carrier;
- `isCurrentTerminalDispatchRequest()`;
- `TerminalDownloadWorker`;
- `TerminalCommandPlanFactory/TerminalCommandPlanner`;
- `TerminalExecutionRecovery` restart ownership;
- cancellation/supersession remains unchanged but consumes the same carrier.

Material outcome cells:
- current marker + generation 2 -> runnable CurrentFormat;
- marker + generation 1 + no self-bound authority -> Ambiguous/non-runnable;
- marker + generation 1 + provider -> SelfBound/runnable exact provider;
- marker + generation 1 + authored native home -> SelfBound/runnable exact native;
- marker-only row with no carrier -> treated as legacy/ambiguous, no runnable
  synthesis;
- retry -> requestId advances, generation proof preserved;
- stale old request -> exact request/current-owner checks reject;
- existing execution witness -> no dispatch reconstruction/replan;
- malformed/repeated durable metadata -> exception escapes batch: FAIL under new
  provisional root.

Final authority effects:
BUG-TERMINAL-03 provider reauthorization:
**closed.**

Consumer/effect closure overall:
**not globally closed because provisional BUG-TERMINAL-11 is a distinct
reconciliation/sibling effect.**

## Cross-attempt / live-owner matrix

- genuine provider A current row -> preference B -> restart/retry:
  SAFE, remains A.
- historical generation-1 exact-marker row, no self-bound authority -> B:
  SAFE refusal; no B authority.
- historical marker + provider C -> preference B:
  SAFE SelfBound C.
- historical marker + authored native path -> provider B:
  SAFE authored authority.
- marker-only row with missing carrier:
  SAFE fail-closed/user-gated; no generation 2 synthesized.
- current generation-2 retry request rollover:
  SAFE; generation proof remains on same carrier.
- stale generation-1 request vs current generation-2 owner:
  SAFE; exact current carrier/request authority required.
- existing execution witness:
  preserved; no replanning.
- malformed historical metadata + valid recovery sibling:
  UNSAFE liveness/isolation; provisional BUG-TERMINAL-11.

## Terminal fault matrix summary

Generation-proof branch:
- first durable write remains row + carrier in one transaction;
- transaction failure publishes neither complete Terminal dispatch owner nor
  generation proof;
- enqueue failure retains same carrier and generation proof while requestId
  advances;
- process death before enqueue is recovered from carrier;
- missing current carrier does not recreate current generation from marker text;
- ambiguous old owner is superseded before it can run;
- worker requires exact carrier and durable classification.

Malformed historical metadata:
- authoritative classification throws before per-row disposition;
- no exact malformed-row supersession is guaranteed;
- whole Terminal recovery batch aborts;
- valid sibling reconstruction is rolled back/not reached;
- App logs outer reconciliation failure;
- restart can repeat indefinitely.

Result:
**BUG-TERMINAL-03 matrix PASS; provisional BUG-TERMINAL-11 matrix FAIL.**

## Verification evidence

Exact remote implementation:
`deabc91ff37a06e861813a8b471ba356b4c6906f`

Exact parent:
`58c067b8a7a82320e9a369b77c642b025579d8c8`

Compare:
- ahead = 1
- behind = 0

Changed production files:
- `TerminalCommandMetadata.kt`
- `WorkManagerHandoffRecovery.kt`

Changed test files:
- `TerminalCommandMetadataTest.kt`
- `TerminalPersistedGenerationAuthorityProductionWiringTest.kt`

GitHub exact-SHA status evidence:
- combined statuses: none
- workflow runs: none
- check runs: 0

Implementation-agent exact-final-SHA report:
- TerminalPersistedGenerationAuthorityProductionWiringTest: 15/15
- TerminalSafDestinationAuthorityProductionWiringTest: 13/13
- TerminalDispatchHandoffProductionWiringTest: 8/8
- TerminalExecutionProductionWiringTest: 2/2
- affected connected total: 38/38
- TerminalCommandMetadataTest: 13/13
- TerminalCommandIntentMaterializerTest: 8/8
- TerminalCommandPlanTest: 20/20
- HistoryReplacement / ExecutionRecovery / OutputAuthority JVM: 24/24
- JVM total: 65/65
- compileDebugKotlin: PASS
- compileDebugAndroidTestKotlin: PASS
- git diff --check: PASS

The agent reported one combined-run-only timeout caused by untracked shared
`convergenceScope.launch` test work and reran affected classes in isolated clean
processes without weakening semantic assertions/timeouts. This review accepts the
reported exact-final-SHA execution as implementation evidence for the
BUG-TERMINAL-03 closure.

No provided execution covers the newly confirmed malformed-legacy sibling
reconciliation case.

INDEPENDENT EXECUTION:
**NOT EXECUTED**

## v7 prospective shadow

Candidate:
`fa08097e75cdb20b89e1dbe1149f7bec4022846a`

Status:
**PROSPECTIVE SHADOW COMPLETED on newly observed SHA deabc91f.**

v7-only additions:

1. Thread-affinity / blocking wait:
   **NOT TRIGGERED by deabc91f.**
   No new/widened lock, mutex, lease, transaction wait, or synchronous bridge was
   introduced by this patch.

2. Invalid identity-transformation propagation:
   **NOT TRIGGERED.**
   No new invalid normalization/equality transform was established by this
   remediation.

3. Explicit Module-F marker/sentinel collision proof:
   **TRIGGERED / SATISFIED for BUG-TERMINAL-03.**
   The current implementation uses independent carrier generation 2 and directly
   seeds the historical exact marker collision.

Incremental shadow value:
- it makes the exact old-marker collision requirement more explicit than v6
  Module F alone;
- in this run it did not discover an additional generation-proof defect beyond
  the scenario already mandated by the current persisted prompt/protocol;
- the newly confirmed BUG-TERMINAL-11 was found by governing v6 Module F plus
  core sibling-isolation/recovery reasoning, not uniquely by a v7-only rule.

False-positive pressure:
**none observed.**

Duplication:
- the marker-collision clause partially overlaps the now-adopted stable protocol
  Module-F old-state fixture requirement, but remains useful checklist-local
  semantic wording;
- thread-affinity and invalid-transformation additions did not create irrelevant
  work in this delta.

Promotion conclusion:
the required prospective shadow is complete and the candidate wording remains
stable in this run. This checkpoint does **not** adopt v7. A later explicit
governance action may promote the versioned checklist.

## Review retrospective

What the implementation got right:
- used an app-written durable generation rather than trying to authenticate user
  command bytes by position/order;
- preserved SelfBound legacy semantics rather than enforcing generation as a
  blanket consistency rule;
- preserved retry identity and worker exact authority;
- seeded old state directly for the exact marker collision;
- explicitly documented the user-gated missing-carrier contract.

What this review added:
- expanded Module-F old-state reasoning from the exact valid marker collision to
  parser-invalid historical states;
- followed the classification failure through the batch transaction and App
  outer catch;
- distinguished fail-closed for one row from recovery closure for unrelated
  siblings.

Checklist gap:
**none.**

Governing v6 already contains both:
- persisted schema-generation compatibility; and
- sibling/remainder isolation.

The stable protocol also already requires old-state fixtures and convergence
ownership.

## Disposition

- BUG-TERMINAL-03: **FIXED-CLOSED at deabc91f**
- provisional BUG-TERMINAL-11: **OPEN P2**
- overall: **NOT_CLEAN**
- P0/P1/P2 canonical totals: **0 / 0 / 19**
- CLEAN_REVIEW_BASIS remains
  `74f57e695db30b701ad429af311c39a763bfe086`

Exact next remediation should be the narrow malformed-legacy Terminal metadata
sibling-isolation correction. Do not reopen generation-2 provider authority or
broaden into BUG-BACKUP-11 / BUG-CANCEL-02.
