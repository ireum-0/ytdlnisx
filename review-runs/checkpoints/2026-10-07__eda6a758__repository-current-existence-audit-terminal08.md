# Repository current-existence audit — BUG-TERMINAL-08

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: ae7395f08d12a4804097af58e64774f4a256d244
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-TERMINAL-08 — VERIFIED_CLOSED

Historical root:
Terminal could treat a successful root/supervisor exit as full native completion while an exact
descendant generation was still unresolved, publish output/delete the Terminal row/report success, and
leave restart recovery without a Terminal-specific durable native owner.

Current exact source closes both the live and restart halves.

Live execution:
- YoutubeDLCompat.execute() always consumes executeWithQuiescence();
- executeWithQuiescence returns a typed ExecutionResult carrying nativeFinalization;
- execute() throws NativeExecutionFailure whenever nativeQuiescent is false;
- executeNativeWithQuiescence() finalizes the exact prepared generation before returning the result;
- callback/root failures with unresolved finalization are likewise wrapped as NativeExecutionFailure;
- destroyProcessByIdForGeneration() returns false on missing/changed/opaque exact generation authority
  instead of treating process-ID absence as quiescence.

Terminal ownership:
- TerminalDownloadWorker creates a durable TerminalExecutionRecovery witness before native work;
- the exact generation token is bound into that witness before/at native launch;
- native completion must advance markNativeFinished() before post-native publication ownership;
- unresolved native failure therefore cannot enter the ordinary Terminal semantic-success branch.

Restart:
- App startup runs TerminalExecutionRecovery.reconcile();
- NATIVE_STARTED/NATIVE_QUIESCENCE_PENDING records require proveNativeQuiescent();
- proveNativeQuiescent() requires the exact bound generation token once native may have started and calls
  destroyProcessByIdForGeneration();
- if quiescence cannot be proven, the durable Terminal record remains deferred instead of deleting the
  Terminal row or declaring success;
- TerminalPublicationRecovery separately owns any partial publication remainder.

Disposition:
BUG-TERMINAL-08 = VERIFIED_CLOSED.

Regression classification:
this was historically a remediation regression, but the current exact implementation now restores the
required invariant. The historical regression record remains part of repository history; it is not a
current open root.

## Immediate accounting

Lower-bound 136-ID population:
- audited IDs: 135
- VERIFIED_CLOSED/currently-not-reproduced: 82
- VERIFIED_OPEN: 52
- SUPERSEDED_ALIAS: 1
- remaining unaudited IDs: 1

Remaining:
- BUG-TERMINATE-01

Current narrow download canonical remains P0=0 / P1=0 / P2=8.
