# BUG-CLEANUP-01 — scheduled cleanup recurrence current-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Prior exact-basis root checkpoint: `e44635af458ce46037e9d04a6b651c2c42332d86` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P2 `BUG-METADATA-02` / F13, started from `4ef990e0...`
- Moving implementation diff inspected or relied on: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F10
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Overlap guard

Exact compare `aa1616a2... -> 4ef990e0...` contains only F3/F12 source-authority and automatic-keyword production-wiring changes. Cleanup settings, `CleanUpLeftoverDownloads`, and application startup cleanup ownership were untouched.

The prior F10 disposition therefore carries forward after narrow exact-source confirmation.

## Verdict

**NOT_CLEAN / existing P2 `BUG-CLEANUP-01` remains OPEN at `4ef990e0...`.**

Count delta: **0**.

Canonical blocker count remains **P0 2 / P1 2 / P2 25**.

CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

## Exact current-source confirmation

`DownloadSettingsFragment.kt@4ef990e0...` still handles cleanup cadence changes by computing one next `Calendar` time and enqueueing one `OneTimeWorkRequest<CleanUpLeftoverDownloads>` with:

- stable tag `cleanup_leftover_downloads`;
- but unique-work name `System.currentTimeMillis().toString()`;
- `ExistingWorkPolicy.REPLACE` against that timestamp-specific name.

Because the unique-work name changes on every enqueue, replacement does not identify one stable logical cleanup chain. An older scheduled request can therefore coexist with a newly scheduled request after cadence changes.

Disabling still cancels by the stable tag, which is a useful partial property but does not establish single-chain ownership or startup reconciliation.

`CleanUpLeftoverDownloads.kt@4ef990e0...` still performs the cleanup and returns `Result.success()` without scheduling a successor or handing recurrence to another durable owner. Thus a successful one-shot execution ends the chain.

`App.kt@4ef990e0...` still contains startup reconcilers for other correctness domains but no cleanup preference/work reconciliation. There is no startup proof that enabled cleanup has exactly one future request or that disabled cleanup has none.

The current first monthly delay still uses `Calendar.MONTH`, so this review does not claim the first monthly delay is a fixed 30-day duration. The blocker is the absence of a durable calendar-recurring ownership contract after that first request.

## Governing F10 invariant

The pinned Master Plan requires exactly one logical cleanup schedule preserving selected calendar cadence, with cadence replacement, disable cancellation, successful-run successor, retry without duplicate successor, and startup reconciliation.

Current source still violates that invariant.

## Root reconciliation

- This is the already-counted P2 `BUG-CLEANUP-01`; no new root is introduced.
- Retry of the same WorkRequest is not itself the defect; the root is missing stable recurring ownership combined with timestamp-named one-shot scheduling.
- Cleanup mutation semantics are outside this scheduling root unless future exact source establishes a separate issue.
- F12 `BUG-KEYWORD-01` remains CLOSED at `4ef990e0...`; no shared-domain regression was found.
- Active F13 implementation state is unchanged and was not inspected.

## Required correction boundary carried forward

1. Use one stable logical unique-work identity for scheduled cleanup.
2. Preserve exactly one logical cleanup chain.
3. Cadence change replaces the prior chain with one new schedule.
4. Disable cancels the logical chain.
5. Successful execution establishes exactly one next calendar occurrence, or an equivalent centralized owner does so.
6. Retry/backoff remains the same logical attempt and cannot create a second chain.
7. Startup reconciles preference state with durable WorkManager state.
8. Preserve true calendar day/week/month semantics, including month-end/leap-year behavior.
9. Add focused coverage for cadence replacement, disable, successor, startup recovery, retry/no duplicate, and calendar transitions.

INDEPENDENT EXECUTION: NOT EXECUTED