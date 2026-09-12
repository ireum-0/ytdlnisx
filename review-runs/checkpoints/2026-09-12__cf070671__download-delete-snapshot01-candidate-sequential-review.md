# Task 009 / BUG-DOWNLOAD-DELETE-SNAPSHOT-01 candidate sequential independent review

Date: 2026-09-12

## Review basis and candidate isolation

- Canonical independently CLEAN basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Overnight queue base: `36b43464b8106d90d672ba94718ccee58f38974f`
- Candidate: `cf070671b22949471b6ae070bdfb92ac6d1fbfaa`
- Candidate is exactly one commit ahead of the queue base.
- Compare against canonical CLEAN basis is diverged by one commit on each side with merge base exactly `36b43464b8106d90d672ba94718ccee58f38974f`; candidate remains isolated and not integrated.
- Production change: `app/src/main/java/com/ireum/ytdl/database/repository/DownloadRepository.kt`
- Focused production-boundary test: `app/src/test/java/com/ireum/ytdl/database/repository/DownloadStatusDeletionPreconditionProductionTest.kt`
- No Room migration is required by this candidate.

## Verdict

**CLEAN FOR ROOT / READY FOR SEPARATE REPLAY OR REIMPLEMENTATION.**

The candidate is not automatically integrated. Canonical `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` therefore remains open until a reviewed replay/reimplementation lands on the current canonical line.

Count delta: **0**.

Canonical blocker count remains **P0 2 / P1 1 / P2 34**.

Canonical CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## Root revalidation

The root is valid: status-scoped deletion previously derived destructive authority from rows returned before the deletion transaction. A row could change after that status query and before destructive mutation, allowing stale status authority to delete a newer/current generation and associated state.

The candidate closes that authority gap at the repository mutation boundary.

## Why the candidate is clean for this root

### 1. Status-scoped deletion revalidates the full authoritative row inside the destructive transaction

The candidate routes status-owned deletion through `deleteStatusScopedRows(...)`.

For every candidate row, inside `database.withTransaction` it:

1. reloads the current row with `downloadDao.getDownloadById(candidate.id)`;
2. skips if the row no longer exists;
3. compares the full current `DownloadItem` to the status-query snapshot with `if (current != candidate) return@forEach`;
4. only after that equality check performs linked-child terminalization where applicable, commit-barrier/active-generation deletion where applicable, and `downloadDao.delete(current.id)`.

The stale status observation is therefore a revocable candidate, not mutation authority. A concurrent mutation between discovery and transaction entry invalidates the snapshot and prevents destructive action.

### 2. Linked state and row deletion share the same validated Room transaction

For user-removal/status paths that own linked state, `terminalizeLinkedChildrenForDownloadLocked(current, ...)`, barrier/active-generation cleanup, and row deletion occur only after the same full-row check and inside the same Room transaction.

This prevents a stale status decision from deleting operation-linked state for a row whose authoritative state changed before commit.

### 3. Explicit current-ID deletion remains a separate semantic path

`deleteAllWithIDs(...)` and `deleteAll()` route to `deleteSpecificCurrentIds(...)` rather than through the stale status-snapshot fence.

That primitive re-reads the current row by ID inside its transaction and applies the explicit current-ID deletion semantics to that current row. This separation is intentional: an explicit request to delete the current row identified by ID is not the same authority contract as deleting because an earlier status query said the row was Cancelled/Errored/Saved/etc.

The focused production-boundary test explicitly guards this separation.

### 4. Deferred cache deletion is independently generation/ownership guarded

Status paths that delete cache collect only rows actually deleted by the validated transaction. The subsequent cache deletion is not allowed to rely on numeric ID alone: the existing cache-generation ownership check re-reads any current row and compares generation/path ownership before deleting the candidate's cache artifact.

A newer row/generation appearing after the deletion transaction therefore does not turn the old candidate snapshot into cache-deletion authority.

### 5. Affected producer paths use the repository boundary

The status-delete callers reviewed in UI/ViewModel/restore flows use repository methods such as `deleteSaved()`/`deleteProcessing()` rather than directly owning the DAO status-deletion semantics. Existing direct `downloadDao.delete*` status calls were concentrated in `DownloadRepository`; this candidate replaces that production boundary for the affected operations.

No material bypass of the protected status-scoped repository primitive was found for this root.

## Focused test assessment

`DownloadStatusDeletionPreconditionProductionTest` is primarily a production-source-boundary guard rather than a full concurrent Room instrumentation test. It verifies that:

- status-scoped deletion contains the Room transaction;
- current row is reloaded inside that boundary;
- full-row mismatch is checked before row deletion;
- the status-specific repository methods delegate through the protected primitive;
- explicit current-ID deletion remains separate.

This is useful regression coverage for the production structure. A deterministic concurrent Room test would be stronger defense-in-depth, but its absence does not expose a remaining production correctness gap in the reviewed candidate because the mutation ordering and transaction ownership are explicit in production code.

## Verification facts preserved from the overnight execution record

- Full JVM suite: **610/610 PASSED**.
- Android-test Kotlin compile: **PASSED**.
- Instrumentation: **NOT EXECUTED**. Packaging was interrupted before APK/test execution even though an emulator was connected.

This independent review did not rerun those suites and does not promote the instrumentation caveat to PASS.

## Candidate disposition

`cf070671b22949471b6ae070bdfb92ac6d1fbfaa` is **CLEAN FOR ROOT / READY FOR SEPARATE REPLAY OR REIMPLEMENTATION**.

Do not automatically merge/cherry-pick/rebase/transplant it into canonical history as part of candidate review. Any integration onto the current canonical line remains a separate controlled implementation/replay step followed by focused verification and independent review.

INDEPENDENT EXECUTION: NOT EXECUTED
