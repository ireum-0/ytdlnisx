# Improvement Task Status

This file reconciles the older improvement plan with the implementation
audited on 2026-07-30. It is a status record, not permission to implement
additional work.

## Status values

- **Implemented:** the intended capability is present in the current code.
- **Partial:** important behavior exists, but a documented gap remains.
- **Deferred:** proposed work is not implemented or lacks an approved design.
- **Historical:** retained only as context; no longer an active task.

Before changing any item, inspect the current code and use
[`docs/future-work.md`](../future-work.md) for the maintained recommendation,
priority, and complexity.

## Current status

| ID | Status | Current implementation and remaining evidence |
|---|---|---|
| `PRIV-01` | Implemented | Normal diagnostics use redaction/sanitization policies. New log and export paths must use the same policy. |
| `QG-01` | Implemented | Pull requests compile debug Kotlin and run JVM unit tests with minimal workflow permissions. |
| `QG-02` | Implemented | The release workflow compiles, tests, builds, signs, and publishes artifacts with CI-managed secrets. Device and ABI smoke evidence remains outside workflow success. |
| `DB-01` | Partial | Representative migration smoke coverage and exported schemas exist through Room version 53. Broader connected upgrade execution, especially real old databases and 52→53, remains required. |
| `FAIL-01` | Implemented | Download outcome, issue, stage, and supporting policy types are present. |
| `FAIL-02` | Implemented | High-confidence failures are classified. Unknown external messages deliberately remain unclassified. |
| `FAIL-03` | Implemented | Structured issue information and safe user actions are exposed in relevant download/history flows. |
| `RETRY-01` | Implemented | User-initiated retry is guarded by retry and ownership policies. It is not an automatic retry of every failure. |
| `FILE-01` | Implemented | Copy/open/share/location actions use URI and provider-aware fallbacks. Exact-folder support still varies by provider. |
| `FILE-02` | Implemented | Present, missing, and inaccessible states are represented in history/file actions. |
| `FILE-03` | Implemented | App-owned cache/storage cleanup is separated from user-owned deletion. Provider and permission limitations still apply. |
| `RUNTIME-01` | Implemented | On-demand runtime probes report local component health with redacted diagnostics. Per-ABI release evidence remains necessary. |
| `PRESET-01` | Implemented | Existing settings, templates, and preset precedence were reconciled in the download configuration model. |
| `PRESET-02` | Implemented | Minimal local download presets are implemented in SharedPreferences according to ADR-0001. Portable preset import/sync is not implemented. |
| `HIST-01` | Partial | History already supports broad search, filtering, grouping, file-state handling, and source-publication-date ordering/backfill. Additional filters should follow measured demand. |
| `PLAYER-01` | Partial | `PlaybackQueueState` centralizes queue data, but lifecycle, Media3, subtitle, PiP, URI, and navigation behavior remains concentrated in `VideoPlayerActivity`. |
| `TERM-01` | Implemented | Terminal command planning includes a dry-run/preview path and argument policy. |

## Newly implemented capability

The current branch also stores media source-publication time through result,
download, and history records; reads provider-specific dates; displays and
sorts them in History; defines missing-date policies; and offers an explicit
metadata backfill. This feature was not present in the older task registry.

## Active recommendations

The next work should be selected from
[Recommended Future Work](../future-work.md), not from the old READY/BLOCKED
ordering. Immediate items are:

1. resolve terminal Room projection warnings;
2. execute and extend device-backed Room migration coverage;
3. add focused source-publication-date propagation regressions;
4. test exported share entry points with hostile/malformed input;
5. define and prove an ABI support policy.

Each remains subject to the user's requested scope and the project working
rules.
