# Independent correctness review final checkpoint (post-writer revalidation)

- Exact implementation SHA: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Frozen plan/remediation SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review/remediation bootstrap SHA: `4652df32468e0981e3474d8c10125dcea2e40f49`
- Frozen ledger/remediation SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Master Plan canonical SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

## Completed review scope

- Fresh-fetched and froze all required branch heads at run start.
- Re-read v6 semantic-contract, identity/provenance, retry/re-entry, consumer-closure, and execution-evidence rules.
- Reconciled review-governance changes since the prior user-visible exact-SHA verdict.
- Independently re-traced custom download-archive storage end-to-end at frozen production SHA: SAF selection/persisted grant -> stored tree URI -> FileUtil archive path derivation -> DownloadViewModel duplicate reader -> ObserveSourceWorker duplicate reader -> yt-dlp `--download-archive` fallback.
- Revalidated F14 current production source after the earlier final checkpoint: `ResultRepository.getDownloadMetadataPatch()` derives an immutable narrow patch rather than mutating/publishing a stale full Download row; `UpdateMultipleDownloadsDataWorker` publishes through `DownloadDao.updateMetadataIfSourceMatches`; the DAO only writes metadata-owned columns and predicates on exact id + source URL; the DownloadWorker-owned variant additionally requires durable Active status + exact executionId.
- Re-read exact-SHA immutable review execution evidence for F14: focused production-wiring instrumentation 6/6 PASS and full JVM 610/610 PASS, with independent execution by this reviewer NOT EXECUTED.
- Fresh GitHub commit status/check-run collections for `9edd3e23...` remain empty; this does not erase the separately recorded exact-SHA execution evidence but its provenance remains implementation-agent evidence.
- Final production/plan/ledger recount remained equal to the frozen heads.

## Final severity inventory / status changes

- `BUG-METADATA-01 / F14`: EXISTING P1, **FIXED-CLOSED** at exact `9edd3e23...`. This is a status change from the preceding user-visible run, which had left the root OPEN solely for execution closure.
- `BUG-DUPLICATE-03`: EXISTING historical P2, independently reproduced at exact `9edd3e23...` and newly promoted into the current canonical blocker inventory. OPEN.
- No brand-new canonical P0/P1/P2 root established in this run.
- Final canonical count: **P0 2 / P1 1 / P2 30**.
- Final gate: **NOT_CLEAN**.

## Confirmed fixed invariants

- F14 metadata enrichment cannot overwrite non-metadata Download state via stale full-row publication; final durable mutation is narrow and source/execution guarded.

## Open candidates / questions

- `BUG-DUPLICATE-03` remains open. The configured custom archive is authorized as a SAF/provider identity, but `getDownloadArchivePath()` converts it into assumed raw-path authority. Both production duplicate readers treat raw-file read failure as an empty archive, while yt-dlp fallback may receive the same unproven raw path.
- No other unresolved candidate was promoted beyond already-counted canonical roots.

## Remaining review scope

- None for this frozen run. Any later implementation SHA or materially changed governance requires a new run.

## Exact upstream semantic basis

- Android official Storage Access Framework / `ACTION_OPEN_DOCUMENT_TREE` / `DocumentsContract` semantics: a selected tree is a provider-backed `content://` identity with URI permission; persistable provider authority is not a generic guarantee of direct `java.io.File` or native-process raw-path authority.
- Frozen production source at `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## Concrete BUG-DUPLICATE-03 reproduction

1. Select a custom archive tree through `ACTION_OPEN_DOCUMENT_TREE`; the app persists URI read/write permission and stores the returned `content://...` tree URI.
2. `FileUtil.getDownloadArchivePath()` applies `formatPath(...)` and appends `download_archive.txt`, collapsing provider identity into a raw-looking path.
3. Choose a provider-backed tree for which that derived raw path is not directly readable/writable by `java.io.File`/native yt-dlp.
4. `DownloadViewModel` and `ObserveSourceWorker` catch archive read failure and substitute an empty archive; an actually archived source is therefore no longer recognized as a duplicate and may be queued again.
5. When no generation-private archive path is supplied, `YTDLPUtil` can pass the reconstructed path to yt-dlp as `--download-archive`, although only provider URI authority was established.

Disposition: `EXISTING / P2 / OPEN`, promoted into current canonical inventory.
