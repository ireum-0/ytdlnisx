# BUG-BACKUP-07 — playlists and playlist-group backup/restore current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F9 / `BUG-BACKUP-07`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Hard prerequisites F4 `BUG-BACKUP-04` and F6 `BUG-BACKUP-06` are both independently OPEN at the current basis.
- F9 is a hard prerequisite of F11 `BUG-BACKUP-03`.
- No in-progress implementation diff was inspected or relied on.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-07` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

- Canonical blocker-count delta: `0`
- Canonical blocker count remains **P0 2 / P1 1 / P2 34**.
- CLEAN Review Basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Overall canonical state remains `NOT_CLEAN`.

## Exact current production evidence

### 1. Playlist state is persistent relational state

Current Room schema contains persistent playlist entities and relationship tables, including:

- `Playlist` with an auto-generated local primary key;
- History/playlist membership through `PlaylistItemCrossRef`;
- `PlaylistGroup` with its own local primary key and unique-name constraint;
- `PlaylistGroupMember(groupId, playlistId)`.

These relationships are user-visible durable organization state and depend on DB-local IDs that require explicit remapping during import.

### 2. Backup payload omits the playlist graph

Current `BackupSettingsUtil`/`SettingsViewModel.backup()` serialize History/downloads, keyword/youtuber groups, Download state categories, cookies/templates/shortcuts/search history, Observe Sources, and automatic-keyword state, but there is no playlist, History↔playlist crossref, playlist-group, or group-membership backup payload.

### 3. Restore model/pipeline cannot reconstruct playlist membership

`RestoreAppDataItem` has no playlist, playlist crossref, playlist-group, or group-membership fields.

`SettingsViewModel.restoreData()` therefore allocates new destination History IDs without creating any old→new playlist map or rebuilding History↔playlist membership. It also has no playlist-group import policy or group-membership remap.

Consequently a backup/restore can reconstruct History rows while silently losing which playlists those rows belonged to, all playlist objects themselves, and playlist-group organization.

This is the direct F9 root.

## Governing identity policy

Per Master Plan F9 and unresolved F6:

- every imported Playlist must receive a fresh destination playlist ID;
- backup numeric playlist IDs must never merge by numeric equality;
- playlists must not merge merely by name or `(name, description)`; same-name playlists from independent imports remain distinct;
- History↔playlist crossrefs may be rebuilt only through explicit old→new History and Playlist maps;
- PlaylistGroup may use exact-name merge only if that matches the current destination unique-name constraint;
- PlaylistGroupMember may be rebuilt only from mapped destination group/playlist IDs;
- missing/unmappable references must fail/skip according to explicit semantics, never bind by same-number coincidence.

F6 remains the upstream portable-ID authority contract; F9 implementation must consume it rather than invent a second numeric-reference policy.

## Correction boundary

Once F4 typed capture and F6 ID maps are established, F9 must:

- add self-describing backup payloads for playlists, History↔playlist membership, playlist groups, and group↔playlist membership;
- capture related rows under an adequate F4-consistent snapshot boundary;
- allocate fresh destination Playlist IDs for every imported playlist and maintain explicit old→new maps;
- rebuild crossrefs only after both History and Playlist maps exist;
- apply exact-name PlaylistGroup merge only under the existing unique-name DB contract, with explicit group map;
- preserve distinct same-name playlists across imports;
- support Reset and Merge without numeric-ID aliasing;
- share the first real backup capability/format extension policy with F8 rather than mechanically bumping independent format versions.

Required focused scenarios: Reset/Merge round-trip, same-name duplicate playlists remaining distinct, repeated Merge producing distinct playlist imports, History in multiple playlists, playlist in multiple groups, exact-name group merge, missing references, and deliberate backup/destination numeric collisions.

## Dependency consequence

F9 remains OPEN and is blocked for implementation by OPEN F4 and F6. It also remains a hard prerequisite blocking F11.

At this point F4 through F10 have current independent dispositions relevant to F11: F4/F5/F6/F7/F8/F9 are OPEN from current-basis revalidation, and F10 remains OPEN after its independently NOT_CLEAN candidate. F11 therefore remains unequivocally blocked and must not move to its required Sol Extra High planning/implementation phase yet.

The separate Task 002 `BUG-KEYWORD-04` canonical replay remains the first implementation target unless an explicit workflow event changes that order.

INDEPENDENT EXECUTION: NOT EXECUTED
