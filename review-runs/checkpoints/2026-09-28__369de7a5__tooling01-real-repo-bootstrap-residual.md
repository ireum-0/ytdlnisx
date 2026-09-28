# BUG-TOOLING-01 — real-repository detached bootstrap residual

checkpoint_kind: REVIEW_FIX_REAUTHORIZATION
review_parent_sha: 17ac1f8115191dbb4e2c2030a28fefd07cca12cc
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 369de7a5219f4150c3c143ce20c03dd23dce2182
reported_local_candidate_tree: 115fe48d1c2067d64ab4cf58b5fe70b2dbece73e
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Independent verdict

BUG-TOOLING-01 is REOPENED P2 / SAME-ROOT RESIDUAL.

No new finding ID is created.

The prior closure correctly established detached exact-candidate execution,
candidate/tree provenance, canonical launcher use, source-worktree isolation,
and non-exposure of the protected source-worktree local.properties.

Fresh runtime evidence against the real 13-root local candidate now proves one
uncovered production-repository bootstrap cell: the detached exact-candidate
worktree cannot currently configure this Android project because it contains no
local.properties.

## Evidence chain

Fresh GitHub source at the authoritative remote implementation base shows:
- app/build.gradle applies
  com.google.android.libraries.mapsplatform.secrets-gradle-plugin version 2.0.1;
- the project does not configure a checked-in default properties file for that
  plugin;
- tools/remediation/Invoke-Verification.ps1 intentionally creates a detached
  exact-candidate worktree and does not inspect, copy, or serialize the protected
  source-worktree local.properties;
- tools/remediation/README.md states that SDK configuration for the detached
  execution tree must come from the normal host environment;
- Complete-Wave.ps1 independently requires the existing local.properties
  non-exposure provenance contract.

The implementation agent then reran the unchanged exact candidate with
ANDROID_HOME and ANDROID_SDK_ROOT set only for the verifier process to the
already-installed Android SDK, without reading/copying source local.properties.

The fresh run still failed Gradle configuration before executing the first JVM
test because the detached candidate had no local.properties.

This is consistent with the applied Secrets Gradle Plugin 2.0.1 contract: its
default secret-properties source is local.properties and, without an explicitly
configured default-properties file, a missing source file is a configuration
failure.

The previously accepted tooling matrix did test ignored local.properties
allowance/non-emission in a disposable fixture, but it did not prove that the
real repository could bootstrap Gradle from the detached exact-candidate
materialization while preserving source local.properties non-exposure.

## Root relation

This is not a new production root and not a new tooling root.

It is a residual of BUG-TOOLING-01 because the same promised exact-source
execution-lifetime mechanism is unable to execute the real repository under the
accepted source-worktree isolation contract.

Canonical counts after reconciliation:
- production P0=0 / P1=0 / P2=13;
- tooling P0=0 / P1=0 / P2=1.

BUG-TOOLING-02 remains FIXED-CLOSED.
All 13 production roots remain OPEN because their local implementation series is
still unpublished and exact-final-SHA union execution remains NOT VERIFIED.

## Narrow correction contract

Preserve the protected source-worktree local.properties completely:
- do not read it;
- do not copy it;
- do not serialize or log its contents;
- do not derive the detached file from it.

The narrow preferred correction is for normal exact-source verification to
materialize a fresh ignored EMPTY local.properties only inside the run-owned
detached exact-candidate worktree before Gradle execution.

The generated file is execution-environment scaffolding, not candidate source:
- it contains no source-worktree content, secret, SDK path, or project value;
- Android SDK discovery remains supplied through normal host environment;
- the committed candidate SHA/tree must remain exact;
- tracked/non-ignored cleanliness must remain exact;
- evidence must state separately that source local.properties was never
  inspected/copied/serialized and that an empty detached bootstrap file was
  generated;
- the retained detached worktree may contain this ignored generated file;
- Complete-Wave must validate the revised provenance contract rather than the
  old statement that no detached local.properties exists.

If an empty detached local.properties does not make the real project bootstrap
successfully, STOP and preserve the exact configuration failure. Do not read the
protected source file to discover missing keys and do not invent secret values.

## Required acceptance

The review-fix wave must add deterministic acceptance proving:
1. a source-worktree local.properties containing a unique sentinel is never
   read/copied/serialized/emitted;
2. normal exact-source materialization creates an ignored empty
   local.properties in the detached worktree only;
3. that detached file is empty and does not contain the sentinel;
4. candidate HEAD/tree and tracked/non-ignored clean-state provenance remain
   exact before/after gates;
5. Complete-Wave accepts only the revised explicit provenance contract;
6. the existing 15 tooling acceptance IDs remain passing, with one new
   separately attributable real-bootstrap/non-exposure acceptance cell;
7. an actual YTDLnisX JVM gate on the preserved local candidate reaches test
   execution from the detached exact-candidate worktree with SDK supplied only
   through the host environment.

Do not weaken canonical-launcher, exact-candidate, dirty-worktree,
destination-CAS, review-ref, or protected-state checks.

## Device blocker

The connected gate also remains separately BLOCKED_DEVICE_HEALTH because ADB
shell/boot probes passed while Android PackageManager service was unavailable.

That is not evidence for a source or tooling semantic change.

After the tooling residual is corrected and JVM/compile bootstrap works, perform
only bounded recovery of the already-authorized Android device/AVD as permitted
by the protocol. If PackageManager remains unavailable, connected gates remain
EXECUTION-NOT-VERIFIED and publication remains blocked when those gates are
required.

## Authorized next action

Preserve the existing 13 local root commits exactly and add one separately
attributable tooling-only review-fix commit on top of the reported local
candidate.

No production/application/test semantics belonging to the 13 roots may be
changed under this tooling correction except tooling acceptance tests under
tools/remediation.

After the tooling fix:
- run the full tooling acceptance matrix;
- prove actual-repository detached JVM bootstrap;
- rerun the exact-final-SHA union against the NEW exact committed candidate SHA
  that includes the tooling child commit;
- preserve the first valid semantic failure and stop rather than repairing
  production in the tooling wave;
- run Complete-Wave Check only after the union passes;
- fresh-check refs immediately before any publication;
- publish the full preserved 13-root series plus the separately attributable
  tooling review-fix child only through Section 1.1 exact expected-old CAS;
- no retry/reconciliation after lease failure.

INDEPENDENT EXECUTION: NOT EXECUTED
