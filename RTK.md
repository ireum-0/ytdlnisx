# RTK - Rust Token Killer (Codex CLI)

Use RTK selectively for commands whose output is usually noisy. Keep this
configuration project-local; do not enable RTK globally for this repository.

## Command Rules

Use RTK for these Git commands:

```bash
rtk git status
rtk git diff
rtk git log
rtk git show
```

Use RTK's Gradle wrapper command for noisy Android build, test, lint, and
connected-test tasks:

```bash
rtk gradlew assembleDebug
rtk gradlew testDebugUnitTest
rtk gradlew lint
rtk gradlew connectedDebugAndroidTest
```

Prefer RTK for searches and listings when the expected output is large:

```bash
rtk grep <pattern>
rtk find <path>
rtk ls <path>
```

Do not use RTK for shell built-ins, variable assignments, control operators, or
commands where exact stdout/stderr or exact exit-code semantics are important.

## Debugging

If Gradle output is too compressed, retry with Gradle diagnostic flags:

```bash
rtk gradlew <task> --stacktrace
rtk gradlew <task> --info
rtk gradlew <task> --debug
```

If full unfiltered output is required while still tracking usage, use:

```bash
rtk proxy <command>
```

## Meta Commands

```bash
rtk gain
rtk gain --history
rtk --version
```
