# Task Registry

## Status values

- `READY`: may be selected when it matches the user's request.
- `BLOCKED`: requires a listed decision or dependency.
- `LATER`: valid backlog, not current priority.
- `DONE`: verified in current code; update only after revalidation.

Do not implement all tasks in one branch.

---

## PRIV-01 — Redact normal download diagnostics

**Status:** READY
**Priority:** P0
**Dependencies:** none

### Goal

Apply the shared sensitive-text redaction path to normal-download diagnostics before persistence, notification, clipboard, or export.

### Scope

- Inspect normal download command and log construction.
- Redact command output before storing it in `LogItem`.
- Redact user-visible failure text and notification text.
- Reuse or extend `SensitiveTextRedactor`.
- Add focused tests for normal and multiline diagnostics.

### Non-goals

- No diagnostic bundle.
- No new Room schema.
- No broad logging framework.
- No removal of useful non-sensitive error details.

### Likely files

- `app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt`
- `app/src/main/java/com/ireum/ytdl/util/SensitiveTextRedactor.kt`
- `app/src/main/java/com/ireum/ytdl/util/NotificationUtil.kt`
- `app/src/test/.../SensitiveTextRedactorTest.kt`

### Acceptance criteria

- Cookie paths, headers, credentials, proxy values, and token query values are absent from persisted normal-download logs.
- Redaction handles separate option values and `--option=value`.
- Multiline headers are redacted.
- Non-sensitive yt-dlp error details remain readable.
- Incognito behavior is unchanged.
- Unit tests include negative cases to prevent over-redaction.

### Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin -x lint
```

---

## QG-01 — Add pull-request compile and unit-test checks

**Status:** READY
**Priority:** P0
**Dependencies:** none

### Goal

Detect Kotlin compile and unit-test failures before merge without exposing signing secrets.

### Scope

- Add a pull-request workflow or a safe PR job.
- Run:
  - `git diff --check`
  - `:app:compileDebugKotlin -x lint`
  - `:app:testDebugUnitTest`
- Use minimal permissions.
- Do not create signing files.
- Cache Gradle safely.

### Non-goals

- No release signing.
- No connected-device test.
- No all-ABI release build.
- No dependency upgrade.

### Likely files

- `.github/workflows/android-pr.yml`
- or a carefully separated job in `.github/workflows/android.yml`

### Acceptance criteria

- PRs run compile and unit tests.
- PR jobs do not receive signing secrets.
- A unit-test failure fails the workflow.
- Existing main-branch release behavior is not silently removed.
- Third-party actions used by the new job are pinned.

### Verification

- Validate workflow syntax.
- Run the same Gradle commands locally when available.
- Review permission and secret exposure statically.

---

## QG-02 — Harden the existing release workflow

**Status:** READY
**Priority:** P0
**Dependencies:** QG-01 recommended

### Goal

Reduce supply-chain and secret exposure risk in the main-branch build workflow.

### Scope

- Separate build, signing, artifact, and notification responsibilities where practical.
- Reduce job permissions.
- Pin mutable third-party action references.
- Prevent secret-derived file output.
- Clean temporary signing material.
- Make notification failure independent from build result.

### Non-goals

- No release-system redesign.
- No migration to another CI provider.
- No new distribution service.
- No change to signing identity.

### Likely files

- `.github/workflows/android.yml`

### Acceptance criteria

- No third-party action is referenced by a mutable branch such as `master`.
- Pull-request paths cannot access signing secrets.
- Signing files are created only in signing-required jobs.
- Build success remains visible even if external notification fails.
- Job permissions are no broader than required.

### Verification

- Static workflow review.
- Workflow syntax validation.
- Main-branch dry run only with explicit approval.

---

## DB-01 — Expand representative migration tests

**Status:** READY
**Priority:** P0
**Dependencies:** none

### Goal

Increase confidence that representative populated databases migrate to the current schema without data loss.

### Scope

- Inspect current migration tests and current DB version.
- Add populated-data migration cases for the most recent schema changes.
- Verify important defaults, indexes, and preserved fields.
- Prefer representative paths, not every historical version.

### Non-goals

- No schema change unless a real defect is found and separately approved.
- No exhaustive test of every version pair.
- No destructive migration fallback.

### Likely files

- `app/src/androidTest/java/com/ireum/ytdl/MigrationSmokeTest.kt`
- `app/schemas/...`
- `app/src/main/java/com/ireum/ytdl/database/Migrations.kt` only if a valid defect is found

### Acceptance criteria

- Tests include populated rows.
- History playback fields are preserved in a representative path.
- Observe Sources retry/observed state is preserved in a representative path.
- Expected defaults and indexes are asserted.
- The test uses exported schemas and validates the current version.

### Verification

```bash
./gradlew :app:compileDebugKotlin -x lint
```

Connected migration execution requires explicit approval:

```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## FAIL-01 — Introduce outcome and issue types

**Status:** READY
**Priority:** P1
**Dependencies:** PRIV-01 recommended

### Goal

Represent full success, partial success, retryable failure, final failure, and cancellation without treating every warning as total failure.

### Scope

- Define a small outcome type and structured issue type.
- Map one existing download path to the new representation.
- Keep persistence unchanged unless required.
- Preserve existing user behavior in the first change.

### Non-goals

- No full classifier.
- No UI redesign.
- No automatic retry.
- No Room migration unless separately justified.
- No rewrite of `DownloadWorker`.

### Suggested conceptual model

```text
DownloadOutcome:
- SUCCESS
- SUCCESS_WITH_WARNINGS
- RETRYABLE_FAILURE
- FINAL_FAILURE
- CANCELED

DownloadIssue:
- stage
- code
- severity
- retryable
- suggestedActions
- redactedDetails
```

### Acceptance criteria

- A History or notification failure after valid file creation can be represented as `SUCCESS_WITH_WARNINGS`.
- User cancellation is distinct from failure.
- The model is testable without Android process execution.
- Existing logs and notifications continue to work.
- No sensitive raw text is added to the model.

### Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin -x lint
```

---

## FAIL-02 — Classify high-confidence failures

**Status:** READY
**Priority:** P1
**Dependencies:** FAIL-01, PRIV-01

### Goal

Classify a small set of actionable failures with high precision.

### Initial codes

- `NETWORK_TIMEOUT`
- `AUTH_REQUIRED`
- `FORMAT_UNAVAILABLE`
- `STORAGE_FULL`
- `DESTINATION_NOT_WRITABLE`
- `FFMPEG_FAILED`
- `UNKNOWN`

### Scope

- Use typed state and stage before output pattern matching.
- Return multiple possible causes when one pattern is ambiguous.
- Keep raw redacted diagnostics available.
- Add table-driven unit tests.

### Non-goals

- No claim of complete yt-dlp coverage.
- No confidence scoring.
- No machine learning.
- No automatic retry.
- No extractor-specific rule explosion.

### Acceptance criteria

- Every initial code has positive and negative tests.
- `UNKNOWN` remains available.
- Ambiguous HTTP status text is not presented as a confirmed authentication cause.
- User-facing strings do not depend directly on raw yt-dlp English wording.
- Classifier input and output are bounded.

### Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin -x lint
```

---

## FAIL-03 — Show structured failure information and safe actions

**Status:** READY
**Priority:** P1
**Dependencies:** FAIL-02

### Goal

Show a concise failure summary and only actions that are safe and relevant.

### Scope

- Display issue stage and user-facing summary.
- Offer:
  - view redacted log,
  - copy redacted summary,
  - open relevant settings,
  - retry only when supported.
- Preserve access to detailed diagnostics.

### Non-goals

- No automatic fallback.
- No diagnostic ZIP.
- No global notification redesign.
- No speculative action when the cause is unknown.

### Acceptance criteria

- Every shown action maps to a valid handler.
- Unknown failures do not show misleading cookie or update actions.
- File-created partial success is not shown as total download failure.
- Private/incognito notification behavior is preserved.
- Accessibility labels and localized strings are added.

### Verification

```bash
./gradlew :app:compileDebugKotlin -x lint
```

Manual checks:

- auth-required failure,
- format-unavailable failure,
- storage failure,
- unknown failure,
- partial success warning,
- incognito failure.

---

## RETRY-01 — Add user-initiated safe retry

**Status:** READY
**Priority:** P1
**Dependencies:** FAIL-02, FAIL-03

### Goal

Allow a user to retry a failed logical operation without duplicate state or silent result changes.

### Scope

- Add stable retry-chain metadata using existing storage where possible.
- Support same-settings retry.
- Support one explicitly selected fallback strategy.
- Enforce attempt limits.
- Preserve original and retry diagnostics.

### Non-goals

- No unlimited retry.
- No silent quality reduction.
- No global queue pause.
- No retry of user-canceled work.
- No replacement of valid existing files without a policy.

### Acceptance criteria

- Same logical operation is identifiable across attempts.
- Attempt count survives process recreation when persistence is required.
- Retrying does not create duplicate History rows for one final result.
- A repeated failed strategy is blocked at its limit.
- Changed output settings require confirmation.
- Notifications and DB state reach one final consistent state.

### Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin -x lint
```

Manual checks:

- same-settings retry succeeds,
- same-settings retry fails at limit,
- user cancellation is not retried,
- process death between attempts,
- existing output conflict,
- History deduplication.

---

## FILE-01 — Copy paths and open file locations

**Status:** READY
**Priority:** P1
**Dependencies:** PRIV-01 recommended

### Goal

Help users locate outputs even when direct open or share actions fail.

### Scope

- Copy file path or URI.
- Copy parent location.
- Attempt to open a location using the best available URI.
- Fall back to path copy when no exact folder intent works.
- Support raw paths, MediaStore, and SAF where current permissions allow.

### Non-goals

- No promise that every file manager opens an exact folder.
- No new broad storage permission.
- No recursive directory scan.
- No copying large files into cache.

### Acceptance criteria

- Raw path and `content://` values are labeled correctly.
- Folder-open failure produces a useful fallback.
- Clipboard text does not unnecessarily expose a private full path.
- Multiple-file actions use a valid common parent only when one exists.
- Existing open and share behavior does not regress.

### Verification

```bash
./gradlew :app:compileDebugKotlin -x lint
```

Manual matrix:

- default download path,
- custom raw path,
- SAF document,
- SAF tree,
- MediaStore item,
- missing file,
- no compatible file manager.

---

## FILE-02 — Represent missing and inaccessible files

**Status:** READY
**Priority:** P1
**Dependencies:** FILE-01 recommended

### Goal

Distinguish missing files from inaccessible or unchecked files in History.

### States

```text
EXISTS
MISSING
PERMISSION_REQUIRED
UNKNOWN
CHECKING
```

### Scope

- Check visible items lazily.
- Add a low-frequency or user-triggered scan.
- Show state-specific actions.
- Keep scans off the main thread and cancellable.

### Non-goals

- No whole-library scan at application startup.
- No automatic deletion of missing History rows.
- No assumption that `File.exists()` works for every URI.
- No hash scan.

### Acceptance criteria

- A permission failure is not displayed as missing.
- Visible-page checks do not block scrolling.
- State refresh is available.
- Missing items can be redownloaded or removed from History.
- Scan work does not touch active download temporary files.

### Verification

```bash
./gradlew :app:compileDebugKotlin -x lint
```

Manual performance check with a large generated History dataset is required.

---

## FILE-03 — Add app-owned cache management

**Status:** READY
**Priority:** P2
**Dependencies:** FILE-02 recommended

### Goal

Show and safely remove app-owned cache and leftover files.

### MVP scope

- App cache.
- External app cache.
- App-owned download temp directory.
- Share cache.
- Terminal cache only when ownership is proven.
- Logs as a separate action.

### Non-goals

- No recursive deletion of arbitrary SAF trees.
- No deletion of user media outputs.
- No runtime-payload deletion in the MVP.
- No full-device storage analyzer.

### Acceptance criteria

- Every removable category resolves to a verified app-owned path.
- Active work is excluded.
- The UI shows estimated size and last scan time.
- Deletion requires confirmation with category and size.
- Failed deletions are reported without exposing private paths.
- A partial deletion result is represented accurately.

### Verification

```bash
./gradlew :app:compileDebugKotlin -x lint
```

Add focused tests around ownership boundaries where feasible.

---

## RUNTIME-01 — Add on-demand runtime diagnostics

**Status:** READY
**Priority:** P2
**Dependencies:** PRIV-01

### Goal

Diagnose runtime readiness without slowing normal startup.

### Probes

- yt-dlp version.
- Python/runtime availability.
- ffmpeg version.
- ffprobe version.
- aria2c version.
- QuickJS availability.
- cookie file presence only, not login validity.
- destination writability.
- available storage when resolvable.
- notification permission.
- battery-optimization state.

### Scope

- Run only on user request or before a required operation.
- Apply per-probe timeout.
- Report `OK`, `WARNING`, `ERROR`, or `UNKNOWN`.
- Redact paths and output.

### Non-goals

- No automatic login validation.
- No full download during a basic diagnostic.
- No startup probe of every runtime.
- No claim that binary existence proves functional support.

### Acceptance criteria

- A missing or non-executable runtime is detected.
- A hung probe times out.
- ABI is shown without claiming support.
- Results can be copied in redacted form.
- Normal app startup is unaffected.

### Verification

```bash
./gradlew :app:compileDebugKotlin -x lint
```

Manual checks require at least one healthy and one intentionally missing runtime case.

---

## PRESET-01 — Audit and unify configuration models

**Status:** READY
**Priority:** P2
**Dependencies:** none

### Goal

Determine whether download presets should reuse existing templates and preferences or require a new data model.

### Scope

Inspect:

- command templates,
- template shortcuts,
- Observe Source download templates,
- `DownloadItem` preferences,
- extra commands,
- quick-download defaults.

Produce a short ADR under `docs/` with:

- reusable structures,
- conflicting semantics,
- migration needs,
- proposed precedence,
- privacy implications,
- selected MVP model.

### Non-goals

- No preset UI.
- No Room migration.
- No feature implementation.
- No speculative refactor.

### Acceptance criteria

- The ADR names the exact current types and call paths.
- The decision explains why a new entity is or is not required.
- Precedence is explicit:
  1. direct user selection,
  2. extractor or site rule,
  3. global preset.
- Command safety policy remains enforceable.
- Quick Download behavior is covered.

### Verification

Documentation-only unless code is changed.

---

## PRESET-02 — Implement minimal download presets

**Status:** BLOCKED
**Priority:** P2
**Dependencies:** PRESET-01

### Goal

Allow users to save and apply a small reusable download configuration.

### MVP scope

- Create, rename, apply, and delete a preset.
- Set one global Quick Download preset.
- Show a concise configuration summary.
- Preserve current manual selection precedence.

### Non-goals

- No site rules in the MVP.
- No preset synchronization.
- No cloud storage.
- No arbitrary executable command injection.
- No large set of bundled presets.

### Acceptance criteria

- Applying a preset produces the same internal configuration as equivalent manual choices.
- Manual changes after preset application override the preset.
- Deleting a preset does not corrupt queued downloads.
- Backup behavior is defined.
- Migration is tested if a schema change is used.
- Unsafe options remain filtered.

### Verification

Task-specific after PRESET-01 selects the model.

---

## HIST-01 — Expand History filters after measurement

**Status:** LATER
**Priority:** P3
**Dependencies:** FILE-02; query measurement

### Entry condition

- Existing filters and DAO queries are documented.
- A representative large dataset exists.
- Slow queries are measured.

### Candidate additions

- file state,
- subtitle state,
- watch completion,
- output location,
- uploader,
- size range.

Do not add indexes without query-plan evidence.

---

## PLAYER-01 — Establish a single queue owner

**Status:** LATER
**Priority:** P3
**Dependencies:** queue regression checklist

### Goal

Extract queue state ownership before adding reorder, play-next, or advanced queue UI.

### Non-goals

- No UI feature expansion in the first change.
- No playback-engine replacement.

---

## TERM-01 — Add Terminal dry-run

**Status:** LATER
**Priority:** P3
**Dependencies:** PRIV-01; shared command representation

### Goal

Show the exact redacted sanitized argument/config representation that will be executed.

### Rule

Preview and execution must be generated from the same sanitized data structure. Do not maintain two parsers.
