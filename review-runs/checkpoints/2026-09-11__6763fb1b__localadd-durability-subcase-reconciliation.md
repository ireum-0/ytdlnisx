# Canonical reconciliation — LocalAdd locator-to-payload durability subcase

- Exact implementation SHA / contiguous CLEAN Review Basis: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Prior canonical review checkpoint: `bdb60970850945693f2216ccc87d744d51579817`
- Scheduled evidence being reconciled: `41f83382f3e86e567db8a112c55d3f5feced8682`
- Corroborating scheduled recount: `6c6ea3b94a4d6ed3746cd794ebc3ad6dd8011ddd`
- Plan reference: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Master Plan canonical SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Master Plan modified: NO
- Implementation branch modified: NO
- Overall gate: `NOT_CLEAN`

## Reconciliation decision

ADOPT the scheduled LocalAdd durability observation as a confirmed **subcase of the already-counted `BUG-LOCALADD-HANDOFF-01` root**.

Do not add a new P2 root. Canonical root-count delta is `0` and the canonical inventory remains:

`P0 3 / P1 3 / P2 25`.

The contiguous independently CLEAN basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca` because this is evidence for an already-open root, not a regression introduced by the accepted P2-B repair.

## Fresh exact-current-source proof

The historical scheduled conclusion was independently re-traced on exact source `6763fb1b...` rather than inherited by chronology.

### Producer and locator-only handoff

`HistoryFragment` expands selected local URIs into `LocalAddEntryDto` values, creates a UUID `sessionId`, calls `LocalAddStorage.saveEntries(context, sessionId, entries)`, then builds/enqueues a `OneTimeWorkRequest<LocalAddWorker>` whose relevant input is only `KEY_SESSION_ID = sessionId`.

`LocalAddStorage.saveEntries()` serializes the entry list to JSON and stores it under the session key through `SharedPreferences.Editor.apply()`. There is no application-level persistence acknowledgement, retry, alternate durable carrier, or payload copy in the WorkRequest before enqueue.

Thus the scheduler carrier is a locator into a separately persisted payload rather than the payload itself.

### Consumer behavior when the keyed payload is absent

`LocalAddWorker.doWork()` reads `KEY_SESSION_ID`; when inline `KEY_ENTRIES_JSON` is blank it loads the entries solely through `LocalAddStorage.loadEntries(context, sessionId)`. If the resulting list is empty it clears progress and returns `Result.success()`.

Therefore loss/non-durability of the keyed session payload is not surfaced as an incomplete handoff and is not reconstructible from the surviving WorkManager request.

### Pending user-resolution carrier uses the same persistence primitive

When unresolved local candidates remain, `LocalAddWorker` creates a new session UUID, calls `LocalAddStorage.savePending(...)`, calls `LocalAddStorage.setOpenSession(...)`, publishes a notification referencing that session, and then returns success. Both pending payload and open-session locator are written through the same `SharedPreferences.Editor.apply()` primitive without an application-level durability acknowledgement.

This is the same handoff-authority class: an asynchronous/externally surviving locator can outlive the application-owned payload it names.

## Root relation

This is not a new LocalAdd root. It strengthens existing `BUG-LOCALADD-HANDOFF-01`, which already owns LocalAdd durable handoff/recovery semantics across producer state, scheduler publication, process death and consumer recovery.

The subcase specifically requires the handoff contract to include:

`payload construction -> acknowledged durable payload carrier -> scheduler locator publication/acceptance -> restart discovery/consumption`.

A helper name such as `saveEntries()` is not itself proof that the keyed payload crossed a durable boundary.

## Other scheduled checkpoints reconciled in the same post-handoff sweep

- `b52783d2418d7b67f02ab9ac9ce9cc4b59b733e0` and `0066efd814a4aee6e1e89c4ad478db700bd8c1de`: corroborating B7/producer-finality evidence only. P2-B was subsequently closed canonically once by `37d8007b8907274e0d72e2ee4d045d0124eb98d8`; no additional count delta.
- `f881dd9a908987ffeee3b51bc8ddc8a5a006625f`: Share global-result-table effect remained `NOT_VERIFIED` because the required session-isolation correctness invariant was not established; no finding/count delta is adopted.
- `3aeced008271580e7417490b4385316021c1cb50`: backup sweep reconfirmed existing backup roots and introduced no new semantic delta.
- `986f3b6b910e7b7810888823bb416f0d7ed63389` / `168413a798bbdbac1b52ff632453d88089687f94`: Youtuber visible-child-group restored-ID mismatch remained `NOT_VERIFIED` because a persisted-preference-to-runtime consumer/effect was not established; no finding/count delta is adopted.

Accordingly the only post-handoff scheduled semantic changes adopted into canonical state are:

1. `BUG-OBSERVE-HANDOFF-01` severity P2 -> P0, checkpointed separately at `bdb60970850945693f2216ccc87d744d51579817`;
2. this LocalAdd durability subcase, root-count delta `0`.

## Result

- `BUG-LOCALADD-HANDOFF-01`: remains OPEN, severity P2
- adopted new subcase: locator-to-payload durability / pending-session durability
- canonical root-count delta: `0`
- canonical count: `P0 3 / P1 3 / P2 25`
- CLEAN basis: unchanged `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- global gate: `NOT_CLEAN`

INDEPENDENT EXECUTION: NOT EXECUTED
