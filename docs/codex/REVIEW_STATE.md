# Checkpoint Review State

This file tracks persistent state for repeated correctness review of
`checkpoint/pre-baseline-review`. Documentation-only commits produced by the
review itself do not change the reviewed code checkpoint.

- reviewed_checkpoint_commit_sha: `73d3836665f5f2e6e232e327eef1d968054d0539`
- last_tasks_update_commit_sha: `5d66d3ac05035771147d5a4700b34bc3d01dcedc`
- reviewed_at: `2026-08-20T11:40:00+09:00`
- newly_confirmed_defects: `0`
- consecutive_clean_review_streak: `3`
- active_correctness_defect_count: `22`
- completion: `true`

## Last run

The 23 previously recorded active correctness entries were revalidated from
scratch against the unchanged implementation checkpoint
`73d3836665f5f2e6e232e327eef1d968054d0539`. Twenty-two entries remain confirmed
correctness defects. `BUG-BACKUP-09` was removed because the production restore
parser resets `CookieItem`, `CommandTemplate`, and `TemplateShortcut` IDs to
`0L` before `restoreData()` receives them, so the documented backed-up-primary-
key collision path does not occur in the user-facing restore flow.

The remaining defect descriptions in `docs/codex/TASKS.md` were corrected and
expanded to state the concrete execution path, failure condition, user-visible
impact, and required fix/test contract. In particular, this pass corrected the
reset-restore parsing wording, narrowed the output-recovery scope, expanded the
stale metadata writer scope, removed unsupported formats/subtitles impact from
metadata enrichment, documented date-fetch retry consequences, clarified the
SharedPreferences backup scope, and made the Local Add same-batch condition
non-absolute.

No new implementation defect was added during this revalidation. The reviewed
product-code checkpoint remains unchanged; documentation-only commits after that
checkpoint do not alter the reviewed implementation. The checkpoint retains its
three consecutive zero-new-defect review streak, so the review cycle remains
complete.
