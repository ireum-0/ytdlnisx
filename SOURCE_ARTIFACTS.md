# Governing Source Artifacts

The correctness-remediation project uses the following documents as governing references. Historical checklist identities are preserved so earlier reviews remain reproducible.

## Master Plan

- File: `YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md`
- Role: long-running correctness remediation master plan / session handoff, including F1→F22 ordering, model policy, verification rules, commit/push/review discipline, and ledger closure rules.
- SHA-256: `7f3a554a87eae50368edaf0a35f0fcb5d4b85aaea532bb818c90dbc45d90c5fa`

## Current Review Checklist — v6

- File: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- Role: governing semantic and execution-evidence review gate for reviews performed after v6 adoption. It preserves v5 requirements and generalizes contract-propagation review into a triggered `Semantic Contract Delta -> Consumer Closure -> Authority-Effect Closure` gate with final-checkpoint recount evidence.
- Adopted on `ledger/remediation` by explicit checklist-revision decision.
- Adoption commit: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- SHA-256: `61f4c1f9c278601a773058d28682ca36c8f5465e67445458608dace8d5ca8c95`

## Historical Review Checklist — v5

- File: `REVIEW_CHECKLIST_V5_OPERATIONAL.md`
- Role: governing semantic and execution-evidence review gate for reviews performed after v5 adoption and before v6 adoption, preserving v4 requirements while adding generalized live-authority, mutation-boundary, async-completion, recovery-discovery, shared-contract, identity/provenance, and sequential-batch review rules plus triggered conditional modules.
- Adoption commit: `e00600e846fe9967aa3619ba193ff509aced4e60`
- SHA-256: `654b7698737f861f8c7133788d6524cfcc6661b4ee714bdcf42d73ae35beabd6`

## Current Status Authority

- File: `CURRENT_STATUS.md`
- Role: current ledger status overlay applied on top of the historical `TASKS.md` baseline and append-only `TASKS_DELTA.md` finding records.
- Initial status-overlay commit: `67e5b971e3291e21337dffd2fba180f0addb212f`.
- Historical Finding-A semantic-clean checkpoint: `checkpoint/pre-baseline-review@648d2c8044e9d67f8a7367c54e3185f28206b636`.
- Historical Finding-A closure evidence: `evidence/FINDING_A_CLOSURE_2026-08-28_648D2C80.md`.
- Current Finding-A semantic-clean checkpoint recorded by the overlay: `checkpoint/pre-baseline-review@70437f76ede9cf0e69fb08d694dd3baf7bd8bfac`.
- Current Finding-A closure evidence: `evidence/FINDING_A_CLOSURE_2026-09-03_70437F76.md`.

`CURRENT_STATUS.md` is intentionally a mutable ledger-status index rather than a frozen governing artifact. It may record only status/closure decisions already established by exact review evidence; it must not originate new blocker-impacting classifications, attributions, waivers, or semantic findings.

At the Finding-A closure represented by the current overlay, `BUG-ADMISSION-01` is explicitly superseded from its historical Open finding record to **CLOSED**. No other baseline/post-split defect is closed or reclassified by that status overlay.

## Historical Review Checklist — v4

- File: `remediation-review-checklist-v4.md`
- Role: governing original for reviews performed before v5 adoption; retained as the historical semantic and execution-evidence baseline from which v5 was revised.
- SHA-256: `effdc6d6f8148d0eac031cbb8d9b93a12022c0df21638cc1dd8ac36712d539a2`
- GitHub mirror: `REVIEW_CHECKLIST_V4_OPERATIONAL.md`

## Revision / copy rule

The Master Plan and historical uploaded v4 artifact retain their recorded identities and must not be silently rewritten. If either uploaded source is copied into this branch, copy it byte-for-byte and verify the recorded SHA-256.

`REVIEW_CHECKLIST_V5_OPERATIONAL.md` and `REVIEW_CHECKLIST_V6_OPERATIONAL.md` are explicitly adopted semantic revisions, not byte-for-byte copies of their predecessors. Future semantic checklist changes require a separate review and explicit decision, must preserve historical checklist identities, and must not create, close, waive, reclassify, or reattribute production defects merely by changing review methodology.

Status updates are separate from checklist revisions. Current status must be derived from `CURRENT_STATUS.md` plus the exact cited review/closure evidence, while historical `TASKS.md`, `TASKS_DELTA.md`, and evidence snapshots remain preserved for auditability.
