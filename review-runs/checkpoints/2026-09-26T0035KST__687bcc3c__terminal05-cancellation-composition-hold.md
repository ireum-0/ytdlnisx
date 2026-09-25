# BUG-TERMINAL-05 — independent source review hold: cancellation composition gap

Date: 2026-09-26 +09:00

Finding:
P2 BUG-TERMINAL-05

Prior independently CLEAN basis:
8d4624e810c4a7d51ed625a0dc06302b0f8e1819

Candidate reviewed:
687bcc3c87390c1a0caeacb09cf1886bf2ba37b2

Parent:
8d4624e810c4a7d51ed625a0dc06302b0f8e1819

Prior current-basis checkpoint:
d810e4d1937ce345f04daf4441de906995e2b011

## Verdict

SOURCE-MOSTLY-FIXED / SAME-ROOT CANCELLATION GAP.

Do NOT close BUG-TERMINAL-05 yet.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

Overall remains NOT_CLEAN.

CLEAN_REVIEW_BASIS remains:
8d4624e810c4a7d51ed625a0dc06302b0f8e1819

## Confirmed source improvements

Independent review confirms the candidate correctly adds:
- TERMINAL_DISPATCH durable carriers without a Room schema migration;
- atomic Terminal row + carrier staging in TerminalViewModel.insert();
- exact request UUID persisted before WorkManager publication;
- Operation.result acceptance instead of enqueue-call acceptance;
- retry with stable semantic handoff/generation and new request UUID;
- startup reconstruction of pending Terminal intents;
- accepted-owner retention across transient missing WorkInfo;
- exact worker validation of Terminal id, command, fingerprint, handoff,
  generation, boundary, request UUID, and current row;
- superseded stale-request refusal;
- exact carrier resolution after Terminal semantic convergence.

The reported exact-final-SHA gates are consistent with the added A-G coverage:
- TerminalDispatchHandoffProductionWiringTest 7/7;
- TerminalExecutionProductionWiringTest 2/2;
- WorkManagerHandoffProductionTest 25/25;
- compileDebugKotlin PASS;
- compileDebugAndroidTestKotlin PASS;
- git diff --check PASS.

Implementation-agent execution is evidence, not independent execution.

## Remaining same-root defect

Both user cancellation entry points currently compose the two cancellation
responsibilities with a short circuit.

TerminalViewModel.cancelTerminalDownload():

    val cancellationRequested = WorkManagerHandoffRecovery.cancelTerminalDispatch(...)
    if (!cancellationRequested || !TerminalExecutionRegistry.cancel(...)) {
        ...
        return
    }

CancelTerminalNotificationReceiver has the same shape.

TerminalViewModel.delete() similarly returns immediately when
cancelTerminalDispatch() reports false before TerminalExecutionRegistry.cancel()
is attempted.

This is unsafe when the durable dispatch owner has already been superseded but
the asynchronous WorkManager cancellation Operation fails.

### Concrete sequence

1. Terminal dispatch carrier T is current and a worker has already crossed
   TerminalExecutionRegistry admission.
2. User requests cancellation.
3. cancelTerminalDispatch() durably marks T SUPERSEDED first.
4. cancelWorkByIdAndAwait(T.requestId) fails or throws.
5. cancelTerminalDispatch() returns false.
6. The caller short-circuits and never invokes TerminalExecutionRegistry.cancel().
7. The exact admitted execution/native generation therefore receives no direct
   STOPPED convergence attempt from this user cancellation.
8. Carrier supersession prevents future/stale dispatch authority, but it does
   not itself stop an execution that already crossed worker admission.
9. The current worker may continue until some later stop/recovery event.

This violates the governing correction contract that cancel/delete must:
- durably supersede dispatch responsibility;
- revoke the stale WorkRequest identity;
- preserve the existing downstream TerminalExecutionRegistry/native-quiescence
  cancellation responsibility.

A WorkManager cancellation failure must not suppress the independent exact
execution/native cancellation path.

## Why existing regression does not close this

cancellationSupersedesBeforeOldOperationCanResurrectTerminal() installs a
cancelWorkById override that always succeeds.

It proves:
- supersession precedes late enqueue acceptance;
- a delayed old Operation cannot mark the owner ACCEPTED;
- exact request cancellation is addressed.

It does not exercise WorkManager cancellation failure after a worker/execution
has already acquired durable admission.

## Required narrow correction

Stay within BUG-TERMINAL-05.

1. Keep durable dispatch supersession first.

2. After supersession, attempt WorkManager request revocation and
   TerminalExecutionRegistry.cancel() as independent responsibilities.
   Failure of one must not short-circuit the other.

3. Do not delete the Terminal row until exact execution/native cancellation is
   safely converged according to the existing TerminalExecutionRegistry /
   TerminalExecutionRecovery contract.

4. A failed WorkManager cancellation must leave the superseded exact request
   tombstone/recovery responsibility intact so a delayed request remains
   non-authoritative and can be revoked later.

5. Preserve the rule that a late old Operation success cannot resurrect or
   execute the command.

6. Do not weaken native-quiescence fail-closed behavior merely to make
   cancellation return success.

7. Apply the same semantic composition to:
   - TerminalViewModel.cancelTerminalDownload();
   - TerminalViewModel.delete();
   - CancelTerminalNotificationReceiver.

Prefer a small structured cancellation result or similarly explicit
composition if it prevents callers from conflating durable supersession,
WorkManager cancellation acknowledgement, and native execution convergence.

## Required regression

Add a deterministic same-root production-wiring regression where:
- a Terminal dispatch is durably staged;
- execution has crossed exact TerminalExecutionRegistry admission (or an
  equivalent durable execution witness proves that downstream cancellation is
  required);
- dispatch supersession succeeds;
- the exact WorkManager cancel Operation fails;
- TerminalExecutionRegistry cancellation is still attempted;
- the exact execution is converged STOPPED when quiescence can be proven;
- the row is deleted only when safe;
- the superseded old request remains unable to execute/resurrect even if its
  enqueue Operation later reports success.

Also preserve the existing successful-cancellation and A-G regressions.

## Exact next action

Create one minimal same-root child commit of
687bcc3c87390c1a0caeacb09cf1886bf2ba37b2.

Do not modify unrelated Terminal execution/publication semantics.

Do not advance CLEAN_REVIEW_BASIS or decrement P2 until this composition gap is
closed and exact-final-SHA gates pass.

INDEPENDENT EXECUTION: NOT EXECUTED
