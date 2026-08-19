# Verification Checks

Choose the smallest verification tier that can falsify the change. Escalate for persistence, background execution, storage, playback, native runtime, or release work.

## Current automated workflows

### Pull requests

`.github/workflows/android-pr.yml` currently runs:

```bash
git diff --check
./gradlew :app:compileDebugKotlin -x lint
./gradlew :app:testDebugUnitTest
```

The job uses read-only repository permission, does not create signing material, and pins third-party actions by commit SHA.

### Pushes to main

`.github/workflows/android.yml` runs debug Kotlin compilation and JVM unit tests before the release-build job. Signing material is created only in the release job and cleaned with an always-run cleanup step. Artifact upload and external notification are separate concerns.

### Enforcement caveat

At the 2026-08-19 reviewed snapshot, the GitHub `main` branch is not protected and no required status checks are configured in branch protection. The workflows exist, but repository settings do not force every merge/direct push through them.

## Local verification tiers

### Documentation only

```bash
git diff --check
```

Also verify links and that current-state claims match source. Do not rewrite historical archive/release files to look current.

### Focused Kotlin/policy change

```bash
./gradlew :app:compileDebugKotlin -x lint
./gradlew :app:testDebugUnitTest
```

### Room/persistence change

In addition to compilation/unit tests:

- update `DBManager` version only when the schema changes;
- add the migration to `Migrations.migrationList`;
- export and commit the new Room schema;
- add populated migration coverage;
- run connected instrumentation when available/approved.

The current reviewed Room version is 52.

### WorkManager/background change

Check constraints, unique-work policy, process death/reconnect, cancellation, retry limits, foreground service type, notifications, and persisted terminal state. Device validation is required for release confidence.

### Storage/History deletion change

Exercise raw paths, SAF documents, tree roots, revoked grants, MediaStore-like targets, duplicate aliases, missing files, and revalidation immediately before deletion. Never infer ownership from a display path alone.

### Playback change

Check History/local/SAF playback, resume position, queue transitions, shuffle/current-item retention, PiP, background playback, subtitles, and lifecycle recreation.

### Native/download change

Check yt-dlp request construction, cancellation/process ownership, ffmpeg/ffprobe, aria2c where enabled, temporary files, final-file movement, History persistence, redacted diagnostics, and ABI/device behavior.

## Required regressions for current open correctness findings

Before closing the corresponding task, add or execute a regression for each:

1. **Backup restore marker remap** — restore into a DB where History IDs change/collide; a queued hard-sub replacement must target only the mapped History row and must not delete unrelated media.
2. **Automatic-keyword empty baseline** — an incomplete/failed empty fetch must not complete baseline; a later non-empty fetch must not become eligible merely because of that empty run.
3. **Rule edit during History Undo** — deleting record-only, changing rule condition/revision, then Undo must not resurrect the old derived RULE assignment.
4. **Concurrent metadata refresh** — changing scheduling/configuration while enrichment is in flight must preserve the concurrent edit.
5. **Hard-sub lookup failure** — transient subtitle lookup failure must remain retryable and must not mark the History item permanently ineligible.

## Release candidate

Use [`../testing/release-checklist.md`](../testing/release-checklist.md). Release evidence should correspond to the exact reviewed commit and supported ABI/device matrix.
