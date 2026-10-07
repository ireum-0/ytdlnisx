# Checkpoint-only production reconciliation — duplicate/history/cleanup roots

checkpoint_kind: CHECKPOINT_ONLY_PRODUCTION_FINDING_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: b51f60f49ae3ad153af5653d75a2438f72b6e43c
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-DUPLICATE-ADMISSION-01 — VERIFIED_CLOSED

Historical root:
Observe duplicate policy could perform final insert, then a worker could claim the row Active/E1 before
the Observe continuation published a stale Queued full-row update.

Exact historical closure:
- final SHA a12c58055fff51b104f8b56fd53b534b8d7e5df4;
- review-runs/checkpoints/2026-09-12__a12c5805__duplicate-admission-execution-closure-basis-advance.md;
- final duplicate admission recheck+insert became one Room transaction;
- freshly inserted final-admission rows no longer pass through the stale generic full-row update;
- exact-final production wiring and full JVM/build gates passed.

The later known-good baseline retained this closure, and db29f63..eda6a758 changes only scheduler
production files. No current evidence reopens the duplicate-admission root.

Disposition:
BUG-DUPLICATE-ADMISSION-01 = VERIFIED_CLOSED.

## BUG-HISTORY-DUPLICATE-IDENTITY-01 — VERIFIED_CLOSED

Historical root:
History "Remove duplicates" once grouped rows by display-title equality, allowing unrelated same-title
media to be destructively deduplicated.

Exact historical closure:
- final SHA aa1616a2c7710b878c44949a5f74ad02c6706d8d;
- HistoryDuplicateIdentity introduced typed destructive identity;
- generic HTTP(S) destructive identity preserves extractor-significant raw fragments;
- provider/stable media identity and DownloadType are retained;
- ambiguous/unprovable sources fail closed;
- History grouping uses the typed key rather than title-only equality.

The closure was incorporated into later known-good review state, and the current scheduler-only delta does
not touch HistoryDuplicateIdentity/WebUrlInput duplicate grouping semantics.

Disposition:
BUG-HISTORY-DUPLICATE-IDENTITY-01 = VERIFIED_CLOSED.

Keep distinct from BUG-HISTORY-01, which owns relationship/Undo atomicity after a legitimate destructive
target set has already been selected.

## CLEANUP-MAIN-THREAD-BLOCK-01 — VERIFIED_CLOSED

Historical root:
CleanupScheduleCoordinator.configure() used runBlocking from a synchronous settings listener while
destructiveEffectMutex could be held across database/cache deletion, allowing main-thread lock waiting
and ANR-class unresponsiveness.

Current exact source:
- CleanupScheduleCoordinator.configure(context, cadence) is suspend;
- it still correctly serializes authority transition with destructiveEffectMutex, but no runBlocking
  bridge is inside configure();
- DownloadSettingsFragment no longer waits synchronously from the preference/main-thread callback;
- cleanup transitions are launched through lifecycle-owned coroutine work;
- resetDownloadingPreferences explicitly calls configure() inside withContext(Dispatchers.IO);
- the source comment documents that the preference callback / Android main thread never waits
  synchronously for the destructive cleanup owner.

The authority serialization remains while the thread-affinity regression is removed.

Disposition:
CLEANUP-MAIN-THREAD-BLOCK-01 = VERIFIED_CLOSED.

Historical lesson remains valid: widened slow-work mutexes require synchronous-caller/thread-affinity
review even when the underlying authority fix is correct.

## Inventory effect

distinct checkpoint-only production roots classified here: 3
OPEN: 0
CLOSED: 3

Current download canonical inventory unchanged.
No production source, implementation prompt, active implementation scope, Master Plan or ledger changed.
