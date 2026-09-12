# Independent correctness review checkpoint — updater full-path

- Review time (UTC): 2026-09-12T22:34:00Z
- Frozen implementation SHA: `1bae1eafea08942f11e6df30e4a13a515dda621c`
- Frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review-governance bootstrap SHA: `5b2be1de08bdf7b83999b62dd990a8bedff22927`
- Frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- v6 checklist blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Completed scope
- Re-reviewed existing `BUG-UPDATER-02` from current production source, not from its historical diff.
- Traced production entries: `MainActivity` startup auto-update; `UpdateSettingsFragment` source-selection/manual update; `UpdateYTDLWorker` scheduled update; shared `UpdateUtil.updateYoutubeDL()` runtime mutation.
- Confirmed source selection writes `ytdlp_source` and immediately starts update for that requested channel.
- Confirmed `UpdateUtil.updateYoutubeDL()` still constructs `PROCESSING` when `updatingYTDL` is already true but does not return it; every overlapping caller continues and writes `updatingYTDL = true`.
- Confirmed there is no reset to false in the reviewed helper, no mutex/generation/lease, and no completion check that the request still owns the current selected source before runtime publication.
- Confirmed startup auto-update is launched in an independent `CoroutineScope(SupervisorJob())`; settings updates use lifecycle coroutines; WorkManager has another caller. Thus multiple production owners can reach the same mutation helper.

## Provisional blockers
- P0: 2
- P1: 1
- P2: 29
- `BUG-UPDATER-02`: EXISTING / P2 / OPEN / CONFIRMED
- No new canonical root identified.

## Confirmed fixed invariants
- None for `BUG-UPDATER-02`; the historical invariant remains violated on current source.

## Concrete reproduction/evidence
1. Startup update A enters for current source `stable` and begins mutating the shared yt-dlp runtime.
2. User changes source to `nightly`; `SharedPreferences.Editor.apply()` makes that change visible in-memory immediately, then settings calls `updateYoutubeDL("nightly")`.
3. Because the `updatingYTDL` PROCESSING branch does not return, B also proceeds into the same runtime mutation domain.
4. If B completes before A and A completes last, runtime materialization may correspond to stale `stable` while persisted/in-memory selected source is `nightly`.
5. No generation comparison or current-source revalidation prevents stale completion.

## Remaining review scope
- Verify no exact-SHA execution evidence or later governance state independently closes this root.
- Recount canonical blockers and final branch refs.
- Write final checkpoint before verdict.

## Exact upstream semantic basis used
- Android `SharedPreferences.Editor.apply()` contract, Android Developers API reference: `https://developer.android.com/reference/android/content/SharedPreferences.Editor` — modifications are committed to the in-memory SharedPreferences immediately and disk persistence is asynchronous; concurrent editors are last-apply-wins.
- Repository-local coroutine ownership facts are read directly from frozen production source; no assumption that detached coroutine launch serializes shared runtime mutation is made.
- Governing v6 rules applied: positive live authority; discovery/request is not mutation/completion authority; identity/provenance; concurrency/exact ownership; semantic consumer/effect closure.
