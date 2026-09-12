# BUG-CLEANUP-01 current-basis revalidation — 2026-09-12

## Scope

- Independently CLEAN review basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Root: F10 / existing P2 `BUG-CLEANUP-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior current-basis checkpoint at `9edd3e23...`: `df47f9dc4f595ba4367d82f6b506f5ddd6b89326`
- Active Task 004 `BUG-CACHE-01` implementation diff was not inspected.

## Verdict

**OPEN / NOT_CLEAN.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 33**.
- CLEAN basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- F10 remains a hard prerequisite blocking F11 `BUG-BACKUP-03` planning.

## Exact current-source evidence

The cleanup production files are unchanged from the prior `9edd3e23...` revalidation. Exact compare `9edd3e23... -> 93d01d2a...` contains only the two BUG-KEYWORD-04 files (`AutomaticKeywordRuleEngine.kt` and its focused persistence test). Fresh exact `93d01d2a...` source was also inspected directly.

### Preference-side carrier creation

`app/src/main/java/com/ireum/ytdl/ui/more/settings/DownloadSettingsFragment.kt` handles `cleanup_leftover_downloads` changes as follows:

- `daily`: `Calendar.DAY_OF_WEEK + 1`
- `weekly`: `Calendar.DAY_OF_WEEK + 7`
- `monthly`: `Calendar.MONTH + 1`
- disabled: `cancelAllWorkByTag("cleanup_leftover_downloads")`
- enabled: create one `OneTimeWorkRequest<CleanUpLeftoverDownloads>` with the computed initial delay and tag `cleanup_leftover_downloads`
- enqueue it via `enqueueUniqueWork(System.currentTimeMillis().toString(), ExistingWorkPolicy.REPLACE, ...)`

The unique-work name is a fresh timestamp, so it is not one stable logical schedule owner. Repeated cadence changes can create independently named carriers; `REPLACE` only applies within each timestamp name.

A current-source narrowing is important: the **initial monthly delay is already computed with `Calendar.MONTH + 1`**, not a hard-coded 30-day duration. Do not continue the historical “monthly = 30 days” claim as current evidence for this code. The root remains open independently because the selected cadence is not maintained as a recurring durable logical schedule.

### Worker-side recurrence

`app/src/main/java/com/ireum/ytdl/work/CleanUpLeftoverDownloads.kt` currently:

1. establishes foreground notification state;
2. deletes Cancelled downloads;
3. deletes Errored downloads;
4. if active-download count is zero, invokes download-temp cache deletion;
5. returns `Result.success()`.

It does not read the cleanup cadence, validate a cadence/generation token, compute the next occurrence, or publish a successor request. Therefore a successful execution consumes the only one-time carrier and recurrence stops until another preference mutation creates a new request.

### Startup/recovery ownership

Fresh exact `app/src/main/java/com/ireum/ytdl/App.kt@93d01d2a...` contains startup reconciliation owners for Download execution/finalization, Terminal execution/publication, WorkManager handoffs, low-quality redownload state, automatic-keyword coverage, and History date fetch. No cleanup schedule reconciliation owner is present.

Repository search for `cleanup_leftover_downloads` identifies the preference listener/resources/notification text, not a separate production schedule coordinator. Thus a missing cleanup carrier is not repaired on startup from the persisted cadence preference.

### Cadence change / disable fencing

There is no durable cleanup cadence generation/token carried by the request and checked by the worker. A prior timestamp-named request therefore has no semantic proof that it still belongs to the current selected cadence when it eventually runs. Disable issues tag cancellation, but there is no worker-side generation/cadence fence that prevents a stale already-running/admitted occurrence from acting as an authorized current occurrence.

This is the same existing root: exactly one logical cleanup schedule does not durably preserve the selected calendar cadence across success, edits, disable, retry/re-entry, and restart.

## Relation to the isolated overnight candidate

The isolated candidate `e280bf758bac0d7e921b23694a64cad13ea02bf2` remains NOT_CLEAN and must not be replayed as-is. Its useful pieces—stable owner identity, local-calendar recurrence, cadence generation fencing, and startup reconciliation—remain reusable design evidence. Its previously reviewed residuals also remain relevant to any future implementation:

- a RUNNING worker must not publish a same-name `REPLACE` successor that cancels/replaces itself;
- transient cleanup failure must retry the same logical occurrence rather than escaping and killing recurrence;
- successor publication must happen exactly once after successful logical completion and be idempotent across retry/re-entry.

## Stable future correction boundary

A future canonical F10 correction should provide:

1. one stable logical cleanup schedule owner;
2. persisted/current cadence identity or generation sufficient to reject stale runs;
3. calendar-aware next-occurrence computation for daily/weekly/monthly semantics;
4. cadence change that authorizes exactly one replacement future occurrence;
5. disable that prevents stale current/old generations from resurrecting recurrence;
6. retry of the same occurrence on transient cleanup failure without creating a successor;
7. exactly one successor after successful completion without self-replacement;
8. startup reconciliation from persisted preference/generation to exactly one valid carrier;
9. deterministic coverage for month-length/year/DST/time-zone boundaries, repeated success, retry then success, cadence change during a run, disable during a run, duplicate prevention, and restart repair.

No Room schema migration is inherently indicated by this root; the durable owner/generation design should choose the smallest carrier consistent with the invariants.

## F11 consequence

F10 remains OPEN, so F11 / P0 `BUG-BACKUP-03` remains `BLOCKED_BY_HARD_PREREQUISITES`. No F11 Extra High plan should be issued yet.

INDEPENDENT EXECUTION: NOT EXECUTED