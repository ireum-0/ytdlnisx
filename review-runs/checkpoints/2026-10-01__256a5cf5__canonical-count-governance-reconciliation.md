# Canonical count governance reconciliation — 2026-10-01

implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
review_parent_sha: 11518985a905e3dc3cc340ec4041e5824afcfe8b
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
governing_protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933

## Reconciliation

The immediately preceding checkpoint
`review-runs/checkpoints/2026-10-01__256a5cf5__canonical-current-domain-count-correction.md`
incorrectly redefined canonical count semantics from active-remediation inventory counts to broad
current-domain counts.

That reinterpretation conflicts with REVIEW_PROTOCOL.md section 10 and is superseded by this
append-only reconciliation.

Governing semantics restored:

- CANONICAL_COUNT_SEMANTICS=ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
- ACTIVE_REMEDIATION_SCOPE_ID=DOWNLOAD_CORRECTNESS_REMEDIATION
- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=1

Current adopted active-remediation open root:
- BUG-UPDATER-02 — OPEN P2

Known unresolved repository findings not currently adopted into the active remediation inventory:
- BUG-UPDATER-03 — P2 pre-existing baseline defect
- BUG-HISTORY-05 — P2 pre-existing baseline defect

Per protocol section 10, those out-of-inventory findings do not increment CANONICAL_P* until an
explicit canonical scope/inventory expansion adopts them.

The active implementation prompt remains narrowly scoped to BUG-UPDATER-02.

If a broader current-domain count is desired in future, it must be carried as a separately named
metric or established by an explicit scope/inventory expansion. It must not silently redefine
CANONICAL_P*.

No production source, test, configuration, ledger, prompt, or governance protocol file was changed.
