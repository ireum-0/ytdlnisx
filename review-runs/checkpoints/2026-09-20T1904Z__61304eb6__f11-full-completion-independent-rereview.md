# F11 / BUG-BACKUP-03 — full completion independent re-review

Date: 2026-09-20

## Exact reviewed state

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Original F11 base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
- Previous reviewed candidate: `2ba43fd272967725b946b1a8fca4e6314107b03b`
- Review-remediation commit 1: `36215a22172fc7781d757ebe89faa7f8ea4381be`
- Final reviewed remote SHA: `61304eb6f11b10ac66057a1978d5b1f8f75019b0`
- Canonical first F11 review: `8b5b064c63fc04de9b2d18346954ab5dfdec625e`
- Governing design checkpoint: `393ed9a2b0ade7ead283637f2a932a9a8456f99a`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing Checklist v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger ref-only: `899328bc91e4008e39a658387396a0106c8666ec`

## Git verification

Independently verified before semantic review:

- `origin/checkpoint/pre-baseline-review == 61304eb6f11b10ac66057a1978d5b1f8f75019b0`.
- `2ba43fd2..61304eb6`: exactly 2 commits ahead, 0 behind, merge base exactly `2ba43fd2...`.
- `3072ce86..61304eb6`: exactly 12 commits ahead, 0 behind, merge base exactly `3072ce86...`.
- Full F11 range contains 69 changed files.
- No Room schema / migration / DBManager version file changed.
- All 12 F11 commits contain `Defect-ID: BUG-BACKUP-03`.
- Existing history remains straight/forward-only; no amend/rebase/squash/force-push/history rewrite was observed.

## Final independent verdict

**NOT_CLEAN / REVIEW-REMEDIATION-REQUIRED**

F11 remains the existing canonical P0 root:

`BUG-BACKUP-03`

Canonical root-count delta from this re-review: `0`.

Checklist-v6 classification:

- Current blocker: `[P0] BUG-BACKUP-03`.
- F11-R1 / F11-R2 / F11-R3 are open residuals under that same P0 root; they do not receive separate HIGH/MEDIUM severities.
- F11-R4 is closed.

Do NOT advance the independently CLEAN basis to `61304eb6...`.

### Residual summary

- `F11-R1`: **STILL_OPEN**
- `F11-R2`: **STILL_OPEN**
- `F11-R3`: **STILL_OPEN**
- `F11-R4`: **CLOSED**

Intermediate checkpoints incorporated by this final verdict:

- R2 initial consumer gap: `a45afe2f005b98023a6b1899459eec342d2e61f6`
- R3 still-open: `2a65dc2ec46414436a72435c5eb75f2c5d98dd16`
- R1 still-open: `bbc04cd89b566aa3a61dced6dc9ea4259d246651`
- R4 closed: `4426d72cf47fd9194ed12051e0dd5b221cc38593`
- R2 handoff-carrier addendum: `4f915d84405332684e44231875e537b0e3b0c73b`

---

## F11-R1 — STILL_OPEN

### What the remediation fixed

The new durable `quiescedWorkTags` journal field and post-commit reconciliation materially improve sibling/remainder responsibility.

Exact final source reconstructs or converges:

- final runnable Queued/Scheduled Download ownership;
- final ACTIVE ObserveSource ownership, including managed KEYWORD_DISCOVERY sources;
- automatic-keyword observation coverage and pending apply-existing work;
- surviving low-quality-redownload ownership or terminalization;
- F10 cleanup authority.

`quiescedWorkTags` is nullable and consumed with `.orEmpty()`, so existing pre-remediation active journals missing the new field remain readable.

### Remaining LocalAdd responsibility loss

History Reset still cancels `LocalAddWorker.TAG` (`local_add_worker`).

But LocalAdd's user-submitted work has its own durable authority outside the History Room graph:

1. `HistoryFragment` expands the user's chosen media URIs.
2. It creates a session ID.
3. It calls `LocalAddStorage.saveEntries(context, sessionId, entries)` before enqueue.
4. Those entries persist under `local_add_entries_<sessionId>` in default SharedPreferences.
5. It enqueues `LocalAddWorker` carrying that session ID.

When F11 History quiescence cancels `local_add_worker`:

- the WorkManager execution owner is removed;
- the LocalAdd session entries survive;
- the authoritative History Room reset does not retire that session;
- `reconcilePostCommit()` has no LocalAdd reconstruction/retirement branch;
- `App.onCreate()` has no LocalAdd orphan-session inventory/re-enqueue pass.

Therefore this reachable sequence remains:

user submits LocalAdd session
→ session becomes durable
→ worker is enqueued
→ History Reset cancels LocalAdd work
→ session remains durable
→ Reset completes
→ no LocalAdd owner is reconstructed
→ durable user responsibility is stranded.

This is the exact R1 sibling/remainder invariant, not a new root.

### Neighboring namespaces

- HardSubScan carriers are explicitly retired with the History graph.
- HistoryDateFetch operation/item authority is captured/deleted under the History Reset contract and has startup reconciliation.
- LocalAdd differs because its durable session survives outside the Room graph.

### Coverage gap

The final 26-method `BackupResetTransactionProductionWiringTest` has no LocalAdd responsibility scenario.

---

## F11-R2 — STILL_OPEN

The new `RestoreMutationAdmission` is a valid shared serialization mechanism for callers that actually use it:

- ordinary writer acquires the process-global mutex, checks Restore admission, performs the durable mutation, then releases;
- Reset active-pointer publication acquires the same boundary before publishing ownership;
- HistoryReferenceMutationCoordinator delegates to the same boundary.

That closes several repository-level TOCTOU paths.

However Checklist-v6 consumer closure is incomplete in at least two material production surfaces.

### R2-A — AndroidX Preference auto-persistence bypass

`BackupSettingsUtil.isPortablePreferenceKey()` makes ordinary settings portable by default except for a narrow exclusion set.

Therefore values such as:

- `use_alarm_for_scheduling`
- `use_scheduler`
- `preferred_download_type`
- `proxy`
- `limit_rate`
- `format_id`
- `format_id_audio`
- `recode_video`
- `compatible_video`

are part of the F11 settings Reset authority graph.

The remediation routes several explicit `SharedPreferences.Editor` writes through `RestoreMutationAdmission.applyOrdinaryPreferences(...)`, but the real AndroidX Preference persistence path remains outside that boundary.

Examples:

- `BaseSettingsFragment` assigns `ListPreference.value = newValue` and `EditTextPreference.text = newValue`; AndroidX persists those changes through its own default preference persistence.
- standard `SwitchPreferenceCompat` / `ListPreference` / `EditTextPreference` values in DownloadSettingsFragment and ProcessingSettingsFragment retain framework auto-persistence.
- `use_alarm_for_scheduling` can be changed through ordinary framework persistence without holding the shared Restore mutation authority.

Thus Reset can publish/commit its authoritative settings image while a framework-owned preference write occurs outside the shared serialization boundary.

The remediation test only invokes `RestoreMutationAdmission.applyOrdinaryPreferences()` directly, so it proves the helper contract, not actual AndroidX writer closure.

### R2-B — ordinary WorkManager handoff carrier writers bypass shared admission

F11 authoritative Reset mutates `workManagerHandoffCarrierDao` directly:

- History Reset deletes outstanding `HARD_SUB_SCAN` carriers;
- Download-category Reset deletes `SCHEDULE_START` / `SCHEDULE_END` carriers;
- ObserveSources Reset deletes outstanding Observe-retry carriers for superseded sources.

Ordinary producers of those same carriers still rely on point-in-time RestoreGate checks rather than the shared admission authority.

Relevant paths include:

- `WorkManagerHandoffRecovery.prepareHardSub()`;
- ordinary `prepareSchedulerBoundary()`;
- `prepareObserveRetryDownload()`;
- ordinary boundary cancellation/mutation paths.

`replaceOutstandingAndInsert()` performs repeated RestoreGate checks, but a context switch after its last check and before its Room mutation still permits:

ordinary caller observes ALLOWED
→ Reset publishes active owner
→ Reset Room transaction deletes authoritative carrier scope
→ ordinary caller resumes
→ ordinary carrier transaction inserts after Reset.

This is the original R2 check→mutation race on a shared durable carrier table.

Adding another gate check is not sufficient. The final ordinary carrier mutation must participate in the same shared ordering as Reset publication.

### Lock-order note

No new AB/BA deadlock was established in the corrected repository paths. A safe completion of R2 should preserve the current order:

`RestoreMutationAdmission`
→ narrow handoff boundary lock where required
→ Room mutation.

Do not hold the shared admission mutex across native/worker/network execution.

---

## F11-R3 — STILL_OPEN

The remediation closes the original direct RestoreGate self-block by carrying an operation-bound `RestoreReconciliationAuthority` through:

`RestoreTransactionCoordinator`
→ `DownloadRepository.startDownloadWorkerForRestore`
→ `AlarmScheduler.scheduleAtForRestore`
→ `WorkManagerHandoffRecovery.prepareSchedulerBoundaryForRestore`.

However exact-alarm publication failure still loses durable convergence until restart.

### Exact failure sequence

1. During RECONCILING, the restore-specific path persists a deterministic scheduler carrier.
2. The carrier has a future `notBeforeAt`.
3. `AlarmScheduler.setAlarm()` attempts `setExactAndAllowWhileIdle(...)`.
4. On exact-alarm publication failure or missing AlarmManager it calls fire-and-forget `ensureConvergenceForRestore(...)`.
5. `performAttempt()` sees that `notBeforeAt` is still in the future.
6. Instead of enqueueing a WorkManager request now with initial delay, it schedules an in-memory delayed retry carrying the Restore-specific authority and returns `RETRYING`.
7. The Restore coordinator does not await a durable accepted successor and may advance to COMPLETE / active-pointer retirement.
8. When the delayed retry wakes, `requireCurrentReconciliationAuthority(...)` fails because the Restore is no longer active in RECONCILING.
9. The exact alarm already failed; the durable handoff carrier remains pending.
10. Generic startup `WorkManagerHandoffRecovery.reconcile()` can later recover it only after a subsequent process/startup pass.

Scheduled responsibility can therefore remain stranded in the current process until restart.

### Existing mechanism already supports a smaller safe fix

`WorkManagerHandoffRecovery.buildRequest()` already maps `carrier.notBeforeAt` to WorkManager `setInitialDelay(...)`.

Therefore the restore-owned exact-alarm failure path can establish and await a delayed WorkManager successor immediately while Restore authority is still valid, rather than waiting in process-local memory until the scheduled wall-clock time.

Any correction must preserve same-operation replay convergence and must not reopen R4.

### Coverage gap

The final Reset test setup forces `use_alarm_for_scheduling=false`.

`acceptedRestoreSchedulingReplaysWithOneCurrentOwner()` therefore validates the WorkManager scheduling branch, not:

`AlarmScheduler.scheduleAtForRestore`
→ exact-alarm publication failure
→ fallback ownership
→ Restore COMPLETE.

---

## F11-R4 — CLOSED

The original process-death duplicate Download reconciliation root is source-fixed.

Final source uses operation-scoped stable restore identities:

- immediate restore work: `DownloadWorker-restore-<operationId>`;
- delayed restore WorkManager work: `scheduledDownload-restore-<operationId>-<startTime>`;
- restore-owned enqueue policy: `ExistingWorkPolicy.KEEP`.

Ordinary user/worker Download triggers retain their existing independent UUID identity / ordinary behavior.

`DownloadWorker.doWork()` retries while RestoreGate is active, so an accepted restore-owned owner does not execute against the partial Reset graph.

Same-operation replay therefore converges on one current unfinished owner rather than creating UUID-distinct sibling work.

R3's alarm-failure liveness issue is a different scheduler-transfer subcase and does not reopen this direct R4 identity fix.

---

## Test fixture correction review

Commit `61304eb6...` changes only `BackupResetTransactionProductionWiringTest`.

The first remediation candidate had a failing managed-source test because its fixture created a KEYWORD_DISCOVERY source with no corresponding enabled AutomaticKeywordRule. Production coverage reconciliation correctly removed that orphan.

The final test commit inserts a real AutomaticKeywordRule with the matching condition key before inserting the managed source.

This is a legitimate regression-contract fixture correction, not an anti-green weakening:

- production semantics were not changed;
- the assertion was not weakened;
- the fixture now represents a valid managed source authority.

---

## Original F11 architecture / prior-closure regression review

No additional blocker was established in these already-accepted F11 boundaries:

- immutable validated RestorePlan before ownership;
- active file-backed operation ownership;
- PREPARED → QUIESCED → FILES_READY → APPLYING → DATA_COMMITTED → RECONCILING → COMPLETE phase model;
- malformed active carrier fail-closed behavior;
- deterministic custom-thumbnail staging/publication;
- one restore-wide Room transaction;
- ambiguous Room-commit replay model;
- explicit SharedPreferences Reset `commit()` plus compensation on durability failure;
- typed Reset outcomes;
- overlapping Reset exclusion;
- no Room schema/version change.

`quiescedWorkTags` is backward-compatible with existing active journals because the field is nullable and missing values are consumed as empty.

### F10 preservation

F10 / `BUG-CLEANUP-01` remains independently CLOSED.

Across the full F11 range:

- `CleanupScheduleCoordinator` production cadence/retry/successor/occurrence semantics are not redesigned;
- `CleanUpLeftoverDownloads` only gains a RestoreGate retry guard during active Reset;
- Cleanup regression changes adapt to typed RestoreOutcome/recovery and allow the same cleanup occurrence to appear in either durable pending or active replay slot;
- no new F10 regression was established.

---

## Runtime evidence classification

Implementation-agent evidence reports:

- final Reset suite 26/26 PASS;
- six backup/restore regression classes PASS;
- Cleanup 69/69 PASS;
- valid isolated F11-scope union: 480 executed = 453 PASS + 27 exact inherited baseline failures + 0 skips + 0 new semantic failures;
- 519-test combined batch infrastructure-invalid after instrumentation crash.

These results remain implementation-agent evidence.

This independent re-review did not execute the Android runtime suites.

The exact inherited 27-method exception remains frozen; no broader exception is created. The prior exact baseline discriminator remains the independent attribution basis. The implementation report says the same method/signature set remained, but that claim is not relabeled as independent execution here.

---

## Final disposition

`F11 / BUG-BACKUP-03`:

**P0 / NOT_CLEAN / REVIEW-REMEDIATION-REQUIRED**

Required next implementation wave is limited to:

1. R1 LocalAdd durable-session responsibility after F11 History quiescence;
2. R2 actual AndroidX Preference persistence plus ordinary WorkManager handoff carrier mutation serialization;
3. R3 exact-alarm publication failure fallback that retains an accepted durable scheduler owner across Restore completion;
4. regression proof that R4 remains closed.

Do not start F12.

Do not modify the canonical root count.

Do not advance `CLEAN_REVIEW_BASIS`.

## Evidence confidence

- Git/ancestry/source conclusions: independently established from exact GitHub objects.
- Runtime test counts: implementation-agent evidence only.
- Independent runtime execution in this re-review: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
