# Independent P2-B residual closure review — 6763fb1b

- Prior independently reviewed P2-B completion SHA: `4da5b67bbb2eb7d80184791b8deb34ceb821d22c`
- B5 implementation commit: `92d48bf3554f06b7f73d9efa211238f3189e31dd`
- B7 implementation / reviewed final SHA: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Verified implementation chain: `4da5b67bbb2eb7d80184791b8deb34ceb821d22c` -> `92d48bf3554f06b7f73d9efa211238f3189e31dd` -> `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Remote implementation branch exact match: YES (`checkpoint/pre-baseline-review` = `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`)
- Plan reference: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Master Plan canonical SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Master Plan modified: NO
- Implementation branch modified by this review: NO
- Verdict for P2-B: `CLEAN / CLOSED`
- Global gate: `NOT_CLEAN`

## Review basis and count authority

The authoritative private handoff before this completion had P2-B open with B5/B7 residuals, contiguous independently CLEAN basis `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`, and canonical count `P0 2 / P1 3 / P2 27`.

`review/remediation` contains later scheduled/narrow checkpoints, including provisional inventories and a B7-only finality review at this same implementation SHA. Per the stable review protocol and the handoff itself, those scheduled/narrow checkpoints do not silently override the authoritative canonical dispositions/count. This checkpoint therefore applies only the independently established P2-B closure delta to the authoritative handoff count. Reconciliation of unrelated scheduled findings remains separate work.

P2-B is one canonical P2 root. Closing B5 and B7 does not subtract two roots; it closes the single P2-B root once.

Canonical count after this review-only semantic delta:

- `P0 2`
- `P1 3`
- `P2 26`

## Completed scope

- Verified the exact remote implementation HEAD and both-commit ancestry from `4da5b67b...` to `6763fb1b...`.
- Verified the B5+B7 range is exactly two commits ahead of `4da5b67b...` and reviewed the production files changed by that range.
- Re-read the prior independent P2-B residual proof at `24525ade6297528afff2d963e9b0a274a07d75d1` and treated its B5/B7 required corrections as the acceptance basis.
- Re-traced B5 from production `YTDLPUtil.buildYoutubeDLRequest()` effective generated-config construction through semantic snapshot capture, normalization, fingerprint construction, and both `DownloadWorker` fingerprint call sites.
- Re-traced B7 through `DownloadProducerRecovery`, `DownloadExecutionRecovery`, `DownloadSchedulerAdmission`, `DownloadWorker` successor establishment/adoption, and exact recovered output provenance.
- Inspected the production-wiring regression tests added for B5 and B7 as source evidence. The implementation report's JVM/compile results were treated as reported evidence only; this reviewer did not independently execute tests.
- Rechecked changed shared boundaries for regression against B10/B11 and previously preserved P2-C/P2-J/P2-K/P2-D/E/F/G/H semantics.

## B5 — CLEAN

Prior residual: the durable semantic fingerprint saw the outer `--config-locations <generated file>` launcher while most effective yt-dlp producer semantics lived inside that generated config; runtime path normalization could therefore erase the only distinction between materially different producer configurations.

Current production fixes the exact wiring defect:

1. `YTDLPUtil.buildYoutubeDLRequest()` finishes constructing the effective generated config contents before writing the runtime config file.
2. It registers an in-memory `YtdlpProducerSemanticSnapshot` on the exact returned `YoutubeDLRequest`, containing those effective generated-config contents plus runtime-only paths.
3. `DownloadWorker.producerSemanticFingerprint(...)` retrieves that snapshot from the exact prepared request and passes both effective semantics and runtime paths into `DownloadProducerSemanticFingerprint`.
4. Both production fingerprint boundaries reviewed pass the exact prepared request rather than only an outer command string.
5. `DownloadProducerSemanticFingerprint` tokenizes and hashes the normalized effective generated-config semantics together with normalized outer request, output-plan and publication semantics.
6. Generated config/cache/staging/archive/runtime paths are normalized to placeholders, so temporary pathname churn does not become semantic identity.
7. The effective option tokens themselves remain in the SHA-256 input, so producer-affecting format, subtitle, post-processing and other generated-config changes alter compatibility.
8. Only the resulting SHA-256 fingerprint is persisted; the raw effective config snapshot remains process-local and was not found to be persisted or logged by this change.

The production-wiring test source builds real requests through `YTDLPUtil` and covers materially different format/subtitle semantics versus runtime-only generated-path differences.

No B5 residual was reproduced at `6763fb1b...`.

## B7 — CLEAN

Prior residual: after process death following durable producer COMPLETE but before publication, generic abandoned-row cleanup could requeue/clear the mutable Download owner; a later recovery pass could then retire the still-unadopted COMPLETE producer record and its exact output, allowing native producer replay.

Current production closes that sequence:

1. `DownloadExecutionRecovery.reconcileProducerRecords()` now explicitly retains `COMPLETE` producer finality when no stronger exact publication/primary-success authority owns the result.
2. Generic unpublished cleanup can retire weaker producer phases without treating ordinary COMPLETE as disposable staging.
3. `DownloadProducerRecovery.hasBlockingForAdmission()` excludes COMPLETE from the admission fence while retaining other unresolved phases as blockers; this lets a queued successor claim execution solely to resolve/adopt the durable predecessor.
4. Compatible resolution still requires exact persisted semantic fingerprint equality.
5. `DownloadWorker.establishProducerAuthority()` validates the predecessor output root/manifest, durably prepares a successor, marks the successor through durable producer authority to COMPLETE with the exact predecessor outputs, and only then retires the predecessor.
6. The incompatible path first durably prepares a distinct successor and only afterward explicitly supersedes/retires the predecessor; incompatible outputs are not merged into the new generation.
7. The adopted execution path records the exact recovered producer paths and returns the completed producer result without crossing the native yt-dlp producer boundary.
8. `DownloadOutputProvenance` accepts recovered paths only under the exact stored producer root/direct root; adoption does not wildcard-scan or merge arbitrary staging contents.
9. Existing stronger publication/primary-success ownership remains capable of retiring producer authority through its explicit finality path.

The added production-wiring test source reconstructs the prior failure sequence with a COMPLETE producer, runs reconciliation twice, then lets a real successor worker adopt; its native-boundary hook fails if yt-dlp is invoked. This is the required regression shape, although it was not independently executed here because no device/emulator execution was available to this reviewer.

No B7 residual was reproduced at `6763fb1b...`.

## Preservation / regression review

- B10 exact extractor-plus-media-ID archive identity: no changed production boundary in this repair range was found to weaken it.
- B11 sync-before-close archive durability: archive atomic-write durability implementation was not altered by this repair range.
- P2-C exact provider destination finalization-only recovery: no changed boundary was found to broaden provider replay authority.
- P2-J Room-backed primary-success authority: stronger exact primary-success handling remains ahead of generic producer retirement.
- P2-K role-aware command/source identity: outer command/source semantic normalization remains composed with the new effective-config semantics rather than replaced by it.
- Previously CLEAN P2-D/E/F/G/H terminal/recovery behavior: no new conflicting ownership/finality path was found in the changed shared recovery/admission surfaces.

No new P0/P1/P2 semantic root was established by this review.

## P2-B final disposition

- B5 effective producer semantic compatibility: `CLEAN`
- B7 COMPLETE producer finality/adoption: `CLEAN`
- B10 exact archive identity: previously independently `CLEAN`, preserved
- B11 archive durability ordering: previously independently `CLEAN`, preserved
- P2-C/P2-J/P2-K and P2-D/E/F/G/H: preserved on reviewed changed boundaries
- P2-B canonical root: `FIXED / CLOSED`

Because the prior P2-B full-wave review had already independently reviewed the cumulative state through `4da5b67b...` and identified only B5/B7 as residuals in this root, and this review closes those residuals on the exact descendant `6763fb1b...` without finding a repair regression, the contiguous independently CLEAN review basis may advance to:

`6763fb1be188fb000b9e9a665c7b3fe349fd40ca`

The repository remains globally `NOT_CLEAN` because unrelated canonical blockers remain open.

## Verification evidence classification

Reported by implementation agent, not independently executed here:

- focused JVM: 86 passed / 0 failed / 0 skipped
- full JVM: 591 passed / 0 failed / 0 skipped
- KSP: PASS
- debug Kotlin compile: PASS
- Android-test Kotlin compile: PASS
- `git diff --check`: PASS
- instrumentation: NOT EXECUTED (device/emulator unavailable)

Independent source/structural review: EXECUTED.
Independent JVM/instrumentation/device execution: NOT EXECUTED.

INDEPENDENT EXECUTION: NOT EXECUTED