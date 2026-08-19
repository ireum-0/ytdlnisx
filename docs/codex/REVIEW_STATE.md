# Checkpoint Review State

This file tracks persistent state for repeated correctness review of
`checkpoint/pre-baseline-review`. Documentation-only commits produced by the
review itself do not change the reviewed code checkpoint.

- reviewed_checkpoint_commit_sha: `73d3836665f5f2e6e232e327eef1d968054d0539`
- source_branch_head_before_run: `684afbf9d5ee6323fa2fe35ae341aea6b83bdb0b`
- last_tasks_update_commit_sha: `c3dc7eea7c073de86b37f8ce3bfd3dfedb946ff7`
- reviewed_at: `2026-08-20T00:53:13+09:00`
- newly_confirmed_defects: `1`
- consecutive_clean_review_streak: `0`
- active_correctness_defect_count: `21`
- completion: `false`

## Last run

Confirmed one additional correctness defect beyond the 20 defects previously
recorded in `docs/codex/TASKS.md`: `BUG-BACKUP-08`. Settings backup records the
runtime type of every SharedPreferences value, including Long and Float, but
restore handles only String, Boolean, Int, and string sets. Real player state
uses the unsupported types (`putLong` for per-History playback position cache;
`putFloat` for subtitle sizing, hold speed, and speed presets), so a successful
reset restore silently loses those values and a merge restore silently retains
destination values instead of applying the backup. The clean-review streak was
reset to zero.
