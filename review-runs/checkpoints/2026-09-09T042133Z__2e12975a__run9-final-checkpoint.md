# Hourly correctness review checkpoint — run 9 (final)

## Pinned authorities
- Implementation: `checkpoint/pre-baseline-review` @ `2e12975a4b044d52986ec9ac0f3204f6e6bdc326`
- Plan: `plan/remediation` @ `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Review bootstrap: `review/remediation` @ `49f9d5387997ad6c691b8edbaee530075ac6b9df`
- Ledger: `ledger/remediation` @ `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- Master Plan manifest SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

## Final verdict
- NOT_CLEAN
- P0: 0
- P1: 0
- P2: 1 existing (`BUG-OUTPUT-01`), OPEN
- New canonical findings: 0

## Completed review scope
- Fresh exact-SHA bootstrap of implementation, plan, review, and ledger branches.
- v6 governing invariants and Master Plan authority manifest.
- Parent-to-current semantic delta (`ee470570...` -> `2e12975a...`).
- `FileUtil` MediaStore and SAF provider creation, exact reservation persistence, rollback, fallback fencing, publication finalization, and cleanup paths.
- `ProviderPublicationOutcome` state model and tests.
- Download publication-journal callbacks and UNKNOWN terminalization/startup convergence code.
- Terminal publication journal callbacks and Terminal admission/reconciliation of UNKNOWN provider debt.
- `DownloadIssueClassifier` suggested-action mapping for `PUBLICATION_OUTCOME_UNKNOWN`.
- `DownloadViewModel` reconfigure retry admission.
- Final implementation HEAD recount and GitHub combined-status check.

## Existing P2 retained
The current commit correctly closes an important subcase: after a provider create returned an exact URI but durable reservation persistence failed, fallback is permitted only when deletion of that exact provider object and durable clearing of its reservation are both positively acknowledged. Missing either proof keeps provider publication fenced.

However, a genuinely opaque provider outcome remains non-convergent. When create/insert completion is UNKNOWN and no exact URI is durable, startup/recovery can terminalize and quarantine the journal, and Terminal admission can return terminal failure rather than replaying native work. This is a safety fence, not a resolution owner for the external side effect.

`DownloadIssueClassifier` still maps `PUBLICATION_OUTCOME_UNKNOWN` to `RECONFIGURE`. `DownloadViewModel.queueDownloads()` still processes Error items via `DownloadRetryStrategy.RECONFIGURED`. No reviewed production consumer provides exact external-object discovery, deterministic rollback, provider idempotency reconciliation, or an explicit authority revocation/transfer protocol for the old UNKNOWN operation. Therefore reconfigure does not discharge the old UNKNOWN authority; it can only encounter the retained fence again.

This remains an incomplete remediation of BUG-OUTPUT-01 under v6 invariants 6, 10, 11, 14, 16, and 18: recovery semantic identity, discoverability, full consumer/authority-effect closure, retry/reconfigure closure, and CLEAN semantic closure.

## Review retrospective
The changed implementation correctly strengthened the post-create exact-URI rollback frontier, but it did not change the downstream semantic contract of `PUBLICATION_OUTCOME_UNKNOWN`. The structural blind spot would be treating a stronger fence as convergence. The full consumer graph still contains a user-facing `RECONFIGURE` action that has no production authority-transfer or debt-resolution semantics for an opaque provider result.

## Checklist evolution
No new core checklist rule is required. Refine the existing external-UNKNOWN row: when an UNKNOWN state is terminalized/quarantined, audit every suggested action and re-entry path. A user-facing retry/reconfigure action counts as resolution only if it either (a) resolves the old external effect to an exact identity, (b) deterministically rolls it back, or (c) durably revokes/transfers the old authority before a new operation is admitted. Re-entering the same durable fence is not convergence.

## Evidence
- Source-semantic review: PASS for the exact-URI rollback/fallback fencing subcase.
- UNKNOWN end-to-end convergence: FAIL (existing P2 remains).
- GitHub combined status for exact implementation SHA: no status contexts (`total_count=0`); independent JVM/emulator/device execution remains NOT_VERIFIED.
- Final implementation recount: unchanged at `2e12975a4b044d52986ec9ac0f3204f6e6bdc326`.
