# Repository current-existence audit — Terminal immediate dispositions

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 9a73d653740559eaf37be4d7a611179e9f4c8ec1
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

Already-audited Terminal roots are not recounted here:
- BUG-TERMINAL-02 — Batch B, closed/current path absent.
- BUG-TERMINAL-03 — Batch D, closed.
- BUG-TERMINAL-05 — Batch D, closed.
- BUG-TERMINAL-06 — Batch A, closed.

## BUG-TERMINAL-01 — VERIFIED_OPEN P2

Historical root:
after user-visible Terminal output is already committed, an ancillary post-output failure can still
reclassify the operation as failed.

Current exact source still has the same semantic ordering:
1. cache-staged output is published through FileUtil.moveFile under exact Terminal output authority;
2. the worker verifies authoritative published paths, verifies no source files remain stranded, removes
   the artifact manifest, checks that no unproven temporary artifacts remain, and advances the
   PublicationRecoveryJournal to COMMITTING;
3. at this point the destination publication has already occurred and the exact publication journal
   records the completed effect;
4. TerminalExecutionRecovery is nevertheless still in POST_NATIVE_PUBLISHING;
5. the worker then performs ancillary/log/UI work, including TerminalDao.updateLog(redactedOutput,...),
   notification cancellation, and a delay;
6. only after those steps does it call TerminalExecutionRecovery.markCommitting(), delete the Terminal
   row, and set terminalSemanticCommit=true;
7. therefore an exception from the post-publication DAO log update reaches the broad catch while
   terminalSemanticCommit=false;
8. convergeTerminalOutcome(FAILURE) records/finishes FAILURE from the POST_NATIVE phase and the worker
   returns Result.failure(), even though the exact destination publication already won.

The publication recovery journal preserves evidence and prevents destructive loss, but it does not make
the Terminal semantic result successful at this boundary. The historical contradiction
"requested output committed -> later ancillary failure -> operation reported failed" therefore remains.

Disposition:
BUG-TERMINAL-01 = VERIFIED_OPEN P2.

Correction boundary:
- define the exact point at which successful destination publication becomes the primary Terminal
  semantic result;
- once exact output publication is proven complete, durably enter COMMITTING/success ownership before
  fallible ancillary log/notification cleanup, or move every must-succeed semantic write before the
  irreversible publication boundary;
- after primary success wins, later log/notification/journal-marker cleanup failures are recovery debt
  and must not produce a contradictory WorkManager failure;
- process death at every publication/COMMITTING boundary must converge exactly once without republishing
  output.

## BUG-TERMINAL-04 — VERIFIED_CLOSED

Historical root:
FileUtil.moveFile could partially publish a subset, retain failed source files, and Terminal discarded
that evidence and returned success.

Current exact source:
- publication is journaled before the first effect and each output reservation/publication is recorded;
- exact current-attempt source files are carried explicitly;
- the worker records move results through TerminalOutputAuthority;
- it throws if no authoritative output path is proven;
- it explicitly checks sourceFiles.filter { it.exists() } after the move and throws if any current-attempt
  source remains stranded;
- it also refuses success while the staging authority reports unproven temporary artifacts;
- failures retain publication/recovery evidence instead of deleting the semantic carrier as success.

Disposition:
BUG-TERMINAL-04 = VERIFIED_CLOSED.

## BUG-TERMINAL-07 — VERIFIED_CLOSED

Historical root:
Terminal cancel deleted the row and issued unawaited WorkManager cancellation while a stale worker could
still cross into output publication.

Current exact source:
- CancelTerminalNotificationReceiver uses TerminalCancellationCoordinator, not direct row deletion as
  cancellation authority;
- the coordinator durably supersedes the Terminal dispatch and independently converges the exact
  Terminal execution;
- rowDeletionAuthorized requires both dispatch supersession and execution convergence;
- TerminalExecutionRegistry serializes cancellation against post-native publication ownership;
- beginPostNativeEffects acquires the exact effect lease;
- markPublicationStarted is the final durable pre-publication gate under that same serialized owner;
- cancellation that wins while POST_NATIVE effects are live records the STOPPED outcome but does not
  claim those effects quiescent or authorize row deletion until the exact worker owner converges.

Disposition:
BUG-TERMINAL-07 = VERIFIED_CLOSED.

## BUG-TERMINAL-09 — VERIFIED_CLOSED

Historical root:
a progress callback exception could escape on the stdout reader thread, kill pipe drainage, and bypass
worker control flow.

Current YoutubeDLCompat production path:
- ProgressStreamReader catches Throwable and stores it in its failure field;
- executeNativeWithQuiescence joins the stdout/stderr processors;
- it explicitly rethrows stdOutProcessor.failure/stdErrProcessor.failure into the owning execute call;
- Terminal's fallible callback therefore returns to TerminalDownloadWorker's controlled error/recovery
  path instead of becoming an unowned reader-thread failure.

Disposition:
BUG-TERMINAL-09 = VERIFIED_CLOSED.

## BUG-TERMINAL-10 — VERIFIED_CLOSED

Historical root:
config-file write/request-construction failure occurred before the worker's main handled region and could
leave a persisted Terminal row with no semantic convergence.

Current exact worker:
- execution admission first establishes a durable TerminalExecutionRecovery witness;
- the setup/native/publication region is wrapped by an outer setup catch;
- a setupFailure after durable admission calls convergeTerminalOutcome(FAILURE) and returns
  Result.failure();
- doWork finally runs NonCancellable stopped cleanup/dispatch resolution/registry release;
- startup TerminalExecutionRecovery provides the durable cross-process owner.

Disposition:
BUG-TERMINAL-10 = VERIFIED_CLOSED.

## Immediate audit result

new_candidate_ids_audited_here: 5
verified_open_here: 1
verified_closed_here: 4

Corrected cumulative lower-bound progress:
- candidate IDs: 136
- audited unique IDs: 77
- verified closed/currently not reproduced: 66
- verified open: 11
- not yet audited inside lower bound: 59

Verified-open non-canonical repository roots now include:
- BUG-PLAYER-01 — P2
- BUG-QUEUE-01 — P3
- BUG-QUEUE-05 — P2
- BUG-DATE-03 — P2
- BUG-QUEUE-02 — P2
- BUG-QUEUE-03 — P2
- BUG-SCHEDULER-01 recurrence remainder — P2
- BUG-SCHEDULER-03 — P2
- BUG-SCHEDULER-04 — P2
- BUG-SCHEDULER-06 — P2
- BUG-TERMINAL-01 — P2

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
