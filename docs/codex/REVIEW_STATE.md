# Checkpoint Review State

This file tracks persistent state for repeated correctness review of
`checkpoint/pre-baseline-review`. Documentation-only commits produced by the
review itself do not change the reviewed code checkpoint.

- reviewed_checkpoint_commit_sha: `73d3836665f5f2e6e232e327eef1d968054d0539`
- source_branch_head_before_run: `684afbf9d5ee6323fa2fe35ae341aea6b83bdb0b`
- last_tasks_update_commit_sha: `fcbc4375d3e77010a9d3271d0c3a91a3567741ce`
- reviewed_at: `2026-08-19T22:47:53+09:00`
- newly_confirmed_defects: `1`
- consecutive_clean_review_streak: `0`
- active_correctness_defect_count: `20`
- completion: `false`

## Last run

Confirmed `P2 — BUG-BACKUP-07 — Preserve playlists and playlist groups in app-data backup`.
The default all-category backup has no category or serialization path for
`Playlist`, `PlaylistItemCrossRef`, `PlaylistGroup`, or `PlaylistGroupMember`;
`RestoreAppDataItem` likewise has no corresponding fields, and `restoreData()`
recreates History rows with new IDs without rebuilding those relationships.
A success-labelled reset backup/restore therefore loses user-created playlists,
playlist-to-History membership, and playlist grouping.
