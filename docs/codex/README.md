# Current Working Guide

Use this directory for planned engineering work. Revalidate every selected task against the current branch before editing.

## Reviewed baseline

- review date: 2026-08-19
- branch: `main`
- reviewed code commit: `41d0acf89735c28a07f34cb565bd54e66cd9b6d0`
- source app version: `1.8.9`
- Room database version: `52`

This replaces the previous 2026-07-13 / `1a0a7fd` / Room 50 planning baseline.

## Read order

1. repository `AGENTS.md`;
2. [`PROJECT_STATE.md`](PROJECT_STATE.md) for current implementation and known correctness findings;
3. one selected entry in [`TASKS.md`](TASKS.md);
4. [`RULES.md`](RULES.md) for durable correctness constraints;
5. [`CHECKS.md`](CHECKS.md) for verification required by the selected change.

Do not treat archived audits or old release notes as current implementation truth.

## Current implementation milestones

The following work that used to be listed as future work is present in current source and is marked DONE in the task registry after source revalidation:

- normal-download diagnostic redaction;
- pull-request compile/unit-test workflow and hardened main workflow;
- representative Room migration tests through version 52;
- structured download outcomes, issue classification, failure UI, and bounded retry;
- file-location copy/open fallback and app-owned cache management;
- user-triggered runtime diagnostics;
- download preset ADR and MVP implementation;
- playback queue state extraction;
- Terminal dry-run/preview based on the same sanitized command plan used for execution.

`FILE-02` remains unfinished: History does not yet have the planned explicit lazy file-state model (`EXISTS`, `MISSING`, `PERMISSION_REQUIRED`, `UNKNOWN`, `CHECKING`).

## Active correctness order

Unless a user request requires a different scope, address correctness findings before expanding features:

1. `BUG-BACKUP-01` — remap History replacement markers during backup restore and verify the target before destructive replacement.
2. `BUG-KEYWORD-01` — do not complete an automatic-keyword baseline from a merely empty/incomplete extraction result.
3. `BUG-KEYWORD-02` — do not restore stale derived RULE assignments after a rule revision/condition change.
4. `BUG-METADATA-01` — prevent metadata refresh from overwriting concurrent download-row edits.
5. `BUG-HARDSUB-01` — distinguish subtitle lookup failure from a verified no-subtitle result.
6. `FILE-02` and measured backlog work.

Do not batch unrelated fixes into one branch merely because they are listed here.

## CI reality

The repository contains PR and main-branch GitHub Actions workflows. As of the reviewed snapshot, the `main` branch itself is **not protected** and does not have required status checks configured at the repository-settings level. Workflow presence is therefore not the same as merge enforcement.
