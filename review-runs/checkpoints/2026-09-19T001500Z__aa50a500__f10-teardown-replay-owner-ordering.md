# F10 terminal-successor teardown timeout: teardown drains WorkManager before revoking replay ownership

## Authoritative implementation state

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Authoritative remote HEAD independently re-verified as:
  `aa50a500a47263f704914f0960543d6e92ff3aff`
- Expected parent:
  `f869315196b222f6919832181e1f98c29a7ccdfc`
- No implementation push occurred.

Protected local-only test chain reported after the latest consolidation attempt:

1. `192e7d15f794493e3c361ec8cb8fdc4535cdfabe`
2. `a0cc16a755ad31deeaf4104397e0678ecc7dfb4a`
3. `711e914a9060671da5c96fb3c639b1342be50cbe`
4. `e76a4bcd07ed5c2d56ac71a95d767c431b410d67`
5. `7d72ce5ab6ccaa8835efcb8196720fcf9ad19054`
6. `eaf4d3058504f13dfe63099f0a01c88b99dc12cd`
7. `c0d0da802cdb0cbfab8fd33fdc0dde024a1573e2`
8. `3dfa9993b441a00d9afd06111ac9e61b29c58e60`
9. `c2159c213025ea347e2d5d6393cbc3cb2f7ee030`
10. `d9ae267438119e2bc8da29fd5408c4d50e3fb5b1`
    - `test: assert exact successor debt after persistence failure`
    - `Defect-ID: BUG-CLEANUP-01`
11. `6353dc47d6e0af1b13a1da34345ea90aab22d0ef`
    - parent `d9ae2674...`
    - `test: use occurrence tag for successor identity`
    - `Defect-ID: BUG-CLEANUP-01`

All remain local-only/non-authoritative until normally pushed.

## Reported verification on 6353dc47

Implementation-agent evidence:

- `git diff --check`: PASS
- committed-range diff check: PASS
- `:app:compileDebugKotlin -x lint`: PASS
- AndroidTest compile:
  - first correction attempt exposed invalid `WorkInfo.inputData` test usage;
  - forward correction switched to exact occurrence-tag identity;
  - corrected compile PASS.
- focused successor-debt test:
  - first attempt infrastructure-invalid due UTP/ADB disconnect before semantic execution;
  - one permitted rerun: PASS 1/1.
- full coordinator class:
  - 69 discovered;
  - first valid failure during `terminalWorkerRetainsExactSuccessorAcrossReplayOwnerLoss`;
  - teardown cancellation wait timed out;
  - last reported WorkInfo:
    `6f257798-57b3-44c7-aff0-5f9921726605 ENQUEUED / attempt=0`;
  - runner stopped after first valid failure;
  - no push.

The exact local-only helper/diff at `6353dc47` is not GitHub-authoritative and is not independently inspectable here. Runtime mechanics of the reported local helper remain implementation-agent evidence.

## Exact-source teardown ordering defect

At authoritative remote `aa50a500...`, class teardown is:

1. `cancelAllWorkAndAwaitIdle()`;
2. `clearTestSeams()`;
3. `failOutstandingControlledOperations()`;
4. `CleanupScheduleCoordinator.configure(context, null)`;
5. main-looper drain and remaining cleanup.

`clearTestSeams()` performs `CleanupScheduleCoordinator.resetReplayOwnerForTesting()`.

Therefore teardown attempts to prove WorkManager idle **before** it revokes the process-local replay owner and before it disables durable cleanup cadence.

This ordering is unsafe for any test that intentionally finishes with a durable current cleanup debt plus a live replay owner.

## Exact-source terminal successor test

`terminalWorkerRetainsExactSuccessorAcrossReplayOwnerLoss` intentionally:

1. runs exact predecessor D1;
2. forces successor handoff persistence failure;
3. observes D1 terminal success with handoff pending;
4. explicitly discards the prior replay owner;
5. removes the persistence failure;
6. calls real `reconcile(context)`;
7. waits until exact D2 becomes active;
8. verifies one unfinished exact D2 WorkRequest.

That successful D2 reconstruction also calls/retains normal replay ownership for exact D2.

The test therefore legitimately ends with:
- durable enabled DAILY authority;
- exact D2 debt;
- unfinished exact D2 WorkRequest;
- a process-local replay owner capable of reconciliation while that debt remains current.

## Exact-source production replay behavior

`ensureReplayOwnerLocked(...)` starts `replaySchedulingDebt(...)`.

While the expected debt remains current, `replaySchedulingDebt(...)` repeatedly:
- validates/repairs durable debt;
- calls `reconcileSuspending(context)`;
- continues while exact debt remains current.

If WorkManager work disappears while durable authority/debt remains current, reconciliation is explicitly allowed to recreate the exact request.

Thus teardown's current ordering creates a race:

`cancel WorkManager`
→ `wait for no unfinished work`
while
`live replay owner + durable enabled debt`
→ `reconcile/recreate work`.

A cleanup WorkInfo remaining/reappearing `ENQUEUED/attempt=0` is consistent with that race. The report does not independently prove whether the final UUID is the original cancellation target or a replay-created replacement, so do not overstate that detail.

## Classification

This is a **test teardown lifecycle/quiescence ordering defect**, already within the preauthorized F10 harness-consolidation root:

- async actor/callback quiescence;
- teardown that attempts to clear an external scheduler before stopping the actor authorized to repopulate it.

It is not evidence that production cancellation or successor replay is incorrect.

## Required teardown contract

Teardown must revoke the producer of new cleanup work before asserting WorkManager emptiness.

The teardown should establish, in bounded order:

1. preserve useful failure diagnostics before destructive teardown;
2. release/fail any test-controlled operations necessary to prevent harness-held callbacks from remaining pending;
3. remove test fault seams that could block the real disable transition;
4. revoke/stop process-local replay ownership;
5. durably disable cleanup scheduling through the real coordinator authority path (`configure(context, null)`) so old replay activity can no longer consider the debt current;
6. drain relevant main-executor callbacks when needed;
7. only then cancel/drain remaining cleanup WorkManager work and prove no unfinished tagged work remains;
8. finally clear raw test preferences/files/database fixtures.

Exact helper ordering may be adapted to the existing local-only harness, but the semantic barrier is mandatory:

**no actor may remain authorized to enqueue cleanup work while teardown is waiting for WorkManager to become idle.**

Do not replace this with longer timeouts.

Do not weaken teardown to ignore ENQUEUED/RUNNING/BLOCKED work.

## Consolidation implication

Because this is another preauthorized harness pattern, the next wave should remain in Section 4.2 harness-consolidation mode rather than returning to one-failure-per-chat behavior.

The full-class static audit should specifically include:
- setup/teardown ordering where replay owners or async callbacks can recreate work;
- tests that intentionally end with durable debt/live replay ownership;
- cleanup helpers that cancel external work before revoking its producer;
- test-controlled operations/seams that may need to be released before global drain.

Once the teardown barrier is corrected, the affected terminal-successor test should be focused-run, followed by the remaining changed focused set, then the full class.

## Production stop boundary

STOP and reopen possible production semantics only if, after teardown has durably disabled authority and revoked replay ownership:
- exact cleanup work is still recreated by a production actor;
- cancellation/disable leaves current durable cleanup authority unexpectedly enabled;
- duplicate/widened cleanup responsibility appears;
- exact D2 recovery itself fails before teardown begins.

Do not modify production from the current evidence.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

- new canonical production root: NO
- count delta: 0
- canonical totals remain P0=2 / P1=0 / P2=24
- Overall remains NOT_CLEAN
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 remains blocked.

INDEPENDENT EXECUTION: NOT EXECUTED
