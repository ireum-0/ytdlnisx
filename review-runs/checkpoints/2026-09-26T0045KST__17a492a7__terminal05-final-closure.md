# BUG-TERMINAL-05 — final independent closure

Date: 2026-09-26 +09:00

Finding:
P2 BUG-TERMINAL-05

Prior independently CLEAN basis:
8d4624e810c4a7d51ed625a0dc06302b0f8e1819

Primary implementation commit:
687bcc3c87390c1a0caeacb09cf1886bf2ba37b2

Cancellation-composition follow-up:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

Exact final implementation HEAD independently reviewed:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

Prior hold checkpoint:
e4496c36d7a97c53a43a7241640708f476869fa9

## Verdict

CLEAN / FIXED-CLOSED for P2 BUG-TERMINAL-05.

Canonical count delta:
- P0: 0
- P1: 0
- P2: -1

Resulting canonical totals:
- P0 = 0
- P1 = 0
- P2 = 18

Overall remains NOT_CLEAN because unrelated canonical P2 findings remain open.

Advance CLEAN_REVIEW_BASIS to:

17a492a7891c159fcdd66905ecc72d8e9f90aadf

## Durable dispatch closure

Independent source review confirms the cumulative Terminal wave now provides one
durable dispatch contract from Terminal row creation through WorkManager
acceptance, worker admission, cancellation, and semantic convergence.

TerminalViewModel.insert() commits the Terminal row and exact TERMINAL_DISPATCH
carrier in the same Room transaction.

The carrier binds:
- exact Terminal row id;
- immutable command payload;
- command fingerprint;
- handoff id;
- semantic generation id;
- exact WorkRequest UUID;
- boundary/unique work identity.

Publication occurs only after the durable transaction.

WorkManager Operation.result is observed before accepted state is recorded.

Publication failure retains the same semantic generation while advancing the
exact request UUID/attempt for retry.

Startup reconciliation reconstructs pending Terminal dispatch debt and retains
an accepted current owner across transiently missing WorkInfo.

TerminalDownloadWorker requires exact current:
- Terminal id and row;
- command and command fingerprint;
- handoff id;
- generation id;
- boundary;
- request UUID equal to the actual WorkRequest UUID.

Legacy, malformed, superseded, copied, deleted-row, and command-mismatched
requests cannot cross Terminal execution admission.

The existing downstream TerminalExecutionRegistry /
TerminalExecutionRecovery native-generation and publication contracts remain
authoritative after admission.

## Cancellation-composition closure

The prior review hold identified an exact same-root gap:
a failed WorkManager request cancellation short-circuited
TerminalExecutionRegistry.cancel(), allowing an already-admitted native
execution to continue without a cancellation attempt.

Exact follow-up 17a492a7 introduces TerminalCancellationCoordinator as the
single composition owner.

Cancellation now separates:
- durable dispatch supersession;
- WorkManager request cancellation acknowledgement;
- exact execution/native convergence.

WorkManager cancellation success is not a prerequisite for attempting
TerminalExecutionRegistry.cancel().

Terminal row deletion is authorized only when:
- durable dispatch supersession is established; and
- exact execution/native convergence is safe.

A failed WorkManager cancellation therefore cannot suppress native quiescence.

Unproven native quiescence remains fail-closed: the row/witness is retained.

The exact superseded dispatch remains non-authoritative after row deletion.
Startup reconcile enumerates SUPERSEDED carriers independently of Terminal row
presence, and late acceptance/finalization also revokes an exact stale request.
A transiently missing WorkInfo is not treated as proof that a late enqueue
cannot still accept.

TerminalViewModel.delete(), TerminalViewModel.cancelTerminalDownload(), and
CancelTerminalNotificationReceiver now use the same coordinator semantics.

## Regression evidence

Implementation agent reported exact-final-SHA verification at:

17a492a7891c159fcdd66905ecc72d8e9f90aadf

with:
- TerminalDispatchHandoffProductionWiringTest: 8/8 PASS;
- TerminalExecutionProductionWiringTest: 2/2 PASS;
- WorkManagerHandoffProductionTest: 25/25 PASS;
- total connected affected gate: 35/35, 0 failures;
- :app:compileDebugKotlin -x lint: PASS;
- :app:compileDebugAndroidTestKotlin -x lint: PASS;
- git diff --check: PASS;
- tracked tree clean.

The added regression
workManagerCancelFailureStillConvergesAdmittedTerminalExecution proves:
- exact Terminal dispatch was staged and publication issued;
- durable exact execution witness/admission exists;
- dispatch supersession wins;
- injected exact WorkManager cancel Operation fails;
- execution still converges to TERMINAL_STOPPED;
- row release occurs only after convergence;
- delayed old enqueue success cannot recreate an authoritative dispatch or row;
- zero stale command admissions occur.

Two intermediate test defects were corrected without changing production
semantics:
- fabricated process identity replaced by the canonical production
  YtdlpProcessIdentity.terminal(id);
- durable execution witness setup reordered after publication to match
  production's intentional refusal to publish a second request once a witness
  already owns the Terminal subject.

These were test-harness defects, not production findings.

Implementation-agent execution is accepted as evidence; this reviewer did not
independently execute instrumentation.

## Scope review

The follow-up is exactly one child of 687bcc3c and modifies only:
- TerminalCancellationCoordinator.kt (new);
- TerminalViewModel.kt;
- CancelTerminalNotificationReceiver.kt;
- WorkManagerHandoffRecovery.kt;
- TerminalDispatchHandoffProductionWiringTest.kt.

No unrelated root was modified.

The full Terminal implementation range is contiguous from prior CLEAN basis
8d4624e8 through 687bcc3c to 17a492a7.

## Push verification

Live implementation branch independently verified at:

17a492a7891c159fcdd66905ecc72d8e9f90aadf

with exact parent:

687bcc3c87390c1a0caeacb09cf1886bf2ba37b2

The follow-up comparison is one commit ahead / zero behind.

## Queue consequence

BUG-TERMINAL-05 no longer blocks basis advancement.

Continue the established P2 current-basis review order with:

P2 BUG-DUPLICATE-03 — download archive SAF authority.

INDEPENDENT EXECUTION: NOT EXECUTED
