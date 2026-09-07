# YTDLnisX Hourly Correctness Review Checkpoint

## Fixed target
- implementation: `baf26583074601516d4c283deb27bfb65fb7c4ad`
- parent: `2c0bf3a5b4489c4294df0aabb91a3ec900f5604d`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review: `8790d4fd88953bcc69d80ce58aef8d7cf35094a3`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- yt-dlp semantic basis: `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Verdict
`NOT_CLEAN`

- P0: 1 existing/open
- P1: 0
- P2: 1 existing/open

## Existing P0 — still open after remediation
The previous ownership-bootstrap P0 is not closed.

`DownloadCacheOwnership.prepareAttempt()` now avoids recursive deletion of arbitrary children, but it still calls `ensureMarker()` before deciding whether pre-existing contents are authoritative. If the pre-existing numeric directory already contains `.ytdlnisx-download-artifacts.txt`, the newly-created marker causes `prepareAttempt()` to trust that pre-existing manifest and delete every listed in-root file before proving that either the manifest or the files came from the same prior operation.

Therefore the bootstrap transition remains:
`pre-existing unowned root + pre-existing manifest -> create current marker -> trust old manifest -> destructive deletion`.

The direct recursive-delete path was removed, but destructive authority is still retroactively granted through an unauthenticated pre-existing manifest.

## Existing P2 — unchanged
`TerminalDownloadWorker` still detects stranded current-attempt sources after partial publication, throws, then its ordinary non-cancellation catch recursively deletes `terminalOutputDirectory`. This destroys the recoverable authoritative remainder preserved by `FileUtil.moveFile`.

## Confirmed improvements
- `prepareAttempt()` no longer recursively deletes arbitrary directory contents.
- Cleanup/import now use explicit artifact manifests rather than directory membership.
- Cache import destinations remain collision-safe.
- Unknown unmanifested siblings are preserved/fail closed in ordinary cleanup.
- Earlier command/parser/destructive-option fixes remain materially present in the reviewed lineage.

## Checklist evolution
Add an explicit mandatory `proof bootstrap provenance` row:
1. authority carrier already existed before current ownership proof;
2. current proof is created;
3. code reads an older sidecar/manifest/journal;
4. first destructive/publication mutation;
5. prove the older carrier is cryptographically/generation/operation-bound, or treat it as untrusted.

A marker must not authenticate a sibling manifest merely because both are now present under the same directory.

## Evidence state
- GitHub combined status contexts for exact SHA: none.
- Independent JVM/emulator execution: NOT_VERIFIED.
- Source semantic review is sufficient to keep the two blockers open.
