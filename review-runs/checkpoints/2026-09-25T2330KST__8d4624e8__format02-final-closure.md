# BUG-FORMAT-02 — final independent closure

Date: 2026-09-25 +09:00

Finding:
P2 BUG-FORMAT-02

Prior independently CLEAN basis:
51816a619b3c85bd2a8d130c84c37f15c662b45e

Exact final implementation HEAD reviewed:
8d4624e810c4a7d51ed625a0dc06302b0f8e1819

Parent:
51816a619b3c85bd2a8d130c84c37f15c662b45e

Prior current-basis checkpoint:
a6c5a1672983f95b6631f2334c2ebf224ff9f52a

## Verdict

CLEAN / FIXED-CLOSED for P2 BUG-FORMAT-02.

Canonical count delta:
- P0: 0
- P1: 0
- P2: -1

Resulting canonical totals:
- P0 = 0
- P1 = 0
- P2 = 19

Overall remains NOT_CLEAN because unrelated canonical P2 findings remain open.

The contiguous independently CLEAN review basis advances to:

8d4624e810c4a7d51ed625a0dc06302b0f8e1819

## Exact-source closure

The delayed format-completion notification still carries numeric Download IDs,
but those IDs are now only candidate/navigation hints.

DownloadViewModel no longer globally deletes Processing rows when consuming a
format-notification bundle.

For each deleteExisting=true notification candidate:
- deleted rows are skipped;
- Error rows retain the existing retry/history-replacement snapshot-fenced
  path;
- non-Error rows are delegated to
  DownloadRepository.transitionFormatNotificationCandidateToProcessing(...);
- one stale/refused/deleted candidate no longer aborts the remaining bundle.

The repository transition reloads current state and permits only:
- status = Saved;
- blank executionId;
- blank operationId;
- retryAttempt = 0;
- no current process-local Download execution owner.

The final mutation runs under the Download worker execution lock and uses the
existing transactional exact-snapshot CAS primitive with expected:
- status;
- executionId;
- operationId;
- retryAttempt;
- issue code/stage.

The same lock is used by production Download worker claim/publication, which
prevents a stale Saved observation from racing through after a newer execution
claim. A concurrent lifecycle/user transition that changes status or generation
identity causes the CAS to refuse the notification mutation.

The resulting format-notification path therefore cannot rewrite current
Active/PostProcessing/Queued or newer execution-generation state underneath a
live owner.

The broad deleteProcessing() cleanup remains only on the legacy
deleteExisting=false path and is not used by the delayed notification bundle.

## Regression coverage

Exact implementation adds:

app/src/androidTest/java/com/ireum/ytdl/database/DownloadFormatNotificationAuthorityProductionWiringTest.kt

Reported exact-final-SHA execution:
- DownloadFormatNotificationAuthorityProductionWiringTest: 7/7 PASS;
- :app:compileDebugKotlin -x lint: PASS;
- :app:compileDebugAndroidTestKotlin -x lint: PASS;
- git diff --check: PASS.

Independent source review confirms the focused coverage includes:
- Active current owner refused with status/execution/operation/retry preserved;
- PostProcessing current owner refused;
- newer Queued intent refused;
- deleted candidate safely skipped;
- eligible Saved candidate transitions to Processing;
- mixed stale/deleted/eligible bundle preserves stale rows and processes the
  eligible sibling;
- Error History-replacement refusal remains preserved.

Implementation-agent execution is evidence, not independent execution by this
reviewer.

## Scope/regression review

The implementation range modifies only:
- DownloadRepository.kt;
- DownloadViewModel.kt;
- the new focused androidTest class.

No unrelated production refactor was introduced.

The general deleteExisting=false Download editing path retains its prior broad
Processing cleanup behavior.

No source blocker was found reopening previously closed automatic-keyword or
Observe ownership roots.

## Push verification

Live implementation branch was independently verified at:

8d4624e810c4a7d51ed625a0dc06302b0f8e1819

with exact parent:

51816a619b3c85bd2a8d130c84c37f15c662b45e

The implementation comparison is one commit ahead / zero behind.

## Queue consequence

BUG-FORMAT-02 no longer blocks basis advancement.

Advance CLEAN_REVIEW_BASIS to:
8d4624e810c4a7d51ed625a0dc06302b0f8e1819

Continue established P2 review order with:
BUG-TERMINAL-05

INDEPENDENT EXECUTION: NOT EXECUTED
