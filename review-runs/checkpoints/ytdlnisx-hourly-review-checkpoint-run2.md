# YTDLnisX Hourly Correctness Review Checkpoint

## Fixed target
- implementation: `2c0bf3a5b4489c4294df0aabb91a3ec900f5604d`
- parent: `728f5c525117b1d291a071b2b07cc11ae558f87f`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review: `8790d4fd88953bcc69d80ce58aef8d7cf35094a3`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- yt-dlp semantic basis: `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Verdict
`NOT_CLEAN`

- P0: 1 new
- P1: 0
- P2: 1 existing/open

## New P0
`DownloadCacheOwnership.ensureMarker()` can create an ownership marker beside a pre-existing numeric cache directory. `resetYtdlpTempDirectoryUnsafe()` then recursively deletes that directory after marker creation. The new marker therefore retroactively authorizes pre-existing contents rather than proving they were created by the app.

Production sequence:
`configured cache root -> pre-existing <downloadId>/ directory -> ensureMarker -> marker created -> deleteRecursively`.

The current unit test only proves `deleteIfOwned` refuses an unmarked directory when called directly. It does not test the production sequence `ensureMarker -> delete`.

## Existing P2
Terminal partial-publication failure still deletes recoverable current-attempt staging in the non-cancellation catch.

## Cache-import remediation
The newest cache-import correction excludes unmarked root files and unmarked legacy directories and uses collision-safe destinations. Source-level direction is materially better, but ownership-marker creation semantics remain unsafe and also make marker-backed import authority too broad if a marker can be created over a pre-existing directory.

## Checklist gaps
1. Ownership marker creation was treated as proof acquisition rather than an authority-changing mutation.
2. Existing tests separately checked `unmarked => no delete` and `marked => delete`, but omitted the transition `unmarked pre-existing directory -> create marker -> destructive cleanup`.
3. Add a mandatory proof-bootstrap transition row for every sidecar/marker: resource absent/marker absent; resource pre-exists/marker absent; marker created now; first destructive action. Prove marker creation cannot retroactively authorize pre-existing contents.

## Evidence state
- no GitHub commit-status contexts for reviewed SHA
- independent local/emulator execution: NOT_VERIFIED
