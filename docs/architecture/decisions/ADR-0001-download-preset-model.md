# ADR-0001: Download preset persistence and precedence

Status: Accepted
Date: 2026-07-13

## Context

The repository has several configuration mechanisms with different ownership:

- `DownloadItem` stores one queued operation. Its reusable typed fields are
  `AudioPreferences`, `VideoPreferences`, `container`, and `SaveThumb`, but it
  also contains URLs, paths, formats, sections, commands, status, and source
  metadata that must not become a reusable preset.
- `DownloadViewModel.createDownloadItemFromResult()` builds a new item from
  global preferences and URL-filtered `CommandTemplate` rows.
- `DownloadAudioFragment` and `DownloadVideoFragment` mutate the resulting
  item for direct user selections before it is queued.
- `CommandTemplate` and `TemplateShortcut` store yt-dlp command text. They are
  edited through `CommandTemplatesFragment`, `AddExtraCommandsDialog`, and the
  command/Terminal UI. They are not typed download settings.
- `ObserveSourcesItem.downloadItemTemplate` is a complete `DownloadItem`
  snapshot. `ObserveSourceWorker` clones it for each discovered URL, so its
  semantics are source-specific and durable rather than global defaults.
- Quick Download enters through `HomeFragment`, `ShareActivity`, and
  `MainActivity`, then uses `DownloadViewModel.createDownloadItemFromResult()`
  directly or through `DownloadBottomSheetDialog`.

Reusing command templates would mix typed choices with executable option text.
Reusing Observe Source templates would persist source metadata and paths. A new
Room entity would add a migration even though the MVP is small preference data.

## Decision

Use a versioned `DownloadPreset` DTO stored as JSON in default
`SharedPreferences`. Keep one preference containing the preset list and one
preference containing the global Quick Download preset ID. The existing
settings backup already includes default preferences, so presets are included
when users back up or restore Settings. No Room migration is required.

The MVP stores only:

- audio or video type,
- a validated container,
- thumbnail-save state,
- typed `AudioPreferences` values except source-specific data,
- typed `VideoPreferences` values except content-specific audio format IDs.

It does not store paths, URLs, selected `Format` IDs, download sections,
filename templates, cookies, proxy values, extra commands, command templates,
incognito state, or queue metadata. Restored values are bounded and validated
against supported container, bitrate, and SponsorBlock values.

Application precedence is:

1. Direct user selection, including explicitly applying a preset in the
   download sheet and any edits made afterward.
2. Extractor or site rules, currently URL-filtered command templates selected
   by `DownloadViewModel`.
3. The global Quick Download preset.

The global preset is applied only on explicit Quick Download entry paths. A
manual download starts from existing defaults and site rules. Applying a preset
creates a copy; deleting or renaming the preset cannot change queued items or
Observe Source templates.

Command text remains outside presets. `YTDLPUtil` continues to build requests
from the final `DownloadItem`, and `YoutubeDLCompat` continues to enforce
`YtdlpArgumentPolicy` when producing executable arguments. Presets therefore do
not create a second command parser or an arbitrary command-injection path.

## Consequences

- The MVP needs no database migration and is covered by the existing Settings
  backup path.
- Presets remain intentionally small and cannot select a site-specific format,
  output directory, filename template, or command.
- A future site-rule feature must remain a separate layer and preserve the
  stated precedence.
- If presets later need sharing, large collections, or independent backup
  selection, persistence should be reconsidered rather than expanding the
  preference payload without bounds.
