# BUG-LOCALADD-06 clean-basis remediation-ready refinement

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 31d2bcb21c85432f5d967fb535efc24b536180ca
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L1 Durability & recovery DEEP
supporting_lenses:
- L2 Identity & provenance
- L3 Concurrency & authority
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Existing behavior to preserve

Local Add already gives each unresolved continuation a unique immutable UUID and stores its payload under `local_add_pending_<sessionId>`. Work-manager input sessions likewise have explicit per-session entries/owner records and can enumerate those input-session keys.

The per-session PendingIntent request code is also derived from the session ID.

The defect is the successful pending-result publication/discovery layer, not the existence of unique payload identities.

## Exact defect

A successful worker with unresolved candidates currently performs:

1. `savePending(sessionId, pending)`;
2. `setOpenSession(sessionId)` into singleton `local_add_open_session`;
3. notification ID `93500` containing that session's PendingIntent;
4. `Result.success()`.

Two independent successful workers A and B can both persist their unique payloads, then B overwrites the singleton open-session pointer and replaces notification 93500. Payload A remains durable but its opaque UUID is no longer reachable through a production enumeration path.

`LocalAddStorage` already demonstrates that SharedPreferences-backed session namespaces can be enumerated by key prefix for entry/owner recovery. The pending namespace simply does not expose/use the corresponding durable enumeration contract.

## Exact invariant

Every successfully published unresolved Local Add session remains independently discoverable by exact session UUID until that exact session is either:

- consumed and durably completed; or
- explicitly abandoned/retired by a user-owned action.

A later sibling session may not supersede discoverability merely by completing later.

Notification state is a convenience projection, not the durable continuation index.

## Narrow implementation boundary

Replace the `savePending() + setOpenSession()` singleton publication contract with one durable exact-session publication primitive.

A narrow implementation can use the existing per-session SharedPreferences key namespace itself as the durable enumerable index:

- write `local_add_pending_<sessionId>` with synchronous checked `commit()` before worker success;
- add `loadPendingSessionIds()` that enumerates only valid pending-prefix keys and validates non-empty/parseable payloads;
- make startup/History re-entry enumerate every pending session, not consume one singleton pointer;
- retain compatibility by adopting an existing legacy `local_add_open_session` value into ordinary pending enumeration when its payload exists, then retire only that pointer.

Do not introduce a read-modify-write singleton StringSet unless updates are serialized and durable; prefix-key enumeration avoids last-writer-wins index updates entirely.

Make pending-result notification identity exact per session (stable integer/tag derived from the exact session UUID, with collision-safe tag+id if needed). Disabled/replaced notifications must not affect durable discoverability.

Consumption must be exact and crash-safe: opening/claiming A must not remove B, and A's payload should not be deleted merely because its launcher was consumed. Remove A's pending payload only after the continuation has durably completed or the user explicitly abandons A.

Preserve the separate LocalAdd input-session owner lifecycle. Pending-result sessions are continuation output and should not be reinterpreted as active WorkManager input owners.

## Forbidden shortcuts

- keeping `local_add_open_session` as the authoritative one-slot index
- relying on unique PendingIntent request codes while retaining one notification ID
- scanning arbitrary SharedPreferences values heuristically instead of the exact pending prefix
- deleting all pending keys when one session is consumed
- marking the worker success before the exact pending payload is durably committed
- using notification presence as restart authority
- auto-abandoning older pending sessions when a new one appears
- merging this root with LocalAdd-03/04/05

## Acceptance matrix

- A publishes unresolved payload, then B publishes: both IDs enumerate/open exactly once
- B then A ordering: same
- three concurrent sessions: all remain independently discoverable
- A is opened before B publishes: B remains discoverable and A remains recoverable until exact completion/abandon
- notifications enabled: each pending session has an independent action
- notifications disabled: all sessions remain discoverable from durable app state
- notification for A dismissed/replaced externally: A remains discoverable
- process death after payload commit but before notification: session remains discoverable
- process death after UI opens A but before A completion: A is not silently lost
- completing A removes only A; B/C remain
- explicit abandon A removes only A
- legacy singleton pointer + valid legacy payload is adopted without duplicating or losing it
- malformed/empty pending payload fails closed and is not silently presented as a valid continuation
- LocalAdd-03 first-persistence-failure regression remains failure, not successful orphan publication
- LocalAdd-04 entry failure and LocalAdd-05 duplicate insertion remain separately governed
- production wiring covers two real LocalAddWorker completions, restart, History/MainActivity discovery, and exact consumption

## Test gap

The CLEAN-basis source has production enumeration for input-session owners but no corresponding pending-result enumeration test. Existing successful sibling publication can still hide an older pending UUID.

This is the same BUG-LOCALADD-06 root; no new finding ID or count change.
