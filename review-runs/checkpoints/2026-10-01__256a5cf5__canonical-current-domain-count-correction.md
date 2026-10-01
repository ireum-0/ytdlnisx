# Canonical current-domain open-count correction — 2026-10-01

implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
review_parent_sha: 25c9d571704031271b04852a963b2d9c514d9812
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION

## Corrected canonical count semantics

canonical_count_semantics: CURRENT_REMEDIATION_DOMAIN_OPEN_ROOTS

"Canonical" is the count of unresolved correctness roots in the remediation domain currently being worked,
not merely the single root in the currently active implementation prompt and not the repository-wide project total.

For the current DOWNLOAD_CORRECTNESS_REMEDIATION domain, the known unresolved P2 roots are:

1. BUG-UPDATER-02 — OPEN P2 same-root backup/restore runtime provenance and startup/deferred recovery residual.
2. BUG-UPDATER-03 — OPEN P2 pre-existing blank portable ytdlp_source restore/updater contract defect.
3. BUG-HISTORY-05 — OPEN P2 pre-existing valid-dedupe playlist-membership loss.

Therefore the corrected canonical current-domain counts are:

- CANONICAL_P0=0
- CANONICAL_P1=0
- CANONICAL_P2=3

The active implementation prompt may still target only BUG-UPDATER-02. Implementation scope and canonical
current-domain count are separate concepts.

This correction supersedes prior count-only statements that used ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY and
therefore reported P2=1. It does not rewrite or invalidate the underlying semantic findings in earlier checkpoints.

Repository-wide total open correctness defects are NOT counted by this checkpoint and are not claimed to equal 3.

No production source, test, configuration, ledger or governance implementation semantics were changed.
