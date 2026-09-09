# Independent Track A correctness checkpoint — LocalAdd identity

- Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`
- Implementation branch HEAD observed during this review: `8de0e0f8aa7c0f4294c41b4b6e958acf53a778fa`
- Review branch parent before this checkpoint: `112be8070d184aad4af9edfcc0b5dfc4fc7ef5be`
- Plan commit: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Authoritative ledger ref (not modified): `899328bc91e4008e39a658387396a0106c8666ec`
- Master Plan SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

## Semantic decision established by source review

### P2-L / F20 `BUG-LOCALADD-01` — CONFIRMED P2

This checkpoint records a semantic decision already established by independent source review. It does not create a new ledger decision.

Root invariant violation:

> A filename stem / basename is not durable local-media identity and must never by itself authorize dropping a selected local file.

Exact production source at the fixed Review Basis:

- `app/src/main/java/com/ireum/ytdl/ui/downloads/HistoryFragment.kt`
  - `addLocalVideos()` first builds `existingBaseNames` from every existing History `downloadPath`.
  - For each selected local entry it performs stronger exact tree/local-path checks and exact stored `downloadPath` lookup, but then independently derives `baseName/baseKey` from the selected file name.
  - `existingBaseNames.contains(baseKey)` causes `skipped += 1` followed by `return@forEach`.
  - The add-local UI is production reachable from the History toolbar's `add_local_video` action through document/file/folder selection into the local-add path.
- `app/src/main/java/com/ireum/ytdl/work/LocalAddWorker.kt`
  - The background/session worker repeats the same weaker `existingBaseNames` authority after exact tree/download-path checks.
  - This is the same semantic root and does not add another P2.

Concrete impact:

Two distinct local media objects may have the same filename stem while differing by directory, provider, tree-relative path, document identity, or URI. If one stem is already present in History (or has already been accepted earlier in the same local-add batch), the other selected object can be silently skipped even though the stronger local identity checks did not match it.

Producer → authority → consumer/impact:

`user-selected local media URI/tree identity`
→ `filename stem derived from History.downloadPath / selected display name`
→ `existingBaseNames membership`
→ `early return / skipped`
→ `distinct selected local file never receives History insertion or manual/match reconciliation`.

This is not P2-K. P2-K is remote/config duplicate media identity. P2-L is local object identity and discard authority.

## Acceptance condition

- Exact durable local identity may deduplicate: e.g. tree URI + relative path, provider-scoped document identity, or normalized full URI where appropriate.
- Bare filename/basename/stem must not discard a local candidate.
- Same-name files from different directories/providers must proceed independently.
- Same-name entries accepted earlier in one batch must not suppress a distinct later entry.
- Existing exact tree/path/URI dedupe and source-URL match behavior should remain intact.
- Both foreground `HistoryFragment.addLocalVideos()` and the `LocalAddWorker` session/background path must obey the same identity contract.

## Working count update

Canonical working count before this decision:

`P0 0 / P1 0 / P2 9`

After confirming P2-L:

`P0 0 / P1 0 / P2 10`

Open P2 now includes the prior canonical set plus P2-L. The narrower scheduled recount recorded in the previous review checkpoint does not replace this canonical remediation working count.

## Execution evidence

No JVM, instrumentation, emulator, or device execution was performed for this finding. The decision is based on exact-SHA source-level production-path evidence only.

`INDEPENDENT EXECUTION: NOT EXECUTED`
