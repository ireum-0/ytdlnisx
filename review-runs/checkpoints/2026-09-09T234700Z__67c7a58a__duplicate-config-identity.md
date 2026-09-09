# Track A checkpoint — config duplicate identity gap

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

## Confirmed P2-K — config duplicate identity is raw-URL keyed in production

Master Plan F19 / `BUG-DUPLICATE-01` requires supported representations of the same media identity to share duplicate identity while preserving real configuration differences.

At the fixed Review Basis:

- `DownloadConfigurationDuplicatePolicy.requestConfiguration()` includes raw `DownloadItem.url` and raw `playlistURL` in equality.
- `YTDLPUtil` can preserve the user's raw single-item URL as `ResultItem.url`.
- `DownloadViewModel.createDownloadItemFromResult()` copies `resultItem.url` directly into `DownloadItem.url` without canonicalization.
- Manual queue `config` duplicate mode uses `DownloadConfigurationDuplicatePolicy.findMatch(activeAndQueuedDownloads, requested)` for live rows, so equivalent YouTube URL representations can miss the active/queued duplicate.
- Manual History config lookup computes a canonical/equivalent History set but performs the command match only against the raw exact-URL History lookup, leaving the canonical set unused for the actual decision.
- `ObserveSourceWorker` canonicalizes normal url/type and History lookups, but its `config` active/queued comparison again calls the raw-URL `DownloadConfigurationDuplicatePolicy.findMatch()`.

Concrete impact: the same supported media identity and otherwise identical download configuration can be queued twice when represented by equivalent URL forms (for example youtu.be vs youtube.com/watch), despite config duplicate prevention being enabled.

Classification: P2, HIGH. This is a current incomplete-remediation finding under F19. It is independent of the current Download publication/finalization P2-B/C/J cluster, but may be corrected in the same implementation wave as a separate semantic commit.

Required correction characteristics:

- compare canonical extractor/media source identity, not raw URL spelling, at the duplicate-identity layer;
- preserve true configuration distinctions (format, paths, subtitles, type, extra commands, filename/template, incognito, playlist-item semantics, etc.);
- do not canonicalize unrelated unsupported URLs into false matches;
- use the same identity semantics in manual queue and Observe config-mode comparisons;
- History command/config matching must use equivalent/canonical media identity rather than exact raw URL lookup only;
- add tests for watch/youtu.be/mobile/music supported forms and for nearby-but-distinct media/config cases.

Canonical working count after this checkpoint: `P0 0 / P1 0 / P2 9`.

`INDEPENDENT EXECUTION: NOT EXECUTED`
