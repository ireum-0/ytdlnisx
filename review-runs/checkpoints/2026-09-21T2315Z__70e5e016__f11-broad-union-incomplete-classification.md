# F11 fourth-wave broad-union incomplete execution classification

Date: 2026-09-21

Authoritative remote implementation HEAD:
`70e5e016155df8c14aa96984f08624957c11afe4`

Reported exact local candidate:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Remote remained unchanged; no push occurred.

Governing review:
- F11-R2 source residual: `a2ebe22efeeeabfe7d063016952f0dd7a4f97d11`
- provider-URI harness consolidation: `211d76f20d9afd8d5ae35ad5cd9239f25a5a6da4`
- BackupReset controlled discriminator: `c1d27a21965ad16cbc4d9d3921beb8d36a565e92`

## Completed exact-SHA verification evidence reported

On unchanged local candidate `55112cc6...`:

Controlled BackupReset discriminator:
- isolated `activeConflictingWorkerMustQuiesceBeforeResetApplies`: PASS 1/1;
- full `BackupResetTransactionProductionWiringTest`: PASS 26/26;
- original 1/26 failure remains preserved and is classified only as controlled order/state-dependent nondeterminism.

Other exact-SHA gates reported PASS:
- Automatic-keyword: 8/8;
- History undo: 10/10;
- LowQuality persistence: 125/125;
- Cleanup: 69/69.

Cleanup historical evidence remains:
- original full-class attempt FAIL at 37/69;
- controlled exact-method repeat PASS 1/1;
- controlled full-class repeat PASS 69/69.

Frozen baseline failure classification:
- DownloadWorker cleanup: 3 exact baseline-equivalent FAIL;
- producer recovery: 1 exact baseline-equivalent FAIL;
- Download output: 19 exact baseline-equivalent FAIL;
- automatic-keyword persistence: 2 exact baseline-equivalent FAIL;
- semantic fingerprint: 2 exact baseline-equivalent FAIL;
- no new method or changed signature reported;
- wrong-package producer invocation remains `FAIL BEFORE EXECUTION`.

These are implementation-agent execution claims, not independent execution.

## Blocking evidence

The broad 307-test union was:

`ATTEMPTED NOT COMPLETED`

Reported execution:
- instrumentation started and executed approximately 78/307;
- execution entered `AutomaticKeywordRuleSyncWorkerProductionWiringTest`;
- no terminal instrumentation result or valid completion summary was produced;
- no valid semantic failure was emitted;
- later closure/push gates were correctly not authorized.

## Classification

Current disposition:

**BROAD EXECUTION GATE INCOMPLETE — no semantic failure established.**

This does not reopen any production root and does not authorize source/test changes.

Canonical blocker-count delta: `0`.

The exact local candidate remains non-authoritative and F11-R2 remains pending independent exact-source review.

## Why an unchanged-tree continuation is allowed

Protocol §16 permits a rerun on an unchanged tree when infrastructure/tool/harness behavior prevented the requested verification stage from producing a valid result.

The governing fourth-wave prompt permits the established broad F11 union **or an exact current equivalent** covering the materially changed consumer surface.

Therefore a controlled broad-equivalent continuation is authorized, but not a blind retry loop.

## Authorized broad-equivalent continuation

On exact unchanged local SHA `55112cc6...`:

1. Reconstruct the exact intended test set of the failed 307-test union from the original invocation / test plan / discovery records.
2. Record a stable manifest of the intended classes/methods before running.
3. Partition that SAME intended set into small serial class batches chosen only to avoid long-lived instrumentation/harness instability.
4. Do not omit, replace, weaken, or add semantic exceptions merely because a batch is unstable.
5. Aggregate evidence is valid only if:
   - every intended test identity is accounted for exactly once in the aggregate;
   - duplicate execution is identified rather than double-counted;
   - every batch has nonzero intended execution and terminal status;
   - no unexplained failure exists;
   - exact frozen inherited failures, where applicable to the governing broad contract, remain matched by exact method/signature identity rather than family name;
   - no behavior-relevant tree change occurs between batches.
6. A valid semantic failure in any batch short-circuits immediately.
7. If a batch again hangs/does not complete:
   - preserve it as `ATTEMPTED NOT COMPLETED`;
   - do not endlessly repartition/retry;
   - isolate one known-good control from the same batch only if needed to classify infrastructure/harness health;
   - STOP if reliable aggregate completion still cannot be established.
8. Do not push until the governing broad-equivalent coverage is complete.

The original incomplete 307-test attempt remains preserved permanently and must not be rewritten as PASS.

## Push consequence

A successful aggregate broad-equivalent run on unchanged exact `55112cc6...` may satisfy the broad execution gate if the intended test-set equivalence is demonstrated and all other final gates remain valid.

No source/test edit or new commit is authorized by this classification.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
