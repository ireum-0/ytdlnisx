# Hourly Correctness Review Final Checkpoint

## Fixed target
- Implementation: `d07e90b98af48496bb20b5ab5b6e35136703b0dd`
- Parent: `a454bdb44fe318dda6072ed642b998b5535becfd`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Review at run start: `c4516dd43f2812022b92dcf8993f3aeabd8e43ab`
- Ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- yt-dlp semantic basis retained for command semantics: `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Independent verdict
`NOT_CLEAN`

- P0: 0
- P1: 0
- P2: 1 open in the existing BUG-OUTPUT-01 / Terminal publication-recovery domain

## Findings
The prior marker-revoked partial-publication discovery gap is materially improved. Terminal failure cleanup can persist an exact recovery-only carrier before removing the live marker, `TerminalCacheOwnership.listRecoveryRoots()` validates and enumerates it, and ordinary cache import does not treat it as live publication authority.

The remediation does not yet close semantic recovery:

1. `App` calls `TerminalPublicationRecovery.reconcile()` and `CacheImportPlanner.collectRecovery()`, but the production action shown is only logging counts. The new recovery carrier is discoverable, yet no production consumer in this remediation converges the Terminal durable result, resumes exact remaining publication, or retires the debt after a verified semantic outcome.

2. There is a separate post-publication/pre-semantic-commit window. After exact publication succeeds, `TerminalDownloadWorker` removes the artifact manifest, owner marker, and staging directory before later log/Terminal DAO completion and before clearing the durable publication journal. Process death or another failure in this window can leave only a COMPLETE app-private `PublicationRecoveryJournal` as exact evidence. `TerminalPublicationRecovery.reconcile()` rejects that record because it requires `sourceRoot` to still be a directory with a valid live Terminal marker. Thus recovery is not discoverable from every surviving durable carrier and the committed publication can be reinterpreted by later retry/re-entry instead of converging from the journal.

This remains within the existing BUG-OUTPUT-01 correctness domain rather than creating a new canonical defect ID.

## Review retrospective
The previous review correctly focused on the candidate-construction bug after marker revocation, but stopped at `discoverable carrier exists`. v6 requires one more proof step: carrier discovery must lead to a semantic recovery owner, and the carrier-loss matrix must include the opposite crash window where the staging carrier disappears first while the independent publication journal survives.

## Checklist evolution
No new v6 core invariant is required, but the operational execution should explicitly require a two-dimensional recovery matrix for publication work:

- staging survives / semantic ledger survives;
- staging survives / semantic ledger lost;
- staging lost / publication journal survives;
- marker revoked / recovery carrier survives;
- publication COMPLETE / semantic terminal commit absent.

For every cell, identify an actual production consumer that converges or retires the debt. Logging or enumeration alone must not count as recovery completion.

## Confirmed non-regressions in reviewed domain
- publication reservation is persisted before irreversible destination creation;
- exact source->destination publication lineage is journaled;
- marker-revoked partial remainder is separated from ordinary cache-import authority;
- current implementation HEAD remained `d07e90b98af48496bb20b5ab5b6e35136703b0dd` at final recount.

## Evidence
- GitHub combined status for exact SHA reports no individual status contexts (`total_count=0`); independent JVM/emulator execution is `NOT_VERIFIED`.
- Source semantics are sufficient to keep the P2 open; tests are supporting evidence only and were not substituted for production-path review.
