# Governing Source Artifacts

The correctness-remediation project currently uses the following source documents as governing references. Their contents are not replaced by summaries in this ledger branch.

## Master Plan

- File: `YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md`
- Role: long-running correctness remediation master plan / session handoff, including F1→F22 ordering, model policy, verification rules, commit/push/review discipline, and ledger closure rules.
- SHA-256: `7f3a554a87eae50368edaf0a35f0fcb5d4b85aaea532bb818c90dbc45d90c5fa`

## Review Checklist

- File: `remediation-review-checklist-v4.md`
- Role: mandatory semantic and execution-evidence review gate, including cross-attempt preservation, candidate-rejection discipline, terminal-fault matrix, and CLEAN criteria.
- SHA-256: `effdc6d6f8148d0eac031cbb8d9b93a12022c0df21638cc1dd8ac36712d539a2`

## Copy rule

If either source document is later added to this branch, copy it byte-for-byte from the authoritative supplied artifact and verify the resulting file against the SHA-256 above. Do not silently reconcile, modernize, summarize, or edit the source while copying it. Any later semantic revision must be a separately reviewed decision and must preserve historical SHAs and attribution.
