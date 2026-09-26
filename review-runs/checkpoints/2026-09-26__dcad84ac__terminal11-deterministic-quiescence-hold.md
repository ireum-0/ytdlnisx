# dcad84ac — BUG-TERMINAL-11 positive-count fixed / deterministic quiescence still open

Reviewed implementation range:
`41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a..dcad84ac30aa6dac7096961e408b48971c2f0376`

Review parent:
`99adcb896e3a3148044b66e533bbbd56ae02094a`

Protocol:
`b9ff2c984a1655f0f214ab0ec89d3ccb18c0f913`

Governing checklist:
v7 `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`

Verdict: NOT_CLEAN overall.

## Exact range and scope

GitHub proves:
- `dcad84ac30aa6dac7096961e408b48971c2f0376` is one forward commit from
  `41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a`;
- only
  `app/src/androidTest/java/com/ireum/ytdl/work/TerminalPersistedGenerationAuthorityProductionWiringTest.kt`
  changed;
- exact diff is 2 insertions / 1 deletion;
- no production/application source changed.

The change replaces:

`awaitEnqueue()`

with:

`awaitEnqueue(count = siblingCommands.size)`
followed by
`settleEnqueueWindow()`.

## Independent semantic review

The first harness defect identified at 41ef1c5a is fixed:
- the fixture now waits for both expected valid sibling enqueue observations rather than allowing one
  sibling to satisfy the positive wait;
- therefore the prior immediate false-fail window for the second valid sibling is closed.

The negative async property is still not closure-grade.

At the reviewed SHA:
- `reconcileTerminalDispatches()` commits classification/supersession in Room, then publishes each
  runnable Terminal handoff through detached `convergenceScope.launch` jobs;
- the fixture's `awaitEnqueue(count = 2)` establishes only that two enqueue callbacks have been
  observed;
- after that, `settleEnqueueWindow()` immediately skips its `while (enqueued.isEmpty())` loop and
  performs only `delay(250L)`;
- that is elapsed-time settling, not a completion/drain signal for every reconciliation-launched
  dispatch relevant to the fixture.

Protocol async-quiescence rule 17 explicitly requires a bounded completion/quiescence boundary and
forbids elapsed sleep alone as a proxy unless the production contract makes elapsed time a
completion signal. This production path has no such timing contract.

Therefore a regression that incorrectly launched malformed work could still be scheduled after the
two valid sibling callbacks and after the 250 ms settle interval. The negative assertion
`malformed ... must never be enqueued` can still theoretically observe too early.

This is a same-root TEST-HARNESS residual, not a new production defect and not a new finding ID.

## Production semantics

The production source is unchanged from the previously reviewed 225eafd1/41ef1c5a state.

The current exact source still proves:
- malformed provider metadata is classified fail-closed for both legacy and current persisted
  generations;
- generation 2 does not override malformed provider metadata;
- startup reconciliation supersedes the exact malformed outstanding TERMINAL_DISPATCH carrier and
  preserves the Terminal row/command;
- malformed state is excluded from the runnable handoff list;
- enqueue publication revalidates durable owner/authority before WorkManager publication;
- stale/late Terminal WorkRequests are independently refused at the worker authority boundary before
  native admission;
- valid siblings remain independently dispatchable.

No same-root production residual was found.

## Triggered modules / lens state

- Module B — SOURCE PASS / TEST-EVIDENCE GAP remains open only for deterministic async observation.
- Module F — direct persisted generation-2 production cell exists, but execution-harness closure
  remains incomplete because the negative enqueue assertion is not behind a deterministic bounded
  quiescence boundary.
- Module C — PASS for malformed provider representation/authority for this root.
- Module H — PASS source-semantically for this root.
- L1 remains DEEP from prior exact-source review.
- L3 remains DEEP for production semantics; evidence closure remains open at the harness boundary.
- L2/L4/L5/L6 remain BASELINE for this state.

## Finding / count / basis disposition

BUG-TERMINAL-11:

`SOURCE-FIXED / PRODUCTION-CELL-PRESENT / POSITIVE-SIBLING-WAIT-FIXED / DETERMINISTIC-QUIESCENCE-INCOMPLETE`

No new P0/P1/P2 finding ID.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

`CLEAN_REVIEW_BASIS` remains
`74f57e695db30b701ad429af311c39a763bfe086`.

BUG-TERMINAL-03 remains FIXED-CLOSED.

## Implementation-agent execution evidence

Reported exact-candidate evidence:
- focused TerminalPersistedGenerationAuthorityProductionWiringTest: 28/28;
- related Terminal production-wiring partition: 23/23;
- shared WorkManager production-wiring partition: 28/28;
- connected scoped total: 79/79;
- JVM regression scope: 68 tests, 0 failures/errors;
- compileDebugKotlin and compileDebugAndroidTestKotlin: PASS;
- git diff --check: PASS;
- normal fast-forward push; remote implementation equals dcad84ac.

These results are evidence for the exact candidate but do not close the remaining harness
observability defect.

## Required next correction

TEST-ONLY.

Replace the time-based negative-observation settle with an explicit bounded deterministic
completion/quiescence mechanism for the reconciliation-launched Terminal dispatch work relevant to
this fixture.

Requirements:
- retain the full positive sibling count wait;
- do not use fixed sleep, main/UI idleness, or API return alone as the negative proof;
- negative malformed-enqueue assertions must run only after the harness can prove the relevant
  dispatch work has either crossed the enqueue seam or completed refusal;
- preserve exact carrier SUPERSEDED, non-admission, row/command preservation, and both valid sibling
  assertions;
- do not modify production/application source;
- if deterministic closure cannot be achieved from test-only code without a new production test
  seam, STOP and report that constraint rather than changing production source.

INDEPENDENT EXECUTION: NOT EXECUTED
