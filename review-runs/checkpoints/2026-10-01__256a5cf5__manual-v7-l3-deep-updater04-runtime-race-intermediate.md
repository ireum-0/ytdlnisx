# Manual correctness review — 256a5cf5 — L3 concurrency and authority (intermediate)

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 37aff97e469475b6a968913f314abc92bdfbaced

implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c
master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
ledger_adoption_sha: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption_sha: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3
protocol_commit: 54086a4d27e7b224bf92582e74662200413283ac
protocol_blob: 71630837d84456a86ca1c6b3709e7573dc2d13f3
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY

primary_deep_lens: L3 Concurrency & authority
primary_deep_selection_reason: R1/R5 — L3 is the sole remaining not-yet-DEEP lens on this implementation SHA and directly owns updater/runtime live-consumer authority.

## Baseline L1-L6

- L1 Durability & recovery: DEEP / FAIL — BUG-UPDATER-02 startup/Restore/queued deferral recovery ownership remains open.
- L2 Identity & provenance: DEEP / FAIL — BUG-UPDATER-02 foreign runtime provenance and BUG-UPDATER-03 blank restored source remain open.
- L3 Concurrency & authority: DEEP / FAIL — new BUG-UPDATER-04 shared yt-dlp runtime replacement race.
- L4 Destructive ownership: DEEP / FAIL — BUG-HISTORY-05 valid-dedupe playlist-membership loss remains open.
- L5 Platform contract closure: DEEP — fresh supported-range/scheduler/Resume/ABI runtime checks did not establish a new L5 root.
- L6 Cross-feature semantic propagation: DEEP / FAIL — updater restore/runtime propagation and History relationship final effect remain open.

## New finding — BUG-UPDATER-04

severity: P2
attribution: PRE_EXISTING_BASELINE_DEFECT
inventory_disposition: ADOPTED_INTO_ACTIVE_INVENTORY
relation: NEW_DISTINCT_ROOT
current_implementation_root: NO

Root:
yt-dlp runtime replacement has no shared authority/lease with Download runtime consumers.

Production evidence:
1. Manual UpdateSettingsFragment calls UpdateUtil.updateYoutubeDL() without checking or acquiring Download runtime authority.
2. MainActivity startup only performs a point-in-time Active/Queued count check before calling UpdateUtil; that observation is not a lease and a Download can claim afterward.
3. UpdateUtil.updateMutex serializes updater requests only. RestoreMutationAdmission covers short preference admission/commit windows and does not span the native updater mutation.
4. Download claim uses CacheMaintenanceAuthority + per-Download side-effect lease + DownloadWorker execution lock, but no updater/runtime-replacement authority.
5. Download native execution resolves the shared yt-dlp binary at noBackupFilesDir/youtubedl-android/yt-dlp/yt-dlp and requires it to exist immediately before native start.
6. The exact youtubedl-android 0.18.1 dependency source at release commit d725d5c9a18c3a99a13ee0308bf78275dc310760 implements stable/nightly/master update by:
   - downloading the replacement to cache;
   - FileUtils.deleteDirectory(ytdlpDir);
   - ytdlpDir.mkdirs();
   - FileUtils.copyFile(file, binary).
   Its execute path launches the exact same ytdlpPath under that directory.
7. Upstream YoutubeDL.updateYoutubeDL() is synchronized against other updater calls, but the execute path is not synchronized on that updater lock.
8. Therefore a valid Download attempt can enter after ytdlpDir deletion and before copy completes. Current YoutubeDLCompat.ensureRuntimeInitialized() cannot repair this gap because upstream YoutubeDL.init() returns immediately once initialized; checkRequiredBinary() then throws "Missing yt-dlp runtime ...".

Concrete counterexample:
- Download D is running or becomes claimable.
- User starts manual yt-dlp update, or startup reads Active/Queued count=0.
- updater downloads replacement and reaches destructive promotion.
- updater deletes ytdlpDir.
- D begins/retries an exact yt-dlp native attempt during the delete->copy interval.
- D observes the required shared runtime missing and fails because the updater destroyed the shared generation without excluding live/new consumers.

The same semantic race exists at baseline 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9:
- UpdateSettingsFragment directly invoked updateYoutubeDL();
- MainActivity used the same Active/Queued snapshot gate;
- UpdateUtil performed the same dependency update;
- app/build.gradle already used youtubedl-android library 0.18.1;
- Download execution already used the same youtubedl-android runtime path.

This is distinct from BUG-UPDATER-02:
- BUG-UPDATER-02 owns updater request/source-generation/provenance and startup convergence.
- BUG-UPDATER-04 owns shared-runtime destructive replacement versus concurrent runtime consumers.
- BUG-UPDATER-04 reproduces with one updater request, no source change, no backup/restore and no stale updater generation.

Required future correction boundary:
- introduce one shared yt-dlp runtime generation authority that covers destructive updater promotion and every new native consumer admission;
- updater promotion must wait for/prove no live runtime consumer or use a generation-safe staged/atomic replacement design that permits old consumers and new-generation consumers safely;
- a point-in-time Active/Queued count is not sufficient authority;
- manual update, startup update and any worker update path must use the same authority;
- preserve per-Download exact execution ownership and do not broaden cancellation.
- add deterministic production-wiring coverage for manual update + live Download and startup zero-count snapshot + late Download admission.

## Trigger map

- Core concurrency/live-owner matrix: FAIL — BUG-UPDATER-04.
- Core filesystem/reference authority at actual mutation point: FAIL — shared yt-dlp generation is deleted without a consumer exclusion lease.
- Module E referenced-artifact/shared-generation promotion: FAIL — updater destroys the current shared generation before new generation promotion is complete and without excluding concurrent consumers.
- Module C external representation/schema/authority projection: FAIL — BUG-UPDATER-02/03.
- Module F persisted schema-generation compatibility: FAIL — BUG-UPDATER-02.
- Module H persisted executable configuration fan-out: FAIL — BUG-UPDATER-03.
- Core destructive relationship preservation: FAIL — BUG-HISTORY-05.
- Module A / L5 supported platform contract: PASS in freshly sampled exact source.
- Module D packaged runtime provenance: PASS in freshly sampled FFmpeg boundary.
- Module I Terminal maintenance/live-owner boundary: no new residual established in this run.

## Canonical inventory recount

Before this run:
- P0=0
- P1=0
- P2=3
- open roots: BUG-UPDATER-02, BUG-UPDATER-03, BUG-HISTORY-05

After adopting BUG-UPDATER-04:
- P0=0
- P1=0
- P2=4
- open roots: BUG-UPDATER-02, BUG-UPDATER-03, BUG-HISTORY-05, BUG-UPDATER-04

The active implementation prompt remains BUG-UPDATER-02 only. No existing prompt is silently broadened.

## Test gap

Current UpdateUtilProductionWiringTest covers:
- source A/B ordering;
- startup desired/committed reconciliation when updateOnStartup() is directly invoked;
- native failure followed by direct retry;
- overlapping same-generation updater requests;
- custom updater error parsing.

It does not cover:
- a live Download consumer while updater promotes the runtime;
- manual update while Download is active;
- late Download claim after MainActivity's zero-count observation;
- delete->copy promotion gap in the 0.18.1 updater.

No tests were independently executed in this manual run.

remaining_not_yet_deep: NONE
next_not_yet_deep_lens: NONE
independent_execution: NOT_EXECUTED
