# Hourly Correctness Review Checkpoint — final

## Fixed target
- implementation: `b16add5da1abd292515c8fadb07d251457daa41d`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review governance pinned at run bootstrap: `6fe78ebfcd362d7eaed55e1bf5ff835fbd2e9c2f`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- upstream semantic basis: yt-dlp `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Independent verdict
`NOT_CLEAN`

- P0: 0
- P1: 0
- P2: 1 existing/open
- state vs previous review: `no material change`

## Findings
No new canonical finding and no severity/state transition were confirmed.

Existing BUG-OUTPUT-01 Terminal P2 remains open. Current production semantics still permit:
1. exact publication succeeds;
2. journal reaches `COMMITTING`;
3. Terminal DAO row is deleted, establishing the durable semantic terminal fact used by recovery;
4. a later `COMMITTED` journal write, committed-root retirement, or journal clear fails;
5. ordinary worker catch executes and ultimately returns `Result.failure()`;
6. recovery treats the same `COMMITTING` + absent row + all exact destinations present as `semanticCommitKnown = true` and converges it as committed success.

This remains a v6 post-commit barrier / outer-catch final-result consistency violation. A committed semantic outcome is still reinterpretable as caller-visible failure solely because a later convergence side effect failed.

Affected files:
- `app/src/main/java/com/ireum/ytdl/work/TerminalDownloadWorker.kt`
- `app/src/main/java/com/ireum/ytdl/work/TerminalPublicationRecovery.kt`
- publication journal / Terminal ownership helpers transitively

Disposition: existing P2 OPEN; no new defect ID.

## Review retrospective
No new blind spot was identified. The existing lesson remains sufficient: recovery semantic identity and caller-visible WorkManager result must be audited together across every post-semantic-commit throwable point.

## Checklist evolution
No new checklist rule is required beyond the existing v6 execution order and rules 11-12. Operational recommendation remains to fault-inject every side effect after the semantic commit boundary and require the final scheduler result to remain consistent with the durable committed outcome.

## Confirmed fixed invariants
- exact publication recovery has a production reconciler
- recovery candidate construction supports exact journal/carrier state rather than directory membership
- recovery success requires semantic commit evidence, not destination existence alone
- marker-revoked recovery-only state remains distinct from ordinary cache-import authority
- committed-root retirement avoids recursive deletion of unknown descendants

## Evidence state
- independent JVM/emulator execution: `NOT_VERIFIED`
- GitHub combined status contexts for exact implementation SHA: none

## Final recount
`checkpoint/pre-baseline-review` still resolves to `b16add5da1abd292515c8fadb07d251457daa41d`; no implementation drift occurred during this pinned run.
