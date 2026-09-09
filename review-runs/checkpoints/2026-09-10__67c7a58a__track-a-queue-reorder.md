# Independent Track A correctness checkpoint — queue mixed-selection reorder

- Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`
- Implementation branch HEAD observed during this review: `8de0e0f8aa7c0f4294c41b4b6e958acf53a778fa`
- Review branch parent before this checkpoint: `ba297e93c14ee07d069dff598cf05fad1c5267c8`
- Authoritative ledger ref (not modified): `899328bc91e4008e39a658387396a0106c8666ec`

## Semantic decision established by source review

### F22 `BUG-QUEUE-01` — CONFIRMED P3 / hardening-correctness item

This does not change the canonical P2 count.

Exact production source at the fixed Review Basis:

- `app/src/main/java/com/ireum/ytdl/ui/downloads/QueuedDownloadsFragment.kt`
  - queued-tab population/count includes both `Queued` and `WaitingForMembership`.
  - contextual `getSelectedIDs()` uses the same `Queued + WaitingForMembership` selection universe for inverted/select-all style selection; directly checked waiting rows are also retained.
  - the contextual Up/Down actions are inflated without a status-dependent disable/hide gate and forward the full selected ID set to `putAtTopOfQueue()` / `putAtBottomOfQueue()`.
- `app/src/main/java/com/ireum/ytdl/database/viewmodel/DownloadViewModel.kt`
  - the Up/Down calls delegate directly to DAO reorder operations.
- `app/src/main/java/com/ireum/ytdl/database/dao/DownloadDao.kt`
  - `getQueuedDownloadsListIDs()` selects only `status='Queued'`.
  - `putAtTopOfTheQueue()` / `putAtBottomOfTheQueue()` intersect caller IDs with that queued-only list.
  - `rewriteQueuedOrder()` calls `updateQueuedOrderPosition()`, whose SQL also requires `status='Queued'`.

Concrete behavior:

A contextual selection containing both queued and membership-waiting rows presents one reorder action over the visible selection, but the DAO silently reorders only the queued subset. The waiting subset is ignored.

Invariant:

> A visible reorder action must either apply to the entire selected set under one defined ordering model or be unavailable when the selected set contains rows outside that model.

Classification rationale:

The production behavior is incorrect and source-confirmed, but current evidence shows partial UI/order semantics rather than destructive data loss, cross-generation authority corruption, or an irreversible effect. Keep it at the Master Plan's P3 severity rather than inflating the P2 blocker count.

## Acceptance condition

- Hide/disable Up/Down whenever the resolved selection includes `WaitingForMembership`, or define a sound reorder model that applies to all selected statuses.
- Revalidate statuses immediately before executing reorder to close async menu/status races.
- Cover direct, inverted, select-all, and select-between selection semantics.
- Waiting rows remain selectable for unrelated actions such as delete/copy.

## Working count

Unchanged:

`P0 0 / P1 0 / P2 11`

## Execution evidence

No JVM, instrumentation, emulator, or device execution was performed.

`INDEPENDENT EXECUTION: NOT EXECUTED`
