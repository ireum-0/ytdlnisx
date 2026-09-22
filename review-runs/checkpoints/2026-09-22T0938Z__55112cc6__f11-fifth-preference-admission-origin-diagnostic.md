# F11 fifth-wave Preference admission failure — startup isolation / mutation-origin diagnostic required

Date: 2026-09-22

Authoritative remote implementation HEAD:
`55112cc6d5234e44b785fe655007a7fb58ac0553`

Reported exact local fifth-wave candidate:
`08aa2656715c19f14afda244c5879a66c398d8ba`

Reported local chain:
- `c3ff9bc93ee93ba66fac9e194314da4058bd271c`
- `6df11bccefc0e3e75ef8a3a7c4d6ef3dba6c3ac3`
- `08aa2656715c19f14afda244c5879a66c398d8ba`

Reported relation:
3 ahead / 0 behind

No push occurred.

Governing review before this classification:
`9fa4fe10052dbd5187fd78f61f1e3ec59f8f3491`

## Reported verification

On exact local candidate `08aa2656...`:
- Cleanup focused method PASS 1/1;
- Cleanup full class PASS 69/69;
- scheduler transition / scheduler external authority / WorkManager handoff / real handoff / Restore alarm fallback / third-remediation suites passed;
- first later valid failure occurred in `F11PreferenceMutationAdmissionProductionWiringTest`.

Preference class:
- 6 discovered;
- 6 executed;
- 0 skipped;
- 1 failed;
- 5 passed.

Failing method:

`listPreferenceResetWinsBeforeFrameworkMutation`

Observed assertion:

`expected: "video", actual: "auto"`

Later gates were correctly short-circuited.

## Authoritative remote source facts

Do NOT inspect the unpushed local fifth-wave/harness diff.

At authoritative remote `55112cc6...`, the preference-admission test blob is:

`8c1060e747c93343cb8fe295e05a5ddeda420398`

The failing method calls `resetWins(...)` with:
- key `preferred_download_type`;
- initial `video`;
- restore target `audio`;
- expected pre-Restore value `video`.

`resetWins(...)`:
1. commits the initial value through raw test `SharedPreferences.Editor.commit()`;
2. constructs AndroidX Preference objects using production `RestoreAwarePreferenceDataStore`;
3. publishes a Restore;
4. launches a competing ListPreference mutation only after Restore publication authority is acquired;
5. blocks that framework mutation in `beforeAdmissionForTesting`;
6. releases it while Restore is active;
7. requires that mutation to fail with `IllegalStateException`;
8. then asserts the durable preference is still the original `video` before allowing Restore quiescence to continue.

At `RestorePhase.PREPARED`, production Restore has published durable Restore authority but has NOT yet run `publishPreferences()`.
Preference publication occurs later from FILES_READY/APPLYING under `withRestoreMutation`.

Therefore `auto` at the pre-quiescence assertion is not the intended Restore target (`audio`) and is not an expected write from the tested framework mutation.

## Exact `auto` source

At authoritative remote, `app/src/main/res/xml/downloading_preferences.xml` declares:

`preferred_download_type` default value = `auto`.

`App.onCreate()` launches startup initialization asynchronously after Restore recovery.

That startup initialization includes `setDefaultValues()`.

The startup/default preference writer is already part of the governing F11-R2 preference-authority graph and was independently recorded in:

`review-runs/checkpoints/2026-09-21T0106Z__7efd3fe2__f11-startup-default-preference-addendum.md`

Current production source correctly orders startup default publication after Restore recovery and wraps the default publication in ordinary Restore admission.

However, this preference-admission test's `setUp()` does not establish that the startup default writer for the same preference namespace has completed before the test writes its synthetic `video` precondition.

It also does not preserve a key-specific mutation timeline capable of proving which actor changed `preferred_download_type` if the value changes unexpectedly.

## Classification

Current failure classification:

**VALID EXECUTED FAILURE / MUTATION ORIGIN INDETERMINATE / TEST HARNESS ISOLATION-AND-OBSERVABILITY GAP.**

Specifically:
- production F11-R2 regression: NOT ESTABLISHED;
- startup/default production regression: NOT ESTABLISHED;
- preference-admission production regression: NOT ESTABLISHED;
- test PASS: NO;
- unchanged-tree rerun: NOT AUTHORIZED;
- canonical blocker-count delta: 0.

The failure remains preserved permanently.

Protocol §16.2 prohibits simply rerunning this same failed semantic test on unchanged `08aa2656...`.

The same section also requires the next narrow test/harness correction to preserve the surrounding state when the original failure report is insufficient to classify the production path.

## Authorized correction

Authorize exactly ONE additional forward test-only diagnostic/isolation commit on top of `08aa2656...`.

Authorized file ONLY:

`app/src/androidTest/java/com/ireum/ytdl/ui/more/settings/F11PreferenceMutationAdmissionProductionWiringTest.kt`

No production source change is authorized.

The correction must:

1. Establish startup/default-writer isolation in `setUp()`.
   - use the existing durable startup-default marker `spl == 1` as the relevant readiness condition;
   - if it is not already 1, register a SharedPreferences listener before waiting so the test cannot miss the transition;
   - use a bounded wait;
   - unregister the listener in all cases;
   - do not use arbitrary sleeps;
   - require `RestoreGate.isRestoreInProgress(context) == false` after recovery before test-specific mutation begins.

2. Preserve the initial-value precondition explicitly.
   - after `putValue(key, initial)`, assert the raw durable preference equals the requested initial value;
   - after `preferencesFor()` attaches the production PreferenceDataStore, assert the same value still holds before launching Restore.

3. Add diagnostic phase capture for `preferred_download_type` without changing production behavior.
   At minimum preserve:
   - value immediately after initial commit;
   - value immediately after Preference hierarchy attachment;
   - value when Restore publication authority is acquired;
   - value when the framework write reaches `beforeAdmissionForTesting`;
   - value immediately after the blocked framework writer returns/fails;
   - current RestoreGate state for those phases.
   A key-specific SharedPreferences listener/timeline is acceptable.

4. Keep all existing semantic assertions.
   Do not weaken:
   - Restore must own publication first;
   - competing framework mutation must be rejected;
   - pre-quiescence durable value must remain the initial value;
   - final Restore value must become the requested restore target.

5. If the diagnostic listener sees an unexpected key mutation, preserve the complete phase/value sequence in the failure message/report.

Do NOT:
- change expected `video` to `auto`;
- make `auto` an accepted alternate result;
- clear/rewrite the preference after the unexpected mutation;
- bypass `RestoreAwarePreferenceDataStore`;
- add a retry loop around the semantic test;
- add sleeps;
- change production startup/default behavior;
- modify scheduler fifth-wave code;
- modify Cleanup harness correction.

## Commit policy

Add exactly one forward test-only commit.

Required trailers:

`Defect-ID: BUG-BACKUP-03`
`Reviewed-Checkpoint: <this checkpoint SHA>`
`Review-Finding: F11-R2-PREFERENCE-ADMISSION-HARNESS`
`Canonical-Defect-Delta: 0`

The commit must touch only the one authorized androidTest file.

## Verification after correction

Because the test tree changes, rerun is then authorized.

On the new exact committed SHA:

1. `git diff --check`;
2. AndroidTest compile as needed;
3. run only:
   `F11PreferenceMutationAdmissionProductionWiringTest.listPreferenceResetWinsBeforeFrameworkMutation`;
4. if PASS, run full `F11PreferenceMutationAdmissionProductionWiringTest` once.

If focused FAIL:
- STOP;
- no rerun;
- preserve exact phase/value timeline;
- report whether initial commit and hierarchy-attachment preconditions were valid;
- report the first phase where value changed;
- report RestoreGate state at that phase;
- no further correction is authorized until independent classification.

If full class FAIL:
- STOP on first valid failure;
- no automatic isolate/rerun.

If full class PASS 6/6:
- the current `video -> auto` result is classified as a harness isolation/state-origin failure for this verification wave;
- preserve the original failure;
- continue the still-missing fifth-wave exact-final-SHA gates on the SAME new SHA.

## Exact-final-SHA consequence

This diagnostic/isolation commit changes androidTest source.

Therefore earlier execution at `08aa2656...` is historical evidence only for final-SHA closure.

After focused/full preference PASS, the governing fifth-wave focused/neighboring/broader execution must be valid on the new exact final SHA, including Cleanup 69/69, frozen-27 reconciliation, and the broad F11 identity set.

No unchanged-tree green-seeking reruns.

## Push consequence

Only the exact tested four-commit chain may be normally pushed if every mandatory final-SHA gate completes.

No amend/rebase/squash/force-push/history rewrite.

Independent exact-source re-review remains required after push.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
