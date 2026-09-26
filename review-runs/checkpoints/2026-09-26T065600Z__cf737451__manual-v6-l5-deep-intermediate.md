# Manual correctness review checkpoint — cf737451 — INTERMEDIATE

checkpoint_kind: INTERMEDIATE
run_mode: manual_trigger_3
review_parent_sha: 337eacdb219538781a6c03ad741d1f8db9bd770f

## Frozen basis

- implementation_sha: cf7374510decad9f308fdc3f1b528e731a3ea4f1
- plan_sha: 2145847a1054da28398b730b9be0ca728668f967
- ledger_sha: 899328bc91e4008e39a658387396a0106c8666ec
- Master Plan blob: 507a97c1455793b272298e29f37b945f4cfb55d7
- Checklist v6 blob: 7b553328dfcd9941d783658f49ecb16c71b98c56
- SOURCE_ARTIFACTS blob: bdee5f5efeee81426433c64ceb975208dc41a299
- TASKS blob: fae11a65fc7fe1e58bd725d64355ce4c242f398c
- TASKS_DELTA blob: 96869f414efe6c3c33d4586eecf08cccb18375cc
- CURRENT_STATUS blob: 0dd569290dfc209763b4f94c2041bad492641d44

## Same-SHA lens progression

Previous current-SHA coverage:
- L1 DEEP
- L2 DEEP
- L3 DEEP
- L4 BASELINE
- L5 BASELINE
- L6 DEEP

This run promotes:
- L5 Platform contract closure -> DEEP

Primary reason:
the open Terminal/backup findings cross Android SAF permission, SharedPreferences, WorkManager foreground, NotificationManager, Room and provider-publication platform boundaries.

## Platform basis

- minSdk: 24
- compileSdk: 36
- targetSdk: 36
- WorkManager: 2.11.0
- Room: 2.8.4
- manifest declares FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC
- WorkManager SystemForegroundService is declared with foregroundServiceType=dataSync
- Terminal worker supplies FOREGROUND_SERVICE_TYPE_DATA_SYNC on API 33+

## Existing P2 — BUG-TERMINAL-03 remains OPEN

No status change.

Current-format provider authority remains durably bound before asynchronous handoff.

Legacy same-schema Terminal state remains compatible at the Room level but semantically incomplete because original provider authority may be absent. Platform exactness of a later provider URI cannot repair missing historical provenance.

## Existing newly discovered P2 — provisional BUG-BACKUP-11 remains confirmed

Title:
Do not restore Terminal destination provider strings without restoring or validating provider authority.

L5 strengthens the finding.

### Platform contract matrix

Normal settings producer:
- API: ACTION_OPEN_DOCUMENT_TREE
- requested flags: READ, WRITE, PERSISTABLE
- accepted result: exact content URI
- authority establishment: takePersistableUriPermission()
- durable locator: SharedPreferences command_path

Backup representation:
- serializes only SharedPreferences key/type/value
- command_path is currently treated portable
- no provider grant/provenance dimension exists in the backup schema

Restore:
- BackupRestoreParser validates type only
- SettingsViewModel merge restore writes the URI string
- RestoreTransactionCoordinator reset restore writes the URI string
- neither path calls takePersistableUriPermission for the restored URI
- no matching persistedUriPermissions proof is required before restore success

Terminal consumer:
- TerminalViewModel reads configured command_path
- TerminalDestinationAuthority.classify treats syntactically valid content:// authority as ProviderTree
- TerminalCommandIntentMaterializer embeds that URI into durable Terminal command
- row/carrier/fingerprint/WorkRequest then faithfully preserve that unproved locator

Provider boundary:
- actual provider usability is tested only later by DocumentFile/FileUtil publication paths
- therefore restore success and durable Terminal admission can precede discovery that destination-side authority is absent

Conclusion:
URI locator and persistable provider authorization are separate platform dimensions. Backup/restore preserves the first and omits the second while still declaring the setting portable.

P2 remains confirmed.

### Existing test gap

TerminalSafDestinationAuthorityProductionWiringTest sets content:// strings directly into SharedPreferences. It correctly verifies raw-path vs provider planning, but does not establish or assert a real persisted URI grant.

BackupSettings tests explicitly assert cache_path is non-portable but contain no equivalent command_path destination-authority case.

No focused backup -> restore -> grant-state -> new Terminal instrumentation test exists in the reviewed tree.

## Notification / foreground candidate

Current notification families:
- Download running ID = 90000 + downloadId
- Terminal foreground/running ID = 99000 + terminalId

Concrete arithmetic collision:
- Download 9001 -> 99001
- Terminal 1 -> 99001

Current pinned source shows:
- Download progress uses NotificationManager.notify(downloadRunningNotificationId(...))
- Download cleanup uses cancelRunningDownloadNotification(...)
- Terminal ForegroundInfo uses terminalNotificationId(...)
- Terminal progress uses NotificationManager.notify(terminalNotificationId(...))
- Terminal cleanup uses cancelTerminalDownloadNotification(...)

Thus one integer notification namespace is not globally disjoint.

Confirmed source effect:
two independent operations can address the same system notification integer.

What remains NOT_VERIFIED from repository evidence alone:
- whether WorkManager/SystemForegroundService foreground ownership is semantically lost or merely visually replaced for every supported Android version;
- whether a colliding cancel causes platform-level worker termination or only notification removal/update;
- whether this reaches P0/P1/P2 severity under the Master Plan.

Do not promote this candidate without exact supported-version/platform evidence.

Historical BUG-TERMINAL-02 destructive same-ID process/cancel path remains rejected because typed process identities and feature-specific receivers remain separate.

## Module A — platform capability/admission

Triggered by:
- API-gated ForegroundInfo service type
- POST_NOTIFICATIONS / notification channels
- SAF provider grants
- WorkManager foreground service

Source-level manifest/wiring:
- foreground service permissions and dataSync type are present
- TerminalActivity and cancellation receivers are non-exported where expected
- Terminal uses immutable PendingIntent for activity/cancel capability
- no new manifest admission blocker confirmed for current Terminal remediation

Focused runtime verification across supported API bands is NOT EXECUTED.

## Module C — external authority projection

FAIL for BUG-BACKUP-11:
the backup schema represents command_path as a String but omits destination-side grant scope/provenance.

A syntactically valid restored URI is therefore not proof of semantic authority.

## Module B — scheduler handoff

Current-format Terminal row/carrier/request identity remains source-semantically exact.

No new WorkManager handoff finding confirmed in L5 so far.

## L5 effectiveness — provisional

- coverage_level: DEEP
- new P0/P1/P2 first discovered this run: 0
- carried newly confirmed finding: BUG-BACKUP-11 P2
- existing blocker: BUG-TERMINAL-03 P2
- confirmed platform residual: notification integer namespace collision
- notification blocker severity: NOT_VERIFIED
- exact CI/check execution: pending verification
- checklist gaps: 0

## Remaining scope

- re-run L1/L2/L3/L4/L6 gates for this invocation
- refresh exact GitHub status/check-run evidence
- refresh fault/cross-attempt matrices
- verify no canonical duplicate owns BUG-BACKUP-11
- fresh-fetch production/review heads
- append and verify FINAL checkpoint
