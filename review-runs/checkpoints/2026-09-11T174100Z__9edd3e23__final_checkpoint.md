# Independent correctness review final checkpoint

- Exact implementation SHA: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Frozen plan/remediation SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review/remediation bootstrap SHA: `4652df32468e0981e3474d8c10125dcea2e40f49`
- Frozen ledger/remediation SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Master Plan canonical SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

## Completed review scope

- Fresh-fetched and froze all required branch heads.
- Re-read governing v6 rules and Master Plan authority/pinning model.
- Reconciled review-governance changes since the prior exact-SHA independent verdict.
- Revalidated F14 metadata publication source semantics at unchanged implementation SHA and inspected the newly added exact-task execution record.
- Independently re-traced custom download-archive storage end-to-end: SAF tree selection/persisted permission -> stored content URI -> FileUtil path conversion -> DownloadViewModel duplicate check -> ObserveSourceWorker duplicate check -> yt-dlp `--download-archive` fallback.
- Verified against official Android SAF semantics that URI/provider authority is distinct from raw filesystem/native-process authority.
- Final-fetched production, plan, and ledger heads; all remain equal to the frozen values.

## Final severity inventory / status changes

- `BUG-METADATA-01 / F14`: EXISTING P1 -> FIXED-CLOSED at `9edd3e23...`. Source semantics remain fixed and frozen review governance now contains actual exact-task execution evidence: focused production-wiring instrumentation 6/6 PASS and full JVM 610/610 PASS. GitHub commit status/check-run collections are empty, but those are not the only admissible execution-evidence carrier under v6.
- `BUG-DUPLICATE-03`: EXISTING historical P2 root, newly promoted into the current canonical blocker inventory at the same frozen implementation SHA. OPEN.
- No additional new P0/P1/P2 root found in this run.
- Final canonical count: **P0 2 / P1 1 / P2 30**.
- Final gate: **NOT_CLEAN**.

## Confirmed fixed invariants

- F14: metadata enrichment no longer republishes a stale full Download row; publication is narrow to metadata-owned fields and guarded at the final source/execution authority boundary.

## Open candidates / questions

- `BUG-DUPLICATE-03` remains open: the custom archive setting establishes SAF/provider authority, but production converts that identity to a raw path and readers fail open to an empty archive if raw-file access fails. Native yt-dlp fallback may receive the same unproven raw path.
- No unresolved candidate beyond already-counted canonical roots was established.

## Remaining review scope

- None for this run. Any implementation advancement after `9edd3e23...` belongs to a new frozen review run.

## Exact upstream semantic basis

- Android official `Intent` / Storage Access Framework / `DocumentsContract` semantics: ACTION_OPEN_DOCUMENT_TREE and persistable grants authorize provider-backed `content://` document/tree access; subtree document URIs leverage that provider grant and are not proof of a generally usable raw filesystem pathname.
- Frozen current production source at `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## Concrete BUG-DUPLICATE-03 reproduction

1. User selects a custom archive tree through ACTION_OPEN_DOCUMENT_TREE. The app persists read/write URI permission and stores the returned `content://...` tree URI.
2. `FileUtil.getDownloadArchivePath()` feeds the stored identity through `formatPath(...)` and appends `download_archive.txt`, discarding provider identity.
3. For a provider-backed tree with no valid direct raw path (including a non-filesystem DocumentsProvider, or scoped-storage authority not equivalent to raw access), the derived pathname is not usable via `java.io.File`/native yt-dlp.
4. `DownloadViewModel` and `ObserveSourceWorker` catch raw-file read failure and substitute an empty archive, so an actually archived item can be treated as not archived and queued again.
5. Where no generation-private archive is supplied, `YTDLPUtil` can also pass the reconstructed pathname to yt-dlp as `--download-archive`, despite the app only possessing provider URI authority.

Disposition: `EXISTING / P2 / OPEN`, promoted into the current canonical inventory.
