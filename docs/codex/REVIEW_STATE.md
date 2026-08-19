# Checkpoint Review State

This file tracks persistent state for repeated correctness review of
`checkpoint/pre-baseline-review`. Documentation-only commits produced by the
review itself do not change the reviewed code checkpoint.

- reviewed_checkpoint_commit_sha: `73d3836665f5f2e6e232e327eef1d968054d0539`
- last_tasks_update_commit_sha: `fb3ea8afe920945eb247b48f1655277558e9295c`
- reviewed_at: `2026-08-20T02:43:52+09:00`
- newly_confirmed_defects: `1`
- consecutive_clean_review_streak: `0`
- active_correctness_defect_count: `23`
- completion: `false`

## Last run

Confirmed one additional correctness defect beyond the 22 defects previously
recorded in `docs/codex/TASKS.md`: `BUG-BACKUP-09`. Merge restore passes backed-up
cookies, command templates, and template shortcuts back to their repositories
with their source database auto-generated primary keys intact. Those IDs are not
portable identities. A collision with an unrelated live row is handled by the
current DAO conflict policies rather than by semantic merge logic: cookie and
command-template inserts use `IGNORE`, silently dropping the imported row, while
shortcut inserts use `REPLACE`, silently overwriting the unrelated live shortcut.
The defect was added as P2 and the clean-review streak was reset to zero.
