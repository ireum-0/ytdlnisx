# BackupPaused provider-URI regression harness classification during F11 fourth-wave stop

Date: 2026-09-21

Authoritative implementation HEAD:
`70e5e016155df8c14aa96984f08624957c11afe4`

Fourth-wave local-only candidate reported by implementation agent:
`3b4ab56c6f902b6c5e5917428cb70e0a1466113a`

Reported local range:
`70e5e016155df8c14aa96984f08624957c11afe4..3b4ab56c6f902b6c5e5917428cb70e0a1466113a`

Remote implementation remained unchanged; local candidate source is therefore NOT GitHub-authoritative and is not independently reviewed in this checkpoint.

Governing F11 checkpoint:
`a2ebe22efeeeabfe7d063016952f0dd7a4f97d11`

F8 / BUG-BACKUP-05 independent closure:
`review-runs/checkpoints/2026-09-13__a9586835__backup05-independent-closure.md`

## Reported stop evidence

Implementation-agent execution on exact local candidate `3b4ab56c...` reported:

- `BackupPausedProductionWiringTest`: 4 discovered / 4 executed / 1 failed;
- failing method: `allCategoryBackupIncludesPausedPayload`;
- failure: `FileNotFoundException: content:/media/external_primary/downloads/35 open failed: ENOENT`;
- later gates short-circuited;
- no push occurred.

This runtime result is implementation-agent evidence only.

## Independent exact-source classification

Verdict:

**TEST-HARNESS / REGRESSION-CONTRACT DEFECT — NOT an F11-R2 semantic failure, NOT a reopened F8 production defect, canonical blocker-count delta 0.**

### 1. The exact current test assumes a raw filesystem path

At authoritative remote `70e5e016...`, `BackupPausedProductionWiringTest.kt` blob is:

`c8137b5f26338c48a92824e42b1940c69daac2e8`.

Both:
- `pausedBackupAndRestorePreserveStateWithoutStartingWork`; and
- `allCategoryBackupIncludesPausedPayload`

store `SettingsViewModel.backup(...).getOrThrow()` in `publishedBackup` and then read it using:

`File(publishedBackup!!).readText()`.

Teardown similarly uses:

`File(it).delete()`.

That assertion/cleanup contract is valid only when the returned publication identity is a raw filesystem path.

### 2. Exact current production deliberately returns provider identities

At the same authoritative SHA:

`SettingsViewModel.backupInternal(...)`
- writes an operation-local staging JSON file;
- publishes it via `FileUtil.moveFileWithResult(...)`;
- returns `moveResult.paths.firstOrNull()`.

`FileUtil.moveFile(...)`:
- attempts direct raw-file publication only when the destination is directly writable;
- for a primary shared-storage destination that is not directly writable, publishes through MediaStore;
- returns the exact provider URI from the MediaStore reservation/publication boundary.

`FileUtil` explicitly documents that a published SAF/MediaStore path is an exact content URI and must not be handed to `java.io.File`.

With no `backup_path` preference, `getBackupPath()` resolves under public Downloads/YTDLnisX/Backups. On scoped-storage environments this can legitimately publish through MediaStore and return `content://media/...`.

Therefore a successful backup result of `content://media/external_primary/downloads/<id>` is a valid production outcome, not evidence that paused payload capture failed.

### 3. The reported exception matches the invalid harness assumption exactly

The reported runtime failure is a `FileNotFoundException` against:

`content:/media/external_primary/downloads/35`

while the test is constructing `File(publishedBackup!!)`.

That converts the URI string into a filesystem pathname-like object and loses provider authority. The resulting ENOENT is the expected failure mode of the stale harness contract.

### 4. The test predates the current provider-aware publication contract

The F8 closure at `a9586835...` recorded `BackupPausedProductionWiringTest` 4/4 PASS and established the paused payload production invariant.

The paused test already used raw `File(...)` access in that closure-era blob.

At `70e5e016...`, the test still uses the same raw-path assumption, while current `FileUtil` preserves provider-returned URIs as exact publication identities.

Thus the regression contract became stale relative to the stronger provider-aware publication semantics.

### 5. F8 remains CLOSED

This failure does not contradict the F8 semantic assertions:
- all-category backup includes paused payload;
- paused metadata/order survive capture/restore;
- paused restore does not start work;
- Reset paused selection does not delete queued siblings;
- repeated Merge creates independent rows.

The failure occurs before the test can inspect JSON payload semantics because the harness opens the returned publication identity incorrectly.

Do not reopen `BUG-BACKUP-05`.

### 6. F11-R2 remains unreviewed on the local candidate

The fourth-wave local production commits are not pushed and were not inspected here.

Implementation-agent focused PASS results on `3b4ab56c...` are evidence only.

This checkpoint does NOT close F11-R2 and does NOT advance CLEAN basis.

## Authorized minimal harness correction

A continuation wave may modify only `BackupPausedProductionWiringTest.kt` (and a test-only shared helper only if exact reuse is already present and strictly narrower).

Required correction:
- read the backup publication identity provider-aware:
  - `content://` through `ContentResolver.openInputStream(Uri.parse(path))`;
  - raw path / file URI through the corresponding filesystem path;
- cleanup must likewise support provider identities;
- keep the test's default `backup_path` behavior; do NOT force an app-private/raw destination merely to make `File(...)` work;
- do NOT change production source;
- do NOT weaken paused payload assertions;
- do NOT special-case the failing test to skip content URIs;
- do NOT turn provider publication back into raw-path publication.

The corrected test should continue to prove the same F8 semantics while becoming transport-representation-neutral.

## Required continuation verification

On the preserved local candidate, after a separate test-only forward commit:

1. rerun the exact previously failing `BackupPausedProductionWiringTest` class;
2. require 4/4 PASS with nonzero execution;
3. if valid, resume the fourth-wave neighboring/broader verification from the stopped point;
4. preserve the original 1/4 failure evidence;
5. any new valid semantic failure still short-circuits;
6. final closure-grade evidence must be on the exact committed candidate that would be pushed.

If another backup test fails for the same raw-`File`-on-provider-URI harness assumption, STOP and report it for same-root harness classification rather than broadening production changes.

Canonical count delta: `0`.

CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`

INDEPENDENT EXECUTION: NOT EXECUTED
