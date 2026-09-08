# Hourly Correctness Review Checkpoint — run08 mid

## Fixed target
- implementation: `b16add5da1abd292515c8fadb07d251457daa41d`
- plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review governance: `77d26fb4ea6093e5a9572f2a65abfd8e9cd4099d`
- ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md` (governing v6 identity recorded in `SOURCE_ARTIFACTS.md`, SHA-256 `61f4c1f9c278601a773058d28682ca36c8f5465e67445458608dace8d5ca8c95`)
- Master Plan identity: SHA-256 `7f3a554a87eae50368edaf0a35f0fcb5d4b85aaea532bb818c90dbc45d90c5fa`
- upstream semantic basis retained from current review lineage: yt-dlp `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Reviewed scope so far
- fresh branch-head pinning for implementation / plan / review / ledger
- v6 governance identity and Master Plan authority
- current `TerminalDownloadWorker` publication -> COMMITTING -> DAO deletion -> COMMITTED -> staging retirement -> journal clear -> outer catch/result path
- current `TerminalPublicationRecovery` semantic-commit inference and committed-root retirement path
- exact current implementation commit metadata for BUG-OUTPUT-01 remediation
- exact commit status contexts

## Provisional severity
- P0: 0
- P1: 0
- P2: 1 existing/open (`BUG-OUTPUT-01` Terminal post-commit/final-result semantic mismatch)

## Confirmed fixed invariants
- Terminal recovery now has a production consumer in both worker failure handling and startup reconciliation.
- staging-loss recovery journal/carrier discovery is materially present.
- COMMITTING/COMMITTED phases provide a durable semantic-boundary model.

## Open finding / evidence
The existing P2 remains source-level reproducible. The worker records `COMMITTING`, then durably deletes the Terminal DAO row. Any subsequent failure in `markPhase(COMMITTED)`, committed staging retirement, or journal clear reaches the ordinary catch, which ultimately returns `Result.failure()`. Recovery, however, interprets `COMMITTING` plus absent Terminal row plus all destinations present as `semanticCommitKnown = true` and converges that same durable attempt as committed success. Thus caller-visible final result can contradict the durable recovery semantic result after the semantic commit boundary.

Affected production files:
- `app/src/main/java/com/ireum/ytdl/work/TerminalDownloadWorker.kt`
- `app/src/main/java/com/ireum/ytdl/work/TerminalPublicationRecovery.kt`
- publication journal/cache ownership helpers transitively

## Remaining scope
- final recount of the same pinned implementation SHA
- verify no material governance change alters severity/gate interpretation
- final checkpoint

## Execution evidence
GitHub combined status contexts for exact implementation SHA are empty. Independent JVM/emulator execution in this run: `NOT_VERIFIED`. Source semantic evidence is sufficient to keep the existing P2 open.
