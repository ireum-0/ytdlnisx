# Correctness Remediation Ledger

This branch is a ledger-only record for correctness remediation. It is not a production-code branch and must not be merged into `checkpoint/pre-baseline-review`.

## Branch roles

- `checkpoint/pre-baseline-review`: authoritative production code, tests, build files, implementation commits, and review-fix commits.
- `ledger/remediation`: authoritative remediation status records, review evidence, and exact pointers to governing source artifacts.

## Operating rule

Every ledger decision must point to the exact checkpoint commit SHA it reviewed. Ledger commits may record decisions already established by review and evidence, but must not introduce a new blocker-impacting classification, attribution decision, waiver, or other semantic finding decision.

Production correctness must always be verified against `checkpoint/pre-baseline-review@<SHA>` rather than against this branch's ancestry or tree.

## Current ledger snapshot

This branch was initialized from checkpoint HEAD `1bd62b05abfbdd0f8217c57d7a43d05647ae3467`. `TASKS.md` is the exact blob from that checkpoint state and records 73 active correctness defects at initialization.

## Files

- `TASKS.md` — authoritative defect/status ledger snapshot from the checkpoint branch.
- `SOURCE_ARTIFACTS.md` — exact names and SHA-256 identities of the governing Master Plan and Review Checklist v4 supplied to the remediation project.
- `evidence/FINDING_A_REMAINING.md` — preserved Finding A review evidence from `review/finding-a-consolidation`; it remains historical/frozen evidence and is not silently reinterpreted here.

The large Master Plan and Review Checklist v4 are not rewritten or summarized into replacement documents on this branch. Their exact source identities are recorded in `SOURCE_ARTIFACTS.md`; if they are later copied into GitHub, they should be copied byte-for-byte and verified against those hashes.

This branch intentionally contains no Android production source, tests, Gradle files, assets, or other application files.
