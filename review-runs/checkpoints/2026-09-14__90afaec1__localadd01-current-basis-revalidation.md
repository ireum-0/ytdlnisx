# F20 / BUG-LOCALADD-01 — exact CLEAN-basis revalidation

Date: 2026-09-14

## Exact review state

- Independently CLEAN review basis: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Prior checkpoint: `8acb93e6138daf607c721496a07f8176a48c9451` at `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F20 `BUG-LOCALADD-01`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Active F10+F16+F17 implementation remains frozen from inspection. No in-progress implementation diff was used.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `BUG-LOCALADD-01` remains valid at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 0 / P2 23**.
- CLEAN Review Basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Governing invariant

Filename stem alone must never discard a local file. LocalAdd admission/dedupe requires storage-scoped identity such as exact normalized URI or exact tree URI + relative path; provider-scoped identifiers must retain provider authority; unknown identity must fail open. A pre-observed absence is not final insertion authority when concurrent producers can change durable History state.

## Intervening-range verification

Exact compare `3616ae02... -> 90afaec1...` is 16 commits ahead. It does not modify `LocalAddWorker.kt`, `HistoryFragment.kt`, or `HistoryKeywordAssignmentRepository.kt`. `HistoryDao.kt` does change in the range, so its exact final insertion/query surface was re-read directly.

## Exact current production evidence

### 1. Worker still uses basename as cross-storage exclusion authority

`LocalAddWorker.doWork()` builds `existingBaseNames` from every persisted History `downloadPath`, reducing each path to filename-without-extension.

After exact tree/path and exact download-path checks, each candidate derives a display title/baseName and is skipped when that lowercase baseName already exists.

Two distinct valid files in different directories/providers with the same filename stem can therefore collide and one is silently omitted without proof that the storage objects are identical.

### 2. UI/manual LocalAdd still repeats the basename exclusion

`HistoryFragment` independently builds the same `existingBaseNames` set and skips candidates whose filename stem collides. Pending/manual publication paths also update that basename set after insertion.

Worker and UI therefore continue to implement the same coarse cross-storage exclusion rather than consuming one shared strong-identity admission authority.

### 3. Worker/UI batch dedupe still has a provider-unscoped document-ID fallback

`LocalAddWorker.localEntryIdentity()` uses:

1. exact tree URI + relative path when available;
2. otherwise bare `DocumentsContract.getDocumentId(uri)` as `doc:<id>`;
3. otherwise normalized URI.

`HistoryFragment.localEntryIdentity()` uses the same identity shape.

A document ID is scoped to its provider, not globally unique. Two distinct content providers may expose the same document-ID string, so `distinctBy(localEntryIdentity(...))` can collapse distinct valid files before later exact checks run.

### 4. Final durable insertion still does not revalidate strong storage identity

Worker/UI prechecks eventually publish through `HistoryKeywordAssignmentRepository.insertHistory(item)`.

That repository correctly acquires `HistoryReferenceMutationCoordinator.withLock` and a Room transaction, but inside the final serialized transaction it immediately invokes `historyDao.insertAndGetIdRaw(item.copy(keywords = ""))` and then materializes assignments. It does not re-read current History rows by exact normalized URI/tree identity and require current absence before insertion.

Exact `HistoryDao` still exposes ordinary `@Insert(onConflict = REPLACE) insertAndGetIdRaw` plus lookup helpers, but no storage-identity uniqueness constraint or final LocalAdd admission predicate closes that race.

Reachable sequence remains:

A prechecks exact identity X absent
-> B prechecks X absent
-> A enters mutation lock and inserts X
-> B later enters the same lock
-> B does not revalidate X
-> B inserts another durable History row for the same exact local object.

The lock serializes insertion but does not convert stale earlier absence into current mutation authority.

## Root/count reconciliation

Keep the following as one existing F20 P2 root:

- filename/basename overreach;
- provider-unscoped document-ID collision;
- stale final durable local-identity admission.

They are failures of one local-storage identity/admission contract. Count delta remains `0`.

The separate F21 `BUG-LOCALADD-HANDOFF-01` scheduler/session/recovery root remains distinct.

## Stable correction boundary

A future fix still requires one shared strong local-storage identity policy for worker and every UI/manual/pending path; no basename-only exclusion; provider authority in any document-ID identity; fail-open handling for unprovable identity; and one typed durable admission primitive that, under the canonical History mutation lock and Room transaction, revalidates exact current storage identity immediately before insert and returns inserted/already-present truthfully.

Preserve exact URI/tree dedupe, URL-match behavior, cancellation, and LocalAdd session semantics.

INDEPENDENT EXECUTION: NOT EXECUTED