# BUG-BACKUP-07 — playlists and playlist-group backup/restore current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Implementation branch: `checkpoint/pre-baseline-review`
- Verification-only CACHE-02 wave remains active at exact implementation SHA `3616ae02e56995e795cc52f3074d8c3d1cd2e330`; no post-basis implementation diff was used for this exploratory review.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F9 / `BUG-BACKUP-07`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Hard prerequisites F4 `BUG-BACKUP-04` and F6 `BUG-BACKUP-06` remain independently OPEN.
- F9 remains a hard prerequisite of F11 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-07` remains OPEN at exact canonical CLEAN basis `93d01d2a...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 31**.
- CLEAN Review Basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Overall canonical state remains `NOT_CLEAN`.

## Intervening-range verification

The exact range `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71 -> 93d01d2afbce2cfa62dc17fad4478416b3d7cf6c` changes only automatic-keyword production/test code and does not modify the playlist backup/restore graph. Exact `93d01d2a...` source was nevertheless re-read directly.

## Exact current production evidence

### 1. Playlist graph remains durable relational state

At exact `93d01d2a...`, Room still includes these persistent entities:

- `Playlist` with auto-generated local primary key;
- `PlaylistItemCrossRef(playlistId, historyItemId)` for History↔Playlist membership;
- `PlaylistGroup` with auto-generated local primary key and a unique-name index;
- `PlaylistGroupMember(groupId, playlistId)` for group↔playlist membership.

This is user-visible persistent relational state whose numeric IDs are destination-local and therefore require explicit import mapping.

### 2. Backup still omits the playlist graph

`BackupSettingsUtil` has no helper that serializes Playlist, History↔Playlist crossrefs, PlaylistGroup, or PlaylistGroupMember state.

`SettingsViewModel.backup()` likewise has no playlist or playlist-group category/payload branch. The current all-category backup therefore does not carry the playlist graph.

### 3. Restore model/pipeline still cannot reconstruct membership

`RestoreAppDataItem` has no fields for Playlist, PlaylistItemCrossRef, PlaylistGroup, or PlaylistGroupMember payloads.

`SettingsViewModel.restoreData()` therefore allocates new destination History IDs without building an old→new Playlist map or rebuilding History↔Playlist crossrefs, and has no PlaylistGroup import/remap path.

A backup/restore can consequently recreate History rows while silently losing playlists, History membership, playlist groups, and group membership.

## Governing identity policy

Per F9 and the unresolved F6 portable-ID contract:

- every imported Playlist receives a fresh destination playlist ID;
- backup numeric playlist IDs are never identity proof and cannot merge by numeric equality;
- Playlist rows must not merge merely by name or `(name, description)`; distinct same-name playlists remain distinct imports;
- History↔Playlist crossrefs are rebuilt only through explicit old→new History and Playlist maps;
- PlaylistGroup exact-name merge may be used only insofar as it matches the current unique-name DB constraint, with an explicit destination group map;
- PlaylistGroupMember rows are rebuilt only from mapped destination group/playlist IDs;
- missing or unmappable relations must never fall back to same-number coincidence.

## Correction boundary

Once F4 typed capture and F6 ID policy are established, F9 remains implementation-ready:

- add self-describing payloads for playlists, History↔Playlist membership, playlist groups, and group membership;
- capture related relational state under an F4-consistent snapshot boundary;
- allocate fresh destination Playlist IDs and maintain explicit old→new maps;
- rebuild crossrefs only after both relevant maps exist;
- preserve distinct same-name playlists across imports;
- apply PlaylistGroup exact-name merge only under the current DB uniqueness contract;
- support Reset and Merge without backup-local numeric-ID aliasing;
- share the first actual backup wire-format/capability extension with F8 rather than mechanically creating independent format bumps.

Required focused scenarios remain: Reset/Merge round-trip, same-name duplicate playlists remaining distinct, repeated Merge, one History in multiple playlists, one playlist in multiple groups, exact-name group merge, missing references, and deliberate numeric-ID collisions.

## Dependency consequence

F9 remains OPEN and implementation-blocked by OPEN F4/F6. It remains a hard prerequisite blocking F11. With F5-F9 now freshly revalidated at `93d01d2a...`, the next dependency-eligible backup prerequisite boundary is F10 `BUG-CLEANUP-01` current-basis revalidation.

INDEPENDENT EXECUTION: NOT EXECUTED