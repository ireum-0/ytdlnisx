# Track A checkpoint — opaque provider UNKNOWN canonical status and runtime grammar drift

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`
Date: 2026-09-09
Independent execution: NOT EXECUTED

## Canonical decision — broad opaque Download provider UNKNOWN gate

Historical review material contained a broad P2 concept that no production owner converged opaque provider UNKNOWN through exact discovery/rollback/provider reconciliation/authority transfer.

Fresh current-source review does not retain that broad item as a current P2 blocker.

Current production behavior at the fixed Review Basis:

- `convergeUnknownProviderPublicationDebt()` discovers Download publication journals containing `QUARANTINED_UNKNOWN`, unknown reservations, or unresolved provider intents.
- When the exact execution is no longer live, it terminalizes the journal and converges a matching Active/PostProcessing row to Error / `PUBLICATION_OUTCOME_UNKNOWN`.
- Queue admission calls UNKNOWN convergence before generic Download execution recovery.
- App startup can race with a separate generic `DownloadExecutionRecovery.reconcile()` and temporarily requeue the row first, but that requeue preserves `operationId` and only clears `executionId`.
- A subsequently claimed execution invokes `recoverPriorPublication()` before its native producer phase. The prior UNKNOWN journal is therefore encountered and converted to `UnknownProviderPublicationException`; the worker classifies the result as `PUBLICATION_OUTCOME_UNKNOWN` instead of crossing provider/native publication again.
- `PUBLICATION_OUTCOME_UNKNOWN` does not support SAME_SETTINGS retry. Reconfiguration can be offered, but `DownloadRetryPolicy` retains the existing nonblank operationId, so the same UNKNOWN lineage remains a pre-native fence.
- Download `PublicationRecoveryJournal.Handle.clear()` is allowed only from `COMPLETE`; a Download `QUARANTINED_UNKNOWN` journal cannot be cleared through the ordinary retirement API.
- `markPhase()` also refuses to transition a `QUARANTINED_UNKNOWN` record back to an ordinary phase.

Therefore the current architecture establishes a permanent durable terminal quarantine that prevents replay without fabricating a provider URI or scanning by basename/directory coincidence.

The Master Plan's F2 `BUG-OUTPUT-01` invariant requires exact current-operation output provenance before finalPaths/History/replacement/destructive authority. It does not require unsafe discovery of an opaque provider object whose exact identity is unavailable.

### Classification

The historical broad UNKNOWN convergence gate is **not retained as a current canonical P2**.

Residual risk: the provider may have created an opaque external object that cannot be addressed for cleanup. This is storage/orphan cleanup debt, not evidence of replay, wrong-file authority, wrong deletion, or History corruption. Track it as **P3 / hardening** unless later evidence shows a stronger semantic impact.

This decision does not close or merge P2-H. If publication-journal discovery itself fails open, the durable UNKNOWN fence can be missed; that remains P2-H.

Canonical P2 count remains:

`P0 0 / P1 0 / P2 8`

## Runtime yt-dlp grammar drift candidate

At Review Basis, `UpdateUtil` can update the writable yt-dlp runtime through stable/nightly/master or an explicit update-to channel, while `YtdlpOptionOwnership` documents a static model of bundled yt-dlp 2025.11.12.

Fresh upstream verification:

- current stable release as of 2026-09-09 is yt-dlp `2026.08.19`;
- the 2026.08.19 option surface was inspected against the app's static short-option value/no-value sets;
- no new short option character was found that concretely recreates the prior `-qo` / `-qP` cluster confinement escape;
- upstream options.py changes after 2025.11.12 include additions such as `--format-sort-reset`, but no current concrete destination-authority escape was established;
- no `yt_dlp/options.py` commit was present between the 2026.08.19 stable release and 2026-09-09.

### Classification

Do **not** add a current P2 on hypothetical parser/runtime drift alone. Keep a P3/hardening item: static confinement policy is not version-gated to the independently updateable runtime, so a future/nightly/master grammar change could invalidate static assumptions.

## Review basis

No semantic correction was reviewed CLEAN in this checkpoint. Review Basis remains `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`.
