# F10 folder-settings Espresso focus failure: UI readiness/infrastructure classification

## Authoritative state

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Authoritative remote HEAD independently re-verified:
  `aa50a500a47263f704914f0960543d6e92ff3aff`
- Parent:
  `f869315196b222f6919832181e1f98c29a7ccdfc`
- No push occurred.

Protected local-only chain now reported through:

`398b9800712bdeeba2e2261d9325229bb5ab6ecf`

Subject:
`test: revoke cleanup replay authority before teardown drain`

Trailer:
`Defect-ID: BUG-CLEANUP-01`

All prior local-only commits remain preserved.

## Reported verification

Implementation-agent evidence:

- teardown/quiescence focused regression
  `terminalWorkerRetainsExactSuccessorAcrossReplayOwnerLoss`: PASS 1/1 including teardown;
- static/build checks: PASS;
- full coordinator class discovered 69;
- execution stopped at:
  `folderSettingsResetRetainsCachePathWhenRootCaptureFails`;
- exception:
  `RootViewWithoutFocusException`;
- Espresso timed out because no app root gained window focus;
- launcher remained the focused app;
- no ANR or application crash was reported;
- partial run stopped under the current consolidation stop boundary.

## Exact-source UI harness pattern

At authoritative remote `aa50a500...`,
`folderSettingsResetRetainsCachePathWhenRootCaptureFails`:

1. launches `SettingsActivity` with `ActivityScenario.launch(...)`;
2. immediately uses `scenario.onActivity` to navigate to `FolderSettingsFragment`;
3. directly invokes the reset preference's `performClick()`;
4. immediately calls Espresso:
   `onView(withText(R.string.continue_anyway)).perform(click())`.

The test does not establish an explicit window-focus/readiness boundary before handing control to Espresso.

Several sibling settings tests use materially the same pattern, including:
- `folderSettingsResetCapturesPreBindingRootBeforeDefaultingCachePath`;
- `folderSettingsResetPreservesR1ForLegacyJournalAfterRestart`;
- `downloadSettingsResetDisablesCleanupThroughProductionConsumer`;
- `downloadSettingsResetWaitsForAdmittedCleanupWithoutBlockingUi`.

`ActivityScenario.launch`/RESUMED lifecycle and fragment transaction completion are not themselves proof that the relevant application window/root currently owns focus.

The reported exception occurred before a production semantic assertion was reached. No evidence from this run establishes a cleanup/cache-path production defect.

## Classification

Current result is:

**UI instrumentation readiness / infrastructure-invalid semantic execution**

not a production correctness failure.

The fastest discriminator is a focused execution of the exact failed test against exact committed local SHA `398b9800...` before editing.

### Branch A — focused test passes unchanged

If the focused test executes once and passes:

- classify the full-class focus failure as an infrastructure-invalid full-class run;
- do not modify the test merely because of one non-reproduced focus event;
- a new full-class execution against the unchanged exact SHA is permitted under the infrastructure-invalid rerun rule.

### Branch B — focused test reproduces RootViewWithoutFocusException

If the focused test reproduces the same no-focused-root condition:

- treat it as a concrete UI harness readiness defect;
- in the same consolidation wave, add a bounded deterministic Activity/window readiness boundary;
- audit/apply that helper to the materially identical SettingsActivity + direct fragment navigation + dialog Espresso sibling tests;
- do not use arbitrary sleep;
- do not weaken the production assertions.

A reasonable semantic helper must prove the Activity is RESUMED and its decor/root has window focus before direct navigation/preference invocation hands off to Espresso. If dialog publication itself also needs a bounded readiness signal, prove the dialog/root is actually present/focused rather than sleeping.

## Production stop boundary

Production remains out of scope.

STOP and reopen production only if a properly focused/resumed UI test reaches its production assertions and shows an actual semantic failure such as:
- root-capture failure mutates `cache_path` despite the retain contract;
- reset proceeds across a prohibited capture failure;
- exact pre-binding root preservation fails;
- cleanup scheduling/reset semantics themselves fail after UI readiness is established.

## F10 disposition

`F10 / BUG-CLEANUP-01: SOURCE-FIXED / TEST-COVERAGE-FIXED / EXECUTION-HARNESS-RESIDUAL / EXECUTION-NOT-VERIFIED`

Canonical production counts unchanged.
F11 remains blocked.

INDEPENDENT EXECUTION: NOT EXECUTED
