# Backup provider-URI test-family harness consolidation addendum

Date: 2026-09-21

Parent classification checkpoint:
`9e75b7902be5be9c1cbc32b27ab7c031809e16dc`

Authoritative implementation HEAD:
`70e5e016155df8c14aa96984f08624957c11afe4`

Fourth-wave local-only candidate remains implementation-agent evidence only:
`3b4ab56c6f902b6c5e5917428cb70e0a1466113a`

## Purpose

Static inventory after the initial `BackupPausedProductionWiringTest` classification found the same provider-identity harness defect in a small existing backup test family.

This addendum authorizes one test-only harness consolidation rather than waiting for the same already-proven stale assumption to fail one class at a time.

Canonical blocker-count delta: `0`.

No production defect is reopened.

## Exact current same-root inventory

At authoritative remote `70e5e016...`:

### 1. BackupPausedProductionWiringTest

Blob:
`c8137b5f26338c48a92824e42b1940c69daac2e8`

Setup removes `backup_path`.

The class reads successful backup results via:
- `File(publishedBackup!!).readText()`

and cleans them via:
- `File(it).delete()`.

This is the already-confirmed failing harness.

### 2. BackupPreferenceProductionWiringTest

Blob:
`d659375249492a0d433f862fe9d2a7049ab9b184`

Setup removes every key in its test key set, including `backup_path`.

The class reads successful backup results via raw `File` assumptions, including:
- `publishedBackup?.let(::File)`;
- `File(publishedBackup!!).readText()`;

and deletes via `File(it).delete()`.

It is therefore subject to the same valid `content://` publication representation.

### 3. BackupSettingsProductionWiringTest

Blob:
`0fab95de94f4a36b845cc5d10db4cc2792bc3d1f`

Setup explicitly removes `backup_path`.

Multiple tests assert/read/delete successful backup results using raw `File` construction, including:
- `result.getOrNull()?.let(::File)`;
- `File(downloadsPath!!).readText()`;
- `File(keywordPath!!).readText()`;
- `published?.let(::File)?.isFile`;
- `File(published!!).readText()`;
- `File(it).delete()`.

These are the same stale transport-representation assumption.

### 4. BackupPlaylistProductionWiringTest is NOT same-root

It deliberately sets `backup_path` to an app-owned raw directory under `getExternalFilesDir(null)`.

Its raw `File` assertions are consistent with that explicit test precondition and are not authorized for change by this consolidation.

### 5. Other reviewed Backup* classes

The inspected `BackupRestoreIdentityProductionWiringTest` and `BackupRestoreThumbnailProductionWiringTest` do not expose this returned-backup-path raw-`File` pattern in the reviewed surface.

Do not broaden beyond exact proven same-root occurrences.

## Governing current production contract

`SettingsViewModel.backup()` returns the exact published destination identity.

That identity may be:
- a raw filesystem path;
- a file URI;
- a SAF/MediaStore `content://` URI.

The exact provider identity is semantically intentional. Tests must be representation-neutral unless they explicitly establish a raw-path destination as their precondition.

## Authorized consolidation boundary

Test-only changes are authorized in:

- `BackupPausedProductionWiringTest.kt`
- `BackupPreferenceProductionWiringTest.kt`
- `BackupSettingsProductionWiringTest.kt`

A tiny shared androidTest-only helper may be introduced only if it strictly centralizes:
- provider-aware read;
- provider-aware existence/open;
- provider-aware cleanup;

and has no production use.

Required behavior:
- `content://`: use `ContentResolver` / exact URI;
- `file://`: resolve the URI path safely;
- raw path: ordinary `File` access;
- preserve exact returned publication identity;
- preserve all existing semantic assertions.

Prohibited:
- changing production source;
- setting `backup_path` to app-private/raw just to avoid provider publication;
- converting a `content://` identity into a guessed filesystem path;
- skipping assertions when the result is a provider URI;
- weakening F8/F4/F6/F7/F9 backup semantics;
- changing `BackupPlaylistProductionWiringTest`;
- broad refactor of backup tests.

## Verification

After one separate test-only forward commit on the preserved local candidate:

1. `BackupPausedProductionWiringTest` full class: require PASS/nonzero;
2. `BackupPreferenceProductionWiringTest` full class: require PASS/nonzero;
3. `BackupSettingsProductionWiringTest` full class: require PASS/nonzero;
4. preserve the original paused 1/4 failure as historical evidence;
5. then continue the F11-R2 fourth-wave focused/neighboring/broader gates on the exact final committed candidate;
6. because the candidate SHA changes after the harness commit, final closure-grade F11 execution must be on that exact final SHA rather than relying solely on earlier `3b4ab56c...` PASS results;
7. any new valid semantic failure short-circuits.

If static local inspection shows any of the three expected test blobs were already modified in the local fourth-wave candidate, STOP and report before applying this consolidation.

F8 / BUG-BACKUP-05 remains CLEAN/CLOSED.

F11-R2 remains pending independent exact-source review after a successful push.

INDEPENDENT EXECUTION: NOT EXECUTED
