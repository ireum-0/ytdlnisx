# Independent Track A checkpoint — duplicate admission race

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## NEW P2 — `BUG-DUPLICATE-ADMISSION-01`

### Invariant

> When duplicate prevention is enabled, a duplicate decision and publication of the corresponding runnable Download must share one authoritative admission boundary. Two concurrent producers that resolve to the same duplicate identity must not both pass a stale pre-insert snapshot and publish distinct Download rows.

This is different from F19 `BUG-DUPLICATE-01`, whose root is media/config identity normalization. F19 fixes what counts as the same media/configuration. This finding fixes the later check-to-insert race even when both producers already resolve the same canonical Download identity.

### Production reachability — canonical-equivalent Observe sources

`ObserveSourcesRepository.insert()` first calls `checkIfExistsWithSameURL(item.url)` and then performs a separate insert. The source entity has only an auto-generated primary key; there is no canonical URL/condition-key unique constraint for user Observe sources.

Therefore two raw URL spellings that represent the same supported playlist can coexist as distinct user sources. This does not require a simultaneous double tap: raw-string inequality is sufficient.

`ObserveSourceWorker` later canonicalizes the source fetch with `AutomaticKeywordNormalizer.canonicalPlaylistUrl(item.url)`, so those two source rows can fetch the same playlist while retaining different source IDs. `observeTask()` assigns each source its own `OBSERVE<id>` WorkManager namespace, so they are independent producers and may overlap.

### Duplicate admission boundary

For one Observe run, `ObserveSourceWorker`:

1. snapshots pending observation Downloads before building its candidate set;
2. later snapshots `getActiveAndQueuedDownloads()` before per-item duplicate checks;
3. performs `url_type` or `config` duplicate checks against that in-memory snapshot plus History;
4. only after deciding `!isDuplicate` calls `downloadRepo.insert(it)`;
5. then adds the newly inserted item to only that worker's process-local `activeAndQueuedDownloads` list.

There is no shared transaction/lock/reservation spanning the duplicate read and the Download insert.

Two workers W1/W2 for the same canonical playlist can therefore both:

- snapshot no pending/active Download for video V;
- independently resolve V as non-duplicate;
- both reach `downloadRepo.insert(V)`.

### Database boundary

`DownloadItem` defines indexes only for `status`, `downloadStartTime`, and `orderPosition`. There is no unique semantic media/configuration admission key.

`DownloadDao.insert()` ultimately calls `@Insert(onConflict = REPLACE) insertRaw(item)`. New Observe-produced rows carry `id = 0`, so SQLite allocates different primary keys. A conflict on semantic Download identity therefore does not exist and both publications can commit.

### Concrete impact

With duplicate prevention enabled, two overlapping Observe producers can publish two runnable Downloads for one video/configuration. Both rows can subsequently be claimed as independent Download executions, producing duplicate download work and potentially duplicate History/output effects contrary to the configured duplicate-prevention policy.

Canonical-equivalent duplicate Observe sources make this race production-reachable even without an abnormal same-source WorkManager duplication.

### Scope / non-duplication

- F19 `BUG-DUPLICATE-01`: identity normalization — what should compare equal.
- `BUG-DUPLICATE-ADMISSION-01`: atomic admission — once identity is known, only one competing producer may publish it.
- `BUG-OBSERVE-HANDOFF-01`: durable Observe source/successor WorkManager ownership, not Download duplicate admission.
- B/archive authority: archive/provenance semantics, not concurrent runnable-row publication.

### Acceptance direction

- define one authoritative duplicate-admission key from the corrected F19 identity/config policy;
- perform duplicate check + reservation/publication atomically, or enforce an equivalent durable unique reservation keyed by that semantic identity;
- all producers that honor duplicate prevention (direct queue, Observe, retry/other queue paths as applicable) use the same admission primitive;
- the losing concurrent producer receives an explicit duplicate/refused result and does not publish a runnable row;
- intentional duplicate-prevention bypass/redownload semantics remain possible through an explicit separate authority, not by weakening the normal reservation;
- tests include two concurrent producers for one canonical media/configuration and canonical-equivalent Observe source URLs.

## Count

Prior corrected canonical working count: `P0 2 / P1 3 / P2 24`.

Add one distinct semantic root, `BUG-DUPLICATE-ADMISSION-01`.

New canonical working count:

- `P0 2`
- `P1 3`
- `P2 25`

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
