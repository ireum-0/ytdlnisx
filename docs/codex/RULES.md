# Execution Rules

## Scope control

- Implement only the selected task and its strictly required support code.
- Preserve unrelated user changes.
- Do not perform broad cleanup while touching a hotspot.
- Do not add dependencies, build tools, network services, telemetry, or remote configuration without explicit approval.
- Prefer a sequence of small changes over one cross-cutting rewrite.
- Use current project patterns unless they are the direct cause of the selected problem.

## Shared result model

Do not model every non-ideal result as `DownloadFailure`.

Use these conceptual outcomes:

```text
SUCCESS
SUCCESS_WITH_WARNINGS
RETRYABLE_FAILURE
FINAL_FAILURE
CANCELED
```

Attach zero or more structured issues:

```text
DownloadIssue
- stage
- code
- severity
- retryable
- suggestedActions
- redactedDetails
- source
```

Recommended issue stages:

```text
PREFLIGHT
EXTRACT
DOWNLOAD
MERGE
SUBTITLE
HARD_SUB
MOVE
HISTORY
NOTIFICATION
CLEANUP
```

Recommended severity:

```text
INFO
WARNING
ERROR
```

Do not add a confidence enum until classification rules are measurable. Prefer:

- confirmed cause,
- possible causes,
- unknown cause.

A first implementation may keep this model in memory and avoid a Room migration. Persist it only when a user-visible requirement needs persistence.

## Error classification

Classification priority:

1. Typed application exception or explicit state.
2. Known process exit code.
3. Known execution stage.
4. High-confidence output pattern.
5. Runtime, permission, storage, and destination checks.
6. Unknown.

Rules:

- Keep the first classifier small.
- Prefer high precision over high recall.
- Preserve the original redacted diagnostic output.
- Never hide the raw failure behind a guessed message.
- A pattern match may return multiple possible causes.
- Tests must cover positive and negative examples.
- Do not couple user-facing strings directly to raw English yt-dlp messages.

## Retry policy

Automatic retry is allowed only when the requested result does not change.

Examples allowed for limited automatic retry:

- transient network timeout,
- foreground initialization failure,
- same request after a short backoff,
- transient destination access failure.

Require user confirmation before changing:

- format,
- quality,
- container,
- subtitle inclusion,
- hard-sub behavior,
- downloader,
- authentication mode,
- output path,
- filename.

Never:

- retry a user-canceled task,
- retry indefinitely,
- repeat the same failed strategy without a limit,
- overwrite an existing valid output without an explicit policy,
- delete the original diagnostic record,
- create duplicate History entries for one logical retry chain.

Every retry chain must have:

- a stable logical operation identifier,
- an attempt number,
- a strategy identifier,
- a maximum attempt count,
- an explicit final state.

## Privacy and diagnostics

The required processing order is:

```text
raw process output
-> local classification
-> redaction
-> persistence
-> UI, notification, clipboard, export
```

Raw secrets must not be persisted or shown.

Redact at least:

- Authorization headers,
- Cookie and Set-Cookie headers,
- bearer tokens,
- access and refresh tokens,
- API keys,
- usernames and passwords,
- proxies,
- cookie file paths,
- terminal config paths,
- sensitive URL query values,
- private local paths where the full path is unnecessary.

Rules:

- Use one shared redaction path for normal downloads and Terminal.
- Generate command preview and actual sanitized arguments from the same source representation.
- Diagnostic bundles exclude cookies, command originals, full URL queries, account data, and full History by default.
- Notifications must use the least sensitive useful title.
- Incognito behavior must be preserved.

## File and storage rules

Preferred URI order:

1. Existing `content://` URI.
2. MediaStore URI.
3. Persisted SAF document or tree access.
4. Restricted FileProvider URI.
5. Raw-path fallback only where valid.

Rules:

- Never recursively delete a user-selected directory root.
- Automatic cleanup is limited to verified app-owned paths.
- Do not copy large files into cache just to obtain a share URI.
- Keep file-missing, permission-denied, and unknown states distinct.
- Do not run whole-library file scans during application startup.
- File scans must run off the main thread and be cancellable.
- Active download temporary files must not be removed by storage cleanup.
- Folder opening is best-effort and must provide a path-copy fallback.

## Performance rules

- No blocking file or process I/O on the main thread.
- No unbounded in-memory process logs.
- No full History scan for a single visible-page update.
- No runtime health probe during normal startup unless required for a pending operation.
- On-demand diagnostics must have per-probe timeouts.
- Long scans must expose progress or an observable running state.
- Measure before adding new database indexes.

Suggested performance targets are task-specific. Do not invent a target after implementation to make a task pass.

## Room rules

For any schema change:

- update the entity,
- update DAO and repository call paths,
- increment the database version,
- add a migration,
- export the schema,
- add a migration test with populated representative data,
- verify defaults and indexes,
- consider downgrade and restore behavior.

Avoid a schema change when an in-memory or existing-column implementation satisfies the requirement.

## WorkManager and process rules

When changing workers, inspect:

- unique-work naming and policy,
- duplicate execution,
- stop and cancellation paths,
- native process ownership,
- foreground setup,
- notification cleanup,
- DB state transitions,
- retry/backoff behavior,
- application process death,
- partial files and cache reuse.

WorkManager constraint loss is not equivalent to an in-process pause. Define whether the operation is canceled, requeued, resumed, or restarted.

## Media3 rules

When changing playback or queue behavior, inspect:

- actual player timeline,
- displayed queue,
- current media item,
- saved playback position,
- URI type,
- PiP entry and exit,
- background playback service,
- subtitle attachment,
- automatic transitions,
- process recreation.

Do not add queue features until current queue state has a single authoritative owner.

## CI security rules

- Use minimal permissions per job.
- Do not expose signing secrets to pull-request jobs.
- Separate verification jobs from signing and release jobs.
- Pin third-party actions to immutable versions or commit SHAs.
- Treat forked pull requests as untrusted.
- Do not print secret-derived files or values.
- Clean up temporary signing material.
- A notification failure must not make a valid build result ambiguous.

## Feature rollout

Use an existing setting or a narrowly scoped feature flag when a new behavior is high risk or changes user-visible results.

A flag must have:

- a default,
- migration or fallback behavior,
- a removal condition,
- no secret or remote dependency.

Do not leave permanent dead flags without a removal task.

## Final response requirements

Report:

- selected task ID,
- starting commit,
- changed files,
- behavior change,
- verification commands and results,
- skipped verification and reason,
- remaining risks,
- required manual checks.

Do not include full logs, secrets, large diffs, or unrelated findings.
