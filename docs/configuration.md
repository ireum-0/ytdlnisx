# Configuration and Environment

## Build configuration

| Item | Current implementation |
|---|---|
| Module | `:app` |
| Application ID / namespace | `com.ireum.ytdl` |
| Version | `1.8.9` |
| Minimum Android | API 24 |
| Compile / target Android | API 36 |
| Java / JVM target | 17 |
| Gradle distribution | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.3.0 |
| Room schema | 53 |

The build produces universal, arm64-v8a, armeabi-v7a, x86, and x86_64 APK
variants. These are build outputs, not a guarantee of equal native-runtime
support.

## Local developer environment

- Configure the Android SDK through the standard untracked
  `local.properties`.
- Use JDK 17 to match CI.
- Allow Gradle network access for the first dependency resolution.
- Do not commit signing files, API keys, cookies, or local paths.
- Optional release signing is loaded from `keystore.properties` when present.

The build uses Room KSP schema export to `app/schemas`. A schema-changing
commit must include the updated entity, migration, database version, exported
schema, and representative migration test.

## Application settings

Settings are stored primarily in default SharedPreferences. The UI groups them
as follows:

- Appearance: language, theme, accent, high contrast, icon, thumbnail/card
  visibility, navigation, labels, search engine, and gestures.
- YouTube/metadata: recommendation source, optional API key, locale, extractor,
  format source, player client, Data Sync ID, extractor arguments, and PoToken
  generation.
- Downloading: incognito, Quick Download, duplicate policy, archive, metered
  networks, IPv4, cookies, proxy, type defaults, concurrency, rate/buffer/time
  limits, aria2c, logs, retries, and scheduling.
- Processing: SponsorBlock, metadata/thumbnail embedding, subtitles, chapters,
  codecs, containers, quality, hard-sub behavior, and extra commands.
- Folders: video/audio/command/cache destinations, filename templates,
  all-files access, cache retention/movement, cache cleanup, and logs.
- Updates: yt-dlp source/version/update behavior, application update checks,
  beta checks, format refresh, and changelog.
- Advanced: format sorting, certificate checks, flat-playlist behavior,
  diagnostics, command-template metadata access, and YouTube-specific options.

Several settings contain sensitive values, particularly the YouTube API key,
proxy, cookies, and extractor arguments. They must not be printed in diagnostic
or documentation output.

## Video quality, retries, and replacements

Numeric video requests and requests with a known source maximum use temporary
staging even when direct/no-cache downloading is selected. This allows the app
to inspect the merged output before it is published. The effective target is
the lower of the user-selected height and the current per-item source maximum;
an intentional 360p selection is therefore not upgraded, while a 1080p request
for a source whose maximum is 720p expects 720p.

On YouTube, an authenticated attempt that completes below the effective target
gets one bounded public retry. The retry is rebuilt from the same download item,
so format selection, sorting, merge/recode, and processing preferences are
preserved. A normal download that remains below target is retained with a
structured warning rather than silently relabeled. A History quality
replacement is stricter: a below-target or invalid result is rejected and the
old file remains. Explicit History replacements bypass the download archive so
the archive cannot turn a requested replacement into a no-output success.

## Runtime permissions and system settings

The manifest declares:

- Internet and network-state access.
- Legacy and modern media-read permissions.
- Notification permission.
- Foreground-service permissions for data sync and media playback.
- Exact-alarm permission.
- Battery-optimization exemption request capability.

Permissions are requested or used only by relevant flows, but device/OEM
behavior can still restrict background work. `requestLegacyExternalStorage`
exists for compatibility; modern storage paths prefer MediaStore and SAF.

## Backup and restore

Android platform backup is disabled (`allowBackup=false`). The app implements
an explicit backup/restore flow for selected preferences, Room data, and
supported files such as custom thumbnails. Automatic backups and the backup
destination are user-configurable.

Backups can contain private library metadata and settings. They should be
treated as sensitive user data.
