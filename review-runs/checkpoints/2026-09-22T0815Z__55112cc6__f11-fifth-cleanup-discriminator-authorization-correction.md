# F11 fifth-wave Cleanup bootstrap failure — discriminator authorization correction

Date: 2026-09-22

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local fifth-wave candidate:
`6df11bccefc0e3e75ef8a3a7c4d6ef3dba6c3ac3`

Prior review checkpoint being corrected:
`4aac17ede07e0487a54d4410b2a399358e124b2a`

Governing protocol:
`ireum-0/private:ytdlnisx-review/ytdlnisx/REVIEW_PROTOCOL.md`
blob:
`5fd51a55acd549af1e737ef72c64a8117523e0dd`

## Correction

The prior checkpoint correctly classified the reported Cleanup failure as not yet establishing a production Cleanup regression.

However, its authorization to rerun the exact same semantic method once on an unchanged tree conflicts with current REVIEW_PROTOCOL §16.2.

§16.2 permits rerunning the same failed semantic test only when:
- external infrastructure/tool failure prevented a valid result; or
- an authorized correction changes the tested tree.

The reported Cleanup run was a valid execution:
- 69 discovered;
- 69 executed;
- 1 failed;
- the failure had a concrete exception/frame.

Therefore the unchanged-tree rerun authorization is superseded and MUST NOT be used.

## Exact failure remains preserved

Reported failure:
- class: `CleanupScheduleCoordinatorProductionWiringTest`;
- method: `bootstrapReplayIsFencedByDisableAndSupersession`;
- exception: `IllegalArgumentException: Required value was null`;
- first frame: `seedInitializedScheduleWithMissingGeneration(...:3783)`.

This failure remains valid historical evidence.

It occurred while constructing the test fixture precondition, before the method installed its intentional
`authorityCommitOverrideForTesting = { false }`
semantic failure seam.

Production Cleanup semantic regression remains NOT ESTABLISHED.

## Exact harness lifetime defect

At authoritative remote `55112cc6...`:

`seedInitializedScheduleWithMissingGeneration()` is intended to establish:
- initialized dedicated critical store;
- DAILY cadence;
- no generation/debt;
- no leftover scheduling actor/work.

The helper uses production reconciliation to create/accept an initial generation and then tears that authority down.

The same test method invokes the helper twice.

Between the two invocations, the first half can create a bootstrap replay owner.

The relevant production test seam:

`resetReplayOwnerForTesting()`

calls:

`stopReplayOwnerLocked()`

which performs:

- `replayJob?.cancel()`;
- sets the stored replayJob reference to null;
- clears replay debt/bootstrap/cadence bookkeeping.

It does NOT await/join termination of the cancelled replay coroutine.

Therefore a cancelled replay actor may still be unwinding while the next fixture begins constructing a new missing-generation state.

That violates the protocol's bounded async-quiescence requirement for fixture setup.

The current helper also depends on production migration/bootstrap orchestration to create a state that the test can construct directly in the dedicated critical test store.

This makes fixture preparation unnecessarily sensitive to process-local replay lifetime.

## Authorized correction

Authorize ONE narrow harness correction.

Allowed production-file change:
- add only an internal/default-inert test seam that can cancel and then await/join the current cleanup replay job without holding the coordinator lock while joining.

The seam must:
1. capture/cancel the current replay job under the existing lock;
2. clear the same replay bookkeeping as the current reset seam;
3. release the lock;
4. boundedly await/join the captured job;
5. have no production caller and no production behavior when unused.

A clean shape is a suspend test-only helper such as:
`awaitReplayOwnerStoppedForTesting()`
or equivalent.

Do NOT change production replay semantics, retry/backoff, durability, WorkManager scheduling, cleanup authority, or locking.

Allowed test-file change:
- update only `CleanupScheduleCoordinatorProductionWiringTest` fixture setup as needed;
- before constructing the missing-generation fixture, use the new quiescence seam;
- construct the intended durable fixture deterministically from the dedicated critical test store rather than relying on an unrelated migration/bootstrap actor when possible;
- preserve the exact intended state:
  - critical store version initialized;
  - cadence DAILY;
  - generation absent;
  - pending/active generation absent;
  - effect journal absent;
  - no unfinished cleanup WorkInfo;
  - no live replay actor;
- preserve the original semantic assertions in `bootstrapReplayIsFencedByDisableAndSupersession`.

Do NOT weaken or remove the semantic test.

Do NOT add arbitrary sleeps.

Do NOT increase timeouts as the primary fix.

Do NOT modify fifth-wave scheduler-settings production semantics.

## Commit policy

The existing local two-commit fifth-wave candidate must remain unchanged.

Add exactly one forward harness-correction commit on top.

That commit may touch only:
- `app/src/main/java/com/ireum/ytdl/work/CleanupScheduleCoordinator.kt` for the inert test-only quiescence seam;
- `app/src/androidTest/java/com/ireum/ytdl/work/CleanupScheduleCoordinatorProductionWiringTest.kt` for fixture correction.

Required attribution:

`Defect-ID: BUG-CLEANUP-01`
`Reviewed-Checkpoint: <this checkpoint SHA>`
`Review-Finding: F11-FIFTH-CLEANUP-HARNESS`
`Canonical-Defect-Delta: 0`

This is a harness correction only.

It does not reopen F10 or create a new canonical defect.

## Verification after correction

Because the tree changes, §16.2 permits rerun.

On the new exact committed SHA:

1. `git diff --check`;
2. compile AndroidTest only if needed;
3. run exactly:
   `CleanupScheduleCoordinatorProductionWiringTest.bootstrapReplayIsFencedByDisableAndSupersession`;
4. if PASS, run full `CleanupScheduleCoordinatorProductionWiringTest` once;
5. if full class PASS 69/69, continue the fifth-wave gates that were previously short-circuited:
   - frozen-27 exact identity reconciliation;
   - established broad F11 union or exact identity-equivalent partition;
   - remaining exact-final-SHA/pre-push checks.

If the corrected focused method still FAILs:
- STOP;
- preserve exact fixture/replay/durable/WorkInfo diagnostics;
- no further rerun;
- no production correction authorized.

If another full-class test FAILs:
- STOP on first valid failure;
- no automatic rerun.

## Push consequence

Only the exact tested three-commit chain may be normally pushed if every mandatory gate completes.

No amend/rebase/squash/force-push/history rewrite.

Independent exact-source re-review remains required after push.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
