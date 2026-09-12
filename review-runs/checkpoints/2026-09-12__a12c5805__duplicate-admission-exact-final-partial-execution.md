# BUG-DUPLICATE-ADMISSION-01 exact-final review after test-harness fix

## Scope

- Previous independently CLEAN basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Prior production candidate: `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`
- Test-only review-fix: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`
- Exact implementation branch remote HEAD independently verified: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`
- Parent independently verified: `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`
- Root: `BUG-DUPLICATE-ADMISSION-01`

## Exact-source review

`8c5db3c7... -> a12c5805...` is one commit and changes only:

`app/src/androidTest/java/com/ireum/ytdl/work/ObserveSourcePostInsertClaimProductionWiringTest.kt`

The diff adds explicit terminal `Unit` expressions to the expression-bodied JUnit `@Before setUp()` and `@After tearDown()` `runBlocking` bodies. There is no production-source change and no assertion/interleaving/setup/cleanup semantic change.

The exact final test source therefore presents JVM-void/Kotlin-Unit lifecycle methods while preserving the deterministic production-wiring regression that inserts through final Observe admission, claims the committed Download row as a worker with exact `executionId`, resumes Observe continuation, and asserts that the live worker claim survives.

The prior independent full-range source review of `3616ae02... -> 8c5db3c7...` remains applicable because `a12c5805...` changes no production source. No source-semantic residual is introduced by the test-only child commit.

## External exact-SHA execution evidence reported for `a12c5805...`

Reported PASS at exact `a12c58055fff51b104f8b56fd53b534b8d7e5df4`:

- required `ObserveSourcePostInsertClaimProductionWiringTest`: `1/1`, test body executed and passed;
- `DownloadDuplicateAdmissionProductionWiringTest`: `5/5`;
- `ObserveSourceWorkerProductionWiringTest`: `8/8`;
- Android-test Kotlin compilation: PASS;
- Android-test APK assembly: PASS;
- `git diff --check`: PASS;
- instrumentation device: Medium_Phone_API_36.1 AVD, API 36, x86_64.

The previously observed `aapt.exe` cleanup access-denied condition did not recur during the final direct instrumentation execution.

## Remaining exact-final-SHA execution gap

The governing handoff requires the previously required focused/full build/test evidence to be tied to the new exact final SHA before closure. The following evidence was reported only for parent `8c5db3c7...`, not re-executed at `a12c5805...`:

- focused claim/ownership regression: previously `1/1`;
- `DownloadConfigurationDuplicatePolicyTest`: previously `13/13`;
- full JVM suite: previously `613/613`;
- KSP: previously PASS;
- debug Kotlin compile: previously PASS.

Because the child commit is test-only, this is an execution-governance gap rather than a newly observed production semantic defect. However, under the recorded exact-final-SHA closure rule, parent execution evidence is not silently relabeled as execution at the child SHA.

## Disposition

- Production/source verdict for `BUG-DUPLICATE-ADMISSION-01`: **SOURCE CLEAN**.
- Test-harness defect from `8c5db3c7...`: **FIXED** at `a12c5805...`.
- Required post-insert deterministic instrumentation: **PASS** at exact `a12c5805...`.
- Canonical blocker disposition: **OPEN pending remaining exact-final-SHA execution evidence**.
- Blocker count delta: `0`.
- Canonical count remains: **P0 2 / P1 1 / P2 30**.
- CLEAN basis remains: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.

No implementation change is requested. The next action is verification-only at exact `a12c5805...`. If the remaining required execution evidence passes with the implementation branch still exact `a12c5805...`, the root may close, P2 may move `30 -> 29`, and the contiguous independently CLEAN basis may advance `3616ae02... -> a12c5805...` after final ref recount.

INDEPENDENT EXECUTION: NOT EXECUTED