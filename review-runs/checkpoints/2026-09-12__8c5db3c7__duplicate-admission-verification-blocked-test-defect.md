# BUG-DUPLICATE-ADMISSION-01 exact verification — blocked by test defect

## Scope

- Implementation branch: `checkpoint/pre-baseline-review`
- Exact verified implementation HEAD: `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`
- Parent chain: `8c5db3c7 -> e7355f54 -> 1b3fd138 -> 3616ae02`
- Independently CLEAN review basis remains: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Root: existing P2 `BUG-DUPLICATE-ADMISSION-01`

## Verification report disposition

External verification was performed from an isolated clean worktree at exact `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`. The implementation branch remote SHA matched exactly before and after verification. No source changes, commit, or push were made.

Reported passing evidence at exact `8c5db3c7`:

- `DownloadDuplicateAdmissionProductionWiringTest`: 5/5 on Medium_Phone_API_36.1 AVD, API 36, x86_64;
- `ObserveSourceWorkerProductionWiringTest`: 8/8;
- focused claim/ownership regression: 1/1;
- `DownloadConfigurationDuplicatePolicyTest`: 13/13;
- full JVM suite: 613/613, zero skips/failures/errors;
- KSP: PASS;
- debug Kotlin compile: PASS;
- android-test Kotlin compile: PASS;
- `git diff --check`: PASS.

However, required `ObserveSourcePostInsertClaimProductionWiringTest` executed zero test body assertions because JUnit rejected class initialization with:

`Method tearDown() should be void`

Therefore exact verification is **NOT COMPLETE** and this evidence is insufficient for closure.

## Independent exact-source classification of the failure

The exact `8c5db3c7` test source contains:

```kotlin
@After
fun tearDown() = runBlocking {
    ...
    editor.commit()
}
```

`SharedPreferences.Editor.commit()` returns `Boolean`, so the expression-bodied Kotlin lifecycle method is compiled with a non-void return. This directly explains JUnit's `@After` validation failure before the test body executes.

The defect is in the candidate's verification test harness, not a newly established production semantic residual. `8c5db3c7` itself introduced this test together with the final Observe post-insert claim preservation fix.

The separately reported `aapt.exe` access-denied cleanup failure is not used as semantic evidence and does not change the classification above.

## Canonical disposition

- `BUG-DUPLICATE-ADMISSION-01`: remains **OPEN FOR REQUIRED EXACT-SHA EXECUTION ONLY**.
- Independent source verdict for the production implementation remains **SOURCE CLEAN**.
- New canonical blocker count: none. Count delta `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Do **not** advance basis to `8c5db3c7` and do **not** close the duplicate-admission root yet.

## Required next action

Apply a narrowly scoped test-only additive review-fix on `checkpoint/pre-baseline-review` starting from exact remote `8c5db3c7` so JUnit lifecycle methods have JVM `void`/Kotlin `Unit` signatures without changing production semantics or test assertions. Commit and push separately. Then rerun the required exact-final-SHA verification suite, including successful execution of `ObserveSourcePostInsertClaimProductionWiringTest` test body on the emulator/device.

The previous passing execution at `8c5db3c7` remains evidence about that SHA, but closure must be based on the new exact final SHA after the test-source fix.

INDEPENDENT EXECUTION: NOT EXECUTED