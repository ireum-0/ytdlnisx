# Observe exact-SHA worker runCount semantic failure

Date: 2026-09-25 +09:00

Implementation remote:
ee7eea001462b77e88a201ed2f26c2385048d421

Local committed candidate:
2d86ca9869721f3b1d482d801463c0024ffaee21

Prior local base:
aaf89b15224b814470c2efab548f4c3e40003de7

Prior review checkpoint:
77cdb35779c30295d9c11d7b1f8f04e88f33de16

## Verdict

FINAL_SHA_VALID_SEMANTIC_FAILURE

No push occurred.

Canonical defect delta remains 0 pending diagnosis.
Canonical totals remain P0=1 / P1=0 / P2=23.
Overall remains NOT_CLEAN.
CLEAN_REVIEW_BASIS remains ee7eea001462b77e88a201ed2f26c2385048d421.

## Exact-SHA progress before failure

Reported PASS on exact SHA 2d86ca9869721f3b1d482d801463c0024ffaee21:
- formerly failing stale-WorkInfo gate and two related tests before commit;
- required BackupSettings gate;
- compile checks;
- migration gates;
- generation/ownership gates;
- handoff-carrier gates;
- source-snapshot gates.

The subsequent ObserveSourceWorkerProductionWiringTest run executed 14 tests
and had one valid semantic failure:

partialRunAdvancesAndStopsAtEndsAfterCountThroughProductionWorker

Expected runCount: 1
Observed runCount: 2
Location:
ObserveSourceWorkerProductionWiringTest.kt:188

This is a valid semantic execution failure.
It is not a zero-test event and not an ADB/UTP transport failure.

The run stopped at the first failure:
- no retry;
- no later gates;
- no source changes;
- no push.

Evidence reportedly preserved under:
app/build/observe-generation-evidence/final-sha-2d86ca986972-20260925/
gate-observe-source-worker/

including result.xml and stdout-stderr.txt.

## Required next diagnosis

Preserve commit 2d86ca9869721f3b1d482d801463c0024ffaee21 unchanged.

Before editing, verify its exact parent relationship to
aaf89b15224b814470c2efab548f4c3e40003de7 and the tracked tree state.

Diagnose the exact runCount lifecycle in the failing test and production worker.

Establish:
1. initial persisted runCount before the worker invocation;
2. every production write that can mutate runCount during this invocation;
3. whether the worker executes once or more than once;
4. whether partial-run bookkeeping and successor/retry publication each mutate
   the same counter;
5. whether the test fixture already starts at runCount 1;
6. whether configuration/runtime ownership split changed the counter update path;
7. whether observed 2 is an intended semantic value or an actual duplicate
   increment.

Do not change the test expectation merely to accept 2.
Do not suppress a production increment merely to force 1.

Follow the governing run-count invariant and partial-run/ends-after-count
semantics.

After root cause is proven:
- make the smallest correct fix;
- run the exact failing method first, count 1, PASS required;
- rerun the full ObserveSourceWorkerProductionWiringTest class;
- rerun directly affected compile/generation/handoff coverage;
- focused whole-diff review;
- create a NEW child commit of 2d86ca98 only after focused precommit checks pass;
- then restart exact-final-SHA verification on that new SHA from the earliest
  affected gate, including BackupSettings if required by the governing final
  verification plan.

Never claim CLEAN before independent review.

INDEPENDENT EXECUTION: NOT EXECUTED
