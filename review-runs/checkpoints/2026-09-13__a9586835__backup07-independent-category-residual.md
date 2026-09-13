# F9 / BUG-BACKUP-07 independent re-review

## Scope

- Exact completed implementation HEAD: `a95868357ddd70ac990a79026daf8a571db1917b`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F9
- Governing checklist: v6.

## Verdict

**OPEN / NOT_CLEAN / existing P2 root.**

Count delta: `0`.

Canonical blockers remain **P0 2 / P1 0 / P2 25** after F8 closure.

CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Confirmed progress

The final source correctly establishes much of the F9 identity contract:

- format 4 carries `playlists`, `playlist_item_cross_refs`, `playlist_groups`, and `playlist_group_members`;
- capture reads the playlist/group relationship graph inside one Room transaction;
- imported Playlists always receive fresh destination IDs;
- History crossrefs require both `oldPlaylistId -> newPlaylistId` and `oldHistoryId -> newHistoryId` maps;
- PlaylistGroup exact-name merge follows the existing unique-name policy;
- group membership requires both mapped destination group and playlist identities;
- unmapped endpoints are dropped rather than authorized by equal raw numeric IDs;
- Reset removes relationship rows before endpoint rows inside the playlist restore transaction;
- repeated Merge creates fresh Playlist rows while reusing the exact-name group identity.

The submitted `BackupPlaylistProductionWiringTest` covers coherent capture, numeric collisions/unmapped endpoints, two imported same-name playlists, repeated Merge, exact-name group reuse, and Reset cleanup.

## Remaining production residual

`playlistData` is independently selectable in the actual backup-category UI, but its History membership is not independently restorable.

Concrete production path:

1. `backup_category_values` exposes `playlistData` as its own multi-choice category.
2. The dialog permits arbitrary non-empty category subsets, so `playlistData` can be selected while `downloads` is unselected.
3. `backup(listOf("playlistData"))` serializes Playlist rows plus `PlaylistItemCrossRef` rows but does not serialize the History rows that own those relation endpoints.
4. Restore builds `importedHistoryIdMap` only from `data.downloads` History imports.
5. F9 crossref restoration accepts a relation only when its backup History ID is present in that map.
6. For a playlist-only backup, the map is empty, so all History membership crossrefs are silently dropped while playlist/group restore can still report success.

Thus the newly exposed `playlistData` category can successfully create a backup that cannot preserve the very History membership F9 owns. This is a subcase of the existing F9 root, not a new finding.

A scheduled independent audit at the same exact SHA independently reached the same conclusion.

## Required next correction

A successful playlist-relationship backup must carry or require the History mapping authority needed for restoration.

Acceptable designs include a clear enforced dependency on the History/download payload or an equivalently strong self-contained mapping design consistent with F6/F9. What is not acceptable is a successful standalone playlist artifact whose crossrefs are guaranteed to disappear on restore.

Add production-boundary coverage for:

- the independently selected `playlistData` path;
- actual backup -> production parser -> restore relationship round trip;
- multi-group membership (one imported Playlist in multiple groups), which remains part of the Master Plan F9 test matrix;
- preserved missing-reference/numeric-collision fail-closed behavior.

External implementation-agent execution is evidence, not independent reviewer execution.

INDEPENDENT EXECUTION: NOT EXECUTED