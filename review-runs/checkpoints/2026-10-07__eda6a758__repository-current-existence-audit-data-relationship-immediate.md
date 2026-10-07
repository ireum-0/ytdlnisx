# Repository current-existence audit — data/relationship immediate

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 31d83591650df6780b556e4d34acd22a007784a9
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-BACKUP-10 — VERIFIED_CLOSED

The historical restore path discarded SharedPreferences.Editor.commit() false results. Current merge
restore explicitly checks editor.commit(), and the later visible-keyword/youtuber preference writes route
through RestoreMutationAdmission.applyOrdinaryPreferences(), which also checks commit() and throws on a
non-durable write. The original false-success path is closed.

## BUG-DATE-04 — VERIFIED_OPEN P2

HistoryDateFetchManager.cancel() still requests cancellation, cancels WorkManager, calls stopExtractor(),
and then terminalizes the durable operation through finishCancellation(). stopExtractor() still wraps
both destroyProcessById calls in runCatching and discards the YoutubeDLCompat Boolean quiescence result.
HistoryDateFetchWorker also invokes the same unacknowledged stop helper from cancellation/finally paths.
A non-quiescent date-fetch native generation can therefore be represented as terminal CANCELLED with no
later nonterminal reconciliation owner.

## BUG-DUPLICATE-02 — VERIFIED_CLOSED

Archive duplicate admission no longer extracts an ID string and performs URL substring matching.
Configured archive lines are parsed as exact DownloadArchiveEntry(extractorKey, mediaId) values.
DownloadArchiveIdentity.sourceEntry() derives a positively-known source identity and matches exact set
membership; unknown/unreadable archive authority fails closed rather than becoming an empty archive.
The historical cross-extractor/substring false duplicate path is gone.

## BUG-HISTORY-03 — VERIFIED_OPEN P2 — same-root cancellation residual

The ordinary exception half of the root has been materially fixed:
- the primary History write now establishes durable DownloadPrimarySuccessAuthority;
- post-commit ordinary exceptions re-read that authority and become HISTORY_POST_COMMIT_WARNING /
  finalization debt instead of reclassifying the Download as failed.

However the History catch still executes:
  if (historyError is CancellationException) throw historyError
before the committed replacement/primary-success checks. The higher worker boundary also rethrows
CancellationException after cleanup.

Therefore cancellation arriving after the authoritative History/primary-success transaction can still
escape as cancelled rather than being locally classified from the already-committed semantic authority.
The same historical root is not fully closed.

Required residual:
post-commit cancellation must first resolve whether the exact primary semantic commit already won; if it
did, finalization/recovery owns the remaining work and cancellation cannot reinterpret that committed
result.

## BUG-KEYWORD-03 — VERIFIED_CLOSED

Automatic keyword scheduling now stages QUEUED status and an exact AUTOMATIC_KEYWORD_SYNC durable
WorkManager handoff carrier in the same Room transaction. Dispatch/retry/restart responsibility belongs
to WorkManagerHandoffRecovery, which observes enqueue acceptance and retains/reconstructs the exact
carrier. The historical durable QUEUED-with-no-carrier loss window is closed.

## BUG-KEYWORD-05 — VERIFIED_CLOSED

The worker's rule-engine semantic mutation and terminal SUCCESS/PARTIAL status update now execute inside
one db.withTransaction() while exact revision/handoff authority is held. A terminal-status write failure
therefore rolls back the same transaction rather than leaving committed sync semantics followed by a
status-only retry reinterpretation. Terminal error status and exact carrier retirement are also
transactionally coupled under current authority.

## BUG-METADATA-03 — VERIFIED_OPEN P3

UpdateMultipleDownloadsDataWorker still returns Result.success() after MetadataBatchProcessor reports
one or more failed items; it only logs batchResult.failed. Also, a null getDownloadMetadataPatch()
returns normally from processItem, so an item can retain the metadata gap that caused enrichment while
the batch treats that callback as completed. The original truthful-completion defect remains.

## BUG-PLAYER-02 — VERIFIED_OPEN P2

ensureLocalThumbForPlayback() still fetches/writes a cache image from the old remote source outside
HistoryReferenceMutationCoordinator. After taking the coordinator it rereads the History row but does
not compare current customThumb/thumb to the source used to create the cache. It then unconditionally
updateThumbById() with the stale cache path. A newer thumbnail B can still be overwritten by a cache
derived from old source A.

## BUG-PLAYLIST-01 — VERIFIED_OPEN P2

PlaylistRepository.deletePlaylist() still performs:
- deletePlaylistItemsByPlaylistId();
- deletePlaylist();
- deleteMembersByPlaylist();
inside RestoreMutationAdmission but without one Room transaction. Restore admission serializes Restore
authority; it does not make these three database mutations atomic. The historical partial destructive
playlist/group relationship state therefore remains reachable.

## BUG-RECONFIGURE-01 — VERIFIED_CLOSED

The notification reconfigure path no longer lets one stale/deleted sibling trigger a global Processing
delete for surviving existing rows:
- deleteExisting=true reads each candidate with getNullableDownloadById();
- a missing candidate is skipped;
- per-candidate refusal/exception is caught and does not abort the bundle;
- existing Error rows transition with snapshot-guarded updateForQueueIfSnapshot;
- the enclosing catch calls deleteProcessing() only when deleteExisting=false.

The exact historical stale sibling B -> global deletion of already-transitioned existing sibling A path
is closed.

## Immediate result

candidate_ids_audited_here: 10
verified_closed_here: 5
verified_open_here: 5

136-ID lower-bound progress:
- audited: 120
- verified closed/currently not reproduced: 76
- verified open: 44
- not yet audited: 16

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
