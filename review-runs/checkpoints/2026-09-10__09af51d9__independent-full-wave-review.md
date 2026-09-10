# Independent full-wave correctness review — 09af51d9

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Exact reviewed remote HEAD: `09af51d92209d6735e283622fa7d3680414523bc`
- Starting implementation SHA: `8de0e0f8aa7c0f4294c41b4b6e958acf53a778fa`
- Last pre-wave independently CLEAN Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`
- Plan commit: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Master Plan SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- Authoritative ledger ref: `899328bc91e4008e39a658387396a0106c8666ec`

## Git integrity

Remote implementation HEAD directly matched `09af51d92209d6735e283622fa7d3680414523bc` during review.

Exact correction chain verified as nine commits ahead of `8de0e0f8`:

`8de0e0f8`
→ `0ea7902888b1f302dca57296ba57512c677d0d5f`
→ `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
→ `2a7703e2644a3bf184a7ac46616f174c1ebfeeb6`
→ `d73a7c236267bb5956ab27ed8e5621d566816cd3`
→ `7f6fd812cc8d45a55da5e0e1cdc9c191b5b9f8f7`
→ `72fc93124fba12d0d7bc45825906764ea8d65a09`
→ `a4eaaa64dbd5a1f32e962dcc9b689cfdbbe51891`
→ `5473706b085828f9f425da9e28b6f12131376d88`
→ `09af51d92209d6735e283622fa7d3680414523bc`

Changed-file scope matched the reported Terminal, Download publication/recovery, discovery, and config-duplicate boundaries.

## Independent semantic verdicts

### T1 — CLEAN

Commits:
- `0ea7902888b1f302dca57296ba57512c677d0d5f`
- `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`

The real production `YoutubeDLCompat.executeWithQuiescence()` path now durably prepares the exact native generation marker, invokes the Terminal generation-binding callback, and only then crosses `ProcessBuilder.start()`. A bind persistence failure therefore prevents native launch.

Admission initializes the native barrier before restart rebinding. An ADMITTED record can rebind only an exact matching generation marker; unavailable/legacy/unknown generation state remains fenced. Registration verifies that the durable Terminal generation equals the live native barrier generation. Existing `QUARANTINED_UNKNOWN` precedence remains intact.

This closes the prior T-A/T-B/T-C native-start gap while preserving the already-reviewed post-admission setup and false-quiescence fencing.

Status:
- P2-D: CLOSED
- P2-E: CLOSED
- P2-F: CLOSED
- P2-G: CLOSED

### K1 — NOT_CLEAN

Commit:
- `2a7703e2644a3bf184a7ac46616f174c1ebfeeb6`

Active/queued `DownloadItem.url` media identity canonicalization is directionally correct and the request-configuration object retains the other configuration fields.

However History command comparison is role-blind. `normalizeCommandForComparison()` tokenizes the command and canonicalizes every token for which `youtubeVideoId(token) != null`; it does not distinguish the positional source-media URL from an option value. This contradicts its own comment that option-value URLs must not be rewritten.

Production `YTDLPUtil` appends non-command `downloadItem.extraCommands` to the yt-dlp request. `YtdlpArgumentPolicy` blocks only a restricted set of external options and does not remove ordinary URL-valued options such as `--referer`. Consequently a YouTube-looking option value can be canonicalized during History duplicate comparison even though it is an explicit configuration value. Manual and Observe History duplicate consumers both use `DownloadConfigurationDuplicatePolicy.commandsMatch()`.

Concrete invariant violation: media identity may be canonicalized, but option values / `extraCommands` configuration identity must remain semantically distinct. A source-media-only canonicalization pass must use option ownership/role information rather than mapping every URL-like token.

Status:
- P2-K: OPEN / NOT_CLEAN

Required acceptance addition:
- positional source URL spelling equivalents compare equal;
- equivalent-looking YouTube URLs used as values of `--referer` or another allowed URL-valued option are not rewritten as source identity;
- differing `extraCommands` remain non-duplicates unless the only difference is the actual positional source-media URL spelling.

### R1 — CLEAN for canonical P2-H scope

Commits:
- `d73a7c236267bb5956ab27ed8e5621d566816cd3`
- `72fc93124fba12d0d7bc45825906764ea8d65a09`
- `5473706b085828f9f425da9e28b6f12131376d88`
- `09af51d92209d6735e283622fa7d3680414523bc`

`PublicationRecoveryJournal`, `TerminalCacheOwnership`, and `TerminalExecutionRecovery` now expose typed discovery that distinguishes healthy records/empty state from unavailable and opaque/malformed debt.

Download per-worker prior-publication recovery throws/fails closed on unavailable or opaque publication discovery before destructive staging reset or fresh producer authority. Terminal execution admission returns BLOCKED on unhealthy execution discovery. Terminal publication admission separately blocks on unhealthy publication or cache-carrier discovery, including rechecks after reconciliation.

The compatibility projection methods remain, but no production admission authority was found that uses them to turn discovery uncertainty into healthy absence. Startup reconciliation paths that log/defer on namespace uncertainty do not grant producer authority; the actual worker admission paths re-check typed discovery and fail closed.

Status:
- P2-H: CLOSED

### D1 — NOT_CLEAN

Commits:
- `7f6fd812cc8d45a55da5e0e1cdc9c191b5b9f8f7`
- `a4eaaa64dbd5a1f32e962dcc9b689cfdbbe51891`

The wave correctly removes one dangerous behavior: recovered prior publication paths are no longer merged into a fresh producer generation, and an exact already-published source entry prevents a second provider reservation/create in the same durable record.

The full Download lifecycle cluster is nevertheless not closed.

#### P2-B remains OPEN

The production request still passes the authoritative global app archive directly to yt-dlp via `--download-archive FileUtil.getDownloadArchivePath(context)`. The same global file remains a duplicate/skip authority in manual `DownloadViewModel` and `ObserveSourceWorker`. There is still no execution-private archive plus post-primary-success atomic/idempotent merge. Therefore B8 remains source-confirmed: yt-dlp can durably mutate global duplicate authority before app publication/History/no-History primary semantic success.

The wave also did not add the required immutable effective semantic fingerprint or deterministic adopt/supersede generation ledger.

#### P2-C remains OPEN

Exact D1 replay is fenced, but exact destination + source-retirement debt is not represented/converged as a source-retirement-only state. `recoverPriorPublication()` instead sets `priorPublicationFinalizationRequired` and throws `PriorPublicationFinalizationRequiredException`; the outer failure path includes that known exact-publication state in `providerOutcomeUnknown`. Thus the implementation fails closed, but it does not satisfy the required state machine `exact destination durable / source retirement pending → retire source only`, nor the explicit reconciliation rule for a missing exact D1.

#### P2-J remains OPEN

Ordinary History commit still lacks the replacement-style exact primary-success authority. In `runHistoryPersistence()`, ordinary insertion uses `historyKeywordAssignments.insertHistory(historyItem)`, but only the replacement path sets `historyReplacementCommitted=true`. Later terminalization checks `if (!historyReplacementCommitted && shouldStopForUserRequest())`, so a late durable stop can still win after an ordinary H1 has committed. No atomic Room binding of exact ordinary `historyId` to execution/generation primary success was added.

Status:
- P2-B: OPEN / NOT_CLEAN
- P2-C: OPEN / NOT_CLEAN
- P2-J: OPEN / NOT_CLEAN

## Review Basis and count

Pre-wave canonical count was `P0 0 / P1 0 / P2 11`:
B, C, D, E, F, G, H, J, K, L, M.

Closed by this independent review:
- P2-D
- P2-E
- P2-F
- P2-G
- P2-H

Remaining canonical P2:
- P2-B
- P2-C
- P2-J
- P2-K
- P2-L
- P2-M

Updated canonical count:
`P0 0 / P1 0 / P2 6`

The contiguous independently reviewed correction frontier advances from `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8` through the repaired Terminal boundary to:

`c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`

It does NOT advance across `2a7703e2...` because K1 is NOT_CLEAN. Later R1 semantic closure is retained independently even though the contiguous Review Basis cannot leap over that unresolved commit.

## Conflicting scheduled checkpoint

The pre-existing review-branch commit `66317b71d50734d99717f9044f6dbd1981f7d3ae` provisionally recounted only two P2s at `09af...` and treated B/C/J/K as fixed. This independent full-wave review does not adopt that recount. The exact-source evidence above preserves B/C/J/K as open where their canonical acceptance conditions remain unsatisfied.

This checkpoint records the independent semantic decision; it does not modify the authoritative ledger.

## Execution evidence

Implementation-agent evidence reported:
- focused Terminal tests: 7 passed;
- focused publication/recovery tests: 29 passed;
- focused duplicate-policy tests: 7 passed;
- full JVM: 559 passed, 0 failed, 0 skipped;
- KSP/debug/androidTest Kotlin compilation: PASS;
- instrumentation/device: not executed.

Independent GitHub evidence for exact `09af51d92209d6735e283622fa7d3680414523bc`:
- combined commit status contexts: 0;
- GitHub Actions workflow runs associated with the commit: 0.

INDEPENDENT EXECUTION: NOT EXECUTED

## Overall verdict

`NOT_CLEAN`

The wave materially closes the Terminal native-generation handoff and canonical recovery-discovery fail-open blocker, but Download lifecycle authority and config duplicate command-role identity still require follow-up correction. P2-L and P2-M were outside this wave and remain open.