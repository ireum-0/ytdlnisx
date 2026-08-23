# Correctness Remediation Ledger

This branch is a ledger-only record for correctness remediation. It is not a production-code branch and must not be merged into `checkpoint/pre-baseline-review`.

## Branch roles

- `checkpoint/pre-baseline-review`: authoritative production code, tests, build files, implementation commits, and review-fix commits.
- `ledger/remediation`: authoritative remediation status records, review evidence, review-method lessons, and exact pointers to governing source artifacts.

## Operating rule

Every ledger decision must point to the exact checkpoint commit SHA it reviewed. Ledger commits may record decisions already established by review and evidence, but must not introduce a new blocker-impacting classification, attribution decision, waiver, or other semantic finding decision without that decision first being established by the review itself.

Production correctness must always be verified against `checkpoint/pre-baseline-review@<SHA>` rather than against this branch's ancestry or tree. Scheduled review must never modify application source or documentation on the checkpoint branch.

## Current ledger snapshot

This branch was initialized from checkpoint HEAD `1bd62b05abfbdd0f8217c57d7a43d05647ae3467`. `TASKS.md` is the exact blob from that checkpoint state and records 73 active correctness defects at initialization. After initialization, `TASKS.md` is maintained on this ledger branch as the review defect/status registry; it must not be copied back to the checkpoint branch merely to synchronize documentation.

## Files

- `TASKS.md` — authoritative defect/status ledger used to deduplicate and record newly confirmed review findings.
- `REVIEW_LESSONS.md` — append-only meta-review log. Every newly confirmed defect records why earlier reviews missed it and proposes a generalizable Review Checklist improvement. Proposals do not automatically change the checklist.
- `REVIEW_CHECKLIST_V4_OPERATIONAL.md` — operational mirror used by scheduled review to apply Review Checklist v4 directly from GitHub, including mandatory terminal/cross-attempt matrices, candidate-rejection discipline, evidence hierarchy, and CLEAN gate.
- `SOURCE_ARTIFACTS.md` — exact names and SHA-256 identities of the governing Master Plan and original Review Checklist v4 supplied to the remediation project.
- `evidence/FINDING_A_REMAINING.md` — preserved Finding A review evidence from `review/finding-a-consolidation`; it remains historical/frozen evidence and is not silently reinterpreted here.

The original Review Checklist v4 remains the governing source artifact identified in `SOURCE_ARTIFACTS.md`. `REVIEW_CHECKLIST_V4_OPERATIONAL.md` is an operational mirror for automated execution and must not be silently weakened or treated as permission to relax the original checklist. Any semantic checklist revision requires separate review and an explicit decision.

This branch intentionally contains no Android production source, tests, Gradle files, assets, or other application files.
