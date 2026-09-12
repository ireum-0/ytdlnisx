# BUG-DUPLICATE-ADMISSION-01 — exact-final execution closure and CLEAN-basis advance

- Final implementation SHA: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`
- Previous contiguous independently CLEAN basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Parent of final test-only review fix: `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Authoritative ledger: reference-only `899328bc91e4008e39a658387396a0106c8666ec`

## Final source review

The full cumulative implementation range `3616ae02... -> a12c5805...` has been independently reviewed for the original duplicate-admission scope.

The production fix remains source-clean:

- final duplicate admission performs the current duplicate-authority recheck and insertion inside the Room transaction;
- Observe marks rows freshly created by final admission and does not route those rows through the later generic full-row `downloadRepo.update(it)` continuation;
- therefore the previously confirmed sequence `Observe final insert -> worker Active/executionId claim -> stale Observe Queued full-row overwrite` is blocked;
- exact worker ownership is preserved across the production Observe continuation;
- the final `8c5db3c7... -> a12c5805...` follow-up changes only `ObserveSourcePostInsertClaimProductionWiringTest.kt`, adding explicit `Unit` results to JUnit lifecycle `runBlocking` bodies; no production source changed and test semantics/assertions were not weakened.

Final GitHub ref recount confirms `checkpoint/pre-baseline-review` remains exactly `a12c58055fff51b104f8b56fd53b534b8d7e5df4` with parent `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`.

## Exact-final-SHA execution evidence

Externally executed verification was performed from an isolated checkout at exact `a12c58055fff51b104f8b56fd53b534b8d7e5df4`, with the remote SHA matching before and after and the final worktree clean.

Exact-final evidence:

- `ObserveSourcePostInsertClaimProductionWiringTest`: 1/1 PASS; required test body executed;
- `DownloadDuplicateAdmissionProductionWiringTest`: 5/5 PASS;
- `ObserveSourceWorkerProductionWiringTest`: 8/8 PASS;
- `FindingAProductionWiringTest#normalClaimAttachesExactStateAndRestartRecoversAfterAttachment`: 1/1 PASS;
- `DownloadConfigurationDuplicatePolicyTest`: 13 passed, 0 skipped/failures/errors;
- full JVM suite: 613 passed, 0 skipped/failures/errors across 79 suites;
- KSP: PASS;
- debug Kotlin compilation: PASS;
- Android-test Kotlin compilation: PASS;
- Android-test APK assembly: PASS;
- `git diff --check`: PASS;
- instrumentation device: `Medium_Phone_API_36.1`, API 36, x86_64.

No source/test change, commit, push, amend, rebase, squash, reset, or force-push occurred during final verification. A temporary ignored SDK-only `local.properties` was removed after execution; the isolated worktree finished clean.

Implementation-agent/verifier execution is accepted as exact-final-SHA execution evidence; it is not independent reviewer execution.

## Canonical disposition

`BUG-DUPLICATE-ADMISSION-01`: **CLOSED / CLEAN**.

No source-semantic residual remains in the reviewed duplicate-admission scope, and the previously missing exact-final-SHA execution evidence is now complete.

Canonical blocker-count change:

- P0: `2 -> 2`
- P1: `1 -> 1`
- P2: `30 -> 29`

The project remains overall `NOT_CLEAN` because unrelated canonical blockers remain open.

## CLEAN-basis consequence

The cumulative range from the prior CLEAN basis through the exact final implementation state is CLEAN for the completed implementation scope with required execution evidence.

Advance contiguous independently CLEAN Review Basis:

`3616ae02e56995e795cc52f3074d8c3d1cd2e330`

->

`a12c58055fff51b104f8b56fd53b534b8d7e5df4`

Preserved prior closures remain closed; no new blocker was introduced by the test-only follow-up.

The authoritative ledger and Master Plan were not modified.

INDEPENDENT EXECUTION: NOT EXECUTED