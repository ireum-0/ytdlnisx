# BUG-CACHE-ROOT-01 — current CLEAN-basis carry-forward revalidation

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior exact-basis checkpoint: `9cdb6cb05f6016b2b0786ea8d437b363dfd8fe78` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / existing P2 `BUG-CACHE-ROOT-01` remains OPEN.**

No new semantic root is counted.

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Intervening-change overlap check

The exact cumulative range `aa1616a2... -> 9c5191c3...` changes only Observe/source-authority-related production code and tests:

- `ResultRepository.kt`
- `SourceSnapshot.kt`
- `NewPipeUtil.kt`
- `YTDLPUtil.kt`
- `AutomaticKeywordRuleSyncWorker.kt`
- `ObserveSourceWorker.kt`
- Observe/SourceSnapshot tests

The prior cache-root root's governing production boundaries are not changed in this range:

- `FileUtil.kt`
- `FolderSettingsFragment.kt`
- `DownloadWorker.kt`
- `TerminalDownloadWorker.kt`

The F3 addition in `YTDLPUtil` adds typed source-snapshot authority around the existing source-fetch path. Its diff does not change `FileUtil.getCachePath()`, cache-root ownership, Download staging, Terminal staging, retry cleanup, or publication-recovery root selection.

Therefore the prior root is not invalidated or repaired by the intervening F3 work; a narrow current-basis source recheck is sufficient rather than duplicating the full original review.

## Current exact-source recheck

### Mutable preference still defines the root at call time

At `9c5191c3...`, `FileUtil.getCachePath(context)` still reads `cache_path` from `PreferenceManager` on each invocation.

`FolderSettingsFragment` still commits a selected cache directory directly with:

`CACHE_PATH_CODE -> editor.putString("cache_path", path)`

followed by `editor.apply()`.

The active Download/Terminal check is used for cache deletion and some migration operations, but the cache-path picker does not gate the preference write on quiescence.

### Download generation still re-resolves the root

At the exact current CLEAN basis, one Download generation still uses independent current-root reads for correctness-relevant phases:

- initial `DownloadAttemptRunner` temp directory: `File(FileUtil.getCachePath(context), downloadItem.id.toString())`;
- recovered execution retirement;
- artifact-manifest removal;
- owned-cache deletion;
- artifact recording;
- retry reset, where `resetYtdlpTempDirectoryUnsafe()` recomputes the current cache root and rejects a captured directory whose parent differs.

Thus the concrete sequence remains:

1. E1 creates/owns staging under root C1;
2. the user changes `cache_path` to C2 while E1 or its recovery debt survives;
3. E1 later re-resolves C2 for ownership/recovery/cleanup/retry authority;
4. exact generation artifacts and markers remain under C1 while current-root operations reason about C2;
5. retry reset may reject the valid captured C1 directory solely because the mutable preference now points to C2.

### Terminal generation still re-resolves the root

At `9c5191c3...`, Terminal still independently resolves the current cache root for:

- `TerminalExecutionRegistry.admit(...)`;
- creation of `TERMINAL/<terminalTaskToken>` staging;
- `TerminalPublicationRecovery.reconcile(...)`.

A preference change between these phases can therefore split one exact Terminal generation's staging/marker/recovery authority across C1 and C2.

## Current correction boundary

The prior correction boundary remains valid:

1. make cache-root identity generation-scoped for Download and Terminal execution/recovery, with the exact canonical root carried durably through staging, ownership, retry, cleanup, finalization, and restart recovery; or
2. refuse/defer/migrate cache-root changes until all live and recoverable old-root generations and durable recovery debt are provably quiescent.

Startup recovery must remain able to discover old-root debt after a preference change. The current global preference value cannot serve as proof of an earlier generation's root identity.

## Root reconciliation

- This is the same existing P2 `BUG-CACHE-ROOT-01`, counted once.
- No new root is introduced.
- No current evidence changes severity.
- The F3/BUG-OBSERVE-01 closure remains preserved and unrelated to this root.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
