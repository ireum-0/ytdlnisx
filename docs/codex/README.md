# Codex Improvement Plan

## Purpose

This directory is the operational improvement plan for YTDLnisX.

It is written for coding agents. It is intentionally terse, explicit, and task-oriented. It is not a product brochure and it is not authorization to implement every item at once.

## Authority order

When instructions conflict, use this order:

1. The user's latest explicit request.
2. The nearest applicable `AGENTS.md`.
3. The current source code and reproducible behavior.
4. This directory.
5. Older audit or planning documents.

Never treat a task in this plan as permission to expand the requested scope.

## Current baseline

- Repository: `ireum-0/ytdlnisx`
- Reconciliation date: 2026-07-30
- Application version: `1.8.9`
- Room database version: `53`

The task registry was reconciled against the current source tree on that date.
It deliberately avoids naming a permanent branch or commit because this
repository changes quickly.

The repository changes quickly. Before implementing a task:

1. Inspect the current branch and current code.
2. Revalidate every "existing" or "missing" claim in this plan.
3. Record the actual starting commit in the final response.
4. Prefer current behavior over stale documentation.

## Required reading order

For planned improvement work, read:

1. `AGENTS.md`
2. `docs/codex/README.md`
3. `docs/codex/PROJECT_STATE.md`
4. `docs/codex/RULES.md`
5. The selected task in `docs/codex/TASKS.md`
6. `docs/codex/CHECKS.md`

Read `docs/testing/release-checklist.md` only for release-candidate work.

## Task selection

- Use `TASKS.md` to determine whether an older item is already implemented,
  partial, or deferred.
- Use `../future-work.md` for the current recommendation, category, priority,
  and complexity.
- Implement one bounded concern per change unless the user explicitly requests
  a batch.
- Do not silently implement dependencies outside the requested scope.
- A task may be split into smaller changes when risk is high.
- Do not combine Room, WorkManager, Media3, and native-runtime changes in one
  change unless unavoidable.
- Documentation-only tasks do not authorize code changes.

## Definition of done

A task is complete only when:

- Scope and non-goals were respected.
- Current code paths were inspected, not guessed.
- Required tests were added or updated.
- The smallest relevant verification passed.
- Skipped verification is explicitly reported.
- No secrets or sensitive values are exposed.
- No unrelated files were changed.
- Remaining risks and manual checks are stated.

## File map

- `PROJECT_STATE.md`: concise current capabilities, risks, and unverified assumptions.
- `RULES.md`: implementation constraints and shared design rules.
- `TASKS.md`: reconciliation of the original task IDs with current code.
- `CHECKS.md`: verification commands and change-specific test matrices.
- `../future-work.md`: maintained recommendations and priorities.
- `../architecture/`: accepted architecture decisions; read the relevant ADR when a task depends on one.
- `../testing/release-checklist.md`: release-candidate checks, outside the normal task reading path.
- `../archive/`: historical audits, reviews, and prompts; never treat them as current guidance.
