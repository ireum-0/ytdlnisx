# Track A checkpoint — P2-B/B8 download-archive authority

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`
Date: 2026-09-09
Independent execution: NOT EXECUTED

## P2-B/B8 — producer-side global archive commits before app publication

Confirmed as a subcase of existing P2-B. No canonical P2 count increase.

### Production chain

When duplicate prevention is configured as `download_archive`, `YTDLPUtil` adds app-generated:

`--download-archive <FileUtil.getDownloadArchivePath(context)>`

to the yt-dlp request.

The archive path is app-configurable; the archive is intentionally outside per-attempt output provenance.

Bundled yt-dlp 2025.11.12 records the archive from inside `YoutubeDL.process_info()` after the selected formats are considered successfully downloaded and before `after_video` postprocessors and before the native `YoutubeDLCompat.execute*()` call returns to the Android worker.

The Android worker's exact move/publication journal, History semantic commit, and Download-row finalization happen only after that native call returns.

Therefore the durable ordering can be:

producer/staging success
→ global download-archive entry
→ process death / cancellation / publication failure
→ no app-level published output or semantic success

### Downstream impact

The archive is not merely a yt-dlp local optimization. It is consumed by app-level duplicate decisions:

- `ObserveSourceWorker` reads archive IDs and skips a discovered source item as a duplicate when its URL contains an archived ID.
- `DownloadViewModel` manual duplicate detection similarly reads the archive and marks a requested item duplicate.

Thus an E1 whose output never reached app publication can nevertheless cause future automatic and manual downloads to be omitted as already completed.

### Related direct/cached recovery

This combines with P2-B/B7:

- cached E1 can leave producer output under an E1-owned numeric staging root that E2 cannot safely adopt/reuse;
- direct/no-cache E1 uses an execution-scoped UUID root (`.ytdlnisx-output/<operationToken>`), so an unjournaled E1 producer result can remain hidden while E2 gets a different root;
- the global archive can then cause E2's native producer to skip before the app has reconciled/published E1.

### User semantic modifiers

`YtdlpArgumentPolicy` does not block `--force-write-archive` / force-write archive semantics, and the F2 output preflight sensitive-write list covers `--download-archive` but not the force-write semantic modifier. In app archive mode, authored options such as force-write combined with simulate/skip-download can strengthen the same defect by recording global duplicate authority with no final media output.

This is not counted separately because the default app-generated archive already commits before app semantic publication.

## Required Cluster D acceptance

Cluster D must treat global archive state as part of producer-generation/semantic-success authority, not as an unrelated yt-dlp side effect.

At minimum prove:

1. an archive entry cannot become definitive app duplicate-success authority before exact app publication/semantic success is recoverable;
2. process death after native/staging success but before app publication does not leave a permanent false duplicate;
3. publication failure/cancellation after archive write has an explicit convergence or compensation path;
4. recovery of E1 and later E2 do not allow archive state to suppress the only recoverable output generation;
5. Observe-source and manual duplicate checks do not consume an uncommitted producer-side archive fact as final app success;
6. user force-write/simulate/skip-download combinations cannot manufacture app-level completed-download authority without the corresponding semantic success.

A viable correction may stage archive changes per execution and merge them only after semantic publication, or durably link archive entries to an app recovery/finalization witness. The implementation choice should be based on the smallest coherent Cluster D state machine.

## Other reviewed hypothesis

A generic `DownloadViewModel.deleteDownload()` entry point does not itself perform native quiescence, but the exact Active Downloads UI uses `cancelDownload()` / `pauseDownload()` rather than direct deletion. No concrete production active-row delete caller was established, so no active-removal race finding is added at this checkpoint.

## Canonical state

`P0 0 / P1 0 / P2 8`

P2-B now includes B8. Review Basis unchanged.
