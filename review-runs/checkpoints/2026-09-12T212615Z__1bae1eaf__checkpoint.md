# Independent correctness review final checkpoint

- Exact implementation SHA: `1bae1eafea08942f11e6df30e4a13a515dda621c`
- Frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review/remediation start SHA: `6d84ec6cf65c9226624c83814d5c261ba00e07c7`
- Frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Review completed

- Fresh-fetched all four requested branch heads at run bootstrap and froze the exact implementation/governance SHAs.
- Used Master Plan F4/F5 as the severity/invariant authority and v6 as the operational checklist.
- Re-traced current production source rather than diff-only:
  - all reviewed backup entry points;
  - `SettingsViewModel.backup()` and `backupInternal()`;
  - category capture and required thumbnail capture;
  - staging pathname creation/write;
  - exact-source move publication;
  - `FileUtil.moveFileWithResult()` through direct/SAF/MediaStore move handling and returned publication path;
  - restore custom-thumbnail validation/staging, destination History ID allocation, live publication, DB binding and cleanup.
- Re-opened exact implementation commit test source and checked current GitHub execution metadata.
- Final recount re-fetched production, Plan and ledger heads: implementation remained `1bae1eafea08942f11e6df30e4a13a515dda621c`, Plan remained `fada33a7eed86b1fa2c07065af66f14bf4d24714`, ledger remained `899328bc91e4008e39a658387396a0106c8666ec`.

## Final blocker recount

- P0: 2
- P1: 1
- P2: 29
- Overall gate: `NOT_CLEAN`
- New P0/P1/P2 finding: none
- Canonical status/count change from the immediately preceding review: none (`no material change`).

## Confirmed fixed invariants

### `BUG-BACKUP-04` partial fix retained

The old stale-sibling widening path remains fixed: the current operation passes exactly `sourceFiles = listOf(saveFile)` to `moveFileWithResult()`, and the returned paths/failures belong to that invocation. A pre-existing sibling in the `Backups` directory is not intentionally published by the current operation.

### `BUG-BACKUP-02` source-semantic fix retained

Restore custom thumbnails still satisfy the source-level F5 correction shape: collision-resistant UUID staging, no live binding before destination History identity, destination-local UUID filename, exact DB binding after publication, deletion of the published file if binding fails, and staging cleanup. The historical deterministic old-backup-ID live path is absent.

## Open finding

### `BUG-BACKUP-04` — EXISTING / P1 / OPEN

Exact production evidence is unchanged from the preceding review. `backupInternal()` builds its staging filename from version + calendar fields only through seconds. If the pathname exists, it deletes that pathname, then `createNewFile()` and `writeText()` are used. No reviewed production boundary serializes `backup()` invocations or gives each invocation a unique staging identity. Multiple callers can invoke backup independently.

Concrete source interleaving:

1. A and B begin during the same wall-clock second and derive the same `saveFile` pathname.
2. A creates that file.
3. B sees it as existing, deletes it, and recreates the same pathname.
4. B writes its payload.
5. A later publishes through its `File` object, which denotes the same pathname; A can therefore publish B's payload or fail because of B's lifecycle instead of publishing A's exact captured artifact.

This violates the existing F4 final write/move correctness obligation plus v6 exact identity/provenance and concurrency/exact-ownership requirements. It remains a residual subcase of the existing canonical `BUG-BACKUP-04`, not a new finding, so the count does not increase.

Affected production files/paths re-reviewed:
- `app/src/main/java/com/ireum/ytdl/database/viewmodel/SettingsViewModel.kt`
- `app/src/main/java/com/ireum/ytdl/ui/more/settings/MainSettingsFragment.kt`
- `app/src/main/java/com/ireum/ytdl/MainActivity.kt`
- `app/src/main/java/com/ireum/ytdl/ui/more/settings/updating/UpdateSettingsFragment.kt`
- `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt`

Current exact-SHA test addition (`BackupMoveResultProductionWiringTest`) checks invocation-local move-failure isolation, but it does not create two same-second overlapping `backup()` invocations and therefore does not close this source interleaving.

## Execution / NOT_VERIFIED

- Commit status contexts for exact implementation SHA: 0.
- Check-runs for exact implementation SHA: 0.
- Associated GitHub Actions workflow runs for exact implementation SHA: 0.
- Independent runtime/instrumentation execution in this review: NOT EXECUTED.
- Runtime manifestation of the same-second `BUG-BACKUP-04` interleaving: NOT_VERIFIED by execution; source semantics are sufficient to keep the existing P1 open.
- `BUG-BACKUP-02` remains source-semantic FIXED but canonical OPEN where exact-SHA execution closure is still required by the governing review history.

## Checklist evolution

No new canonical finding was established, and the existing residual is detectable under current v6 rules (exact identity/provenance, concurrency/exact ownership, final filesystem mutation authority, consumer/effect closure). Therefore there is no new `Checklist gap` or `Proposed checklist change` in this run.

## Exact upstream semantic basis

- Master Plan F4 / `BUG-BACKUP-04`: genuinely empty capture must be distinguished from capture failure; selected capture failure prevents successful artifact creation; final backup write/move failure is part of the required test/correctness boundary.
- Master Plan F5 / `BUG-BACKUP-02`: collision-resistant no-overwrite staging; bind only during mapped History insertion; staged-artifact cleanup on failure; no old-ID deterministic overwrite path.
- v6 checklist blob `7b553328dfcd9941d783658f49ecb16c71b98c56`: positive live authority; discovery is not mutation authority; destructive/durable identity preserves semantic granularity and provenance; sibling isolation; concurrency and exact ownership; material semantic-contract consumer/effect closure; CLEAN requires semantic closure plus required actual execution evidence.
- Oracle Java `java.io.File.createNewFile()`: existence check + create are atomic only for that create operation, and Oracle explicitly warns not to use it as a file-locking protocol.
- Kotlin stdlib `File.writeText`: sets file contents and overwrites an existing file.

## Final disposition before verdict

- `BUG-BACKUP-04`: EXISTING / P1 / OPEN, unchanged.
- `BUG-BACKUP-02`: EXISTING / P2 / source-semantic FIXED / canonical OPEN / execution NOT_VERIFIED, unchanged.
- New canonical finding: none.
- Final gate: `NOT_CLEAN — P0 2 / P1 1 / P2 29`.
- Material change: none.
