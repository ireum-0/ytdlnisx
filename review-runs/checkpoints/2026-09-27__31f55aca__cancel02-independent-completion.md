# BUG-CANCEL-02 independent completion review — fixed closed

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW
review_parent_sha: `b79be06d197f1b271c5c816799dc2f65a359f830`

reviewed_implementation_sha:
`31f55aca76efb1b956567fca5325b2575779a835`

reviewed_range:
`359ddbf9bf534009be095ad1bffea8ec45c899e4..31f55aca76efb1b956567fca5325b2575779a835`

history_status:
- merge base = `359ddbf9bf534009be095ad1bffea8ec45c899e4`;
- head is 6 commits ahead, 0 behind;
- no rewrite/divergence detected;
- commits are forward-only.

changed_files:
- `app/src/main/java/com/ireum/ytdl/work/TerminalDownloadWorker.kt`
- `app/src/main/java/com/ireum/ytdl/work/TerminalExecutionRecovery.kt`
- `app/src/main/java/com/ireum/ytdl/work/TerminalExecutionRegistry.kt`
- `app/src/androidTest/java/com/ireum/ytdl/work/TerminalExecutionProductionWiringTest.kt`
- `app/src/test/java/com/ireum/ytdl/work/TerminalExecutionRecoveryTest.kt`

No unrelated production expansion was found in the reviewed range.

## Independent verdict

BUG-CANCEL-02 is `FIXED-CLOSED` at
`31f55aca76efb1b956567fca5325b2575779a835`.

Overall repository verdict remains `NOT_CLEAN`.

No new semantic root was found in this completion review.

Canonical open totals become:
- P0 = 0
- P1 = 0
- P2 = 18

## Source-semantic review

### Durable post-native ownership

The reviewed source now distinguishes native completion from post-native provider/file/cache effects.

After native completion:
- `TerminalExecutionRecovery.markNativeFinished()` persists `NATIVE_FINISHED`;
- `TerminalExecutionRegistry.beginPostNativeEffects()` acquires an exact process-held effect lease and persists `POST_NATIVE_EFFECTS`;
- publication can cross only through `markPublicationStarted()`, which requires the exact live lease and moves to `POST_NATIVE_PUBLISHING`.

Cancellation/failure during either post-native phase records the durable winning outcome without claiming effect quiescence. `convergeTerminal()` returns false for post-native phases and therefore does not authorize row deletion merely because native is already finished.

This closes the original BUG-CANCEL-02 authority gap where native completion was previously treated as sufficient execution convergence even while post-native publication ownership remained live.

### Effect quiescence and process-local retirement

`completePostNativeEffects()` requires the exact process-held effect lease and only then transitions the durable record from post-native ownership to `TERMINAL_STOPPED` or `TERMINAL_FAILURE`.

`TerminalExecutionRegistry.release()` refuses to retire the process-local token while:
- the durable phase is still `POST_NATIVE_EFFECTS` / `POST_NATIVE_PUBLISHING`; or
- an effect lease is still held without a durably settled effect phase.

When the durable phase is settled, release closes the exact effect lease and removes only the matching active token.

Cache/staging ownership therefore remains protected for the lifetime of the exact effect owner and is not inferred merely from row state or WorkManager state.

### Cancellation-safe worker finalizer

The final correction at `180598df...` wraps the full application-owned finalizer sequence in one outer `withContext(NonCancellable)`:

1. stopped-worker durable cleanup when `isStopped`;
2. Terminal dispatch-carrier resolution after row convergence;
3. exact registry/effect-owner release.

The earlier residual was that cancellation could interrupt between cleanup and release, leaving a durable STOPPED row state but a still-active process-local token/lease. The reviewed finalizer composition removes that cancellation gap.

### Row deletion ordering

For post-native cancellation:
- durable STOPPED outcome is requested first;
- staging/output cleanup is completed while ownership is still held;
- exact post-native effect quiescence is persisted;
- only then does the worker delete and confirm absence of the Terminal row;
- the outer finalizer subsequently resolves dispatch state and retires the exact process-local token/lease.

Thus row deletion no longer substitutes for effect-owner quiescence.

### Publication and committed precedence

Publication is fenced by `markPublicationStarted()` before `FileUtil.moveFile()`.

A cancellation that wins while still in a post-native phase prevents `markCommitting()` from succeeding because the durable record already carries the STOPPED outcome.

Once `markCommitting()` succeeds, later cancellation cannot rewrite the record to STOPPED. After the Terminal row is deleted, `markCommitted()` preserves the committed semantic result; a later cancellation therefore cannot reopen the result as failed/stopped.

The corrected instrumentation fixture now uses a file-producing plan and writable destination so it exercises the real COMMITTING boundary rather than failing earlier on a NO_FILES_EXPECTED authority mismatch.

### Process-death recovery

The effect lease is an OS file lock held by the live process. Startup reconciliation:
- cannot acquire that lock while the original effect owner is still alive, so it defers;
- can acquire it after process death, which positively proves the prior process-held effect owner is gone;
- only then settles an abandoned `POST_NATIVE_EFFECTS` / `POST_NATIVE_PUBLISHING` record;
- does not replay native execution or promote an unfinished publication into a committed result.

Legacy v1 records remain readable and are upgraded on mutation; v1 cannot encode the new post-native phases, while v2 does.

### Identity and destructive ownership

All new transitions remain bound to:
- exact Terminal subject id;
- exact execution token;
- exact process identity;
- exact native generation where native may have started;
- exact effect lease for post-native ownership.

The reviewed range does not broaden destructive authority to unrelated tokens, cache roots, or rows.

## Verification evidence

Implementation-agent final-SHA evidence reported for exact SHA
`31f55aca76efb1b956567fca5325b2575779a835`:

- focused connected `TerminalExecutionProductionWiringTest`: 6/6 PASS;
- broader Terminal connected partition: 27/27 PASS;
- `TerminalExecutionRecoveryTest` + `TerminalOutputAuthorityTest`: 17/17 PASS;
- `DownloadCacheOwnershipTest`: 19/19 PASS;
- `:app:compileDebugKotlin`: PASS;
- `:app:compileDebugAndroidTestKotlin`: PASS;
- `git diff --check`: PASS.

The earlier zero-test wrapper-download attempt is infrastructure evidence only and is not counted as semantic verification.

The stale persisted target `AppCacheManagerTest` was reviewer-authored gate drift; the authoritative source tree contains no such suite. `DownloadCacheOwnershipTest` is the semantically relevant existing cache-ownership suite and was executed successfully on the exact final SHA.

INDEPENDENT EXECUTION: NOT EXECUTED

Independent closure here is based on exact pushed source-semantic review plus preserved exact-final-SHA implementation execution evidence; implementation execution is not represented as independent execution.

## Finding status

`BUG-CANCEL-02: FIXED-CLOSED`

Closure basis:
- original premature row/token retirement root is structurally removed;
- same-root finalizer residual is removed;
- cancellation-before-effect, cancellation-during-effect, cache-ownership race, process-death recovery, and committed-result precedence are covered by source semantics and exact-final-SHA regression evidence;
- no same-root residual was found in the reviewed range.

## Next governed action

Per the already persisted sequencing requirement, do not select another correctness target yet.

Next:
1. implement the remediation tooling plan:
   `ytdlnisx/plans/2026-09-27_REMEDIATION_TOOLING_AFTER_CURRENT_WAVE.md`;
2. independently review the tooling implementation;
3. only then resume correctness remediation from the canonical review state.

