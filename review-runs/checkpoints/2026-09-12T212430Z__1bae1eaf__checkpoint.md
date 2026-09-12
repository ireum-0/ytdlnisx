# Independent correctness review checkpoint

- Run checkpoint: semantic intermediate
- Exact implementation SHA: `1bae1eafea08942f11e6df30e4a13a515dda621c`
- Frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review/remediation start SHA: `6d84ec6cf65c9226624c83814d5c261ba00e07c7`
- Frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist: v6 blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Review scope completed

- Re-traced current backup production source end-to-end rather than reviewing only the commit diff:
  - manual settings backup caller;
  - automatic update backup caller;
  - update-settings backup caller;
  - `SettingsViewModel.backup()` / `backupInternal()`;
  - selected category capture and required thumbnail reads;
  - shared `Backups` staging pathname construction;
  - exact `sourceFiles = listOf(saveFile)` publication;
  - `FileUtil.moveFileWithResult()` and underlying direct/provider publication path;
  - operation-local move failure result and returned destination path.
- Re-traced restore custom-thumbnail path from payload validation through UUID staging, destination History identity allocation, live publication, DB binding and cleanup.
- Checked current exact-SHA GitHub execution metadata: status contexts 0, check-runs 0, associated workflow runs 0.
- Opened exact Master Plan F4/F5 text and v6 identity/concurrency/final-mutation rules.

## Provisional blocker state

- P0: 2
- P1: 1
- P2: 29
- Canonical count delta: 0
- New P0/P1/P2 finding: none

## Confirmed fixed invariants

### `BUG-BACKUP-04` partial fix remains present

The prior stale-sibling widening bug remains repaired: current backup passes only the exact current `saveFile` as `sourceFiles`, and `moveFileWithResult()` returns invocation-local paths/failures. A stale sibling in the same staging directory is not intentionally enumerated as current backup output.

### `BUG-BACKUP-02` source-semantic fix remains present

Current restore thumbnails use a UUID-scoped staging root, allocate destination History identity before live publication, reserve a destination-local `history_<destinationId>_<UUID>.<ext>` file, bind only that exact path, delete the published file on binding failure, and clean staging after the restore attempt. The historical old-ID deterministic live overwrite path is absent.

## Open candidates / questions

### `BUG-BACKUP-04` existing P1 remains open

The backup staging pathname contains app version + calendar fields only through seconds. If the pathname exists, current code deletes it before `createNewFile()` and then uses `writeText()`. There is no mutex/single-flight field in `SettingsViewModel.backup()` / `backupInternal()`. Multiple production callers can enter backup independently.

Source-level interleaving remains possible:
1. A and B start in the same second and calculate the same pathname.
2. A creates the path.
3. B sees the same path, deletes it, and recreates it.
4. B writes its payload.
5. A continues with its `File` object, which denotes the same pathname, and can publish B's artifact or fail according to B's lifecycle.

This is the same residual subcase already recorded under existing F4 / `BUG-BACKUP-04`; it is not a new canonical root.

### Execution closure

Exact implementation SHA has no GitHub commit status, check-run, or associated Actions run. Independent runtime execution was not performed in this review. Runtime manifestation and execution closure remain `NOT_VERIFIED` where previously required.

## Remaining review scope

- Final frozen-SHA recount.
- Re-fetch production/plan/ledger branch heads to verify the implementation target stayed fixed during the run.
- Emit final checkpoint before verdict.

## Exact upstream semantic basis used

- Master Plan F4 (`BUG-BACKUP-04`): successful empty capture must mean genuinely empty state; selected capture failure and final backup write/move failure cannot become successful artifact creation.
- Master Plan F5 (`BUG-BACKUP-02`): restore staging must be collision-resistant/no-overwrite, become live only after mapped History identity exists, clean staged artifacts on failure, and remove old-ID deterministic overwrite paths.
- v6 core invariants: positive live authority, discovery is not mutation authority, exact durable identity/provenance, sibling isolation, concurrency/exact ownership, consumer/authority-effect closure, and CLEAN requiring semantic closure plus required actual execution evidence.
- Oracle Java `File.createNewFile()`: atomic existence-check + create for that pathname only and explicitly not a reliable file-locking protocol.
- Kotlin stdlib `File.writeText`: overwrites an existing file's content.
