# BUG-CANCEL-02 final-SHA verification gate correction

checkpoint_kind: VERIFICATION_GATE_CORRECTION
review_parent_sha: `8d11e5855c991093ef68dbb6a909f452bf9c1460`

authoritative_remote_implementation_sha:
`359ddbf9bf534009be095ad1bffea8ec45c899e4`

reported_local_final_candidate:
`31f55aca76efb1b956567fca5325b2575779a835`

reported_local_chain:
`359ddbf9... -> b8117240... -> ae27b933... -> 180598df... -> ecbc8ac7... -> d4b4ae00... -> 31f55aca...`

## Disposition

BUG-CANCEL-02 remains OPEN pending exact-final-SHA verification and push.

The implementation agent correctly did not push because one persisted named JVM suite did not execute.

However, the missing suite is a reviewer-authored stale verification target, not an implementation regression:

`com.ireum.ytdl.util.storage.AppCacheManagerTest`

does not exist in the authoritative remote implementation tree at
`359ddbf9bf534009be095ad1bffea8ec45c899e4`.

GitHub inspection of
`app/src/test/java/com/ireum/ytdl/util/storage/`
at the authoritative base confirms no `AppCacheManagerTest.kt` exists.

The semantically relevant existing cache-lifetime suite for BUG-CANCEL-02 is:

`com.ireum.ytdl.util.storage.DownloadCacheOwnershipTest`

It contains the production-contract regression
`liveNewExecutionProtectsStaleMarkerUntilOwnerExits`, which verifies that a live execution protects stale cache ownership until the exact owner exits, plus exact marker/manifest/recovery ownership tests.

This is the cache/staging lifetime contract the original prompt intended to preserve.

No source correction is authorized or required solely because the stale suite name was absent.

## Reported candidate verification accepted as evidence

Reported against exact local candidate
`31f55aca76efb1b956567fca5325b2575779a835`:

- focused connected `TerminalExecutionProductionWiringTest`: 6/6 PASS;
- broader Terminal connected partition: 27/27 PASS;
- JVM task executed `TerminalExecutionRecoveryTest` and `TerminalOutputAuthorityTest`: 17/17 PASS;
- `:app:compileDebugKotlin`: PASS;
- `:app:compileDebugAndroidTestKotlin`: PASS;
- `git diff --check`: PASS.

The first zero-test Gradle attempt caused by blocked Gradle 8.13 download is infrastructure evidence only. The later elevated wrapper recovery is compatible with the adopted infrastructure-continuation rule because the environment materially changed and semantic tests subsequently executed.

The candidate remains local-only, so this checkpoint does not independently source-review or close BUG-CANCEL-02.

## Required verification correction

Run one verification-only continuation against the exact unchanged local final SHA:

`31f55aca76efb1b956567fca5325b2575779a835`

Focused JVM partition must be:

- `com.ireum.ytdl.work.TerminalExecutionRecoveryTest`
- `com.ireum.ytdl.util.storage.DownloadCacheOwnershipTest`
- `com.ireum.ytdl.work.TerminalOutputAuthorityTest`

The prior two-suite JVM PASS may be preserved as evidence, but the corrected three-suite partition should be executed as one explicit exact-final-SHA gate unless tooling constraints make class-level execution separately necessary. Do not substitute another cache suite.

No production or test source edits are authorized in this verification-only continuation.

If the corrected JVM gate passes:
- re-confirm compile/diff evidence still binds the same exact final SHA, rerunning only if needed by protocol/tooling evidence rules;
- fresh-check implementation and review refs;
- require normal fast-forward ancestry from remote `359ddbf9...`;
- push the exact tested final SHA only;
- post-push verify remote == tested SHA and ahead/behind 0/0.

If the corrected JVM gate produces a valid semantic failure, stop under the existing BUG-CANCEL-02 continuation protocol after preserving the failure.

If the final candidate tree changes for any reason, all exact-final-SHA gates must be rebound as required by protocol.

## Next sequence

1. verification-only corrected JVM gate on unchanged `31f55aca...`;
2. fresh refs and normal fast-forward push if all final-SHA evidence is valid;
3. independent exact-source completion review after push;
4. then execute the already queued remediation-tooling wave;
5. independently review tooling;
6. resume correctness remediation.

INDEPENDENT EXECUTION: NOT EXECUTED
