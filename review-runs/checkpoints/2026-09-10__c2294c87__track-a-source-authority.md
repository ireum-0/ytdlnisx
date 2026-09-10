# Independent Track A checkpoint — source authority regression/recount correction

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Scope: Master Plan F3 `BUG-OBSERVE-01` and hard-dependent F12 `BUG-KEYWORD-01`
- Implementation branch in-progress diff was intentionally not inspected.
- Authoritative ledger not modified.

## F3 / BUG-OBSERVE-01 — CONFIRMED OPEN P0

Master Plan invariant: partial or failed extraction cannot make a destructive absence claim; only an authoritative source snapshot, including an authoritative empty snapshot, may authorize destructive synchronization.

Exact production basis still lacks that contract:

1. `ResultRepository.getResultsFromSource()` returns a plain `List<ResultItem>` with no source-authority state.
2. `YTDLPUtil.getFromYTDLInternal()` configures yt-dlp data fetches with `--ignore-errors`.
3. Streaming callback parsing wraps each line in `runCatching`; malformed/parse-failed lines are silently omitted from `finalResults`.
4. A request may therefore return a successful-looking non-empty or empty partial list without signaling PARTIAL/FAILED.
5. `ObserveSourceWorker` treats that plain list as complete: it builds `incomingLinks`, computes every prior processed link absent from that set, selects matching History rows, and enters physical/file-backed History deletion.

Concrete impact: one ignored child error, parse drop, or other incomplete extraction can make an existing source item appear absent and cause its local media/History state to be destructively removed.

Required closure: introduce/restore an explicit authoritative source snapshot contract across the actual fetch boundary. Destructive sync must require `AUTHORITATIVE`; `PARTIAL`/`FAILED` must not make absence claims. Authoritative empty must remain a valid destructive statement.

## F12 / BUG-KEYWORD-01 — CONFIRMED OPEN P1

F12 hard-depends on the F3 authority contract and current production still consumes the same plain list.

`AutomaticKeywordRuleSyncWorker` calls `ResultRepository.getResultsFromSource()` and passes the returned list directly to `recordBaseline`, `applyFullSync`, or `recordDiscovery`.

`AutomaticKeywordRuleEngine.recordBaseline()` initializes `baselineCanComplete = true`; an empty list performs no iteration and then calls `completeScheduledSyncIfCurrent(...)`. `recordDiscovery()` likewise can complete an incomplete baseline when its item processing had no local DB failure. Source completeness/authority is not an input.

Concrete impact: an incomplete empty extraction can durably mark an automatic-keyword baseline complete. Later discoveries then use post-baseline eligibility semantics even though the baseline was never authoritative.

Required closure: F12 must consume the same typed F3 snapshot authority. Baseline/scheduled discovery may complete only on an authoritative snapshot; PARTIAL/FAILED retains pending/retryable state according to the existing retry contract.

## Recount correction

The preceding six-P2 scheduled checkpoint is not sufficient as a full remediation inventory. Exact fixed-basis source re-tracing establishes these still-open Master Plan findings.

Current canonical working count after this Track A pass:

- P0: 1 — F3 / BUG-OBSERVE-01
- P1: 1 — F12 / BUG-KEYWORD-01
- P2: 8 — B, C, J, K, L, M, N/F17, O/F18

P2-B additionally has B10 archive-identity consumer subcase, without count inflation.

Verdict remains `NOT_CLEAN`.

INDEPENDENT EXECUTION: NOT EXECUTED
