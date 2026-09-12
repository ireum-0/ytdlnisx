# BUG-BACKUP-03 — destructive Reset restore current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Remote implementation HEAD was independently verified at session bootstrap as `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F11.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Current-basis prerequisite review immediately preceding this checkpoint: P1 `BUG-BACKUP-04` remains OPEN at `9edd3e23...`, checkpoint `7d39436a724e467edfbf09868ad9a3088173d009`.
- F10 `BUG-CLEANUP-01` also remains OPEN: overnight candidate `e280bf758bac0d7e921b23694a64cad13ea02bf2` was independently NOT_CLEAN at checkpoint `1fc97d78e86583226eda8a89908e9d4068b1ae37`.
- Historical `BUG-BACKUP-01` numeric History-marker restore hypothesis is not this root and was independently not reproduced after marker remap/fail-closed/current source+type authorization.
- No in-progress implementation diff was inspected or relied on.

## Verdict

**NOT_CLEAN — existing P0 `BUG-BACKUP-03` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

The root is independently reproduced, but **canonical implementation is currently BLOCKED_BY_HARD_PREREQUISITES under Master Plan F11**.

- Canonical blocker-count delta: `0`
- Canonical blocker count remains: **P0 2 / P1 1 / P2 34**
- CLEAN Review Basis remains: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Overall canonical state remains `NOT_CLEAN`.

## Governing F11 contract

Master Plan F11 classifies `BUG-BACKUP-03` as `SOL_EXTRA_HIGH_PLAN_THEN_LUNA` and requires hard prerequisites F1, F4, F5, F6, F7, F8, F9, F10 before implementation.

The F11 correction must prove all of the following:

1. immutable fully parsed/validated restore plan before destructive mutation;
2. app marker/version/capability/reference validation before Reset;
3. related Room mutation atomicity;
4. staged filesystem artifacts;
5. explicit SharedPreferences commit/compensation;
6. conflicting worker quiescence/gating;
7. post-commit scheduling/reconciliation;
8. deterministic process-death recovery at every phase;
9. no worker sees partial restore;
10. scheduling failure is represented honestly and retried idempotently.

Current source violates multiple items directly.

## Exact current production evidence

### 1. Destructive Reset remains a long cross-domain live-mutation sequence with no restore-wide commit/rollback protocol

`SettingsViewModel.restoreData(data, context, resetData = true)` is wrapped by one outer `runCatching`, but the body performs live writes/deletes sequentially across different durability domains.

Current ordering includes, among other stages:

- restore/write custom-thumbnail files;
- cancel/delete automatic keyword rules/assignments where applicable;
- clear/write default SharedPreferences;
- delete and rebuild History;
- clear/rebuild keyword groups;
- clear/rebuild Youtuber groups/relations/metadata and visibility preferences;
- delete/rebuild queued/scheduled/cancelled/errored/saved Download rows;
- delete/rebuild Observe Sources and schedule ACTIVE restored sources;
- restore automatic-keyword rules/matches/assignments and enqueue rule work;
- delete/rebuild cookies;
- delete/rebuild command templates/shortcuts;
- delete/rebuild search history.

These operations are not one Room transaction, not one durable staged plan, and not paired with a compensating rollback log. If a later DAO/filesystem/preference/scheduling operation throws, `restoreData()` merely returns `result.isSuccess == false`; mutations already committed in earlier stages are not restored.

Concrete reachable sequence:

`Reset accepted`
→ `preferences/History/group/download state deleted or replaced`
→ `some restored work may already be scheduled`
→ `later category insert/delete/serialization/storage/scheduling throws`
→ outer `runCatching` returns failure`
→ earlier destructive mutations and side effects remain live.

That is the exact F11 destructive-partial-restore root.

### 2. Destructive Reset is offered without validating the backup app marker or supported backup-format version

Backup creation writes `app = "YTDLnisX_backup"` and `backup_format_version = 3`.

The restore UI parses the selected file into a generic `JsonObject`, immediately builds `RestoreAppDataItem` from whichever recognized category keys are present, and then offers both Merge and Reset. No check of the `app` marker or `backup_format_version` occurs before `showAppRestoreInfoDialog(...)` exposes the destructive Reset action.

`RestoreAppDataItem` itself carries only category payloads; it has no manifest/version/capability fields that `restoreData()` could revalidate.

Therefore syntactically parseable category-shaped JSON can reach destructive Reset without the F11 manifest/version admission proof.

### 3. Filesystem restore is not staged transactionally and can silently produce partial live artifacts

`restoreData()` begins by calling `restoreCustomThumbnails(...)` before the destructive database sequence.

That helper writes directly into the live app-owned `custom_thumbs` directory using deterministic `restored_<oldHistoryId>.<ext>` names. Base64 decode failures and file-write failures are converted to omission via `getOrNull()` / `return@forEach`; there is no all-or-nothing staging manifest and no cleanup/rollback when a later restore stage fails.

Thus filesystem mutation can precede database commit and survive a failed Reset. This also intersects F5/F4 prerequisite semantics; it is evidence that F11's staged-filesystem invariant is not yet established, not a new blocker count.

### 4. SharedPreferences are mutated live before later Room/WorkManager stages and have no compensation

For a selected settings payload, Reset calls `PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) { if (resetData) clear(); ... }` near the beginning of the restore.

Later visibility preference groups are also committed separately.

There is no restore-wide preimage/compensation mechanism tying those durable preference mutations to later Room/filesystem/scheduling success. Moreover the AndroidX extension's commit result is not surfaced by this call pattern; that narrower false-success persistence issue is separately owned elsewhere and does not replace F11's cross-domain atomicity root.

### 5. Work can be published before all selected restore stages commit

During restore, queued/scheduled Download data can call `downloadRepository.startDownloadWorker(...)` before later categories such as cookies/templates/shortcuts/search history finish.

Restored ACTIVE Observe Sources call `observeSourcesRepository.observeTask(restoredSource)` inside the source restore stage.

Restored automatic-keyword rules may enqueue `AutomaticKeywordRuleScheduler` work before the trailing category stages complete.

Therefore a later restore failure can occur after executable work has already been published against a partially restored state. There is no post-commit-only scheduling barrier or idempotent restore-session scheduling recovery carrier.

### 6. Existing conflicting Observe workers are not restore-wide quiesced

Reset's Observe stage calls `observeSourcesRepository.deleteAll()`. That repository method delegates to the DAO deletion/linked-state operation; it does not cancel the corresponding WorkManager Observe jobs.

WorkManager cancellation is performed only by `cancelObservationTaskByID(id)`, which `deleteAll()` does not call. New ACTIVE restored sources subsequently call `observeTask(restoredSource)`, which cancels/replaces work only for the restored source ID.

Consequently an old pre-Reset worker can remain queued/running while Reset deletes/rebuilds shared live state. This violates F11's worker-quiescence/no-partial-observation requirements. The separate P0 `BUG-OBSERVE-HANDOFF-01` owns stale Observe generation/revocation authority generally; here the evidence is used only to establish the restore-wide gating invariant and creates no additional root.

### 7. No deterministic process-death recovery state exists for the restore session

`restoreData()` maintains temporary ID maps and progress only in process memory. It does not persist a restore session phase, staged plan, commit marker, rollback state, or recovery journal spanning the cross-domain sequence.

A process death after any early destructive stage therefore leaves whatever live mutations were already committed. Restart has no durable restore-session record from which to finish, roll back, or prove that workers may safely resume.

## Root reconciliation

Keep one existing P0 root `BUG-BACKUP-03`; count delta is `0`.

Do not merge into this root:

- historical `BUG-BACKUP-01` marker-remap/History replacement authorization;
- P1 `BUG-BACKUP-04` selected-category capture false-success/mixed-time snapshot;
- F5/F6/F7/F8/F9 backup portability/schema/content prerequisites;
- the narrower SharedPreferences `commit() == false` false-success finding;
- P0 `BUG-OBSERVE-HANDOFF-01` ordinary Observe generation/revocation;
- P2 `BUG-CLEANUP-01` recurring cleanup scheduling correctness.

Those may be hard prerequisites or interacting domains, but F11 owns the restore-wide destructive transaction/recovery protocol.

## Hard-prerequisite consequence

F11 cannot move to implementation yet.

At minimum two hard prerequisites are independently known OPEN now:

- F4 / P1 `BUG-BACKUP-04` — current-basis NOT_CLEAN at checkpoint `7d39436a724e467edfbf09868ad9a3088173d009`;
- F10 / P2 `BUG-CLEANUP-01` — candidate NOT_CLEAN at checkpoint `1fc97d78e86583226eda8a89908e9d4068b1ae37`; canonical root remains open.

F5–F9 must also be individually current-source reconciled/closed as required by the Master Plan before F11's Extra High design/implementation begins; this checkpoint does not invent their status.

Therefore the correct next exploratory dependency work is to continue F11 prerequisite reconciliation, starting with F5 `BUG-BACKUP-02`, while keeping the already-recorded Task 002 `BUG-KEYWORD-04` canonical replay as the first implementation target unless an explicit workflow event changes that order.

## Stable review conclusion

The P0 defect is concrete and current, but a safe implementation prompt is intentionally **not** issued for F11 because the governing hard prerequisites are not satisfied. Once F1/F4/F5/F6/F7/F8/F9/F10 are independently closed/current and exact source is refreshed, F11 requires a Sol Extra High architecture plan before Luna implementation rather than a direct local patch.

INDEPENDENT EXECUTION: NOT EXECUTED
