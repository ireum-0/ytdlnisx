# Repository current-existence audit — batch E — revalidated historical OPEN roots

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: c09cd6592f9ec36d6bc97ab1de5372108dbc63f8
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-DATE-03 — CURRENT PATH ABSENT

Historical root:
Preserve History date-fetch operation carrier across WorkManager enqueue failure.

Last explicit historical verdict reviewed:
OPEN / CONFIRMED at 90afaec1.

Current exact-source reconciliation:
- repository-wide code search at eda6a758 finds no HistoryDateFetchManager,
  HistoryDateFetchRepository, HistoryDateFetchOperation or HistoryDateFetchWorker production symbol;
- no current source path or resource key matching date_fetch/fetch_missing/missing_date was found;
- current History code still carries mediaPublishedAt data, but the historical user action/Room operation
  /WorkManager carrier chain described by BUG-DATE-03 is not present.

Disposition:
VERIFIED_CLOSED_CURRENT_PATH_ABSENT.

This does not erase the historical defect. It records that its exact production feature/path is no longer
present on the current implementation basis. Reopen only if an equivalent durable History date-fetch
operation/carrier is reintroduced or a renamed current path is later proven.

## BUG-OBSERVE-02 — VERIFIED_CLOSED

Historical root:
Configuration-only Observe Source edits reconstructed a full ObserveSourcesItem with runCount=0/default
runtime fields and published it through full-row @Update(REPLACE), destroying worker-owned runtime state.

Last explicit historical verdict reviewed:
OPEN / CONFIRMED at ee7eea00.

Current exact source:
- UI still constructs an edit candidate with runCount=0, but that object is no longer full-row publication
  authority;
- ObserveSourcesViewModel.insertUpdate routes existing rows to ObserveSourcesRepository.reconfigure;
- reconfigure uses advanceUserConfigurationIfGeneration with exact expected configurationGeneration;
- the SQL updates only configuration-owned columns, increments configurationGeneration, and preserves
  runCount/runtime link/history/status fields unless an explicit resetRunCount/resetProcessedLinks action
  requests the corresponding reset;
- worker runtime publication uses updateRuntimeIfGeneration under exact ACTIVE generation authority;
- configuration and runtime writes therefore no longer share stale full-row authority.

Disposition:
VERIFIED_CLOSED.

The existence of the legacy DAO @Update method does not reopen this root without a current production
configuration-edit caller that uses it as full-row authority. The reviewed edit path does not.

## BUG-QUEUE-05 — VERIFIED_OPEN P2

Historical root:
Clear Queue omits durably Paused Downloads from its cancellation target snapshot.

Current exact source reproduces the root:
- DownloadQueueMainFragment Clear Queue calls DownloadViewModel.cancelAllDownloads();
- cancelAllDownloadsImpl invokes cancelActiveQueued();
- DownloadRepository.cancelActiveQueuedWithResult obtains candidates through
  getActiveAndQueuedDownloadsList();
- current DownloadDao.getActiveAndQueuedDownloadsList SQL still includes
  Active, PostProcessing, Queued, WaitingForMembership and Scheduled, but omits Paused;
- current resetPausedToQueued() still makes Paused rows runnable again;
- WorkManager tag cancellation does not mutate an already Paused Room row into semantic Cancelled state.

Disposition:
VERIFIED_OPEN / P2.

Current correction boundary:
- define one durable Clear Queue target taxonomy that includes every retained executable queue intent,
  including Paused;
- snapshot and per-row cancellation admission must use the same taxonomy;
- preserve exact execution-owner/quiescence handling for live states while allowing already-quiescent
  Paused rows to receive the semantic cancellation write;
- a successful Clear Queue must prevent Resume/Resume All/restart from reviving that prior intent;
- first-write failure for one Paused row must remain an explicit retryable/observable failure and must not
  corrupt healthy siblings.

Required focused proof:
real Fragment -> ViewModel -> Repository -> Room path with Paused plus all other supported nonterminal
states, then Resume/Resume All/restart/repeated Clear Queue controls.

## Batch result

roots_audited: 3
verified_closed_or_currently_not_reproduced: 2
verified_open: 1
reopened: 0
not_verified: 0

Registry-derived/later-current candidate progress:
- lower-bound candidate IDs: 136
- audited: 52
- verified closed/currently not reproduced: 49
- verified open: 3
- not yet audited inside that lower bound: 84
- checkpoint-only candidate discovery remains required and may increase the total population.

Current verified-open non-canonical repository roots found by batches A-E:
- BUG-PLAYER-01 — P2
- BUG-QUEUE-01 — P3
- BUG-QUEUE-05 — P2

Current download canonical remains P0=0 / P1=0 / P2=8.

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
