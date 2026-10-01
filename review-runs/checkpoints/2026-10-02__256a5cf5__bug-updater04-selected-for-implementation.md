# BUG-UPDATER-04 selected as next canonical implementation root

checkpoint_kind: CANONICAL_IMPLEMENTATION_ROOT_SELECTION
review_parent_sha: 85f80fe3bd46e5ce37410f0782945d17bf7a7143
implementation_base_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_base_tree: acc40abe31e99b78e76056eb0a002b584d00c64c
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
canonical_p0: 0
canonical_p1: 0
canonical_p2: 4
tooling_open_p2: 0
finding_dispositions_changed: NO
count_change: 0

## Selected root

ACTIVE_IMPLEMENTATION_ROOT=BUG-UPDATER-04
ACTIVE_IMPLEMENTATION_ROOT_STATUS=OPEN_P2_CONFIRMED_REMEDIATION_READY
GOVERNING_REMEDIATION_READY_CHECKPOINT=review-runs/checkpoints/2026-10-02__256a5cf5__bug-updater04-remediation-ready.md

The other canonical P2 roots remain open and unmodified by this selection:

- BUG-UPDATER-02
- BUG-UPDATER-03
- BUG-HISTORY-05

One canonical root is authorized per implementation wave. No multi-root implementation batch is
authorized by this checkpoint.

## Dependency and ordering rationale

BUG-UPDATER-04 is selected first because it establishes the shared yt-dlp runtime mutation/consumer
authority that BUG-UPDATER-02's remediation-ready contract explicitly requires downstream:

- BUG-UPDATER-02 says startup/recovery should reuse the shared runtime authority after
  BUG-UPDATER-04 introduces it rather than treating Active/Queued row count as correctness
  ownership.
- Implementing BUG-UPDATER-02 before that authority exists would risk creating a temporary or
  duplicate deferral/ownership mechanism that must later be replaced.
- BUG-UPDATER-03 and BUG-HISTORY-05 are independent remediation-ready roots and do not provide a
  prerequisite authority consumed by the other open roots.

Therefore the dependency-safe next implementation boundary is BUG-UPDATER-04.

This ordering does not pre-close or reclassify BUG-UPDATER-02, BUG-UPDATER-03, or BUG-HISTORY-05.

## Governing semantic contract

Use the remediation-ready checkpoint as the canonical root contract.

The required final invariant is:

- every production yt-dlp native consumer and every production yt-dlp runtime mutation path
  participates in one app-owned runtime-generation authority;
- a consumer lease/lifetime covers the real native-use interval through exact native quiescence;
- updater mutation/promotion authority excludes incompatible live and newly admitted consumers for
  the entire destructive replacement risk interval;
- a point-in-time Active/Queued row count, UI state, WorkManager tag, or earlier idle observation
  is not runtime authority;
- built-in updates and custom --update-to self-update obey the same mutation authority;
- custom self-update must not self-deadlock by reentering ordinary consumer authority while it
  already owns mutation authority;
- failure/cancellation releases authority exactly once and never reports success with a missing or
  partial runtime;
- existing exact Download executionId/process/native ownership, recovery, sibling isolation,
  updater desired-generation/SUPERSEDED ordering, Restore authority, and no-broad-cancellation
  semantics remain intact.

## Implementation planning boundary

The implementation agent may introduce one narrowly scoped app-owned runtime authority coordinator
and wire the existing real production boundaries into it.

Likely production touch points include:

- app/src/main/java/com/ireum/ytdl/util/UpdateUtil.kt
- app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YoutubeDLCompat.kt
- one new narrowly-scoped runtime-authority/coordinator file in the same yt-dlp utility boundary
- directly proven production consumer/mutator callers only when required to enter the shared
  authority at the correct final launch/mutation boundary

DownloadWorker or scheduler/admission code may change only when exact source proves it is necessary
to bind consumer authority to the real native execution lifetime. Do not weaken or replace existing
Download execution ownership.

Before editing, inventory exact current production consumers and mutators at 256a5cf5. A common
wrapper may be used only if source review proves all materially different production consumers pass
through it. Any direct library execute/update path that bypasses the common wrapper must either be
wired into the same authority or be explicitly classified as mutation-owned/non-consumer by the
governing contract.

No dependency, schema, migration, build-configuration, backup/restore provenance, History, Pause,
Resume, cookie, terminal-cleanup, FFmpeg-runtime, or unrelated feature correction is authorized.

## Regression-contract obligations

This production semantic change triggers Protocol 4.1.

Before editing tests, inventory materially affected existing tests/fixtures/seams/assertions and
classify them as:

- still-valid regression;
- stale contract requiring precise strengthening/rewrite;
- harness/precondition/seam defect;
- genuine production-semantic failure.

Required deterministic production-wiring coverage includes the remediation-ready acceptance matrix:

1. already-live Download consumer vs manual updater;
2. startup zero-count observation followed by a late Download claim before promotion;
3. updater-held promotion authority vs a newly admitted Download;
4. updater failure/cancellation releasing authority and preserving later runtime usability;
5. custom --update-to self-update under mutation authority without self-deadlock;
6. overlapping updater requests preserving existing generation/SUPERSEDED semantics;
7. existing exact Download ownership/sibling isolation remaining intact.

Helper-only lock tests are insufficient. Decisive tests must cross real app production seams.

## Previous verification state

The final-heavy H1 result at exact 256a5cf5 is preserved historical evidence:

- 694 JVM tests PASS;
- 0 failures/skips/errors;
- Gradle BUILD SUCCESSFUL;
- recorded child exit-code metadata remains null.

H2-H6 were superseded when current source blockers were discovered.

Any source/test/config change for BUG-UPDATER-04 means the historical H1 result is not exact-final
execution evidence for the new candidate. Do not continue old H2-H6 as if the candidate were
unchanged. Final-heavy verification is deferred until the active production roots are independently
closed on a later exact SHA.

## Wave boundary

This implementation wave owns BUG-UPDATER-04 only.

Preferred logical history:

1. production authority correction;
2. regression-contract/test correction as a separate forward commit when practical.

Both commits, if two are needed, must remain one-root attributable. One combined commit is allowed
when separation would not improve attribution. Never amend/rebase/squash/rewrite existing history.

After exact committed-candidate verification and authorized normal fast-forward publication, STOP
for independent completion review. Do not begin BUG-UPDATER-02, BUG-UPDATER-03, BUG-HISTORY-05, or
final-heavy verification in the same wave.

INDEPENDENT EXECUTION: NOT EXECUTED
