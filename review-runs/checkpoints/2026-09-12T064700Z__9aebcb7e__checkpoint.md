# Independent correctness review final checkpoint

Timestamp: 2026-09-12T06:47:00Z

## Frozen review basis

- implementation reviewed: `9aebcb7e2eb3c87813d49ea6c68b35d365f41738`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review-governance bootstrap: `05d43e321403a564e194f2d55d77116d93587a87`
- ledger reference: `899328bc91e4008e39a658387396a0106c8666ec`
- v6 checklist blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

The production branch advanced during this review to `3616ae02e56995e795cc52f3074d8c3d1cd2e330`. Per review protocol, that later SHA was not substituted into this run; all semantic conclusions below remain scoped exclusively to frozen implementation `9aebcb7e...`.

## Review completed scope

- Fresh branch/bootstrap identity capture and exact-SHA freeze.
- Master Plan/v6 gate review.
- Current BUG-CACHE-02 implementation delta inspected only for prioritization.
- Diff-independent production trace completed through:
  - `ACTION_OPEN_DOCUMENT_TREE` / cache preference publication;
  - legacy/current cache-root resolution and fallback;
  - Download raw staging;
  - Terminal raw staging;
  - cache ownership/maintenance/deletion;
  - cache migration/import;
  - app-owned raw fallback authority.
- Exact Android SAF and app-specific filesystem semantics checked against official platform documentation.
- Exact implementation GitHub status/check-run/Actions evidence checked.
- Final consumer/effect recount performed against frozen `9aebcb7e...`.

## Final provisional P0/P1/P2

**NOT_CLEAN — P0 2 / P1 1 / P2 32.**

Material governance change versus the preceding user-visible review: frozen review governance closes historical `BUG-DUPLICATE-01` and records `P2 -1`, moving the canonical count from `P2 33` to `P2 32`.

`BUG-CACHE-02` at frozen implementation `9aebcb7e...`:

- severity: P2
- classification: EXISTING
- source-semantic disposition: FIXED
- canonical disposition: OPEN
- reason remaining OPEN: required exact-SHA execution evidence is NOT_VERIFIED / independent execution NOT EXECUTED

No new P0/P1/P2 finding was established in this run.

## Confirmed fixed invariants

For the original BUG-CACHE-02 authority-loss invariant:

1. Provider/tree `content://` cache authority is no longer converted into a raw-looking native cache pathname.
2. New provider-only selections are rejected before `cache_path` persistence.
3. Legacy provider-only values resolve to fallback rather than raw-path promotion.
4. The fallback is an app-owned directly accessible filesystem root.
5. Download and Terminal raw staging consume the resolved root.
6. Cache maintenance/migration consumers also consume the resolved root; no separate provider-to-raw normalization bypass was found in the reviewed blocker-relevant consumer set.
7. Direct non-app-owned raw roots require direct filesystem writability evidence rather than a persisted SAF grant.

## Open candidates/questions

- No source-level BUG-CACHE-02 correctness candidate remains open after final recount.
- Execution closure remains NOT_VERIFIED. The exact SHA has no commit status contexts, no check-runs, and no Actions run; the reviewer did not execute the added instrumentation/production wiring tests.
- The provider picker currently obtains a persistable grant before rejecting the provider-only cache selection. That leaves an unused grant possibility, but this review did not establish a Master-Plan P0/P1/P2 correctness violation from it; it is not promoted as a finding.
- The production branch's later `3616ae02...` state is outside this frozen run and remains for a subsequent review.

## Remaining review scope

None for this frozen run. The next scheduled independent review should fresh-fetch and, if still current, treat the later production SHA as a new frozen implementation basis.

## Exact upstream semantic basis

- Android `ACTION_OPEN_DOCUMENT_TREE`: returned authority is a `DocumentsProvider` tree URI; descendant access is through tree/document URIs and `DocumentsContract`/`ContentResolver`, with persistable URI grants.
- Android app-specific external storage: `getExternalFilesDir()`/app-specific external directories are directly accessible to the calling app without storage permission on API 19+ while the volume is available; app internal/cache roots are direct app filesystem authority.
- v6 core invariants applied: semantic-contract changes require full consumer/authority-effect closure; identity/provenance must not be widened; test source is not PASS; CLEAN requires semantic closure plus required actual execution evidence.

INDEPENDENT EXECUTION: NOT EXECUTED
