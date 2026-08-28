# Remediation Review Checklist v6 — Governing Operational Checklist

This checklist supersedes Review Checklist v5 for correctness reviews performed after the v6 adoption commit on `ledger/remediation`.

Review Checklists v4 and v5 remain historical governing artifacts for reviews performed under their respective adoption windows. v6 preserves v5's semantic and execution-evidence requirements and strengthens only generalizable closure mechanics for material semantic-contract changes.

v6 remains intentionally split into **core review rules** (mandatory whenever applicable) and **conditional review modules** (mandatory only when their trigger exists in the reviewed production path). The goal is stronger closure evidence without turning every review into an indiscriminate audit of unrelated platform, packaging, migration, protocol, or unchanged-callsite surfaces.

## Core invariants

1. An authoritative observation is not safely preserved until it crosses every required typed and durable carrier boundary.
2. Stateful retry/re-entry must reconstruct the same semantic barrier; a one-attempt carrier is insufficient.
3. Mutable retry/reconfigure fields must not retroactively re-authorize previously rejected privileged work.
4. Abandoned authority must be explicitly and irreversibly revoked before ordinary retry can proceed.
5. Non-authoritative side effects must not block, replace, or reinterpret authoritative persistence.
6. Recovery must preserve semantic identity, not merely escape a running state.
7. **Positive live authority is first-class correctness evidence.** Recovery, cleanup, timeout, reconciliation, retry, or maintenance must not mutate a resource away from an exact currently live owner unless an explicit revocation/transfer protocol has won.
8. **Discovery is not mutation authority.** A candidate selected before a suspension, lease wait, callback, transaction boundary, external operation, or concurrent state change must be revalidated at the final authority-changing mutation boundary.
9. **Asynchronous request is not completion.** Scheduling, cancellation, reset, revoke, clear, teardown, or publication calls are not barriers merely because the call returned.
10. **Recovery must be discoverable from every surviving durable carrier.** The existence of reconciliation code is not proof if candidate construction filters out the orphan/debt state that needs recovery.
11. **Material semantic-contract changes propagate through the full consumer and authority-effect graph.** If remediation changes the observable meaning of any production semantic boundary—helper/API result, DAO/CAS outcome, callback/future, ownership or lease primitive, identity/provenance predicate, recovery carrier, publication barrier, or equivalent contract—every direct and indirect production consumer inherits the changed obligation through its final correctness-relevant effects.
12. **Every destructive or durable identity relation must preserve semantic granularity and provenance.** Grouping keys, equality predicates, derived scalar values, callback payloads, cleanup keys, and external locators are identity claims when they authorize mutation.
13. **Sibling isolation applies to concurrent and sequential batches.** One item's failure must not strand, delete, revoke, or silently skip unrelated durable siblings or the unprocessed suffix.
14. A candidate may be rejected only after the original invariant disproves it on the production path.
15. Correctness is decided before attribution.
16. CLEAN requires semantic closure and required actual execution evidence.
17. A triggered conditional module is mandatory; do not infer CLEAN while a blocker-relevant triggered module remains materially unknown.
18. A material semantic-contract delta is not closed by reviewing the changed implementation alone; CLEAN requires explicit consumer closure and authority-effect closure against the final reviewed checkpoint.

## Mandatory reviewer execution order

1. Fix/review scope and invariant.
2. First authoritative observation.
3. Carrier-creation gap.
4. Post-carrier / pre-handler throwable frontier.
5. Helper-internal throwable window.
6. Semantic preservation, proof consumption, and identity/provenance.
7. Persistence barrier and first-write / ignored-result fault injection.
8. Asynchronous request / acceptance / completion ordering.
9. Recovery semantic identity and positive-live-authority preservation.
10. Recovery discovery closure / carrier-loss matrix.
11. Multi-ledger durability and process-death windows.
12. Post-commit / success-sidecar barrier.
13. Outer catch / final result.
14. Filesystem/reference/cleanup authority at the actual mutation point.
15. Retry/reconfigure/raw-requeue/resume/startup/restore inventory.
16. Cross-attempt and live-owner matrices.
17. Expected-identity mutability and durable-negative invalidation.
18. Concurrency, sibling/remainder isolation, lock order, and exact ownership.
19. Semantic-contract delta detection and old/new contract definition.
20. Consumer-closure and authority-effect-closure audit.
21. Triggered conditional review modules.
22. Candidate-rejection re-proof.
23. Test assertions and actual production wiring coverage.
24. Terminal fault matrix.
25. Final-checkpoint closure recount and CLEAN gate.
26. Attribution classification last.

# Core review rules

## 1. First authoritative observation

Identify where source, type, ownership, target, provenance, cancellation, capability, or another semantic fact first becomes authoritative. If the same decision is observed again, assume state may change between calls unless synchronization proves otherwise. Compare caller interpretations of the same helper result.

> Once a result becomes authoritative for a specific decision, later observation must not silently reinterpret or broaden that same decision.

## 2. Carrier-creation gap

Separate the point where an authoritative result is observed from the point where it is safely returned, typed, or durably persisted. Enumerate every operation between:

```text
authoritative observation
-> typed/in-memory carrier
-> first required durable carrier
```

Open DB reads, filesystem operations, cleanup, notification/logging, callbacks, resource close, serialization, and helper calls in that interval. If one can throw, prove the original semantic result survives.

## 3. Post-carrier / pre-handler throwable frontier

After durable intent or an accepted execution carrier exists, identify the first catch/finally/recovery boundary that actually owns application terminal semantics. Every fallible statement before that boundary is correctness-critical.

Examples include file creation/write, serialization/parser construction, request construction, dependency/config lookup, permission/capability probing, notification/foreground setup, DAO reads, and runtime resolution.

Inject or model an ordinary non-cancellation failure in this frontier. Prove the exact durable carrier reaches a typed retry/failure/cancellation/recovery outcome and remains discoverable after restart. A broad catch later in the function does not protect statements executed before it.

## 4. Helper-internal throwable window

Do not reason from helper names or signatures alone. Open helpers that combine authorization + cleanup, validate + mutate, claim + materialization, replace + finalization, queue completion, recovery, notification/publication, transaction-adjacent work, or native/process quiescence.

Identify the exact line where semantic authority is created and every throwable/state-changing operation before the helper returns that authority.

## 5. Semantic preservation and proof consumption

Do not collapse meaningful distinctions into Boolean/null/empty collection/generic exception/string messages unless the lost distinction is provably irrelevant. Distinguish authorization outcome, side-effect execution outcome, cleanup outcome, terminal disposition, and recovery debt.

When an authoritative proof authorizes deletion/release of its own carrier, trace:

```text
authoritative proof
-> captured/typed decision
-> cleanup/release/deletion of proof carrier
-> caller-visible result
```

Successful cleanup must not erase evidence still required to compute the same operation's result. Re-querying an intentionally destroyed carrier is not revalidation unless absence has an explicit generation-bound meaning.

When proof is unresolved or non-successful, trace every subsequent deletion, release, overwrite, compaction, publication, or state transition that could remove a carrier needed for retry/recovery or falsely authorize later work. Recovery code elsewhere is insufficient unless at least one guaranteed-surviving carrier remains discoverable and exact.

## 6. Persistence barrier and ignored-result audit

From an authoritative decision to durable state, enumerate every required persistence/publication call.

### Mandatory first-write fault injection

For each authoritative terminal or authority-changing branch, ask what happens if the first required write fails. Verify that the original issue/disposition survives, stale running state does not remain without a live owner or durable recovery responsibility, linked ledgers are not skipped, false handled success is not emitted, and retry/requeue cannot erase the authoritative decision.

### Mandatory non-exception failure audit

Persistence/publication APIs may signal failure by exception, Boolean, affected-row count, status/result, callback/future/Operation, sentinel/null, or explicit sidecar state. A synchronous-looking call is not a barrier if its failure result is discarded.

Search for ignored results, `runCatching`, `.onFailure`, `getOrNull`, `getOrDefault`, logging-only catches, and wrappers that convert a meaningful status into `Unit`.

## 7. Asynchronous request / acceptance / completion semantics

Whenever correctness depends on an asynchronous API, separate:

```text
prior authority
-> request issued
-> acceptance
-> completion
-> first dependent use/mutation
-> publication/terminal result
```

Apply this to scheduler handoff, cancellation, reset/clear/logout/revoke/invalidate, external-process stop, component result/callback publication, detached coroutine work, and asynchronous provider/framework APIs.

Do not infer completion from the initiating method returning, an unrelated `flush()`/sync call, a delay, UI construction, or a later query unless the API contract guarantees that ordering. If completion is ignored, identify a separate durable retry/failure barrier or treat the path as unresolved.

For every blocker-relevant asynchronous boundary, record the completion owner: request issuer, acceptance signal, actual completion signal, exception/failure observer, cancellation owner, durable responsibility before completion, first effect allowed only after completion, and process-death behavior. Lexical `try`/`catch`, `runCatching`, callback registration, coroutine launch, future creation, or enqueue/cancel invocation does not prove later asynchronous failure is observed unless the lifetime is actually awaited, joined, chained, or durably owned.

## 8. Recovery semantic identity and positive liveness

Recovery must preserve the exact issue, generation, operation identity, terminal disposition, and linked-child meaning required by the original invariant. It must also distinguish abandoned evidence from positive live evidence.

For every recovery/cleanup/timeout/reconciliation/startup repair path, identify evidence that work is stale and evidence that work is currently live/owned, including which evidence is durable, process-local, or externally owned.

### Mandatory live-owner matrix

```text
A. no exact live owner + stale/running state
   -> recovery must converge

B. exact current live owner
   -> recovery must preserve that owner and its durable authority

C. stale old debt/owner + newer exact live owner
   -> old debt must not mutate/cancel/revoke the newer owner

D. candidate discovered stale/unowned, but a new owner appears before mutation
   -> final mutation-boundary revalidation must preserve the new owner
```

A candidate-selection snapshot is never sufficient destructive authority after a suspension, lease wait, callback, transaction boundary, or concurrent claim. If an exact live owner exists, recovery must not clear, requeue, terminalize, replace, cancel, or revoke that owner's durable authority unless an explicit ownership-transfer/revocation protocol has won.

Process-local actor ownership, process/native ownership, durable row state, lease ownership, and recovery debt are distinct evidence classes; do not substitute one for another without proving the ownership model.

## 9. Recovery discovery closure / carrier-loss matrix

A reconciler is not proven merely because code for the desired state exists. For every durable recovery sidecar, marker, journal, lease, tombstone, staged artifact, scheduler token, or nonterminal row:

1. enumerate every sibling carrier used to construct recovery candidates;
2. enumerate every production-supported order in which those carriers can disappear;
3. restart/re-enter with only state guaranteed to survive;
4. prove the exact generation is still discoverable.

Inspect candidate construction before accepting downstream orphan branches. A later `row == null` path is not recovery evidence if an earlier `mapNotNull`, inner join, status filter, or lookup removes rowless candidates.

## 10. Multi-ledger durability and process death

Review the whole operation state: primary row, linked ledgers, operation/attempt/retry identity, issue/reason, filesystem references, sidecars/journals/native markers, scheduler carrier, process-local owner, and reference/keyword tables.

Assume process death between every pair of distinct durable writes/publications. Prove restart restores exact semantic state rather than merely a runnable state.

## 11. Post-commit barrier and success-sidecar consistency

Once an authoritative mutation commits, later ancillary failure must not pretend it never committed.

Do not equate nominal/root success with semantic completion if an authoritative sidecar remains unresolved. Force:

```text
root/helper reports success
-> authoritative sidecar/marker/lease/journal remains unresolved
```

Trace publication, terminal writes, owner release/deletion, scheduler result, restart owner, and overlap with a new generation. A retained unresolved marker is not a recovery barrier if no reconciler can discover and own it.

## 12. Outer-catch reinterpretation and final-result consistency

Trace each meaningful exception/status through every outer catch/finally to the actual final state:

```text
exception/status
-> outer catch
-> DB update
-> linked ledger
-> filesystem cleanup
-> notification/publication
-> local outcome
-> scheduler/WorkManager result
-> finally/recovery
```

Check for generic downgrade, false success, incorrect retry, cancellation reinterpretation, committed-success downgrade, and DB/ledger vs worker-result contradictions.

## 13. Filesystem/reference/cleanup authority at the mutation point

Authority must remain valid at the actual read/mutate/delete/publish moment, not only at an earlier point-check. Inspect TOCTOU between DB/reference snapshot and filesystem mutation, candidate enumeration and deletion, old/new path publication, retained-reference changes, pause/cancel/resume, new generation acquisition, and maintenance vs live ownership.

Any earlier zero-count/idle/no-owner observation is a **revocable snapshot, not a lease**. Between observation and destructive mutation, prove either ownership acquisition is serialized by the same lock/lease/transaction or exact current ownership/identity is revalidated immediately before mutation.

Destructive intervals must participate in a canonical synchronization order.

## 14. Retry, reconfigure, resume, restart, restore

Inventory same-settings retry, manual/raw requeue, reconfigure, notification retry/resume, startup reconciliation, process restart, restore/Undo, scheduled start, duplicate/replacement paths, retry-metadata reset, direct DAO status helpers, and WorkManager re-enqueue.

A prior authoritative refusal, committed success, or unresolved recovery debt must not be reinterpreted merely because mutable fields now differ. If privileged authority is intentionally abandoned, prove it is durably and irreversibly revoked first.

## 15. Identity, equality, granularity, and derived-value provenance

Treat every key or predicate that suppresses, merges, selects, cleans up, or destroys durable state as an identity claim. Trace initiating exact identity/selection, observation/grouping key, callback payload, service/repository key, and final DAO/filesystem mutation key. Enumerate valid non-selected/non-owned siblings that can collide.

Destructive authority must not silently widen from exact identity to a coarser key such as ID -> URL, generation -> status, selected records -> title/domain, path handle -> basename, or exact tuple -> substring/prefix/partial key.

### Derived values

A scalar/field-owned write can still be stale if it was derived from mutable authority outside the canonical boundary. For network fetches, decodes, filesystem probes, native operations, selectors, or other long computations, record the exact source identity/revision used and require expected-source/revision CAS or equivalent revalidation before final publication. Merely re-reading a row without comparing the derivation source is not validation.

### Equality and deduplication

For `contains`, substring, prefix, normalized text, title, path, partial tuples, hashes, or other coarse relations that can suppress a record, state the intended semantic equality and construct distinct valid identities that satisfy the implemented relation.

## 16. Expected-identity mutability and durable-negative invalidation

If a persisted negative decision suppresses future observation/work, enumerate every input that made it true: settings, source metadata/capability, permission state, remote availability, or schema/runtime version.

For each mutable dependency, prove the negative carrier is versioned/fingerprinted, invalidated, or re-authoritatively observed before suppressing a later attempt. A previously correct negative observation is not permanent identity merely because its original write was valid.

## 17. Concurrency, sibling/remainder isolation, lock order

One item's failure must not strand or corrupt unrelated durable work. This applies equally to concurrent coroutine children, sequential multi-item loops, UI/service batches, and cleanup/reconfigure loops.

### Mandatory later-sibling fault row

Inject a non-cancellation failure at item N after item N-1 has durably changed state. Prove prior outcomes remain represented, deferred output is not lost, later siblings retain a durable owner or continue safely, cleanup targets exact attempt-owned identities, mutable status/category is not treated as provenance, and retry/restart replays only exact incomplete identities.

Build a lock-order graph for process-global mutexes, Room transactions, per-item leases, filesystem/reference locks, and scheduler ownership. Reject reachable AB/BA ordering.

## 18. Semantic-contract delta, consumer closure, and authority-effect closure

A **semantic contract delta** exists whenever remediation materially changes what a production semantic boundary means to its consumers, even if the boundary has only one current consumer or its function signature, callsite text, or nominal success path is unchanged. The trigger is semantic, not syntactic.

Typical triggers include changes to:

- success, non-success, exception, cancellation, partial-completion, null/sentinel, affected-row, callback/future, or status/result meaning;
- request/acceptance semantics becoming completion/proof semantics;
- ordinary update becoming exact ownership/CAS authority;
- identity, provenance, generation, equality, or authorization predicates becoming stricter or broader;
- owner/lease acquisition, release, revocation, transfer, or liveness meaning;
- recovery carrier creation, discoverability, or authority;
- terminal/resumable/publication prerequisites;
- destructive cleanup prerequisites or the proof required before a carrier may be deleted/released;
- external representation or packaged/runtime capability being promoted from assumption/advisory state into correctness authority.

If no material contract changed, record the trigger as `NO` with a short proof and do not perform an indiscriminate repository-wide audit. If the trigger is `YES`, the following closure artifact is mandatory.

### 18.1 Contract-delta record

Record:

- changed producer/boundary and exact reviewed checkpoint;
- old semantic contract;
- new semantic contract;
- every meaningful outcome class for that contract;
- which outcomes authorize, refuse, defer, retry, recover, publish, release, delete, or admit later work;
- identity/generation/provenance carried by each outcome;
- whether the change affects completion, ownership, recovery, publication, destructive cleanup, or later admission.

Do not force every contract into Boolean `true/false/exception`. Model the result space actually exposed by the API, including explicit non-success, cancellation, callback/future completion, partial results, affected-row counts, sentinels/nulls, typed outcomes, or exceptional completion where applicable.

### 18.2 Consumer-closure audit

Start from the changed semantic producer and walk outward independently of the remediation diff:

```text
changed semantic producer
-> direct production consumers
-> wrappers/adapters/facades
-> transitive semantic consumers
-> re-entry/retry/resume/startup/restore consumers
```

Enumerate the complete production consumer set before declaring closure. Include unchanged pre-remediation code, alternate entrypoints, indirect wrappers, identity namespaces, and production-supported variants when they consume the changed meaning.

For every blocker-relevant consumer record:

- how each meaningful outcome is consumed, collapsed, ignored, retried, or rethrown;
- whether asynchronous completion is actually observed;
- whether exact identity/ownership is preserved;
- whether residual responsibility is durably owned after non-success;
- whether stale prior-generation state can affect a newer generation.

A caller count is an audit aid, not an invariant by itself. The required invariant is **consumer closure**: every reachable semantic consumer is accounted for, and the search is repeated against the final reviewed checkpoint so the discovered set and reviewed set agree.

### 18.3 Authority-effect closure

For each meaningful outcome of each blocker-relevant consumer, continue tracing until the last correctness-relevant effect:

```text
consumer interpretation
-> durable mutation / CAS
-> owner or proof-carrier release
-> deletion / overwrite / cleanup
-> notification / UI / callback / terminal publication
-> retry / requeue / resume / restore / startup
-> recovery ownership and discovery
-> later-generation admission or destructive authority
```

A downstream fence or eventual reconciler is defense in depth; it does not retroactively authorize an earlier terminal, resumable, destructive, or ownership-changing effect. Conversely, do not create a defect merely because an intermediate state looks unusual if the full authority-effect graph proves exact durable responsibility and no incorrect authority is published or destroyed.

### 18.4 Final-checkpoint contract-outward recount

Before CLEAN, repeat the semantic-producer search against the exact final reviewed checkpoint, not only the original diff. Reconcile:

- discovered production consumers;
- reviewed production consumers;
- blocker-relevant semantic consumer classes;
- material result/outcome cells;
- production-wiring evidence for those classes.

Any materially unknown consumer, wrapper, outcome, residual recovery owner, or final authority effect keeps the triggered contract delta open.

This rule generalizes v5's strengthened shared-helper rule. It applies equally to helpers, repository/service boundaries, DAO/CAS operations, async framework calls, ownership primitives, identity predicates, recovery/publication contracts, and other production semantic boundaries.

## 19. Test quality and execution evidence

Test source existence, compilation, or intent is not PASS.

Evidence hierarchy:

1. executed production-path worker/repository fault-injection PASS;
2. executed focused integration/Room PASS;
3. executed focused JVM wiring PASS;
4. executed helper-level pure test PASS;
5. test added but not executed;
6. source reasoning only.

Use exact labels:

- `PASS`
- `FAIL`
- `ATTEMPTED, NOT COMPLETED`
- `FAIL BEFORE EXECUTION`
- `ADDED, NOT EXECUTED`
- `NOT EXECUTED`
- `SOURCE-LEVEL ONLY`
- `SOURCE-LEVEL FIXED` for source semantic status; it is not execution evidence.

For concurrency/race claims, prefer deterministic hooks/latches over timing sleeps. For a new remediation boundary, test the production path rather than only a policy/helper model.

When a material semantic-contract delta is triggered, positive-path success evidence alone is insufficient. For each blocker-relevant semantic consumer class, require executed production-wiring or integration evidence for the material non-success/exceptional/async-completion outcomes that could change durable authority, publication, destructive cleanup, recovery ownership, or later admission. If an outcome cannot be deterministically executed in the available harness, record the exact evidence gap; do not silently substitute source inspection for PASS.

## 20. Mandatory terminal fault matrix

For every relevant authoritative terminal or authority-changing branch, record:

- authoritative decision;
- first persistence/publication call;
- behavior if first persistence fails;
- recovery carrier;
- behavior if recovery write fails;
- durable primary-row state;
- durable linked-ledger state;
- sidecar/native/scheduler state;
- filesystem effect;
- final local outcome;
- scheduler/WorkManager result or exceptional exit;
- whether stale running state can remain;
- whether positive live authority can be revoked incorrectly;
- whether issue/disposition can be downgraded/reinterpreted;
- cross-attempt behavior;
- direct production-path test/fault-injection evidence.

For History-replacement / Finding-A-class paths, explicitly cover at least SourceMismatch, TypeMismatch, TargetMissing, Authorized + cleanup incomplete/failed, committed replacement + ancillary/finalization failure, cancellation, claim CAS zero rows, claim write/materialization failure, process death around claim/owner publication, and recovery overlapping an exact live owner.

## 21. Mandatory cross-attempt matrix

For each previous authoritative state and applicable re-entry path, answer: durable carrier restored, privileged marker survives, mutable identity changes, positive live owner present, next-attempt interpretation, destructive authority possible, and safe?

At minimum inspect same-settings retry, manual/raw requeue, reconfigure, notification retry/resume, restart/reconcile, restore/Undo, process death, stale E1 -> newer E2, old recovery debt + newer live owner, exact current live owner + reconcile, candidate discovered stale -> owner appears before mutation, and committed state -> new queue/attempt.

Any materially unknown required cell means cross-attempt closure is incomplete.

## 22. Candidate-rejection discipline

Before dismissing a suspicious path, write it in its strongest concrete form and test it against the original invariant.

Do not reject merely because the user changed something, code re-authorizes, a helper name implies safety, a lock/flag exists by name, current source/type matches, it resembles another follow-up, the fix overlaps another finding, probability is low, no test exists, or a sibling path was already fixed.

A rejection requires proof that production is unreachable, privileged authority is definitely revoked, a durable semantic barrier is restored, exact current identity is checked at mutation, asynchronous completion is ordered before dependent use, recovery remains discoverable after required carrier loss, or another defect owns it **and** the current invariant is not violated.

Record proof, not reviewer intuition.

## 23. Correctness first, attribution second

First decide whether production behavior is wrong. Only then classify it as current-finding blocker, remediation regression, incomplete closure, pre-existing baseline defect, separately owned ledger defect, remediation-discovered follow-up, deferred P3/nonblocking, verification gap, or false positive.

"Not the current fix order" never means "not a bug."

# Conditional review modules

A conditional module becomes mandatory when its trigger is present. Do not run unrelated modules merely to increase checklist size.

## Module A — Platform capability and pre-semantic admission

**Trigger:** `Build.VERSION`, permissions, special access, framework capability helpers, privileged window/service types, background-launch restrictions, exported/component admission, or compatibility wrappers gate production behavior.

Build a supported-version truth table covering API/platform band, permission/special-access state, helper return/exception, manifest/framework admission, caller interpretation, and durable/user-requested effect. Prove business logic is actually reachable through framework admission.

## Module B — External scheduler handoff and observation

**Trigger:** durable nonterminal intent is handed to WorkManager, AlarmManager, JobScheduler, or equivalent; or scheduling is followed by LiveData/Flow/query observation.

Record exact request identity, durable state before scheduling, synchronous/asynchronous acceptance result, zero-match observation semantics, initial and recovery enqueue, cancellation/supersession, lifecycle recreation, and retry identity. A startup reconcile is not closure if it repeats the same unchecked handoff.

## Module C — External representation / schema / authority projection

**Trigger:** production data crosses an external DB, file format, protocol, native API, browser store, backup serialization, or legacy interchange boundary.

Build a contract matrix for type, unit, epoch/origin, scale/range/signedness, sentinel/null/session values, normalization/encoding, complete source uniqueness/authority key, destination identity fields, omitted dimensions, and authorization scope.

A syntactically valid value is not proof of semantic preservation. If the destination cannot represent a required identity/authority dimension, require explicit reject/skip/context-bound materialization rather than silent flattening.

## Module D — Packaged resource / ABI / variant provenance

**Trigger:** correctness depends on native executables, models, databases, certificates, assets, or other ABI/platform/flavor/build-variant-specific artifacts.

Enumerate every published variant and prove source-set/dependency provenance, companion files, installation/materialization, resolver fallbacks, absence/failure semantics, and relevant production-path verification. A source path or resolver candidate is not evidence that the packaged artifact exists.

## Module E — Referenced-artifact publication and shared-generation promotion

**Trigger:** move/rename/replace/import/export/migration or installer/updater replaces an externally referenced or shared runtime/resource generation.

For referenced artifacts:

```text
old durable reference
-> new artifact creation
-> old artifact removal
-> new reference persistence
```

For shared runtime/resource replacement:

```text
current verified generation
-> replacement decision
-> private staging/materialization
-> completeness verification
-> atomic promotion
-> old-generation cleanup
```

Inject failure/process death around every boundary. Do not destroy old authority before new authority is durably committed or an exact recovery/rollback carrier exists. A mutex serializes attempts; it does not make destructive in-place publication failure-safe.

## Module F — Persisted schema-generation compatibility

**Trigger:** remediation introduces stronger exact identity/ownership, nonblank/nonzero preconditions, new CAS predicates, or stricter resource/process naming over persisted state.

Enumerate supported schema/migration generations and sentinels. Seed the oldest relevant supported nonterminal state, run real migrations, and trace startup/retry/recovery through every new strict helper. A migration-produced blank/zero/null sentinel is valid durable state if supported upgrade code can produce it.

## Module G — Reusable external identifier lifetime

**Trigger:** durable state stores PID, PGID, inode-like numbers, device indexes, handle IDs, names, or other locators that may be recycled after the resource dies.

Test:

```text
generation A publishes locator L
-> A disappears without cleanup
-> L is reused by valid generation B
-> stale A recovery observes L
```

Before privileged signal/delete/revoke/overwrite, require immutable anti-reuse generation/provenance proof. Existence/addressability is not ownership.

## Module H — Persisted executable configuration fan-out

**Trigger:** user/imported persistent configuration is later parsed, compiled, interpreted, or executed (regex, selector, template, expression, query, script fragment, policy).

Enumerate every write/import path, validation/type boundary, every consumer, parser/compiler throwable, invalid-state semantics, restart behavior, and sibling isolation. One safe consumer does not prove sibling consumers are safe.

## Module I — Maintenance vs live-owner namespace

**Trigger:** cleanup, purge, vacuum, migration, cache management, or broad deletion walks a directory/provider tree/table/category that can contain live-owned resources.

Enumerate each independently owned namespace, its durable/process-local owner, stale/idle observation, gap before mutation, lock/lease/transaction that prevents new ownership, exact revalidation at deletion, and owner appearance during enumeration. Do not inherit safety from a sibling namespace or aggregate active-count helper.

# CLEAN gate

## CLEAN

All are required:

- open current-change P1/P2 = 0;
- accepted/waived current-change P1/P2 = 0;
- required focused verification actually completed;
- mandatory terminal fault matrix closed;
- mandatory cross-attempt matrix closed where stateful;
- mandatory live-owner matrix closed where recovery/cleanup/reconciliation can race ownership;
- first required persistence failure directly verified where material;
- recovery-write failure or exact semantic preservation directly verified where material;
- recovery discovery closure proven for surviving durable sidecars where applicable;
- every triggered material semantic-contract delta has a complete contract-delta record, consumer closure, authority-effect closure, and final-checkpoint recount;
- every triggered conditional module closed for blocker-relevant paths;
- actual production wiring not substituted with helper-only tests.

## CLEAN_WITH_WAIVERS

- open current-change P1/P2 = 0;
- explicit accepted/waived current-change P1/P2 exists with exact risk, evidence, owner, and scope.

## NOT_CLEAN

Finding remains NOT_CLEAN if any required path has an open current-change P1/P2, remediation regression, cross-attempt reinterpretation, mutable-state reauthorization of privileged work, exact live authority that recovery/cleanup can revoke incorrectly, stale running state with no live owner or durable recovery responsibility, unresolved authoritative sidecar with terminal success and no exact recovery owner, recovery state that exists but is undiscoverable after supported carrier loss, sibling/remainder stranding or provenance-blind cleanup, mandatory first-write/recovery-write path without proof, ignored asynchronous completion that can change semantic outcome, an open or materially unknown semantic-contract delta/consumer/effect cell, incomplete final-checkpoint consumer recount, or a materially unknown required cross-attempt/live-owner/conditional-module cell.

# Minimal required review output

```text
Verdict: CLEAN | CLEAN_WITH_WAIVERS | NOT_CLEAN

Reviewed checkpoint SHA:
Reviewed ledger SHA:

Current blockers:
- [P?] ...

Verification gaps:
- ...

Separately owned / nonblocking findings:
- ...

Terminal fault matrix:
- ...

Cross-attempt / live-owner matrix:
- ...

Triggered conditional modules:
- Module X: PASS / gap / not triggered

Semantic-contract delta / consumer closure / authority-effect closure:
- Trigger: YES / NO
- Changed boundary: ...
- Old -> new contract: ...
- Final discovered/reviewed production consumer set: ...
- Material outcome cells: ...
- Final authority effects / residual recovery owners: ...
- Final-checkpoint recount: PASS / gap

Recovery discovery closure:
- ...

Candidate rejections reviewed:
- Candidate: ...
  Rejection proof: ...

Verification evidence:
- git diff --check: ...
- compile: ...
- focused JVM: ...
- full JVM if required: ...
- instrumentation/Room: ...
- production-path fault injection: ...
```

# v6 adoption notes

v6 preserves v5's core semantic and execution-evidence requirements. It does not add defect-specific rules or require indiscriminate consumer audits for unchanged contracts.

The principal v6 change is to generalize v5's strengthened shared-helper rule into a **semantic-contract closure** rule with three explicit stages:

```text
Semantic Contract Delta
-> Consumer Closure
-> Authority-Effect Closure
```

The change addresses a general review failure mode: a remediation can correctly change or strengthen a production semantic boundary while unchanged or indirect consumers continue to interpret the old contract. Strong positive-path tests and correct direct callers do not prove closure if other semantic consumers, asynchronous completion owners, residual recovery carriers, destructive actions, or publication/admission effects remain unreviewed.

v6 therefore requires a triggered, diff-independent contract-outward audit and final-checkpoint recount only when remediation materially changes an observable semantic contract. The mechanism applies to helpers, APIs, DAO/CAS operations, callbacks/futures, ownership and lease primitives, identity/provenance predicates, recovery carriers, publication barriers, destructive-cleanup prerequisites, and equivalent production semantic boundaries.

v5 remains preserved for historical review reproducibility, including its folded lesson families: platform/version capability and pre-semantic admission; packaged-resource/ABI provenance; destructive grouping/equality/selection-scope identity; derived-value provenance; semantic revocation vs asynchronous transport cancellation; scheduler acceptance and observer empty state; shared-runtime ownership/generation publication; referenced-artifact cross-domain publication; maintenance vs live-owner mutation; cross-component success handoff; sequential batch remainder isolation; external representation/schema/scope projection; asynchronous reset-before-first-use; reusable external-identifier generation; strengthened shared-helper contract propagation; nominal-success + unresolved sidecar; recovery discovery closure after sibling-carrier destruction; durable-negative-decision invalidation; proof-consumption before carrier destruction; durable schema-generation compatibility; post-carrier/pre-handler throwable frontier; temporary-state cleanup provenance; persisted executable-configuration fan-out; and ignored non-exception persistence/publication failure results.

These checklist changes are review-method changes only. They do not create, close, waive, reclassify, or reattribute any production defect by themselves.

v4 and v5 remain preserved for historical review reproducibility.
