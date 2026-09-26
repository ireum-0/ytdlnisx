# CLEAN-basis exploratory review — BUG-CANCEL-02 Terminal attribution refinement

checkpoint_kind: EXPLORATORY_REVIEW
review_mode: implementation_diff_frozen_clean_basis
review_parent_sha: `f80699c2db1cd92c90820d93aa9d9c80a6241fce`

reviewed_implementation_sha: `74f57e695db30b701ad429af311c39a763bfe086`
clean_review_basis: `74f57e695db30b701ad429af311c39a763bfe086`

current_remote_implementation_at_prewrite:
`dcad84ac30aa6dac7096961e408b48971c2f0376`

implementation_agent_currently_working: YES
active_implementation_start_head:
`dcad84ac30aa6dac7096961e408b48971c2f0376`
active_implementation_scope:
`P2 BUG-TERMINAL-11 TEST-ONLY DETERMINISTIC QUIESCENCE CLOSURE`

Active-wave commits/diffs were not inspected.

governing_ledger: `b98d315006fa19fc6f22b017f43a91899db5fb81`
governing_checklist: v7
governing_checklist_blob: `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`
lens_policy_blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
protocol_blob: `b9ff2c984a1655f0f214ab0ec89d3ccb18c0f913`

## Independent verdict

Overall workflow remains **NOT_CLEAN**.

This exploratory review creates no new finding ID and changes no canonical count.

It independently confirms that the previously established Terminal cancellation/publication/cache
maintenance subcase of canonical P2 BUG-CANCEL-02 was already present at the independently CLEAN
basis `74f57e695db30b701ad429af311c39a763bfe086`.

Attribution refinement:
- the Terminal subcase is proven present no later than 74f57e69;
- it was not introduced by the later cf737451 Terminal provider-binding/generation review state
  where the subcase was first detected;
- exact introducing commit remains **NOT_VERIFIED**.

## Findings

### Existing P2 BUG-CANCEL-02 — Terminal subcase confirmed at CLEAN_REVIEW_BASIS

Canonical invariant:
once durable cancellation wins, stale worker success-side effects must be vetoed before the same
irreversible effect/commit boundary.

Exact 74f57e69 production sequence:

1. `TerminalDownloadWorker` completes native execution and persists
   `TerminalExecutionRecovery.Phase.NATIVE_FINISHED`.
2. Provider/file publication has not necessarily started or completed.
3. User cancellation enters `TerminalCancellationCoordinator.cancel()`.
4. Dispatch cancellation returns distinct facts:
   `dispatchSuperseded` and `workManagerCancellationAcknowledged`.
5. `TerminalCancellationResult.rowDeletionAuthorized` requires only
   `dispatchSuperseded && executionConverged`; WorkManager cancellation acknowledgement is not
   required for row retirement.
6. `TerminalExecutionRegistry.cancel()` calls
   `TerminalExecutionRecovery.convergeTerminal(..., STOPPED)`.
7. For a `NATIVE_FINISHED` witness, `convergeTerminal()` can transition the exact execution to
   `TERMINAL_STOPPED` without proving that the already-running worker's post-native publication
   code has stopped.
8. On convergence, `TerminalExecutionRegistry.cancel()` removes the process-local active token.
9. The worker proceeds from `markNativeFinished()` into output discovery, publication-journal
   setup, and `FileUtil.moveFile(...)` without an intervening durable-cancellation authority
   recheck.
10. `TerminalExecutionRecovery.markCommitting()` is checked only after provider/file publication
    may already have occurred.
11. A worker that remains live because WorkManager cancellation was unacknowledged or not yet fully
    unwound can therefore publish output after durable cancellation has already won.
12. The later semantic-commit failure cannot undo an already-created provider/file side effect.

This is the already-canonical BUG-CANCEL-02 root, not a new Terminal-specific finding.

### Cache-maintenance consequence

The same sequence retires the process-local cache liveness signal too early:

- `TerminalExecutionRegistry.cancel()` removes the active token after native-only convergence;
- `TerminalCacheOwnership.isLiveOwnedRoot()` treats a marker as live only while that token remains
  active;
- `AppCacheManager.delete()` protects Terminal cache entries through that live-owner predicate;
- `CacheMaintenanceAuthority` serializes only the short admission/maintenance window and does not
  span the full Terminal execution/publication lifetime.

Therefore a TERMINAL_CACHE maintenance pass can treat staging owned by a still-unwinding worker as
unowned after cancellation has retired the token but before publication/recovery ownership has
quiesced.

This remains a destructive consequence of BUG-CANCEL-02 and is not counted as a separate cache
finding.

## Root / count reconciliation

No duplicate root.

No new P0/P1/P2 finding ID.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

The current BUG-TERMINAL-11 active-wave status is unchanged.

## Trigger map

### Module B — External scheduler handoff
**TRIGGERED / NOT_CLOSED under existing BUG-CANCEL-02**

Dispatch supersession and WorkManager cancellation acknowledgement are separately represented, but
row/token retirement can occur before the exact worker/effect owner has quiesced.

### Module I — Maintenance vs live-owner namespace
**TRIGGERED / FAIL under existing BUG-CANCEL-02**

Terminal staging liveness is tied to the process-local execution token. Native-only cancellation
convergence can remove that token before post-native publication ownership ends.

### Destructive/final-effect cancellation authority
**TRIGGERED / FAIL**

The exact durable STOPPED winner is not revalidated immediately before irreversible provider/file
publication.

## L1-L6 review

### L1 — Durability & recovery
**BASELINE**

Durable execution and publication recovery records exist, but cancellation terminalizes the native
execution witness before proving the post-native effect owner quiescent.

### L2 — Identity & provenance
**BASELINE**

The same exact Terminal execution token is used, but exact native-generation identity is not the
same ownership dimension as in-flight provider publication authority.

### L3 — Concurrency & authority
**BASELINE**

The unsafe cell is cancellation racing after `NATIVE_FINISHED` and before/during publication.
A failed or incomplete WorkManager stop leaves the worker able to cross the publication boundary.

### L4 — Destructive ownership
**DEEP**

Primary DEEP lens.

The cancellation owner may retire row/token live ownership before the irreversible publication or
cache-staging owner has quiesced. This permits post-cancel publication and can expose still-owned
staging to cache deletion.

Selection reason:
R1 — the root is an exact final-effect ownership failure.
R2 — Module I is directly triggered by the live-owner namespace transition.

### L5 — Platform contract closure
**BASELINE**

WorkManager cancellation acknowledgement and fully unwound worker/effect quiescence are distinct
facts. Neither one alone is treated here as proof of provider-publication quiescence.

### L6 — Cross-feature semantic propagation
**BASELINE**

The cancellation invariant crosses dispatch carrier, execution witness, provider publication
journal, Terminal row, live-cache token, and cache-maintenance consumer.

## Review retrospective

The cf737451 L4 review first detected this Terminal subcase while examining a later implementation
state. Re-reading the exact CLEAN basis proves the decisive sequence already existed at 74f57e69.

The key distinction is ownership phase:
native completion/quiescence is narrower than post-native publication/effect quiescence.

The source already models WorkManager acknowledgement separately, and publication has its own
journal/recovery ownership. The gap is that cancellation can retire row/token liveness based on the
native witness before the publication owner has either been vetoed or handed off to exact recovery.

No active BUG-TERMINAL-11 wave source was inspected.

## Checklist evolution

No checklist or lens-policy change is proposed.

Checklist v7 already contains the required rules:
- final mutation/effect authority;
- Module B scheduler handoff;
- Module I maintenance vs live-owner namespace;
- cross-attempt/live-owner reasoning;
- async completion/quiescence distinctions.

The issue is coverage/application, not missing governance.

## Checkpoint summary

- basis: `74f57e695db30b701ad429af311c39a763bfe086`;
- active deterministic-quiescence implementation wave remained frozen from inspection;
- BUG-CANCEL-02 Terminal subcase confirmed present at CLEAN basis;
- exact introducing commit remains NOT_VERIFIED;
- no new finding ID;
- no canonical count change;
- current BUG-TERMINAL-11 status unchanged;
- overall workflow remains NOT_CLEAN;
- independent execution: NOT EXECUTED.
