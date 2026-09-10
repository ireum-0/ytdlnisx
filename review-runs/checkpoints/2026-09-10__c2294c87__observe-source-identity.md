# Independent Track A checkpoint — Observe source semantic identity / duplicate publication

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## NEW P2 — `BUG-OBSERVE-SOURCE-IDENTITY-01`

### Invariant

> One semantic Observe source must have one authoritative durable source identity, and publication of a Download for one semantic source/video/config key must be atomic against concurrent Observe consumers. Raw URL pre-checks and worker-local snapshots are not uniqueness authority.

### Source creation authority

`ObserveSourcesRepository.insert()` performs:

1. `checkIfExistsWithSameURL(item.url)`;
2. if false, a separate DAO insert.

The existence check and insert are not one atomic uniqueness operation. `ObserveSourcesItem` defines only the generated primary key; the `sources` table has no unique index on URL or a canonical semantic source key.

The check is also raw URL equality. Elsewhere Observe/automatic-keyword logic canonicalizes YouTube playlist identities with `AutomaticKeywordNormalizer.canonicalPlaylistUrl()` / playlist condition keys. Therefore two raw playlist URLs that identify the same semantic YouTube playlist are not prevented from becoming two USER source rows.

Even identical raw URLs can race if two creation requests execute the check before either insert commits.

### Distinct workers

Every inserted source receives its own numeric id and its own unique WorkManager namespace `OBSERVE<id>`. Duplicate semantic source rows therefore become independent recurring producers rather than converging on one owner.

### Downstream duplicate publication race

`ObserveSourceWorker` snapshots `downloadRepo.getActiveAndQueuedDownloads()` before iterating the candidate Download items. For `url_type` and `config` duplicate policies it checks that process-local snapshot (plus History) and, when no match is observed, separately calls `downloadRepo.insert(it)`.

`DownloadItem` / the `downloads` table has indices for status, downloadStartTime, and orderPosition only. There is no database uniqueness constraint for canonical URL + type or canonical URL + effective configuration.

Concrete concurrent sequence:

1. semantic-equivalent Observe sources S1 and S2 both execute;
2. both fetch the same video V;
3. both snapshot active/queued Downloads before either publishes V;
4. both duplicate checks observe no pending V;
5. S1 inserts Queued Download D1;
6. S2 inserts distinct Queued Download D2;
7. each source can schedule/trigger the general Download worker;
8. both durable rows are independently claimable and can produce duplicate media/history work.

This race remains possible even when the configured duplicate policy is `url_type` or `config`; disabling duplicate prevention only makes duplicate publication easier.

### Scope / non-duplication

This root is distinct from:

- `BUG-OBSERVE-01`: incomplete/partial extraction treated as authoritative absence;
- `BUG-OBSERVE-HANDOFF-01`: ACTIVE source -> recurring WorkManager ownership handoff;
- P2-B/B10: yt-dlp archive-key identity;
- P2-K: role-aware command token canonicalization;
- general Download handoff: Queued row -> WorkManager acceptance.

The producer and authority are different: this finding concerns semantic uniqueness of Observe source rows and atomic publication of a video candidate across concurrent Observe owners.

### Acceptance direction

- Persist a canonical semantic source key for USER Observe sources, or an equivalently strong normalized identity.
- Enforce source uniqueness at the database mutation boundary, not only with a pre-insert query.
- Updating an existing semantic source must not silently create a second owner under an equivalent raw URL.
- Publish/reserve an Observe-generated Download under a database-enforced semantic duplicate key or transactional reservation/CAS that concurrent Observe workers cannot both acquire.
- Duplicate policy behavior may remain user-configurable, but when a policy promises `url_type` or full-configuration duplicate prevention its final publication boundary must enforce that promise under concurrency.
- Preserve legitimate cases where the user intentionally selects a duplicate-permitting policy; source-row semantic identity itself should still not accidentally fork one configured Observe source into multiple recurring owners.
- Tests should cover raw-identical concurrent source creation, canonical-equivalent YouTube playlist URLs, two concurrent Observe workers seeing the same video, and one-winner publication under the enabled duplicate policies.

## Recount

Prior corrected canonical working count: `P0 2 / P1 3 / P2 24`.

Add one distinct Observe source/publication identity root:

`P0 2 / P1 3 / P2 25`.

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
