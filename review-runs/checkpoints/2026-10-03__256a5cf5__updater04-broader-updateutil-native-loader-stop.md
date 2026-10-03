# BUG-UPDATER-04 broader gate stop — UpdateUtilProductionWiringTest ARM64 native loader failure

checkpoint_kind: BUG_UPDATER04_BROADER_GATE_UPDATEUTIL_NATIVE_LOADER_STOP
review_parent_sha: f0c5abc15cc0b381d0a8ee0881aa7b1fec00bd30
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Operator broader-gate result

The operator ran:
com.ireum.ytdl.util.UpdateUtilProductionWiringTest

Target:
ARM64 Samsung SM-A546E, Android 16

Exact reported execution:
- Starting 5 tests
- 4 failures
- Finished 5 tests
- BUILD FAILED

Failed tests:
1. overlappingSameGenerationRequestsShareOneNativeUpdate
2. startupReconcilesPersistedDesiredGenerationAfterCoordinatorRecreation
3. nativeFailureReleasesMutationOwnerForLaterRequest
4. sourceBSelectedDuringAUpdateRunsAfterAAndOwnsCommittedProvenance

All four reported the same native loader failure:

CANNOT LINK EXECUTABLE ".../lib/arm64/libpython.so":
library "libandroid-support.so" not found: needed by main executable

The class has five test bodies on the pinned implementation basis. The remaining
customUpdaterErrorOutputRemainsAnErrorResponse test is not listed among failures.

## Initial reviewer classification

CLASSIFICATION=BROADER_GATE_NATIVE_LOADER_FAILURE_NOT_YET_ROOT_MAPPED
SEMANTIC_BUG_UPDATER04_ASSERTION_FAILURE_PROVEN=NO
ABI_MISMATCH_REPRODUCED=NO
MISSING_NATIVE_DEPENDENCY=libandroid-support.so
FAILING_EXECUTABLE=libpython.so
RUNTIME_CORRECTNESS=NOT_VERIFIED

The prior focused BUG-UPDATER-04 ARM64 gate remains PASS 7/7 for the latest corrected candidate.

Do not rerun UpdateUtilProductionWiringTest unchanged.

Do not continue to later broader gates until this failure is classified.

## Required read-only triage

Inspect the exact dirty local candidate and preserved run artifacts to determine which of the following applies:

A. TEST_HARNESS_OR_PRECONDITION_DEFECT
- the production-wiring test or setup causes a real Python/native path to execute even though updaterForTesting
  should own the updater seam;
- the execution is not required by the production semantics being tested.

B. DIRTY_WAVE_SAME_ROOT_WIRING_DEFECT
- the BUG-UPDATER-04 correction introduces an unintended Python/native execution before or around the
  updaterForTesting seam;
- this is within the same shared-runtime-authority root and requires a narrow same-root correction.

C. PREEXISTING_PACKAGING_OR_PLATFORM_CONTRACT_DEFECT
- the installed ARM64 app/test package lacks libandroid-support.so even though libpython.so requires it;
- the same production native path would fail independently of the BUG-UPDATER-04 dirty changes.

D. INFRASTRUCTURE_OR_INSTALL_ARTIFACT_MISMATCH
- the built package contains the required library, but the installed package/runtime library path does not;
- packaging/install/split selection or stale deployment explains the missing dependency.

E. NOT_VERIFIED

Required evidence:
- exact dirty UpdateUtil.kt / YtdlpRuntimeAuthority.kt / YoutubeDLCompat.kt call chain for the four failures;
- confirm whether updaterForTesting is installed before the failing native call;
- exact stacktrace from JUnit XML/logcat for the first failure;
- exact APK(s) installed for this run;
- inspect APK native library inventory for arm64-v8a and determine whether libandroid-support.so is present;
- inspect dependency/native packaging inputs if available without mutation;
- inspect installed package nativeLibraryDir contents read-only;
- determine whether App.initLibraries or runtime-authority admission performs any Python execution;
- distinguish libpython dependency resolution from aria2c/library-path behavior;
- preserve the passing focused 7/7 evidence and this first broader-gate failure.

No source/test/config edit.
No device-test rerun.
No reinstall/app-data clear.
No commit/publication.

BUG-UPDATER-04 remains OPEN P2.
Canonical counts remain 0/0/4.

INDEPENDENT_EXECUTION: NOT EXECUTED
