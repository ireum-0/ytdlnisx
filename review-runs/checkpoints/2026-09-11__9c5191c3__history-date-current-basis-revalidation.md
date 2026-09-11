# BUG-DATE-01 — current CLEAN-basis carry-forward revalidation

Date: 2026-09-11

## Exact basis

- Current independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- Prior checkpoint: `c3d06cb9e5a287f0d1177969a68558ca87f51a3f` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F15 `BUG-DATE-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / existing P2 `BUG-DATE-01` remains OPEN.**

- Count delta: `0`
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`
- P2 `BUG-DATE-02` remains distinct and dependency-gated on a corrected F15 typed child outcome.

## Intervening-change overlap

The cumulative F3 work from `aa1616a2...` to `9c5191c3...` changes `YTDLPUtil`, `ResultRepository`, NewPipe source extraction, `AutomaticKeywordRuleSyncWorker`, and `ObserveSourceWorker`. Because `BUG-DATE-01` also consumes `YTDLPUtil`, the old finding cannot be carried forward solely by file non-overlap.

The exact current date-specific boundary was therefore reopened.

F3 adds a typed `SourceSnapshot` contract for source-list extraction through `getFromYTDLSnapshot()`. It does **not** replace the History-date child adapters:

- `getDateOnlyMetadata(inputUrl, processId): ResultItem?`
- `getCompatibilityDateMetadata(inputUrl, processId): ResultItem?`

Those date-specific methods retain the same nullable semantic surface present at the prior basis.

## Current source revalidation

### 1. Child outcome remains nullable

Exact `HistoryDateFetchPolicy.kt@9c5191c3...` still defines `HistoryDateResolutionEngine.resolve()` with:

- `minimalLookup: suspend () -> Long?`
- `compatibilityLookup: suspend () -> Long?`

The minimal lookup still catches every non-cancellation exception and converts it to `null`.

Therefore successful authoritative no-date, extractor/process/parse failure, and unproven/malformed child output can still collapse onto the same nullable boundary.

### 2. Minimal failure can still become ordinary NONE

After a minimal failure is erased to `null`, the compatibility fallback runs. If it returns `null`, the engine still returns:

- `origin = HistoryDateLookupOrigin.NONE`
- `extractorLaunches = 2`
- `compatibilityFallbacks = 1`

The minimal failure state is not retained.

### 3. Production worker still wires nullable YTDLP adapters directly

Exact `HistoryDateFetchWorker.kt@9c5191c3...` still supplies:

- `ytdlp.getDateOnlyMetadata(source, processId)?.mediaPublishedAt`
- `ytdlp.getCompatibilityDateMetadata(source, processId)?.mediaPublishedAt`

No typed FOUND / AUTHORITATIVE_ABSENT / AMBIGUOUS / RETRYABLE_FAILURE / FINAL_FAILURE child contract exists between extractor execution and the durable History-date result.

### 4. Durable false-success path remains

Exact `HistoryDateFetchRepository.kt@9c5191c3...` still persists:

- present date -> `UPDATED`;
- `lookup.origin == FAILED` -> `FAILED`;
- every other missing-date result -> `NO_DATE`.

Thus:

`minimal exception -> null -> compatibility null -> origin NONE -> durable NO_DATE`

remains reachable, and a run with no pending items can then finalize as `COMPLETED`.

### 5. F3 SourceSnapshot does not close F15

The new F3 `SourceSnapshot` authority is scoped to source-list membership extraction. The History-date worker does not consume it, and the date-specific YTDLP methods remain nullable. No current-source evidence shows that F3 changed the F15 authority boundary.

## Correction boundary carried forward

The prior F15 correction remains valid:

1. Introduce a typed child/date outcome distinguishing date found, authoritative date absence, ambiguous/unproven output, retryable failure, and final failure where applicable.
2. Permit durable `NO_DATE` only from successful correctly matched authoritative absence.
3. Never convert extractor/process/parse failure or ambiguous/mismatched output into authoritative absence merely because a child API returned `null`.
4. Preserve local/cache precedence, valid compatibility fallback, cancellation propagation, source grouping, and identity/provenance checks.
5. Add focused production-path regressions for minimal failure + empty fallback, genuine authoritative no-date, malformed/mismatched child result, local/cache hit avoidance, and cancellation around fallback.
6. Keep `BUG-DATE-02` separate until F15 exposes truthful child outcomes.

## Root reconciliation

- This is the existing canonical P2 `BUG-DATE-01`, counted once.
- No new root or severity change is established.
- F3 / `BUG-OBSERVE-01` remains CLOSED and does not semantically close F15.
- No Master Plan or authoritative-ledger modification is made.

INDEPENDENT EXECUTION: NOT EXECUTED
