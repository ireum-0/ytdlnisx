# Observe generation/ownership — independent exact-source re-review at 252adf8c

Date: 2026-09-25 +09:00

Implementation branch:
checkpoint/pre-baseline-review

Exact remote implementation HEAD reviewed:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

Reviewed range:
ee7eea001462b77e88a201ed2f26c2385048d421..252adf8c0cb3762b4dd1335a7c24fd912be683e5

Governing findings:
- P0 BUG-OBSERVE-HANDOFF-01
- P2 BUG-OBSERVE-02

Prior P0 checkpoint:
8608e4abd1cf7cffab66feb96f9b97470a065f9a

Prior P2 checkpoint:
348579005421e639b13c41affd5427f413decae6

## Verdict

CLEAN / FIXED-CLOSED for BUG-OBSERVE-HANDOFF-01 and BUG-OBSERVE-02 at exact
remote SHA 252adf8c0cb3762b4dd1335a7c24fd912be683e5.

Canonical defect delta:
- P0: -1
- P1: 0
- P2: -1

Canonical totals after this closure:
- P0 = 0
- P1 = 0
- P2 = 22

Overall remains NOT_CLEAN because residual P2 findings remain.

CLEAN_REVIEW_BASIS advances to:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

## Independent exact-source findings

### 1. Durable ordinary configuration generation now exists

ObserveSourcesItem persists a non-null configurationGeneration with database
default 1.

DB version is 63. Migration 62->63:
- adds sources.configurationGeneration NOT NULL DEFAULT 1;
- adds work_manager_handoff_carriers.sourceConfigurationGeneration NOT NULL
  DEFAULT 0;
- backfills extant OBSERVE_RETRY_DOWNLOAD carriers to generation 1 when their
  source still exists.

The generated Room schema 63 is present.

### 2. Every ordinary request is bound to the exact generation

ObserveSourcesRepository.enqueueObservation(...) puts:
- INPUT_SOURCE_ID;
- INPUT_CONFIGURATION_GENERATION;
- a generation-specific WorkManager tag

onto every ordinary ObserveSourceWorker request.

ObserveSourceWorker rejects missing/legacy generation as authority and routes
legacy requests through reconciliation instead of executing captured legacy
configuration.

Confirmed retry WorkRequests also carry:
- sourceConfigurationGeneration;
- source id;
- confirmed URL;
- exact handoff/request identity;
- config fingerprint;
- generation-specific tag.

### 3. Edit, STOP, delete durably supersede old authority

User reconfiguration uses advanceUserConfigurationIfGeneration:
- exact expected-generation CAS;
- configuration-owned field update;
- generation increment;
- ACTIVE transition;
- explicit optional run-count/processed-link resets only.

STOP uses revokeActiveGeneration inside the DAO transaction and increments the
generation before WorkManager cancellation.

Delete removes the exact expected-generation row before WorkManager
cancellation.

A stale UI snapshot therefore loses its CAS rather than overwriting newer
authority. A->B->A configurations receive distinct generations.

### 4. Runtime/config ownership is split

The previous full-row update path is no longer used for ordinary UI edits.

Configuration reconfigure writes only configuration-owned fields plus explicit
user-authorized reset fields.

Worker publication uses updateRuntimeIfGeneration, restricted to:
- runCount;
- ignoredLinks;
- alreadyProcessedLinks;
- runHistory;
- runInProgress;
- currentRunStatus;
- retryPromptedLinks;
- observedLinks;

and requires ACTIVE + exact expected generation.

The edit UI may still construct default runtime values, but they are not
persisted by reconfigure unless the explicit reset switches request the
documented reset behavior.

This closes BUG-OBSERVE-02 without changing its intended reset semantics.

### 5. Final worker effects revalidate generation under ordinary admission

ObserveSourcesRepository.withActiveGeneration(...) shares
RestoreMutationAdmission ordinary authority with edit/STOP/delete and checks
ACTIVE + exact generation before the effect block.

ObserveSourceWorker applies that boundary to:
- positive Download admission;
- destructive History/file synchronization;
- automatic-keyword discovery publication;
- membership requeue/start side effects.

Runtime publication uses publishRuntimeIfCurrent under the same authority.

Recurring finish/runtime/successor publication is combined in
finishRunAndSchedule under the same ordinary admission boundary.

If edit/STOP/delete wins admission first, the stale generation cannot enter the
later effect.

### 6. Automatic stop and successor publication are generation-fenced

finishAndRevokeGeneration atomically:
- persists the exact worker runtime;
- sets STOPPED;
- increments generation;
- requires ACTIVE + exact expected generation.

For a continuing run, runtime publication must win the exact-generation CAS
before enqueueObservation reads/schedules the current source.

The worker's post-stop notification cleanup additionally requires the expected
automatic-stop generation transition.

### 7. Process-death/restart authority is durable

The generation is stored in Room, not process memory.

WorkManager requests carry it in input data/tags.

Confirmed-retry handoff carriers persist sourceConfigurationGeneration and
configFingerprint and startup reconciliation retires carriers whose source is
absent, stopped, on another generation, or on another fingerprint.

A retry request is cancelled by exact request UUID when its durable carrier is
stale; it does not cancel a newer unique-work owner.

### 8. Backup/restore does not import generation authority

BackupSettingsUtil removes configurationGeneration from Observe source backup
JSON.

BackupRestoreParser forces restored Observe sources to
configurationGeneration=1 and normalizes legacy payloads likewise.

Restore application allocates destination-local source ids and again creates
destination sources with configurationGeneration=1.

Thus backup-local generation values cannot become destination authority.

### 9. Previously required surrounding semantics remain represented

The final source continues to preserve:
- SourceSnapshot authority gating before destructive absence reconciliation;
- History reference/target revalidation within the generation-fenced
  destructive boundary;
- membership-retry revocation transactions;
- specialized confirmed-retry fingerprint + exact request carrier semantics;
- duplicate admission;
- Restore-vs-ordinary mutation admission;
- managed automatic-keyword Observe source generation handling.

No new P0/P1 blocker was found in the reviewed range.

## Execution evidence

Implementation-agent evidence reports exact-SHA PASS on
252adf8c0cb3762b4dd1335a7c24fd912be683e5 for:
- BackupSettings 1/1;
- migration 1/1;
- generation/ownership 6/6;
- handoff admission 7/7;
- source snapshot 3/3;
- ObserveSourceWorkerProductionWiringTest 14/14;
- compileDebugKotlin;
- compileDebugAndroidTestKotlin;
- git diff --check.

Those counts are evidence only. They are not independent reviewer execution.

## Residual explicitly not closed here

P2 BUG-OBSERVE-03, alias OBSERVE-SCHEDULER-ASYNC-BARRIER-01, remains a distinct
finding.

The current source's recurring successor still treats the returned
enqueueUniqueWork Operation as sufficient publication:
finishRunAndSchedule -> enqueueObservation returns a non-null Operation, but the
ordinary recurring path does not await Operation.result or persist a durable
ordinary recurrence enqueue-acceptance/recovery owner.

That separate P2 debt was explicitly excluded from the P0 generation-fence
closure contract and is not counted as a failure of this wave.

## Governance note

252adf8c contains a stale Reviewed-Checkpoint trailer. The independent
correction checkpoint already records that metadata mismatch. No source/tree
rewrite was required, and the exact tested SHA is the exact current remote
implementation HEAD.

INDEPENDENT EXECUTION: NOT EXECUTED
