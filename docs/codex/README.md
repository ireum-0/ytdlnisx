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

## Baseline

- Repository: `ireum-0/ytdlnisx`
- Baseline date: 2026-07-13
- Baseline branch: `main`
- Baseline commit used for this plan: `1a0a7fdbe419047262a1a552be927b5af9799bd0`
- Application version at baseline: `1.8.9`
- Room database version at baseline: `50`

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

- Implement one task ID per change unless the user explicitly requests a batch.
- Prefer the first `READY` task that matches the user's request.
- Do not start a `BLOCKED` task.
- Do not silently implement dependencies outside the requested scope.
- A task may be split into smaller changes when risk is high.
- Do not combine Room, WorkManager, Media3, and native-runtime changes in one change unless unavoidable.
- Documentation-only tasks do not authorize code changes.

## Active execution order

The recommended order is:

1. `PRIV-01` — redact normal download diagnostics
2. `QG-01` — add pull-request compile and unit-test checks
3. `QG-02` — harden the existing release workflow
4. `DB-01` — expand representative migration tests
5. `FAIL-01` — introduce outcome and issue types
6. `FAIL-02` — classify a small set of high-confidence failures
7. `FAIL-03` — show structured failure information and safe actions
8. `RETRY-01` — add user-initiated safe retry
9. `FILE-01` — copy paths and open locations with fallbacks
10. `FILE-02` — represent missing and inaccessible files
11. `FILE-03` — add app-owned cache management
12. `RUNTIME-01` — add on-demand runtime diagnostics
13. `PRESET-01` — audit and unify existing configuration models
14. `PRESET-02` — implement a minimal preset feature

Player, advanced History, Observe Sources, and Terminal expansion remain later work.

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

- `PROJECT_STATE.md`: current capabilities, known risks, and unverified assumptions.
- `RULES.md`: implementation constraints and shared design rules.
- `TASKS.md`: ordered task registry with acceptance criteria.
- `CHECKS.md`: verification commands and change-specific test matrices.
- `../architecture/`: accepted architecture decisions; read the relevant ADR when a task depends on one.
- `../testing/release-checklist.md`: release-candidate checks, outside the normal task reading path.
- `../archive/`: historical audits, reviews, and prompts; never treat them as current guidance.
