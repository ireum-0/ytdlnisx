# Independent Track A checkpoint — History duplicate-removal identity

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## NEW P2 — `BUG-HISTORY-DUPLICATE-IDENTITY-01`

### Invariant

> A destructive “remove duplicate History” action must prove that records represent the same semantic media identity before deleting one. Display title equality is not media identity.

### Production reachability

`history_menu.xml` exposes `R.id.remove_duplicates` in the History toolbar menu.

`HistoryFragment.initMenu()` handles that menu item, shows a destructive confirmation dialog, and on OK directly calls `historyViewModel.deleteDuplicates()`.

This is an active user-facing production path, not a dead helper.

### Selection authority

`HistoryRepository.getDuplicateGroups()` currently:

- loads every downloaded History row;
- drops only blank titles;
- groups rows by `item.title.trim()`;
- treats every group with more than one row as duplicates;
- sorts each title-group by time/id.

No URL, canonical source identity, extractor/video id, file identity, download provenance, type, author, website, or output identity is required to match.

`HistoryViewModel.deleteDuplicates()` keeps the first row in each title-group, merges keyword assignments from the remaining rows into it, then deletes all remaining History record IDs through `repository.deleteRecords(...)`.

### Concrete impact

Two unrelated media records can legitimately share a title, for example:

- two different videos both named `Intro`;
- the same title uploaded by different channels;
- audio and video records with the same title;
- unrelated local and remote items with the same title.

When the user chooses Remove duplicates, these distinct records are grouped as duplicates solely by title. All but the first record are removed from History.

`repository.deleteRecords()` also removes each deleted History row's playlist cross-references. Therefore an incorrectly classified same-title record loses its History entry and playlist memberships even though it was not semantically a duplicate.

The physical media file is not deleted by this particular path; the confirmed blocker impact is destructive persistent History/relation data loss.

### Scope / non-duplication

This is distinct from F17/P2-N `BUG-HISTORY-01`.

- This finding is **selection/identity authority**: the wrong History rows are selected for deletion before the deletion primitive is invoked.
- F17 is **mutation/Undo atomicity and convergence** for History rows that have already been selected for deletion.

The fact that wrong duplicate deletion also removes playlist crossrefs is additional impact, not a reason to merge the identity defect into F17.

It is also distinct from Download duplicate-policy findings: this path operates on already-persisted History records and permanently removes records based on title-only grouping.

### Acceptance direction

- Define one semantic History duplicate identity that is strong enough to authorize destructive deduplication.
- Prefer positively resolved canonical media/source identity and compatible media/type/provenance evidence; do not infer duplicate identity from title alone.
- Local/file-backed History must use appropriate file/source identity rather than accidental title equality.
- If identity is ambiguous or unavailable, fail closed by retaining both rows or presenting them for explicit user selection rather than auto-deleting one.
- Preserve/merge relationship state only after the rows are proven equivalent under the F17 transactional History mutation contract.
- Tests should cover same title/different URL, same title/different author, same title/different type, true duplicate canonical URL variants, and local-vs-remote same-title records.

## Related existing F17 subcase

Even for true duplicates, `deleteDuplicates()` merges keyword assignments into the retained row but does not first merge playlist memberships from duplicate rows. That relation-loss behavior remains part of the already-counted F17/P2-N History relationship/atomicity root and is not counted again here.

## Recount

Previous canonical working count:

- `P0 3`
- `P1 3`
- `P2 24`

Add one distinct History duplicate selection/identity root:

- `P0 3`
- `P1 3`
- `P2 25`

Overall verdict remains `NOT_CLEAN`.

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
