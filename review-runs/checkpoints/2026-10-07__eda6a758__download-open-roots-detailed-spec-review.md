# Active download blockers — detailed correction-contract review

Date: 2026-10-07

record_kind: CORRECTION_BOUNDARY_DETAILED_SPEC_REVIEW
record_status: FINAL
manual_review_run: NO

implementation_basis: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: 5121e6e8ace58cbdf29434feaebd1d8edb3a510d
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
independent_execution: NOT_EXECUTED

supplements:
- review-runs/checkpoints/2026-10-06__db29f63__remaining-download-blockers-correction-boundaries.md
- review-runs/checkpoints/2026-10-06__eda6a758__manual-v7-l6-repository-wide-reopen-final.md
- review-runs/checkpoints/2026-10-06__eda6a758__download-open-roots-historical-agent-error-hardening.md
- review-runs/checkpoints/2026-10-06__eda6a758__download-open-roots-ambiguity-closure-addendum.md

canonical_count_change: NONE
canonical_status_change: NONE
new_finding_ids: NONE
implementation_source_change: NONE
prompt_change: NONE
private_handoff_change: NONE
master_plan_change: NONE
ledger_change: NONE

scope:
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01
- BUG-FORMAT-BG-01
- BUG-FORMAT-BG-02
- BUG-FORMAT-BG-03
- BUG-FORMAT-BG-04
- BUG-FORMAT-BG-05
- BUG-INCOGNITO-01

spec_review_verdict: NEEDS_SIX_TARGETED_HARDENING_ADDITIONS

## Detailed verdict

The existing correction documents are materially stronger after the prior supplements and are sufficient
to preserve root identity, minimal correction direction, historical anti-regression rules, and most
implementation/closure boundaries.

A fresh exact-source review of `eda6a758...` nevertheless proves six remaining closure loopholes.
They do not establish new canonical roots. Each is a residual specification obligation inside an
already-open download root.

`BUG-SCHEDULER-WINDOW-01` and `BUG-INCOGNITO-01` need no additional correction-contract expansion
from this pass.

The six additions below are required before a future implementation can be independently accepted as
fully satisfying the current correction contract.

---

# 1. BG-01/BG-02 — capture/transition/handoff staging must participate in Restore mutation admission

## Exact production evidence

Current producer:

```
DownloadViewModel.continueUpdatingFormatsOnBackground()
-> ensureRestoreAdmission()
-> capture all Processing ids
-> choose selected ids
-> moveProcessingToSavedCategory()
-> build WorkRequest
-> enqueueUniqueWork()
```

Current `ensureRestoreAdmission()` only checks `RestoreGate.isRestoreInProgress()`.
It does not acquire `RestoreMutationAdmission.withOrdinaryMutation`.

`RestoreMutationAdmission` is the process-local exclusion boundary specifically intended to serialize
short durable mutations against Restore. It must not span network/native/long-running work.

Therefore a one-time gate check is not proof that Restore cannot acquire authority between:
- captured set C;
- exact successful transition set T;
- durable BG-02 handoff owner creation.

## Required short critical section

The correction must prove one coherent ordinary-mutation boundary, or equivalent durable protocol,
covering the correctness-critical short section:

```
capture/revalidate exact C and authorized S
-> exact guarded lifecycle transition
-> obtain exact successful T
-> derive W/O from T
-> durably stage exact semantic BG-02 handoff owner + payload
```

The boundary ends before external format extraction or other materially long-running work.

Architecture is not prescribed. Capture may occur before admission only if every relevant row is
revalidated under admission and T is derived exclusively from the authoritative inside-boundary state.

## BG-02 durable-carrier propagation requirement

If BG-02 reuses or extends `WorkManagerHandoffCarrier`, adding a row kind is not enough.

Current `WorkManagerHandoffRecovery.buildRequest()` is an explicit kind switch and throws for unknown
kinds. Existing kinds also have kind-specific:
- request reconstruction;
- authority validation;
- retry generation;
- accepted-carrier retention;
- stale retirement;
- terminal resolution;
- superseded-request cancellation.

Closure therefore requires the format-handoff kind to be integrated end-to-end with:

1. request reconstruction from exact durable T/W/O payload;
2. exact current-generation/stale-request validation;
3. retry UUID replacement under one semantic generation;
4. correct carrier retention until truthful BG-03 terminal outcome;
5. worker terminal/cancel retirement;
6. process-start recovery discovery;
7. Reset download-namespace quiescence/supersession;
8. stale pre-Restore carrier being unable to act on replaced/restored Download rows;
9. exact cancellation interaction with BG-05.

A generic carrier row that startup cannot reconstruct is not durable recovery ownership.

## Closure rejection

Reject BG-01/BG-02 closure if:
- Restore can become authoritative after the initial gate check but before T/handoff staging completes;
- background-owned row state can commit without exact durable handoff responsibility;
- a new format carrier survives a Download Reset and later acts on replaced/restored rows;
- recovery treats the new carrier as unknown/default and deletes or misclassifies it;
- accepted carrier retirement occurs before BG-03 has a truthful terminal outcome.

Required production race:
- latch after exact T is determined but before durable handoff staging;
- start Reset;
- prove either the ordinary mutation wins coherently through carrier staging or Reset wins before any
  background-format durable authority is exposed.

---

# 2. BG-03 — format extraction itself needs a typed semantic outcome

## Exact production evidence

Current worker consumes:

```
ResultRepository.getFormats(url): List<Format>
```

The yt-dlp path does:

```
val jsonArray = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
return parseYTDLFormats(jsonArray)
```

Malformed/non-JSON output is therefore collapsed to an empty List rather than a failure.

The worker then calls `DownloadViewModel.getFormat()`. Audio/video branches can fall back to generic
formats, so failed/unknown extraction can be converted into an apparently successful selected format.

## Required extraction outcome contract

The background refresh path must preserve enough typed information to distinguish at least:

- authoritative nonempty format set;
- authoritative empty result only if production semantics prove empty is a valid completed result;
- malformed/incomplete parser output;
- extractor/network/tool failure;
- cancellation;
- unsupported/refused source where applicable.

The implementation need not globally change every format API. A narrow typed adapter for the
background-refresh path is acceptable if it preserves the real extractor result.

## Empty-result rule

An empty `List<Format>` alone is not success authority.

Before closure the implementation/reviewer must establish whether this operation has any legitimate
authoritative-empty success case. If none is established, empty is non-success.

A generic fallback `Format` may remain a UI/configuration fallback where product semantics permit it,
but must not convert failed/unknown extraction into "formats refreshed successfully."

## BG-03 outcome mapping

Typed extraction outcomes feed the existing item algebra:

- valid extracted data -> continue to publication;
- malformed/incomplete/retryable extraction -> RETRYABLE_FAILURE where justified;
- terminal unsupported/failure -> TERMINAL_FAILURE or exact refusal;
- cancellation -> CANCELLED;
- authoritative empty -> only explicitly justified success/empty semantics.

Progress and completion UI derive from the classified outcome, never from loop traversal.

## Closure rejection

Reject closure if:
- malformed yt-dlp JSON still becomes empty formats then generic fallback success;
- empty NewPipe/yt-dlp result is success-labelled without semantic proof;
- tests inject only thrown exceptions and omit nonthrowing malformed/empty paths;
- extractor failure information is lost before BG-03 aggregation.

Mandatory cases:
- thrown extractor failure;
- malformed yt-dlp payload that currently parses to empty;
- explicit empty result;
- cancellation during extraction;
- valid nonempty result.

---

# 3. BG-03/BG-04 — every semantically linked Result projection must converge, not an arbitrary LIMIT 1 row

## Exact production evidence

Current worker does:

```
val r = resDao.getResultByURL(d.url)
...
r?.formats = d.allFormats
r?.apply { resDao.update(this) }
```

`ResultDao.getResultByURL()` is:

```
SELECT * FROM results WHERE url=:url LIMIT 1
```

The schema does not make URL unique.

Production explicitly recognizes multiple same-URL Result rows:
- `ShareActivity` branches when `existingResults.size > 1`;
- `DownloadViewModel.updateDownloadItemFormats()` and
  `updateProcessingFormatByUrl()` use `getAllByURL(url)` and update every matching Result row.

Therefore updating an arbitrary first Result row is not a complete same-refresh projection.

## Normative publication set

For the exact final current Download source URL at publication time, the worker must determine the
production-owned Result projection set under the same final publication authority.

At the current source contract that means all Result rows matching the exact current URL unless final
source semantics prove a narrower identity.

The review must not silently broaden this to unrelated canonical/equivalent URLs without source proof.

## Atomicity / truthful convergence

Where Download `allFormats/format` and Result `formats` are one semantic refresh:
- publish them in one Room transaction when feasible; or
- retain exact durable residual responsibility that prevents success until all required projections
  converge.

A Result row inserted before the final publication transaction must be classified by the exact final
query, not missed because a single row was captured before fetch.

## Closure rejection

Reject closure if:
- worker still calls a LIMIT 1 Result query for publication;
- two same-URL Result rows can end with different format projections after a reported successful refresh;
- Download publishes new formats while a required Result sibling remains stale and batch reports success;
- Result update failure is swallowed while Download success is retained without exact recovery debt.

Mandatory cases:
- zero Result rows;
- one Result row;
- two or more same-URL Result rows;
- one Result write failure;
- Result sibling inserted during external fetch before final publication.

---

# 4. BG-04 — the external refresh-authority image must itself be coherent

## Exact production evidence

The prior addendum correctly expands authority beyond the Download row to:
- `formats_source`;
- multiple format-selection SharedPreferences;
- command-template rows/order/content;
- `lastCommandTemplateUsed`.

However those sources are not one transactional store.

Current facts:
- AndroidX preference writes participate in `RestoreMutationAdmission`, one key mutation at a time;
- merge restore applies many preference keys in one SharedPreferences commit;
- merge restore later inserts command templates through `CommandTemplateRepository`;
- ordinary CommandTemplateViewModel mutations do not share a generation with SharedPreferences;
- format selection reads multiple preferences individually through `FormatUtil`;
- command selection reads Room template state separately.

A fingerprint assembled by sequentially reading these sources can itself be a mixed image that never
existed as one authoritative configuration.

## Coherent-image requirement

The final implementation must prove that the authority image used for publication corresponds to one
stable semantic configuration.

Acceptable strategies include, but are not limited to:
- a durable/global format-selection generation bumped by every material writer;
- short admission while capturing all applicable preference inputs plus a stable template generation;
- pre/post generation validation with retry until stable;
- another equivalent protocol that proves no material writer crossed the snapshot.

Simply hashing fields read one-by-one is not proof of coherence.

## Writer inventory requirement

Final-SHA review must enumerate all material writers, not only readers.

At minimum:
- ordinary preference UI writers;
- merge/reset settings restore;
- command-template insert/update/delete/import;
- command-template restore;
- any writer of extractor-selection preferences;
- any later producer of inputs consumed by `getFormats/getFormat`.

Every material writer must either:
- advance/participate in the generation/admission used by BG-04; or
- be provably detected by final revalidation before publication.

## Merge-restore race

A settings/template merge may create this sequence:

```
new format preferences committed
-> template import still in progress
-> worker snapshots mixed new-preference/partial-template image
-> worker publishes selection
```

That mixed image is not closure-safe merely because each individual field was "current" at the instant
it was read.

The final design must refuse, retry, or stabilize through such a writer interval.

## Closure rejection

Reject BG-04 closure if:
- final fingerprint is assembled from sequential mutable reads with no stable-generation proof;
- preference generation is tracked but command-template mutation is not;
- command templates are tracked but merge settings can change extractor/selection preferences outside the
  same detectable generation;
- a mixed restore image can be accepted as current publication authority.

Mandatory races:
- two relevant preference keys mutate across snapshot capture;
- command template changes between preference read and template read;
- merge restore commits relevant preferences while template restore is still mutating;
- stable unrelated preference mutation does not cause unnecessary format refusal.

---

# 5. BG-05/BG-02 — user cancellation needs durable semantic intent and asynchronous acknowledgement

## Exact production evidence

Current `CancelWorkReceiver` calls a WorkManager cancellation method and ignores the returned
`Operation`.

Existing `WorkManagerHandoffRecovery` already demonstrates the stronger repository pattern:
`cancelWorkByIdAndAwait()` waits on `Operation.result`.

BG-02 also requires failed enqueue to retain the same semantic batch while replacing the exact request
UUID. Therefore cancelling only one request UUID is not by itself sufficient to represent user intent
across an enqueue/retry race.

## Required cancellation protocol

For a live format batch, tapping Cancel must establish exact semantic cancellation authority before the
external WorkManager cancellation can be forgotten.

The exact representation is implementation-defined, but must prove:

1. exact live semantic batch/generation is identified;
2. durable state records that this semantic batch is user-cancelled / cancel-requested;
3. BG-02 recovery may not create a replacement request for that cancelled semantic generation;
4. exact current WorkRequest UUID is cancelled;
5. the cancellation `Operation.result` is observed;
6. process death after durable cancel intent but before WorkManager acknowledgement remains recoverable;
7. worker startup/final publication checks the cancelled generation and cannot success-label it;
8. terminal cancelled state retires the exact carrier/tombstone only when no late request can reclaim
   authority.

A stale notification capability for old request E1 must still fail closed against replacement E2.
A current notification for E2 cancels the semantic live batch and prevents E3 retry.

## BroadcastReceiver lifetime

If cancellation acknowledgement/recovery requires asynchronous work after `onReceive()`, production
must keep the receiver lifetime correctly (for example `goAsync()` with bounded completion) or hand the
action to an already durable owner before returning.

Fire-and-forget cancellation from `onReceive()` is not sufficient closure evidence.

## Closure rejection

Reject BG-05 closure if:
- receiver switches to `cancelWorkById(UUID)` but still ignores `Operation.result`;
- cancellation can race failed enqueue and BG-02 can publish a new UUID for the same user-cancelled batch;
- process death after user action loses all cancellation responsibility;
- stale E1 cancels E2 by semantic alias;
- current E2 cancellation permits E3 automatic retry;
- worker cancellation is later reported as BG-03 success.

Mandatory races:
- cancel before first progress;
- cancel while enqueue acceptance is unresolved;
- cancel after accepted request;
- cancel concurrent A while B survives;
- stale E1 notification after E2 replacement;
- current E2 cancel during retryable worker failure;
- process death after durable cancel intent before WorkManager acknowledgement.

---

# 6. BUG-SCHEDULER-RESTORE-01 — scheduler duplicate-key semantics must match the exact final restore image

## Exact production evidence

`BackupRestoreParser.validateSettings()` validates items individually and does not reject duplicate
preference keys.

Both merge and reset publication iterate the settings list into one SharedPreferences editor. For
duplicate keys, later editor writes determine the final persisted value.

The prior effective-image addendum correctly requires validation/convergence over:

```
(use_scheduler, schedule_start, schedule_end)
```

but does not yet define how duplicate scheduler keys in one restore payload become that effective image.

## Required duplicate-key rule

Before scheduler-domain validation and external publication, the implementation must do one of:

1. reject duplicate occurrences of any scheduler-domain key; or
2. canonicalize them with exactly the same deterministic ordering semantics as the final preference
   publication, then validate/converge only that canonical final value.

It is not safe to:
- validate the first occurrence but persist the last;
- construct scheduler transition E from one occurrence while SharedPreferences ends with another;
- run external effects per duplicate item.

The chosen rule must apply consistently to merge, reset, persisted recovery, and typed restore input.

## No-op / unrelated settings rule

A merge payload containing settings but no scheduler-domain key must not spuriously create a new
scheduler generation solely because unrelated settings were restored.

A reset payload may intentionally change scheduler effective values through removal/default semantics;
that complete reset image remains subject to the existing effective-image contract.

## Mandatory cases

- duplicate `schedule_start`, both valid and different;
- duplicate `schedule_end`;
- duplicate `use_scheduler`;
- duplicate where one occurrence is malformed;
- settings payload with no scheduler keys;
- merge partial scheduler payload;
- reset payload omitting scheduler keys and therefore reaching defined reset defaults.

The final persisted scheduler image, durable transition image, AlarmManager owner, and WorkManager
fallback owner must all agree exactly.

---

# Root-by-root adequacy after this review

## BUG-SCHEDULER-WINDOW-01

Current correction contract is sufficient. No additional ambiguity found in this pass.

It already fixes the key semantic contradiction:
- minute-inclusive membership;
- external END at first instant after inclusive end minute;
- start==end exactly one active minute;
- no `nextEnd` in the past while `contains(now)==true`.

## BUG-SCHEDULER-RESTORE-01

Adequate only after applying addition 6 on top of the existing effective-image addendum.

## BUG-FORMAT-BG-01

Adequate only after addition 1 makes exact transition/handoff staging atomic against Restore authority.

## BUG-FORMAT-BG-02

Adequate only after addition 1's end-to-end recovery-kind integration and addition 5's durable
cancellation interaction.

## BUG-FORMAT-BG-03

Adequate only after addition 2 preserves extractor semantics and addition 3 closes all required Result
projections.

## BUG-FORMAT-BG-04

Adequate only after addition 3 closes Result sibling publication and addition 4 proves the external
authority snapshot is coherent rather than merely comprehensive.

## BUG-FORMAT-BG-05

Adequate only after addition 5 closes cancellation acknowledgement/retry/process-death semantics.

## BUG-INCOGNITO-01

Current correction contract is sufficient. No additional ambiguity found in this pass.

The existing truth table, empty/mixed behavior, action-boundary freshness, exact candidate set, and
per-row History downstream contract are adequate.

---

# Verification obligations retained

This is a specification review, not source execution.

Future closure still requires:
- exact final source-semantic review;
- production-path tests, not helper-only tests;
- deterministic fault injection at the durable/external boundaries named above;
- process-death/recovery evidence for BG-02, BG-05, and Scheduler-Restore;
- sibling/mixed-item tests for BG-03/BG-04;
- exact WorkManager request identities and stale-generation races;
- independent execution where required by the governing review protocol.

Tests that encode a contradictory product contract do not override the source-semantic review.

---

# Final disposition

After this detailed pass, the correction-document set is **not yet fully complete without these six
additions**, but the remaining gaps are bounded and belong to existing roots.

No new finding, severity change, canonical count change, implementation source change, prompt change,
private handoff change, Master Plan change, or ledger change is authorized by this review.

Once this checkpoint is included as part of the review contract, the eight active download blockers are
specific enough for focused implementation waves without another broad concretization pass, unless final
source changes introduce materially new semantics.

INDEPENDENT_EXECUTION=NOT_EXECUTED
