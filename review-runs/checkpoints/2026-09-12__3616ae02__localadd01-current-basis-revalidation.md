# BUG-LOCALADD-01 — exact CLEAN-basis revalidation

Date: 2026-09-12

## Exact review state

- Independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Prior current-basis checkpoint: `88b575f6577833e3c1aee0621650e6c5c68c9936` at `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F20 `BUG-LOCALADD-01`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Separate exact verification remains active for completed remote `checkpoint/pre-baseline-review@8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`; no post-`3616ae02...` implementation state is used as exploratory evidence here.

## Verdict

**NOT_CLEAN — existing P2 `BUG-LOCALADD-01` remains OPEN / CONFIRMED at exact basis `3616ae02...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN Review Basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- Overall canonical state remains `NOT_CLEAN`.

## Governing invariant

F20 requires filename stem alone never to discard a local file. LocalAdd dedupe/admission must use actual storage-scoped identity such as exact normalized URI or tree URI + relative path, preserve exact URI/tree dedupe and URL-match behavior, and fail open when strong identity cannot be proven.

Checklist v6 additionally requires a pre-observed candidate/absence decision to be revalidated at the final durable mutation boundary when concurrency can change the relevant state.

## Intervening-range verification

Exact compare `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c -> 3616ae02e56995e795cc52f3074d8c3d1cd2e330` is three commits ahead and changes cache/download-worker/terminal/cache-maintenance surfaces, but does not modify the LocalAdd production files reviewed for this root.

Exact final-basis source was nevertheless re-read directly in:

- `app/src/main/java/com/ireum/ytdl/work/LocalAddWorker.kt`;
- `app/src/main/java/com/ireum/ytdl/ui/downloads/HistoryFragment.kt`;
- `app/src/main/java/com/ireum/ytdl/database/repository/HistoryKeywordAssignmentRepository.kt`.

## Exact current production evidence

### 1. Worker still treats basename as cross-storage exclusion authority

`LocalAddWorker.doWork()` loads all History rows and builds `existingBaseNames` from every persisted `downloadPath` by filename-without-extension.

For each candidate it first performs exact tree/path checks, but then derives display name/title/baseName and rejects the candidate when the lowercase basename is already present in `existingBaseNames`.

Concrete reachable false exclusion:

`History A = provider/directory A/movie.mp4`
→ `candidate B = distinct provider/directory B/movie.mp4`
→ exact tree/path identity differs`
→ exact URI differs`
→ basename key collides as "movie"`
→ B is skipped`.

No source evidence proves A and B are the same storage object. The valid distinct local file is silently omitted.

### 2. UI/manual LocalAdd still repeats the basename exclusion

`HistoryFragment` independently builds `existingBaseNames` from current History paths. Its LocalAdd candidate processing computes the same basename key and skips a candidate when that key is already present.

The UI/manual path therefore independently reproduces the same cross-directory/provider false exclusion rather than delegating to a shared strong-identity admission rule.

Pending/manual selection publication also calls `HistoryKeywordAssignmentRepository.insertHistory(item)` and updates the basename set after insertion; it does not establish a separate final exact local-identity admission boundary.

### 3. Worker and UI batch dedupe still use provider-unscoped document IDs

`LocalAddWorker.localEntryIdentity()` uses this precedence:

1. exact tree URI + relative path when available;
2. otherwise bare `DocumentsContract.getDocumentId(uri)` encoded as `doc:<id>`;
3. otherwise normalized URI.

`HistoryFragment.localEntryIdentity()` uses the same fallback: tree identity, then bare document ID, then normalized URI.

A document ID is scoped to its content provider, not globally unique across providers. Two distinct providers may legally expose the same document-ID string. `distinctBy(localEntryIdentity(...))` can therefore collapse two distinct valid files before later exact URI/tree checks run.

This is the same insufficient-storage-identity root, not a separate blocker.

### 4. Final durable admission still does not revalidate strong local identity

The exact worker/UI paths perform duplicate checks before publication, but final publication still reaches `HistoryKeywordAssignmentRepository.insertHistory(item)`.

`insertHistory()` correctly acquires `HistoryReferenceMutationCoordinator.withLock` and a Room transaction. However, inside that final serialized transaction it immediately calls `historyDao.insertAndGetIdRaw(item.copy(keywords = ""))`; it does not re-read current History rows by strong local identity and require exact absence before insertion.

Therefore the canonical mutation lock serializes two competing publishers but does not make their earlier absence observations authoritative:

`A checks strong identity X absent`
→ `B checks strong identity X absent`
→ `A later enters mutation lock and inserts X`
→ `B later enters mutation lock`
→ no in-transaction exact local-identity revalidation`
→ `B also inserts X`.

The stale precheck can therefore admit duplicate durable History rows for the same exact local object.

### 5. Root/count reconciliation

Keep these as one existing `BUG-LOCALADD-01` P2 root:

- filename/basename overreach;
- provider-unscoped document-ID collision;
- stale final durable local-identity admission.

They are all failures of the same local-storage identity/admission contract. Do not increment the canonical count.

The separate `BUG-LOCALADD-HANDOFF-01` scheduler/session/recovery root remains distinct and is not reclassified here.

## Stable remediation boundary

The prior correction boundary remains valid:

1. define one shared strong local-storage identity policy used by worker and every UI/manual/pending LocalAdd path;
2. never use filename/basename alone as exclusion authority;
3. never use a provider-unscoped document ID as global identity; provider authority must participate if document IDs are used;
4. exact normalized URI and exact tree URI + relative path may authorize equality when their contracts are valid;
5. unknown/unprovable identity must fail open rather than collapse distinct candidates;
6. route all LocalAdd insertion paths through one typed durable admission primitive;
7. under the same canonical mutation serialization and Room transaction, re-read current strong local identity and insert only if no current row proves the same exact identity;
8. return a typed inserted/already-present outcome so counters/session cleanup remain truthful;
9. preserve existing URL-match semantics without using URL equality as a substitute for storage identity;
10. preserve cancellation and persisted LocalAdd-session behavior owned by their existing contracts.

A schema migration is not inherently required; same-transaction revalidation under the existing canonical History mutation coordinator may be sufficient. If a uniqueness constraint is chosen, its migration and compatibility semantics require separate review.

## Future closure coverage

Production-wiring regressions should cover at least:

- same filename in different directories/providers is admitted;
- same bare document ID across different providers is admitted;
- exact repeated stable URI is suppressed;
- exact tree URI + relative path repeat is suppressed;
- unknown/unprovable identity is not collapsed;
- two concurrent admissions of the same exact content URI converge to one durable History row;
- two concurrent admissions of equivalent exact tree identity converge to one row;
- deterministic stale-precheck race where another producer inserts before final mutation;
- worker and all UI/manual/pending paths consume the same final durable guard;
- URL matching, LocalAdd session recovery, and cancellation remain preserved.

INDEPENDENT EXECUTION: NOT EXECUTED