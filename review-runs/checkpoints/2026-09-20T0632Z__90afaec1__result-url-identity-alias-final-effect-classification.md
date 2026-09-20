# RESULT-URL-IDENTITY-ALIAS — exact CLEAN-basis final-effect classification

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Candidate under review: `RESULT-URL-IDENTITY-ALIAS`.
- Active F11 implementation remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.

## Verdict

**REJECTED AS A CURRENT CANONICAL P0/P1/P2 FINDING at exact CLEAN basis `90afaec1...`.**

- The Result namespace does use exact textual URL equality where other subsystems recognize provider-specific canonical/equivalent YouTube URL forms.
- That mismatch is real, but current production tracing does not establish a severity-bearing destructive, privileged, or durable-authority effect.
- Canonical blocker-count delta: `0`.
- `CLEAN_REVIEW_BASIS` does not move.
- The broader Master Plan 9.7 canonical media/source identity work remains a valid design/remediation domain; this checkpoint rejects only this specific Result-table candidate as a current blocker.

## Governing identity rule

Master Plan 9.7 states that textual URL equality is neither universally required nor universally sufficient for media equality, and requires each consumer to use an identity relation appropriate to its authority strength.

Checklist v6 requires a candidate to be traced through its final correctness-relevant effect. A different equality relation alone is not sufficient to create a blocker.

## Exact production evidence at 90afaec1

### 1. Result persistence uses exact URL equality

`ResultDao` provides exact textual URL operations:

- `getResultByURL(url)`;
- `getAllByURL(url)`;
- `deleteByUrl(url)`;
- `insertMultipleNoDuplicates(items)`, whose preexisting-row check calls exact `getResultByURL(it.url)`.

`ResultItem.url` is not a unique database key; row identity is the auto-generated numeric primary key.

### 2. The app also has explicit provider-specific equivalence semantics

`LinkUtil.equivalentYoutubeVideoUrls()` maps supported YouTube spellings such as youtu.be, youtube.com/watch, mobile/music, shorts, and live forms sharing the same video ID.

That equivalence is used in stronger domains such as History/duplicate source reasoning, proving that Result exact textual equality is not a repository-wide canonical-media identity.

### 3. Direct Result exact-URL consumers were inventoried

Current production consumers located at this exact basis are:

- `UpdateMultipleDownloadsFormatsWorker` -> exact Result lookup for optional Result-format cache update;
- `DownloadViewModel.updateDownloadItemFormats()` and `updateProcessingFormatByUrl()` -> exact Result rows receive refreshed formats;
- `DownloadViewModel.removeUnavailableDownloadAndResultByURL()` -> exact Result cleanup after processing-download removal;
- `ShareActivity` -> exact Result reuse for a shared/viewed URL;
- `ResultViewModel` / `ResultRepository` wrappers around those exact DAO operations.

No additional production caller of `insertMultipleNoDuplicates()` was found.

### 4. Format-cache consumers under-merge; they do not widen authority

When a Download URL is canonical-equivalent to a Result row but textually different:

- `UpdateMultipleDownloadsFormatsWorker` may not find that Result row;
- Download format acquisition and Download-row mutation still proceed from the Download's own URL;
- only the optional Result-row formats cache update is missed.

Likewise, UI format-update helpers update only exact Result rows.

This can leave equivalent Result spellings with stale/different cached format metadata, but the exact lookup does not authorize mutation of an unrelated media identity.

### 5. Exact deletion is conservative rather than over-broad

`removeUnavailableDownloadAndResultByURL(url)` deletes Result rows only for the exact textual URL.

Canonical-equivalent alias rows can remain stale, but no evidence shows exact deletion removes a different media row. This is under-deletion, not destructive identity widening.

Later reuse of an alias row may expose stale cached metadata, but downstream Download creation retains that Result row's own URL and ordinary Download/extractor authority; no privileged replacement/filesystem deletion authority was found to derive from the stale alias.

### 6. ShareActivity cache miss does not establish a blocker

`ShareActivity` asks `getAllByURL(inputQuery)`.

On exact miss it clears the transient Result list and creates an empty Result carrying the requested URL. That may forgo reusable metadata for a canonical-equivalent Result, but it does not substitute a different media identity or gain destructive authority.

The Result table is routinely reset/repopulated by search/recommendation/share flows, so this path is cache/UI state rather than a proven durable user-owned authority boundary.

### 7. Duplicate Result rows remain a non-blocking cache/UI concern on current proof

Because URL is not unique and exact equality is used, canonical-equivalent YouTube spellings can coexist as separate Result rows.

No current production path was found where that coexistence itself:

- deletes or replaces History/media;
- authorizes filesystem deletion;
- bypasses Download execution identity;
- grants privileged redownload/replacement authority;
- strands required durable recovery responsibility.

Therefore the final-effect threshold for a P0/P1/P2 canonical finding is not met.

## Candidate disposition

`RESULT-URL-IDENTITY-ALIAS`: **REJECTED AS CURRENT CANONICAL BLOCKER / NON-BLOCKING RESULT-CACHE IDENTITY INCONSISTENCY**.

This does not assert that exact Result URL equality is an ideal final design. It means the reviewed current production graph does not support blocker severity for this specific candidate.

Reopen only if fresh evidence establishes a stronger final effect, for example:

- canonical-equivalent Result alias becomes destructive/replacement authority;
- stale alias metadata bypasses an exact source/authorization gate;
- Result-row aliasing suppresses or corrupts durable user work rather than transient cache/UI state;
- a new consumer promotes Result URL equality into a privileged identity decision.

## Canonical reconciliation

- New root: none.
- Count delta: `0`.
- Existing canonical totals remain unchanged.
- Broader Master Plan 9.7 canonical media/source identity remains independent of this rejected candidate.

## Verification

- Exact-source producer/consumer/final-effect trace: completed at `90afaec1...`.
- Repository-wide direct consumer search for Result exact-URL APIs: completed.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
