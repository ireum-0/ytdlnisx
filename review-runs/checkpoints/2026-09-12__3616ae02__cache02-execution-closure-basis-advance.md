# BUG-CACHE-02 execution closure and cumulative cache basis advance

Date: 2026-09-12

## Exact review state

- Prior contiguous CLEAN basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Final implementation SHA: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Final implementation parent: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Cumulative cache range reviewed: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c -> 3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Authoritative ledger remains reference-only at `899328bc91e4008e39a658387396a0106c8666ec`.

## Final verdict

**CLEAN for the cumulative cache remediation scope.**

- `BUG-CACHE-01`: remains **CLOSED**.
- `BUG-CACHE-02`: **CLOSED**.
- P2 blocker-count delta: `-1` for `BUG-CACHE-02`.
- Resulting canonical blocker count: **P0 2 / P1 1 / P2 30**.
- Overall project gate remains `NOT_CLEAN` because unrelated canonical blockers remain open.
- Contiguous independently CLEAN Review Basis advances to:
  `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.

## Exact-final-SHA execution evidence

Verification was performed without changing source, commits, or branch history. The remote implementation branch and isolated verification worktree both remained exactly at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.

Device evidence reported for `emulator-5554`, `Medium_Phone_API_36.1(AVD) - 16`, API 36, x86_64. After freeing emulator storage, installation succeeded.

Required CACHE-02 production regression:

- `TerminalExecutionProductionWiringTest.admittedTerminalKeepsItsCacheRootWhenPreferenceChangesBeforePlanning`
- isolated execution: **1/1 passed**;
- Gradle task: **succeeded**.

Cache-storage production wiring:

- `CacheStorageAuthorityProductionWiringTest`: **3/3 passed**;
- repeated verification: **3/3 passed**;
- Gradle task: **succeeded**.

Previously established at the same unchanged SHA and retained as supporting evidence:

- focused JVM cache tests: 16/16 passed;
- full JVM suite: 613/613 passed;
- KSP PASS;
- debug Kotlin compile PASS;
- Android-test Kotlin compile PASS;
- `git diff --check` PASS;
- Room migration not required.

The earlier zero-test instrumentation attempts caused by `INSTALL_FAILED_INSUFFICIENT_STORAGE` remain correctly classified as not executed, not as passes.

## CACHE-02 closure rationale

Independent exact-source review had already established source-semantic closure before this evidence run:

- Terminal resolves one canonical raw cache root before cache-scoped admission;
- the exact bound root is passed to `TerminalExecutionRegistry.admit(...)` and publication recovery;
- command planning and cached staging reuse that same bound root;
- Download resolves/binds a canonical root while execution ownership is published and carries it by exact execution ID through output planning, yt-dlp request construction, retry, info-json, hard-sub staging, artifact ownership, recovery and cleanup;
- Download producer recovery uses its persisted output root rather than re-resolving mutable cache preferences after restart.

The final required production test now directly proves the original residual sequence is closed:

`admit root R -> mutate cache_path so fresh resolution would choose D -> introduce unresolved carrier under D -> continue planning/staging -> actual staging remains under admitted R and does not switch to D`.

No blocker-level source residual survived the cumulative review.

## Reconciliation of the failing existing Terminal class test

A full `TerminalExecutionProductionWiringTest` class invocation executed 2 tests: the required CACHE-02 root-switch test passed, while the pre-existing `durableWitnessExistsBeforeNoCacheNativeBoundary` test failed because its worker finished `FAILED` after the hook assertion.

This is **not a 3616 cache regression and does not block CACHE-02 closure or basis advancement**.

Exact-source reconciliation:

1. The failing test method itself is unchanged between `9aebcb7e...` and `3616ae02...`; the 3616 test-file delta adds the new CACHE-02 test and resets the new hook only.
2. The relevant worker ordering is also unchanged between `9aebcb7e...` and `3616ae02...`:
   - `beforeYtdlpExecutionForTesting` is invoked first;
   - only afterward, in the injected-response branch, `TerminalExecutionRecovery.markNativeStarted(...)` transitions the witness to `NATIVE_STARTED`.
3. The old test asserts `NATIVE_STARTED` *inside* `beforeYtdlpExecutionForTesting`, so the assertion is inconsistent with the pre-existing test seam ordering. An assertion thrown there makes the WorkManager result `FAILED`, matching the observed failure.
4. The actual durable-before-native invariant is nevertheless present in production: `TerminalExecutionRegistry.admit(...)` persists `TerminalExecutionRecovery.begin(...)` before admission returns `ACQUIRED`; `begin(...)` records phase `ADMITTED`. The injected-response branch then marks `NATIVE_STARTED` before constructing the synthetic native response. The real native path separately binds the native generation at its actual generation-prepared boundary.

Therefore the observed class-level failure is classified as a **pre-existing instrumentation harness/seam mismatch**, not a blocker-level production regression and not a reason to keep the cache scope NOT_CLEAN. It may be repaired separately as test maintenance, but it does not add to P0/P1/P2 blocker count.

## Preserved closure / regression result

- `BUG-CACHE-01` remains CLOSED; the new verification does not contradict its source or execution evidence.
- Provider-only cache-root rejection/fallback remains preserved.
- Exact cache ownership, fail-closed unknown material, abandoned-owner cleanup, import/delete maintenance windows, and Terminal recovery carrier semantics remain preserved in the reviewed cumulative source.
- No automatic merge/cherry-pick/rebase/transplant or ledger/plan modification was performed.

INDEPENDENT EXECUTION: NOT EXECUTED
