# BUG-UPDATER-02 runtime failure classification — stale regression contract

checkpoint_kind: INDEPENDENT_RUNTIME_FAILURE_CLASSIFICATION
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: bac6b74e888308de9974f942717033566fda07d3
implementation_sha: 383e06782c919ad5f436e2fd0d38814375ba0db9
implementation_parent_sha: 016633808d4312c7ac33047048c23a17aebbd94f
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
overall_verdict: SOURCE_FIXED_TEST_CONTRACT_STALE_EXECUTION_NOT_VERIFIED
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05
clean_review_basis_advance: NO
independent_execution: NOT_EXECUTED

## Runtime report

Exact implementation candidate remained:
383e06782c919ad5f436e2fd0d38814375ba0db9

Reported environment recovery and execution:
- authorized storage recovery freed 86.4 MiB;
- single install retry succeeded;
- emulator-5560;
- API 36;
- x86_64;
- complete UpdateUtilProductionWiringTest executed 36 tests;
- 35 PASS;
- 1 FAIL;
- 0 skipped;
- no test rerun;
- no source edits;
- no new commits or pushes;
- worktree clean;
- index empty;
- release/protected state preserved;
- no SDK download.

First semantic failure:
startupReconcilesPersistedDesiredGenerationAfterCoordinatorRecreation
reported line: 253
expected committed generation: 8
observed committed generation: 1

Sealed local evidence report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater02-closure-20261005-383e067-a8165c21/BUG_UPDATER_02_383E067_RUNTIME_STOP_REPORT.md

557 new evidence records were reported sealed and verified, alongside 2,387 preserved prior records. The local sealed report was not independently opened from GitHub.

## Independent source/test classification

The failing test is a pre-existing regression contract from parent 016633808d4312c7ac33047048c23a17aebbd94f. Its relevant fixture was not updated by the 383e067 production correction.

The test setup clears the private provenance preferences before each case. The failing fixture then directly persists:
- ytdlp_source = nightly
- ytdlp_source_generation = 8
- committed generation = 7
- committed source = stable
- committed result = DONE:stable@old

It does NOT publish the new private desired_generation_domain discriminator.

Under the independently accepted 383e067 production contract, this is intentionally a pre-domain / origin-ambiguous persisted state. The authoritative behavior is:
- desiredSourceGeneration() must not typed-read or trust the legacy numeric carrier;
- because source/generation intent exists and desired_generation_domain is absent, the synthetic pre-migration desired generation is 1;
- migrateDestinationProvenance()/retirePreChangeProvenance() durably rebases generation to 1;
- old committed/pending proof is retired;
- the new private generation-domain discriminator is published only after that rebase/retirement is durable;
- startup then re-proves the nightly runtime at generation 1.

Therefore observed generation 1 is the expected current production behavior. Preserving 8 in this fixture would contradict the accepted discriminator contract and would reintroduce the BUG-UPDATER-02 origin ambiguity.

The failure is classified:
STALE_REGRESSION_CONTRACT_SAME_ROOT

It is NOT:
- a new root;
- a BUG-UPDATER-02 source residual;
- evidence that generation-domain migration should preserve arbitrary pre-domain positive Long values.

## Required test-contract correction

The narrow correction is test-only.

At minimum, update startupReconcilesPersistedDesiredGenerationAfterCoordinatorRecreation so its assertions prove the current contract rather than the superseded numeric-preservation contract:
- the updater runs once for nightly;
- legacy generation 8 is rebased to destination-local generation 1 because the new discriminator was absent;
- committed source is nightly;
- committed generation is 1;
- committed result is DONE:nightly@recovered;
- the new private desired_generation_domain is current after migration;
- source intent remains nightly;
- no production source behavior is changed.

Do not merely delete or weaken the generation assertion. Replace it with an assertion on the new authoritative semantics.

Before editing, inspect the same class for any other old assertion that assumes an arbitrary pre-domain positive Long must be preserved. Correct only exact same-contract stale expectations demonstrated by source evidence. Do not broaden into unrelated harness cleanup.

Current-domain preservation remains separately required and already has dedicated source/test coverage; do not weaken it. A fixture that intends destination-current generation authority must explicitly seed the private desired_generation_domain discriminator.

## Execution disposition

The runtime attempt produced a valid semantic test result, so the same unchanged failing test must not be rerun merely to seek green.

After an authorized test-only correction materially changes the tested tree:
1. commit/push the narrow regression-contract correction by normal forward history;
2. verify the new exact remote SHA;
3. rebuild/reinstall artifacts for that exact new SHA;
4. execute the complete UpdateUtilProductionWiringTest class;
5. require nonzero execution, 0 failures, 0 skipped.

Previously completed JVM/source compilation evidence from 383e067 may remain historical evidence, but final closure after a test-source commit must target the new exact committed/pushed SHA. Run the build/compile obligations required to prove that new exact test tree.

BUG-UPDATER-02 remains OPEN P2:
SOURCE_FIXED / TEST_CONTRACT_CORRECTION_REQUIRED / EXECUTION_NOT_VERIFIED

BUG-UPDATER-03 remains OPEN P2.
BUG-HISTORY-05 remains OPEN P2.
Canonical P2 remains 3.

## Next governed action

Author/preflight/persist a narrow test-only stale regression-contract correction prompt for exact 383e067, then require same-wave publication and exact-final-SHA runtime closure.

INDEPENDENT EXECUTION: NOT EXECUTED
