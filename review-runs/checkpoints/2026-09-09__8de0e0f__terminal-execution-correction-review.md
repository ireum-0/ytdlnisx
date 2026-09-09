# Independent correction review — Terminal execution recovery cluster

Starting implementation SHA: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

Correction SHA: `8de0e0f8aa7c0f4294c41b4b6e958acf53a778fa`

Correction parent: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

Branch checked: `checkpoint/pre-baseline-review`

Branch SHA match: `YES`

Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`

Ledger reference, not modified: `899328bc91e4008e39a658387396a0106c8666ec`

Cluster under review: `P2-D / P2-E / P2-F / P2-G`

Verdict: `NOT_CLEAN`

Review Basis advance: `NO`

Canonical count advance: `NO` — keep the pre-correction working count until the cluster correction is clean.

INDEPENDENT EXECUTION: NOT EXECUTED

Reported local/JVM/instrumentation results are implementation-agent evidence, not independent execution evidence. GitHub combined status contained no status checks and no commit-associated workflow runs were present for the correction SHA at review time.

## Blocking finding

### P2 — `NATIVE_STARTED` can become durable before any exact native-generation witness exists, creating a non-convergent recovery fixed point

The correction establishes an app-private `TerminalExecutionRecovery` witness before native execution, which is directionally correct. However the current producer ordering splits the native-start semantic phase from the immutable generation proof.

Production ordering at the correction SHA is:

1. Terminal admission persists `ADMITTED`.
2. `TerminalDownloadWorker` calls `TerminalExecutionRecovery.markNativeStarted(...)`, durably changing the record to `NATIVE_STARTED`.
3. Only afterward does the worker call `YoutubeDLCompat.execute(...)`.
4. `YoutubeDLCompat.executeWithQuiescence(...)` later calls `YtdlpNativeProcessBarrier.prepare(...)`, which atomically writes the exact `STARTING` marker and creates the immutable generation token before `ProcessBuilder.start()`.
5. The Terminal execution journal does not copy that generation token at prepare time. It waits until `ProcessBuilder.start()` succeeds, the process is inserted in the in-memory process map, and `onProcessRegistered` runs. Only then does `bindNativeGeneration(...)` persist the token into `TerminalExecutionRecovery`.

This leaves at least two concrete crash windows:

- after durable `NATIVE_STARTED`, before `YtdlpNativeProcessBarrier.prepare()` creates a token/marker;
- after `prepare()` has durably created an exact generation token/marker, but before `onProcessRegistered` copies that token into the Terminal execution journal.

After process death, `TerminalExecutionRecovery.reconcileRecord()` converts `NATIVE_STARTED` to `NATIVE_QUIESCENCE_PENDING` and calls `proveNativeQuiescent()`. That function returns false whenever `nativeGenerationToken` is absent. It does not consume `YtdlpNativeProcessBarrier.observeGeneration(processId)` / `generationTokenFor(processId)` to re-bind an already durable exact marker token, and it cannot distinguish the pre-prepare window where native never started.

The durable record therefore remains `NATIVE_QUIESCENCE_PENDING` indefinitely. `inspectAdmission()` subsequently returns `BLOCKED`, so every later worker remains fenced with no deterministic convergence path.

This is not merely conservative safety. In the post-prepare subcase an exact durable generation token already exists and the recovery owner fails to consume it; in the pre-prepare subcase the state machine publishes `NATIVE_STARTED` before the proof-producing native launch state exists. The correction therefore recreates a fail-closed fixed point at the native-start boundary.

### Required invariant

> A durable phase that means native execution may have started must never exist without either (a) an exact durable generation identity that recovery can consume, or (b) a durable state whose recovery algorithm can positively prove that no native generation crossed the launch boundary. Fail-closed retention without a proof-producing convergence path is not sufficient.

### Narrow correction boundary

Do not weaken generation safety or fall back to numeric process ID.

One acceptable design direction is to move the Terminal lifecycle transition onto the same proof-producing boundary as `YtdlpNativeProcessBarrier.prepare()`: make the exact generation token durably available to `TerminalExecutionRecovery` before `ProcessBuilder.start()` can occur, and only then publish a state meaning native may have started. Equivalently, introduce an explicit durable STARTING/prepared phase with an exact token and monotonic recovery semantics.

Recovery must also consume an already-durable exact marker generation after restart rather than leaving a tokenless `NATIVE_QUIESCENCE_PENDING` record forever. Malformed/unreadable marker state must remain fail-closed.

Required focused crash/fault coverage should include:

- process death after `ADMITTED` but before native barrier prepare;
- process death after exact barrier `STARTING` token persistence but before `ProcessBuilder.start()`;
- process death after `ProcessBuilder.start()` but before process-registration callback;
- generation-journal bind persistence failure after native registration;
- restart reconciliation with an exact marker token present but the Terminal journal token absent;
- stop/cancel arriving in the same prepared-but-not-bound interval;
- proof that no path signals a newer generation by process ID alone;
- proof that all of the above either converge terminally or retain a retryable exact recovery owner with a real proof-producing next step.

## Scoped results

### P2-F — post-admission throwable gap

The source-level structure is substantially corrected. After durable admission and assignment of the Terminal execution token, command-plan creation, config/request construction, foreground setup, cache ownership setup, and the inner execution body are covered by durable failure convergence. An `ADMITTED` failure can terminalize without native quiescence because native has not crossed the start phase.

No separate P2-F blocker was found outside the native-start/generation handoff finding above.

### P2-E — stale generic recovery carrier

The old generic `PARTIAL_PUBLICATION`/`QUARANTINED_FAILURE` fixed loop now has a finite production owner: exact active/native liveness blocks while positive ownership exists; otherwise recovery can install a Terminal execution tombstone, delete the stale row, and retire exact carrier artifacts after row absence.

No separate generic-carrier P2-E blocker was found. The blocker above is a new tokenless native-start fixed point inside the new execution state machine, so the cluster as a whole cannot be accepted yet.

### P2-G — native quiescence contract

Once an exact generation token is bound, stop/failure convergence correctly treats false quiescence as unresolved and retains the durable owner. `destroyProcessByIdForGeneration(...)` checks the exact token and refuses opaque/mismatched generation state; row/cache cleanup is not authorized from a false destroy result.

The remaining blocker is earlier: the code can enter the native-may-have-started phase before the exact generation token is durably bound to the execution witness.

### P2-D — direct/no-cache durable witness

The new journal does exist before native execution and is independent of output/cache usage, including direct/no-cache and no-output commands. This closes the original witness-absence shape, but the native-start handoff still lacks deterministic crash recovery, so the D/G lifecycle cluster is not clean.

## Test evidence handling

Implementation-agent reported:

- focused JVM TerminalExecutionRecoveryTest: PASS, 5 tests;
- full JVM suite: PASS, 551/551;
- compile tasks: PASS;
- focused emulator production-boundary instrumentation: PASS, 1/1;
- `git diff --check`: PASS.

These are not independent execution results. The focused production test proves `NATIVE_STARTED` exists before its injected native seam, but it does not exercise the real `YtdlpNativeProcessBarrier.prepare()` -> `ProcessBuilder.start()` -> `onProcessRegistered` crash windows identified above.

GitHub status/CI at review time:

- combined statuses: none;
- commit-associated workflow runs: none.

## Review decision

`8de0e0f8aa7c0f4294c41b4b6e958acf53a778fa` is `NOT_CLEAN` for the P2-D/E/F/G correction cluster.

Do not advance Review Basis from `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8` yet. Do not modify the authoritative ledger or reduce the working P2 count from this correction until the native-start/generation handoff is corrected and independently re-reviewed.

INDEPENDENT EXECUTION: NOT EXECUTED
