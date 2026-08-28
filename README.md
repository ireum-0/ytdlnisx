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

Do not rely on a defect count copied into this README. Fresh-read both registry files when an exact current count or status is needed.

## Files

- `TASKS.md` — synchronized baseline defect/status registry from the pinned checkpoint.
- `TASKS_DELTA.md` — append-only post-ledger-split active findings confirmed against exact checkpoint SHAs.
- `REVIEW_LESSONS.md` — append-only meta-review log. Every newly confirmed defect records why earlier reviews missed it and proposes a generalizable Review Checklist improvement. Proposals do not automatically change the checklist; v5 was adopted through a separate explicit revision decision.
- `REVIEW_CHECKLIST_V5_OPERATIONAL.md` — current governing operational checklist. It preserves v4's mandatory semantic/evidence gates and adds generalized positive-live-authority, mutation-boundary revalidation, async-completion, recovery-discovery, strengthened-contract, identity/provenance, and sequential-batch rules plus triggered conditional modules.
- `REVIEW_CHECKLIST_V4_OPERATIONAL.md` — preserved historical operational mirror for reproducing reviews performed under Review Checklist v4.
- `SOURCE_ARTIFACTS.md` — exact governing/historical checklist identities and the Master Plan identity, including the v5 adoption commit and SHA-256.
- `evidence/FINDING_A_REMAINING.md` — preserved Finding A review evidence from `review/finding-a-consolidation`; it remains historical/frozen evidence and is not silently reinterpreted here.
- `evidence/CHECKPOINT_REVIEW_2026-08-23_DFA40697.md` — review evidence for the pinned checkpoint run that established `BUG-SCHEDULER-05` and revalidated selected historical Finding A blockers.
- `evidence/FINDING_A_REREVIEW_2026-08-24_AD1A8F02.md` — independent full-scope Finding A re-review at `checkpoint/pre-baseline-review@ad1a8f026a7a05f3e1489775a74d8106dbfa510e`; verdict `NOT CLEAN`, with residual A2, A8, and A9/A12 blocker groups recorded without creating new defect IDs.

`REVIEW_CHECKLIST_V5_OPERATIONAL.md` governs reviews after its adoption commit. The original uploaded v4 checklist and `REVIEW_CHECKLIST_V4_OPERATIONAL.md` remain preserved for historical reproducibility. Checklist methodology changes do not themselves create, close, waive, reclassify, or reattribute production defects.

This branch intentionally contains no Android production source, tests, Gradle files, assets, or other application files.
