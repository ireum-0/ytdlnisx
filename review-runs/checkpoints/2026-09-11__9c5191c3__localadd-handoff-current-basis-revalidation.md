# BUG-LOCALADD-HANDOFF-01 — LocalAdd handoff current-basis revalidation

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `ceb965b9c4e6b380e05f21e9240dc56d38b2b332` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Luna `BUG-KEYWORD-01` review-fix #2 remains in progress; no implementation commit/diff newer than its frozen start was inspected or relied upon.

## Verdict

**NOT_CLEAN / existing P2 `BUG-LOCALADD-HANDOFF-01` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change relation

The fixed-basis range `aa1616a2... -> 9c5191c3...` contains the F3 SourceSnapshot / Observe / automatic-keyword work and does not modify the LocalAdd producer, session storage, worker, or startup-recovery boundary. The exact current LocalAdd path was nevertheless re-read before carrying the finding forward.

## Exact current production path

`HistoryFragment.kt@9c5191c3...` still performs the initial LocalAdd handoff in this order:

1. expand selected URIs and construct `LocalAddEntryDto` entries;
2. generate a random `sessionId`;
3. call `LocalAddStorage.saveEntries(context, sessionId, entries)`;
4. build a one-time `LocalAddWorker` request containing only `KEY_SESSION_ID`;
5. call `WorkManager.getInstance(context).enqueue(request)`.

The returned WorkManager `Operation` is not awaited or observed and the durable session is not associated with an exact request UUID / enqueue-pending / accepted / terminal generation record before producer responsibility ends.

`LocalAddStorage.kt@9c5191c3...` still stores entries under `local_add_entries_<sessionId>` and exposes only known-session operations such as `saveEntries`, `loadEntries`, and `clearEntries`. It still has no enumeration of outstanding entry sessions, request identity, enqueue state, or startup recovery ledger.

`LocalAddWorker.kt@9c5191c3...` correctly loads a known session and clears its stored entries after successful session-backed processing. That consumer cleanup cannot repair a session whose worker request was never accepted or never starts.

`App.kt@9c5191c3...` starts recovery for Download execution, Terminal execution/publication, generic WorkManager handoff carriers, low-quality re-download, automatic-keyword observation coverage, and History-date fetch. No LocalAdd session enumerator/reconciler is present, and the generic handoff recovery does not own the LocalAdd session persisted by `HistoryFragment`.

The concrete orphan sequence therefore remains:

`durable LocalAdd session S`
→ process death / enqueue rejection before accepted WorkManager ownership
→ no `LocalAddWorker` starts
→ no consumer cleanup for S
→ startup cannot enumerate/reconstruct S's exact request
→ durable selected input is orphaned and the user-requested LocalAdd operation can be silently lost.

## Correction boundary carried forward

1. Persist exact LocalAdd handoff/request generation identity with the input session before producer responsibility can end, or provide an equivalent durable owner discoverable on restart.
2. Observe WorkManager `Operation.result`; retain/retry exact semantic responsibility until accepted or explicitly terminal/revoked.
3. Startup/UI re-entry must enumerate outstanding durable LocalAdd handoffs and reconcile them against exact WorkManager ownership.
4. Recovery must be idempotent by session/generation and must not process the same input twice.
5. Keep session entries until accepted worker ownership or explicit cancellation/terminal failure; preserve existing consumer cleanup after accepted processing.
6. Cancellation/replacement must revoke stale pending generations so recovery cannot resurrect a cancelled session.
7. Preserve separate `BUG-LOCALADD-01` local-media identity/admission semantics; this checkpoint does not merge or close that root.

## Root reconciliation

- `BUG-LOCALADD-HANDOFF-01` remains one canonical P2 root.
- No new root or severity change is established.
- `BUG-LOCALADD-01` remains distinct/open.
- F3 / `BUG-OBSERVE-01` remains CLOSED and did not change this handoff boundary.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED