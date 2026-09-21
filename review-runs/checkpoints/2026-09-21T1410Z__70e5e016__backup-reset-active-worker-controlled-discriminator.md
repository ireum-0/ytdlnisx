# BackupReset active-conflicting-worker failure classification during F11 fourth-wave verification

Date: 2026-09-21

Authoritative remote implementation HEAD:
`70e5e016155df8c14aa96984f08624957c11afe4`

Reported local-only candidate:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported parent:
`3b4ab56c6f902b6c5e5917428cb70e0a1466113a`

Remote implementation remained unchanged. The local candidate is not GitHub-authoritative and its fourth-wave implementation diff is not independently reviewed in this checkpoint.

Governing review:
- F11-R2 checkpoint: `a2ebe22efeeeabfe7d063016952f0dd7a4f97d11`
- provider-URI harness classification: `9e75b7902be5be9c1cbc32b27ab7c031809e16dc`
- provider-URI harness consolidation: `211d76f20d9afd8d5ae35ad5cd9239f25a5a6da4`

## Reported runtime failure

On exact local candidate `55112cc6...`:

`BackupResetTransactionProductionWiringTest`
- 26 discovered
- 26 executed
- 0 skipped
- 1 failed

Failing method:

`activeConflictingWorkerMustQuiesceBeforeResetApplies`

Failure point:
- final assertion that `reset.await()` is `RestoreOutcome.Completed`.

Later gates were correctly short-circuited.

No push occurred.

## Independent exact-source evidence

### Test source did not change across the prior authoritative F11 states

`BackupResetTransactionProductionWiringTest.kt` blob at both:
- `7efd3fe2579e421c545d1ed6287713dec02e9225`
- `70e5e016155df8c14aa96984f08624957c11afe4`

is exactly:

`cceca078f66e608c213bfd851dec9ff415756589`.

The failing method is therefore not a new test-contract change introduced after the prior F11 review.

### Prior execution evidence exists for the same class

The canonical F11 re-review chain records implementation-agent evidence of the final 26-method `BackupResetTransactionProductionWiringTest` passing 26/26.

The immediately preceding fourth-wave stop report also recorded the Reset transaction suite as 26/26 PASS on exact local production candidate `3b4ab56c...`.

The next local commit `55112cc6...` is reported as test-only provider-publication harness correction affecting:
- BackupPausedProductionWiringTest;
- BackupPreferenceProductionWiringTest;
- BackupSettingsProductionWiringTest;
- androidTest-only BackupPublicationTestSupport.

No production source and no `BackupResetTransactionProductionWiringTest` source was reported changed after the 26/26 PASS.

### The failing method intentionally crosses asynchronous WorkManager quiescence

The exact authoritative test:
1. enqueues a real cleanup worker with zero initial delay;
2. blocks it at `CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting`;
3. begins Restore concurrently;
4. waits for Restore ownership publication;
5. verifies old state still exists and ordinary Download admission is rejected;
6. releases the cleanup worker;
7. expects Restore to finish quiescence and return `Completed`.

Production Restore:
- cancels the cleanup tag via real WorkManager;
- waits for cancellation operation acceptance;
- polls real WorkInfo until no unfinished conflicting work remains;
- uses bounded query/poll timeouts;
- converts a quiescence exception before DATA_COMMITTED into `RestoreOutcome.RecoveryPending`.

Therefore this method intentionally depends on asynchronous WorkManager terminalization after a cancellation/release crossing. A single non-`Completed` outcome without the preserved exact `RecoveryPending.reason` or equivalent diagnostic is insufficient to establish a semantic regression.

## Classification

Current disposition:

**VALID EXECUTED FAILURE REQUIRING CONTROLLED NONDETERMINISM / STATE-DEPENDENCE DISCRIMINATION.**

It is not yet classified as:
- F11-R2 semantic regression;
- inherited baseline failure;
- harness defect;
- infrastructure failure;
- PASS.

Canonical blocker-count delta: `0`.

Do not modify production source or the test before the controlled discriminator.

## Authorized unchanged-tree discriminator

Because:
- the exact method previously passed in the same production candidate wave;
- the only reported intervening commit is unrelated test-only harness work;
- the method crosses real asynchronous WorkManager cancellation/terminalization;

protocol rerun policy allows a controlled unchanged-tree discriminator.

On exact unchanged local SHA `55112cc6...`:

1. run ONLY:
   `BackupResetTransactionProductionWiringTest.activeConflictingWorkerMustQuiesceBeforeResetApplies`
   once, preserving full outcome / Restore journal / lastError / WorkInfo diagnostics;
2. if the method FAILs again:
   - STOP;
   - do not rerun again merely to seek green;
   - report exact `RestoreOutcome` subtype and reason, current Restore phase/journal lastError, and unfinished cleanup WorkInfo identity/state/tags if available;
3. if the method PASSes:
   - run the full `BackupResetTransactionProductionWiringTest` class once on the same unchanged SHA;
4. if the full class FAILs:
   - STOP and preserve exact failing method/order/state diagnostics;
5. if the full class PASSes 26/26:
   - classify the original failure as controlled order/state-dependent nondeterminism for this execution wave;
   - preserve the original failure permanently;
   - continue later gates on the same exact SHA;
   - do not relabel the original failure as PASS.

No source/test edit is authorized by this checkpoint.

## Push consequence

No push is authorized until:
- the controlled discriminator is complete;
- all mandatory later F11 gates are valid on the exact final SHA;
- no new semantic blocker appears.

F11-R2 remains pending independent exact-source review after a successful push.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
