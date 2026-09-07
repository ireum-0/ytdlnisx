# Hourly Correctness Review — Final Checkpoint

## Fixed review target

- Implementation: `baf26583074601516d4c283deb27bfb65fb7c4ad`
- Parent: `2c0bf3a5b4489c4294df0aabb91a3ec900f5604d`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Review baseline at run start: `aa499724a2f74288319933ecda539fcd769d7804`
- Ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` blob `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Exact yt-dlp semantic basis: `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`
- Intermediate checkpoint commit during this run: `73414ee35809895847304b9845b9e801cb406104`

The production implementation remained pinned to the exact SHA above throughout the review. Review-artifact commits do not alter the production review target.

## Independent verdict

`NOT_CLEAN`

- P0: `1 existing/open`
- P1: `0`
- P2: `1 existing/open`

No new canonical finding and no material status change relative to the immediately preceding independent review of the same implementation SHA.

## Findings

### Existing P0 — ownership-proof bootstrap remains open

Fresh source reinspection confirms `DownloadCacheOwnership.prepareAttempt()` still calls `ensureMarker()` before reading an already-existing `.ytdlnisx-download-artifacts.txt`. The artifact manifest contains only relative paths and is not independently bound to an operation/generation.

The authority transition remains:

`pre-existing unowned numeric directory + pre-existing manifest -> current marker created -> old manifest consumed -> listed in-root files deleted`.

The implementation correctly avoids broad recursive deletion of arbitrary children and preserves unknown siblings, but the current marker can still retroactively authenticate an older manifest. `DownloadCacheOwnershipTest` covers unmarked rejection and marker-backed current artifacts, but does not cover this exact pre-existing-manifest bootstrap transition.

Disposition: existing P0 remains OPEN.

### Existing P2 — Terminal partial-publication recovery remains open

Fresh source reinspection confirms `TerminalDownloadWorker` still:

1. uses UUID attempt-scoped staging and exact current-attempt output authority;
2. calls `FileUtil.moveFile()` with an exact source set;
3. detects stranded current-attempt sources after partial publication and throws;
4. then reaches the ordinary non-cancellation catch, which still executes `terminalOutputDirectory?.deleteRecursively()`.

`FileUtil.moveFile()` deliberately preserves the origin tree when exact-source publication is partial (`hasMoveFailure`), including SAF/direct-filesystem paths. The Terminal outer catch therefore destroys the recoverable exact remainder immediately after partial publication is detected.

Disposition: existing P2 remains OPEN.

## Confirmed fixed / preserved invariants

- Numeric directory membership alone is insufficient for broad recursive cache deletion.
- Cache import uses marker-discovered roots plus explicit artifact manifests rather than ambient cache-tree membership.
- Cache import destination selection remains collision-safe and does not overwrite an already-existing destination.
- Terminal staging remains UUID attempt-scoped.
- Terminal source authority remains tied to exact current-attempt yt-dlp output evidence.
- `FileUtil.moveFile()` preserves exact-source staging on partial publication rather than deleting it itself.
- No production source change occurred since the immediately preceding review of this SHA; earlier command/parser/destructive-option fixes therefore had no code delta in this run.

## Review retrospective

No new defect was discovered, so no new miss-analysis entry is required.

The existing lessons remain applicable:

1. ownership proof creation is an authority-changing mutation; newly-created proof must not retroactively authenticate older sidecars/manifests;
2. partial publication must be traced through exception/catch/finally cleanup, not only through the move helper's local contract.

## Checklist evolution

No additional checklist change beyond the already-proposed v6 operational additions is justified by this unchanged source checkpoint.

Carry forward:

- **Proof-bootstrap provenance:** trace `old carrier exists -> new proof created -> old carrier consumed -> first destructive/publication mutation`; require independent operation/generation binding.
- **Partial-publication cleanup:** trace `partial/ambiguous publication -> exception -> outer catch/finally -> cleanup`; prove recoverable authoritative remainder survives when recovery is required.

## Evidence state

- GitHub combined status contexts for exact implementation SHA: none.
- Independent JVM/emulator execution this run: `NOT_VERIFIED`.
- Source-semantic evidence remains sufficient to keep both blockers open. Tests are not used as a substitute for source-semantic review.

## Checkpoint summary

The implementation SHA is unchanged from the immediately preceding review. Fresh inspection of ownership bootstrap, cache import, exact move semantics, and Terminal failure cleanup re-confirms the same open P0 and P2. No additional P0/P1/P2 finding or status transition was established.

Result: `no material change`.
