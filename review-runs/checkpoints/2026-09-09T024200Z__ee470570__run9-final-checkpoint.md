# Independent correctness review final checkpoint

## Pinned refs
- implementation `checkpoint/pre-baseline-review`: `ee4705703bf736284427b8380b9042af6b051e53`
- plan `plan/remediation`: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review governance baseline: `7ce2d9a61cbe58eedf79399ffd6ed0fd7080a585`
- ledger `ledger/remediation`: `899328bc91e4008e39a658387396a0106c8666ec`

## Independent verdict
`NOT_CLEAN`, P0 0 / P1 0 / P2 1. No material change relative to the preceding review of this implementation SHA.

## Completed review scope
- Fresh-ref pinning and final implementation HEAD recount.
- v6 retry/reconfigure, recovery identity, carrier, consumer, and CLEAN-gate requirements.
- Error classification for `PUBLICATION_OUTCOME_UNKNOWN`.
- Error UI route into configuration/redownload surfaces.
- `DownloadViewModel.turnDownloadItemsToProcessingDownloads`, `queueDownloads`, `prepareRetryMetadata` and exact retry metadata publication.
- `DownloadRetryPolicy` operation-id/attempt/strategy semantics.
- `DownloadWorker` prior-publication recovery handling for unresolved provider publication.
- GitHub combined-status context for the exact implementation SHA.

## Findings
- No new canonical finding.
- Existing `BUG-OUTPUT-01` P2 remains OPEN as incomplete remediation.
- UNKNOWN provider completion is correctly fenced and not replayed as an ordinary provider publication, but the user-facing reconfiguration path still lacks a convergence owner for that authority.
- `RECONFIGURE` is offered for `PUBLICATION_OUTCOME_UNKNOWN`; reconfiguration of the existing Error row uses `prepareRetryMetadata(... RECONFIGURED ...)`, whose policy preserves a nonblank existing `operationId` and advances only attempt/strategy. That correctly avoids bypassing the old UNKNOWN authority, but the next worker execution therefore encounters the same unresolved publication carrier and cannot resolve/revoke/transfer it. The action is safety-preserving but non-convergent.

## Fixed invariants
- UNKNOWN is not treated as proven non-creation.
- Durable quarantine/fencing prevents duplicate provider replay.
- Reconfigured retry does not silently mint a new independent operation to bypass the prior UNKNOWN carrier.

## Open questions / remaining debt
- A production actor is still required to converge UNKNOWN through exact discovery, deterministic rollback/revocation, provider-supported reconciliation/idempotency, or an explicit authority-transfer/new-operation protocol with correct duplicate-risk semantics.
- Independent JVM/emulator/device execution remains NOT_VERIFIED: the exact SHA has zero combined-status contexts.

## Checklist evolution
No new checklist gap beyond the prior refinement: terminal/quarantine contracts must inventory every suggested retry/reconfigure/redownload action and prove that each offered action can actually converge or explicitly transfer the unresolved authority. Deterministic return to the same terminal fence is not recovery closure.

## Final recount
`checkpoint/pre-baseline-review` still resolves to `ee4705703bf736284427b8380b9042af6b051e53`.
