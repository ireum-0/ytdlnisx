# Observe compile-repair wave — valid semantic failure

Date: 2026-09-24 +09:00

Implementation branch:
checkpoint/pre-baseline-review

Remote implementation HEAD:
ee7eea001462b77e88a201ed2f26c2385048d421

Committed base candidate:
aaf89b15224b814470c2efab548f4c3e40003de7

Base parent:
ee7eea001462b77e88a201ed2f26c2385048d421

Prior review checkpoint:
53525607931da7cc1f9f19c028608d8305ee7e6a

## Verdict

COMPILE_REPAIR_VALID_SEMANTIC_FAILURE

No child commit was created.
No implementation push occurred.

Canonical defect delta: 0.

Canonical totals remain:
P0 = 1
P1 = 0
P2 = 23
Overall = NOT_CLEAN

CLEAN_REVIEW_BASIS remains:
ee7eea001462b77e88a201ed2f26c2385048d421

## Repair state

The compile-repair wave preserved committed candidate aaf89b15 unchanged and
made an uncommitted local repair in the F11 worktree.

Reported repair:
- WorkInfo has no inputData property;
- generation remains carried in worker input data;
- a generation-specific tag was added to ordinary, handoff-recovery, and
  confirmed-retry WorkRequest paths;
- deduplication now compares generation through WorkInfo.tags;
- androidTest compilation also required a missing assertNull import.

Reported pre-failure checks:
- git diff --check: PASS
- :app:compileDebugKotlin: PASS
- :app:compileDebugAndroidTestKotlin: PASS
- two directly relevant test methods: PASS 1/1 each

No child commit was created before the semantic failure.

## Valid semantic failure

The directly relevant method:

startupRecoveryRetiresStaleObserveRetryWithoutReplacingNewGenerationWork

executed and failed.

Failure location:

app/src/androidTest/java/com/ireum/ytdl/work/
F11HandoffCarrierMutationAdmissionProductionWiringTest.kt:231

Observed failing condition:

getWorkInfoById(staleCarrier.requestId) returned null

This is a valid semantic test execution failure.

It is not:
- a compile failure;
- an ADB/authorization problem;
- a zero-test UTP result-loss event;
- a host JVM/native-memory invalidity.

Per governing stop rules, no retry, no further tests, no source edits after the
failure, no child commit, and no push occurred.

Evidence reportedly preserved under:

app/build/observe-generation-evidence/compile-repair-20260924-verified/

## Required diagnosis before any additional edit

Preserve the current uncommitted repair exactly until diagnosis begins.

Do not reset/discard/reapply it.
Do not amend/rewrite aaf89b15.

Inspect the minimum local production/test context necessary to explain why the
stale carrier request ID no longer resolves to WorkInfo.

The next wave must establish, from exact code and WorkManager behavior:
1. what staleCarrier.requestId identifies;
2. when and how that WorkRequest is enqueued;
3. which path retires/cancels/removes it during startup recovery;
4. whether getWorkInfoById returning null is an intended consequence of current
   production behavior or evidence of an unintended work-identity lifecycle
   regression;
5. whether the test invariant is that stale work must remain queryable in a
   terminal state, or only that stale generation must not execute/replace
   current-generation work;
6. whether the new generation-tag dedup logic changed request publication,
   replacement, pruning, or identity retention in a way that violates the
   intended generation/fencing semantics.

Do not weaken or rewrite the test merely to accept null unless the exact
governing invariant proves that null is semantically acceptable.

Do not change production logic merely to preserve queryability if WorkManager
identity disappearance is expected and the test is over-constrained.

The fix must follow the governing invariant, not the current implementation or
test by default.

After the root cause is proven:
- make the smallest correct production/test change;
- rerun the exact failing method first;
- then rerun the two directly related methods that previously passed;
- rerun compile checks;
- if all pass, focused whole-diff review;
- create a NEW child commit whose parent is exactly aaf89b15;
- then resume exact-final-SHA verification from BackupSettings first.

Never claim CLEAN before independent review.

## Preservation

Continue preserving:
- current uncommitted compile-repair diff;
- committed aaf89b15 object unchanged;
- protected primary workspace/head;
- baseline;
- all three protected stashes;
- ignored/uncommitted local.properties;
- prior JVM crash/replay logs;
- Observe build/evidence artifacts;
- AVD diagnostic evidence.

INDEPENDENT EXECUTION: NOT EXECUTED
