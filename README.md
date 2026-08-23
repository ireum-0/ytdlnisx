# Correctness Remediation Ledger

This branch is a ledger-only record for correctness remediation. It is not a production-code branch and must not be merged into `checkpoint/pre-baseline-review`.

## Branch roles

- `checkpoint/pre-baseline-review`: authoritative production code, tests, build files, implementation commits, and review-fix commits.
- `ledger/remediation`: authoritative remediation status, plans, review criteria, and evidence pointers.

## Operating rule

Every ledger decision must point to the exact checkpoint commit SHA it reviewed. Ledger commits may record decisions already established by review and evidence, but must not introduce a new blocker-impacting classification, attribution decision, waiver, or other semantic finding decision.

Production correctness must always be verified against `checkpoint/pre-baseline-review@<SHA>` rather than against this branch's ancestry or tree.

## Files

- `TASKS.md` — current defect/status ledger copied from the latest checkpoint state when this branch was created.
- `REMEDIATION_MASTER_PLAN.md` — long-running F1→F22 remediation contract and handoff.
- `REVIEW_CHECKLIST_V4.md` — mandatory review/CLEAN gate.

This branch intentionally contains no Android production source, tests, Gradle files, assets, or other application files.
