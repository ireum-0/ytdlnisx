# Verification Strategy

## General rule

Run the smallest verification that directly tests the selected change.

Do not claim that compile success proves runtime correctness.

Do not run heavy, connected, release, or network-dependent verification without approval when `AGENTS.md` requires approval.

## Basic commands

### Markdown-only change

```bash
git diff --check
```

### Kotlin or resource change

```bash
git diff --check
./gradlew :app:compileDebugKotlin -x lint
```

### Unit-testable logic

```bash
git diff --check
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin -x lint
```

### Room migration change

```bash
git diff --check
./gradlew :app:compileDebugKotlin -x lint
```

Connected execution, when approved:

```bash
./gradlew :app:connectedDebugAndroidTest
```

### Workflow change

- Validate YAML syntax.
- Review permissions.
- Review event triggers.
- Confirm secret availability by event type.
- Confirm third-party actions are pinned.
- Run equivalent local Gradle commands when possible.

## Pull-request verification tier

Run on every pull request:

- whitespace and patch check,
- debug Kotlin compile,
- JVM unit tests,
- no signing material,
- minimal permissions.

An emulator test may be added later after reliability and cost are known. Do not block initial PR checks on a complete device matrix.

## Main-branch verification tier

After merge or on scheduled CI:

- debug build,
- unit tests,
- selected instrumentation tests,
- artifact existence,
- basic arm64 runtime smoke when infrastructure exists.

Keep signing and external notifications separate from correctness checks.

## Release-candidate verification tier

Use risk-based representative coverage.

### Required Android coverage

- one minimum-API representative,
- one modern Android representative,
- one current target-SDK representative,
- one physical arm64 device.

Do not test every feature on every version. Assign each high-risk behavior to at least one relevant device.

### ABI policy gate

Before publishing a generated ABI artifact, classify it as:

- production supported,
- best effort,
- emulator only,
- unsupported.

A production-supported ABI must pass:

- installation,
- application startup,
- yt-dlp probe,
- aria2c probe if used,
- ffmpeg and ffprobe probe,
- normal download,
- merged video/audio download,
- cancellation,
- subtitle path used by the product.

Do not infer support from successful APK assembly.

## Change-specific checks

### Sensitive diagnostics

Test:

- `--cookies value`,
- `--cookies=value`,
- short username/password options,
- quoted values,
- values beginning with `-`,
- multiline Authorization and Cookie headers,
- bearer tokens,
- URL token queries,
- cookie and terminal config paths,
- multiple secrets in one string,
- non-sensitive URLs and errors that must remain readable.

### Download outcome and classification

Test:

- full success,
- success with History warning,
- success with notification warning,
- retryable network failure,
- final format failure,
- user cancellation,
- unknown output,
- multiple possible causes,
- bounded diagnostic input.

### Retry

Test:

- stable operation ID,
- attempt increment,
- attempt limit,
- repeated strategy prevention,
- process recreation,
- user cancellation,
- existing output conflict,
- History deduplication,
- notification cleanup,
- active-to-final DB transition.

### File access

Test:

- default raw path,
- custom raw path,
- `file://`,
- MediaStore URI,
- SAF document URI,
- SAF tree permission,
- missing file,
- revoked permission,
- blocked share file,
- large file outside provider roots,
- no file manager capable of opening a location.

### Storage cleanup

Test:

- app cache root,
- app external cache,
- app external files temp directory,
- symlink or canonical-path escape where applicable,
- active-download exclusion,
- partial deletion,
- permission failure,
- non-app-owned configured path refusal.

### Room

Test representative populated rows for:

- current recent migration,
- one longer migration chain,
- History playback state,
- Observe Sources retry state,
- new defaults,
- indexes,
- enum or serialized-list compatibility.

### WorkManager and native processes

Manually or with instrumentation verify:

- start,
- foreground setup,
- cancel from UI,
- cancel from notification,
- WorkManager stop,
- process death,
- network constraint loss,
- requeue,
- no stale Active rows,
- no orphan notification,
- no orphan yt-dlp/aria2c/ffmpeg process.

### Media3

Verify:

- History playback,
- local folder playback,
- SAF playback,
- queue transition,
- PiP enter and exit,
- background playback,
- saved position,
- near-end completion behavior,
- sidecar subtitle,
- missing subtitle,
- process recreation.

## Performance verification

Performance-sensitive changes require a predeclared target.

Examples:

- visible History page must not perform a full-table file scan,
- application startup must not run all runtime probes,
- file scans must be cancellable,
- process logs must have a fixed retention bound,
- database indexes require query-plan evidence.

Record:

- dataset size,
- device or emulator,
- operation,
- measured time,
- pass target.

Do not use an unspecified "acceptable performance" criterion.

## Reporting

For every verification command, report:

- exact command,
- passed or failed,
- relevant final error only,
- reason for any skip,
- manual checks still required.

Never paste secrets, complete build logs, or large stack traces.
