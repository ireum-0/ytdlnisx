# BUG-DATE-01 current-basis revalidation — 2026-09-12

## Scope

- Exact independently CLEAN review basis: `3616ae02e56995e795cc52f3074d8c3d1cd2e330`
- Root: F15 / existing P2 `BUG-DATE-01`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F15
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Prior revalidation: `85e80aa7df17fca78ff6b6d80b5df89cba71deea` at basis `93d01d2a...`
- Exact implementation branch remains separately completed at `8c5db3c739a6e5fb6a1974ed8276755eb1d112cc`; this exploratory review uses only the independently CLEAN basis.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN.**

- Count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 30**.
- CLEAN basis remains `3616ae02e56995e795cc52f3074d8c3d1cd2e330`.
- F16 `BUG-DATE-02` remains hard-dependent on F15's typed child lookup outcome.

This is the already-counted F15 root, not a new blocker.

## Intervening-range check

`93d01d2a... -> 3616ae02...` includes a modification to `YTDLPUtil.kt` as part of later accepted cache remediation, so the exact final date-fetch helper wiring was reread rather than assumed unchanged.

The final `HistoryDateFetchPolicy`, `HistoryDateFetchWorker`, `HistoryDateFetchRepository`, and relevant `YTDLPUtil` date helpers were inspected at exact `3616ae02...`.

## Exact current production chain

`HistoryDateFetchWorker.fetchPendingDates()` still resolves each source group through `HistoryDateResolutionEngine.resolve(...)` using:

1. known local dates;
2. cached info-json dates;
3. `YTDLPUtil.getDateOnlyMetadata(source, processId)?.mediaPublishedAt`;
4. `YTDLPUtil.getCompatibilityDateMetadata(source, processId)?.mediaPublishedAt`;
5. `HistoryDateFetchRepository.checkpointSourceGroup(...)` for durable item state.

Local/cache precedence and cancellation remain useful preserved behavior and are not the blocker.

## Semantic collapse remains

### 1. Minimal ordinary failure is still converted to nullable absence

`HistoryDateResolutionEngine.resolve(...)` still executes minimal lookup as:

```text
try { minimalLookup() }
catch CancellationException { rethrow }
catch Exception { null }
```

It then accepts only a present date. A non-cancellation extractor/network/parser failure therefore loses its semantic type and becomes the same `null` carrier as no usable date result.

### 2. Compatibility nullable result still becomes `NONE`

After a null minimal result, the engine calls `compatibilityLookup()`. If it returns null or a non-present date without throwing, the engine returns:

- `origin = NONE`
- `extractorLaunches = 2`
- `compatibilityFallbacks = 1`

There is no typed field proving whether the preceding minimal stage failed, was ambiguous, or authoritatively established absence.

### 3. Final YTDLP helpers still do not supply the missing distinction

At exact `3616ae02...`, the actual worker passes the two `YTDLPUtil` helpers above.

`getDateOnlyMetadata(...)` validates a single-source response through `parseVerifiedSingleDateResult(...)`; the verified parser retains only results whose source identity matches and whose `mediaPublishedAt` is present, then returns `singleOrNull()`.

`getCompatibilityDateMetadata(...)` likewise returns nullable metadata from the compatibility path. The final helper composition therefore still represents malformed/mismatched/ambiguous/no-present-date outcomes with nullable `null`; it does not provide an authoritative-absence type.

The intervening cache-related `YTDLPUtil.kt` changes did not close F15's lookup-result contract.

### 4. Durable `NO_DATE` promotion is unchanged

`HistoryDateFetchRepository.checkpointSourceGroup(...)` still classifies:

- present lookup date -> `UPDATED`;
- `lookup.origin == FAILED` -> `FAILED`;
- every other no-date lookup -> `NO_DATE`.

Thus the concrete production sequence remains reachable:

```text
minimal extractor throws ordinary Exception
-> HistoryDateResolutionEngine converts failure to null
-> compatibility returns null / non-present / otherwise unproven result without throwing
-> engine returns origin NONE
-> checkpointSourceGroup persists NO_DATE
```

An extraction failure or ambiguity can therefore become durable evidence of absence.

## Governing invariant and correction boundary

F15 requires extractor failure/ambiguity to remain distinct from authoritative absence. `NO_DATE` may be durable only when successful matching extraction proves date absence.

The stable correction boundary remains a typed lookup outcome preserving at least the distinctions required by persistence and F16, e.g.:

- found date;
- authoritative absence;
- ambiguous/unproven;
- retryable failure;
- terminal/final failure.

Required semantics:

1. a minimal exception may not collapse to ordinary absence;
2. compatibility fallback may improve an unresolved/minimal-failure result only with authoritative matching evidence;
3. empty, malformed, mismatched, ignored-error, or ambiguous output may not authorize `NO_DATE`;
4. cancellation remains cancellation;
5. local/cache precedence and grouping/provenance checks remain preserved;
6. the typed child result must propagate through `checkpointSourceGroup(...)` so F16 can derive truthful retry/terminal parent and WorkManager semantics.

No Room schema migration is inherently required merely to type the lookup result; any durable schema change must be justified by the combined F15/F16 design.

## Dependency consequence

F16 `BUG-DATE-02` remains blocked from canonical remediation until F15 establishes this typed child outcome contract.

INDEPENDENT EXECUTION: NOT EXECUTED