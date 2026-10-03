# BUG-UPDATER-04 focused failure triage accepted — test/precondition correction authorized

checkpoint_kind: BUG_UPDATER04_TEST_PRECONDITION_CORRECTION_AUTHORIZATION
review_parent_sha: 810c3051f8186d11bd208e8252a2eb2ca72380ef
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
count_change: 0
finding_dispositions_changed: NO
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e

## Read-only triage accepted

The implementation agent completed the authorized read-only triage of the first valid focused
YtdlpRuntimeAuthorityProductionWiringTest run.

Reported exact focused result:
- 7 executed;
- 0 pass;
- 7 fail;
- 0 skip;
- 0 error.

Reported triage conclusion:
NO BUG-UPDATER-04 PRODUCTION RESIDUAL IS CONCLUSIVELY ESTABLISHED BY THIS RUN.

Reported root mapping:
- ownedUpdaterFailureAndCancellationReleaseForLaterConsumersAndRetry:
  HARNESS_OR_PRECONDITION_DEFECT.
  The global release observation counted two distinct valid mutation owners: app startup initialization and
  the injected updater.
- startupIdleObservationCannotAuthorizeMutationPastLateRealDownload:
  ABI_OR_RUNTIME_ENVIRONMENT_DEFECT, with the finite latch/wait masking the earlier failed native admission.
- promotionExcludesRealDownloadLaunchUntilPromotedRuntimeIsUsable:
  ABI_OR_RUNTIME_ENVIRONMENT_DEFECT.
- overlappingUpdaterRequestsKeepCoalescingAndDesiredGenerationOrdering:
  ABI_OR_RUNTIME_ENVIRONMENT_DEFECT plus confirmed preceding-test DB/worker contamination.
- independentRealDownloadNativeGenerationsRemainIsolatedOnException:
  ABI_OR_RUNTIME_ENVIRONMENT_DEFECT.
- liveRealDownloadExcludesManualMutationUntilExactQuiescence:
  ABI_OR_RUNTIME_ENVIRONMENT_DEFECT.
- realCustomSelfUpdateOwnsMutationWithoutRecursiveConsumerAdmission:
  ABI_OR_RUNTIME_ENVIRONMENT_DEFECT.

Reported ABI cause:
- an arm64-only app/test artifact was exercised on sdk_gphone64_x86_64;
- the app-private aria2c dependency path exposed ARM64 libxml2/Python native content to the x86_64 test
  process;
- this packaging/extraction/environment behavior predates the current dirty BUG-UPDATER-04 correction;
- the focused authority tests do not require aria2c semantics for their intended assertions.

Reported harness/isolation defects:
- release counting observes unrelated app-startup mutation release;
- startup readiness is not established before the tested mutation observation;
- failed native admission can be masked by a later finite latch timeout;
- teardown closes Room/resets fixtures before all worker/recovery activity is proven complete;
- preceding-test worker/recovery activity was observed after db.close() in a later test.

Reported evidence remains protected under the current worktree, including JUnit XML, UTP log,
textproto, HTML report, per-test logcats, and the tested arm64 debug APK.

## Reviewer decision

A bounded same-root TEST/PRECONDITION correction wave is authorized under REVIEW_PROTOCOL 4.1/4.2.1.

### Production edits

PRODUCTION_SOURCE_EDIT_AUTHORIZED=NO

The focused failure evidence does not currently justify changing YtdlpRuntimeAuthority, UpdateUtil,
YoutubeDLCompat, YtdlpNativeProcessBarrier, YTDLPUtil, App, RuntimeDiagnostics, or other production source.

### Test/harness edits

Authorize only the minimum test-side corrections necessary to:

1. isolate release-count observation to the mutation operation under test, including establishing app/startup
   readiness before the observation baseline is taken;
2. preserve the original native-admission failure instead of allowing a later latch/finite-wait timeout to
   replace it;
3. prove real worker/recovery completion before resetting hooks/fixtures or closing the test Room database;
4. eliminate the confirmed cross-test worker/recovery contamination while preserving all intended production
   authority assertions;
5. keep existing negative assertions and the A-G shared-runtime-authority acceptance semantics intact.

Preferred edit boundary:
- app/src/androidTest/java/com/ireum/ytdl/util/YtdlpRuntimeAuthorityProductionWiringTest.kt

A second androidTest-only file may be touched only if exact source proves an existing shared test helper is
the smallest correct boundary. No main-source/test-seam production change is authorized.

Do not weaken assertions, delete coverage, lower timeouts merely to obtain green, or replace real production
coordination with fake helper-only semantics.

### ABI-compatible execution precondition

The first focused failure must remain preserved.

The next semantic focused execution must use an ABI-compatible Android target for the existing ARM64 artifact,
unless a later evidence-backed reviewer decision explicitly authorizes a different supported package/target
pairing.

Do not treat reinstalling the same ARM64 artifact onto the same x86_64 AVD as a material correction.

Device/target provisioning is an operator action, not part of the test-edit agent's source task.

### Verification boundary for this wave

Implementation agent may:
- apply the bounded test-only correction;
- run static diff/syntax checks;
- compile the complete debug Android-test Kotlin source if needed;
- report the exact test diff and operator rerun command/target precondition.

Implementation agent must NOT:
- run the focused Android test class;
- change production source;
- change Gradle dependencies/ABI packaging/config;
- clear app data or reinstall runtime packages;
- create/reconfigure/wipe/replace AVDs;
- commit or publish before the corrected focused class has a valid semantic result;
- begin another remediation root.

After the test-only correction report, the operator may execute the exact focused class on an
ABI-compatible target. PASS/FAIL must return to reviewer classification before commit/publication.

## Canonical state

BUG-UPDATER-04 remains OPEN P2.
Canonical P0/P1/P2 remain 0/0/4.
Runtime correctness remains NOT_VERIFIED.
No Known-Good Baseline is created.

INDEPENDENT_EXECUTION: NOT EXECUTED
