# BUG-OBSERVE-SOURCE-IDENTITY-01 — current-basis semantic identity revalidation

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `a6ea5fc82e90b1fe133963855c269eebef838f8f` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P1 `BUG-KEYWORD-01`
- Moving implementation diff inspected or relied on: **NO**
- Governing protocol: `ireum-0/private:ytdlnisx-review:ytdlnisx/REVIEW_PROTOCOL.md`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

This revalidation is required because the CLEAN basis advanced through F3 / `BUG-OBSERVE-01` changes to `ObserveSourceWorker`, `ResultRepository`, and source extraction composition. The prior open P2 disposition is therefore checked against exact current production source rather than inherited blindly.

## Verdict

**NOT_CLEAN — existing P2 `BUG-OBSERVE-SOURCE-IDENTITY-01` remains OPEN at `9c5191c3...`.**

Both established halves remain concrete:

1. one semantic USER Observe source can still obtain multiple durable numeric source owners through INSERT, canonical-equivalent raw forms, or UPDATE collision;
2. enabled Observe `url_type` / `config` duplicate prevention still lacks atomic one-winner Download publication under concurrent workers.

- Canonical blocker-count delta: **0**
- Canonical count remains: **P0 2 / P1 3 / P2 25**
- CLEAN Review Basis remains: `9c5191c3539734fa1c9f1b63501def89f47b216a`

## 1. USER Observe source admission remains raw and non-atomic

`ObserveSourcesItem` is still persisted in `sources` with an auto-generated numeric primary key and no persisted canonical semantic source key or unique semantic index.

`ObserveSourcesRepository.insert()` still performs a raw existence pre-check:

`checkIfExistsWithSameURL(item.url)`

followed by a separate `insert(item)` when that check is false.

The DAO predicate remains exact raw equality for USER rows:

`observationPurpose = 'USER' AND url = :url`.

Because semantic source uniqueness is not enforced by a database constraint, two concurrent identical-raw inserts can both observe absence and commit distinct numeric source rows.

## 2. Canonical-equivalent raw forms remain distinct durable owners

The current F3 production worker explicitly dispatches extraction using:

`AutomaticKeywordNormalizer.canonicalPlaylistUrl(item.url) ?: item.url`.

Thus persistence and execution use different identity strength:

- persistence admission distinguishes raw URL spellings;
- production extraction can canonicalize those spellings to the same playlist/source.

Therefore two raw USER source rows can survive as different numeric owners while executing the same semantic YouTube playlist/source.

The F3 typed SourceSnapshot work improves extraction completeness authority but does not establish source-row ownership uniqueness.

## 3. UPDATE/edit collision remains same-root evidence

`ObserveSourcesViewModel.insertUpdate()` still routes `item.id > 0` directly through `repository.update(item)`.

For ACTIVE rows `ObserveSourcesRepository.update()` uses `ObserveSourcesDao.update(item)`. The DAO remains a full-row Room `@Update(onConflict = REPLACE)` keyed by the numeric source primary key; there is no sibling raw/canonical semantic-source uniqueness predicate.

Therefore source S2 can still be edited to the raw or canonical-equivalent source already owned by S1 while preserving both ids and separate recurring `OBSERVE<id>` namespaces.

## 4. Observe Download duplicate prevention remains snapshot-then-insert

At exact `9c5191c3...`, `ObserveSourceWorker` still obtains one process-local snapshot:

`activeAndQueuedDownloads = downloadRepo.getActiveAndQueuedDownloads().toMutableList()`.

For configured duplicate mode `url_type`, it searches that snapshot for the same Download type plus canonical-equivalent source URL.

For duplicate mode `config`, it searches the same snapshot through `DownloadConfigurationDuplicatePolicy.findMatch(...)`.

If no duplicate is observed, publication occurs later through a separate:

`downloadRepo.insert(it)`.

`DownloadRepository.insert()` delegates directly to `downloadDao.insert(item)`; it does not reserve the preceding Observe duplicate-policy semantic key atomically.

The `DownloadItem` entity at `9c5191c3...` has indexes only on:

- `status`;
- `downloadStartTime`;
- `orderPosition`.

There is no database uniqueness constraint for either:

- canonical URL + DownloadType (`url_type`); or
- canonical/effective Download configuration identity (`config`).

## 5. Concrete concurrent impact remains

Two equivalent source owners or otherwise concurrent Observe workers S1/S2 can process the same V:

1. S1 and S2 each snapshot active/queued Downloads before either publishes V;
2. enabled `url_type` or `config` policy sees no existing candidate in either snapshot;
3. S1 inserts Queued D1;
4. S2 inserts distinct Queued D2;
5. D1/D2 are independent durable Download rows and may both enter ordinary Download execution.

This violates the enabled duplicate-prevention policy's one-winner meaning at the durable publication boundary.

If duplicate prevention is intentionally disabled, duplicates may remain product-permitted; this finding does not impose a new global no-duplicate rule.

## 6. Recent F3 changes do not close either half

F3 adds truthful typed source-membership authority and improves source extraction/canonical dispatch. It does not add:

- canonical USER source-row uniqueness;
- transactional source admission/update collision handling;
- atomic Observe Download duplicate reservation;
- a unique durable Download semantic key for `url_type` or `config` policy.

The current production worker still uses the snapshot/filter/insert sequence above.

## Root reconciliation

Keep this as existing canonical P2 `BUG-OBSERVE-SOURCE-IDENTITY-01`, counted once.

It owns:

- canonical USER Observe source-row ownership/admission;
- INSERT and UPDATE semantic identity collisions;
- concurrent Observe Download one-winner publication when configured duplicate prevention is enabled.

Keep distinct from:

- CLOSED `BUG-OBSERVE-01` source extraction completeness/destructive-absence authority;
- P0 `BUG-OBSERVE-HANDOFF-01` existing-row generation/supersession/revocation;
- P2 `BUG-DOWNLOAD-HANDOFF-01` durable Queued Download -> WorkManager acceptance/recovery;
- closed command duplicate-token/archive identity roots.

## Correction boundary remains

1. establish an explicit persisted canonical semantic key for USER Observe source ownership, with database-enforced one-winner admission covering INSERT and UPDATE;
2. define deterministic behavior for edit collision with an existing semantic owner;
3. preserve managed automatic-keyword source ownership semantics where purpose/condition key intentionally differ;
4. when duplicate prevention mode is enabled, make the `url_type` or effective `config` duplicate key atomic at the final durable Download publication boundary rather than relying on a stale worker-local snapshot;
5. preserve intentionally duplicate-permitting product behavior when duplicate prevention is disabled.

Required regressions remain concurrent identical USER insert, canonical-equivalent source forms, UPDATE collision, and concurrent Observe publication under both `url_type` and `config` enabled modes.

## Independent execution

No independent Gradle/JVM/instrumentation execution was performed in this exploratory review.

INDEPENDENT EXECUTION: NOT EXECUTED
