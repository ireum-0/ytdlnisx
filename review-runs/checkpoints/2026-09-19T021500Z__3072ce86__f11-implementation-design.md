# F11 / BUG-BACKUP-03 implementation design checkpoint

## Status

F10 is closed. F11 is eligible.

Authoritative implementation branch:

`ireum-0/ytdlnisx:checkpoint/pre-baseline-review`

Exact reviewed HEAD:

`3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`

Expected parent:

`d21a2fdc4baf97e85d75c9b10bc63001aa696340`

This checkpoint records the independent ChatGPT planning decision for F11. It does not claim implementation or execution closure.

## Confirmed current F11 root

Current Reset is not restore-wide atomic/recoverable.

`MainSettingsFragment` directly parses external JSON into mutable `RestoreAppDataItem`, validates only version 3..4 plus the format-4 playlist graph helper, and does not validate the app marker before presenting Reset.

`SettingsViewModel.restoreData(..., resetData=true)` then performs destructive state changes incrementally across:
- default SharedPreferences;
- History and assignment tables;
- playlist/group relationships;
- keyword/youtuber tables;
- Download categories and linked recovery/barrier state;
- observe-source rows and WorkManager tasks;
- automatic-keyword rows/scheduling;
- destination thumbnail files;
- notifications;
- cleanup coordinator integration.

Those effects are split across multiple Room transactions, direct preference commits, filesystem publication, notification cancellation, and WorkManager scheduling. Several schedulers are invoked before the restore as a whole succeeds.

A crash/failure after an early mutation can therefore expose a partial Reset with no restore-wide durable owner.

## Current format/history facts

Current backups write:
- app marker: `YTDLnisX_backup`;
- `backup_format_version = 4`.

The current UI treats missing version as version 3 and accepts 3..4, but does not validate the app marker.

Repository history proves:
- initial backups used marker `YTDLnisx_backup` and had no format version;
- later backups used `YTDLnisX_backup` without a format version;
- implementation branch at `90afaec...` emitted format 3;
- `eee70c...` introduced format 4 for relationship-complete playlist payload.

Do not infer any additional legacy compatibility without inspecting the exact historical field layout.

## Selected architecture

Do **not** add a Room restore-journal entity or DB schema migration by default.

Current DB version is 62 and all Room restore targets already live in the same DB. The smallest cross-system recovery design is:

1. immutable normalized `RestorePlan`;
2. centralized parser/validator;
3. file-backed durable restore transaction directory under `noBackupFilesDir`;
4. durable Reset gate while an incomplete transaction exists;
5. idempotent roll-forward recovery;
6. one Room transaction for the Reset database graph;
7. explicit preference commit barrier;
8. deterministic operation-owned filesystem publication;
9. post-commit scheduler/notification reconciliation from final authority;
10. startup recovery before existing startup reconcilers are launched.

A schema migration is out of scope unless implementation discovers exact source evidence that this design cannot satisfy a required invariant. If so, STOP for independent review rather than adding a migration.

## Durable operation carrier

Use an operation directory such as:

`noBackupFilesDir/restore-transactions/<operationId>/`

The exact filenames/types may vary, but the carrier must durably and atomically contain enough information to resume without the original Activity/URI/process. Prefer AtomicFile-style temp/write/fsync/rename discipline.

The journal/plan must include:
- operation id;
- mode (RESET; MERGE may use parser but does not require this transaction protocol);
- normalized validated restore plan or a durable reference to operation-owned normalized payload;
- staged file metadata/content digests;
- current phase;
- enough information to identify what post-commit reconciliation remains.

Do not make a SAF URI or process-local object the only recovery carrier.

## Restore state machine

Exact names may vary. Required semantics:

### PREPARED
- raw input has been completely parsed and validated;
- immutable normalized plan exists;
- required custom-thumbnail payloads are staged and fsynced;
- durable journal is atomically committed;
- no destructive target mutation has occurred before this point.

Once PREPARED exists, recovery owns the Reset and must roll it forward.

### QUIESCED
- durable restore gate is active;
- new conflicting worker/scheduler admission is blocked or made no-op/deferred while gate is active;
- currently running conflicting WorkManager work has reached an actual terminal/quiescent boundary;
- issuing cancellation alone is not sufficient.

### FILES_READY
- every destination-owned file required by the plan has been published to a deterministic operation-owned final path;
- publication is idempotent;
- files are still unreferenced by committed Room authority;
- a retry/process restart can reuse them.

Custom-thumbnail final naming must be deterministic from restore operation + backup-local identity/content (or equivalent), not destination auto-id + random UUID.

### APPLYING / DATA_COMMITTED
- Reset database graph is applied inside one Room transaction;
- categories absent from the backup plan remain untouched, preserving current partial-category Reset semantics;
- current old→new identity mapping, History replacement/barrier semantics, paused order/state, playlist mapping, observe-source mapping, automatic-keyword mapping, and destination-local fields are preserved;
- SharedPreferences are committed with a checked Boolean result using the fully validated plan;
- visible-child preferences belong to this planned preference commit, not scattered mid-restore commits.

Because DB + SharedPreferences cannot be one ACID transaction, reapplication after an ambiguous crash must be semantically idempotent. The durable Reset gate remains active until the cross-system committed image is re-established.

If preference commit fails in-process, attempt compensation from the exact captured pre-commit preference snapshot and retain the durable journal/gate for recovery. Never report successful Reset.

### RECONCILING
The restored data/preferences/files are authoritative. External operational side effects are rebuilt only from that final state:
- queued/scheduled Download work;
- active observe-source work;
- automatic-keyword observation/worker coverage;
- cleanup scheduling reconciliation;
- required notifications/cancellations;
- any other affected scheduler discovered in the exact source inventory.

Failure here must **not** roll back or describe the committed restore as uncommitted. Persist reconciliation debt in the transaction journal and return a typed committed-but-reconciliation-pending result. Startup/next reconciliation retries idempotently.

### COMPLETE
- all required reconciliation is confirmed;
- restore gate is released;
- staging/journal artifacts are retired safely.

## Idempotent Reset database transaction

Do not call current repository helpers from the Reset transaction when those helpers:
- create their own externally visible side effects;
- cancel/schedule WorkManager;
- delete filesystem state;
- cancel notifications;
- otherwise escape the Room transaction.

Introduce narrow in-transaction restore primitives or DAO-level operations where required.

The one Reset DB transaction must apply all Room categories present in the immutable plan and create all fresh destination identity maps in that same transaction.

Process death before Room commit rolls back automatically.
Process death after Room commit but before phase advancement is handled by rerunning the same category Reset transaction from the immutable plan. The operation must converge semantically even if destination auto-increment IDs differ from a prior committed-but-unrecorded run.

Do not clear categories absent from the plan. Preserve established behavior such as paused-only Reset not deleting queued rows.

## Preferences

Before destructive work:
- parse and type-check every portable preference;
- filter destination-local and cleanup-coordinator-owned keys through the existing portability policy;
- construct the complete desired preference mutation.

For Reset with settings:
- preserve destination-local `cache_path`;
- preserve/reconstruct the cleanup cadence mirror from the dedicated coordinator authority exactly as the accepted F10 behavior requires;
- generic imported cleanup authority remains nonportable.

Use explicit `SharedPreferences.Editor.commit()` and check its returned Boolean.

Capture only the pre-commit preference state necessary for exact compensation. Do not treat `apply()` as a durability barrier.

## Filesystem

Existing thumbnail staging is useful but final publication currently happens too early and uses destination ID + random UUID.

Move to:
- pre-destructive validation/staging;
- deterministic operation-owned final publication before DB reference commit;
- fsync;
- Room row binds only an existing final file;
- unreferenced operation-owned files are retained/reused while journal is active and cleaned only when safe.

## Worker/scheduler gate

Before destructive Reset:
1. persist gate/journal first;
2. prevent new conflicting work from entering mutation;
3. cancel or otherwise quiesce current conflicting work;
4. await real terminal/quiescent completion;
5. only then mutate target state.

Inventory all workers/reconcilers that can read/write tables/preferences affected by Reset, at minimum:
- Download worker/scheduling/recovery;
- ObserveSource worker/scheduling;
- automatic keyword workers/coverage;
- cleanup worker/coordinator;
- low-quality-redownload work/recovery;
- History date-fetch work/recovery;
- LocalAdd or other History writers if they can race the Reset graph;
- other WorkManager/recovery actors discovered from exact current source.

Use the smallest centralized gate/admission hook practical. Do not broadly cancel unrelated work merely for convenience.

A worker that starts while the durable gate is active must not mutate partial Reset state. It should defer/retry/no-op according to its existing safe semantics.

## Startup recovery

`App.onCreate` currently launches multiple reconciliation coroutines immediately.

Restore recovery must run before those conflicting reconcilers are allowed to inspect/mutate restored state.

If an incomplete Reset journal exists, synchronously establish the restore gate and recover/roll-forward to a safe committed/reconciliation state before launching ordinary recovery actors.

Do not allow Cleanup/Download/History/keyword startup reconciliation to race an incomplete Reset.

## Parser and plan

Move external raw JSON parsing/validation out of `MainSettingsFragment` into a production parser.

The parser must validate before Reset mutation:
- proven app marker compatibility;
- proven format/version compatibility;
- field shapes/types;
- supported portable preference types and values;
- relationship completeness and references;
- thumbnail identity/extension/base64;
- source/rule/history/playlist/group reference constraints that can be checked from the backup-local graph;
- status normalization and destination-local field stripping.

Format 4 playlist graph keeps the existing all-four-fields rule.

For version 3 and unversioned legacy input, inspect exact historical sources before implementing aliases/capabilities. Do not simply equate unversioned with v3 because current UI defaults that way.

Output must be immutable. Do not let mutable `RestoreAppDataItem` remain the external authority for destructive Reset.

Programmatic/direct test callers may receive a typed-plan adapter, but it must run equivalent structural/value validation.

## Result semantics

Boolean is too weak for Reset.

Introduce a typed outcome that distinguishes at least:
- completed;
- rejected/failed before authoritative commit;
- recovery pending before committed data is known complete;
- committed data with post-commit reconciliation pending.

Exact names may vary.

Production UI must not tell the user “restore failed and nothing changed” when authoritative restored data is already committed but scheduling is pending.

A compatibility Boolean wrapper may remain only for non-production/internal Merge callers when its semantics are not ambiguous.

## Merge boundary

F11 is the destructive Reset finding.

Do not convert Merge into the full durable Reset transaction unless exact shared correctness requires it.

Central parsing/validation may be shared.
Preserve current Merge semantics and existing accepted regression behavior.

## Regression-contract co-maintenance

Inventory and update affected tests in the same wave.

Existing production-wiring suites include:
- `BackupPausedProductionWiringTest`;
- `BackupPlaylistProductionWiringTest`;
- `BackupPreferenceProductionWiringTest`;
- `BackupRestoreIdentityProductionWiringTest`;
- `BackupRestoreThumbnailProductionWiringTest`;
- `BackupSettingsProductionWiringTest`.

Preserve their accepted contracts:
- paused rows remain paused and do not start work;
- partial-category Reset only clears the targeted present category;
- fresh destination identities/remappings;
- relationship completeness;
- portable preference typing and nonportable exclusions;
- thumbnail ownership and staging cleanup;
- no raw destination-ID aliasing.

Add a dedicated Reset transaction/recovery production-wiring suite rather than overloading unrelated tests.

## Required new coverage

At minimum:
- wrong/unsupported app marker fails before mutation;
- unsupported version/capability fails before mutation;
- malformed preference/reference/thumbnail input fails before mutation;
- fault before PREPARED leaves all live state unchanged;
- process death/restart at each durable phase;
- worker is running when Reset begins: Reset waits/quiesces and worker cannot see partial state;
- worker starts while gate active: no partial mutation;
- Room transaction failure leaves no partial DB graph;
- preference commit failure has explicit compensation/recovery;
- filesystem staging/publication failure never binds missing/partial file;
- crash after Room commit before journal advancement converges idempotently;
- crash/failure after committed data before scheduling leaves committed-reconciliation debt;
- startup recovery retries scheduling and reaches COMPLETE;
- repeated recovery is idempotent;
- stale/complete journal cleanup cannot mutate a newer Reset;
- queued/scheduled work is recreated from final DB only;
- paused stays unscheduled;
- observe-source/automatic-rule scheduling is post-commit only;
- cleanup cadence/root semantics remain F10-correct;
- existing playlist/history/source/rule mappings remain exact;
- Merge regressions remain green.

No migration test unless a schema change is independently authorized after a STOP.

## Logical commit shape

Prefer forward commits such as:

1. `fix: validate immutable backup restore plans`
2. `fix: add durable reset recovery gate`
3. `fix: apply reset graph atomically`
4. `fix: reconcile restored work after commit`
5. `test: cover reset transaction recovery matrix`

Adapt boundaries if exact implementation requires, but keep parser/authority/gate/data/reconciliation/test changes attributable.

Every F11 commit requires:

`Defect-ID: BUG-BACKUP-03`

No amend/rebase/squash/history rewrite.

## Mandatory STOP conditions

Stop without push and report for independent review if:
- a DB schema migration appears necessary;
- the design cannot make Reset reapplication idempotent after an ambiguous Room-commit/journal-write crash;
- a conflicting production actor cannot be gated/quiesced without changing another finding's ownership model;
- legacy backup behavior required for compatibility cannot be proven from repository history;
- preference compensation cannot be made exact enough to avoid falsely successful Reset;
- an existing prerequisite invariant from F1/F4/F5/F6/F7/F8/F9/F10 must be weakened;
- a test exposes durable responsibility loss/duplication/widening outside this F11 design.

## Preservation

Preserve the user's primary dirty/untracked workspace and all three named stashes exactly:
- `preserve interrupted BUG-OBSERVE-HANDOFF-01 work after f1a159db`
- `preserve unrelated local strings change before remote synchronization`
- `codex preserve BUG-OUTPUT candidate before remote synchronization`

Reported stash hashes:
- `b4c84b2ad2c097b2d5ef20b809e3c290aa7506b8`
- `3372f0393530ddfc7ad9a3a3476ba050fb67dad4`
- `0b0236b665e4ba9527782ef146722c75355dfabc`

Use an isolated clean worktree.
