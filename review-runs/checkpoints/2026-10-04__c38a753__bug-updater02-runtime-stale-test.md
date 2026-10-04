# BUG-UPDATER-02 — c38a753 runtime gate failure classification

checkpoint_kind: INDEPENDENT_RUNTIME_FAILURE_CLASSIFICATION
checkpoint_status: FINAL
manual_review_run: NO

review_parent_sha: 1c80701bc23d5edb5e142846e0bb869255c7ec98
implementation_sha: c38a753f90060dd45030dc8162cdf1c5e4782dfe

runtime_device: SM-A546E
runtime_api: 36
runtime_abi: arm64-v8a
runtime_target_package: com.ireum.ytdl.debug
runtime_class: com.ireum.ytdl.util.UpdateUtilProductionWiringTest
runtime_result: 27_EXECUTED_26_PASS_1_FAIL_0_SKIPPED
failed_test: startupOwnerSurvivesStaleActiveRecoveryAndQueuedSnapshotInProcess

classification: STALE_TEST_CONTRACT
production_semantic_defect: NOT_ESTABLISHED
new_root: NO
same_root_production_residual: NO
canonical_count_change: 0

## Independent classification

The failing final assertion expects exactly one row across statuses Active and Queued after startup recovery.

That assertion is incompatible with the production recovery contract:
- DownloadRepository.requeueRunningDownloadInternal() converts an abandoned Active/PostProcessing row to Queued when exact execution ownership is still the same and no stronger terminal authority exists.
- DownloadDao.requeueActiveDownload() explicitly writes status='Queued', downloadStartTime=0 and executionId=''.
- The test also seeds one independent row that is already Queued and explicitly requires that row to remain Queued.
- Therefore successful stale-Active recovery leaves two Queued rows, so getDownloadsCountByStatus(listOf("Active", "Queued")) == 2 is the expected aggregate state.

The test's earlier updater callback already proves the relevant admission contract:
- the stale row is no longer Active before updater mutation;
- the independent queued row remains Queued;
- the updater runs exactly once.

The aggregate expected value 1 does not observe the semantic object promised by recovery. It accidentally assumes stale recovery removes one runnable row instead of requeueing it.

## Attribution

The failing test was introduced at 58e631394b3f5a868307fba8e9f8382436023949 with the same final expected count of 1.

At that same SHA, and already at its predecessor 05c1fc2ed53531da6935f93470df93028bd799f3, DownloadRepository.requeueRunningDownloadInternal() used the same Queued reclassification contract.

Therefore this is a pre-existing stale regression assertion, not a behavioral regression introduced by c38a753.

c38a753 did not modify this stale-recovery assertion block.

## Required narrow correction

Test-only correction in UpdateUtilProductionWiringTest:
- do not change production recovery semantics;
- replace the aggregate count assertion with direct semantic assertions:
  - stale active row converged to Queued;
  - its executionId was cleared;
  - the independent queued row remains Queued;
  - no Active/PostProcessing row remains for the stale execution;
  - updater call count remains exactly 1.
- preserve the pre-recovery assertion that both seeded runnable rows are initially visible.
- do not weaken the prerequisite/recovery ordering.

After the test-only correction:
- compile the touched AndroidTest source;
- create one logical test-only commit and normal fast-forward push;
- on the exact pushed SHA, rerun the complete UpdateUtilProductionWiringTest class on the connected ARM64 device;
- acceptance remains nonzero execution, 0 failures, 0 skipped;
- do not stop normally at publication; continue directly into the exact-SHA runtime gate.

BUG-UPDATER-02 remains OPEN pending successful exact-SHA runtime execution.

INDEPENDENT EXECUTION: NOT EXECUTED
