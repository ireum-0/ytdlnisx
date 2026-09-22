# F11 fifth-wave BackupReset diagnostic compile stop — WorkInfo identity diagnostic correction

Date: 2026-09-22

Authoritative remote implementation HEAD:
\`55112cc6d5234e44b785fe655007a7fb58ac0553\`

Reported exact local candidate:
\`ff7d1a57ad68a53e4debdd9c670908c4935c9e93\`

Reported parent:
\`0172cfe16ba97915b5d3e05cb4274877c861ba53\`

Reported relation:
5 ahead / 0 behind

No push occurred.

Governing review before this classification:
\`4160b98e2bbd7309ec69960f2813a6944914abdb\`

## Reported stop

Exactly one authorized forward test-only diagnostic commit was created:

\`ff7d1a57ad68a53e4debdd9c670908c4935c9e93\`

Changed file only:

\`app/src/androidTest/java/com/ireum/ytdl/database/BackupResetTransactionProductionWiringTest.kt\`

The committed diagnostic test did not compile.

Command:

\`./gradlew :app:compileDebugAndroidTestKotlin --console=plain\`

Result:

FAIL before instrumentation.

Reported compiler errors:
- unresolved reference \`inputData\` at lines 500, 506, 507;
- related type-inference error at line 503.

Therefore:
- focused BackupReset method = NOT EXECUTED;
- full BackupReset class = NOT EXECUTED;
- later fifth-wave gates = NOT EXECUTED.

The original valid BackupReset semantic failure from \`0172cfe...\` remains preserved.

## Independent classification

Current classification:

**TEST-HARNESS DIAGNOSTIC COMPILE DEFECT / PROMPT-SPEC ERROR.**

This is not:
- a new production semantic failure;
- evidence that F11-R2 regressed;
- evidence that BackupReset production regressed;
- a new canonical defect.

Canonical defect-count delta: \`0\`.

The diagnostic compile failure is a valid tool/compiler result, but it did not execute the requested semantic test. Under REVIEW_PROTOCOL §16.2, a corrected changed tree may be compiled and then used to run the originally requested semantic boundary.

## Exact source/API mismatch

The prior diagnostic prompt required reading cleanup request input identity through each returned \`WorkInfo.inputData\`.

That requirement is incorrect for the WorkInfo API used by this project.

Repository search on the authoritative remote finds no valid \`WorkInfo.inputData\` usage.

The production cleanup request does publish the same identity redundantly into exact WorkManager tags:

- base tag:
  \`CleanupScheduleCoordinator.TAG\`

- generation tag:
  \`\${TAG}_generation_<generation>\`

- cadence tag:
  \`\${TAG}_cadence_<cadence>\`

- occurrence tag:
  \`\${TAG}_occurrence_<generation>_<occurrenceAt>\`

The exact authoritative enqueue source also publishes request input Data, but the returned \`WorkInfo\` observation surface available to the test does not expose that request input as \`inputData\`.

Therefore the diagnostic can preserve exact cleanup generation/cadence/occurrence identity from \`WorkInfo.tags\` without any production source change.

## Authorized correction

Authorize exactly ONE additional forward TEST-ONLY compile-fix commit on top of:

\`ff7d1a57ad68a53e4debdd9c670908c4935c9e93\`

Authorized file ONLY:

\`app/src/androidTest/java/com/ireum/ytdl/database/BackupResetTransactionProductionWiringTest.kt\`

No production file may change.

The correction must be limited to the diagnostic implementation.

### WorkInfo diagnostic requirements after correction

For every relevant WorkInfo preserve:
- id;
- state;
- tags;
- runAttemptCount.

Derive cleanup identity only from exact tags.

Use:
- generation tag prefix:
  \`\${CleanupScheduleCoordinator.TAG}_generation_\`
- cadence tag prefix:
  \`\${CleanupScheduleCoordinator.TAG}_cadence_\`
- occurrence tag prefix after generation is resolved:
  \`\${CleanupScheduleCoordinator.TAG}_occurrence_<generation>_\`

If a tag is absent or ambiguous:
- report \`null\` / \`AMBIGUOUS\`;
- preserve the raw full tag set;
- do not invent an identity.

Do NOT:
- access \`WorkInfo.inputData\`;
- query or depend on WorkManager internal Room tables;
- add a production test seam;
- modify CleanupScheduleCoordinator;
- weaken the semantic test;
- alter timeouts to obtain green.

### Preserve the rest of the diagnostic contract

The corrected test must still:
- await Restore exactly once;
- capture exact RestoreOutcome subtype/fields;
- capture RestoreGate state;
- capture active Restore pointer/journal phase/lastError/quiescedWorkTags;
- capture history and \`f11_reset_marker\`;
- capture WorkInfos both by cleanup tag and cleanup unique-work name;
- preserve the ordered phase timeline;
- still accept only \`RestoreOutcome.Completed\`;
- still require final imported history only.

## Commit policy

Add exactly one forward compile-fix commit.

Required trailers:

\`Defect-ID: BUG-BACKUP-03\`
\`Reviewed-Checkpoint: <this checkpoint SHA>\`
\`Review-Finding: F11-BACKUP-RESET-DIAGNOSTIC-COMPILE-FIX\`
\`Canonical-Defect-Delta: 0\`

No amend.
No rebase.
No squash.
No force-push.
No history rewrite.

The prior diagnostic commit \`ff7d1a57...\` remains preserved unchanged as historical evidence of the compile stop.

## Verification after correction

On the new exact committed SHA:

1. \`git diff --check\`;
2. committed-range diff check;
3. verify the new commit touches only the authorized test file;
4. run \`:app:compileDebugAndroidTestKotlin\`.

If compile FAILs:
- STOP;
- do not make another correction;
- preserve exact compiler diagnostics;
- instrumentation remains NOT EXECUTED.

If compile PASSes:
- run only:
  \`BackupResetTransactionProductionWiringTest.activeConflictingWorkerMustQuiesceBeforeResetApplies\`
  once.

If focused semantic test FAILs:
- STOP;
- no rerun;
- report exact RestoreOutcome, journal/gate, WorkInfo tags/identity, history/marker, and phase timeline;
- no production correction is authorized until independent classification.

If focused PASSes:
- run full \`BackupResetTransactionProductionWiringTest\` once.

If full class FAILs:
- STOP on first valid failure;
- no automatic isolate/rerun.

If full class PASSes 26/26:
- continue all governing fifth-wave exact-final-SHA gates on the same exact SHA.

## Exact-final-SHA consequence

The compile-fix changes androidTest source again.

All earlier execution is historical evidence only for final-SHA closure.

All governing fifth-wave final-SHA focused/neighboring/broader gates must be valid on the new exact final SHA before push.

## Push consequence

Only the exact tested six-commit candidate may be normally pushed if every mandatory final-SHA gate completes validly.

No history rewrite.

Independent exact-source re-review remains required after push.

CLEAN basis remains:
\`90afaec157607669ea32fa41877e7f0efcdcca86\`

INDEPENDENT EXECUTION: NOT EXECUTED
