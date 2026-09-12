# BUG-DATE-01 current-basis revalidation — 2026-09-12

## Scope

- Independently CLEAN review basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Root: F15 / existing P2 `BUG-DATE-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active Task 004 `BUG-CACHE-01` implementation diff was not inspected.

## Verdict

**OPEN / NOT_CLEAN.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 33**.
- CLEAN basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- F16 `BUG-DATE-02` remains semantically dependent on F15's typed child outcome.

## Exact current production chain

`HistoryDateFetchWorker.fetchPendingDates()` resolves each source group through `HistoryDateResolutionEngine.resolve(...)` with this real production composition:

1. local known-date values;
2. cached info-json values;
3. minimal extractor lookup through `YTDLPUtil.getDateOnlyMetadata(...)`;
4. compatibility lookup through `YTDLPUtil.getCompatibilityDateMetadata(...)`;
5. the resulting `HistoryDateLookupResult` is committed through `HistoryDateFetchRepository.checkpointSourceGroup(...)`.

### Local/cache precedence is preserved

`HistoryDateResolutionEngine` first accepts a single non-conflicting present local value, then a single non-conflicting cached value. This matches the Master Plan preserve boundary and is not the blocker.

### Minimal failure loses its semantic type

The minimal lookup is executed inside:

```text
try { minimalLookup() }
catch CancellationException { rethrow }
catch Exception { null }
```

The subsequent `takeIf(MediaPublishedDate::isPresent)` therefore cannot distinguish:

- successful minimal extraction that authoritatively produced no date;
- unsupported/malformed result;
- ordinary extractor/network/parser failure.

All ordinary non-cancellation failures become the same `null` carrier.

Cancellation itself remains correctly distinct and propagates; focused tests also cover that behavior. That preservation does not repair the non-cancellation semantic collapse.

### Compatibility null can turn prior failure into `NONE`

After a minimal `null`, the engine runs `compatibilityLookup()`. If that returns `null` or a non-present date without throwing, the engine returns:

- `origin = NONE`
- `extractorLaunches = 2`
- `compatibilityFallbacks = 1`

The result carries no bit or typed cause proving whether the preceding minimal lookup failed versus successfully established absence.

The real compatibility helper can itself return nullable `ResultItem?`; the engine therefore treats a nullable compatibility outcome as authoritative `NONE` even after a swallowed minimal failure.

### Durable promotion to `NO_DATE`

`HistoryDateFetchRepository.checkpointSourceGroup(...)` reloads and revalidates the History target, then classifies the lookup as follows:

- present date -> `UPDATED`;
- `origin == FAILED` -> `FAILED`;
- every other no-date lookup -> `NO_DATE` with `lookup.origin.name` as reason.

Therefore the concrete sequence:

```text
minimal extractor throws ordinary Exception
-> engine converts it to null
-> compatibility lookup returns null / non-present value without throwing
-> engine returns origin NONE
-> repository persists item state NO_DATE
```

is reachable in current production composition.

A non-authoritative extraction failure/ambiguity has become durable absence evidence.

## Focused test evidence disposition

Exact `HistoryDateFetchPolicyTest` currently proves useful neighboring behavior:

- local/cache precedence;
- conflict fallback;
- minimal-null followed by compatibility-date success;
- cancellation propagation;
- persisted cancellation before compatibility fallback;
- provenance-aware batch mapping.

It does **not** establish the required F15 distinction for `minimal failure + compatibility null/ambiguous output`. In fact the current production engine has no type capable of retaining that distinction after the minimal catch.

This is source-semantic evidence; no independent test execution was performed.

## Root/count reconciliation

This is the existing P2 `BUG-DATE-01` root exactly as described by F15, not a new defect:

- extractor failure/ambiguity collapses into nullable absence;
- nullable fallback can become durable `NO_DATE`;
- cancellation remains separately handled.

No blocker-count increment is warranted.

## Stable future correction boundary

F15 should introduce a typed child lookup outcome that preserves at least the semantic distinctions needed by persistence, such as:

- found date;
- authoritative absence;
- ambiguous/unproven;
- retryable failure;
- terminal/final failure.

The exact type names are implementation choices, but the following invariants are required:

1. a thrown minimal extractor failure may not become ordinary `null` absence;
2. compatibility fallback may improve an unresolved/minimal-failure result only when it provides authoritative matching evidence;
3. empty, malformed, mismatched, ignored-error, or otherwise ambiguous compatibility output may not authorize durable `NO_DATE`;
4. `NO_DATE` may be persisted only after successful matching extraction proves date absence;
5. retryable/final failures must remain typed through `checkpointSourceGroup(...)` so F16 can derive truthful child/parent/WorkManager semantics;
6. preserve local/cache precedence, source grouping/provenance checks, cancellation propagation, and bounded extraction behavior.

Required focused regressions include:

- minimal ordinary failure + compatibility empty/null/ignored-error -> not `NO_DATE`;
- minimal ordinary failure + authoritative compatibility date -> date accepted;
- successful authoritative extraction proving no date -> `NO_DATE`;
- malformed/mismatched compatibility result -> unresolved/failure, not absence;
- local/cache hit bypasses extractor launches;
- cancellation remains cancellation and never becomes absence/failure fallback.

No Room schema migration is inherently indicated merely to type the in-flight lookup outcome; durable-state changes, if chosen, must be justified by the F15/F16 combined design rather than assumed.

## F16 consequence

F16 `BUG-DATE-02` should not be canonically remediated before F15 establishes the typed child outcome contract that parent finalization and WorkManager retry/failure semantics must consume.

INDEPENDENT EXECUTION: NOT EXECUTED