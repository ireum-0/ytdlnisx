# BUG-OBSERVE-03 — independent FIXED-CLOSED exact-final-SHA closure

Date: 2026-09-25 +09:00

Implementation branch:
checkpoint/pre-baseline-review

Exact remote implementation HEAD:
3b475625db7bed29c199f6decd39717cb0efbde2

Parent:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

Reviewed implementation range:
252adf8c0cb3762b4dd1335a7c24fd912be683e5..3b475625db7bed29c199f6decd39717cb0efbde2

Finding:
P2 BUG-OBSERVE-03

Alias:
OBSERVE-SCHEDULER-ASYNC-BARRIER-01

Count once.

Prior source-review / execution-pending checkpoint:
f6fa5f6dba40252053b9d60873017bdb42f88e7c

## Verdict

FIXED-CLOSED.

The independent exact-source review at the prior checkpoint found no source
blocker in the implementation range.

The missing exact-final-SHA execution gates have now all produced valid,
nonzero passing results on the same exact committed candidate that remains the
remote implementation HEAD.

Canonical defect delta:
- P0: 0
- P1: 0
- P2: -1

Canonical totals:
- P0 = 0
- P1 = 0
- P2 = 21

Overall remains NOT_CLEAN because other canonical P2 findings remain.

CLEAN_REVIEW_BASIS advances from
252adf8c0cb3762b4dd1335a7c24fd912be683e5
to
3b475625db7bed29c199f6decd39717cb0efbde2.

## Source closure retained

The accepted implementation provides a durable ordinary recurrence owner:

- worker runtime publication and recurrence staging share the same
  generation-fenced Room transaction;
- the next OBSERVE_RECURRENCE carrier is durable before asynchronous
  WorkManager publication;
- the carrier binds exact source id, source configuration generation, exact
  request UUID, semantic handoff generation, source boundary, unique-work
  name, and durable notBeforeAt;
- WorkManager initial delay is derived from that durable timing;
- Operation.result success is required before ACCEPTED;
- failed publication preserves semantic recurrence ownership and advances only
  the durable request attempt/UUID;
- process restart reconciles persisted recurrence debt;
- accepted recurrence ownership is retained when WorkInfo is transiently
  absent rather than duplicating semantic work;
- the recurrence worker proves exact source generation and exact
  handoff/request authority before acting;
- worker completion consumes/replaces the current durable recurrence owner in
  the same transaction as runtime publication;
- edit/reconfigure, STOP, delete, automatic stop, and owner replacement make
  old recurrence generations stale;
- stale recurrence cancellation is exact-request scoped and does not broadly
  cancel a newer unique-work owner;
- Restore tombstones pre-Restore OBSERVE_RECURRENCE owners before reconstructing
  current destination ownership.

No source regression was found reopening:
- P0 BUG-OBSERVE-HANDOFF-01;
- P2 BUG-OBSERVE-02.

No schema migration was required.

## Exact-final-SHA execution evidence

Exact SHA:
3b475625db7bed29c199f6decd39717cb0efbde2

Already-established final-SHA evidence:
- WorkManagerHandoffProductionTest: 16/16 PASS;
- ObserveSourceWorkerProductionWiringTest: 14/14 PASS;
- :app:compileDebugKotlin: PASS;
- :app:compileDebugAndroidTestKotlin: PASS;
- git diff --check: PASS.

Verification-only completion supplied afterward on the same exact SHA:
- ObserveSourceGenerationOwnershipProductionWiringTest:
  6/6 PASS, 0 failures, 0 skipped;
- F11HandoffCarrierMutationAdmissionProductionWiringTest:
  7/7 PASS, 0 failures, 0 skipped;
- RealWorkManagerHandoffProductionTest:
  3/3 PASS, 0 failures, 0 skipped;
- BackupResetTransactionProductionWiringTest#
  managedKeywordSourceRegainsOwnerAfterDownloadQuiescence:
  1/1 PASS, 0 failures, 0 skipped.

For the targeted BackupReset gate, the first attempt executed zero tests because
ActivityManager reported "failed to attach". That attempt is infrastructure
invalid, not PASS and not a semantic failure. Emulator core services were
confirmed healthy and one narrow infrastructure retry on the unchanged exact
SHA executed 1/1 and passed. Both attempts were preserved separately.

For the generation/ownership gate, Gradle reported BUILD SUCCESSFUL and the
result XML recorded 6/6 passing tests. The wrapper shell remained attached
afterward and was interrupted; that shell condition occurred after the valid
test result and does not invalidate the recorded nonzero passing execution.

The remaining three successful invocations returned exit code 0.

Reported raw evidence directory:
D:/AndroidStudioProjects/ytdlnisx-f11/app/build/observe03-verification-3b475625

Final verification state reported:
- exact protocol / implementation / review anchors matched;
- local HEAD remained exact 3b475625;
- tracked tree remained unchanged;
- no source/test/config edits occurred;
- no commit occurred;
- no push occurred;
- protected primary/baseline worktrees, three protected stashes,
  ignored local.properties, existing crash/replay logs, and prior evidence were
  preserved.

Fresh independent GitHub verification before this closure confirmed:
- remote implementation HEAD still exactly
  3b475625db7bed29c199f6decd39717cb0efbde2;
- review branch still based on prior checkpoint
  f6fa5f6dba40252053b9d60873017bdb42f88e7c before this checkpoint write.

Therefore Protocol Section 16.3 is satisfied for BUG-OBSERVE-03.

This checkpoint does not claim the entire remediation program CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
