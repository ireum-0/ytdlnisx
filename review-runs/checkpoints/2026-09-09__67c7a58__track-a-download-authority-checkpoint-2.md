# Track A Download authority checkpoint 2

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`

Ledger reference (not modified): `899328bc91e4008e39a658387396a0106c8666ec`

Independent execution: NOT EXECUTED

Working canonical count at this checkpoint: `P0 0 / P1 0 / P2 8`.

This checkpoint records source-level review against the fixed Review Basis while Cluster T (P2-D/E/F/G) is being corrected separately. No in-progress correction diff was semantically reviewed here.

## 1. P2-B extension B6 confirmed — recovered prior publication does not stop the E2 producer

`DownloadAttemptRunner.run()` resolves the current output plan and calls `recoverPriorPublication(outputPlan)` before the current yt-dlp phase. `recoverPriorPublication()` finds prior DOWNLOAD publication journals by `downloadId + operationId`, reconciles exact destinations, completes remaining publication, marks the prior journal COMPLETE, retires the prior cache/direct staging carrier, and returns the recovered destinations.

Those recovered destinations are subsequently recorded into the current execution provenance, but the normal E2 yt-dlp producer path remains reachable. There is no finalization/adoption branch that says a fully recovered prior publication is now the authoritative producer result and therefore the new producer must not run.

Classification: fold into existing P2-B as B6. Do not create a new P2.

Required correction invariant:

> Prior-execution publication adoption and current-execution producer execution must be mutually exclusive authority decisions. If an exact prior publication is adopted as the current semantic output, the current producer must not independently execute and create a competing generation.

## 2. P2-B semantic compatibility must not rely on operationId or SAME_SETTINGS

`DownloadRetryPolicy.prepare()` preserves a nonblank `operationId` across retries, including RECONFIGURED retries. Therefore `operationId` is an operation grouping identity, not proof of immutable output semantics.

The effective producer/output contract also depends on current preferences read again by the retry path. Retry labels such as SAME_SETTINGS do not themselves freeze all effective request/output semantics.

Future P2-B correction should therefore use a durable normalized effective producer/output-contract identity (or an equivalently strong immutable witness), not `operationId` or retry strategy name alone, before adopting prior outputs/source mappings.

A conservative incompatible-generation rule is acceptable where exact semantic compatibility cannot be proven.

## 3. P2-C remains provider-specific and should be fixed inside the same Download publication authority model

For MediaStore/SAF, an exact provider destination can already be durably recorded as `destinationPath = D1` while source retirement fails. On worker retry/recovery, `reserveIntent(source)` currently returns true when `destinationPath` is already nonblank. The provider create boundary can therefore be entered again even though D1 is already the exact committed destination, producing a second object D2 before later callbacks reject or reconcile it.

Raw filesystem publication does not show the same replay structure: an already reserved/committed path fences a conflicting new collision target before copy, and atomic move removes the source.

Required regression assertion:

> With exact provider destination D1 already committed and source still present, the provider create callback MUST NOT be invoked again. Recovery must perform only exact source-retirement/finalization convergence.

Keep P2-C provider-specific; do not broaden it to raw filesystem without evidence.

## 4. P2-J root expanded — ordinary committed History is not a repository-wide success authority

The ordinary History path constructs `HistoryItem(downloadId = downloadItem.id, downloadPath = finalPaths, ...)` and durably inserts it through `HistoryKeywordAssignmentRepository.insertHistory()` before several later correctness/ancillary operations and before Download-row finalization.

Unlike History replacement, ordinary History commit is not represented by a dedicated durable Download recovery disposition/phase and is not recognized by `isCommittedHistoryReplacementLocked()`.

This produces several subpaths under one root finding.

### J1 — process death after ordinary History commit before Download row deletion

Startup discovery includes running Active/PostProcessing rows, but direct committed-History discovery is replacement-only. Generic recovery can therefore requeue an execution that already has durable ordinary History success.

Queue admission guards are likewise replacement-specific, so a later E2 can run.

### J2 — same-process post-insert exception can downgrade durable success to Error

For ordinary History, `insertHistory(historyItem)` can commit successfully and then `AutomaticKeywordRuleEngine.applyToHistory(...)` can fail. The History catch path only recognizes a committed History replacement as a stronger primary result. Ordinary commit falls through generic failure handling, allowing the Download row to be persisted as Error even though its History success already exists.

This is not merely a crash-recovery bug; it is an ordinary exception boundary under the same missing committed-success authority.

### J3 — late user stop/status mutation can outrank ordinary committed success

`DownloadRepository.convergeUserStopSemantic()` returns `COMMITTED_HISTORY_ALREADY_WON` only through `isCommittedHistoryReplacementLocked(current)`. Other status/save mutation guards also protect committed replacement only.

Therefore an ordinary History row can already be committed while later Cancel/Pause/requeue/status/save logic still treats the Download row as mutable pre-success execution state.

Correction must generalize the concept of an exact committed success rather than adding a startup-only special case.

## 5. Ordinary History row alone is not a sufficient exact success witness

`HistoryDao.getItemByDownloadId(downloadId)` is `LIMIT 1`; `downloadId` is not a unique History key, and `HistoryItem` does not itself carry Download executionId/operationId.

Therefore future correction must not use merely:

`EXISTS history WHERE history.downloadId = downloads.id`

as universal exact-generation success proof.

Preferred design:

- exact Download execution/publication lifecycle record owns the transition;
- once ordinary History is committed, record a semantic-commit phase bound to the exact Download execution;
- bind the exact inserted `historyId` (or equivalently strong immutable commit identity) into that durable phase;
- recovery and all repository mutation guards consult the same committed-success authority;
- stale/mismatched History rows cannot terminalize a newer execution.

## 6. Download journal currently cannot distinguish publication-complete from History-committed

`PublicationRecoveryJournal.Handle.clear()` permits Download journal retirement only from `Phase.COMPLETE`. Download production does not use COMMITTING/COMMITTED as semantic phases the way Terminal does.

Consequently these two states are durably indistinguishable in the Download journal:

1. exact publication is complete, but History semantic work has not committed;
2. ordinary History has committed, but Download finalization remains.

This is the direct state-model gap behind P2-J and should be addressed in Cluster D.

The future lifecycle should distinguish at least:

`producer semantic identity -> exact publication/adoption -> source retirement -> PUBLICATION_COMPLETE -> HISTORY/NO-HISTORY semantic continuation -> EXACT SUCCESS COMMITTED -> Download finalization -> authority retirement`.

## 7. Incognito/no-History paths must be included in B6 correction

Cluster D cannot be designed only around History. A Download can intentionally skip History (for example incognito). Exact publication COMPLETE must still prevent a later execution from blindly rerunning the producer after process death.

Therefore PUBLICATION_COMPLETE is a producer-finalization boundary shared by History and no-History paths; History commit is a later semantic state where applicable.

## 8. Post-insert ordinary semantics must be modeled deliberately

Ordinary History insertion is followed by correctness-relevant keyword application. Duplicate-download handling can also persist a pending duplicate pair after insert.

Do not simply declare every ordinary row insertion immediately equivalent to fully finalized Download success unless the correction either:

- includes those required post-insert operations in the same semantic commit protocol, or
- gives them their own durable post-commit debt that recovery can resume without producer replay.

The blocker requirement is that producer/publication success must never be reopened. Ancillary/deferred semantics must have explicit ownership rather than being lost or causing native replay.

## 9. P2-H reconfirmed; no count change

`PublicationRecoveryJournal.readAll()` treats journal directory enumeration failure as an empty list and drops malformed/unreadable records through `mapNotNull`/validation filtering.

`TerminalCacheOwnership.listRecoveryRoots()` similarly drops malformed/unreadable recovery carriers and converts root enumeration failure to an empty result.

`TerminalExecutionRegistry.admit()` delegates to `TerminalPublicationRecovery.admit()`. If both durable namespaces are unreadable but represented as empty, admission can reach ACQUIRED and start a new Terminal execution.

Preferred R-cluster model is typed discovery, distinguishing at least:

- healthy and empty,
- healthy with records,
- namespace/discovery unavailable,
- opaque/malformed durable debt.

The latter two must fail closed at admission.

Native process marker recovery was checked separately and has explicit fail-closed candidate/debt handling, so it is not added to P2-H on current evidence.

`DownloadCacheOwnership.listOwnedRoots()` has a similar fail-open shape, but no production admission/recovery impact was proven in this checkpoint; keep it as scope-adjacent and do not add it to canonical P2-H without a concrete consumer path.

## 10. Rejected adjacent Terminal marker-reuse hypothesis

`TerminalCacheOwnership.ensureMarker()` can overwrite a malformed marker when no recovery carrier exists. However production cached Terminal roots are UUID/task-token scoped (`TERMINAL/$terminalTaskToken`), so a new Terminal execution does not reuse the prior execution directory.

No concrete E2 same-root reuse path was established. Do not create a new finding from this hypothesis.

## 11. Cluster guidance after this checkpoint

Current intended correction clusters remain:

- Cluster T: P2-D + P2-E + P2-F + P2-G (currently being implemented separately)
- Cluster D: P2-B + P2-C + P2-J
- Cluster R: P2-H

Cluster D should be one durable Download lifecycle correction, not three point fixes. Its key state machine must own semantic compatibility, prior-generation adoption, provider exact-destination replay fencing, publication-complete producer finality, exact ordinary History commit, no-History finalization, repository mutation guards, and deterministic retirement.

No new canonical P2 was added by this checkpoint.

Working count remains:

`P0 0 / P1 0 / P2 8`

INDEPENDENT EXECUTION: NOT EXECUTED
