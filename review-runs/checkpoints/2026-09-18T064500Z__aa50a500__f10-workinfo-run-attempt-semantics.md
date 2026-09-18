# F10 final cleanup recovery focused failure: WorkInfo run-attempt observer semantics

## Authoritative implementation state

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Authoritative remote HEAD remains exactly:
  `aa50a500a47263f704914f0960543d6e92ff3aff`
- Parent remains:
  `f869315196b222f6919832181e1f98c29a7ccdfc`
- No implementation push occurred.

Protected local-only test chain:

1. `192e7d15f794493e3c361ec8cb8fdc4535cdfabe`
2. `a0cc16a755ad31deeaf4104397e0678ecc7dfb4a`
3. `711e914a9060671da5c96fb3c639b1342be50cbe`
4. `e76a4bcd07ed5c2d56ac71a95d767c431b410d67`
5. `7d72ce5ab6ccaa8835efcb8196720fcf9ad19054`
6. `eaf4d3058504f13dfe63099f0a01c88b99dc12cd`
   - parent `7d72ce5a...`
   - `test: retain failed cleanup occurrence for recovery`
   - `Defect-ID: BUG-CLEANUP-01`

All remain local-only/non-authoritative until normally pushed.

## Reported verification on local-only eaf4d305

Implementation-agent evidence:

- worktree clean
- `git diff --check`: PASS
- committed-range diff check: PASS
- `:app:compileDebugKotlin -x lint`: PASS
- `:app:compileDebugAndroidTestKotlin`: PASS
- first focused instrumentation attempt: infrastructure-invalid, 0 tests, runner failed to attach
- permitted rerun: 1 test executed / 1 failure / 0 skipped
- test:
  `finalCleanupFailureRetainsExactOccurrenceForRecovery`
- original D1 UUID:
  `cbfaee72-e337-40d0-b892-6a29451f4388`
- exact D1 occurrence:
  generation `2dc79cac-3bc0-4ff4-b955-94efc0fc164d`,
  cadence `daily`,
  occurrence `1789798212992`
- WorkManager progression observed:
  RETRY -> RETRY -> FAILURE
- terminal FAILED assertion passed
- first valid assertion failure:
  expected terminal `WorkInfo.runAttemptCount == 2`, observed `3`
- output-data, terminal-journal, D2-exclusion, and recovery assertions were not reached
- full class was not run.

## Exact-source worker retry contract

At authoritative remote `aa50a500...`:

`CleanUpLeftoverDownloads.MAX_ATTEMPTS = 3`.

For incomplete/recovery-required cleanup, the worker returns:

- RETRY when `runAttemptCount < MAX_ATTEMPTS - 1`;
- FAILURE otherwise.

The `runAttemptCount` seen by the Worker is the current WorkerParameters attempt index.

Therefore the intended three worker invocations are:

1. WorkerParameters `runAttemptCount = 0` -> RETRY
2. WorkerParameters `runAttemptCount = 1` -> RETRY
3. WorkerParameters `runAttemptCount = 2` -> FAILURE

This exactly matches the reported RETRY -> RETRY -> FAILURE progression.

## WorkManager observer semantics

WorkManager's WorkerWrapper constructs WorkerParameters using the current persisted `workSpec.runAttemptCount`, then when the WorkSpec transitions from ENQUEUED to RUNNING it increments the persisted WorkSpec run-attempt count.

Conceptually:

- before first start: persisted count 0; Worker sees 0; WorkSpec becomes RUNNING and stored count becomes 1;
- before second start: Worker sees 1; stored count becomes 2;
- before third start: Worker sees 2; stored count becomes 3;
- the third Worker returns FAILURE;
- terminal WorkInfo exposes the persisted WorkSpec count, therefore `runAttemptCount = 3`.

Thus the local assertion `terminal WorkInfo.runAttemptCount == 2` conflates:

- the zero-based current Worker attempt index, and
- the persisted WorkInfo count of started attempts.

The observed terminal value 3 is consistent with exactly three starts and does not indicate a fourth execution or retry-budget violation.

Android WorkInfo documents `runAttemptCount` as the run-attempt count of the WorkRequest; WorkManager implementation increments the WorkSpec count when entering RUNNING.

## Classification

This is an **instrumentation observer-semantics/off-by-one assertion defect**, not a production retry-budget residual.

Do not change:
- `MAX_ATTEMPTS`;
- the worker's `runAttemptCount < MAX_ATTEMPTS - 1` condition;
- retry/backoff semantics;
- production journal/recovery behavior.

The corrected focused test should distinguish the two surfaces explicitly:

- if it needs to prove the Worker-local terminal attempt index, use a test-side observation executed inside the Worker/effect seam and expect `MAX_ATTEMPTS - 1`;
- if it inspects terminal `WorkInfo.runAttemptCount`, expect the number of WorkRequest starts, which in this deterministic sequence is `MAX_ATTEMPTS`.

The simplest valid terminal assertion for the observed exact sequence is:

`terminalWorkInfo.runAttemptCount == CleanUpLeftoverDownloads.MAX_ATTEMPTS`

while retaining an independent cleanup invocation count equal to `MAX_ATTEMPTS` where the test already has or can narrowly add such evidence.

Do not weaken the assertion merely to `>= 2` if exact three-attempt behavior is deterministic and part of the contract.

## Remaining unverified focused-test contract

Because the count assertion failed first, the following required assertions remain unexecuted/unverified:

- terminal output flags:
  - `cleanup_schedule_failure=true`
  - `cleanup_failure=true`
  - `cleanup_effect_recovery_required=true`
- exact D1 journal remains incomplete/recovery-owned;
- no D2/future occurrence is published over incomplete D1;
- replay/reconciliation retains or requeues exact D1;
- no duplicate/widened destructive responsibility.

The next wave must continue through those assertions. Passing the corrected count assertion alone is not closure.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

- new canonical production root: NO
- count delta: 0
- canonical totals remain P0=2 / P1=0 / P2=24
- Overall remains NOT_CLEAN
- CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- F11 remains blocked.

## Next narrow boundary

Preserve local-only chain through `eaf4d305...`.

Run one test-only forward follow-up:

1. verify authoritative remote remains exact `aa50a500...`;
2. verify local HEAD exact `eaf4d3058504f13dfe63099f0a01c88b99dc12cd` and complete ancestry;
3. preserve all local-only commits without rewrite;
4. correct only the terminal WorkInfo run-attempt expectation so it uses WorkInfo observer semantics;
5. where practical, retain/add independent exact cleanup-attempt evidence so the test proves three Worker executions rather than merely accepting a terminal counter;
6. do not change production retry budget or worker logic;
7. audit only materially identical assertions that compare terminal `WorkInfo.runAttemptCount` to a zero-based Worker-local index;
8. create one new forward-only test commit with `Defect-ID: BUG-CLEANUP-01`;
9. run diff/build checks;
10. run the corrected focused final-cleanup-recovery test against the exact new committed SHA;
11. if focused passes all output/journal/D2/recovery assertions, run the full coordinator class exactly once;
12. on the first valid failure/hang preserve exact request/debt/journal/replay evidence and stop without push;
13. only on nonzero full-class execution with zero failures push the exact tested seven-commit local chain.

INDEPENDENT EXECUTION: NOT EXECUTED
