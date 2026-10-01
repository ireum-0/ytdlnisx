# Android support floor decision — minSdk 26 authorized

checkpoint_kind: REVIEWER_PRODUCT_SUPPORT_SCOPE_CHANGE_AUTHORIZATION
review_parent_sha: f5f0c097b1b41b23f7211812b1cef761139fae92
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 43bcebf8d4f796ed6e6759ebe03b0e5925442444
reported_local_candidate_parent: 250b495941237f207537b244fca8c92f62de0e62
reported_local_candidate_tree: c187d28025799cb38869d251bed0795801d5936a
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## User-authorized product decision

The application support floor is intentionally raised from Android API 24 to API 26.

New product support contract:

- minSdk = 26
- Android 8.0+ remains supported.
- Android 7.0/7.1 (API 24/25) is no longer part of the supported product contract for future builds
  made from this change.
- The decision is a product-support choice, not a test waiver.

The change is deliberately conservative: it removes only the API24/25 lower platform band that was
blocking RESUME-01 closure while retaining Android 8.0 and later support.

## RESUME-01 consequence

The previously required RESUME-01 API24/25 production-path proof exists solely to prove behavior in
the lower supported platform band.

Once minSdk=26 is committed and independently verified on the exact candidate:

- API24/25 becomes outside the declared install/support range;
- the RESUME-01 API24/25 execution gate becomes NOT_APPLICABLE_TO_SUPPORTED_RANGE rather than
  NOT_EXECUTED;
- no API24/25 AVD/device provisioning is required for this remediation;
- API26+ Resume/Retry capability semantics and all existing identity/provenance checks remain
  required;
- the existing RESUME-01 production correction must remain intact. Raising minSdk must not be used
  to revert, weaken, or bypass that correction.

This support-scope decision does not by itself close RESUME-01. Closure still requires exact
candidate verification after the minSdk change.

## Narrow implementation scope

Authorize exactly one forward support-floor commit on the protected local candidate
43bcebf8d4f796ed6e6759ebe03b0e5925442444.

Behavior-relevant file allowed:
- app/build.gradle
  - change only defaultConfig minSdk 24 -> 26.

Documentation consistency file allowed:
- docs/codex/PROJECT_STATE.md
  - update the current-state minSdk statement from 24 to 26.
  - do not rewrite unrelated project-state content.

Do not edit:
- ResumeActivity.kt;
- NotificationUtil.kt;
- any production Kotlin/Java source;
- tests;
- manifests;
- dependency versions;
- targetSdk/compileSdk;
- ABI configuration;
- archived historical docs;
- review evidence;
- schema/migrations.

Archive documents that historically say minSdk 24 remain immutable historical evidence and must
not be "corrected".

No versionName/versionCode bump is authorized in this support-floor commit.

## Required implementation preflight

Before editing:
- exact protected local HEAD must be 43bcebf8d4f796ed6e6759ebe03b0e5925442444;
- exact tree must be c187d28025799cb38869d251bed0795801d5936a;
- parent must be 250b495941237f207537b244fca8c92f62de0e62;
- protected worktree must be behavior-relevantly clean;
- implementation remote must remain 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9;
- review/remediation must remain compatible forward movement from this checkpoint;
- all prior exact-candidate and Complete-Wave evidence must remain preserved.

## Focused verification for the support-floor commit

After the one commit:

1. require exactly the two authorized files changed and no production source/test file changed;
2. git diff --check PASS;
3. prove Gradle/merged manifest/build metadata resolves minSdkVersion 26;
4. prove targetSdk and compileSdk remain 36;
5. prove no non-archive current-state source still declares minSdk 24;
6. perform the established exact-candidate compile/build proof sufficient to catch dependency,
   manifest-merger, desugaring, or Android plugin incompatibility caused by the minSdk change;
7. use only the already-approved API36 target for any connected smoke/verification step; do not
   provision a lower API device.

If focused configuration/build verification fails, preserve evidence and STOP for reviewer
classification. Do not make a second correction.

## Exact-final-candidate verification requirement

Because minSdk is behavior-relevant build configuration, the prior 43bcebf8 execution evidence is
not final closure evidence for the new SHA.

After focused PASS, the new exact candidate must rerun the governing exact-final-SHA verification
needed by the remediation protocol, including:

- detached exact-SHA diff proof;
- complete canonical 19-gate union from partition 1;
- Complete-Wave Check.

The prior 242 PASS / Complete-Wave PASS remains historical evidence but must not be substituted for
the new SHA's closure-grade verification.

The API24/25 gate is excluded from the new candidate only after minSdk=26 is proven in the exact
candidate build contract.

## Publication / closure

No publication is authorized in the implementation task.

After the new exact candidate passes the governing gates, reviewer classification must determine
the resulting RESUME-01 disposition and the remaining remediation/publication state.

No root is declared FIXED-CLOSED by this checkpoint.
Repository-wide CLEAN remains unsupported.
