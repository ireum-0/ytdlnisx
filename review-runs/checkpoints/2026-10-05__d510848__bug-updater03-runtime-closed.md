# BUG-UPDATER-03 — d510848 exact-SHA completion review

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO

review_parent_sha: 695ea8713484bdccd5ae9c03d7c073602e26929b
implementation_parent_sha: 39f971cdca7712e60046dbdf346847ce9c74924a
implementation_sha: d510848904af427ee4a837f791e779791fdd23e0
overall_verdict: BUG_UPDATER_03_CLOSED
canonical_p0: 0
canonical_p1: 0
canonical_p2: 1
canonical_count_change: -1
canonical_open_roots: BUG-HISTORY-05
clean_review_basis_advance: NO
independent_execution: NOT_EXECUTED

## History and scope

39f971cdca7712e60046dbdf346847ce9c74924a..d510848904af427ee4a837f791e779791fdd23e0
is one normal forward commit:
- ahead: 1
- behind: 0
- total commits: 1.

Changed files are confined to BUG-UPDATER-03 production and regression scope:
- BackupRestoreParser.kt
- RestoreTransactionCoordinator.kt
- UpdateUtil.kt
- App.kt
- UpdateSettingsFragment.kt
- BackupSettingsProductionWiringTest.kt
- BackupResetTransactionProductionWiringTest.kt
- UpdateUtilProductionWiringTest.kt
- UpdateUtilProvenanceMigrationTest.kt
- UpdaterPreferenceRestoreSchemaTest.kt

No manifest, dependency, DB schema, unrelated feature, or build-harness change is present in the implementation range.

## Independent source-semantic review

### New admission

BackupRestoreParser.validateSettings() now binds destination updater keys to destination schema:
- ytdlp_source requires String and the same nonblank predicate exposed by UpdateUtil;
- auto_update_ytdlp requires Boolean and generic Boolean value validation still applies;
- ytdlp_source_label requires String.

The validation remains in the shared normalization path used by typed/current/legacy representations, so new Merge/Reset input is rejected before mutation/ownership rather than only at one writer.

### Already-owned historical Reset compatibility

RestoreOperationStore.load() proves the current durable owner before compatibility adaptation:
- active pointer;
- structurally valid operation identity/directory;
- matching journal owner;
- recognized phase;
- raw plan bytes;
- raw digest matching both pointer and journal.

Only after that proof does BackupRestoreParser.recoverOwnedResetPlan() derive a sanitized effective plan. The raw plan bytes and planDigest are not rewritten.

The compatibility path still runs generic value/type, metadata, capability and normalized payload checks. It relaxes only the BUG-UPDATER-03 updater destination-schema cells historically admitted by generic validation.

The effective image removes malformed updater intent rather than coercing it:
- invalid source becomes absent/default-source intent and its label is removed;
- invalid auto-update becomes absent;
- invalid label becomes absent;
- valid updater settings and unrelated plan content remain.

### Durable phase recovery

RestoreTransactionCoordinator drives the sanitized/effective plan under the existing active Reset owner.

For FILES_READY/APPLYING, updater repair occurs under Restore mutation ownership before preference publication and the sanitized plan is then published.

For DATA_COMMITTED, RECONCILING and COMPLETE, updater repair is performed under the active owner without reapplying committed Room data. The owner is retained until durable repair/reconciliation succeeds.

Preference repair reloads/validates the active owner before mutation and preserves the existing Restore gate, so ordinary mutation cannot bypass unresolved historical ownership.

### Persisted malformed destination recovery

UpdateUtil performs raw SharedPreferences inspection before typed reads and repairs:
- wrong-type or blank source;
- wrong-type auto-update;
- wrong-type source label.

Malformed source is a composite-invalid identity:
- source and stale label are removed;
- committed/pending proof is retired;
- destination desired generation is canonically rebased to 1 even when the invalid composite carried current-domain Long.MAX_VALUE.

Valid-source current-domain Long.MAX_VALUE remains outside that repair branch and remains exhausted/fail-closed.

Failed SharedPreferences publication remains untrusted through the unconfirmed-repair tracking path, so same-process retry and restart rediscovery cannot treat a memory-only repair as durable.

### Consumer ordering

App startup invokes persisted updater recovery before defaults/native runtime initialization after Restore recovery/scheduler gating.

UpdateUtil.desiredSource() repairs before its typed source capture.

UpdateSettingsFragment drives pending Restore and persisted updater repair before preference XML inflation/direct String reads.

The authoritative consumers therefore no longer become first owners of malformed stored types.

## Regression contract review

The new/expanded tests cover:
- current typed and supported serialized restore representations;
- strict rejection and exact acceptance preservation;
- Merge/Reset pre-mutation rejection;
- persisted wrong-type/blank recovery;
- failure/restart/idempotency;
- malformed-source Long.MAX_VALUE composite recovery;
- valid-source Long.MAX_VALUE negative control;
- historical active Reset carrier integrity;
- immutable plan/digest preservation;
- every durable Restore phase, including APPLYING publication ambiguity;
- post-commit no-Room-reapply behavior;
- owner retention on repair failure;
- duplicate updater-key last-writer behavior.

At exact d510848 the three required complete AndroidTest classes contain:
- BackupSettingsProductionWiringTest: 12 @Test methods, 0 @Ignore;
- BackupResetTransactionProductionWiringTest: 33 @Test methods, 0 @Ignore;
- UpdateUtilProductionWiringTest: 39 @Test methods, 0 @Ignore;
- total intended complete-class tests: 84.

This independently matches the reported exact-SHA complete-class result of 84 executed / 84 PASS / 0 FAIL / 0 skipped, so no required class can have had zero execution.

## Implementation-agent verification evidence

Reported for the published exact SHA d510848904af427ee4a837f791e779791fdd23e0:
- focused JVM: 48 PASS;
- focused runtime: 12 PASS;
- production and complete AndroidTest compilation: PASS;
- build/artifact identity checks: PASS;
- exact-SHA required complete classes: 84 PASS / 0 FAIL / 0 skipped;
- worktree clean;
- index empty;
- protected evidence preserved.

Sealed report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater03-storage-resume-20261005-8b9b2e10/BUG_UPDATER_03_IMPLEMENTATION_EXACT_SHA_REPORT.md

The local sealed report was not independently opened from GitHub. Runtime counts are implementation-agent evidence; exact GitHub history, source semantics and test inventory were independently reviewed.

## Disposition

BUG-UPDATER-02: CLOSED.
BUG-UPDATER-03: CLOSED P2.
BUG-HISTORY-05: OPEN P2.

Canonical P2 count:
2 -> 1

No new root or same-root BUG-UPDATER-03 residual is established by this completion review.

CLEAN_REVIEW_BASIS does not advance because BUG-HISTORY-05 remains open.

Next governed action:
derive/reuse the canonical BUG-HISTORY-05 correction from the latest governing review evidence, persist a launch-ready implementation prompt, and continue correctness remediation.

INDEPENDENT EXECUTION: NOT EXECUTED
