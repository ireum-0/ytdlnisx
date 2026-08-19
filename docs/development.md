# Development Guide

## Repository layout

- `app/`: the only Android application module
- `app/src/main/java/`: Kotlin product code
- `app/src/main/res/`: XML layouts, strings, menus, drawables, and other
  Android resources
- `app/src/main/assets/`: application assets and bundled runtime payloads
- `app/src/main/schemas/`: exported Room schemas
- `app/src/test/`: JVM unit tests
- `app/src/androidTest/`: device and emulator tests
- `.github/workflows/`: pull-request, release, and notification automation
- `docs/`: current documentation and dated historical material

Generated build output, local SDK configuration, signing material, and bundled
native/runtime payloads should not be edited as part of routine application
changes.

## Development workflow

1. Read the root project instructions and inspect the current branch.
2. Revalidate the requested behavior against the code; archived audits and plans
   can be stale.
3. Keep changes narrow. Room, WorkManager, Media3, storage, and native download
   paths require extra review because failures can affect upgrades, background
   execution, playback, or user files.
4. Add focused tests for logic that can run on the JVM and device tests where
   Android framework or Room migration behavior is essential.
5. Run the smallest relevant checks during development and the broader suite
   before release. See [Testing](testing/README.md).
6. Review the final diff for generated files, secrets, signing data, and
   unrelated local changes.

## Architecture conventions

- UI is primarily Activity/Fragment based, with XML layouts and ViewBinding.
  Compose is used only in selected web-oriented screens.
- ViewModels and repositories mediate most UI/database interactions. The
  project does not use a dependency-injection framework.
- Room is the durable source of truth for downloads, history, terminal records,
  observe-source state, keyword metadata, and related entities.
- SharedPreferences stores user settings, download presets, and lightweight
  coordination state.
- WorkManager owns durable background work. Do not treat WorkManager
  constraints as an in-process pause primitive.
- File access spans raw paths, MediaStore, SAF/DocumentFile, and FileProvider.
  Preserve the distinction between app-owned and user-owned content.

## High-risk changes

For Room changes, update entities, DAOs, migrations, the database version,
exported schemas, and migration tests together. A schema export does not prove
that a migration works on a device.

For WorkManager changes, review unique-work policy, constraints, cancellation,
retry classification, foreground-service behavior, notifications, and cleanup.

For Media3 changes, review saved position, queue ownership, URI type, subtitles,
PiP, media-session behavior, and lifecycle cleanup.

For download/native-runtime changes, review ABI packaging, cancellation,
partial-success behavior, temporary-file cleanup, user-visible errors, runtime
initialization, and licensing. Do not assume every generated ABI is operational
without device evidence.

## Adding or changing documentation

Current behavior belongs in the main documentation tree. Proposed behavior
belongs in [Future Work](future-work.md). Dated audits, prompts, and superseded
plans belong under `archive/` and must be identified as historical. Update
`docs/README.md` whenever a major document is added, moved, or renamed.
