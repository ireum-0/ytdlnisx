# Track A checkpoint — Download publication/finalization authority

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

Independent execution: **NOT EXECUTED**

Working canonical count remains:

- P0: 0
- P1: 0
- P2: 8

This checkpoint adds no new canonical P2. It strengthens and decomposes existing P2-B, P2-C, P2-J, and the later P2-H correction requirements.

## P2-B — additional confirmed subpaths / requirements

### B5 — multi-generation recovery has no authoritative supersession order

`PublicationRecoveryJournal.Record` contains `subjectId`, `operationId`, `executionId`, and random `attemptId`, but no monotonic generation/supersession relation.

`PublicationRecoveryJournal.readAll()` consumes `storageDirectory.listFiles()` without sorting. `findDownload()` only filters by `downloadId + operationId`.

`DownloadWorker.recoverPriorPublication()` iterates all returned prior records, adds every published destination to `recovered`, and writes `sourceDestinations[artifact.sourcePath] = destination` in that enumeration order.

Therefore multiple prior retry generations can contribute destinations nondeterministically; the last record enumerated for a repeated source path wins the source→destination mapping even though filesystem enumeration is not a semantic generation order.

Correction must introduce explicit deterministic supersession/generation authority. Do not sort random UUID `attemptId` and treat that as semantic order.

### B6 — exact prior publication adoption does not suppress the new producer

Production path in `DownloadAttemptRunner.run()`:

1. resolve current output plan;
2. `recoverPriorPublication(outputPlan)`;
3. recovered exact destinations are placed in `recoveredPublishedPaths`;
4. current `YtdlpPhaseInput` is created with those paths;
5. `prepareYtdlpPhase(...)`;
6. `executeYtdlpPhase(...)` still runs.

Thus E2 may adopt E1's exact publication and still execute a new yt-dlp producer.

Required correction invariant:

> Adoption of an exact prior publication and execution of a new producer are mutually exclusive authority decisions for the same semantic operation generation.

This applies to History and incognito/no-History success paths alike.

### Semantic compatibility cannot use `operationId` or `SAME_SETTINGS` alone

Existing retry metadata may preserve `operationId` across retries, while the effective request/output contract is reconstructed from current preferences. Current settings can change filename constraints, SponsorBlock behavior, metadata/subtitle/thumbnail embedding, codecs/languages, format sorting, post-processing, and path/output planning.

Correction must use a durable normalized effective producer/output contract fingerprint or an equivalent immutable semantic witness. `operationId` alone is not such a witness; `retryStrategy == SAME_SETTINGS` is not such a witness.

## Rejected adjacent hypothesis — prior publication provenance immediately disappears after E2 adoption

Not confirmed.

`recoverPriorPublication()` marks the old journal COMPLETE and may retire the old cache/staging ownership carrier, but keeps the publication journal handle in `recoveredPublicationJournals`. The journal is not immediately cleared at adoption.

Therefore process death immediately after E2 adoption does not, by itself, erase all exact E1 destination provenance. Do not create a new finding for this hypothesis.

## P2-C — refined root and correction boundary

The duplicate provider creation is primarily a **same-worker retry** problem, not the cold-start `recoverPriorPublication()` path.

Once provider D1 is durably committed, the journal artifact has a non-null `destinationPath`. Cold-start recovery's `remainingSources()` excludes that artifact from provider publication replay.

However, when the original FileUtil provider publication has already committed D1 and source deletion fails, the worker may retry the still-existing authoritative source inside the same execution. `reserveIntent()` returns success when `destinationPath` is already nonblank, so the provider creation boundary can be crossed again, producing collision-suffixed D2.

Required correction:

> An artifact whose exact provider destination is already durably committed is in `source-retirement-only` state. Same-execution retry and cross-execution recovery must never call provider create for that source again.

A two-state `retry / fail` treatment is insufficient; the implementation must represent at least:

- publication still required;
- exact destination already durable, source retirement only;
- unresolved/UNKNOWN external publication.

Required negative production-boundary assertion:

`D1 exact committed + source still exists -> provider create callback is NOT invoked again`.

## P2-J — expanded confirmed scope

P2-J is broader than startup recovery requeue after ordinary History commit.

### J1 — process death after ordinary History commit

Ordinary History insert durably creates `HistoryItem(downloadId = downloadItem.id, downloadPath = finalPaths, ...)` while the Download row still exists. Startup recovery recognizes only committed History *replacement* as finalization authority, so an ordinary committed success can be requeued.

Existing classification remains confirmed P2-J.

### J2 — post-commit ancillary failure can downgrade durable ordinary success to Error

`runHistoryPersistence()` performs ordinary:

`withOwnedExecutionSideEffect { historyKeywordAssignments.insertHistory(historyItem) }`

then later runs `AutomaticKeywordRuleEngine.applyToHistory(...)` and other ancillary work.

If an exception occurs after the ordinary insert has committed, the catch block asks only whether a **History replacement** committed. If not, it sets:

`preserveQueueRecord = true`
`downloadItem.status = Error`

and persists the Error state.

Therefore H1 may already be a durable ordinary success while the same Download is projected back to Error. This is a semantic downgrade even though the normal manual retry path performs an additional `hasValidOutput` check and blocks retry while a History row for this Download still references an existing output. The startup-recovery J1 path does not pass through that retry-policy guard; furthermore an Error projection must not be treated as equivalent to a correctly retained committed-primary finalization state.

This is the same root as P2-J: ordinary History commit is not recognized as stronger primary/finalization authority.

### J3 — late user stop can downgrade durable ordinary success to Cancelled/Paused

Replacement commit explicitly sets `historyReplacementCommitted = true` and comments state that cancellation/ancillary failures cannot undo the primary commit.

Ordinary History insert has no equivalent durable/worker semantic state.

The ordinary insert and subsequent keyword application are separate `withOwnedExecutionSideEffect` leases. After the insert lease releases, a user stop can win the per-Download lease and durably publish Cancel/Pause. The next ordinary ancillary side effect then revalidates USER_STOP and aborts.

`hasDurableUserStopRevokedAuthority()` exempts only durably committed History replacement, not ordinary History success.

Result: durable H1 success can coexist with a Download row whose terminal semantic becomes Cancelled/Paused.

Correction must make an exact ordinary History commit a stronger primary result, with the same principle already applied to committed replacement: later cancellation or ancillary failure may affect finalization/warnings, but cannot reinterpret the committed primary result as a fresh failure/cancel/retry operation.

### J4 — file-journal phase written after History commit is not sufficient

`HistoryKeywordAssignmentRepository.insertHistory()` uses `db.withTransaction` and atomically inserts the History row plus its initial assignment/materialization work. It does **not** atomically record exact Download/execution finalization debt.

Writing `PublicationRecoveryJournal.COMMITTED` after that transaction would create a new cross-storage crash window:

`Room History commit -> process death -> file-journal COMMITTED write`

So a correct P2-J fix should prefer a finalization witness committed in the same Room transaction as the ordinary History insert (or an equivalently atomic design).

The witness must bind the exact Download/execution/operation and exact committed History identity. `History.downloadId` alone is insufficient because it is not unique and History rows do not currently carry executionId/operationId.

A viable design may update an exact Download finalization state or dedicated Room finalization record in the same transaction. Do not prescribe schema change if an existing exact Room carrier can be safely reused, but cross-storage post-commit acknowledgement alone is insufficient.

### J5 — ancillary recovery obligations

After ordinary History insert, observe-source/rule keyword work may still remain. `AutomaticKeywordRuleEngine.applyToHistory()` is largely idempotent/replacement-based, so a finalization recovery path can safely rerun it if it retains the exact History identity and needed inputs.

Correction should explicitly decide which post-History actions are correctness-relevant finalization debt rather than simply deleting the Download row as soon as the primary History insert is detected.

## Download journal phase model observation

`PublicationRecoveryJournal` documentation says it is retained until semantic terminal result is committed. However:

- `COMMITTING` / `COMMITTED` comments are Terminal-specific;
- `Handle.clear()` allows a DOWNLOAD journal to retire when phase is merely `COMPLETE`;
- DOWNLOAD therefore has no durable journal phase distinguishing `publication complete / History not committed` from `History semantic commit / row finalization pending`.

This is structural evidence supporting P2-J/B clustering, but the History transaction atomicity requirement above means simply adding a file phase after the DB commit is not enough.

## Cluster D correction model after this checkpoint

The combined P2-B/P2-C/P2-J correction should model:

`immutable effective semantic contract`
`-> deterministic exact generation/supersession authority`
`-> producer OR prior-publication adoption (not both)`
`-> exact artifact publication`
`-> provider source-retirement-only when destination already committed`
`-> publication complete / semantic commit pending`
`-> ordinary/replacement History primary commit + exact Room finalization witness`
`-> ancillary finalization debt`
`-> Download terminal finalization`
`-> publication/recovery carrier retirement`

P2-C remains provider-specific on current evidence. Raw filesystem publication has not shown the same provider-create replay boundary.

## P2-H reminder

No count change. R-cluster still needs typed recovery discovery for `PublicationRecoveryJournal` and Terminal recovery carriers, distinguishing healthy-empty from unreadable/enumeration-failed/malformed opaque debt. A malformed journal must not become `no prior publication` and permit producer admission.

## Current status

Canonical working findings remain:

- P2-B
- P2-C
- P2-D
- P2-E
- P2-F
- P2-G
- P2-H
- P2-J

Cluster T (D/E/F/G) is being corrected separately. Track A remains pinned to `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8` until that correction is submitted for independent review.
