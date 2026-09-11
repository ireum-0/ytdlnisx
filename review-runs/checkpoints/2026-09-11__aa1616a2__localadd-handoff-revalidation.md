# BUG-LOCALADD-HANDOFF-01 — LocalAdd input-session WorkManager handoff revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while the `BUG-OBSERVE-01` review-fix prompt is issued but has not been explicitly started.
- No post-`aa1616a2...` implementation commit/diff was used as evidence.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Finding: `BUG-LOCALADD-HANDOFF-01`

The exact CLEAN basis still persists a LocalAdd input session before attempting WorkManager enqueue, but does not persist/observe exact request acceptance and has no startup reconciler that can enumerate orphan input sessions. A process death or enqueue failure in that gap can leave durable selected inputs without a worker owner.

## Producer still persists input before unacknowledged enqueue

Exact `HistoryFragment@aa1616a2...` LocalAdd path:

1. expands the user-selected video URIs;
2. constructs `LocalAddEntryDto` values;
3. creates a random `sessionId`;
4. calls `LocalAddStorage.saveEntries(context, sessionId, entries)`;
5. builds a one-time `LocalAddWorker` request containing only that session ID;
6. calls `WorkManager.getInstance(context).enqueue(request)`.

The enqueue `Operation` is not awaited/observed and no exact request UUID/acceptance state is committed beside the input session.

## Storage is addressable only by an already-known session ID

Exact `LocalAddStorage.kt@aa1616a2...` persists each input list under a key `local_add_entries_<sessionId>` and provides `saveEntries`, `loadEntries`, and `clearEntries`.

It does not provide:

- enumeration of outstanding entry-session IDs;
- request UUID / semantic generation identity;
- enqueue pending/accepted/terminal state;
- startup reconciliation ownership for orphan input sessions.

`KEY_OPEN_SESSION` and pending-candidate/progress state are UI-result/progress carriers, not an initial enqueue-ownership ledger.

## Worker cleanup does not close the pre-start gap

`LocalAddWorker` can load the entry list by `KEY_SESSION_ID`; once it has successfully processed a session-backed run, it calls `LocalAddStorage.clearEntries(context, sessionId)`.

That is correct consumer cleanup after the worker has started. It cannot help when no worker ever owns the request.

Concrete fixed point remains:

1. session S with selected input URIs is durably stored;
2. process dies before WorkManager acceptance, or `enqueue()` fails/rejects;
3. no `LocalAddWorker` starts, so S is not consumed/cleared;
4. startup has no LocalAdd session enumerator/reconciler;
5. reopening History may observe WorkManager-tagged work/progress/results, but there is no exact owner to rediscover S and recreate the lost request;
6. durable input remains orphaned and the user-requested LocalAdd operation can be silently lost.

## Scope separation from BUG-LOCALADD-01

`BUG-LOCALADD-01` owns local media identity/admission correctness such as basename suppression and provider-local document-ID collisions. This root owns the persisted input-session -> WorkManager ownership handoff. Fixing one does not close the other.

## Required correction boundary carried forward

1. Persist an exact LocalAdd handoff generation/request identity with the input session before producer responsibility can end.
2. Observe WorkManager enqueue acceptance (`Operation.result`) and retain/retry the exact semantic generation until accepted or explicitly terminal/cancelled.
3. Startup and/or UI re-entry must enumerate durable outstanding LocalAdd handoff sessions and reconcile them against exact WorkManager request identity.
4. Retry/reconstruction must be idempotent by LocalAdd session/generation; duplicate recovery must not process the same input session twice.
5. Keep entries until accepted worker ownership or an explicit user cancellation/terminal failure; consumer cleanup after successful processing remains separate.
6. Cancellation/replacement must revoke stale pending request generations so later recovery cannot resurrect an intentionally cancelled LocalAdd session.
7. Add production-level tests for death before enqueue, death after enqueue call before acceptance observation, explicit enqueue failure, restart repair, duplicate recovery, and exact session cleanup after accepted execution.

## Root/count and basis reconciliation

- `BUG-LOCALADD-HANDOFF-01` was already counted as one P2 root.
- This exact-basis review reconfirms it; no new root is introduced.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- `BUG-LOCALADD-01` remains distinct/open for local-source identity/admission semantics.
- The active `BUG-OBSERVE-01` review-fix prompt/start state is unchanged.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
