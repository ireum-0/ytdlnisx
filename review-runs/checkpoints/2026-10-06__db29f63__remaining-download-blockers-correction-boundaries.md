# Remaining download blockers — correction-boundary concretization

Date: 2026-10-06

record_kind: INDEPENDENT_CORRECTION_BOUNDARY_REVIEW
record_status: FINAL
manual_review_run: NO

implementation_basis: db29f63ce169176b4c8ade4cec01f66cc0307ec8
implementation_basis_policy: FIXED_REMOTE_BASIS_ONLY
in_progress_scheduler_candidate_inspected: NO
review_parent_sha: 85339789657ac00f3071a5b1f71dbbdb11ec4b98
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
independent_execution: NOT_EXECUTED

latest_manual_review_reconciliation:
- 85339789657ac00f3071a5b1f71dbbdb11ec4b98 is a same-db29f63 L4 manual review.
- It adds only deferred BUG-PLAYLIST-DELETE-01 and BUG-COOKIE-RESTORE-01.
- The seven active download blockers remain unchanged.
- No implementation source moved.

scope:
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01

scheduler_scope:
- BUG-SCHEDULER-WINDOW-01 remains the active implementation wave.
- This review does not inspect or rely on its local/in-progress four-file candidate.
- Nothing here changes the scheduler correction or launch-recovery contract.

## Historical review pattern carried forward

Prior correction-boundary work consistently used:
1. exact producer/carrier/consumer/final-effect tracing;
2. concrete incorrect outcome;
3. root/alias separation;
4. minimal safe implementation boundary;
5. deterministic acceptance matrix;
6. one semantic root counted once.

Representative prior checkpoints:
- 2026-09-12__9edd3e23__bulk-format-partial-success-broader-promotion.md
- 2026-09-12__9edd3e23__format-notification-stale-authority-broader-promotion.md
- 2026-09-11__6763fb1b__history-title-duplicate-correction-boundary.md
- 2026-09-20T0554Z__90afaec1__bulk-format-silent-partial-success-exact-basis-revalidation.md
- 2026-09-20T0650Z__90afaec1__format-notification-stale-authority-exact-basis-revalidation.md

This checkpoint applies the same discipline to the six blockers that follow the scheduler wave.

---

# BUG-FORMAT-BG-01 — lifecycle mutation is not bounded to the captured background-format bundle

## Exact production chain

DownloadMultipleBottomSheetDialog.onContinueOnBackground()
-> DownloadViewModel.continueUpdatingFormatsOnBackground(selectedItems)
-> captures:
   allProcessing = repository.getAllProcessingDownloads().map { it.id }
   ids = selectedItems ?: allProcessing
   other_ids_in_bundle = allProcessing - ids
-> moveProcessingToSavedCategory()
-> DownloadDao.updateProcessingtoSavedStatus()
-> SQL updates every row whose current status is Processing.

Exact-current source:
- DownloadViewModel.kt 2942-2969;
- DownloadDao.kt 1142-1148.

Authority sets are therefore different:
- refresh target = exact ids;
- captured UI bundle = exact allProcessing snapshot;
- actual mutation target = live status='Processing' predicate at SQL execution.

## Concrete failure

A/B are captured; A selected; C enters Processing after capture.
The broad UPDATE moves A/B/C to Saved.
Worker knows A; completion carrier knows A+B.
C was mutated by this operation but has no matching worker/completion responsibility.

The presence of other_ids_in_bundle proves the current UX intentionally remembers captured-but-not-refreshed
bundle members. This review preserves that observable composition; it does not justify mutating later
Processing entrants.

## Root boundary

Owns row-selection/lifecycle authority only.

Distinct from:
- BG-02 WorkManager acceptance/recovery;
- BG-04 worker stale publication;
- the older completion-notification stale-authority root, already fenced by
  transitionFormatNotificationCandidateToProcessing().

## Minimal safe correction

1. Freeze one exact immutable captured bundle-id set.
2. Keep selected refresh ids as an explicit subset.
3. Preserve explicit other_ids_in_bundle semantics for captured nonselected members.
4. Replace live status='Processing' bulk mutation with exact-id transition over the captured bundle.
5. Revalidate each exact row at mutation:
   - still Processing;
   - no incompatible newer owner/state;
   - History replacement refusal/barrier fail-closed.
6. Rows entering Processing after capture remain untouched.
7. Captured rows that advance before mutation are refused, not pulled back.
8. No worker/result/cancel behavior is changed by this root alone.

## Acceptance

- captured A/B, selected A -> exact captured bundle transition; A worker target; B explicit other member.
- C enters Processing after capture -> C untouched.
- B leaves Processing before transition -> not overwritten.
- barrier/refusal appears -> preserved/refused.
- deleted captured row -> no recreation.
- null/empty selected mode preserves existing whole-captured-bundle behavior.
- no noncaptured Processing row changes.

---

# BUG-FORMAT-BG-02 — Saved publication lacks durable WorkManager acceptance/recovery ownership

## Exact production chain

continueUpdatingFormatsOnBackground():
1. moves the bundle out of Processing;
2. builds UpdateMultipleDownloadsFormatsWorker;
3. adds shared tag updateFormats;
4. calls enqueueUniqueWork;
5. ignores Operation.result;
6. stores no exact recoverable format-batch carrier.

The repository's WorkManagerHandoffRecovery states the governing invariant explicitly:
"Calling enqueueUniqueWork is not acceptance."
It persists exact request UUID/generation and reconciles after process death.

Current WorkManagerHandoffCarrier can hold request/generation state, but it does not contain the exact
format worker ids + other bundle ids. That payload must itself become durable.

## Concrete failure

Rows become Saved -> enqueue fails/process dies before durable acceptance -> no exact pending batch
exists after restart -> intended refresh disappears while durable row state already changed.

## Root boundary

Owns enqueue acceptance/restart responsibility.

Distinct from:
- BG-01 row set;
- BG-03 worker result after execution starts;
- BG-05 exact cancellation after a request exists.

## Minimal safe correction

1. Persist one exact semantic format-handoff generation before producer authority is released.
2. Durably carry:
   - selected worker ids;
   - captured other/bundle ids;
   - exact WorkRequest UUID;
   - unique work identity;
   - separate notification/display id;
   - state/attempt timestamps sufficient for restart convergence.
3. Stage lifecycle transition + durable responsibility in one coherent transaction, or prove no
   Saved-without-owner state exists.
4. Enqueue exact persisted request generation.
5. Observe Operation.result.
6. Failed enqueue: same semantic generation, new exact request UUID.
7. Restart: reconstruct PENDING/ACCEPTED handoff and reconcile by exact WorkInfo/request identity.
8. Exact worker completion/cancellation retires only that handoff.
9. Retry/recovery is idempotent.
10. Timestamp Int may remain UI notification id, never semantic request identity.

## Acceptance

- accepted enqueue -> exact carrier ACCEPTED.
- Operation failure -> recoverable responsibility retained.
- death after carrier transaction/before enqueue -> restart enqueues exact batch.
- death after acceptance/before worker -> no duplicate request.
- concurrent batches do not alias.
- stale old-request callback cannot resolve replacement request.
- exact worker resolves only its own handoff.
- cancellation has deterministic terminal/recovery state.

---

# BUG-FORMAT-BG-03 — per-item failure/cancellation collapses into aggregate success

## Historical continuity

Current BG-03 is the active db29f63 expression of the previously reviewed bulk-format partial-success
root. The historical BUG-FORMAT-01 correction boundary still applies and is not a new count.

## Exact production chain

UpdateMultipleDownloadsFormatsWorker:
- setForegroundAsync(...) result is not awaited/observed;
- per-item fetch + Result write + Download write is inside ignored runCatching;
- updateIfExecutionOwned(...) Boolean is ignored;
- count/progress advances after the ignored block;
- nested CancellationException can be swallowed;
- loop completion returns Result.success();
- finally emits formats-updated notification while ids is nonempty.

Exact source: UpdateMultipleDownloadsFormatsWorker.kt 21-101.

Result is written before Download. A later Download refusal/failure can leave split durable state.
Historical review already placed that split state inside the same outcome root.

## Concrete failure

getFormats throws, Result write succeeds then Download rejects, ownership CAS returns false, nested
cancellation occurs, or foreground promotion fails: the current control flow can still count progress
and/or converge to success/completion UI.

## Root boundary

Owns truthful item/batch outcome and same-refresh durable convergence.

Distinct from:
- BG-04 payload authority;
- BG-05 cancellation capability identity.

## Minimal safe correction

1. Await/observe foreground promotion.
2. Every requested id receives explicit outcome:
   success / exact already-satisfied skip / authority-refused / retryable failure / terminal failure /
   cancellation.
3. CancellationException remains cancellation.
4. Boolean CAS refusal is non-success unless exact fresh state proves already satisfied.
5. Result + Download format publication is atomic where one semantic refresh, or has compensating
   recovery that cannot success-label split state.
6. Progress derives from classified outcomes, not loop traversal.
7. Material failed/refused/unprocessed items prevent false full success.
8. Completion notification names only semantically justified successes.
9. Retryable residuals retain exact responsibility.
10. Successful siblings need not be rolled back merely because another item fails.

## Acceptance

- extractor failure.
- Result write failure.
- Result succeeds then Download publication fails/refuses.
- updateIfExecutionOwned()==false.
- cancellation during fetch/publication.
- foreground promotion failure.
- mixed success/failure.
- all failed.
- all success.
- later failure after earlier durable success.
- completion notification excludes unjustified ids.
- no material failure produces false full success.

---

# BUG-FORMAT-BG-04 — pre-fetch Download snapshot launders newer authority into stale full-row publication

## Exact production chain

Worker reads d before external getFormats().
After fetch it rereads current, then copies only:
- status;
- executionId;
- issue fields
into old d and full-row publishes old d through updateIfExecutionOwned or updateWithoutUpsert.

Exact source:
- UpdateMultipleDownloadsFormatsWorker.kt 46-82;
- DownloadDao.kt updateIfExecutionOwned 697-715;
- updateWithoutUpsert 970-987.

The newer execution token proves current ownership only; copying it into stale d does not make d's
other configuration current.

## Concrete failure

During fetch a user/newer lifecycle changes type, format/preferences, path/container/incognito,
operation/retry generation, or execution. Old fetch returns, copies the new token/status, and can
republish older fields through a full-row update.

## Historical root separation

Do not merge with the old completed-notification stale-authority root.

db29f63 already has transitionFormatNotificationCandidateToProcessing():
- Saved-only;
- blank execution/operation;
- retryAttempt==0;
- no live owner;
- final snapshot CAS.

DownloadFormatNotificationAuthorityProductionWiringTest covers Active/PostProcessing/new Queued/deleted/
eligible Saved/mixed/refusal cases.

BG-04 is the worker external-fetch publication path.

## Minimal safe correction

1. Fetch result is data, not row authority.
2. Never republish the pre-fetch full DownloadItem.
3. Reread exact current row immediately before publication.
4. Validate row/source identity and no incompatible newer generation/configuration.
5. Publish only format-owned fields through a narrow guarded DAO/transaction.
6. Never copy a newer execution token into stale payload.
7. If selected format depends on current type/preferences:
   - recompute from current authoritative config, or
   - reject when relevant config fingerprint changed.
8. Deleted/source-changed rows are never recreated.
9. Publication refusal feeds BG-03 non-success.
10. Result + Download convergence follows BG-03 atomic/recovery contract.

## Acceptance

- unchanged Saved row -> publishes.
- selected format/preferences edit during fetch -> newer intent survives.
- type edit -> no stale type/format overwrite.
- path/container/incognito changes -> narrow publication preserves them.
- newer Queued operation/retry -> not overwritten.
- newer Active execution -> old snapshot cannot borrow token.
- URL/source change -> refused.
- deleted row -> no recreation.
- barrier appears -> fail closed.
- refusal reported to BG-03.

---

# BUG-FORMAT-BG-05 — Cancel action lacks exact WorkRequest identity

## Exact production chain

Producer request:
- tag = "updateFormats".

Worker progress passes:
- UpdateMultipleDownloadsFormatsWorker::class.java.name

to NotificationUtil.updateFormatUpdateNotification().

Notification stores that string as "workTag".
CancelWorkReceiver calls cancelAllWorkByTag(workTag).

Thus current Cancel targets a tag the request does not have.

Changing to shared "updateFormats" would over-cancel concurrent batches.
Initial createFormatsUpdateNotification() has no Cancel action before first progress update.

Exact source:
- DownloadViewModel.kt 2954-2969;
- UpdateMultipleDownloadsFormatsWorker.kt 35-40, 85-86;
- NotificationUtil.kt 953-1007;
- CancelWorkReceiver.kt 8-14.

## Root boundary

Owns exact user cancellation capability.

Distinct from BG-02 durability and BG-03 cancellation outcome, but must interoperate with them.

## Minimal safe correction

1. Cancel capability carries exact WorkRequest UUID.
2. Receiver cancels exact UUID, not class/shared tag.
3. Initial foreground notification already contains exact Cancel.
4. Progress updates preserve same UUID.
5. PendingIntent capability identity cannot alias concurrent batches; exact request identity must
   participate in action/data/request-code identity, not only extras.
6. Stale old notification cannot cancel newer batch.
7. Missing/malformed UUID fails closed.
8. Worker cancellation flows to BG-03 cancellation, not success notification.
9. BG-02 retry generation binds the live capability to the actually executing request UUID.

## Acceptance

- cancel before first progress.
- cancel after progress.
- concurrent A/B: cancel A preserves B.
- stale A notification after B exists -> B unaffected.
- malformed UUID -> no broad cancel.
- old retried request capability cannot cancel replacement.
- real WorkManager target reaches cancelled state where feasible.
- cancelled incomplete work is not success-labelled by BG-03.

---

# BUG-INCOGNITO-01 — all-incognito aggregate is implemented as any-incognito

## Exact production chain

DownloadViewModel.areAllProcessingIncognito():
- all Processing: incognitoCount > 0;
- selected ids: selected incognitoCount > 0.

DAO only counts incognito=true rows.

DownloadMultipleBottomSheetDialog:
- calls result allIncognito;
- alpha=255 when true;
- uses icon alpha as the branch deciding whether click disables or enables incognito.

DownloadWorker History persistence is per-row:
- if (!downloadItem.incognito) { ... persist History ... }

Exact source:
- DownloadViewModel.kt 3136-3150;
- DownloadDao.kt 1214-1224;
- DownloadMultipleBottomSheetDialog.kt 385-398, 525-549;
- DownloadWorker.kt 3470 onward.

## Concrete privacy failure

[A=true, B=false]:
- count>0 => UI says aggregate/all incognito active;
- B remains false;
- B's successful download may persist History.

The worker's false-row History behavior is the correct downstream control proving the UI aggregate is
false authority; do not "fix" by suppressing History irrespective of row flag.

## Root boundary

Owns privacy aggregate truth and toggle authority.

## Minimal safe correction

1. all=true iff candidate set nonempty AND every exact candidate is incognito=true.
2. Selected ids: evaluate exact currently relevant selected Processing candidates.
3. No explicit selection: evaluate exact current Processing set.
4. Empty set -> false.
5. Mixed set -> fail-closed non-all presentation unless a separately designed tri-state UI exists.
6. Enable from mixed/non-all sets exact chosen rows true.
7. Disable only from freshly established all-true state.
8. Do not treat stale icon alpha as durable truth if selection/list can change; recompute or keep state
   synchronized at action boundary.
9. Preserve per-row worker contract: true suppresses History, false permits ordinary History.
10. Do not reopen unrelated historical log/incognito roots.

## Acceptance

- [true,true] -> all=true; disable exact rows.
- [false,false] -> all=false; enable exact rows.
- [true,false] -> all=false; enable makes both true.
- selected subset ignores unselected rows.
- no selection evaluates all current Processing.
- empty -> false.
- row leaves/deletes before action -> no unrelated mutation.
- selection changes -> current aggregate used at action.
- true row -> no History; false row -> ordinary History control.
- mixed set is never represented as full privacy.

---

# Cross-root implementation dependency

Canonical roots remain distinct. No count merge is authorized.

## Preferred Wave FMT-PRODUCER: BG-01 + BG-02

They meet at continueUpdatingFormatsOnBackground().
Exact captured bundle identity and durable WorkManager handoff should be established at one coherent
producer transaction boundary.

Independent closure remains two verdicts:
- BG-01 exact lifecycle target;
- BG-02 durable acceptance/recovery.

## Preferred Wave FMT-PUBLICATION: BG-04 + BG-03

BG-04 defines what the worker is allowed to publish after external fetch.
BG-03 defines truthful outcome for that publication.
Fixing either without the other leaves an immediately adjacent correctness hole.

Independent closure remains two verdicts.

## Preferred Wave FMT-CANCEL: BG-05

Run after or with the worker-outcome wave only when scope remains reviewable.
Exact cancellation must feed BG-03 truthful cancellation semantics.

## Preferred Wave INCOGNITO: BUG-INCOGNITO-01

Independent narrow privacy wave.

# Prompt readiness disposition

These six correction boundaries are concrete enough to author focused implementation prompts without
repeating broad root discovery.

Do not persist those implementation prompts while BUG-SCHEDULER-WINDOW-01 is still the active wave.
Their base must be the actual independently reviewed post-scheduler implementation SHA.

After scheduler closure:
1. verify exact published scheduler SHA;
2. revalidate only bounded delta against these six contracts;
3. if compatible, author FMT-PRODUCER immediately;
4. then FMT-PUBLICATION, FMT-CANCEL, INCOGNITO using exact post-wave SHAs.

This checkpoint does not mutate NEXT_CHAT.md, active scheduler prompt, implementation source, Master
Plan, or authoritative ledger.

INDEPENDENT EXECUTION: NOT EXECUTED
