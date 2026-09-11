# BUG-DATE-01 — History date lookup authority revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna `BUG-OBSERVE-01` implementation wave is active.
- The active Luna implementation branch HEAD, commits, and diffs were not inspected, compared, reviewed, or relied upon for this decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Defect: `BUG-DATE-01` / F15

The fixed CLEAN basis still collapses extractor failure/ambiguity and successful authoritative date absence onto the same nullable child-lookup surface. A minimal extractor exception can therefore be erased, a null compatibility result can be interpreted as ordinary `NONE`, and the production persistence path can terminalize the affected History item as `NO_DATE` while the overall operation completes successfully.

## Exact-source evidence

### 1. Minimal extractor failure is erased

`app/src/main/java/com/ireum/ytdl/util/HistoryDateFetchPolicy.kt` defines `HistoryDateResolutionEngine.resolve()` with nullable child lookups:

- `minimalLookup: suspend () -> Long?`
- `compatibilityLookup: suspend () -> Long?`

The minimal lookup catches every non-cancellation `Exception` and converts it to `null` before date-presence filtering. This makes these materially different outcomes indistinguishable at the authority boundary:

- successful extractor execution that authoritatively found no publication date;
- extractor/process/parse failure;
- an otherwise unproven or malformed child result represented as null.

The engine then launches the compatibility fallback without retaining the minimal failure state.

### 2. Empty/null compatibility output can become ordinary `NONE`

The compatibility path only returns `FAILED` when `compatibilityLookup()` throws. If it returns `null` or a missing-value date, the engine returns:

- `origin = HistoryDateLookupOrigin.NONE`
- `extractorLaunches = 2`
- `compatibilityFallbacks = 1`

Therefore the concrete sequence:

`minimal extractor throws`
-> exception swallowed to `null`
-> compatibility lookup returns `null`
-> `HistoryDateLookupOrigin.NONE`

loses the evidence that the lookup chain was not an authoritative proof of date absence.

### 3. Production worker uses those nullable extractor adapters directly

`app/src/main/java/com/ireum/ytdl/work/HistoryDateFetchWorker.kt` wires the resolution engine to:

- `YTDLPUtil.getDateOnlyMetadata(... )?.mediaPublishedAt` for minimal lookup;
- `YTDLPUtil.getCompatibilityDateMetadata(... )?.mediaPublishedAt` for compatibility lookup.

The worker then passes the resulting `HistoryDateLookupResult` directly to `HistoryDateFetchRepository.checkpointSourceGroup()`.

No typed FOUND / AUTHORITATIVE_ABSENT / AMBIGUOUS / RETRYABLE_FAILURE / FINAL_FAILURE child outcome is inserted between extractor behavior and durable item disposition.

### 4. `NONE` is persisted as terminal `NO_DATE`

`app/src/main/java/com/ireum/ytdl/database/repository/HistoryDateFetchRepository.kt` determines each pending item's terminal outcome as follows:

- present lookup date -> `UPDATED`;
- `lookup.origin == FAILED` -> `FAILED`;
- every other missing-date lookup -> `NO_DATE` with the origin name as reason.

Thus the failure-erasing sequence above reaches:

`origin=NONE, mediaPublishedAt=MISSING`
-> `HistoryDateFetchItemState.NO_DATE`

After all pending items are terminalized, `finalizeWorkerRun()` marks the operation `COMPLETED`. This is a production durable false-success distinction loss, not merely a helper-level modeling concern.

### 5. Existing focused tests do not close this authority gap

`HistoryDateFetchPolicyTest` covers:

- local/cache precedence;
- conflicting cache fallback;
- successful minimal and compatibility lookups;
- cancellation propagation;
- cancellation between a minimal failure and compatibility fallback;
- batch provenance mapping.

It does not prove that a non-cancellation minimal extractor failure followed by null/empty compatibility output is retained as failure/ambiguity rather than becoming `NONE`, nor can the current nullable child API express an authoritative no-date result distinctly from failure.

## Preserved positive behavior

This finding does not reject the current local/cache precedence, source grouping, cancellation propagation, compatibility fallback itself, or the rule that genuinely successful date absence may terminate as `NO_DATE`.

The defect is specifically the authority required before `NO_DATE` becomes a durable terminal conclusion.

## Governing correction boundary

The Master Plan F15 contract remains applicable:

1. Replace nullable child lookup authority with a typed semantic outcome equivalent to:
   - date found;
   - authoritative date absence;
   - ambiguous/unproven result;
   - retryable extraction failure;
   - final/non-retryable failure where applicable.
2. `NO_DATE` may be persisted only when a successful, correctly matched extraction positively proves date absence.
3. Extractor/process/parse failures must not be converted into authoritative absence merely because a child API produced `null` or an empty usable result.
4. Ambiguous or mismatched output must not become `NO_DATE`.
5. Preserve local/cache precedence and valid compatibility fallback behavior.
6. Preserve cancellation semantics: cancellation remains distinct and propagates without being converted to a lookup outcome.
7. Preserve source grouping and identity/provenance checks.
8. Add focused regression coverage for at least:
   - minimal failure + fallback empty/null/ignored-error -> not authoritative `NO_DATE`;
   - genuinely authoritative no-date extraction -> `NO_DATE` allowed;
   - malformed/mismatched/ambiguous child result -> not authoritative absence;
   - local/cache hit avoids unnecessary extractor launches;
   - cancellation before/during fallback remains cancellation.
9. Keep `BUG-DATE-02` / F16 separate. F16's durable retry/resume semantics depend on F15 first exposing truthful child outcomes; this checkpoint does not close or newly count F16.

## Root/count reconciliation

- This is a revalidation of existing canonical P2 root `BUG-DATE-01`, not a new root.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- Contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- `BUG-DATE-02` remains a distinct open P2 root and is dependency-gated on a corrected F15 typed child outcome.
- The separate active Luna `BUG-OBSERVE-01` implementation was not inspected or relied upon.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
