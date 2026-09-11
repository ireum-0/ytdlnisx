# Independent correctness review final checkpoint

- exact implementation SHA: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- frozen plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- bootstrap review SHA: `6a229a02545f38cbfc16c31ad29491070c95008f`
- frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Master Plan SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

## Completed scope

Fresh-fetch/pinning; current review-governance reconciliation; v6 identity/provenance and consumer-closure rules; historical Master Plan `BUG-TERMINAL-03`; full current Terminal destination path from both Folder UI and configured `command_path` through persisted SAF grant, `FileUtil.formatPath`, provider-aware writability, planner direct/staged selection, request construction, and `TerminalDownloadWorker`; current planner tests; Android official SAF/tree-URI semantics; exact-SHA GitHub status/check evidence; root reconciliation against cache/archive/move roots; final branch recount.

## Final provisional counts

P0 2 / P1 1 / P2 31. Overall `NOT_CLEAN`.

No new canonical root was discovered in this run. Material status change relative to the preceding user-visible run is the already-governance-promoted historical P2 `BUG-TERMINAL-03`, which this run independently reproduces and accepts into the current canonical blocker inventory. Count delta versus that preceding run: P2 +1 (30 -> 31).

## Finding disposition

### BUG-TERMINAL-03 — EXISTING historical P2 / OPEN / newly current-promoted

Violated invariant: SAF/provider destination authority must remain typed through the actual writer. A provider/tree grant must not be treated as proof that a reconstructed raw `/storage/...` pathname is directly writable by native yt-dlp.

Production evidence:

- Terminal Folder action obtains and persists the tree URI grant, then inserts `FileUtil.formatPath(treeUri)` into command text, dropping the provider identity.
- Settings `command_path` stores the selected URI. Factory computes `destinationWritable` with provider-aware `DocumentFile`/persisted-permission logic but separately computes `formattedDownloadLocation` by lossy raw-path conversion.
- Planner chooses direct writing when `cache_downloads=false && destinationWritable=true`, then emits native `-P formattedDownloadLocation` and marks `usesAppCache=false`.
- Real worker skips staging/provider-aware publication when `usesAppCache=false`; therefore the persisted SAF permission that established writability is no longer the authority exercised by the writer.
- Existing tests prove the direct-vs-fallback Boolean behavior but do not bind provider writability to provider-aware publication or reject the `content:// -> raw -P` mismatch.

Concrete reproduction: configure Terminal output via an SAF provider-only tree; persist its write grant; disable cache downloads; provider-aware writability returns true; planner selects direct native output; `-P` receives `FileUtil.formatPath(contentUri)`; native filesystem access is not authorized by the SAF grant; output can fail despite the selected tree being writable through ContentResolver/DocumentFile.

Affected files: `TerminalFragment.kt`, `FolderSettingsFragment.kt`, `FileUtil.kt`, `TerminalCommandPlan.kt`, `TerminalDownloadWorker.kt`, Terminal planner/production-wiring tests.

Root reconciliation: distinct from `BUG-CACHE-02` (staging-root authority), `BUG-DUPLICATE-03` (download-archive authority), and `BUG-MOVE-01` (partial provider publication after a source artifact already exists). This root owns Terminal destination authority loss before native execution.

No additional adjacent canonical root was established.

## Fixed invariants confirmed

No new closure. Previously closed F14 metadata guarded-patch invariant remains uncontradicted within reviewed touched paths.

## Open candidates/questions

None beyond existing canonical inventory. Provider-specific runtime manifestation on a concrete device/provider remains `NOT_VERIFIED` by this reviewer; source/upstream contract is sufficient to keep the root OPEN.

## Remaining review scope

None for this frozen-SHA run.

## Exact upstream semantic basis

Android official Storage Access Framework documentation: `ACTION_OPEN_DOCUMENT_TREE` selects a directory tree represented by a provider-backed URI; access to the selected directory/descendants is through the granted URI/tree-document contract. Persistable URI permission preserves that URI authority. No Android contract establishes generic raw-filesystem authority from such a grant.

Exact application writer basis: frozen source `TerminalCommandPlan.kt` emits native yt-dlp `-P` with `formattedDownloadLocation` on direct execution, while provider-aware copy/move publication exists only on the staged path.

## Execution evidence

Exact implementation SHA has zero GitHub commit statuses and zero check runs. Independent device/instrumentation execution was not performed in this run.

INDEPENDENT EXECUTION: NOT EXECUTED
