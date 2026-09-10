# Independent Track A false-positive / scope audit

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Review branch HEAD observed before this audit: `07dbbfd842a938b95e750217f95d31e817d63c5f`
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Purpose

Re-audit the currently open correctness findings before continuing Track A. The audit asks, for every retained root, whether there is an actual production caller/path, a correctness-relevant durable/destructive effect, and a scope that is neither duplicated nor materially broader than the source supports.

The governing v6 checklist explicitly treats ignored asynchronous acceptance/completion, undiscoverable recovery debt, stale mutation candidates, multi-ledger process-death windows, and identity/provenance widening as correctness boundaries. Therefore the recent WorkManager-handoff, stale-deletion, and mutable-storage-root findings are in scope when their production path is established; they are not excluded merely because they are crash/restart or asynchronous-boundary defects.

## Audit result

### False positives

No currently retained root was disproved by this static production-source audit.

This does not claim runtime reproduction. The result is source-semantic confirmation at the fixed Review Basis.

### Duplicate count removed — `BUG-HISTORY-UNDO-PLAYLIST-01`

`BUG-HISTORY-UNDO-PLAYLIST-01` is not a new P2 root. It is the already-confirmed F17 `BUG-HISTORY-01` production case.

Both descriptions use the same exact path:

`HistoryFragment` single-record delete
→ snapshot History row + keyword assignments only
→ `deleteHistoryItems(..., deleteAssociatedFiles=false)`
→ `HistoryRepository.deleteRecords()` removes playlist crossrefs and History row
→ Snackbar Undo calls `HistoryViewModel.restoreHistory(deletedItem, assignmentSnapshot)`
→ History/keywords return without the prior playlist membership.

The Master Plan already defines F17 as “playlist membership across delete/Undo” and requires an atomic `HistoryUndoSnapshot` containing History row, keyword assignments, and playlist refs. The later alias therefore contributes evidence to F17 but must not increment the blocker count.

### Scope wording corrected — `BUG-LOCALADD-HANDOFF-01`

The finding remains valid, but the earlier wording overstated the first durable boundary.

`LocalAddStorage.saveEntries()` uses `SharedPreferences.Editor.apply()`, not a synchronous commit barrier. The supported source therefore does not prove that the entry session is durably on disk before the subsequent `WorkManager.enqueue()` boundary.

The precise root is:

- LocalAdd session/input persistence and WorkManager request acceptance are separate asynchronous boundaries;
- enqueue acceptance is not observed;
- there is no exact request/generation carrier tying the input session to accepted WorkManager ownership;
- startup/UI recovery cannot enumerate input sessions that lack a worker owner and reconstruct their exact request.

Thus the valid defect is the absence of an atomic/recoverable input-session → accepted-worker handoff. Do not phrase the crash case as guaranteeing that the SharedPreferences session always survives abrupt process death.

Acceptance should prove durable input before relying on it for recovery (or use another transactional carrier), then retain exact retry/recovery ownership until WorkManager acceptance or explicit terminal resolution.

## Retained distinct recent roots

The following recent Track A roots remain distinct and source-supported:

1. `BUG-SCHEDULE-01` — scheduling-window/capability/recurrence/generation authority. Its S3-S8 cases remain one root and do not multiply the count.
2. `BUG-OBSERVE-HANDOFF-01` — ACTIVE Observe source state can outlive exact WorkManager successor ownership.
3. `BUG-KEYWORD-HANDOFF-01` — durable queued/apply-existing rule intent can outlive exact sync-work ownership.
4. `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` — stale status/ID snapshot can retain destructive authority over a newer Download state; D4 is same-root set/snapshot mismatch.
5. `BUG-CACHE-ROOT-01` — one admitted Download/Terminal generation can re-read a different cache-root preference mid-lifecycle; output-destination preferences are intentionally excluded from this root.
6. `BUG-LOWQUALITY-SAVED-01` — generic Saved transition bypasses the linked low-quality child/parent convergence contract.
7. `BUG-PLAYLIST-TXN-01` — playlist delete/selection operations have their own multi-commit relation mutation path, distinct from History delete/Undo F17.
8. `BUG-TERMINAL-HANDOFF-01` — Terminal row commit precedes unobserved WorkManager acceptance; Terminal startup recovery enumerates execution witnesses rather than arbitrary pre-admission rows.
9. `BUG-LOCALADD-HANDOFF-01` — retained with the narrower handoff wording above.

These are not merged merely because several could reuse a common handoff/transaction helper. Their producers, durable semantic states, cancellation/recovery rules, and terminal effects are different.

## Retained Master Plan / pre-existing remediation roots

The exact-source reviews already establish production reachability for the current open Master Plan roots and the pre-existing remediation roots B/C/J/K/L/M. No contradiction was found in this audit that justifies downgrading or rejecting them.

Current P0:
- F3 `BUG-OBSERVE-01`
- F11 `BUG-BACKUP-03`

Current P1:
- F4 `BUG-BACKUP-04`
- F12 `BUG-KEYWORD-01`
- F14 `BUG-METADATA-01`

Current P2, before the nine recent distinct roots:
- B, C, J, K/F19, L/F20, M/F21
- F5 `BUG-BACKUP-02`
- F6 `BUG-BACKUP-06`
- F7 `BUG-BACKUP-08`
- F8 `BUG-BACKUP-05`
- F9 `BUG-BACKUP-07`
- F10 `BUG-CLEANUP-01`
- F13 `BUG-METADATA-02`
- F15 `BUG-DATE-01`
- F16 `BUG-DATE-02`
- F17 `BUG-HISTORY-01`
- F18 `BUG-KEYWORD-02`

F22 `BUG-QUEUE-01` remains P3 and is not included in blocker count.

## Corrected working recount

Previous reported working recount: `P0 2 / P1 3 / P2 27`.

Correction:
- remove one duplicate count for `BUG-HISTORY-UNDO-PLAYLIST-01` because it is F17 evidence, not a separate root;
- LocalAdd scope wording changes, but the root remains P2.

Corrected distinct blocker recount:

- `P0 2`
- `P1 3`
- `P2 26`

Overall verdict remains `NOT_CLEAN`.

## Scope discipline going forward

Before adding another blocker, require all of:

1. exact production caller/reachability at the fixed Review Basis;
2. concrete durable/destructive/authority effect, not only a helper smell;
3. final mutation/consumer boundary traced;
4. comparison against existing roots to avoid count inflation;
5. narrow acceptance tied to the proven invariant;
6. no runtime-reproduction claim without independent execution.

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
