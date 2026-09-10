# Independent full-wave review — 09af51d9 -> 1a3f7d2c

- Starting implementation SHA: `09af51d92209d6735e283622fa7d3680414523bc`
- Reviewed completed SHA: `1a3f7d2c81f79454426e42b394d3328cfb7683d3`
- Reviewed commits: `8114550c96c4ed2c9f2eea92aca2797fdafd2994`, `96396b6ed84495d0b9563765420a943a7f369de4`, `650884d11a894124c3ac4ed8b54768073ecc740b`, `1a3f7d2c81f79454426e42b394d3328cfb7683d3`
- Implementation branch remote HEAD verified at reviewed SHA: YES
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Dispositions

### P2-K — CLOSED / source-level CLEAN

`DownloadConfigurationDuplicatePolicy` now uses `YtdlpOptionOwnership` so only positively identified positional source tokens are canonicalized. Recognized/ambiguous option values remain configuration identity, `--` terminator semantics are preserved, and unknown option arity is handled conservatively by preserving contiguous non-option tokens instead of treating them as source URLs. The second K commit closes the ambiguity left by the first K commit.

### P2-C — CLOSED for the canonical provider-replay defect

Exact prior Download publication recovery now validates the prior journal/destination and performs source-retirement/finalization only. A known exact provider destination no longer enters another provider-create boundary and is no longer reclassified as UNKNOWN merely because source retirement remains. `PriorPublicationFinalizationRequiredException` distinguishes source-retirement debt from already-retired exact prior publication.

Legacy journals still do not carry enough immutable semantic metadata to reconstruct an ordinary History continuation. That broader producer/publication-to-semantic-finalization gap is retained under P2-B rather than double-counted as P2-C after the provider replay invariant is satisfied.

### P2-J — CLOSED for ordinary committed-History primary-success authority

A new Room-backed `download_primary_success_authorities` table records exact `downloadId + operationId + executionId + historyId/noHistory + semanticFingerprint + archiveDelta + phase`. Ordinary History insertion and exact primary-success authority creation occur in one Room transaction. Queue admission, requeue/status/save/user-stop paths and worker stop policy consult primary-success authority. Startup recovery enumerates pending primary-success authorities independently of the Download row and finalizes them under the exact execution side-effect lease.

The no-History implementation is complete for explicit incognito success. Non-incognito no-output success such as a yt-dlp archive hit does not create this authority; that pre-finalization producer-completion gap remains part of P2-B/B7 rather than reopening P2-J.

### P2-B — REMAINS OPEN / NOT CLEAN

The wave correctly redirects app-managed `--download-archive` execution to a generation-private archive and promotes only after exact primary success. However the canonical P2-B root is broader than this sub-fix and remains open:

1. B5 semantic compatibility/supersession remains incomplete. Prior publication recovery is still selected by `downloadId + operationId` and does not require the current immutable producer/output semantic fingerprint before the current execution is finalized from prior publication state. `operationId` is not sufficient semantic compatibility because reconfigured retries can preserve it.
2. B7 pre-publication producer/post-processing crash ownership remains. Exact cache ownership/artifact manifest can exist before a publication journal or primary-success authority exists. No recovery consumer converts that state into deterministic continuation/supersession, so the old generation can continue to block later attempts or be generically requeued without a completed producer-generation authority.
3. B10 archive identity consumer remains unchanged. Manual duplicate checking and Observe parsing discard the extractor portion of `<extractor> <video-id>` archive keys and then use raw `url.contains(id)`, so distinct media identities can be falsely suppressed.
4. B11 remediation-introduced archive durability defect: `DownloadArchiveAuthority.atomicWrite()` closes the `OutputStreamWriter` (and therefore its underlying `FileOutputStream`) before calling `output.fd.sync()`. Production `prepare()` and `promote()` can therefore attempt `sync()` on a closed descriptor. The focused `DownloadArchiveAuthorityTest` replaces `syncForTesting` with a no-op, so its PASS does not cover the production sync ordering.
5. Non-incognito successful no-output producer paths such as an archive hit still do not record an exact no-History primary-success authority before final completion; this is the previously identified B7 producer-finality coverage gap.

B11 is a subcase of P2-B and does not add a new canonical P2 count.

## Regression preservation

No new source-level regression was found in previously CLEAN P2-D/E/F/G/H behavior. No Terminal lifecycle production file changed. The `PublicationRecoveryJournal` semantic change in this wave is limited to prior-publication finalization state and does not weaken typed discovery/fail-closed behavior. Primary-success recovery additions preserve exact execution leases and positive-live-owner checks.

## Migration 61 -> 62

Source-level migration wiring is internally consistent: DB version is 62, the new entity/DAO is registered, `Migrations.kt` creates the same table/indices represented in exported schema 62, and existing instrumentation migration smoke tests include supported older-version-to-current upgrade paths. This wave did not independently execute those instrumentation tests; the implementation report states instrumentation failed before execution because APK installation timed out.

## Execution evidence

Implementation-reported evidence: focused JVM regression classes PASS; full JVM 570 PASS; KSP PASS; debug Kotlin compile PASS; Android-test Kotlin compile PASS; `git diff --check` PASS. Instrumentation did not execute; physical Samsung device did not execute; no GitHub CI/status run was reported.

This independent review did not execute tests, emulator/device runs, or migration instrumentation.

## Recount

The false-positive/scope audit immediately before this wave established distinct working count `P0 2 / P1 3 / P2 26`.

This wave independently closes canonical P2-C, P2-J, and P2-K. P2-B remains open. No new canonical root is added by B11.

Updated working count: `P0 2 / P1 3 / P2 23`.

Global verdict remains `NOT_CLEAN`; P2-L, P2-M, the other previously confirmed Track A P0/P1/P2 findings, and P3/hardening work are untouched by this wave.

## Review Basis

Do not advance the contiguous independently-CLEAN Review Basis to `1a3f7d2c...` because P2-B remains open at the completed SHA. The prior fixed Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d` until a contiguous independently CLEAN wave is established.

INDEPENDENT EXECUTION: NOT EXECUTED
