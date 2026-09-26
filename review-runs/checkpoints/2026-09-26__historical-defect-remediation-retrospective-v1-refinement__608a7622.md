# Historical defect/remediation retrospective v1 — adversarial refinement

review_parent_sha: `608a7622a68344b7effc3aabec396bc67bee4dcf`
checkpoint_kind: RETROSPECTIVE_REFINEMENT
canonical_status_change: NONE
canonical_count_change: NONE
governance_modified: NO
production_source_modified: NO

This checkpoint refines the proposals recorded in:
`review-runs/checkpoints/2026-09-26__historical-defect-remediation-retrospective-v1__6b69cb73.md`
(blob `7a9a1738fada84da904bd2483c539c54ca56990c`).

The refinement tests each proposal against:
- current Review Checklist v6 blob `7b553328dfcd9941d783658f49ecb16c71b98c56`;
- current private Review Protocol blob `76cfd81cc7d28e01d669bcca24beaf4ecf945036`;
- representative historical false positives and same-root residuals;
- current post-snapshot reviews through `review/remediation@608a7622a68344b7effc3aabec396bc67bee4dcf`.

The objective is to avoid duplicate rules and avoid turning ordinary fixes into ceremony.

## Executive refinement

The evidence supports:

### Genuine checklist-content additions
1. explicit thread-affinity / blocking-wait review for locks that can span slow work;
2. targeted cross-feature propagation search when a transformation/equality rule is proven invalid under an upstream identity contract.

### Checklist/coverage clarification
3. a triggered conditional module or blocker-relevant semantic-pattern propagation obligation must not be deferred merely because another blocker already makes the run NOT_CLEAN or because its related lens is scheduled for a later DEEP rotation.

### Stable Review Protocol / implementation-prompt improvements
4. enumerate all producers/writers/import/restore/migration/default/recovery materializers as well as consumers when a persisted/authority contract changes;
5. when Module F is triggered, require an old-state seed/upgrade regression or a concrete NOT_APPLICABLE proof;
6. when a patch leaves UNKNOWN/quarantine/hold/debt/unresolved state, require the prompt to name its positive convergence owner and terminal outcomes.

No historical lens ranking and no historical-total effectiveness score is justified.

---

## 1. Thread-affinity / blocking-wait rule — genuine checklist gap

Historical evidence:
`CLEANUP-MAIN-THREAD-BLOCK-01` at checkpoint blob
`1fe8905d227f4e7a3e2e39b07283610be78bccf0`.

The correction introduced/widened a synchronization boundary. A synchronous UI preference path could enter `runBlocking` and wait on a mutex held across DB/cache deletion.

The checkpoint explicitly identified an ANR-class gap and proposed thread-affinity review.

Exact current v6 contains mutex/lock-order review but contains no explicit occurrences of:
- `thread affinity`;
- `main thread`;
- `runBlocking`;
- `ANR`;
- `UI thread`;
- `blocking wait`.

### Proposed vNext wording

> **Thread-affinity and blocking wait.** For every new or widened lock, mutex, lease, transaction wait, or synchronous bridge that can span suspension or DB/filesystem/network/native work, enumerate reachable synchronous callers and their execution thread/dispatcher. A main/UI-thread path must not wait on an unbounded or materially slow owner. Require asynchronous or bounded handoff, or exact proof of a platform-safe bounded wait. When material, test the real synchronous caller while the competing production owner holds the boundary.

False-positive guard:

> Do not flag synchronization merely because a lock exists. Require a reachable synchronous caller plus a competing hold interval that can include materially slow/blocking work.

Disposition: **retain for versioned checklist adoption.**

---

## 2. Invalid identity transformation propagation — genuine checklist gap

Historical evidence:
`HISTORY-CONTENT-AUTHORITY-ALIAS-01` in checkpoint blob
`2b00a7a3e772b3fb6cfc554fc92eb914e14c6917`.

Prior LocalAdd review had already established exact provider/document identity semantics. History deletion independently lowercased provider authority and widened destructive identity.

The checkpoint explicitly says why the earlier review missed it:
- review remained inside LocalAdd's producer/admission graph;
- the newly established invalid transformation was not propagated to sibling destructive/reference identity implementations.

It explicitly proposed a targeted repository-wide search for the same unsafe transformation.

v6 consumer-closure starts from a changed semantic producer. That does not fully cover independent sibling implementations of the same invalid normalization/equality pattern.

### Proposed vNext wording

> **Invalid-transformation propagation.** When a finding establishes that an identity transformation is invalid under the authoritative namespace contract — including case-folding, trimming, prefix/substring interpretation, path/basename reinterpretation, partial-tuple collapse, or equivalent normalization — perform a targeted repository-wide search for the same transformation wherever it participates in destructive, deduplication, retained-reference, cleanup, admission, publication, or recovery identity. Classify each material hit against its own upstream namespace semantics before closing the review.

False-positive guard:

> The search discovers candidates; it does not make the transformation globally invalid. Each hit must be independently classified under its own upstream equality/authority contract.

Disposition: **retain for versioned checklist adoption.**

---

## 3. Triggered module / semantic-pattern coverage must not wait for later DEEP rotation

### Current evidence — BUG-BACKUP-11

L6 DEEP final blob:
`b1c4ab391d3a1c15c446aed3234b77350cf039d3`.

It explicitly states that prior L1/L2/L3 DEEP passes focused on Terminal dispatch/recovery/identity while L6 remained BASELINE, and the producer graph had not been followed outward into backup/restore.

The later L6 pass found provisional P2 `BUG-BACKUP-11`:
a serialized `content://` locator can be restored as executable `command_path` without reconstructing destination-side persisted URI authorization.

The checkpoint records:
- Checklist gap: none.
- Proposed checklist change: none.
- existing L6/Module C/Module H rules already require the traversal.

### Current evidence — BUG-CANCEL-02 Terminal subcase

L4 DEEP final blob:
`a8492bf3d4e70713c95ee9e1b66dd2414953a98e`.

It confirmed a Terminal subcase of existing P2 `BUG-CANCEL-02`:
durable cancellation can win after native execution while stale worker publication remains able to create provider/file output because cancellation/native convergence is not equivalent to publication-owner quiescence.

The final retrospective says directly:
- earlier provider-identity/platform reviews did not expose the boundary between native execution ownership and post-native publication ownership;
- Checklist gap: none;
- “The miss was lens coverage, not checklist design.”

### Refined rule

A blocker-relevant triggered conditional module or already-established semantic-pattern propagation obligation must be completed in the **first review of the relevant semantic change**, irrespective of:
- the selected primary DEEP lens;
- another blocker already making the verdict NOT_CLEAN.

Primary DEEP rotation remains an extra blind-spot search mechanism. It must not be permission to postpone a known triggered module or a known cross-feature semantic pattern that directly applies to the changed root/contract.

Efficiency guard:
- do not execute unrelated conditional modules;
- scope remains the changed root/contract and actual producer/consumer/effect graph;
- NOT_APPLICABLE is allowed with concrete trigger proof.

Disposition: **retain as checklist/protocol coverage clarification.**

---

## 4. Producer closure in implementation prompts — operationalize existing v6 Module H and contract closure

Current Terminal03 initial remediation prompt blob:
`93f94c1102b7d5f0327f2d9ca8563ca0c33cc828`.

It covered:
- normal Folder picker;
- configured `command_path`;
- planner/worker/publication;
- process death/restart.

It did not enumerate backup/import as a producer of executable `command_path`. “Restore” appeared only as an affected-test category.

Current provisional BUG-BACKUP-11 proves that an alternate producer can be semantically unsafe even when the later Terminal materializer and durable consumers are correct.

Current v6 Module H already requires “every write/import path” and every consumer. Current Review Protocol prompt-preflight item 7 emphasizes consumers.

### Proposed Review Protocol preflight refinement

For changes to:
- persisted executable configuration;
- identity/provenance;
- ownership/revocation;
- persistence format;
- authority-bearing external representation;

require inventory of:

> all relevant production producers/writers/import/restore/migration/default/recovery-materialization paths, validation/type boundaries, consumers, and final correctness-relevant effects.

This is not a generic whole-repository audit for simple local changes. Trigger only on a material shared semantic contract.

Disposition: **retain as protocol/prompt improvement; do not duplicate as a broad new v6 invariant.**

---

## 5. Module F old-state regression — operational hard gate

Current `BUG-TERMINAL-03` persisted-generation evidence:
checkpoint blob `ff226e66b0bfb5d7400a3dd0ff96982426a8bb72`.

The current-format writer correctly binds provider authority into Terminal row/carrier/fingerprint. But old nonterminal rows can lack that field and later borrow a newer mutable provider.

The checkpoint explicitly says current regressions create rows through the new writer; they do not seed an old persisted row/carrier missing provider metadata.

v6 Module F already requires oldest relevant supported nonterminal state and startup/retry/recovery through new strict helpers.

### Proposed prompt-preflight hard gate

When Module F is triggered, an implementation prompt is incomplete unless it contains either:

1. an explicit old-state fixture + upgrade/startup/retry/recovery regression; or
2. a concrete `NOT_APPLICABLE` proof that no supported pre-change persisted state can reach the changed boundary.

The old-state fixture must not pass through the new writer when doing so would populate the newly required field and erase the compatibility condition being tested.

Disposition: **retain as protocol/preflight improvement; no new Module F semantic text required.**

---

## 6. Safety fence versus convergence owner — prompt operationalization

Historical `BUG-OUTPUT-01` evidence shows exact UNKNOWN/quarantine identity can prevent unsafe duplicate replay while still lacking any finite convergence owner.

Representative blobs:
- `529e9227808c4dd2e061468fa62ef32be748d0f9`
- `b1c13bc46a657d7791934a1177e93b3aef51fe19`

The historical review explicitly distinguishes:
- safety fence;
- correctness closure / convergence.

Current v6 already contains this semantic rule through recovery liveness, discovery closure, post-commit consistency and retry/reconfigure closure.

### Proposed prompt-preflight field

Any remediation introducing or retaining UNKNOWN/quarantine/hold/debt/tombstone/unresolved states must name:

- durable carrier;
- discovery/wakeup owner;
- finite convergence outcomes;
- process-death behavior;
- retry/reconfigure meaning for old authority;
- explicit product contract if unresolved state is intentionally permanent/user-gated.

A patch may be valuable as a safety-only improvement, but absent a convergence owner it must not be presented as a closure candidate.

Disposition: **retain as prompt operationalization; reject a duplicate generic v6 rule.**

---

## 7. Do not introduce a universal “closure packet” artifact

The first retrospective proposed a compact root closure matrix.

Adversarial review changes the recommendation:

- **do not** require a new repository artifact or extra phase for every fix;
- incorporate the required fields directly into existing implementation prompt planning/preflight only when a material semantic contract is changed.

The existing Review Protocol already requires current-source architecture reconstruction, preserved contracts, failure boundaries, concurrency/recovery obligations and regression contracts.

Therefore the correct improvement is to sharpen specific preflight fields (producer closure, Module F old-state fixture, unresolved-state convergence), not create a new mandatory document type.

Disposition: **universal extra artifact rejected.**

---

## 8. Historical false-positive / duplicate-root lessons require enforcement, not new rules

Historical false positive:
`BUG-LOWQUALITY-SAVED-01`.

Original blob:
`f46c1ae7a0ac7ea1aeb24683c85c7d27779d0a7b`.

Correction blob:
`84f03476e2ef3590f3d4f11d10543ca4e865a93f`.

The original review stopped at the apparent `updateToStatus(Saved)` path. Exact production tracing showed `updateToStatus` special-cased Saved, called `moveToSaved()`, and refreshed the low-quality ledger.

Historical duplicate root:
`BUG-HISTORY-UNDO-PLAYLIST-01` was later reconciled as a subcase of the already-counted F17 History delete/Undo atomicity root.

Current Review Protocol already requires:
- candidate rejection only after production-path proof;
- correctness before attribution;
- duplicate/alias/root-count reconciliation before a repair prompt;
- one semantic root counted once.

Disposition: **no new generic rule. Enforce current rules.**

---

## 9. Exact-final execution fragmentation requires enforcement, not another rule

Historical examples repeatedly separate:
- source-fixed;
- harness-fixed;
- exact-final execution not verified;
- exact-final execution closure.

Representative final closure:
`BUG-DUPLICATE-ADMISSION-01`,
blob `bd7766ef64ad025009f5c80d7c9fa558fef9610e`.

Current Review Protocol already has strong exact-final-SHA execution, nonzero test execution, harness-vs-production, async quiescence, rerun and stale-test-contract rules.

Disposition: **no new generic execution rule. Prefer completing the existing exact-final execution packet in the original implementation wave when infrastructure allows.**

---

## Versioned-governance recommendation

Do not edit `REVIEW_CHECKLIST_V6_OPERATIONAL.md` in place.

Historical convention:
- v4, v5 and v6 coexist as immutable historical governing artifacts;
- v6 was introduced as a new file by commit `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

If governance adoption proceeds, create a new versioned checklist (v7 or equivalent) that preserves v6 and adds only:

1. thread-affinity / blocking-wait review;
2. invalid identity-transformation propagation;
3. explicit wording that triggered modules are executed when triggered even if verdict is already NOT_CLEAN and regardless of DEEP rotation;
4. optionally generalize semantic-contract closure from only the changed producer's consumers to alternate production **producers/writers** when they can supply the changed authority/value.

Separately revise stable Review Protocol prompt preflight for:
- producer/write/import/restore/migration inventory;
- Module F old-state seed or NOT_APPLICABLE proof;
- unresolved-state convergence-owner field;
- triggered-module work cannot be deferred by lens-depth scheduling.

Finding-specific reproduction details must remain in checkpoints/prompts, not stable generic governance.

---

## Safety / scope

This refinement:
- changes no canonical finding count;
- changes no finding disposition;
- does not modify production source;
- does not modify Master Plan, ledger, checklist, private protocol or handoff;
- does not use historical lens effectiveness as a score;
- does not require future implementation agents to read the 764-rank corpus.

The corpus served as a one-time evidence base for method improvement.

Next governance action should be a versioned draft + second-pass conflict review before adoption.
