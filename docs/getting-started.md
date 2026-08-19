# Project Overview and Setup

## Purpose

YTDLnisX is an unofficial Android fork of YTDLnis. It combines yt-dlp-based
media downloading with a local history/library, download queue management,
Media3 playback, and Android storage integration. The package and application
ID are `com.ireum.ytdl`.

The application is designed for Android 7.0 and newer (`minSdk 24`). The current
source declares `compileSdk 36`, `targetSdk 36`, and application version
`1.8.9`.

## Install a release

Published APKs are distributed through the
[GitHub Releases page](https://github.com/ireum-0/ytdlnisx/releases). ABI-specific
and universal APK names are produced by the Gradle build. Successful assembly
alone does not establish that every bundled runtime works on every ABI; see
[Known limitations](known-limitations.md).

## Build from source

Required local tools:

- JDK 17 for the supported CI configuration. The source targets Java/JVM 17.
- Android SDK platform 36 and the build tools selected by Android Gradle Plugin.
- Network access on the first build to resolve Gradle plugins and dependencies.
- An SDK path in the normal Android `local.properties` file.

Basic commands:

```powershell
.\gradlew.bat :app:compileDebugKotlin -x lint
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Release signing is optional for local development. When a valid
`keystore.properties` is present, `app/build.gradle` configures the release
signing block. Signing files and `local.properties` are intentionally not
documented or committed because they may contain local or secret material.

## First-run behavior

`App` initializes notification channels, yt-dlp/aria2 integration, and bundled
subtitle/ffmpeg support. The app then opens the Home destination. Downloads,
playback, exact scheduling, notifications, and media-library access can require
Android runtime permissions or system settings depending on device version and
the feature used.

The default database is a Room database managed by `DBManager`. Current schema
version is 53. Migrations are applied explicitly; destructive fallback is not
configured.
