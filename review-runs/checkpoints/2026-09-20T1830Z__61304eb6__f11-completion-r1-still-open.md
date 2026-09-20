# F11 completion re-review — intermediate R1 disposition

Date: 2026-09-20

## Exact reviewed state

- Exact final remote implementation SHA: `61304eb6f11b10ac66057a1978d5b1f8f75019b0`.
- Original F11 base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`.
- Canonical F11 review: `8b5b064c63fc04de9b2d18346954ab5dfdec625e`.
- Governing Checklist v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Disposition

### F11-R1 — STILL_OPEN / HIGH

The remediation correctly records `quiescedWorkTags` durably and reconstructs several final-state responsibilities from authoritative post-commit state:

- final Queued/Scheduled Download responsibility;
- final ACTIVE USER and managed KEYWORD_DISCOVERY ObserveSource responsibility;
- automatic-keyword coverage / pending apply-existing work;
- surviving low-quality responsibility;
- Cleanup authority.

Those subcases are materially improved.

However the broad History quiescence set still includes `local_add_worker`, and the final source does not reconstruct or authoritatively retire the user's already-durable LocalAdd responsibility.

## Exact final-source sequence

1. `RestoreTransactionCoordinator.quiesce()` treats History Reset as requiring cancellation of:
   - `HardSubScanWorker.TAG`;
   - `local_add_worker`;
   - `history_date_fetch`.

2. The LocalAdd producer in `HistoryFragment` expands the user's chosen URIs, allocates a session ID, and **durably stores the exact entries** with:

`LocalAddStorage.saveEntries(context, sessionId, entries)`

before enqueuing a `LocalAddWorker` carrying that session ID.

3. `LocalAddStorage.saveEntries()` persists the entries under `local_add_entries_<sessionId>` in default SharedPreferences. The worker reloads that durable session when it starts.

4. F11 History quiescence cancels every work item tagged `LocalAddWorker.TAG` and waits until it is finished/cancelled.

5. The Reset Room transaction does not delete or terminalize the durable LocalAdd session. `publishPreferences()` also does not intentionally retire a History-only LocalAdd session when settings are absent.

6. `reconcilePostCommit()` has no LocalAdd responsibility branch.

7. `App.onCreate()` has startup recovery for Restore, Download execution, Cleanup, WorkManager handoffs, automatic-keyword coverage, low-quality redownload, and HistoryDateFetch, but no inventory that finds `local_add_entries_<sessionId>` with no live LocalAdd WorkRequest and re-establishes the owner.

8. A cancelled `LocalAddWorker` therefore remains cancelled while its durable user-submitted entries survive.

Concrete result:

user selects local videos
→ app durably saves LocalAdd session + enqueues worker
→ History Reset starts
→ F11 cancels `local_add_worker` for quiescence
→ LocalAdd durable entries survive
→ Reset reaches COMPLETE
→ no LocalAdd WorkManager owner / no startup owner reconstruction
→ user-submitted LocalAdd operation is stranded.

This is the same sibling/remainder responsibility invariant as the original R1 finding: F11 removed an operational owner while preserving the durable authority that requires it.

## Neighboring cancelled namespaces

- HardSubScan outstanding handoff carriers are explicitly removed when the History graph is reset, so that responsibility is intentionally retired with its source graph.
- HistoryDateFetch carries explicit DB operations and startup reconciliation; the History Reset path captures/removes affected operation/item authority rather than simply assuming cancellation is enough.
- LocalAdd is different because its session authority is outside the Room History graph and survives the History-only Reset.

## Test-coverage gap

`BackupResetTransactionProductionWiringTest` contains no LocalAdd/HardSub/HistoryDateFetch scenario.

The R1 tests cover paused-only/history-only Download owner reconstruction, managed keyword source recovery, no-spurious Download work, and low-quality ownership, but not the complete quiesced-namespace responsibility map required by the remediation prompt.

Therefore the reported 26/26 Reset suite cannot close this R1 subcase.

## Required correction boundary

A safe correction must decide the LocalAdd contract explicitly:

- if a user-submitted LocalAdd session is intended to survive History Reset, F11 must durably reconstruct exactly one LocalAdd execution owner after the Reset, with process-death-safe identity; or
- if History Reset semantically revokes an in-flight LocalAdd session, F11 must durably retire/clear the exact session and user-visible responsibility as part of the authoritative Reset contract, not leave orphan session state.

Do not merely stop cancelling LocalAdd unless partial-History writes during Reset are otherwise excluded.

Add a production-wiring test crossing the real durable `LocalAddStorage` session → WorkManager owner boundary.

## Canonical reconciliation

- Same canonical F11-R1 root; no new root.
- Canonical count delta: `0`.
- F11 remains P0 / NOT_CLEAN.

## Evidence confidence

- Exact source sequence: independently reviewed at `61304eb6...`.
- Luna runtime claims: evidence only.

INDEPENDENT EXECUTION: NOT EXECUTED
