# 256a5cf5 post-publication cumulative closure review

checkpoint_kind: CANONICAL_POST_PUBLICATION_CUMULATIVE_CLOSURE_REVIEW
review_parent_sha: 919761de79e399a6cb4987abb659b00812e46d12
review_base_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
review_head_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
review_head_tree: acc40abe31e99b78e76056eb0a002b584d00c64c
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
canonical_count_semantics: ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
finding_dispositions_changed: YES
canonical_p0_before: 0
canonical_p1_before: 0
canonical_p2_before: 13
canonical_p0_after: 0
canonical_p1_after: 0
canonical_p2_after: 0
tooling_open_p2_before: 1
tooling_open_p2_after: 0
clean_review_basis_before: 74f57e695db30b701ad429af311c39a763bfe086
clean_review_basis_after: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_remediation_scope_verdict: CLEAN
known_good_baseline_status: NOT_CREATED_FINAL_HEAVY_VERIFICATION_PENDING
repository_wide_clean_claim: NOT_MADE

## Publication and history integrity

GitHub now resolves checkpoint/pre-baseline-review exactly to
256a5cf507b54adcca0342b82ddaf6e2d75a684e.

The cumulative comparison from the previously completed remote implementation
7d6a7b7c445d9e45297032fa0521a1fc1d732eb9 to 256a5cf5 is:

- status: ahead;
- ahead: 36;
- behind: 0;
- merge base: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9;
- history shape: one forward linear chain;
- preserved intermediate evidence includes the intentionally erroneous zero-test diagnostic commit
  7442f5edb26a4338706f85926b9211e66d4d7fba and its forward repair
  256a5cf507b54adcca0342b82ddaf6e2d75a684e.

No merge, amend, rebase, squash, replacement, or non-forward history movement is present in the
reviewed range.

The first 13 production commits correspond exactly to the canonical 13-root remediation inventory.
The subsequent commits are governed verification/tooling/test-contract/diagnostic follow-ups plus
the user-authorized minSdk26 support-floor change. Path review found no unrelated production
feature expansion.

## Exact-final execution evidence

The implementation campaign reports exact committed 256a5cf5 evidence:

- realWorkerRejectsAmbientRecentAndSameNameFiles: 1 PASS;
- DownloadOutputProductionWiringTest: 21 PASS;
- detached exact-SHA diff: PASS;
- complete canonical union: 19/19 gates PASS;
- aggregate: 242 PASS / 0 FAIL / 0 SKIP / 0 ERROR;
- Complete-Wave: CHECK_PASS;
- API24/25_PROOF=NOT_APPLICABLE_MINSDK_26.

The exact tested SHA is now the exact remote implementation HEAD and no behavior-relevant
source/test/config commit was added after that run. This satisfies Protocol 16.3's exact-final-SHA
identity requirement for the reported execution evidence.

The reviewer did not independently execute the tests.

## Cumulative production-source review

### BUG-DOWNLOAD-01 — FIXED-CLOSED

The governing defect conflated failed/indeterminate Room authority reads with proven row absence,
allowing cleanup/stop logic to treat uncertainty as revocation.

Final exact source keeps CURRENT, REVOKED, and INDETERMINATE distinct through
DownloadAuthorityObservation/readDownloadExecutionAuthority, and DownloadWorker no longer converts
authority-read exceptions into row absence. DownloadExecutionRecovery retains or transfers exact
recovery responsibility when observation/persistence cannot safely discharge it.

Later DownloadOutput work is test/diagnostic or test-seam work except for an inert nullable cleanup
observation hook. The final ambient-output regression retains its original synthetic yt-dlp output
and original safety assertions; the 73514f16..256a5cf5 delta adds read-only diagnostic observation
and method selection rather than accepting null, recreating the row, forcing Error, or weakening
the ambient-output contract.

The earlier 73514f16 one-off missing-row event remains causally NOT_VERIFIED. It is preserved as
historical evidence and is not relabeled as a flake. It does not establish a current production
residual at exact 256a5cf5: the governing production authority invariant is closed by exact-source
review and the current exact candidate completed the required focused/class/union/Complete-Wave
execution gates.

Disposition: FIXED-CLOSED.

### BUG-LOCALADD-06 — FIXED-CLOSED

The former singleton local_add_open_session pointer could make sibling unresolved sessions
undiscoverable.

Final LocalAddStorage durably publishes each pending result under its exact UUID, enumerates all
valid pending UUID payloads, adopts the legacy pointer only as compatibility input, and retires that
legacy pointer without making it the new authority. LocalAddWorker publishes exact-session
notifications; HistoryFragment/MainActivity carry and consume exact session IDs, while completion
retires only the exact session.

The distinct-pending-session production wiring was corrected without changing production semantics
and the exact final union passed.

Disposition: FIXED-CLOSED.

### BUG-UPDATER-02 — FIXED-CLOSED

Final UpdateUtil uses one process-wide update mutex plus durable desired source generation,
pending mutation state, and committed source/generation provenance. Source selection durably
increments generation; stale admitted requests are SUPERSEDED; startup calls updateOnStartup and
repairs desired/committed mismatch rather than relying on a sticky process boolean.

The settings caller binds manual update to the generation returned by selectSource.

Disposition: FIXED-CLOSED.

### BUG-SCHEDULER-05 — FIXED-CLOSED

Final ExactAlarmCapabilityPolicy permits exact-alarm scheduling on supported pre-31 Android versions
when AlarmManager exists, and consults canScheduleExactAlarms() only on API 31+.

The later minSdk26 decision leaves API26-30 inside the supported range, so the corrected pre-31
contract remains materially relevant and correct.

Disposition: FIXED-CLOSED.

### BUG-ABI-01 — FIXED-CLOSED

The release configuration now publishes only arm64-v8a, the ABI for which the bundled FFmpeg
runtime is provided and validated. Runtime resolution uses typed available/unavailable results and
hard-sub operations fail before publication when a required FFmpeg runtime is unavailable rather
than guessing a path.

Exact final Gradle configuration is compileSdk 36 / minSdk 26 / targetSdk 36.

Disposition: FIXED-CLOSED.

### BUG-HISTORY-04 — FIXED-CLOSED

deleteDuplicateHistoryGroups now treats caller-supplied candidate IDs only as hints. Under the
HistoryReferenceMutationCoordinator relationship lock and one Room transaction it rereads current
rows, recomputes duplicate identity, moves assignment/playlist relationships, rechecks the exact
identity immediately before deletion, and rolls relationship mutation and deletion back together on
failure.

A stale precomputed duplicate group therefore no longer grants destructive authority.

Disposition: FIXED-CLOSED.

### BUG-MIGRATION-01 — FIXED-CLOSED

The canonical residual required preserving source-to-destination recovery authority until all
still-current History references were durably reconciled.

Final HistoryVideoFolderMigration holds the History relationship mutation lock, copies and validates
the destination before reference mutation, reconciles current History references, then rereads all
current History rows before source deletion. It keeps the source when any current reference remains
or when source size/mtime changed after copy. Interruption before path reconciliation therefore
retains the original source rather than retiring it early.

Disposition: FIXED-CLOSED.

### BUG-RUNTIME-01 — FIXED-CLOSED

Final bundled-FFmpeg installation materializes into an owned staging generation, writes exact
generation/provenance markers durably, validates the staged runtime, publishes an install journal,
moves the prior live runtime to an owned rollback location, atomically publishes the verified
staging generation, revalidates the exact published generation, and retires rollback/journal state
only after success.

Recovery mutates only exact journal-owned stage/live/backup paths and restores or removes only the
interrupted generation it can prove it owns. A partial candidate cannot silently replace the last
verified runtime.

Disposition: FIXED-CLOSED.

### BUG-TERMINAL-06 — FIXED-CLOSED

Terminal cache cleanup now re-reads both terminal-execution and publication-recovery namespaces
before destructive entry handling. Opaque/unavailable recovery discovery is fail-closed.
TerminalCacheProtectionClassifier protects live registry owners, valid recovery carriers,
nonterminal execution records, incomplete publication records, opaque markers, and artifacts that
lack an exact terminal witness.

AppCacheManager applies that classifier at deletion time; only REMOVABLE terminal entries are
eligible for deletion, while UNKNOWN/PROTECTED entries remain.

Disposition: FIXED-CLOSED.

### BUG-COOKIE-03 — FIXED-CLOSED

The initial acquisition correction serializes Room upsert and runtime projection under one
coordinator, binds success to exact request/generation identity, rereads the exact row around
projection, and exposes only typed Ready/Unavailable outcomes.

The later reported "runtime request cookie omission" was caused by observing only top-level request
arguments while the production download request carries app-generated options through an exact
--config-locations carrier. The final production-wiring regression follows request.buildCommand(),
locates that exact generated config, tokenizes its effective options, and proves exactly one
--cookies entry with the expected path.

Exact final YTDLPUtil additionally uses
CookieProjectionCoordinator.requireUsableFile(context) while constructing authenticated requests;
with use_cookies=true a missing/unusable projection fails request construction instead of silently
omitting cookies.

The earlier top-level-null observation is therefore reconciled as an observer-surface mismatch,
not a remaining production omission.

Disposition: FIXED-CLOSED.

### BUG-BACKUP-11 — FIXED-CLOSED

command_path is now explicitly non-portable backup state alongside cache_path. Backup export omits
it, BackupRestoreParser filters non-portable settings on input, and RestoreTransactionCoordinator's
portable-setting application rejects non-portable keys rather than granting a foreign locator
destination authority.

The destination's command-path authority is therefore not recreated from backup payload data.

Disposition: FIXED-CLOSED.

### BUG-PAUSE-03 — FIXED-CLOSED

pauseAllDownloads snapshots only current Active/PostProcessing rows under the execution lock and
operates each snapshot member through its exact execution side-effect lease. It durably records
USER_PAUSE, converges the exact semantic stop, and performs exact native cancellation/recovery for
that execution.

The former broad cancelAllWorkByTag("download") transport action is absent from pauseAllDownloads;
a later admission outside the snapshot is not transport-cancelled by Pause All. The remaining broad
tag calls belong to different operations such as Resume All/Cancel All/exit paths and do not restore
the Pause-All defect.

The deterministic late-admission production wiring passed before the final exact union, and the
final union/Complete-Wave passed.

Disposition: FIXED-CLOSED.

### BUG-RESUME-01 — FIXED-CLOSED

ResumeActivity no longer changes the window to an overlay/system-alert type. It executes as an
ordinary app Activity, is non-exported, and consumes exact Resume/Retry identity carried by normal
PendingIntent activity launches.

The production wiring covers exact/stale/missing Resume identity and exact/stale Retry identity.
The API36 focused path passed. The explicitly authorized minSdk change makes API24/25 outside the
supported install/runtime contract; exact final app/build.gradle proves minSdk 26, so the prior
API24/25 proof requirement is NOT_APPLICABLE_MINSDK_26 rather than an unexecuted supported-range
gate.

Disposition: FIXED-CLOSED.

## Tooling blocker reconciliation

### BUG-TOOLING-01 — FIXED-CLOSED

The Gradle launch-provenance residual incorrectly reused the outer wrapper distribution name
(gradle-8.13-bin) as the inner extracted root.

Final Invoke-Verification parses canonical bin/all wrapper names into separate:
- outer distribution identity;
- exact URL-derived bucket identity;
- Gradle version;
- inner extracted root (for example gradle-8.13);
- launcher paths and completeness evidence.

Completeness requires readable metadata, the expected extracted root, .ok, and required launcher
artifacts; an .ok marker alone cannot authorize completeness. Unsupported archive mappings fail
closed.

The corrected tooling source is present in the exact remote 256a5cf5 chain and the final exact
verification/Complete-Wave campaign executed through the corrected verifier.

Disposition: FIXED-CLOSED.

## Count and CLEAN-basis reconciliation

The canonical active-remediation inventory was exactly these 13 production P2 roots. All 13 are
now FIXED-CLOSED.

Result:
- ACTIVE_REMEDIATION_OPEN_P0_COUNT=0
- ACTIVE_REMEDIATION_OPEN_P1_COUNT=0
- ACTIVE_REMEDIATION_OPEN_P2_COUNT=0
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=0
- TOOLING_OPEN_P0_COUNT=0
- TOOLING_OPEN_P1_COUNT=0
- TOOLING_OPEN_P2_COUNT=0

No waiver is used.

The cumulative 7d6a7b7c..256a5cf5 range is independently source-reviewed with no remaining blocker
inside DOWNLOAD_CORRECTNESS_REMEDIATION and with the governing exact-final execution gates green.
The contiguous CLEAN_REVIEW_BASIS therefore advances to
256a5cf507b54adcca0342b82ddaf6e2d75a684e.

This does NOT assert that every historical or backlog finding in the repository is closed. The
canonical count scope explicitly excludes unrelated/future repository-wide backlog.

## Next governed gate

Protocol 17 and Master Plan section 14 require final heavy verification and a final high-effort
independent review before any Known-Good Baseline commit/tag or authoritative baseline closure.

Required heavy verification includes:
1. full JVM test suite;
2. Android-test compile;
3. full Kotlin compile;
4. assembleDebug;
5. representative filesystem/SAF/WorkManager/Media3/restore smoke checks where practical;
6. migration coverage when applicable to the final design;
7. final high-effort review of the agreed full remediation scope.

No Known-Good Baseline commit or immutable tag is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
