# BUG-LOCALADD-01 current-basis revalidation — 2026-09-12

## Scope

- Exact independently CLEAN review basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Root: existing P2 `BUG-LOCALADD-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F20
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Latest isolated candidate: `7adf9c6399be9ab855972933782918ce25456be7` — previously reviewed NOT_CLEAN and not integrated
- Active Task 004 / BUG-CACHE-01 implementation diff was not inspected.

## Verdict

**OPEN / NOT_CLEAN for `BUG-LOCALADD-01`.**

Count delta: `0`.

Canonical blocker count remains **P0 2 / P1 1 / P2 33**.

The independently CLEAN basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.

## Governing invariant

F20 requires filename stem alone never to discard a local file. LocalAdd dedupe/admission must use actual storage-scoped identity such as exact normalized URI or tree URI + relative path, preserve exact URI/tree dedupe and URL-match behavior, and fail open when strong identity cannot be proven.

Checklist v6 additionally requires any admission candidate observed before a suspension/transaction/concurrent change to be revalidated at the final durable mutation boundary.

## Exact current-source evidence

### 1. Worker still treats basename as cross-storage identity

At exact `93d01d2a...`, `LocalAddWorker.doWork()`:

- reads all History rows once;
- derives `existingBaseNames` from every persisted `downloadPath` by filename-without-extension;
- performs current tree/path checks for the candidate;
- resolves the display name/title;
- rejects the candidate when that basename already exists in `existingBaseNames`.

Concrete production failure:

```text
History contains provider/directory A: /A/movie.mp4
new LocalAdd candidate is distinct provider/directory B: /B/movie.mp4
exact tree identity differs
exact URI/downloadPath differs
basename key for both is "movie"
worker rejects B before admission
```

The two files are not proven identical. This directly violates the F20 invariant and can silently omit a valid local file.

### 2. UI/manual LocalAdd repeats the same basename exclusion

`HistoryFragment.addLocalVideos()` independently constructs `existingBaseNames` from the current History snapshot. After exact tree/path checks it computes the candidate filename stem and rejects the candidate if the same stem exists.

The UI path can therefore reproduce the same cross-directory/provider false exclusion independently of the worker.

`processPendingSelections()` also still uses the old LocalAdd model and ordinary `insertHistory()` publication; it has not adopted the reviewed isolated candidate's stronger shared exact-identity policy.

### 3. Batch dedupe can collide provider-local document IDs

Both current `LocalAddWorker.localEntryIdentity()` and `HistoryFragment.localEntryIdentity()` use this precedence:

1. tree URI + relative path when available;
2. otherwise bare `DocumentsContract.getDocumentId(uri)`;
3. otherwise normalized URI.

A bare document ID is provider-local, not global. Two distinct content providers can legally expose the same document ID string. In that case `entries.distinctBy { localEntryIdentity(...) }` / `result.distinctBy { localEntryIdentity(...) }` can collapse two distinct valid local files before later exact checks run.

This is the same BUG-LOCALADD-01 identity-strength root, not a new blocker.

### 4. Final durable admission is still not revalidated

Even if the coarse basename/document-ID exclusions are bypassed, the current exact duplicate checks remain pre-insert observations:

- worker/UI query current tree/path or URL state;
- metadata matching, search, rename, dialogs, or other work can occur after that observation;
- final publication calls `HistoryKeywordAssignmentRepository.insertHistory(item)`.

`HistoryKeywordAssignmentRepository.insertHistory()` does acquire `HistoryReferenceMutationCoordinator.withLock` and a Room transaction, but it immediately calls `historyDao.insertAndGetIdRaw(...)`; it does not re-read exact local URI/tree identity inside that final serialized transaction.

`HistoryDao` exposes `getItemByDownloadPath()` and `getItemByLocalTree()` but `insertAndGetIdRaw()` has no exact-local-identity guard. `HistoryItem` has no unique index on `downloadPath`, `localTreeUri`, or `localTreePath`.

Therefore two producers can still execute:

```text
A: exact local identity X absent
B: exact local identity X absent
A: passes precheck
B: passes precheck
A: insertHistory(X) acquires lock and commits
B: later acquires same lock and insertHistory(X) also commits
```

The mutation lock serializes the inserts but does not make the stale absence decision authoritative. Both rows can survive.

This final-admission residual was already identified during review of isolated candidate `7adf9c63...`; it remains present in canonical source and belongs to the same existing P2 root.

## Root/count reconciliation

Do not split these into additional blocker IDs:

- basename overreach;
- provider-unscoped document-ID collision;
- stale final durable duplicate admission.

All concern insufficient or stale local-storage identity being used to suppress/admit LocalAdd records under the same `BUG-LOCALADD-01` contract.

Canonical blocker count therefore does not change.

The separate `BUG-LOCALADD-HANDOFF-01` root remains separate; persisted session / WorkManager ownership and recovery are not being reclassified here.

## Required future correction boundary

A correct canonical implementation should preserve the useful parts of isolated candidate `7adf9c63...` but additionally close final durable admission:

1. define one shared strong local identity policy;
2. never use filename/basename alone as exclusion authority;
3. never use provider-unscoped document ID as global identity;
4. exact normalized URI and exact tree URI + relative path may be comparable when valid;
5. unknown/unprovable identity fails open rather than collapsing distinct candidates;
6. route worker and every UI/manual/pending LocalAdd insertion through one durable admission primitive;
7. under the same canonical mutation serialization/Room transaction, re-read current strong local identity and insert only if no current row proves the same exact identity;
8. return a typed inserted/already-present outcome so counters/session cleanup remain truthful;
9. preserve existing URL-match semantics without treating URL equality as a substitute for local-storage identity;
10. preserve cancellation and persisted-session behavior owned by their existing contracts.

A schema migration is not inherently required. The existing History mutation lock plus a same-transaction exact-identity revalidation may be sufficient; if a uniqueness constraint is chosen instead, its migration/compatibility semantics require separate review.

## Required focused regression coverage

At minimum:

- same filename in different directories is admitted;
- same filename/document ID across different providers is admitted;
- exact repeated stable URI is suppressed;
- exact tree URI + relative path repeat is suppressed;
- unknown/unprovable identity is not collapsed;
- two concurrent admissions of the same exact content URI converge to one durable History row;
- two concurrent admissions of equivalent exact tree identity converge to one row;
- deterministic stale-precheck race where another producer inserts before the tested producer's final mutation;
- worker and all UI/manual/pending admission paths consume the same final durable guard;
- URL-match behavior, session recovery, and cancellation remain preserved.

INDEPENDENT EXECUTION: NOT EXECUTED