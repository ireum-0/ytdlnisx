# Independent P2-B full-wave review — 4da5b67b

- Implementation starting SHA: `1a3f7d2c81f79454426e42b394d3328cfb7683d3`
- Reviewed completed SHA: `4da5b67bbb2eb7d80184791b8deb34ceb821d22c`
- Implementation branch remote match: YES (`checkpoint/pre-baseline-review` is identical to reviewed SHA)
- Commit chain reviewed: `1a3f7d2c81f79454426e42b394d3328cfb7683d3` -> `c5d2ab2332a540636c5db4f84f505e6e5353cff5` -> `4da5b67bbb2eb7d80184791b8deb34ceb821d22c`
- Plan reference: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Master Plan modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Summary

The completed P2-B wave materially improves the generation/archive architecture, but P2-B cannot close at `4da5b67b`.

Disposition:

- B11 archive durability ordering: **CLEAN**
- B10 exact archive identity: **CLEAN**
- B5 semantic generation compatibility: **NOT_CLEAN**
- B7 pre-publication producer finality: **NOT_CLEAN**
- P2-C provider replay correction: **PRESERVED**
- P2-J primary-success authority: **PRESERVED**
- P2-K role-aware command identity: **PRESERVED**

P2-B remains one canonical P2 root. The two residuals below do not increment the blocker count.

Canonical blocker recount remains:

- `P0 2`
- `P1 3`
- `P2 24`

Overall verdict remains `NOT_CLEAN`.

## B11 — CLEAN

`DownloadArchiveAuthority.atomicWrite()` now writes bytes directly through a `FileOutputStream`, calls `flush()`, then performs `FileDescriptor.sync()` while the same stream is still inside its `use` scope. Only after that durability boundary does it close the stream and replace the target.

The prior closed-descriptor ordering defect is removed.

The production-sync test does not replace the sync operation: it sets `syncForTesting = null`, observes the descriptor immediately before sync, then allows the real default `stream.fd.sync()` to execute. A separate injected-sync-failure test confirms that promotion does not report success and the prior global/private authority remains available.

No B11 residual was found in this review.

## B10 — CLEAN

`DownloadArchiveIdentity` models archive authority as an exact `(extractorKey, mediaId)` pair. It accepts only two-token well-formed archive lines and resolves only positively known YouTube source forms into the exact `youtube + videoId` identity.

Both production consumers changed to the same policy:

- Manual queue duplicate handling in `DownloadViewModel`
- Observe queue duplicate handling in `ObserveSourceWorker`

The old second-token extraction plus `url.contains(videoId)` authority is gone from these paths. Distinct extractor keys, ID suffix/prefix collisions, malformed records, and arbitrary URL substrings no longer become archive authority.

No B10 residual was found in this review.

## B5 — NOT_CLEAN

### Residual: production semantic fingerprint hashes the outer config-file launcher, not the effective producer configuration

The intended B5 invariant requires exact compatibility of the effective producer/output contract before a completed predecessor may be adopted.

The new `DownloadProducerSemanticFingerprint` looks reasonable when called with a command string that directly contains semantic yt-dlp options. However the production request builder does not expose most effective options in that outer command.

Production ordering in `YTDLPUtil.buildYoutubeDLRequest()` is:

1. build the effective producer option set in the local `request` `StringJoiner` — format selection (`-f`), subtitles, metadata/post-processing, retry/network options, user-authored command/extra options, output template/path controls, and other producer semantics;
2. write `request.toString()` to a fresh app-generated config file;
3. add only `--config-locations <that file>` to the returned outer `ytDlRequest`, plus a small set of outer arguments such as source identity, generated cache path, and structured output carrier;
4. return that `ytDlRequest`.

`prepareYtdlpPhase()` then sets `YtdlpAttempt.command` from `parseYTDLRequestString(request)` on that returned outer request. `producerSemanticFingerprint()` hashes this `initialAttempt.command`.

`DownloadProducerSemanticFingerprint.normalizeTokens()` explicitly replaces the value of `--config-locations` with `<runtime>` and does not read/hash the referenced config file contents.

Therefore two executions can have the same source and output-plan/publication bits while having materially different effective producer configurations inside two different generated config files, yet receive the same fingerprint. Concrete examples include changes to format expression, subtitle behavior, recode/merge/post-processing options, metadata transformations, or other settings serialized into the config file.

This is not a theoretical helper mismatch: it is the exact production fingerprint input path.

The focused fingerprint unit test does not cover this wiring. It passes `-f` / `-o` and other semantic options directly in the string supplied to `DownloadProducerSemanticFingerprint.fingerprint()`. Production instead hides those options behind the generated `--config-locations` file that the normalizer intentionally erases.

### Impact

A completed E1 producer record can compare compatible with E2 even though E2's effective yt-dlp configuration differs. E2 may then adopt E1 output/provenance instead of executing the newly requested semantic configuration.

This violates B5 and the governing rule that `operationId`, runtime launcher identity, or output directory identity alone cannot prove semantic producer compatibility.

### Required correction

Fingerprint the **effective sanitized config semantics**, not only the outer launcher command.

Acceptable directions include:

- compute the semantic fingerprint from the pre-materialization effective option/token representation before it is written to the generated config file; or
- include a deterministic hash/canonical representation of the generated config contents while still excluding only truly runtime/secret material.

The fingerprint must change when any producer/output-affecting effective option changes and remain stable only across differences that are actually runtime-only.

Required production-wiring regression:

- build two real production requests for the same source/output destination but different effective format/subtitle/post-processing configuration;
- prove the resulting producer semantic fingerprints differ even though both outer requests use generated `--config-locations` paths;
- prove runtime-only config-file pathname changes with identical effective contents do not change the fingerprint.

## B7 — NOT_CLEAN

### Residual: startup recovery can retire a durable COMPLETE producer generation before a successor adopts it

The new producer carrier correctly creates a durable pre-publication state and `markComplete()` can persist exact output paths before publication starts. This covers the intended crash window at the producer boundary.

However the startup/retry consumer does not preserve that COMPLETE finality through abandoned-row recovery.

Exact sequence:

1. E1 is `Active` and owns a `DownloadProducerRecovery` record.
2. yt-dlp completes successfully.
3. `DownloadWorker` persists `DownloadProducerRecovery.Phase.COMPLETE` with exact output paths.
4. process death occurs before a `PublicationRecoveryJournal` or primary-success authority is established.
5. first `DownloadExecutionRecovery.reconcile()` calls `reconcileProducerRecords()` before abandoned-row cleanup. Because the current Room row is still the same E1 and `Active`/`PostProcessing`, the COMPLETE producer record is left in place.
6. the same recovery pass subsequently treats the now-ownerless Active E1 row through generic abandoned-download cleanup. `cleanupStoppedDownloadExecution()` has no COMPLETE-producer-finality branch. With no primary success / user-stop / History-finalization authority, it calls `DownloadRepository.requeueRunningDownload()`.
7. `DownloadDao.requeueActiveDownload()` changes the row to `Queued`, sets `downloadStartTime=0`, and clears `executionId`.
8. producer debt remains, so `DownloadExecutionRecovery` installs another live recovery pass (`DownloadProducerRecovery.hasPendingForDownload()` is part of `debtRemains`).
9. on the next recovery pass, the E1 producer record is still `COMPLETE`, but the Room row is no longer Active E1 and there is no live E1 owner or publication journal.
10. `reconcileProducerRecords()` falls through to the generic unpublished-generation path: it proves native quiescence and calls `DownloadProducerRecovery.retireUnpublishedAfterQuiescence()`.
11. that method deletes exact output paths when possible or quarantines the exact staging root, transitions/retire the producer record, and removes the active producer-finality carrier.
12. the Queued Download can later be claimed as a new execution with no remaining COMPLETE E1 record to adopt, allowing producer replay.

The special `NO_OUTPUT_COMPLETE` branch does not have this problem: recovery explicitly converts it into exact no-History primary-success authority. The residual is the ordinary `COMPLETE` path with real output files and no publication journal yet.

### Impact

A process death after authoritative producer completion but before publication can still erase the only durable proof of completed producer work before a successor has adopted it. The implementation therefore does not satisfy B7's required safety+liveness invariant.

### Required correction

`COMPLETE` must be treated as producer finality, not as unpublished disposable staging.

At minimum:

- generic abandoned-row recovery must not requeue/retire a COMPLETE generation in a way that loses adoption authority;
- a COMPLETE record must survive until a successor has durably adopted it or a stronger publication/primary-success authority has taken ownership;
- only PREPARED/RUNNING/OUTPUT_UNPROVEN generations may enter the ordinary unpublished cleanup/supersession path after native quiescence unless an explicit incompatible successor has first established durable replacement authority;
- recovery ordering must be idempotent across repeated startup/live reconciliation passes;
- the exact output must never be deleted/quarantined merely because the mutable Download row was requeued after process death.

Required production-wiring regression:

`COMPLETE producer record + exact Active E1 row + no publication journal + no primary-success authority + no live worker`

→ run startup/recovery repeatedly

→ exact COMPLETE record/output remains adoptable until a successor takes ownership

→ successor performs finalization/publication without invoking yt-dlp again

→ producer invocation count remains zero across the recovery sequence.

## B5/B7 test-gap note

The new unit tests establish useful local properties of `DownloadProducerRecovery` and `DownloadProducerSemanticFingerprint`, but they do not exercise the two failing production compositions above:

- effective config -> generated config file -> outer `--config-locations` request -> semantic fingerprint;
- COMPLETE producer carrier -> startup `reconcileProducerRecords()` -> generic abandoned Active-row requeue -> second reconciliation -> producer-carrier retirement.

The reported JVM pass is implementation-agent evidence only and does not contradict these source-semantic residuals.

## P2-C preservation — CLEAN for regression scope

No source regression was found that reintroduces provider creation for an exact already-known prior destination. Prior-publication recovery remains exact/finalization-oriented, and the new semantic-fingerprint checks narrow which prior publication journals may be consumed.

`PUBLICATION_FINALIZATION_PENDING` remains distinct from genuinely unknown provider outcomes.

## P2-J preservation — CLEAN for regression scope

The Room-backed exact primary-success authority remains the stronger terminal producer result. Worker/startup cleanup checks primary success before generic requeue, and producer-recovery state is retired from primary-success finalization only after the stronger authority is available.

The new `NO_OUTPUT_COMPLETE` recovery path also uses the existing exact no-History primary-success authority rather than fabricating a History row.

No P2-J regression was found.

## P2-K preservation — CLEAN for regression scope

Role-aware command identity remains in place. The follow-up change additionally handles recognized options with missing required values conservatively instead of allowing later URL-looking tokens to gain fabricated positional-source authority.

No P2-K regression was found.

## Review-basis consequence

Because canonical P2-B remains open at the completed SHA, the contiguous independently CLEAN Review Basis must **not** advance to `4da5b67bbb2eb7d80184791b8deb34ceb821d22c`.

The previously fixed basis remains:

`c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`

This does not undo source-level closures already established for P2-C/J/K; it only means the implementation commit chain is not yet a contiguous independently CLEAN basis.

## Independent execution

No JVM, Android instrumentation, emulator, or physical-device tests were independently executed in this review. The implementation report states focused/full JVM and compile checks passed, but those remain implementation-agent evidence.

INDEPENDENT EXECUTION: NOT EXECUTED
