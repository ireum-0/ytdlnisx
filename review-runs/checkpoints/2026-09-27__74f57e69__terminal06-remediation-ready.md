# BUG-TERMINAL-06 clean-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 5776c627607b7512f3dcad78a7b0a119dc263304
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
previously_observed_completed_implementation_head: 31f55aca76efb1b956567fca5325b2575779a835
newer_live_implementation_head_unreviewed: 21a04168286ae6562adbf219b8ea6a03984a2e25
active_tooling_wave_inspected: NO

verdict: OPEN P2 / PARTIALLY FIXED / SAME-ROOT RESIDUAL CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 17
primary_lens: L3 Concurrency & authority DEEP
supporting_lenses:
- L1 Durability & recovery
- L2 Identity & provenance
- L4 Destructive ownership
independent_execution: NOT EXECUTED

## Existing hardening to preserve

The original in-process "idle snapshot then recursive delete" race is materially narrowed on the CLEAN basis.

`AppCacheManager.delete()` executes under `CacheMaintenanceAuthority.withMaintenanceWindow`. `TerminalExecutionRegistry.admit()` enters the same authority through `withExecutionAdmission`, so a new Terminal execution cannot cross durable admission while a cache-maintenance window owns that gate.

Deletion also rechecks every collected entry at the destructive boundary through `isLiveOwnedEntry()`. For Terminal roots, `TerminalCacheOwnership.isLiveOwnedRoot()` requires a valid ownership marker whose task token is currently present in `TerminalExecutionRegistry`.

`TerminalDownloadWorker` creates the operation-scoped `TERMINAL/<taskToken>` directory and ownership marker before native/output execution. The admitted-to-marker interval is therefore not itself a destructive-output residual: if maintenance removes the still-empty directory before the marker exists, `ensureMarker()` recreates the root before native work can begin; once the marker is published while the process-local token is active, per-entry deletion fails closed.

These protections should not be removed or replaced with the old aggregate-count check.

## Same-root residual

The remaining defect is the process-death / durable-recovery side of the same Terminal cache ownership root.

`TerminalCacheOwnership.isLiveOwnedRoot()` recognizes only:

1. a valid Terminal ownership marker; and
2. `TerminalExecutionRegistry.isActiveNow(taskToken)`.

The registry is process-local. After process death/restart it is empty even when the same task still has durable execution responsibility in `TerminalExecutionRecovery`, including `ADMITTED`, `NATIVE_STARTED`, `NATIVE_FINISHED`, or quiescence-pending state. A valid marker-backed staging root can therefore become "not live" to `AppCacheManager` solely because the process-local registry was lost.

Startup recovery does not close that deletion authority gap. `App.onCreate()` launches Terminal execution/publication reconciliation asynchronously. `TerminalExecutionRecovery.reconcile()` can need to rebind/prove native generation state and quiescence before the durable task is terminalized. Manual cache deletion has no shared durable-owner read against that recovery record, and Terminal startup reconciliation itself is not the per-entry protection predicate used by `AppCacheManager`.

The second residual is marker-revoked recovery state. `TerminalCacheOwnership.recordRecoveryCarrier()` / `discoverRecoveryRoots()` intentionally preserve exact partial-publication or quarantine state in `.ytdlnisx-terminal-recovery.json` after the live marker has been revoked. `AppCacheManager.isLiveOwnedEntry()` does not recognize that carrier at all. Generic `TERMINAL_CACHE` deletion can therefore remove a valid recovery root/carrier as ordinary cache before the recovery owner has explicitly retired it.

Thus a restart or recovery transition can still produce:

durable Terminal execution/publication responsibility
-> no process-local live token (or marker intentionally revoked)
-> generic cache deletion sees no live owner
-> exact staging/recovery files and possibly the recovery carrier are deleted
-> Terminal recovery loses the filesystem state/evidence it is responsible for converging.

This is the existing BUG-TERMINAL-06 root, not a new finding. The in-process admission race is partially fixed; durable ownership across process death is not yet part of cache-deletion authority.

## Exact invariant

A `TERMINAL_CACHE` entry may be destructively removed only when the exact Terminal directory is proven to have no current execution owner and no unresolved durable recovery/publication owner.

Process-local registry absence is never sufficient proof after a marker, execution witness, publication journal, or recovery carrier has established Terminal responsibility.

A valid unresolved recovery carrier is protected state, not stale cache. An unreadable/opaque Terminal ownership or recovery namespace is uncertainty and must fail closed rather than being interpreted as removable absence.

## Narrow implementation boundary

Preserve `CacheMaintenanceAuthority` and the existing per-entry destructive recheck.

Add one shared Terminal cache-protection classifier consumed by `AppCacheManager` at the deletion boundary. For an exact Terminal task root it must combine, without broad directory heuristics:

- valid Terminal ownership marker identity;
- current process-local `TerminalExecutionRegistry` liveness when present;
- exact durable `TerminalExecutionRecovery` responsibility for the same execution token while that responsibility is non-retired/deferred;
- exact Terminal publication journal / recovery-carrier ownership for that root;
- opaque/unreadable discovery as protected/unknown, never stale.

Do not use the mutable Terminal DAO row alone as deletion authority. Do not parse the numeric prefix of `taskToken` as the sole identity proof if the exact durable record can be matched by execution token. If marker schema evolution is needed to carry an explicit subject identity, keep old markers fail-closed rather than guessing identity.

Startup reconciliation and manual maintenance may share additional serialization if useful, but serialization alone is insufficient: a deferred durable recovery owner can legitimately remain after a reconciliation pass, so the final deletion predicate must still recognize durable responsibility.

Only after all exact live/recovery authorities for a root are absent or explicitly retired may that root become stale-removable.

## Forbidden shortcuts

- restoring reliance on `hasActiveDownloads()` / Terminal row count as deletion authority
- treating an empty `TerminalExecutionRegistry` after restart as proof that marker-owned staging is stale
- deleting a marker-revoked recovery carrier because it is not "live"
- using Terminal DAO row presence/absence alone as execution or publication ownership
- parsing `taskToken` text as authoritative subject identity without an exact durable binding
- blocking all Terminal cache cleanup permanently instead of distinguishing unresolved ownership from proven stale state
- deleting opaque/unreadable ownership or recovery state to make cleanup report complete

## Acceptance matrix

- maintenance window starts first; new Terminal admission waits and later runs normally
- Terminal already admitted with marker + active process-local token; live root is skipped
- maintenance observes the admitted-to-marker interval; no native/output file is lost and the worker establishes its marker before execution
- process restart with marker + durable `ADMITTED` witness and no process-local token; manual clear preserves the root until recovery explicitly retires responsibility
- process restart with marker + durable `NATIVE_STARTED` / generation witness; clear cache cannot delete staging while native quiescence/recovery is unresolved
- `NATIVE_FINISHED` but semantic/publication convergence still pending; root remains protected until the exact owner is retired
- valid marker-revoked `.ytdlnisx-terminal-recovery.json` carrier; generic Terminal cache deletion preserves the carrier and its exact remaining sources
- QUARANTINED_UNKNOWN / unknown reservation recovery state remains protected and diagnosable
- malformed/unreadable Terminal marker or recovery namespace fails closed as partial/skipped cleanup
- truly stale Terminal directory with no valid marker, execution witness, publication journal, or recovery carrier remains removable
- stale valid marker whose exact durable responsibility has been explicitly retired becomes removable without requiring a fake active token
- cancellation, same-command retry/manual rerun, WorkManager retry, and startup reconciliation cannot reinterpret a cleanup-damaged prior attempt as safe absence
- production wiring test covers `FolderSettingsFragment/AppCacheManager -> TerminalExecutionRecovery/TerminalCacheOwnership -> TerminalDownloadWorker`, not only helper mutex behavior

Existing `CacheMaintenanceAuthorityTest` proves only maintenance-vs-admission ordering. `CacheMaintenanceProductionWiringTest` covers Download cache ownership, not the Terminal restart/recovery cells above. `TerminalExecutionProductionWiringTest` exercises Terminal recovery/ownership paths but does not invoke `AppCacheManager` or `TERMINAL_CACHE` deletion. The cross-boundary regression remains missing.

The active/newer implementation at `21a04168286ae6562adbf219b8ea6a03984a2e25` was not inspected and is recorded only as unreviewed forward implementation movement.
