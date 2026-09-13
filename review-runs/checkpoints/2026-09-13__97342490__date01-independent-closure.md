# Independent correctness review — F15 / BUG-DATE-01

- Reviewed implementation head: `973424909fd97de758b62f639967c8bae7c0bad7`
- Semantic implementation commit: `973424909fd97de758b62f639967c8bae7c0bad7`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Verdict: **CLEAN / CLOSED**
- Root: `BUG-DATE-01`
- Count delta: **P2 -1**
- Resulting canonical count: **P0 2 / P1 0 / P2 23**
- CLEAN-basis consequence: no contiguous basis advance because earlier F10 / `BUG-CLEANUP-01` remains open in the cumulative implementation range; basis stays `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Exact-source closure

The production History-date path now carries an explicit typed lookup outcome rather than collapsing failure/ambiguity into nullable date absence. The outcome contract distinguishes `Found`, `AuthoritativeAbsence`, `Ambiguous`, `RetryableFailure`, and `FinalFailure` through extractor lookup, resolution, worker, and repository persistence.

`HistoryDateLookupOutcomePolicy` authorizes absence only from exactly one clean candidate with validated source identity. Empty output, multiple candidates, missing identity, or source mismatch remain ambiguous rather than becoming absence. A present date becomes `Found`; only the one validated matching candidate with genuinely absent date becomes `AuthoritativeAbsence`.

The exact source-provenance policy does not treat the caller request itself as extractor evidence. `ExtractorSourceIdentity.trustedUrls()` contains only extractor-returned `originalUrl`, `canonicalUrl`, `fallbackUrl`, and `playlistUrl`. `matchesRequestedSource` compares the request against those returned identities or a provider-specific stable media identity. The `requestedSource` field can reject a mismatch but does not independently authorize a match.

The YTDLP date-only and compatibility paths preserve cancellation and translate ordinary extraction errors to typed failure rather than absence. Resolution preserves local-known-date and cache precedence. Repository checkpoint mapping persists:

- `Found` -> `DATE`;
- `AuthoritativeAbsence` -> `NO_DATE`;
- `Ambiguous`, `RetryableFailure`, and `FinalFailure` -> failure state, never `NO_DATE`.

Thus extraction failure, malformed/ambiguous output, or unproven compatibility fallback can no longer become durable evidence that a History date is absent.

The reported exact-final external evidence includes F15 instrumentation 7/7 PASS, focused policy tests 16/16 PASS, cumulative instrumentation 50/50 PASS, and the exact-final JVM/build matrix. Implementation-agent execution is evidence, not independent reviewer execution.

F16 / `BUG-DATE-02` was not implemented in this wave and is now dependency-eligible because F15 is independently closed.

INDEPENDENT EXECUTION: NOT EXECUTED