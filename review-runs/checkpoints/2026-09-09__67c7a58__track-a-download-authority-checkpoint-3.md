# Track A Download authority checkpoint 3

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`

Ledger reference (not modified): `899328bc91e4008e39a658387396a0106c8666ec`

Independent execution: NOT EXECUTED

Working canonical count remains: `P0 0 / P1 0 / P2 8`.

This checkpoint continues Track A against the fixed Review Basis while Cluster T is being implemented separately. No in-progress Cluster T diff is reviewed here.

## 1. P2-B/B5+B6 production impact confirmed through finalPaths and History

Prior DOWNLOAD publication recovery is not merely diagnostic provenance. Recovered destinations are inserted into the current `DownloadOutputProvenance` attempt, the E2 producer still executes, and later output processing constructs final success paths as a union of prior recovered destinations and current E2 move/direct destinations.

Representative production forms include:

- `finalPaths = recoveredPublishedPaths + exactDirectPaths`
- `finalPaths = recoveredPublishedPaths + exactMovedPaths`

Those `finalPaths` are then consumed by quality validation and stored directly in `HistoryItem.downloadPath`.

Therefore if E1 and E2 differ semantically and produce different outputs, outputs from two generations can become one current success/History result.

This is existing P2-B, not a new finding.

Required invariant:

> One semantic Download result must be owned by one deterministic producer generation. Independent prior generations must never be unioned into one current provenance/finalPaths set unless they were explicitly defined as one immutable continuation generation before either producer ran.

## 2. P2-B/B5 deterministic supersession requirement strengthened

`PublicationRecoveryJournal.findDownload()` returns every DOWNLOAD record matching the same numeric Download ID and operationId. The journal inventory is based on filesystem enumeration and does not establish a semantic latest/superseded order.

`recoverPriorPublication()` iterates all matching prior records, unions their recovered destinations, and assigns `sourceDestinations[sourcePath] = destinationPath` while iterating. Multiple historical generations may therefore contribute outputs, and repeated source-path mappings have last-iteration-wins behavior without a semantic generation order.

The existing code then uses `recoveredSourceDestinations` to suppress a source recreated by E2 when a recovered destination exists. Thus arbitrary multi-generation selection is not merely metadata: it can decide which E2 source is discarded as an already-published duplicate.

Correction must establish one deterministic authoritative generation/supersession relation before adoption; filesystem enumeration order or UUID ordering is not semantic authority.

## 3. P2-B/B7 confirmed — pre-publication producer recovery fixed point

A new subcase of existing P2-B is confirmed.

Cached Download execution establishes `DownloadCacheOwnership` marker authority before native I/O. After yt-dlp returns, exact current-attempt artifacts are persisted into the cache artifact manifest. However the `PublicationRecoveryJournal` is not created until later, immediately before publication/move in `runOutputProcessing()`.

There is therefore a durable pre-publication interval including at least:

- native producer completion,
- output provenance acceptance,
- cache artifact-manifest persistence,
- hard-sub / derived-output processing,
- before `beginPublicationJournal(...)`.

Process death in this interval can leave an E1-owned numeric cache root with output files while no publication journal exists.

Startup `DownloadExecutionRecovery` does not consume `DownloadCacheOwnership` as an execution-continuation state. With no publication journal/native owner, the running row can be generically requeued.

E2 then uses the same numeric staging directory with a new execution token. `DownloadCacheOwnership.prepareAttempt()` / `ensureMarker()` refuses to rotate E1 authority when prior contents or an artifact manifest remain. This is correctly fail-closed for safety, but no recovery owner exists that can adopt, retire, or supersede the E1 producer state.

E2 failure cleanup cannot delete E1 state under E2 identity. Further retries can repeat the same failure.

Classification: fold into existing P2-B as B7. Do not increment the canonical P2 count.

Required liveness invariant:

> Exact prior producer/post-processing state must have a deterministic continuation or safe supersession path. A crash before publication journal creation must not leave an E1-owned staging root that permanently blocks every later execution while no recovery consumer owns that state.

Cluster D must therefore begin earlier than the publication phase. It must model producer generation / output-provenance handoff into publication recovery.

## 4. Hard-sub/derived output expands B7 but is not a new root

Hard-sub and subtitle/sidecar conversion add derived outputs to process-local `DownloadOutputProvenance` before publication begins. `beginPublicationJournal()` is reached only later when authoritative source files are about to move.

A crash after a derived transform but before publication journal creation is therefore the same B7 pre-publication authority gap. Some derived artifacts may not be represented by the earlier yt-dlp artifact manifest, making safe E2 root reuse even less possible.

Do not create a separate hard-sub P2 from this path. Cluster D should make producer/post-processing generation state durable enough for recovery or deterministic supersession before publication.

## 5. P2-H scope expanded to Download production admission/recovery

P2-H is not Terminal-only.

`PublicationRecoveryJournal.readAll()` collapses namespace-enumeration failure to empty and drops malformed/unreadable records. That same API is used by Download production paths.

### Download prior-publication recovery

`recoverPriorPublication()` uses `PublicationRecoveryJournal.findDownload()`. If discovery fails open and returns no records, the worker concludes there is no prior publication and proceeds to E2 producer execution.

Thus an unreadable E1 publication journal can directly reopen the B6 duplicate/mixed-generation producer path.

### Download opaque-provider recovery

`observeQueuedDownloadsAfterRecovery()` first calls `convergeUnknownProviderPublicationDebt()`, which discovers UNKNOWN provider debt exclusively through `PublicationRecoveryJournal.readAll()`, and then runs generic `DownloadExecutionRecovery.reconcile()`.

If an UNKNOWN E1 journal is unreadable or omitted by fail-open discovery before the exact Download row is terminalized, UNKNOWN convergence sees no debt. Generic recovery may then requeue the abandoned Active/PostProcessing row, exposing a later execution.

Therefore Cluster R must apply one truthful discovery contract to at least:

- Download `findDownload` / prior-publication recovery,
- Download UNKNOWN-provider startup convergence,
- Terminal publication recovery/admission,
- Terminal recovery-carrier discovery.

The typed result should distinguish healthy empty from unavailable/opaque/malformed debt. Unavailable or opaque debt must not authorize native admission or generic requeue.

This remains one P2-H; no count change.

## 6. Completion phase observation

The worker intentionally retires a Download publication journal only after `completeAndDelete()` or equivalent semantic Download/History terminalization. This ordering is safe, but Download journal semantics still use COMPLETE for the entire period from exact publication completion through later History/Download finalization.

Therefore Cluster D still needs durable sub-phases or a companion exact success state; otherwise recovery cannot distinguish publication continuation from already-committed semantic success.

## 7. Current Cluster D correction requirement after checkpoints 2-3

Cluster D (P2-B + P2-C + P2-J) should now be treated as one Download lifecycle state machine covering:

1. immutable effective producer/output-contract identity;
2. exact producer generation identity;
3. durable producer/post-processing provenance handoff before publication;
4. deterministic single-generation continuation/supersession;
5. provider exact-destination replay fencing;
6. publication complete producer finality;
7. History or no-History semantic continuation;
8. exact committed-success identity;
9. repository-wide protection against requeue/stop/status mutation after committed success;
10. Download finalization;
11. deterministic retirement of obsolete generation/journal/cache authority.

Safety and liveness are both required. A fail-closed state with no possible convergence is not a complete correction.

No new canonical P2 was added.

Working count remains:

`P0 0 / P1 0 / P2 8`

INDEPENDENT EXECUTION: NOT EXECUTED
