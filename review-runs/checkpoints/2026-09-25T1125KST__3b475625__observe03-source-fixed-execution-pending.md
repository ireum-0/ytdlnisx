# BUG-OBSERVE-03 — independent source review; exact-SHA execution completion required

Date: 2026-09-25 +09:00

Implementation branch:
checkpoint/pre-baseline-review

Exact remote implementation HEAD reviewed:
3b475625db7bed29c199f6decd39717cb0efbde2

Parent:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

Reviewed range:
252adf8c0cb3762b4dd1335a7c24fd912be683e5..3b475625db7bed29c199f6decd39717cb0efbde2

Finding:
P2 BUG-OBSERVE-03
Alias: OBSERVE-SCHEDULER-ASYNC-BARRIER-01
Count once.

Prior current-basis checkpoint:
8ca21699ebbe12b005124e9d800da4c2a568bcd5

## Verdict

SOURCE-FIXED / EXECUTION-VERIFICATION-INCOMPLETE.

No independent exact-source blocker was found in the reviewed implementation
range.

Do NOT decrement the canonical finding count yet because the governing
exact-final-SHA execution contract is not fully evidenced at the final remote
SHA after the final behavior-relevant timing/authority refinements.

Canonical totals therefore remain:
- P0 = 0
- P1 = 0
- P2 = 22

Overall remains NOT_CLEAN.

CLEAN_REVIEW_BASIS remains:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

The remote implementation HEAD may remain at 3b475625 while the missing
verification is completed. No source rewrite or rollback is authorized by this
checkpoint.

## Independent exact-source review

### Durable recurrence owner is committed before asynchronous publication

WorkManagerHandoffCarrier now has an OBSERVE_RECURRENCE kind using the existing
generic durable carrier schema.

ObserveSourcesRepository.finishRunAndSchedule(...) enters ordinary mutation
admission and delegates to
WorkManagerHandoffRecovery.commitObserveRunAndStageRecurrence(...).

That coordinator commits, in one Room transaction:
- exact ACTIVE source-generation validation;
- worker-owned runtime state;
- the next generation-bound OBSERVE_RECURRENCE carrier for a continuing run,
  or the automatic-stop revocation for a terminating run.

The carrier contains:
- source id;
- exact source configurationGeneration;
- exact WorkRequest UUID;
- stable semantic handoff/generation id;
- unique WorkManager name;
- boundary keyed to the source;
- next-run notBeforeAt.

Only after the durable transaction succeeds does the repository start
ensureConvergence(...).

Thus a process death or enqueue failure after runtime commit leaves durable
recurrence debt instead of silently stranding an ACTIVE source.

### WorkManager acceptance is now explicit

The recurrence carrier is initially PENDING_ENQUEUE.

The exact WorkRequest is created with:
- the carrier's exact request UUID;
- source id;
- source configuration generation;
- recurrence handoff id;
- recurrence request id;
- generation tag;
- WorkManager initial delay derived from durable notBeforeAt.

The publication call is short and generation/owner checked under ordinary
mutation admission.

Operation.result is observed outside that admission boundary.

Only a successful Operation.result may advance the same exact durable request
to ACCEPTED.

A failed publication advances only the carrier request UUID/attempt while
retaining the same semantic recurrence generation and durable timing.

### Restart recovery is durable and exact

Startup WorkManagerHandoffRecovery.reconcile(...) reads persisted carriers.

For PENDING recurrence debt it can retry the exact persisted request or the
durably advanced retry request.

For ACCEPTED recurrence debt with no currently observable WorkInfo it keeps the
accepted exact owner instead of manufacturing a duplicate semantic successor.

A successful recurrence worker carries the exact handoff/request identity.
Before effects it proves that:
- source is ACTIVE;
- source generation matches;
- carrier/request/source/generation match;
- the carrier is still the current retained owner for that source boundary.

When that worker finishes, consumption of the current owner and staging of the
next recurrence owner occur in the same transaction as runtime publication.

### Stale generations cannot reclaim recurrence authority

Recurrence authority requires both:
- the durable source's exact ACTIVE configuration generation; and
- the exact current outstanding carrier for that source boundary.

Edit/reconfigure generation advancement, STOP, delete, automatic stop, or
replacement of the durable boundary owner therefore makes older recurrence
debt stale.

Stale recurrence carriers are tombstoned as SUPERSEDED. Recovery cancels only
their exact WorkRequest UUID when needed; it does not broadly cancel a newer
unique-work owner.

This preserves the previously closed generation/ownership fence.

### Restore supersedes prior recurrence authority

RestoreTransactionCoordinator now marks OBSERVE_RECURRENCE carriers SUPERSEDED
when Observe responsibility is reset/quiesced.

Those tombstones are not returned as outstanding current owners. Existing
Restore quiescence/reconciliation can then reconstruct destination/current
Observe ownership without treating pre-Restore recurrence debt as authority.

No schema migration is required because the existing carrier table already
contains all required fields; this change adds only a new carrier kind.

### Previously closed findings remain closed by source inspection

No source regression was found that reopens:
- P0 BUG-OBSERVE-HANDOFF-01;
- P2 BUG-OBSERVE-02.

The recurrence implementation continues to bind worker effects to exact source
generation and does not restore full-row UI/runtime ownership.

## Implementation-agent execution evidence

The implementation agent reports the following against final committed SHA
3b475625db7bed29c199f6decd39717cb0efbde2:
- git diff --check: PASS;
- :app:compileDebugKotlin: PASS;
- :app:compileDebugAndroidTestKotlin: PASS;
- WorkManagerHandoffProductionTest: PASS 16/16;
- ObserveSourceWorkerProductionWiringTest: PASS 14/14.

Those final-SHA results cover the new durable recurrence owner, explicit
Operation acceptance/retry/restart behavior, and the worker-side recurrence
contract.

The implementation agent also reported:
- F11 handoff: PASS 7/7;
- Real WorkManager handoff: PASS 3/3;
- targeted Restore regression: PASS 1/1;

but explicitly stated that those supporting runs occurred before the final
recurrence-specific timing/authority refinements.

## Exact-final-SHA gap

The governing remediation prompt requires final closure on the exact committed
SHA and at minimum includes:
- ordinary recurrence acceptance/recovery;
- generation/ownership;
- handoff-carrier coverage when affected;
- worker production wiring;
- affected prior gates.

Protocol Section 16.3 does not allow a materially earlier behavior-relevant
tree to substitute for the final SHA.

The report does not establish that the following required/affected gates were
executed after the final refinements on exact SHA 3b475625:

1. ObserveSourceGenerationOwnershipProductionWiringTest
   - prior expected scope: 6 tests;

2. F11HandoffCarrierMutationAdmissionProductionWiringTest
   - prior reported scope: 7 tests;

3. RealWorkManagerHandoffProductionTest
   - prior reported scope: 3 tests;

4. BackupResetTransactionProductionWiringTest#
   managedKeywordSourceRegainsOwnerAfterDownloadQuiescence
   - targeted Restore/Observe recurrence regression: 1 test.

The final WorkManagerHandoffProductionTest 16/16 and
ObserveSourceWorkerProductionWiringTest 14/14 do not need to be rerun merely
for duplication if the implementation tree remains exactly 3b475625 and no
behavior-relevant files change.

No migration gate is required for this commit because no schema/migration file
changed.

## Exact next gate

Verification-only.

Do not edit source/tests/configuration.
Do not create a new commit.
Do not amend/rebase/squash/rewrite.
Do not push again.

On a clean checkout/worktree at exact
3b475625db7bed29c199f6decd39717cb0efbde2, execute the four missing exact-SHA
gates above serially.

Every intended test/class must execute a nonzero count.

Any valid semantic failure stops immediately.

Infrastructure-invalid execution remains not PASS and may be recovered/rerun
under the normal infrastructure rules.

If all four pass on exact 3b475625 with no behavior-relevant changes, the
existing final-SHA evidence plus those results is sufficient to return for
independent FIXED-CLOSED review.

INDEPENDENT EXECUTION: NOT EXECUTED
