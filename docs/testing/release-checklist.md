# Release Checklist

Use only for a release candidate. This is not required for every small change.

## Source and version

- [ ] Release commit is identified and matches reviewed evidence.
- [ ] Working tree is clean.
- [ ] Version code and version name are intentional.
- [ ] Room database version matches schema changes.
- [ ] Exported Room schemas are committed.
- [ ] Release notes describe user-visible changes and known limitations.
- [ ] No open Blocker correctness finding is being waived silently.

## CI and supply chain

- [ ] Pull-request compile and unit tests passed.
- [ ] Main/release build passed for the release commit.
- [ ] Third-party actions are pinned.
- [ ] Job permissions are minimal.
- [ ] Signing secrets were not exposed to untrusted jobs.
- [ ] Temporary signing files were cleaned.
- [ ] External notification failure did not hide build status.
- [ ] Repository settings/branch protection enforce the checks claimed as merge requirements, or the lack of enforcement is explicitly recorded.

## Upgrade, backup, and database

- [ ] Fresh install works.
- [ ] Representative upgrade install works.
- [ ] Populated migration test passes on Android.
- [ ] History playback state survives upgrade.
- [ ] Observe Sources and automatic-keyword rule/assignment state survive upgrade.
- [ ] No destructive fallback is used unexpectedly.
- [ ] Backup reset-restore and merge-restore both work with changed primary keys.
- [ ] Every persisted cross-row reference/marker is remapped during restore.
- [ ] Queued hard-sub History replacement after restore targets only the mapped History row and cannot delete unrelated media.

## Automatic keyword rules

- [ ] `apply to existing videos = false` does not become true after an incomplete/failed empty baseline fetch.
- [ ] An authoritative empty playlist can complete baseline intentionally.
- [ ] Rule condition/revision changes do not allow History Undo to resurrect stale derived RULE assignments.
- [ ] Manual keyword assignments survive the same History delete/Undo path.

## Runtime and ABI

For every artifact intended as production supported:

- [ ] APK installs.
- [ ] App starts.
- [ ] yt-dlp probe passes.
- [ ] Python/runtime probe passes.
- [ ] aria2c probe passes when enabled.
- [ ] ffmpeg probe passes.
- [ ] ffprobe probe passes.
- [ ] QuickJS probe passes when required.
- [ ] Normal video download passes.
- [ ] Audio download passes.
- [ ] Video/audio merge passes.
- [ ] Subtitle flow passes.
- [ ] Hard-sub flow passes when supported.
- [ ] A transient hard-sub subtitle lookup failure remains retryable and is not recorded as a verified no-subtitle result.
- [ ] Cancel terminates native processes.

Unsupported or best-effort artifacts are clearly identified or not published.

## Download lifecycle

- [ ] Queue execution works.
- [ ] Scheduled execution works.
- [ ] Observe-triggered execution works.
- [ ] UI cancellation works.
- [ ] Notification cancellation works.
- [ ] Stopped work leaves no stale Active rows.
- [ ] Failed work leaves an actionable redacted diagnostic.
- [ ] Retry limits are enforced.
- [ ] One logical retry chain does not create duplicate final History entries.
- [ ] Metadata enrichment in flight cannot overwrite concurrent scheduling/configuration/path edits.

## Privacy

- [ ] Normal download logs are redacted.
- [ ] Terminal logs and dry-run preview are redacted.
- [ ] Notifications do not expose credentials, tokens, cookies, or full sensitive paths.
- [ ] Clipboard and exported diagnostics are redacted where required.
- [ ] Incognito behavior is preserved.
- [ ] Diagnostic output excludes secrets by default.

## Storage and file access

- [ ] Default-path files open and share.
- [ ] Custom-path files open or provide a fallback.
- [ ] SAF files work with valid permission.
- [ ] Revoked permission is shown distinctly from missing file where the UI claims a file state.
- [ ] Large files are not copied into share cache.
- [ ] Blocked sensitive files cannot be shared.
- [ ] History deletion removes only exact validated/revalidated targets.
- [ ] SAF tree roots/directories cannot be deleted as media targets.
- [ ] Cleanup deletes only verified app-owned paths.
- [ ] Active download temporary files survive cache cleanup.

## Playback

- [ ] History playback works.
- [ ] Local-folder playback works.
- [ ] SAF playback works.
- [ ] Resume position works.
- [ ] Queue automatic transition works.
- [ ] Shuffle/current-item state works.
- [ ] PiP works.
- [ ] Background playback works.
- [ ] Sidecar subtitle works.
- [ ] Missing subtitle does not crash playback.

## Final decision

- [ ] Remaining risks are documented.
- [ ] Required manual/device checks are complete.
- [ ] Supported Android versions are stated.
- [ ] Supported ABI policy is stated.
- [ ] Release artifacts correspond to the reviewed commit.
