# Independent Track A checkpoint — canonical count reconciliation 2

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Authoritative ledger changed: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Purpose

Reconcile the two recent checkpoints that re-confirmed already-counted semantic roots and therefore incorrectly incremented the working P2 total.

No source finding is being discarded merely because its evidence is duplicated. This checkpoint corrects blocker counting only and preserves the additional production evidence under the original semantic roots.

## Trusted count chain before the recent duplicate increments

The later c229 checkpoints establish the following canonical sequence:

1. `low-quality-saved-false-positive-correction.md` retracts `BUG-LOWQUALITY-SAVED-01` and records `P0 2 / P1 3 / P2 23`.
2. `history-undo-count-reconciliation.md` removes the duplicate `BUG-HISTORY-UNDO-PLAYLIST-01` alias of F17/P2-N and records `P0 2 / P1 3 / P2 22`.
3. `hardsub-generation-backup-revalidation.md` adds the distinct `BUG-HARDSUB-GENERATION-01` root and records `P0 2 / P1 3 / P2 23`.
4. `handoff-metadata-source-authority-followup.md` confirms no further root and preserves `P0 2 / P1 3 / P2 23`.

That `P2 23` is the correct basis immediately before the new group-relation finding.

## Valid new root since that basis

### `BUG-GROUP-TXN-01` — distinct P2, retained

`HistoryFragment` performs one Keyword/Youtuber group deletion as multiple independent DAO writes (members/relations first, group row later) without a transaction or durable recovery phase. Process death can therefore leave a live group with lost relationships. This is a distinct group-relation namespace and remains one new P2 root.

Correct delta: `23 + 1 = 24`.

## Duplicate increment 1 — ordinary Download handoff

The later `ordinary-download-handoff.md` checkpoint independently re-confirmed a valid production path, but it is not a new semantic root.

The earlier `download-queue-handoff.md` checkpoint had already established and counted `BUG-DOWNLOAD-HANDOFF-01` using the same invariant and fixed point:

- runnable Download row commits before WorkManager acceptance;
- `DownloadRepository.startDownloadWorker()` does not observe the enqueue `Operation`;
- startup recovery does not inventory arbitrary ordinary Queued/due Scheduled rows to reconstruct a missing worker trigger.

Therefore `ordinary-download-handoff.md` is additional evidence for the already-counted `BUG-DOWNLOAD-HANDOFF-01` and contributes **+0**, not +1.

Its reported move from P2 24 to P2 25 is superseded by this reconciliation.

## Duplicate increment 2 — History external-file deletion

The later `history-file-delete-cross-resource.md` checkpoint also confirms a real defect, but its semantic root was already explicitly included in F17/P2-N `BUG-HISTORY-01`.

The original `track-a-history-undo.md` checkpoint already states the exact path:

- `HistoryViewModel.executePreparedHistoryFileDeletion()` executes `HistoryFileDeletionEngine` first;
- the deletion gateway can irreversibly remove media;
- DB record/relationship mutation occurs afterward;
- failure/process death between those boundaries leaves partial History/file state;
- F17 acceptance explicitly requires staged/compensated or durable file-deletion convergence.

Therefore `BUG-HISTORY-FILE-DELETE-TXN-01` must be treated as an evidentiary alias/subcase of F17/P2-N, not a separate P2 root.

The default-video-folder migration provides an additional F17 consumer case: `migrateDefaultVideoFolderInternal()` moves an external file before `historyDao.updateDownloadPathById(...)`, and per-row path updates can be interrupted after the move. This broadens F17 evidence/acceptance coverage but still contributes **+0**.

Its reported move from P2 25 to P2 26 is superseded by this reconciliation.

## Scheduler S8/S9

The END and START consumer-generation findings remain subcases of the already-counted `BUG-SCHEDULE-01` root. They contribute +0.

## Corrected canonical working count

Starting from the latest trusted pre-group count:

- P2 23
- `BUG-GROUP-TXN-01`: +1
- ordinary Download handoff re-confirmation: +0 (already `BUG-DOWNLOAD-HANDOFF-01`)
- History file-delete/migration evidence: +0 (already F17/P2-N)
- scheduler S8/S9: +0 (already `BUG-SCHEDULE-01`)

Correct canonical working count:

- `P0 2`
- `P1 3`
- `P2 24`

Overall verdict remains `NOT_CLEAN`.

The earlier recent checkpoints remain useful evidence, but their P2 25/26 recounts are not canonical after this reconciliation.

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
