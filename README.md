# Correctness Remediation Ledger

This branch is a ledger-only record for correctness remediation. It is not a production-code branch and must not be merged into `checkpoint/pre-baseline-review`.

## Branch roles

- `checkpoint/pre-baseline-review`: authoritative production code, tests, build files, implementation commits, and review-fix commits.
- `ledger/remediation`: authoritative remediation status records, review evidence, review-method lessons, and exact pointers to governing source artifacts.

## Operating rule

Every ledger decision must point to the exact checkpoint commit SHA it reviewed. Ledger commits may record decisions already established by review and evidence, but must not introduce a new blocker-impacting classification, attribution decision, waiver, or other semantic finding decision without that decision first being established by the review itself.

Production correctness must always be verified against `checkpoint/pre-baseline-review@<SHA>` rather than against this branch's ancestry or tree. Scheduled review must never modify application source or documentation on the checkpoint branch.

## Registry model

`TASKS.md` is the synchronized baseline registry copied byte-for-byte from a reviewed checkpoint state. After the ledger split, newly confirmed review findings are appended to `TASKS_DELTA.md` instead of rewriting the large baseline file on every review run.

The effective active-defect registry is therefore:

`TASKS.md` baseline + `TASKS_DELTA.md` post-split findings.

At the current ledger state, `TASKS.md` is synchronized from `checkpoint/pre-baseline-review@dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15` and contains 74 active defects. `TASKS_DELTA.md` contains 12 additional active defects, for an effective total of 86.

## Files

- `TASKS.md` — synchronized baseline defect/status registry from the pinned checkpoint.
- `TASKS_DELTA.md` — append-only post-ledger-split active findings confirmed against exact checkpoint SHAs.
- `REVIEW_LESSONS.md` — append-only meta-review log. Every newly confirmed defect records why earlier reviews missed it and proposes a generalizable Review Checklist improvement. Proposals do not automatically change the checklist.
- `REVIEW_CHECKLIST_V4_OPERATIONAL.md` — operational mirror used by scheduled review to apply Review Checklist v4 directly from GitHub, including mandatory terminal/cross-attempt matrices, candidate-rejection discipline, evidence hierarchy, and CLEAN gate.
- `SOURCE_ARTIFACTS.md` — exact names and SHA-256 identities of the governing Master Plan and original Review Checklist v4 supplied to the remediation project.
- `evidence/FINDING_A_REMAINING.md` — preserved Finding A review evidence from `review/finding-a-consolidation`; it remains historical/frozen evidence and is not silently reinterpreted here.
- `evidence/CHECKPOINT_REVIEW_2026-08-23_DFA40697.md` — review evidence for the pinned checkpoint run that established `BUG-SCHEDULER-05` and revalidated selected historical Finding A blockers.
- `evidence/FINDING_A_REREVIEW_2026-08-24_AD1A8F02.md` — independent full-scope Finding A re-review at `checkpoint/pre-baseline-review@ad1a8f026a7a05f3e1489775a74d8106dbfa510e`; verdict `NOT CLEAN`, with residual A2, A8, and A9/A12 blocker groups recorded without creating new defect IDs.

The original Review Checklist v4 remains the governing source artifact identified in `SOURCE_ARTIFACTS.md`. `REVIEW_CHECKLIST_V4_OPERATIONAL.md` is an operational mirror for automated execution and must not be silently weakened or treated as permission to relax the original checklist. Any semantic checklist revision requires separate review and an explicit decision.

This branch intentionally contains no Android production source, tests, Gradle files, assets, or other application files.
