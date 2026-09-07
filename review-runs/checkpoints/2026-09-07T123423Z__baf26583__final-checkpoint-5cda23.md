# Hourly Correctness Review — Final Checkpoint

## Fixed target
- implementation: `baf26583074601516d4c283deb27bfb65fb7c4ad`
- parent: `2c0bf3a5b4489c4294df0aabb91a3ec900f5604d`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- pinned review authority at run start: `914846f83c94ed64202e17137792bad652229cb9`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- yt-dlp semantic basis: `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Independent verdict
`NOT_CLEAN`

- P0: 1 existing/open
- P1: 0
- P2: 1 existing/open

No new P0/P1/P2 finding and no material correctness-status change relative to the previous independent review of the same implementation SHA.

## Findings revalidated from current production source

### Existing P0 — proof-bootstrap provenance remains open
`DownloadCacheOwnership.prepareAttempt()` calls `ensureMarker()` before consuming any pre-existing `.ytdlnisx-download-artifacts.txt`. The marker is operation-bound, but the manifest itself is only a relative-path list and contains no operation/generation binding. Consequently, a pre-existing unowned numeric staging directory plus a pre-existing manifest can acquire current authority after marker creation, and manifest-listed in-root files can then be deleted. The remediation narrowed deletion scope but did not close the bootstrap provenance gap.

### Existing P2 — Terminal partial-publication recovery remains open
`FileUtil.moveFile()` explicitly preserves the origin tree when publication is partial. `TerminalDownloadWorker` then detects stranded current-attempt source files and throws, but the ordinary non-cancellation catch recursively deletes `terminalOutputDirectory`. This destroys the recoverable authoritative remainder that the move layer preserved.

## Confirmed fixed invariants retained
- Download cache cleanup is no longer arbitrary recursive deletion of all staging children.
- exact manifest-listed cleanup and collision-safe import direction remain present.
- Terminal output authority is attempt-scoped and current-output-derived.
- partial publication is detected before success.
- earlier command/parser/destructive-option fixes remain in the unchanged implementation lineage.

## Review retrospective
No new canonical finding this run. Prior checklist lesson remains sufficient: newly created ownership proof must not retroactively authenticate a pre-existing manifest/journal/sidecar without independent provenance binding.

## Checklist evolution
No additional checklist change beyond the existing `proof-bootstrap provenance` improvement is justified by this unchanged source. Continue requiring a transition review from pre-existing unproven carrier -> new proof creation -> first destructive mutation.

## Evidence
- GitHub combined-status contexts for exact implementation SHA: none.
- independent JVM/emulator execution: `NOT_VERIFIED`.
- source semantic evidence is sufficient to keep both existing blockers open.

## Checkpoint summary
Production SHA is unchanged from the preceding review and the same two blockers remain. `no material change`.
