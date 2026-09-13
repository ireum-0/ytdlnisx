# Independent correctness review — F9 / BUG-BACKUP-07

- Reviewed implementation head: `973424909fd97de758b62f639967c8bae7c0bad7`
- Semantic implementation commit: `eee70c8085f269752190abef21b530b44e753bf7`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Verdict: **CLEAN / CLOSED**
- Root: `BUG-BACKUP-07`
- Count delta: **P2 -1**
- Resulting canonical count: **P0 2 / P1 0 / P2 24**
- CLEAN-basis consequence: no contiguous basis advance because earlier F10 / `BUG-CLEANUP-01` remains open in the cumulative implementation range; basis stays `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Exact-source closure

The prior residual was that `playlistData` could be selected independently while playlist/History membership restore required `importedHistoryIdMap`, which existed only when History/download payload was restored.

At `97342490...`, a playlist-data request now automatically includes the `downloads` payload when it was not already selected. Thus a successful application-generated playlist relationship backup carries the History identity authority required to reconstruct `PlaylistItemCrossRef` rows.

The implementation also adds a production `BackupRestoreParser`, and the actual settings restore UI is wired through that parser. Relationship payload admission is treated as one capability set rather than silently accepting a partial relationship graph.

The earlier F9 invariants remain preserved:

- coherent transactional capture of Playlist rows, PlaylistItemCrossRef rows, PlaylistGroup rows and PlaylistGroupMember rows;
- fresh destination Playlist identity for every imported Playlist;
- explicit old Playlist ID -> new Playlist ID mapping;
- History membership authorized only by the imported old History ID -> new History ID map, never by equal raw destination numeric IDs;
- same-name/same-description Playlists remain distinct;
- repeated Merge produces fresh Playlist imports;
- exact-name PlaylistGroup merge follows the existing unique-name database policy;
- PlaylistGroupMember relations require explicit mapped group and playlist endpoints;
- missing/unmapped endpoints fail closed;
- Reset relationship cleanup is ordered transactionally before endpoint replacement;
- backup format remains 4, preserving F8.

The reported exact-final external execution evidence includes F9 instrumentation 4/4 PASS and the cumulative instrumentation/build matrix at exact `97342490...`. Implementation-agent execution is evidence, not independent reviewer execution.

No relevant F4/F5/F6/F7/F8 regression was found in this reviewed scope.

INDEPENDENT EXECUTION: NOT EXECUTED