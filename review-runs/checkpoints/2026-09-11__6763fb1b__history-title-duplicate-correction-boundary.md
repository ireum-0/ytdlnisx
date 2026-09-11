# Independent Master Plan §9.8 History title-only duplicate deletion — correction boundary

- Exact implementation SHA / contiguous independently CLEAN basis: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Implementation branch: `checkpoint/pre-baseline-review`
- Review branch HEAD immediately before this checkpoint: `a346ebe37127a3c40e26da8bcba90cd5df717288`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, §9.8
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Canonical root: `BUG-HISTORY-DUPLICATE-IDENTITY-01` (P2)
- Verdict: `CONFIRMED / IMPLEMENTATION BOUNDARY STABLE`
- Root-count delta: `0`
- Canonical blocker count: `P0 3 / P1 3 / P2 26`
- CLEAN basis consequence: unchanged at `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Master Plan modified: NO
- Authoritative ledger modified: NO
- Implementation branch modified by this review: NO

## Exact-current production trace

### Duplicate selection authority

At exact current SHA, `HistoryRepository.getDuplicateGroups()`:

- reads `historyDao.getAllDownloaded()`;
- filters only blank titles;
- groups solely by `item.title.trim()`;
- accepts every title group of size > 1 as a destructive duplicate set;
- sorts each accepted group with `compareBy<HistoryItem> { it.time }.thenBy { it.id }`.

`HistoryDao.getAllDownloaded()` is currently a misleadingly named `SELECT * FROM history`; it does **not** apply a separate downloaded/media-present predicate. Therefore every History row can enter duplicate selection if its title is nonblank.

`HistoryItem` contains source/type fields usable for a stronger contract, including at least `url`, `type`, `website`, local-tree fields, download identity fields, and row `id`. The current duplicate selector ignores those fields entirely.

### Retained-row selection

`HistoryViewModel.deleteDuplicates()` uses `group.first()` as the retained row and every `group.drop(1)` row as a deletion target.

Fresh current source therefore establishes the exact comparator/selection mechanics as:

`time ASC` -> `id ASC` -> retain first.

The governing Master Plan records a historical observation that the newest row was retained and explicitly says not to describe the historical behavior as “keeps oldest.” Exact source controls current behavior, so this checkpoint does **not** rewrite that historical note and does not attach an oldest/newest label to the current comparator.

For attribution only, the same ascending comparator is present at original dirty HEAD `0a00e35af07632aa6b40a8cc5f7c003b33d73c56`; this review therefore does not attribute the comparator to the current remediation wave.

Retention ordering is not the root being repaired here. The implementation fix must preserve the exact current comparator/`group.first()` survivor semantics unless a separately justified product/correctness decision changes it.

### Keyword mutation

For every row selected as a duplicate, `HistoryViewModel.deleteDuplicates()` calls:

`HistoryKeywordAssignmentRepository.mergeHistoryAssignments(duplicate.id, retained.id)`.

That operation copies authoritative assignment rows from the selected duplicate to the retained History ID and rematerializes the retained row's keyword projection. It does not itself prove the two History rows are the same media; it trusts the upstream duplicate selector.

### Playlist/reference mutation

After keyword merging, `HistoryViewModel.deleteDuplicates()` passes all `drop(1)` IDs to `HistoryRepository.deleteRecords()`.

`HistoryRepository.deleteRecordsWithinReferenceMutation()` removes playlist cross-references for each selected History ID with `PlaylistDao.deletePlaylistItemsByHistoryIds(...)`, then deletes the History rows.

`PlaylistItemCrossRef` has playlist/history IDs as its composite primary key and no model-level foreign-key declaration that would independently transfer membership to the retained row.

The current duplicate action therefore removes deleted-row playlist memberships rather than transferring them to the retained row. Whether exact relationship state must be transferred/restored atomically is F17 `BUG-HISTORY-01` scope, not this identity root.

### Filesystem effect

The duplicate-removal path reaches `HistoryRepository.deleteRecords()` only. It does not call `HistoryFileDeletionEngine` or a filesystem-deletion gateway in this action.

Thus the exact §9.8 action is destructive to persistent History and relation state, but it is DB-only with respect to media files at the reviewed SHA.

### UI trigger / recovery

`HistoryFragment` handles `R.id.remove_duplicates` by showing a confirmation dialog (`confirm_delete_history` / `confirm_delete_history_desc`) and invokes `historyViewModel.deleteDuplicates()` only from the positive-button callback.

`deleteDuplicates()` returns no deletion snapshot/result to that UI path and the duplicate-menu callback contains no duplicate-specific Undo/recovery action. This checkpoint does not create a separate Undo root; broader History deletion/Undo/relationship convergence remains F17 scope.

## Root/alias boundary

`BUG-HISTORY-DUPLICATE-IDENTITY-01` owns the **selection authority** question:

> Which History rows are proven to represent the same semantic media strongly enough to authorize automatic merge/deletion?

F17 `BUG-HISTORY-01` owns mutation/transaction/playlist-reference/Undo correctness **after targets are legitimately selected**.

The roots remain distinct. Do not broaden the §9.8 repair into F17 and do not use an F17 fix as evidence that title-only selection became safe.

## Minimal safe implementation boundary

Replace title-only destructive grouping with a strong, explicit duplicate identity contract at the History duplicate-selection boundary.

Required properties:

1. `title`, filename, duration, filesize, author, website label, or bare History row ID must never independently authorize destructive equivalence.
2. Duplicate equivalence must require compatible media type. In particular, the same source represented as different `DownloadType` values must not be collapsed merely because the source/title matches.
3. For supported providers where a stable media identity can be proven (currently notably supported YouTube URL forms), equivalent URL spellings for the same stable media ID may be grouped.
4. For generic/unknown web sources, preserve a conservative source identity. Semantically meaningful query parameters and HTTP-vs-HTTPS endpoint identity must not be erased for destructive grouping.
5. Nonblank exact source equality may be used only where it is itself a sufficiently strong source identity for that media/type; arbitrary weak/search/command-like strings must not become destructive authority merely because text matches.
6. If strong identity cannot be established, fail closed: do not automatically group/delete that pair/set.
7. The correction does not require a Room schema migration. Current persisted fields are sufficient for a conservative fix.
8. The implementation may reuse or factor the already-reviewed destructive-grade semantics behind `HistoryReplacementSourceIdentity` only if the resulting duplicate contract explicitly includes the additional History media-type requirement and remains conservative for unsupported/ambiguous sources. Do not blindly reuse a weaker display/input identity helper.
9. Keep the exact current `time ASC -> id ASC -> first retained` ordering behavior unchanged while repairing identity.
10. Preserve current duplicate action wiring: user confirmation, keyword-assignment merge for legitimately selected duplicates, playlist-reference deletion behavior, and DB-only media-file behavior. F17 will own any later relationship/Undo/atomicity correction.

A small pure helper/structured key (for example a duplicate-specific strong identity model) is acceptable if it makes the destructive authority test explicit; architecture is not prescribed beyond the invariants above.

## Required acceptance matrix

Focused tests must prove at minimum:

- same normalized title + distinct canonical/source identity -> both preserved, no duplicate group;
- same title + different provider/source -> both preserved;
- same strong media identity + different title -> duplicate eligibility is based on identity, not title;
- equivalent supported YouTube URL forms + same media ID + same media type -> eligible as one duplicate group;
- same canonical source/media + incompatible/different media type -> preserved separately;
- unknown-provider URLs that differ in semantically meaningful query parameters -> preserved separately;
- blank/ambiguous/unprovable source identity -> fail closed, no destructive grouping;
- exact current group retention comparator/first-survivor behavior remains unchanged once a group is legitimately proven;
- only rows in a strong duplicate group have keyword assignments merged/deleted-row references removed;
- same-title nonduplicates cause no History-row deletion and no relation mutation;
- duplicate-removal production path remains DB-only and does not begin deleting physical media as a side effect of this correction;
- existing `HistoryReplacementSourceIdentity` replacement semantics and P2-B/P2-K identity closures are not weakened.

No focused test covering `getDuplicateGroups()` / `deleteDuplicates()` was found in the exact-current JVM repository test tree or the inspected History-oriented Room instrumentation test inventory, so new focused coverage is required rather than relying on an existing duplicate test name.

## Implementation handoff disposition

The concrete impact, root relation, current production composition, preservation requirements, and minimal correction boundary are now independently stable and unambiguous enough for an implementation prompt.

Next action: issue one focused Luna implementation prompt for `BUG-HISTORY-DUPLICATE-IDENTITY-01` from exact implementation HEAD `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`. Require a separate logical commit, push to `checkpoint/pre-baseline-review`, focused semantic tests, and a completion report. Do not modify the review branch, Master Plan, or authoritative ledger.

Independent source/structural review: EXECUTED.
Independent JVM/instrumentation/device execution: NOT EXECUTED.

INDEPENDENT EXECUTION: NOT EXECUTED
