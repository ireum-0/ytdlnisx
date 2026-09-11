# BUG-OBSERVE-01 — source-authority revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna implementation wave is active.
- The active Luna implementation commits/diffs were not inspected, compared, reviewed, or relied upon for this decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P0 ROOT RECONFIRMED**

Defect: `BUG-OBSERVE-01`

The fixed CLEAN basis still permits a partial or otherwise non-authoritative source extraction to be represented as an ordinary successful `List<ResultItem>`, after which Observe source synchronization can treat missing members as authoritative absence and enter destructive History/file/reference cleanup.

## Exact-source evidence

### 1. NewPipe producer can return a partial snapshot as success

`app/src/main/java/com/ireum/ytdl/util/extractors/newpipe/NewPipeUtil.kt`:

- per-item conversion failure is absorbed by `createVideoFromStreamInfoItem()` as a null result rather than becoming source-level failure/partial authority;
- callers skip those null items and continue;
- pagination can terminate on an empty converted page even when the extraction path has already lost items/continuation completeness;
- the accumulated subset is nevertheless returned through `Result.success(totalItems)`.

Therefore parse drops / empty partial pages / incomplete pagination are not represented as a non-authoritative snapshot.

### 2. yt-dlp producer also loses child/parse completeness

`app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YTDLPUtil.kt`:

- source data fetch uses yt-dlp error-tolerant behavior including `--ignore-errors`;
- per-output-line parsing failures are handled locally rather than being surfaced as a typed source-snapshot authority result;
- only successfully parsed items are accumulated for the caller.

The caller therefore cannot distinguish a complete source listing from one in which child/extractor/parse failures were ignored.

### 3. ResultRepository erases source authority

`app/src/main/java/com/ireum/ytdl/database/repository/ResultRepository.kt`:

- playlist/channel extraction selects NewPipe or yt-dlp paths and ultimately returns only `List<ResultItem>`;
- the generic yt-dlp path likewise accumulates successful callback results into a plain list;
- `getResultsFromSource()` exposes no `AUTHORITATIVE` / `PARTIAL` / `FAILED` distinction.

This is the semantic loss boundary between extraction completeness and destructive Observe consumers.

### 4. Observe consumes the plain list as complete source truth

`app/src/main/java/com/ireum/ytdl/work/ObserveSourceWorker.kt`:

- `getResultsFromSource()` whole-call failure is handled separately, but a successful partial list is indistinguishable from a complete list;
- when `syncWithSource` is enabled and prior processed links exist, the worker constructs `incomingLinks` directly from that list;
- it computes prior processed links absent from `incomingLinks` and resolves matching History rows;
- those rows then enter the production deletion/reference-mutation path through the History deletion machinery.

Concrete chain:

`partial NewPipe / yt-dlp extraction`
→ `plain successful List<ResultItem>`
→ `ResultRepository authority erasure`
→ `ObserveSourceWorker incomingLinks`
→ `alreadyProcessedLinks - incomingLinks`
→ `History/file/reference destructive reconciliation`

A partial or failed extraction can therefore make a destructive absence claim, violating the governing F3 invariant.

## Governing correction boundary

The Master Plan F3 contract remains applicable:

- source extraction needs an explicit authority outcome equivalent to `AUTHORITATIVE`, `PARTIAL`, and `FAILED`;
- only an authoritative snapshot, including an authoritative empty snapshot, may authorize destructive source-membership reconciliation;
- partial/failed snapshots must preserve existing members from destructive absence treatment;
- normal additions, filtering, canonical URLs, retry behavior, and authoritative-empty removal must remain supported;
- the same authoritative source-snapshot contract is a prerequisite for dependent `BUG-KEYWORD-01` semantics.

This checkpoint does not prescribe a specific implementation type or persistence mechanism beyond the semantic contract.

## Root/count reconciliation

- This is a revalidation of existing canonical P0 root `BUG-OBSERVE-01`, not a new root.
- Count delta: `0`.
- Canonical blocker count remains `P0 3 / P1 3 / P2 26`.
- `BUG-KEYWORD-01` remains a distinct dependent P1 root; this review does not count or close it again.
- The contiguous independently CLEAN Review Basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED