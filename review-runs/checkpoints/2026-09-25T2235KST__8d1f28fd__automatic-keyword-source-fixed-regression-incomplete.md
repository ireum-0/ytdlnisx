# AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01 — independent source-fixed review; regression contract incomplete

Date: 2026-09-25 +09:00

Implementation branch:
checkpoint/pre-baseline-review

Exact remote implementation HEAD reviewed:
8d1f28fd694e6515b8469e7fc7e28e0a7a939c70

Parent:
3b475625db7bed29c199f6decd39717cb0efbde2

Reviewed range:
3b475625db7bed29c199f6decd39717cb0efbde2..8d1f28fd694e6515b8469e7fc7e28e0a7a939c70

Finding:
P2 AUTOMATIC-KEYWORD-SCHEDULER-HANDOFF-01

Prior current-basis checkpoint:
6b79925ad043b6a2ae219badec242476a4e3081b

## Verdict

SOURCE-FIXED / REGRESSION-CONTRACT-INCOMPLETE.

No independent exact-source blocker was found in the production implementation
range for the authorized scheduler-handoff root.

Do NOT decrement the canonical count yet because two explicitly required
production-wiring regression contracts from the governing remediation prompt
are not present in the exact candidate test source.

Canonical totals therefore remain:
- P0 = 0
- P1 = 0
- P2 = 21

Overall remains NOT_CLEAN.

CLEAN_REVIEW_BASIS remains:
3b475625db7bed29c199f6decd39717cb0efbde2

The implementation remote may remain at 8d1f28fd while the missing test-only
contract is added and exact-final-SHA verification is repeated.

## Independent exact-source findings

### Durable rule/revision/mode owner

The candidate adds AUTOMATIC_KEYWORD_SYNC to the existing durable
work_manager_handoff_carriers model without a Room schema change.

The carrier binds:
- exact rule id;
- exact rule revision through sourceConfigurationGeneration;
- exact semantic mode through decision;
- stable semantic generation id;
- exact WorkRequest UUID;
- per-rule boundary;
- stable unique WorkManager name.

### save / setEnabled / syncNow ordering

AutomaticKeywordRuleRepository now stages the exact carrier inside the same
Room transaction that publishes the rule revision/runtime sync state when work
is required.

WorkManager publication is dispatched only after that transaction returns.

syncNow atomically increments the rule revision, marks apply-existing pending,
sets QUEUED, and stages the corresponding APPLY_EXISTING carrier before
publication.

A process death after the Room commit but before WorkManager acceptance
therefore leaves durable recovery debt.

### acceptance / retry / restart

WorkManagerHandoffRecovery does not equate a non-null Operation with acceptance.

The exact WorkRequest UUID is persisted before enqueue.

Operation.result success is followed by exact carrier acceptance.

Failure advances only requestId/attempt while retaining semantic handoff and
generation identity.

Startup reconciliation reconstructs automatic-keyword rules left in
QUEUED/RUNNING state, retains an ACCEPTED current owner when WorkInfo is
transiently absent, and retries current PENDING/failed responsibility.

### worker fencing

AutomaticKeywordRuleSyncWorker now requires exact:
- rule id;
- originating revision;
- mode;
- handoff id;
- request id;
- generation id;
- boundary;
- WorkRequest id.

It rejects unbound legacy requests and stale revisions before extraction.

After extraction it re-enters exact current-owner authority before applying
automatic-keyword state, preserving the existing second revision/History
identity fences through the engine.

Worker terminal status/carrier resolution uses exact revision/request authority.

### supersession and Restore

New rule revisions, disable/delete, and Restore can make older carriers
durably non-authoritative.

Late accepted stale requests are revoked by exact WorkRequest UUID rather than
broad unique-work cancellation.

Restore supersedes pre-Restore automatic-keyword carriers, reconstructs the
restored rule's exact revision/mode owner, and awaits Operation.result
acceptance under Restore reconciliation authority.

No production source regression was found reopening the closed Observe
generation/runtime or recurrence roots.

## Reported exact-final-SHA execution evidence

Implementation-agent evidence reported on exact committed SHA 8d1f28fd:

- WorkManagerHandoffProductionTest: 23/23 PASS;
- AutomaticKeywordRuleSyncWorkerProductionWiringTest: 11/11 PASS;
- F11HandoffCarrierMutationAdmissionProductionWiringTest: 7/7 PASS;
- BackupResetTransactionProductionWiringTest: 26/26 PASS;
- ObserveSourceGenerationOwnershipProductionWiringTest: 6/6 PASS;
- targeted automatic-keyword revision/ownership persistence tests: 2/2 PASS;
- :app:compileDebugKotlin -x lint: PASS;
- :app:compileDebugAndroidTestKotlin -x lint: PASS;
- git diff --check: PASS.

Initial connected-test attempts were reported infrastructure-invalid and were
not counted. Valid nonzero AndroidJUnitRunner executions replaced them.

## Inherited semantic failures

The implementation agent also reported exact-base attribution at
3b475625db7bed29c199f6decd39717cb0efbde2 for:

1. AutomaticKeywordRulePersistenceTest#
   historyInsertedAfterKnownDiscoveryReceivesRuleKeywords
   - expected Live;
   - actual empty.

2. AutomaticKeywordRulePersistenceTest#
   compatibilityHistoryUpdateCannotDivergeFromAssignments
   - expected Manual;
   - actual Diverged.

Both reportedly reproduce with the same signatures on the exact clean base and
are therefore treated as INHERITED_OUT_OF_SCOPE for this root.

Independent source inspection agrees with that attribution:
- the first expectation conflicts with the existing first-baseline
  eligibleForAssignment=false behavior;
- HistoryDao.updateRaw is an ordinary Room @Update and does not preserve the
  materialized keyword projection against an incoming divergent keywords
  field.

The implementation agent also reported one ObserveSource gate failure
(expected PENDING_ENQUEUE, observed ACCEPTED) reproduced on the exact base.
It is not used as closure evidence and is not attributed to this root.

These inherited failures are not relabeled PASS and are not repaired in this
wave.

## Missing required regression contract

The governing remediation prompt explicitly required deterministic
production-wiring coverage proving, at minimum:

1. save stages exact durable rule/revision/mode debt before asynchronous
   Operation acceptance;

2. setEnabled(true) does the same when synchronization is required.

The exact candidate test source contains direct coverage for:
- exact staging/request identity;
- syncNow atomic staging;
- enqueue failure/restart;
- accepted-owner transient missing WorkInfo;
- disable;
- delete;
- late stale acceptance;
- stale revision;
- wrong mode;
- unbound legacy request;
- revision change during extraction;
- Restore through broader BackupReset coverage.

However no test call to AutomaticKeywordRuleRepository.save(...) was found, and
no production-wiring test was found for setEnabled(ruleId, true) staging a
current exact carrier.

Therefore the candidate has not satisfied the full explicitly authorized
regression contract even though independent source inspection finds the
production implementation coherent.

## Exact next action

TEST-ONLY correction.

Do not modify production source.

Add focused deterministic production-wiring tests that invoke the real
AutomaticKeywordRuleRepository and prove:

A. save:
- a new/enabled or otherwise sync-requiring save commits the exact new rule
  revision/state and a matching AUTOMATIC_KEYWORD_SYNC carrier before the
  asynchronous enqueue Operation is accepted;
- carrier mode matches the durable rule pending/baseline state;
- Operation remains pending while the durable carrier is already visible.

B. setEnabled(true):
- starting from a disabled rule that requires synchronization, enabling it
  increments the exact revision and commits QUEUED/current rule state plus the
  matching revision/mode carrier before asynchronous acceptance;
- the request is bound to that exact carrier/revision/mode.

Do not weaken or alter inherited unrelated persistence tests.

Create one test-only child commit of 8d1f28fd.

Then rerun exact-final-SHA closure gates affected by that test-only commit:
- WorkManagerHandoffProductionTest, including the new tests;
- compileDebugAndroidTestKotlin;
- git diff --check.

Production source is unchanged by this follow-up, so prior exact
8d1f28fd production execution evidence remains source evidence; however the new
test commit itself must have a clean exact-SHA test result.

Normal push is allowed only after fresh remote checks prove implementation
remote is still exactly 8d1f28fd and review remote equals this checkpoint.

Never claim CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
