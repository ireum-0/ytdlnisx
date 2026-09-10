# Independent Track A checkpoint — low-quality Saved authority

Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
Verdict: `NOT_CLEAN`
Independent execution: NOT EXECUTED

This review remains pinned to the fixed contiguous independently-CLEAN basis and does not inspect in-progress implementation work.

## New `BUG-LOWQUALITY-SAVED-01` — CONFIRMED P2

The Queued UI includes both `Queued` and `WaitingForMembership` rows. Its long-press save-for-later path mutates the supplied item to `Saved` and calls `DownloadViewModel.updateToStatus(id, Saved)`, which delegates to the generic Download status writer. That path does not consume/update the linked low-quality child or parent operation.

The repository already has a dedicated Saved transition that calls `markLinkedDownloadSaved()`, and `LowQualityRedownloadLinkedDownloadPolicy` explicitly defines `Download=Saved -> child=SKIPPED`. This demonstrates that Download status and linked low-quality state are one semantic transition, not independent metadata.

Concrete fixed point:
1. a low-quality child is linked to a Download in `QUEUED` or membership-waiting state under a RUNNING operation;
2. the Queued UI moves the Download to `Saved` through the generic status API;
3. the Download is no longer worker-runnable, while the linked child remains nonterminal and the parent remains RUNNING;
4. ordinary queue execution does not consume the Saved row. Startup/recovery reconciliation can later derive `SKIPPED`, but the normal UI mutation has created durable cross-table debt that may remain until a restart or an unrelated recovery trigger.

Acceptance:
- every production Saved transition for a linked Download must atomically or recoverably update the exact linked child and parent operation;
- generic status APIs must reject privileged linked transitions or delegate to the dedicated transition;
- cover both `Queued` and `WaitingForMembership`, process death after each side of the transition, and parent completion when this is the last nonterminal child.

## Prior scope retained

`BUG-CACHE-ROOT-01` remains OPEN P2. Output destination preferences are not broadened into that finding: ordinary Download destination is captured in `DownloadItem.downloadPath`, and Terminal creates a concrete command plan before native execution. The mutable cross-generation namespace problem is specific to the repeatedly re-read app cache root.

The existing `BUG-DOWNLOAD-DELETE-SNAPSHOT-01` D4 scope also remains retained.

## Working independent recount

`P0 2 / P1 3 / P2 23`

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.
Authoritative ledger unchanged.

Other independently/unindependently produced review-branch checkpoints do not override this semantic count without exact-source support at the fixed basis.
