# Hourly Correctness Review Checkpoint

## Fixed review target
- Implementation: `2207a8706eaabbaaf58ea6351d1111444c89d125`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Review baseline at run start: `337269d156c0f9bfb6f1788a9b39bd5eddfa5fac`
- Ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` at the pinned review baseline
- Upstream yt-dlp semantic basis: `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Reviewed so far
- Fresh branch/governance pinning
- Terminal per-attempt staging and failure cleanup
- Terminal exact artifact manifest creation
- Terminal ownership-marker revocation on partial/failure paths
- Terminal recovery/import candidate construction through `CacheImportPlanner`
- Alternate markerless Terminal recovery authority in the inspected storage path

## Provisional findings
- P0: 0
- P1: 0
- P2: 1 existing/open

### Existing P2
A partial Terminal publication can leave exact current-attempt files and the artifact manifest in staging. `cleanupTerminalOutputDirectory()` calls `TerminalCacheOwnership.revokeOwnershipPreservingArtifacts()`, which removes the ownership marker while retaining those artifacts. However, `TerminalCacheOwnership.listOwnedRoots()` returns only directories carrying a valid marker, and `CacheImportPlanner.collect()` obtains Terminal recovery/import candidates exclusively from that marker-filtered list. The preserved markerless remainder is therefore excluded from the production recovery candidate graph.

This remains the existing BUG-TERMINAL-04 / BUG-OUTPUT partial-publication recovery obligation, not a new canonical defect.

## Confirmed fixed invariants
- Failure cleanup no longer recursively deletes the exact partial-publication remainder.
- Terminal staging remains UUID/task-token scoped.
- Exact current-attempt files are recorded in a manifest before publication.
- Prior cache-ownership bootstrap P0 remains outside this current open Terminal path.

## Open questions / remaining scope
- Re-check for any other production reconciler that intentionally discovers markerless Terminal manifests.
- Check exact-SHA CI/status evidence.
- Recount implementation HEAD without changing this run's pinned target.
- Write final checkpoint.

## Evidence state
Source semantic review in progress. Independent JVM/emulator execution remains `NOT_VERIFIED` at this checkpoint.
