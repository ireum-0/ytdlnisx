# Release Checklist

Use only for a release candidate. This is not required for every small change.

## Source and version

- [ ] Release commit is identified.
- [ ] Working tree is clean.
- [ ] Version code and version name are intentional.
- [ ] Room database version matches schema changes.
- [ ] Exported Room schemas are committed.
- [ ] Release notes describe user-visible changes and known limitations.

## CI and supply chain

- [ ] Pull-request compile and unit tests passed.
- [ ] Release build passed.
- [ ] Third-party actions are pinned.
- [ ] Job permissions are minimal.
- [ ] Signing secrets were not exposed to untrusted jobs.
- [ ] Temporary signing files were cleaned.
- [ ] External notification failure did not hide build status.

## Upgrade and database

- [ ] Fresh install works.
- [ ] Representative upgrade install works.
- [ ] Populated migration test passes.
- [ ] History playback state survives upgrade.
- [ ] Observe Sources state survives upgrade.
- [ ] No destructive fallback is used unexpectedly.

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

## Privacy

- [ ] Normal download logs are redacted.
- [ ] Terminal logs are redacted.
- [ ] Notifications do not expose credentials, tokens, cookies, or full sensitive paths.
- [ ] Clipboard and exported diagnostics are redacted.
- [ ] Incognito behavior is preserved.
- [ ] Diagnostic bundles exclude secrets by default.

## Storage and file access

- [ ] Default-path files open and share.
- [ ] Custom-path files open or provide a fallback.
- [ ] SAF files work with valid permission.
- [ ] Revoked permission is shown distinctly from missing file.
- [ ] Large files are not copied into share cache.
- [ ] Blocked sensitive files cannot be shared.
- [ ] Cleanup deletes only verified app-owned paths.
- [ ] Active download temporary files survive cleanup.

## Playback

- [ ] History playback works.
- [ ] Local-folder playback works.
- [ ] SAF playback works.
- [ ] Resume position works.
- [ ] Queue automatic transition works.
- [ ] PiP works.
- [ ] Background playback works.
- [ ] Sidecar subtitle works.
- [ ] Missing subtitle does not crash playback.

## Final decision

- [ ] Remaining risks are documented.
- [ ] Required manual checks are complete.
- [ ] Supported Android versions are stated.
- [ ] Supported ABI policy is stated.
- [ ] Release artifacts correspond to the reviewed commit.
