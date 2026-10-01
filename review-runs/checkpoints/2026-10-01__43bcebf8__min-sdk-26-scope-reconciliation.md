# minSdk26 scope reconciliation — PROJECT_STATE target absent

checkpoint_kind: REVIEWER_IMPLEMENTATION_SCOPE_RECONCILIATION
review_parent_sha: fe6548e1a852dbaaceaf1140607c6e3bc9388c45
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 43bcebf8d4f796ed6e6759ebe03b0e5925442444
reported_local_candidate_parent: 250b495941237f207537b244fca8c92f62de0e62
reported_local_candidate_tree: c187d28025799cb38869d251bed0795801d5936a
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Stop classification

The implementation agent stopped before editing because the persisted prompt required exactly two
changed files, including replacing an existing minSdk statement in docs/codex/PROJECT_STATE.md.

GitHub source confirms that the authoritative PROJECT_STATE.md at the implementation basis contains
no minSdk statement to replace.

Classification:

REVIEWER_SCOPE_TARGET_ABSENT_DOCUMENTATION_EDIT_NOT_REQUIRED

This is a reviewer-authored scope mismatch. It is not:
- an implementation defect;
- a production defect;
- a test failure;
- a new canonical root.

The protected candidate remains unchanged at 43bcebf8 and minSdk remains 24.

## Reconciled implementation scope

The product decision remains unchanged:

- raise minSdk from 24 to 26;
- Android 8.0+ remains supported;
- API24/25 leaves the supported install/runtime contract.

The implementation change is now exactly ONE behavior-relevant file:

- app/build.gradle
  - defaultConfig minSdk 24 -> 26.

Do not add a new minSdk statement to docs/codex/PROJECT_STATE.md merely to satisfy the prior prompt.
Its omission is not inconsistent with the new support floor because that file currently does not
claim any SDK floor.

Do not edit any documentation in this correction.

All prior forbidden areas remain forbidden:
- production Kotlin/Java;
- tests;
- manifests;
- dependencies/plugins;
- targetSdk/compileSdk;
- ABI configuration;
- versionName/versionCode;
- schemas/migrations;
- archive docs;
- review evidence.

## Verification consequence

After the one-file commit:

1. changed paths must be exactly app/build.gradle;
2. git diff --check must PASS;
3. exact committed Gradle/build metadata must prove:
   - minSdkVersion 26;
   - targetSdkVersion 36;
   - compileSdk 36;
4. focused compile/build proof must PASS;
5. because minSdk is behavior-relevant configuration, the new exact candidate must still rerun:
   - detached exact-SHA diff;
   - complete canonical 19-gate union from partition 1;
   - Complete-Wave Check;
6. API24/25 proof becomes NOT_APPLICABLE_MINSDK_26 only after the exact committed build proves
   minSdk 26;
7. do not create or use an API24/25 device;
8. no publication is authorized.

The prior 43bcebf8 exact-candidate PASS evidence remains historical evidence only for the new SHA.

No root is FIXED-CLOSED by this scope reconciliation.
Repository-wide CLEAN remains unsupported.
