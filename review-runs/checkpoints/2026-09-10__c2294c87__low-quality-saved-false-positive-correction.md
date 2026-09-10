# Independent Track A correction — retract `BUG-LOWQUALITY-SAVED-01`

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Correction

Retract the previously counted P2 root `BUG-LOWQUALITY-SAVED-01` as a false positive.

The earlier finding claimed that the Queued-download long-press Save-for-later action used a generic Saved status update that changed only the Download row and failed to converge the linked low-quality ledger.

That producer-path premise is false at the exact fixed basis.

## Exact production path

`QueuedDownloadsFragment` long-click does mutate its local presentation item to Saved and then calls:

`downloadViewModel.updateToStatus(it.id, DownloadRepository.Status.Saved)`.

However `DownloadViewModel.updateToStatus()` explicitly special-cases `Status.Saved`:

- it does **not** call generic `repository.setDownloadStatus()`;
- it calls `repository.moveToSaved(id)`;
- it immediately refreshes `LowQualityRedownloadLedger` with the returned affected operation IDs.

`DownloadRepository.moveToSaved()` performs the Saved transition inside its Room transaction and calls `markLinkedDownloadSaved(...)`, which converges the linked low-quality state under the same repository semantic path.

Therefore the claimed durable state:

`Download = Saved` while the linked low-quality child remains indefinitely `QUEUED/WAITING` solely because this UI action bypassed linked-ledger convergence

does not follow from the production source.

## Disposition

- `BUG-LOWQUALITY-SAVED-01`: **RETRACTED / FALSE POSITIVE**
- Do not count it as P2.
- Do not ask an implementation agent to remediate it.
- The local assignment `it.status = Saved` before navigation is presentation state and is not evidence of the durable repository mutation path.

This correction does not affect distinct low-quality cancellation/History-replacement findings.

## Count reconciliation

Immediately preceding checkpoint added one distinct root:

- `BUG-DOWNLOAD-HANDOFF-01` +1

This correction removes one previously counted root:

- `BUG-LOWQUALITY-SAVED-01` -1

Therefore the canonical working count returns to:

`P0 2 / P1 3 / P2 23`.

INDEPENDENT EXECUTION: NOT EXECUTED
