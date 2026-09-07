# Hourly Correctness Review — Final Checkpoint

## Fixed review target

- Implementation: `baf26583074601516d4c283deb27bfb65fb7c4ad`
- Parent: `2c0bf3a5b4489c4294df0aabb91a3ec900f5604d`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Review baseline at run start: `dfbf3eb0ca6e0aa001a2030868a77bb63da4bd63`
- Ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` blob `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Exact yt-dlp semantic basis: `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`
- Intermediate checkpoint commit created during run: `03f109e36a511d50321e646eb23118c3ba6e6f15`

This run stayed pinned to the exact implementation/governance state above. Review-artifact commits made during the run do not alter the production review target.

## Independent verdict

`NOT_CLEAN`

- P0: 1 existing/open
- P1: 0
- P2: 1 existing/open

No new canonical finding and no material status change relative to the immediately preceding independent review of the same implementation SHA.

## Existing P0 — ownership-proof bootstrap remains open

`DownloadCacheOwnership.prepareAttempt()` still calls `ensureMarker()` before consuming an already-existing `.ytdlnisx-download-artifacts.txt`. The artifact manifest is only a list of relative paths and has no independent operation/generation provenance binding.

The production-reachable authority transition therefore remains:

`pre-existing unowned numeric directory + pre-existing manifest -> current marker created -> old manifest consumed -> listed in-root files deleted`.

The remediation correctly removed broad recursive deletion of arbitrary children and narrowed cleanup/import to explicit artifact entries, but a newly-created current marker can still retroactively authenticate an older manifest. Existing steady-state ownership checks do not make that proof bootstrap sound.

Disposition: existing P0 remains OPEN.

## Existing P2 — Terminal partial-publication recovery remains open

`TerminalDownloadWorker` still requires exact current-attempt yt-dlp output evidence, performs exact publication, and detects stranded current-attempt source files after partial publication. However, on the resulting ordinary non-cancellation failure, its outer catch still runs `terminalOutputDirectory?.deleteRecursively()`.

Thus recoverable authoritative remainder that survives the partial move is destroyed by generic failure cleanup immediately after being detected.

Disposition: existing P2 remains OPEN.

## Confirmed fixed / preserved invariants in reviewed scope

- Download cache preparation no longer treats numeric-directory membership as sufficient authority for broad recursive deletion.
- Cache cleanup/import is narrowed to explicit manifest-listed artifacts and unknown siblings fail closed/preserve the root.
- Terminal staging remains UUID attempt-scoped.
- Terminal source authority remains tied to current-attempt yt-dlp output evidence rather than ambient directory membership.
- No source change occurred in the exact implementation checkpoint since the immediately preceding review; therefore previously confirmed command/parser/output-provenance fixes cannot have changed in this run.

## Review retrospective

No new defect was discovered, so no new miss-analysis entry is required. The prior lesson remains active: ownership proof creation is itself an authority-changing mutation, and a newly-created proof must not retroactively authenticate an older sidecar/manifest/journal.

## Checklist evolution

No additional checklist change beyond the prior proposals is justified by this unchanged source checkpoint.

Carry forward:

1. `Proof-bootstrap provenance`: review `old carrier exists -> new proof created -> old carrier consumed -> first mutation`, and require independent generation/operation binding before the old carrier gains destructive/publication authority.
2. `Partial publication cleanup`: review `partial/ambiguous publication -> exception -> catch/finally -> cleanup`, proving recoverable exact artifacts survive when recovery is required.

## Evidence state

- GitHub combined commit-status contexts for exact implementation SHA: none.
- Independent JVM/emulator execution in this run: `NOT_VERIFIED`.
- Source-semantic evidence remains sufficient to keep both blockers open; test status is not used as a substitute for source review.

## Checkpoint summary

The production implementation SHA is unchanged from the preceding review. Fresh source inspection re-confirmed the same P0 ownership-bootstrap flaw and the same P2 Terminal partial-publication cleanup flaw. No additional P0/P1/P2 finding or status transition was established.

Result: `no material change`.
