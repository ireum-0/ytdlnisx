# 41ef1c5a — BUG-TERMINAL-11 production cell present / harness quiescence hold

Reviewed implementation: 41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a
Parent: 225eafd1904a6500d1e1aa6db2aa37d26e10650b
Review parent: 10e43a5ac63a0f359e59842aa62bb5d342d4287c
Protocol: c6cac5f3d7ad10dddb68343f6684e95ae915366c
Governing checklist: v7 e758358ff6d8952470ef3b07f5b18fb26ed4c05c

Verdict: NOT_CLEAN overall.

41ef1c5a is the exact requested TEST-ONLY production-wiring commit:
- one changed file only: TerminalPersistedGenerationAuthorityProductionWiringTest.kt;
- directly seeds malformed generation-2 Terminal rows + outstanding generation-2 TERMINAL_DISPATCH carriers;
- keeps the current-format marker present;
- seeds valid siblings after them;
- runs real WorkManagerHandoffRecovery.reconcile();
- asserts carrier SUPERSEDED, no malformed enqueue/admission, row/command preservation, and sibling dispatch.

Production source at 225eafd1 remains source-fixed. No production residual was found.

Remaining test-harness closure gap:
reconcileTerminalDispatches dispatches each handoff through convergenceScope.launch. The new test calls awaitEnqueue() with its default count=1 even though two valid sibling dispatches are expected, then immediately checks both siblings and negative malformed-enqueue assertions.

Therefore the test has no deterministic quiescence boundary for all launched dispatches:
- it can false-fail if only one valid sibling has enqueued when assertions start;
- more importantly, if a regression scheduled malformed work later than a valid sibling, the negative malformed-enqueue assertion could observe the list before that late enqueue arrives.

The exact run passed, but one passing schedule does not prove the negative asynchronous property.

Required test-only correction:
- after reconcile, await the full expected positive sibling enqueue count (2 in this fixture);
- then use the class's existing bounded settle/quiescence window, or an equivalent bounded deterministic drain, before asserting malformed requests never appeared;
- only then assert exact carrier supersession, non-admission, row preservation, and both sibling enqueues.

Do not modify production source. If the strengthened test exposes a production semantic failure, preserve first-failure diagnostics and STOP for independent reclassification.

Status:
BUG-TERMINAL-11 = SOURCE-FIXED / PRODUCTION-CELL-PRESENT / HARNESS-QUIESCENCE-INCOMPLETE.
No new finding ID. Canonical totals remain P0=0 / P1=0 / P2=19.
BUG-TERMINAL-03 remains FIXED-CLOSED.
CLEAN_REVIEW_BASIS remains 74f57e695db30b701ad429af311c39a763bfe086.

Lens coverage on this test-only SHA:
- L1 DEEP: production cell exists but async observation boundary is not closure-grade.
- L2-L6 BASELINE; production semantics unchanged from 225eafd1.
Triggered Module F remains open only for execution-harness closure.
Module H remains source-semantically PASS.

Implementation-agent reported exact-SHA evidence:
focused class 28/28; connected aggregate 79/79; JVM 68/68; both Kotlin compiles PASS; git diff --check PASS.
GitHub status/workflow/check runs: none.

INDEPENDENT EXECUTION: NOT EXECUTED
