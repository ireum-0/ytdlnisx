# BUG-HISTORY-01 — current CLEAN-basis revalidation

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact checkpoint: `5afff125663209da720aedc3a82e5c8681cc5cbe` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F17 `BUG-HISTORY-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / existing P2 `BUG-HISTORY-01` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`
- P2 `BUG-KEYWORD-02` remains distinct and dependency-gated on corrected F17 snapshot semantics.

## Intervening-change overlap

The cumulative F3 work from `aa1616a2...` to `9c5191c3...` modifies `ObserveSourceWorker`, which is itself a History destructive consumer. The old F17 finding therefore cannot be carried forward solely from file non-overlap.

The exact current consumer and the shared History deletion primitive were reopened.

## Current source revalidation

### 1. Shared History relationship deletion remains non-transactional

Exact `HistoryRepository.kt@9c5191c3...` still implements `deleteRecordsWithinReferenceMutation(ids)` by chunking IDs and, for each batch, performing:

1. `playlistDao.deletePlaylistItemsByHistoryIds(batch)`;
2. `historyDao.deleteWithIds(batch)`.

The surrounding `HistoryReferenceMutationCoordinator` is an application-level lock, not a Room transaction.

A DB exception/process boundary between those calls can therefore still remove playlist memberships while retaining the History row; chunked bulk deletion can still stop between batches with a partially applied logical set.

### 2. F3 Observe changes continue to consume the same primitive

Exact `ObserveSourceWorker.kt@9c5191c3...` performs validated destructive History/file reconciliation for authoritative source absence. After external file deletion and record-snapshot revalidation, it computes `removableRecordIds` and calls:

`historyRepo.deleteRecordsWithinReferenceMutation(removableRecordIds.toList())`.

Thus the changed F3 consumer still depends on the same non-transactional relationship primitive. F3 source-membership authority prevents untrusted absence from reaching this boundary, but it does not make the legitimate target-set mutation itself atomic or crash-consistent.

### 3. Existing Undo relationship authority is not replaced by F3

The cumulative F3 range does not change the History Undo/keyword-assignment restore contract or introduce a `HistoryUndoSnapshot` that owns playlist memberships.

The prior F17 evidence therefore remains current: record Undo can restore History/keyword state without restoring playlist-reference state that was removed by deletion.

### 4. File-backed crash-consistency gap remains

External file/document deletion cannot join the later Room mutation. The current paths still lack a durable intent/recovery/compensation contract that makes successful external deletion followed by DB failure/process death converge truthfully.

The F3 Observe changes strengthen *which* absence may authorize deletion, but not the F17 requirement for atomic relationship mutation and crash-consistent file-backed convergence after a legitimate target has been selected.

## Correction boundary carried forward

The prior F17 correction remains valid:

1. Introduce one `HistoryUndoSnapshot`-equivalent authority owning History row, keyword assignments, and playlist references.
2. Capture complete relationship state and perform History/reference deletion atomically in one Room transaction.
3. Restore History, restorable keyword state, and playlist memberships atomically in one Room transaction.
4. Route record-only, bulk, Observe-authorized, and file-backed DB record removal through the same transactional relationship primitive where applicable.
5. Preserve current file target/ownership/reference validation and the independently closed duplicate-selection identity contract.
6. Establish durable crash-consistent semantics for external file deletion followed by DB failure/process interruption.
7. Keep F18 / `BUG-KEYWORD-02` separate until F17 snapshot semantics are corrected.

## Root reconciliation

- Existing P2 `BUG-HISTORY-01` remains counted once.
- Observe destructive-sync usage is another current consumer of the same F17 mutation authority, not a new root.
- `BUG-HISTORY-DUPLICATE-IDENTITY-01` remains CLOSED; target-selection identity is distinct from post-selection mutation atomicity.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
