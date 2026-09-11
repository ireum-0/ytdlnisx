# BUG-OBSERVE-SOURCE-IDENTITY-01 — exact-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Newer implementation diff used as exploratory evidence: **NO**
- Governing protocol: `ireum-0/private:ytdlnisx-review:ytdlnisx/REVIEW_PROTOCOL.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior root checkpoints:
  - `review-runs/checkpoints/2026-09-10__c2294c87__observe-source-identity.md`
  - `review-runs/checkpoints/2026-09-10__c2294c87__observe-source-update-identity.md`

## Verdict

**NOT_CLEAN — existing P2 `BUG-OBSERVE-SOURCE-IDENTITY-01` is reconfirmed OPEN at `aa1616a2...`.**

Both established halves of the root remain reachable:

1. one semantic Observe source can still acquire multiple durable numeric owners through INSERT or UPDATE;
2. enabled `url_type` / `config` duplicate policies still do not provide atomic one-winner publication across concurrent Observe workers.

- Canonical blocker-count delta: **0**
- Canonical count remains: **P0 3 / P1 3 / P2 25**
- CLEAN Review Basis remains: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`

## Source-row semantic identity remains non-authoritative

### Entity schema

`ObserveSourcesItem` is persisted as `sources` with only an auto-generated numeric primary key. There is no unique index or persisted canonical semantic source key.

The row already contains semantically related durable fields such as:

- raw `url`;
- `observationPurpose`;
- `managedConditionKey`.

But USER source uniqueness is not enforced by a database semantic key.

### INSERT remains raw pre-check + separate insert

`ObserveSourcesRepository.insert()` still performs:

1. `observeSourcesDao.checkIfExistsWithSameURL(item.url)`;
2. if false, a separate `observeSourcesDao.insert(item)`.

The DAO check is:

`observationPurpose = 'USER' AND url = :url`

and therefore uses raw URL equality only.

The insert uses `@Insert(onConflict = IGNORE)`, but because the table has no semantic source unique constraint, concurrent identical/equivalent semantic sources do not conflict at the database boundary.

Concrete identical-raw race remains:

`create S1(U)`
→ `create S2(U)` concurrently
→ both raw existence checks observe false
→ each receives a distinct generated source id
→ two durable USER Observe owners exist.

### Canonical-equivalent source forms remain distinct at persistence

The application already recognizes a stronger playlist identity elsewhere. `AutomaticKeywordNormalizer.canonicalPlaylistUrl()` can canonicalize YouTube watch/playlist spellings; `ObserveSourceWorker` itself fetches from:

`canonicalPlaylistUrl(item.url) ?: item.url`.

Thus two raw USER source URLs can be different in storage while dispatching the same semantic YouTube playlist/source.

The persistence admission boundary does not consume that canonical relation, so equivalent raw spellings can become independent source rows even without concurrency.

## UPDATE path remains same-root evidence

`ObserveSourcesViewModel.insertUpdate()` still treats `item.id > 0` as an update and calls `repository.update(item)` directly.

For an ACTIVE source, `ObserveSourcesRepository.update()` calls `ObserveSourcesDao.update(item)` without checking sibling raw/canonical source identity.

`ObserveSourcesDao.update()` remains Room `@Update(onConflict = REPLACE)` keyed by the source primary key. With no semantic unique constraint, editing S2 to the raw or canonical-equivalent source already owned by S1 is accepted while preserving both numeric source ids.

`insertUpdate()` then calls `repository.observeTask(item)`, so S1 and S2 continue to own distinct recurring `OBSERVE<id>` namespaces.

This reconfirms the historical update-path subcase under the same root.

## Downstream duplicate-policy publication remains snapshot-then-insert

At `aa1616a2...`, `ObserveSourceWorker` constructs one process-local snapshot:

`activeAndQueuedDownloads = downloadRepo.getActiveAndQueuedDownloads().toMutableList()`

before iterating source candidates.

For enabled duplicate mode `url_type`, it checks that snapshot for:

- same `DownloadType`;
- canonical-equivalent video URL through `areSameSourceUrl()`.

For enabled duplicate mode `config`, it checks the same snapshot using `DownloadConfigurationDuplicatePolicy.findMatch(...)`.

If no duplicate is observed, publication is later performed separately through:

`downloadRepo.insert(it)`.

`DownloadRepository.insert()` delegates directly to `DownloadDao.insert()`. `DownloadDao.insert()` is an ordinary Room insert/replace operation; there is no atomic duplicate reservation joined to the preceding Observe-policy check.

The `downloads` entity has indexes only on:

- `status`;
- `downloadStartTime`;
- `orderPosition`.

There is no DB uniqueness constraint for either:

- canonical video URL + type; or
- canonical video URL + effective duplicate-configuration semantic key.

## Concrete concurrent impact

Two semantic-equivalent Observe source owners S1/S2 can execute concurrently:

1. both fetch video V;
2. both snapshot active/queued Downloads before either publishes V;
3. enabled `url_type` or `config` checks see no candidate;
4. S1 inserts Queued D1;
5. S2 inserts distinct Queued D2;
6. D1/D2 are independent durable rows and can both enter ordinary Download execution.

Therefore configured duplicate prevention is not concurrency-authoritative for Observe publication.

If duplicate prevention is intentionally disabled, publication of duplicate Downloads may be product-permitted; this finding does not redefine that policy. The blocker is that enabled policies promise duplicate suppression but the final mutation boundary cannot enforce one winner, combined with the source-row identity defect that can accidentally fork one semantic source into multiple recurring owners.

## Root reconciliation

This remains exactly existing P2 `BUG-OBSERVE-SOURCE-IDENTITY-01`.

It is distinct from:

- P0 `BUG-OBSERVE-01`, which owns extraction completeness / destructive absence authority;
- P0 `BUG-OBSERVE-HANDOFF-01`, which owns generation/supersession/revocation of an Observe worker for an existing source row;
- P2 `BUG-DOWNLOAD-HANDOFF-01`, which owns durable Queued Download -> WorkManager acceptance/recovery;
- closed P2-K command duplicate-token semantics;
- closed B10/archive identity semantics.

The INSERT race, canonical-equivalent source rows, UPDATE collision, and cross-worker duplicate publication are all consumers of one semantic identity/admission root and must be counted once.

## Required correction boundary

A coherent remediation must cover both durable boundaries:

1. **Observe source-row ownership**
   - establish a persisted canonical semantic source key (or equivalently strong identity) for USER Observe sources;
   - enforce one-winner uniqueness at the database mutation boundary;
   - cover both INSERT and UPDATE;
   - define deterministic behavior for an edit colliding with another source owner (reject/merge only under an explicit contract);
   - preserve managed automatic-keyword source semantics and purpose separation where they intentionally use another ownership key.

2. **Observe-generated Download publication when duplicate prevention is enabled**
   - the configured `url_type` / `config` semantic duplicate key must be reserved/published atomically under concurrency;
   - stale worker-local active/queued snapshots may assist fast filtering but cannot be the final uniqueness authority;
   - when policy intentionally permits duplicates, do not silently impose a stronger product rule.

Required regressions should include:

- concurrent identical USER source insertion -> one authoritative source owner;
- canonical-equivalent YouTube playlist/source forms -> one owner according to the canonical source contract;
- UPDATE/edit collision against a sibling semantic source;
- concurrent Observe workers with the same video under `url_type` -> one durable publication;
- same under `config` -> one durable publication for an equivalent effective configuration;
- duplicate-permitting policy remains intentionally permissive where current product semantics require it.

## Verification note

No independent Gradle/JVM/instrumentation test was executed in this review. Source-level production wiring was reviewed at the exact fixed basis.

INDEPENDENT EXECUTION: NOT EXECUTED
