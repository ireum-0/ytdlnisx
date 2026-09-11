# Independent correctness review checkpoint — History duplicate closure

- Implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Review bootstrap SHA: `8cd70b8250a0da218cd2145eac2df05b5b2f3610`
- Ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist: v6 blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Completed scope

- Re-read current production `HistoryDuplicateIdentity`, `WebUrlInput`, `HistoryRepository.getDuplicateGroups`, `HistoryViewModel.deleteDuplicates`, and `HistoryReplacementSourceIdentity` at the pinned implementation SHA.
- Reconstructed the destructive path: typed History source -> duplicate identity -> grouping -> deterministic survivor -> keyword-assignment merge -> playlist-reference/History deletion.
- Re-read the prior correction boundary in review bootstrap `8cd70b8...` rather than treating the implementation diff as closure evidence.
- Fresh-verified exact yt-dlp upstream `bbc809a1161d3bfca51fa36f59dda35556ee85a0`, `yt_dlp/extractor/sina.py`: Sina fragment `#<id>` is extractor-significant media identity.

## Provisional P0/P1/P2

- P0: 3
- P1: 3
- P2: 25 if the current closure survives final surrounding regression review; prior count was 26.

## Confirmed fixed invariants

- `BUG-HISTORY-DUPLICATE-IDENTITY-01` prior fragment false-equivalence is fixed: generic destructive identity now preserves `URI.rawFragment` through a duplicate-specific strict key.
- Existing distinctions remain: generic HTTP vs HTTPS, host/effective-port/path/raw-query, DownloadType, and valid YouTube stable video identity.
- Blank/malformed/unsupported/unprovable sources continue to fail closed for automatic destructive grouping.
- `HistoryReplacementSourceIdentity` remains on the pre-existing fragment-blind strict helper, so the duplicate remediation does not silently alter that separate replacement contract.
- Survivor ordering (`time`, then `id`) and DB-only deletion/relationship behavior are unchanged.

## Open candidates / questions

- Final check for a new coarse-equivalence regression in generic source canonicalization (default-port/host/path normalization) requires a concrete extractor-semantic counterexample before becoming a finding.
- F17 mutation atomicity remains a separate pre-existing root and is not evidence against closure of the duplicate-selection identity root.

## Remaining review scope

- Sample surrounding current source/identity consumers for regression.
- Recount canonical roots without double-counting F17.
- Fresh-ref recount and final checkpoint immediately before verdict.

## Exact upstream semantic basis

- `yt-dlp/yt-dlp@bbc809a1161d3bfca51fa36f59dda35556ee85a0`, `yt_dlp/extractor/sina.py`: `_VALID_URL` accepts fragment media IDs and the upstream test URL carries `#250576622` as the extracted ID.

## Provisional disposition

`BUG-HISTORY-DUPLICATE-IDENTITY-01`: **FIXED candidate at aa1616a2...**. No new finding confirmed in this run so far.
