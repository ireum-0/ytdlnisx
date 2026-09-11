# BUG-CLEANUP-01 — scheduled cleanup recurrence revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna `BUG-OBSERVE-01` implementation wave is active.
- The active Luna implementation branch HEAD, commits, and diffs were not inspected, compared, reviewed, or relied upon for this decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Defect: `BUG-CLEANUP-01`

The fixed CLEAN basis still does not provide a durable, single-owner recurring cleanup schedule. The current settings path creates timestamp-named one-shot WorkManager jobs, the cleanup worker does not enqueue a successor after success, and application startup does not reconcile enabled/disabled cleanup state with durable scheduled work.

## Exact-source evidence

### 1. Schedule identity is not stable

`app/src/main/java/com/ireum/ytdl/ui/more/settings/DownloadSettingsFragment.kt` creates a one-time `CleanUpLeftoverDownloads` request and enqueues it with a unique-work name derived from the current time:

`cleanup_leftover_downloads_${System.currentTimeMillis()}`

Although `ExistingWorkPolicy.REPLACE` is supplied, the logical unique-work name changes on each enqueue. Therefore a cadence change does not replace a single stable logical cleanup chain; an already scheduled older request can coexist with the newly scheduled request.

The request tag `cleanup_leftover_downloads` is stable, and disabling cleanup cancels work by that tag. This is a useful partial property but does not provide stable unique-work ownership for replacement, startup reconciliation, or exactly-one-chain semantics.

### 2. Successful cleanup terminates the chain

`app/src/main/java/com/ireum/ytdl/work/CleanUpLeftoverDownloads.kt` performs the cleanup and returns `Result.success()` on success. It does not schedule the next cleanup occurrence and does not delegate to any stable recurring-schedule owner.

Therefore an enabled Daily/Weekly/Monthly setting produces, at most, the one currently enqueued one-shot request. After that request succeeds, no successor is established by the reviewed production path.

### 3. Retry does not itself establish recurrence

On exception the worker returns `Result.retry()`. That retries the same WorkManager request; the reviewed source does not show the retry path itself enqueueing an additional cleanup request.

The defect is therefore not that `Result.retry()` directly duplicates work. The correctness gap is the absence of a single stable logical chain combined with independently timestamp-named enqueues from settings changes. Any correction must preserve the useful same-request retry semantics while proving that retry cannot coexist with a separately created second logical cleanup chain.

### 4. No startup reconciliation

`app/src/main/java/com/ireum/ytdl/App.kt` contains no startup reconciliation for the cleanup preference versus durable WorkManager state.

Consequences include:

- cleanup enabled but its prior one-shot work missing/cancelled/consumed -> startup does not restore the schedule;
- cleanup disabled with stale durable cleanup work -> startup does not enforce cancellation;
- there is no startup proof of exactly one logical cleanup chain.

### 5. Cadence parsing has a partial positive property but is not a durable cadence contract

The settings values are `disabled`, `1 days`, `7 days`, and `1 months`. The current settings path computes an initial one-shot delay using calendar operations, including `Calendar.MONTH` for the monthly option. Thus this review does **not** claim that the existing first monthly delay is implemented as a fixed 30-day duration.

However, because successful work has no successor and there is no shared next-run helper or startup reconciliation, the production system still lacks a durable calendar-month recurring contract. There is also no production evidence that month-end, February, leap-year, cadence-update, successor, startup-recovery, or retry/no-second-chain semantics are jointly preserved.

### 6. Required cleanup scheduling regression coverage is absent

The exact-basis JVM and Android work-test directories contain multiple download/handoff tests but no dedicated cleanup-cadence test exercising the F10 scheduling contract. In particular, no focused exact-basis coverage was found for:

- Daily/Weekly/Monthly next-run computation including month-end/February/leap-year behavior;
- disable cancellation;
- cadence update replacing the old logical schedule;
- successful worker execution creating exactly one successor;
- startup reconciliation when enabled work is missing or disabled work is stale;
- retry without creation of a second chain.

## Governing correction boundary

The Master Plan F10 contract remains applicable:

1. Use one stable logical unique-work identity for scheduled cleanup; remove timestamp-derived unique names from the authority boundary.
2. Preserve exactly one logical cleanup chain.
3. Cadence change must cancel/replace the prior logical chain and enqueue one replacement from the new change point.
4. Disable must cancel the logical cleanup chain so no stale future cleanup remains.
5. A successful cleanup execution must establish exactly one next occurrence, or an equivalent centralized owner must do so with the same durable semantics.
6. Retry/backoff must remain the same logical attempt and must not create a second chain.
7. Application startup must reconcile preference state with durable work: enabled means ensure the single chain exists; disabled means cancel stale work.
8. Isolate next-run calculation so Daily uses calendar days, Weekly uses calendar weeks, and Monthly uses true calendar months (`plusMonths`-equivalent semantics), not a fixed-duration approximation.
9. Add semantic regression coverage for month-end, February/leap-year, Daily/Weekly, disable, cadence replacement, worker-success successor, startup recovery, and retry/no-duplicate behavior.
10. Preserve the actual cleanup mutation semantics; this finding is about scheduling/cadence ownership, not a license to broaden deletion behavior.

A stable named one-shot successor chain plus startup reconciliation is consistent with the plan. A periodic WorkManager interval must not be substituted if it cannot represent the required true calendar-month semantics.

## Root/count reconciliation

- This is a revalidation of existing canonical P2 root `BUG-CLEANUP-01`, not a new root.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- Contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- The separate active Luna `BUG-OBSERVE-01` implementation was not inspected or relied upon.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
