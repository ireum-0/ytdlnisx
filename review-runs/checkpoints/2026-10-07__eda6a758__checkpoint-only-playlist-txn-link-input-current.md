# Checkpoint-only production reconciliation — playlist transaction and Share input roots

Date: 2026-10-07

checkpoint_kind: REPOSITORY_CHECKPOINT_ONLY_FINDING_RECONCILIATION
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: e0ff806b33f90e3665073e5d4fef7ba4439811f7
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b
production_source_changed: NO
private_prompt_changed: NO
canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8
repository_discovered_canonical_roots_total: NOT_YET_VERIFIED

## BUG-PLAYLIST-TXN-01 — VERIFIED_OPEN P2

Historical root evidence:
- first checkpoint-era playlist relation transaction finding: review commit f88ef4532eb96772e61ce0ed98136c424ed88151;
- exact-basis revalidation: a9c855b781bcae57f1b4c7676511eadc1f7061ab at 9c5191c3;
- historical disposition was OPEN P2. This root is absent from the 136-ID TASKS/TASKS_DELTA/current-review lower-bound population and is therefore checkpoint-only.

Current exact eda6a758 production still contains the semantic failure.

Producer -> carrier -> consumer -> recovery -> final effect:
- PlaylistViewModel.applyPlaylistSelections() accepts one confirmed add/remove selection and iterates playlist IDs.
- PlaylistRepository.insertPlaylistItems()/removePlaylistItems() publish each relation mutation independently under ordinary Restore admission.
- PlaylistRepository.deletePlaylist() executes deletePlaylistItemsByPlaylistId(), deletePlaylist(), then playlistGroupDao.deleteMembersByPlaylist() sequentially.
- PlaylistItemCrossRef has composite keys/indices but no foreign-key parent authority.
- No enclosing Room transaction or durable multi-step recovery owner covers the complete logical selection/delete operation.
- A failure/process death can therefore persist only a prefix; concurrent parent deletion can race stale cross-reference publication.

This is not BUG-HISTORY-01: that root owns History/playlist membership delete+Undo atomicity. It is not BUG-PLAYLIST-01 or BUG-PLAYLIST-DELETE-01 unless a later lineage checkpoint proves exact semantic equality. Current evidence supports a distinct playlist relation-transaction root.

Current correction boundary:
1. Execute one logical playlist deletion in one Room transaction across playlist-item refs, the playlist row, and playlist-group membership.
2. Execute one confirmed multi-playlist add/remove selection as one atomic relation mutation or an equivalently durable recoverable operation.
3. Revalidate existence/identity of both referenced parents at the mutation boundary; stale callers fail closed.
4. Foreign keys are optional defense-in-depth and do not replace the all-or-nothing semantic operation.
5. Preserve idempotent duplicate-membership behavior.

Closure rejection conditions:
- separate DAO calls remain externally interruptible;
- parent validity is checked only before, rather than at, relation mutation;
- only deletion or only selection is made atomic;
- a failure can still persist a proper prefix of the confirmed operation.

Process-death/restart matrix:
- before atomic begin: no effect;
- during transaction: rollback to pre-operation state;
- after commit: complete effect;
- any design with external phases must carry a durable operation identity and deterministic roll-forward/rollback on restart.

Concurrency/stale-generation matrix:
- playlist deletion vs stale relation insert;
- History deletion vs stale relation insert;
- concurrent multi-selection edits;
- stale UI selection after parent replacement/deletion.
All must resolve under one current-authority boundary without orphan/partial membership.

Required production-path tests:
- fault injection between the historical delete steps;
- multi-playlist add/remove failure at each former commit boundary;
- playlist deletion racing stale insert;
- History deletion racing stale insert;
- process recreation/retry idempotence;
- parent-missing/stale selection fail-closed behavior.

Current download canonical membership: NO.

## BUG-LINK-INPUT-01 — VERIFIED_OPEN P2

Historical root evidence:
- confirmed Share input admission blocker: review commit b226a58bac7c50010233e0f8cfa2e976418adc49;
- exact-basis revalidation: 9e6cbc775459111da26e21ca7b8f8519a9aadbc8 at aa1616a2;
- historical disposition was OPEN P2. This root is absent from the 136-ID lower-bound population and is checkpoint-only.

Current exact eda6a758 production preserves the failure:
- ShareActivity still maps ACTION_SEND/VIEW data through data.extractURL();
- LinkUtil.extractFirstUrl() returns the matched explicit URL, otherwise text.trim();
- ShareActivity uses that untyped inputQuery for Result lookup and createEmptyResultItem(inputQuery);
- the direct/background branch creates a DownloadItem and queues it durably;
- WebUrlInput still separately defines Extractor, SearchQuery, and UnsupportedExplicitScheme and normalizes valid scheme-less web addresses, but ShareActivity does not use that contract before durable admission.

Concrete failures:
- a valid scheme-less web address can be durably admitted without the typed HTTPS normalization;
- arbitrary no-URL text can be reinterpreted as a raw direct source instead of SearchQuery or explicit rejection;
- an unsupported explicit scheme accepted by LinkUtil's extraction syntax can cross the durable Download admission boundary even though WebUrlInput would reject it.

Producer -> carrier -> async acceptance -> consumer -> recovery -> final effect:
ACTION_SEND/ACTION_VIEW text
-> untyped inputQuery
-> ResultItem.url / DownloadItem.url
-> durable queue + WorkManager ownership
-> Download execution/native source consumption
-> later retry/recovery faithfully preserves the already-wrong semantic source classification.
The root is therefore pre-admission semantic authority; downstream handoff/recovery correctness cannot repair it.

Current correction boundary:
1. Route every Share external-input surface through one explicit typed extraction/routing result before durable Download mutation.
2. Preserve deliberate first-explicit-URL behavior for prose if desired, but distinguish supported extractor input, search text/no URL, unsupported explicit scheme, malformed input, and blank input.
3. Normalize accepted scheme-less addresses with WebUrlInput before storing source identity.
4. Direct/quick download must either resolve SearchQuery through the normal search/result-selection contract or reject/prompt explicitly; it must not store arbitrary search text as a raw producer source.
5. Reject unsupported/malformed input before a Queued Download row or worker handoff exists.
6. Keep downstream canonical media identity and WorkManager handoff roots separate.

Closure rejection conditions:
- validation happens only inside the worker after the wrong durable row exists;
- Share and normal URL entry retain different semantic routing rules;
- scheme-less input remains stored in a representation different from the accepted extractor identity;
- unsupported/search input can still create a queued Download row.

Process-death/restart matrix:
- no admitted row before typed validation;
- after accepted typed admission, restart must replay the same normalized semantic source;
- a rejected/search-only input must not become recoverable raw-download debt.

Concurrency/stale-generation matrix:
- onNewIntent/new Share input must not allow an older lifecycle job to publish the prior input after the newer intent becomes current;
- cached Result lookup must not substitute a row whose source semantics differ from the current typed route;
- queue admission must use the exact route result that was validated.

Required production-path tests:
- ACTION_SEND and ACTION_VIEW explicit HTTP(S);
- supported URL inside surrounding prose;
- deterministic multiple-URL selection;
- scheme-less supported address -> normalized durable source;
- arbitrary text -> search contract or explicit no-queue rejection;
- unsupported explicit scheme -> no durable Download/no handoff;
- blank/null -> no durable Download;
- new-intent replacement race;
- process recreation after accepted typed admission.

Current download canonical membership: NO.

## Inventory effect

Distinct checkpoint-only production roots classified by this checkpoint: 2.
- OPEN P2: 2
- CLOSED: 0
- aliases/rejected/tooling: 0

These roots do not change the fixed active download canonical eight or CANONICAL_P*.
The repository-wide semantic-root total remains NOT_YET_VERIFIED until checkpoint-only discovery is exhausted and all identity overlaps are reconciled.
