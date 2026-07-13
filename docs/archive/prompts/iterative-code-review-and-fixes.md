# Iterative Code Review and Fixes

> Status: Archived
> Snapshot date: 2026-06-24
> Revalidate all instructions against the current repository before using them.

Review, fix, verify, and re-review all current uncommitted changes until no important issues remain. Do not limit the number of iterations; continue until every exit condition is met.

## Rules

- Read and follow the root and relevant nested `AGENTS.md` files first.
- Preserve existing user changes. Do not modify or revert unrelated files.
- Review staged and unstaged changes in `git diff HEAD`, plus required new files.
- Also inspect affected call paths, models, migrations, and resources.
- Do not expand into unrelated existing issues or broad refactors.
- Validate every review finding against the actual code and reproducible behavior. Fix only valid issues.
- Do not add dependencies, network access, build tools, or project configuration without approval.
- Do not read, print, or modify files that may contain secrets or signing data.
- Do not commit or push.

## Review Method

For each iteration:

1. Prefer the environment's dedicated review tool or `/review`.
2. If a CLI is required, run `codex review --uncommitted`.
3. If those options are unavailable or fail, perform the review directly.
4. For a direct review, reread the entire `git diff HEAD` and affected call paths. Report only reproducible correctness, regression, or security issues as `P0`, `P1`, or `P2`.
5. Record a direct review as `direct review` in the iteration log so it is distinguishable from an independent review.

Treat `P0`, `P1`, and `P2` findings as important. Do not automatically fix `P3`, style-only, preference-based, or out-of-scope existing issues.

## Iteration Log

- Initialize `build/reports/codex-review-loop.md` when starting.
- This path is Git-ignored and must not become a product change or review target.
- Append a short entry after each iteration:

```md
## Iteration N
- Review: method and P0/P1/P2 counts
- Assessment: valid and rejected findings with brief reasons
- Fixes: changed files and key changes
- Verification: commands and results
- Next: re-review, completed, or stopped
```

- Do not include full diffs, full build logs, stack traces, or sensitive values.
- For failures, record only the final lines needed to explain the cause.
- If logging fails, do not work around it by writing elsewhere in the repository. Report the failure in the final response.

## Loop

1. Inspect `git status --short`, `git diff HEAD`, and new files.
2. Run an independent review; fall back to a direct review if necessary.
3. Validate each `P0`/`P1`/`P2` finding against the code and call paths.
4. Fix valid findings with minimal changes that preserve existing structure and style.
5. Confirm that required new sources and Room schemas are not omitted as untracked files.
6. Run the basic verification commands below.
7. If verification fails, diagnose and fix causes introduced by the current changes.
8. Re-review the updated full diff, directly if an independent review cannot run.
9. Log the iteration.
10. Stop only when no valid `P0`/`P1`/`P2` findings remain and verification passes.

## Basic Verification

```powershell
git diff HEAD --check
./gradlew :app:compileDebugKotlin -x lint
```

- For Markdown-only changes, the Gradle command may be skipped; report this in the final response.
- Do not automatically run full/release builds, all unit tests, APK installation, connected tests, or network-dependent tasks.
- If heavier verification is necessary, stop and report the required command and reason.
- Do not impose arbitrary short timeouts on verification commands.

## High-Risk Android Checks

When affected, statically check:

- Room: entities, DAOs, `DBManager` version, every upgrade migration path, and exported schemas.
- WorkManager: unique-work policy, duplicate execution, lost requests, retry/cancel behavior, constraints, foreground behavior, and background limits.
- Media3/ExoPlayer: playback state and position, actual timeline versus displayed list, PiP, media session, URIs, and subtitles.
- Downloads/yt-dlp/FFmpeg: ABI and packaging impact, cancellation, retries, cleanup, container compatibility, and user-visible errors.
- Manifest/Gradle: impact scope and verification method; include both in the final response.
- New Android components: manifest registration and inclusion of their sources in the clean-checkout patch.

## Stop Conditions

Stop and report the reason if:

- The same issue recurs after a fix.
- A safe fix is unclear or requires a user decision.
- A fix requires new authority, external state changes, or broader scope.
- Basic verification cannot run.

## Exit Conditions

Normal completion requires all of the following:

- The final independent or direct re-review finds no valid `P0`/`P1`/`P2` issues.
- `git diff HEAD --check` passes.
- If code changed, `./gradlew :app:compileDebugKotlin -x lint` passes.
- Only `P3`, out-of-scope existing issues, or items requiring human judgment or separate approval remain.

## Final Response

Briefly report:

- Total review iterations.
- Findings and fixes or rejection reasons for each iteration.
- Changed files.
- Verification commands and results.
- Skipped verification and reasons.
- Remaining risks and required human checks.
- Iteration log path.
