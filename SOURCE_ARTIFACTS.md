# Governing Source Artifacts

The correctness-remediation project uses the following documents as governing references. Historical checklist identities are preserved so earlier reviews remain reproducible.

## Master Plan

- File: `YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md`
- Role: long-running correctness remediation master plan / session handoff, including F1→F22 ordering, model policy, verification rules, commit/push/review discipline, and ledger closure rules.
- SHA-256: `7f3a554a87eae50368edaf0a35f0fcb5d4b85aaea532bb818c90dbc45d90c5fa`

## Current Review Checklist — v5

- File: `REVIEW_CHECKLIST_V5_OPERATIONAL.md`
- Role: governing semantic and execution-evidence review gate for reviews performed after v5 adoption, preserving v4 requirements while adding generalized live-authority, mutation-boundary, async-completion, recovery-discovery, shared-contract, identity/provenance, and sequential-batch review rules plus triggered conditional modules.
- Adopted on `ledger/remediation` by explicit checklist-revision decision.
- Adoption commit: `e00600e846fe9967aa3619ba193ff509aced4e60`
- SHA-256: `654b7698737f861f8c7133788d6524cfcc6661b4ee714bdcf42d73ae35beabd6`

## Historical Review Checklist — v4

- File: `remediation-review-checklist-v4.md`
- Role: governing original for reviews performed before v5 adoption; retained as the historical semantic and execution-evidence baseline from which v5 was revised.
- SHA-256: `effdc6d6f8148d0eac031cbb8d9b93a12022c0df21638cc1dd8ac36712d539a2`
- GitHub mirror: `REVIEW_CHECKLIST_V4_OPERATIONAL.md`

## Revision / copy rule

The Master Plan and historical uploaded v4 artifact retain their recorded identities and must not be silently rewritten. If either uploaded source is copied into this branch, copy it byte-for-byte and verify the recorded SHA-256.

`REVIEW_CHECKLIST_V5_OPERATIONAL.md` is an explicitly adopted semantic revision, not a byte-for-byte copy of v4. Future semantic checklist changes require a separate review and explicit decision, must preserve historical checklist identities, and must not create, close, waive, reclassify, or reattribute production defects merely by changing review methodology.
