# Active download blockers — historical agent-error hardening supplement

Date: 2026-10-06

record_kind: REVIEW_CONTRACT_HARDENING_SUPPLEMENT
record_status: FINAL
manual_review_run: NO

implementation_basis: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: 55356d98f5a8b2b19981e1d531f8288462a6c0ef
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
independent_execution: NOT_EXECUTED

supplements:
- review-runs/checkpoints/2026-10-06__db29f63__remaining-download-blockers-correction-boundaries.md
- review-runs/checkpoints/2026-10-06__eda6a758__manual-v7-l6-repository-wide-reopen-final.md

canonical_count_change: NONE
canonical_status_change: NONE
new_finding_ids: NONE
implementation_source_change: NONE
prompt_change: NONE
private_handoff_change: NONE
master_plan_change: NONE
ledger_change: NONE

scope:
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01

## Purpose

The existing correction-boundary checkpoints already establish the production defects, root separation,
minimal correction direction, and acceptance cases. This supplement does not replace or broaden those
roots. It hardens review acceptance against failure patterns that have repeatedly survived earlier
implementation/review waves.

The intended effect is review-side only:

- an implementation may choose any architecture consistent with the governing root contract;
- a reviewer must reject closure when one of the historical anti-patterns below survives;
- a large passing test count, helper-local correctness, or a nominally successful framework/API call is
  never sufficient by itself;
- all closure decisions remain exact-final-SHA source-semantic decisions plus required execution evidence.

## Historical recurrence evidence

The following recurring review failures are directly relevant to the current download blockers.

### H1 — helper facts validated separately but not composed through the final effect

The prior exact-SHA scheduler closure at
`2026-10-06__eda6a758__bug-scheduler-window-01-exact-sha-closure-reviewed.md`
accepted both:
- inclusive end-minute membership; and
- an END publication normalized to the beginning of that same minute.

The later manual L6 review proved those individually tested facts contradict each other on the real
timeline. The helper test preserved the contradiction instead of detecting it.

Review consequence:
**predicate/helper correctness and publication/helper correctness must be composed into one final-effect
timeline invariant.**

### H2 — asynchronous request treated as acceptance/completion

Repeated historical roots show the same class:
- WorkManager `enqueueUniqueWork()` request issued but `Operation.result` not consumed;
- `setForegroundAsync()` future issued but not awaited;
- recurring successor scheduling requested before current-run success, with no exact accepted successor
  or durable recovery owner.

Representative checkpoints:
- `2026-09-11__6763fb1b__section-9.9-workmanager-handoff-reconciliation.md`
- `2026-09-20T0541Z__90afaec1__worker-foreground-completion-exact-basis-revalidation.md`
- `2026-09-20T0607Z__90afaec1__observe-scheduler-async-barrier-exact-basis-revalidation.md`

Review consequence:
**a returned request object, synchronous method return, scheduled callback, or issued cancellation is not
semantic acceptance/completion. The actual finite completion carrier must be consumed, or exact durable
recovery responsibility must already exist.**

### H3 — non-success result discarded and later relabelled as success

The bulk-format root was independently promoted/revalidated more than once:
- ignored per-item `runCatching` result;
- ignored Boolean ownership/CAS refusal;
- unconditional progress;
- aggregate `Result.success()`;
- success-facing notification despite failed/refused items.

Representative checkpoints:
- `2026-09-12__9edd3e23__bulk-format-partial-success-broader-promotion.md`
- `2026-09-20T0554Z__90afaec1__bulk-format-silent-partial-success-exact-basis-revalidation.md`

Review consequence:
**every material outcome value must be classified and consumed. No Boolean, affected-row count,
exception, cancellation, future completion, nullable/sentinel result, or per-item outcome may disappear
before the final worker/batch/UI result.**

### H4 — stale carrier/snapshot retained mutation authority after lifecycle movement

The completed-format notification root survived across exact-basis revalidation because old numeric IDs
remained sufficient to mutate a row after it had acquired newer lifecycle/execution authority.

Representative checkpoints:
- `2026-09-12__9edd3e23__format-notification-stale-authority-broader-promotion.md`
- `2026-09-20T0650Z__90afaec1__format-notification-stale-authority-exact-basis-revalidation.md`

Other review history likewise shows that a row can retain an apparently terminal status while exact
recovery debt remains live; current status alone is not ownership proof.

Review consequence:
**discovery identity and mutation authority are distinct. Revalidate exact current generation/owner at
the final mutation. Never make an old payload authoritative by copying a current token into it.**

### H5 — tests can create or preserve the wrong world

Historical review includes both:
- the scheduler test that encoded an internally contradictory end-minute contract; and
- a WorkInfo seam that permanently hid the successor and therefore created an artificial recovery loop.

Representative checkpoint:
- `2026-09-18T032500Z__aa50a500__f10-workinfo-seam-async-observation-residual.md`

Review consequence:
**test seams must model only the intended fault and must allow the production system to observe effects
that should become observable afterward. A test passing is not evidence if the assertion itself encodes
the defect or the seam removes required production observability.**

---

# Common historical anti-regression gate

The following rules apply to all eight active download blockers in addition to their existing correction
boundaries.

## G1 — Final-effect composition is mandatory

For every changed semantic boundary, write and verify the composed chain:

```
producer decision
-> durable carrier / exact identity
-> async acceptance or refusal
-> production consumer
-> final durable/external/UI effect
-> restart/retry/re-entry effect
```

Closure is rejected if two individually passing helpers publish contradictory authority on the same
timeline or generation.

## G2 — No ignored semantic completion

Closure is rejected if a correctness-relevant call returns or exposes any of the following and production
does not consume its semantic completion:
- WorkManager Operation/future;
- foreground future;
- cancellation completion;
- DAO/CAS Boolean;
- affected-row count;
- typed outcome;
- exception/cancellation;
- callback/future representing publication, revoke, reset, or scheduling completion.

A later reconciler does not retroactively authorize an earlier success unless exact durable responsibility
for that unresolved outcome was already established and remains discoverable.

## G3 — No authority laundering

Closure is rejected if any path:
- reads an old full-row object;
- performs external/suspending work;
- reads a newer token/status;
- copies that newer authority into the old object;
- publishes the old object's unrelated fields.

Current execution/generation identity proves current ownership only. It does not make stale payload fields
current.

## G4 — No captured-set widening

A set captured to authorize one operation must not later be replaced by:
- a live status-wide SQL predicate;
- a shared tag;
- a broader category query;
- a fresh collection whose membership can include later entrants,

unless the product contract explicitly authorizes that widening.

For snapshot-driven operations, late entrants are siblings, not implicit members.

## G5 — Exact identity must survive retries and external carriers

Numeric row IDs, display IDs, class names, shared tags, timestamps, notification extras, and mutable
status values are not sufficient semantic ownership when concurrent/retried generations can exist.

Where a WorkRequest/generation is the authority, exact request UUID plus semantic generation must survive
through durable handoff, notification/cancel capability, retry replacement, stale callback rejection, and
terminal resolution.

## G6 — Durable mutation cannot outrun recoverable responsibility

If durable Download/settings state is changed before an external worker/alarm/effect is accepted, an exact
durable recovery owner must exist before the producer loses authority.

For every such boundary, inject/reason about process death:
- immediately before durable owner creation;
- after owner creation but before primary durable mutation;
- after primary durable mutation but before external request;
- after external request but before acceptance observation;
- after acceptance but before worker/effect execution;
- during retry/replacement.

Any state in which durable product state changed but no exact accepted external owner and no discoverable
durable recovery owner exists rejects closure.

## G7 — Tests must prove the invariant, not the implementation shape

Required negative review question for every focused test:
> Could this test pass while the original user-visible/durable invariant is still violated?

Reject closure when:
- the test asserts two incompatible helper facts without composing them;
- a seam permanently hides an effect production must later discover;
- a synchronous mock bypasses the real async completion boundary;
- only helper-level logic is executed while the defect is production wiring;
- the test verifies call count/tag presence but not exact ownership/final state.

## G8 — Exact-final-SHA recount

Before closure, repeat:
- production consumer inventory;
- async outcome inventory;
- live-owner/stale-generation matrix;
- recovery discovery;
- final effects;
- focused production-wiring assertions

against the exact published candidate SHA. Historical draft tests or pre-publication counts are supporting
evidence only.

---

# Root-specific hardening

## BUG-SCHEDULER-WINDOW-01

Existing contract remains:
- minute-granular membership;
- inclusive start minute;
- inclusive end minute;
- equal start/end means exactly one active minute;
- END external effect is the first instant after the inclusive end minute.

### Additional invariant

For any `now` where `ScheduledDownloadWindow.contains(now) == true`:

1. the END boundary governing that same active window must be strictly later than `now`; and
2. AlarmManager END millis and durable fallback `notBeforeAt` must equal that same boundary.

At the first instant where `contains(now) == false` because that active window ended, no stale owner for
the just-ended window may remain authoritative.

### Historical-error closure rejection

Reject closure if:
- membership and END timestamp are tested separately but not composed;
- a test still permits `contains(now)==true && nextEnd(now)<=now`;
- AlarmManager and durable fallback differ by even one semantic minute/boundary;
- a queue/worker/Observe consumer uses different boundary arithmetic;
- start=end or end=23:59 is handled only by helper tests without production-wiring timeline proof.

Required edge cells:
- END minute at :00.000, :00.001, :30.xxx, :59.999;
- first millisecond of following minute;
- 23:59 -> next-day 00:00;
- start=end one-minute window;
- scheduling invoked inside the inclusive END minute.

## BUG-SCHEDULER-RESTORE-01

The correction must not leave durable scheduler preferences as an unowned external-effect debt.

### Durable convergence ownership sequence

The review must prove one concrete production sequence equivalent in safety to:

```
validated restored scheduler intent
-> exact durable scheduler transition/recovery owner
-> restored preference authority publication
-> stale prior alarm/handoff owner supersession or revoke
-> restored enabled owner publication OR disabled no-owner convergence
-> exact external identity/timestamp verification
-> durable transition resolution
```

Architecture is not prescribed, but every arrow must have one durable/recoverable owner.

### Writer inventory gate

Closure requires an exact inventory of every supported scheduler-setting writer/importer at final SHA:
- ordinary UI/settings transition;
- portable merge restore;
- reset/authoritative restore path;
- startup/recovery replay;
- any sibling direct SharedPreferences writer found by exact-source search.

One canonical UI writer being correct does not close import/restore siblings.

### Historical-error closure rejection

Reject closure if:
- scheduler strings are validated only by generic String/type checks;
- preferences become authoritative before any recoverable scheduler-effect responsibility exists;
- post-restore code merely supersedes an internal transition record but does not prove AlarmManager /
  WorkManager external convergence;
- enabled->disabled leaves any stale start/end alarm, handoff, receiver, or retry owner current;
- enabled->enabled changed times leave an old generation capable of winning later;
- a stale old receiver/request can act after restored generation wins;
- merge and reset rely on different unreviewed ownership semantics;
- process death after preference commit can strand external state;
- restore-owned code recursively enters ordinary Restore admission;
- exact AlarmManager timestamp and durable fallback timestamp are not compared on restored configuration.

Mandatory fault/cross-attempt cells:
- invalid HH:mm rejected before executable authority;
- valid enabled->enabled changed times;
- enabled->disabled;
- disabled->enabled;
- old alarm/carrier exists before restore;
- death after durable transition owner but before preference commit;
- death after preference commit but before old-owner revoke;
- death after revoke but before new publication;
- enqueue/alarm publication failure;
- recovery-write failure where material;
- stale E1 receiver/request after E2 restored owner;
- repeated startup reconciliation idempotence.

## BUG-FORMAT-BG-01

The existing exact captured-bundle contract remains authoritative.

### Additional review invariant

The operation must have one immutable captured bundle identity. The later mutation boundary may narrow that
set by eligibility/refusal, but must never widen it.

Mutation must be exact-id + expected-state guarded. A broad
`WHERE status = Processing` transition is disallowed for this operation even if followed by filtering.

### Historical-error closure rejection

Reject closure if:
- any noncaptured later Processing entrant can change state;
- a captured row that advanced can be pulled back to Saved;
- exact bundle IDs are used for the worker but a broader live predicate is used for lifecycle mutation;
- refusal is represented only by a later worker skip rather than preventing the unauthorized row mutation;
- deleted rows are recreated through stale snapshots.

Mandatory race:
A/B captured, A selected, C enters Processing before mutation, B advances before mutation.
Expected: C untouched, B not pulled back, only still-authorized captured rows transition.

## BUG-FORMAT-BG-02

This root is especially exposed to the historical request-vs-acceptance error.

### Exact handoff state contract

The final implementation must make the following semantic states distinguishable for one format batch:
- durable semantic generation staged;
- exact current request UUID assigned;
- enqueue requested;
- enqueue accepted;
- enqueue failed/refused;
- replacement request UUID assigned under same semantic generation;
- executing/terminal as needed for exact retirement;
- cancelled/superseded terminal ownership.

Equivalent naming is allowed, but those distinctions may not be collapsed if doing so loses restart or
stale-request authority.

### Historical-error closure rejection

Reject closure if:
- `enqueueUniqueWork()` returning is treated as acceptance;
- `Operation.result` is ignored;
- exact selected + other bundle payload exists only in volatile WorkRequest Data before acceptance;
- Saved state can exist with neither accepted WorkRequest nor durable format-handoff owner;
- retry reuses an old failed request UUID as current authority;
- stale R1 completion can accept/retire replacement R2;
- startup recovery scans only Download status and cannot reconstruct exact original format-batch semantics;
- recovery can duplicate an already accepted exact request;
- concurrent batches share one semantic owner/tag such that one can retire another.

Mandatory terminal/cross-attempt cells:
- first durable handoff write failure;
- lifecycle transition failure after handoff staging;
- process death before enqueue;
- async Operation failure;
- process death after acceptance before worker start;
- R1 failure -> R2 replacement -> late R1 callback;
- two concurrent batches;
- repeated recovery;
- cancellation/terminal exact retirement.

## BUG-FORMAT-BG-03

Historical bulk-format review shows this root is highly recurrence-prone.

### Required per-item outcome algebra

Every requested item must finish the current attempt in exactly one explicit semantic class, at minimum:
- SUCCESS;
- ALREADY_SATISFIED with fresh proof;
- AUTHORITY_REFUSED;
- RETRYABLE_FAILURE;
- TERMINAL_FAILURE;
- CANCELLED;
- UNPROCESSED_DUE_TO_BATCH_CANCELLATION where applicable.

No requested ID may disappear from the accounting.

### Required batch aggregation contract

The implementation/review must define one deterministic mapping from the multiset of item outcomes to:
- WorkManager result;
- retained durable residual responsibility;
- progress;
- success-facing notification contents;
- failure/retry/cancellation publication.

The exact architecture may vary, but these invariants are mandatory:
- any retryable residual prevents false terminal full success unless exact residual responsibility is
  durably detached and owned elsewhere;
- authority refusal is not success unless a fresh exact state proves ALREADY_SATISFIED;
- cancellation remains cancellation and must not be swallowed by `runCatching`;
- terminal failure cannot be counted as updated;
- successful siblings need not be rolled back merely because another item fails;
- retry must not re-run already committed siblings in a way that can overwrite newer authority.

### Result + Download publication decision must be explicit

The implementation must choose and prove one of:
1. one atomic transaction for the semantic refresh publication; or
2. an explicit compensating/recovery protocol for split publication.

Leaving this as accidental sequential writes is not acceptable.

### Historical-error closure rejection

Reject closure if:
- `runCatching`/catch consumes failure without producing an item outcome;
- `updateIfExecutionOwned()==false` is ignored;
- foreground establishment completion is not owned before correctness-relevant work;
- progress increments because the loop advanced rather than because an outcome was classified;
- any materially failed/refused/unprocessed item can coexist with a full-success notification;
- Result updated / Download stale split can be labelled success;
- mixed outcomes are tested only by mocks that cannot produce the real DAO/async refusal.

## BUG-FORMAT-BG-04

This root must be reviewed as an authority-transfer problem, not merely a stale-data problem.

### Publication authority rule

External fetch output is data only.

After fetch, production must:
1. reread the exact current Download/Result authority needed for publication;
2. validate immutable source identity;
3. validate the exact configuration dimensions that determine whether this fetched result is still
   semantically applicable;
4. publish only format-owned fields through a narrow guarded mutation.

A pre-fetch `DownloadItem` must never become current merely by copying current status/execution fields
into it.

### Configuration invalidation contract

Before closure, the implementation/reviewer must explicitly enumerate:
- configuration fields whose change invalidates the fetched result and requires refusal/recompute;
- unrelated fields that must survive untouched because narrow publication does not own them.

At minimum independently classify:
- source URL/source identity;
- type;
- format-selection preferences;
- path/container;
- incognito;
- operation/retry generation;
- execution generation;
- issue/finality fields.

Do not rely on an informal "relevant fields" set.

### Historical-error closure rejection

Reject closure if:
- any full-row writer receives the pre-fetch object after a suspension/external fetch;
- newer executionId/status is copied into an old payload;
- URL/source changed but publication proceeds;
- configuration that determines selected format changed but old fetched selection is silently published;
- deleted row can be recreated;
- narrow format publication overwrites path/container/incognito/newer operation fields;
- refusal is not propagated into BG-03 truthful outcome handling.

Mandatory race cells:
- preference edit during fetch;
- type edit;
- URL/source replacement;
- path/container/incognito edit;
- newer Queued/retry;
- newer Active execution;
- deletion;
- barrier/refusal.

## BUG-FORMAT-BG-05

This root is an exact capability-identity problem.

### Capability contract

The cancel capability exposed by every initial/progress notification must identify the exact live
WorkRequest UUID. PendingIntent identity itself must prevent aliasing; putting UUID only in extras is not
sufficient when Android PendingIntent equivalence can still reuse the same capability.

### Historical-error closure rejection

Reject closure if:
- class name or shared `updateFormats` tag remains the cancellation target;
- cancel can affect more than one concurrent batch;
- initial foreground notification lacks exact cancel while later progress has it;
- retry R2 publishes/uses a capability that can still be cancelled by stale R1 notification;
- malformed/missing UUID falls back to broad cancellation;
- cancellation is issued but BG-03 still reports the cancelled item/batch as success;
- tests verify only intent extras/tag strings rather than actual exact WorkManager cancellation.

Mandatory cells:
- cancel before first progress;
- after progress;
- concurrent A/B;
- stale A notification after B/replacement;
- malformed UUID;
- R1 retry replaced by R2;
- actual WorkInfo reaches expected cancelled/terminal state where feasible.

## BUG-INCOGNITO-01

This root is simple in expression but privacy-sensitive; Boolean naming must not substitute for a truth
table.

### Normative aggregate truth table

For the exact current candidate set:
- [] -> allIncognito=false;
- [true] -> true;
- [false] -> false;
- [true,true] -> true;
- [false,false] -> false;
- [true,false] -> false.

Selected mode evaluates only exact currently relevant selected Processing candidates.
Unselected mode evaluates the exact current Processing set.

### Action-boundary authority

The aggregate used to choose enable/disable behavior must correspond to the exact mutation candidate set.
If rows/selection can change between display and click, stale icon alpha/UI state is not authority.

The action must rederive or transactionally/CAS-guard the exact candidate set and expected current
incognito state. Rows that leave the authorized candidate set before mutation are skipped/refused, not
mutated through a stale UI decision.

### Historical-error closure rejection

Reject closure if:
- `count > 0` or any-equivalent logic remains for an ALL predicate;
- empty set becomes true;
- mixed set is rendered/treated as full privacy;
- UI alpha/icon state alone decides durable mutation;
- selected subset mutation leaks to unselected rows;
- a row leaving Processing before action is still toggled due to stale selection;
- the fix suppresses History globally instead of preserving the per-row worker rule
  (`incognito=true` suppresses, false follows ordinary History behavior).

---

# Mandatory closure matrices for these roots

The existing per-root acceptance lists remain required. In addition, reviewers must record the following
cells when applicable.

## Terminal fault matrix

For each authority-changing path:
- authoritative decision;
- first durable write;
- failure of that write;
- durable recovery owner;
- recovery-owner write failure where material;
- external enqueue/alarm/foreground request;
- asynchronous acceptance failure;
- durable primary-row state after failure;
- final local/WorkManager result;
- notification/UI claim;
- whether exact residual responsibility remains discoverable.

## Cross-attempt / stale-owner matrix

At minimum:
- stale E1 request/callback/notification vs newer E2;
- old recovery debt vs current live owner;
- retry with changed configuration;
- process restart before acceptance;
- process restart after acceptance;
- deleted row before replay;
- concurrent sibling batch;
- cancellation followed by replacement;
- current valid owner plus stale recovery reconciliation.

The exact root may mark genuinely irrelevant cells NOT_APPLICABLE only with concrete production proof.

# Review disposition

The original correction-boundary documents remain authoritative for root identity and minimal correction
scope. This supplement tightens only closure review.

No new root is created by these historical patterns. They are recurrence guards for already-open roots.

Current canonical download blockers remain:
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01

No implementation, prompt, private handoff, Master Plan, ledger, or production-source mutation is
authorized or performed by this checkpoint.

INDEPENDENT_EXECUTION=NOT_EXECUTED
