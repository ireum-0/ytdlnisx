# YTDLnisX Documentation

This directory contains the current engineering documentation for the repository.

## Reviewed source snapshot

The current documents were reconciled against:

- branch: `main`
- code snapshot: `41d0acf89735c28a07f34cb565bd54e66cd9b6d0`
- review date: 2026-08-19
- app version in source: `1.8.9`
- Room database version: `52`
- Android SDK levels: min 24, target/compile 36

The documentation commit that updates these files is intentionally newer than the code snapshot above. Revalidate these facts when product code changes.

## Where to start

- [`codex/README.md`](codex/README.md): current working guide and task-selection rules.
- [`codex/PROJECT_STATE.md`](codex/PROJECT_STATE.md): current implemented capabilities, risk areas, and known correctness findings.
- [`codex/TASKS.md`](codex/TASKS.md): active bug-fix and backlog registry.
- [`codex/CHECKS.md`](codex/CHECKS.md): current verification tiers and required regressions.
- [`architecture/README.md`](architecture/README.md): current architecture inventory and ADR index.
- [`testing/README.md`](testing/README.md): testing entry point and release checklist.
- [`releases/README.md`](releases/README.md): release-note index.
- [`archive/README.md`](archive/README.md): historical audits, reviews, and prompts.

## Authority

Source code, exported Room schemas, Gradle configuration, and workflow files are authoritative for implemented behavior. Current-state documentation must be updated when those surfaces change. Files under `archive/` and dated release notes are historical snapshots and must not be silently rewritten to describe current behavior.

Known correctness findings discovered during the 2026-08-19 review are tracked in [`codex/PROJECT_STATE.md`](codex/PROJECT_STATE.md) and as READY tasks in [`codex/TASKS.md`](codex/TASKS.md).
