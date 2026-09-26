# Manual correctness review checkpoint — cf737451 — INTERMEDIATE

checkpoint_kind: INTERMEDIATE
run_mode: manual_trigger_3
review_parent_sha: 0677d34516317244f0a93d8540ea91438ff6832f

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

## Lens progression
Previous: L1 DEEP, L2 DEEP, L3 DEEP, L4 BASELINE, L5 BASELINE, L6 BASELINE.
This run: L6 Cross-feature semantic propagation -> DEEP.

## Scope reviewed
- FolderSettingsFragment command_path picker and persistable grant
- TerminalActivity / TerminalFragment / TerminalDownloads UI
- TerminalViewModel / TerminalDao / TerminalItem
- Terminal provider materializer, typed destination, planner
- dispatch row/carrier/fingerprint/request/startup/retry path
- FileUtil provider publication
- BackupSettingsUtil
- BackupRestoreParser
- SettingsViewModel backup/merge restore
- RestoreTransactionCoordinator reset restore
- RestoreAppDataItem

## Existing P2
BUG-TERMINAL-03 remains OPEN.

Current-format provider authority is bound into command -> dispatch carrier -> fingerprint -> WorkRequest and later preference changes do not replace it.

Legacy same-schema Terminal state can still lack original provider authority. Settings restore is an additional same-root re-entry: command_path can change while legacy Terminal state itself is not part of the backup payload. This is not a new root.

## New confirmed P2 — provisional BUG-BACKUP-11
Title: Do not restore Terminal destination provider strings without restoring or validating provider authority.

Exact implementation SHA:
cf7374510decad9f308fdc3f1b528e731a3ea4f1

Violated invariant:
Portable restore must not reconstruct executable external authority from a locator string alone when the destination installation has not independently established that authority.

Evidence:
1. FolderSettingsFragment selects command_path through ACTION_OPEN_DOCUMENT_TREE and calls takePersistableUriPermission before storing the provider URI.
2. BackupSettingsUtil excludes cache_path as destination-local but does not exclude command_path; backupSettings serializes command_path.
3. BackupRestoreParser retains command_path because it is classified portable and validates only preference value type.
4. SettingsViewModel merge restore and RestoreTransactionCoordinator reset restore commit the imported command_path string without re-performing the Folder-picker grant step or proving a matching destination grant.
5. A later TerminalViewModel.insert reads that imported value.
6. TerminalCommandIntentMaterializer and TerminalDestinationAuthority accept a syntactically valid provider URI and bind it into the new durable Terminal command/carrier/fingerprint.
7. Actual provider usability is deferred until FileUtil publication. The restore can therefore report success while future Terminal execution carries unproved destination authority and later fails at publication.

Affected files:
- BackupSettingsUtil.kt
- BackupRestoreParser.kt
- SettingsViewModel.kt
- RestoreTransactionCoordinator.kt
- FolderSettingsFragment.kt
- TerminalViewModel.kt
- TerminalCommandIntentMaterializer.kt
- TerminalDestinationAuthority.kt
- TerminalCommandPlan.kt
- FileUtil.kt

Disposition:
New confirmed P2. Pre-existing backup/settings portability defect surfaced by L6 DEEP. The cf737 provider-binding change consumes and durably propagates the restored value but did not create the original restore behavior. Introducing commit is NOT_VERIFIED.

Why previous review missed it:
Prior current-SHA runs focused on Terminal dispatch/recovery/process identity. L6 had not yet been DEEP and the backup/restore producer of command_path had not been followed into Terminal execution.

Checklist gap:
none.

Proposed checklist change:
none. Existing L6 plus Module C/H already require following executable configuration through import/restore producers and authority consumers.

Required correction:
Treat provider-backed command_path as destination-local, preserving destination-side configuration during generic restore, or restore it only as non-authoritative pending configuration and require independent destination grant validation before execution.

Required regression:
- backup contains provider-backed command_path; destination lacks matching grant -> restore must not make it executable authority
- destination already has valid B -> generic restore must not replace it with unproved A
- destination reauthorizes A -> later new Terminal may bind A
- reset and merge restore controls
- active current-format Terminal keeps already-bound authority
- legacy Terminal control remains owned by BUG-TERMINAL-03

## Additional L6 observation
Auto-materialized provider metadata is stored inside TerminalItem.command. TerminalDownloadsAdapter renders SensitiveTextRedactor.redactCommand(item.command), and the redactor does not remove this Terminal-owned option. Internal provider metadata can therefore appear in active-list UI. Correctness severity is NOT_VERIFIED; not promoted to P0/P1/P2.

## Coverage / effectiveness
- L1 DEEP
- L2 DEEP
- L3 DEEP
- L4 BASELINE
- L5 BASELINE
- L6 DEEP

L6:
- new confirmed P0/P1/P2: 1
- new P2: provisional BUG-BACKUP-11
- existing finding status changes: 0
- existing residual: BUG-TERMINAL-03
- checklist gaps: 0

## Remaining scope
- re-run L1-L5 gates this invocation
- refresh terminal/cross-attempt matrices
- exact-SHA status/check evidence
- duplicate check against canonical findings
- fresh-fetch heads before FINAL
- append and verify FINAL checkpoint
