# Historical defect/remediation retrospective v1

review_parent_sha: `6b69cb73df93b978ae79b105b00bb00142c018ae`
checkpoint_kind: RETROSPECTIVE
canonical_status_change: NONE
canonical_count_change: NONE
production_source_modified: NO
governance_modified: NO

## Purpose

This checkpoint records a historical defect/remediation retrospective whose purpose is **not** to rank review lenses. It reconstructs recurring failure patterns across prior fixes and reviews so that future implementation-agent prompts and correctness-review procedure can prevent the same classes of residuals, missed consumers, false closure, and false positives.

The retrospective distinguishes:

1. a genuinely missing review/checklist rule;
2. an existing rule that was not operationalized early enough in implementation planning or test design;
3. a verification/harness closure problem rather than a production semantic defect;
4. duplicate/alias/count-reconciliation noise rather than a new semantic root.

No historical finding is reclassified by this checkpoint.

## Evidence basis

### Closed historical corpus

Authoritative closed reconstruction:

- branch: `review/lens-history-v1`
- closure HEAD: `00c6acd04a06a1620d97db9a00e2f1e50d4da4d3`
- frozen implementation/review snapshot: `25a554d1768f8d3cdd09a6e384a89d915eeeace4`
- corpus closure certification blob: `6a4dc2203014e3e37d6e584c9a60b11eab92a390`
- closure status: `PASS`
- history ranks: 764
- distinct frozen checkpoint documents: 763
- unaccounted frozen documents: 0
- historical total effectiveness: `NOT_VERIFIED`

All 80 effective lens-history ledger batches were screened for explicit finding attribution, disposition/status/count changes, residual/subcase evidence, and checklist-gap evidence. Candidate recurring families were then checked against representative original checkpoint contents rather than inferred from filenames or frequency alone.

### Current governance compared

- Review Checklist v6 adoption commit: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Review Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- private review protocol exact HEAD used for comparison: `98f78bb6a4d59fe105e72ac04a04d541e5c17be6`
- review protocol blob: `76cfd81cc7d28e01d669bcca24beaf4ecf945036`

### Post-snapshot/current corroboration

The historical corpus ends at the frozen snapshot above. Current evidence was used only as corroboration, not silently merged into the frozen corpus.

At current implementation `cf7374510decad9f308fdc3f1b528e731a3ea4f1`, later independent reviews include:

- L6 DEEP final: `review-runs/checkpoints/2026-09-26T065439Z__cf737451__manual-v6-l6-deep-final.md`, blob `b1c4ab391d3a1c15c446aed3234b77350cf039d3`
- L5 DEEP final: `review-runs/checkpoints/2026-09-26T070201Z__cf737451__manual-v6-l5-deep-final.md`, blob `7f2787121341b2b097dfad9cdb0e489e3d6066d9`

The L6 review independently confirmed a new provisional P2 `BUG-BACKUP-11`, while explicitly recording that prior L1/L2/L3 DEEP reviews had not followed the executable-configuration producer graph outward into backup/restore. That current example materially corroborates the historical pattern described below. This retrospective does not itself adopt or alter that finding's canonical registry status.

---

## Recurrent archetype A — local correction without whole-lifecycle closure

### Historical evidence

`BUG-CLEANUP-01` repeatedly survived or changed shape across successive implementation waves. Representative verified checkpoints include:

- `2026-09-13__a9586835__cleanup01-review-fix-residual.md` blob `005611c553b3d512be8b1e79aecfb5989c00a2ee`
- `2026-09-15T133900Z__a67cb4ff__9218b8d2__final-checkpoint.md` blob `f8b581783d16c38cce64c62b8762368601cea007`

The residual sequence includes, at different stages:

- durable scheduling debt without a same-process replay owner after enqueue/acceptance failure;
- authority/debt transitions split across multiple durable writes;
- point-in-time authority checks that do not cover the eventual destructive effect;
- partial destructive effects followed by exception handling that makes the same occurrence eligible again;
- retry that recomputes a broader/different target set because the committed destructive target was not durably frozen.

The 2026-09-15 final checkpoint states directly that a narrow mixed-carrier change did not close the broader root and that effect admission, destructive body commits, exception handling, and retry authority must be treated as one semantic transaction. It explicitly says diff-only review would have been insufficient.

### Why earlier fixes missed later residuals

**DIRECT/CROSS-CHECKED:** the checkpoints show successive corrections closing one failure frontier while another lifecycle frontier remained reachable.

**INFERENCE, strongly supported:** implementation boundaries were repeatedly narrower than the semantic operation. The unit of reasoning was often a helper/write/slot rather than the complete operation from authority acquisition through external/durable effects, outer catch, retry/re-entry, and recovery.

### Current governance assessment

Checklist v6 already contains the core rules needed for this class:

- persistence barriers and ignored-result audit;
- async request/acceptance/completion ordering;
- recovery semantic identity;
- recovery discovery/carrier-loss matrix;
- multi-ledger/process-death review;
- post-commit/final-result consistency;
- mutation-point authority;
- retry/reconfigure/restart inventory;
- cross-attempt/live-owner matrix;
- semantic-contract consumer/effect closure.

Therefore the main historical lesson is **not** “add another generic durability rule.” It is to operationalize these rules in the implementation prompt before coding.

### Agent/process improvement

For any remediation involving durable authority, recovery, destructive effects, retry, publication, or scheduler handoff, require a pre-code **root closure matrix** that names:

- authoritative producer/observation;
- durable carriers created before responsibility can be lost;
- external/durable effects and their ordering;
- synchronous and asynchronous failure points;
- outer catch/final result;
- retry/reconfigure/restart interpretation;
- process-death recovery owner;
- destructive target identity frozen for replay;
- all known same-root residuals from current handoff/checkpoints;
- exact regression scenario for each row.

A patch is not implementation-ready while a blocker-relevant row is materially unknown.

---

## Recurrent archetype B — current-format fix without persisted-generation compatibility

### Current exact evidence

`BUG-TERMINAL-03` is the clearest current example.

The first Terminal SAF remediation correctly made current provider authority typed and durable for newly created state. A follow-up then froze configured provider authority into the current-format Terminal command/row/carrier/fingerprint.

However `2026-09-26T061448Z__cf737451__manual-v6-terminal03-intermediate.md` blob `ff226e66b0bfb5d7400a3dd0ff96982426a8bb72` shows that old nonterminal Terminal rows/carriers created before the stronger representation can contain no original provider identity. After upgrade/restart, exact equality among the old row/carrier can still pass while planning later reads a newer mutable `command_path`, redirecting old intent from provider A to B.

The checkpoint states that existing regression tests created rows through the **new** `TerminalViewModel.insert()` path and did **not** seed an old nonterminal row/carrier lacking provider metadata.

### Why the residual was missed

**DIRECT:** the required v6 Module F rule already existed, but the regression design exercised only the new representation.

**DIRECT:** Module F was eventually triggered during independent review and found the persisted-generation blocker.

This is an execution/planning miss, not evidence that Module F's semantic rule was absent.

### Current protocol gap

Review-protocol prompt preflight item 7 requires shared-contract propagation to relevant consumers, but it does not explicitly force a **persisted-generation compatibility test packet** when a remediation strengthens durable identity/ownership.

### Proposed implementation-agent rule

Whenever a remediation changes a durable semantic representation, identity dimension, nonblank/nonzero requirement, CAS predicate, ownership encoding, scheduler carrier, or executable configuration:

1. enumerate all supported pre-change persisted generations/sentinels that may remain nonterminal;
2. seed at least the oldest/relevant pre-change state **without passing through the new writer**;
3. run upgrade/startup/retry/reconcile against the new code;
4. prove the old state either retains exact original authority, migrates deterministically, or fails closed without borrowing current mutable authority;
5. include this seeded-legacy regression in the implementation prompt before coding is considered complete.

This operationalizes existing v6 Module F rather than inventing a new semantic invariant.

---

## Recurrent archetype C — exact locator/identity without authority provenance

### Historical evidence

Multiple historical roots involved collapsing or transforming identity more aggressively than the underlying authority contract allowed:

- `BUG-LOCALADD-01`: basename/provider-scope/document-ID and later opaque-ID normalization subcases;
- `HISTORY-CONTENT-AUTHORITY-ALIAS-01`: provider authority case-normalization widening destructive identity;
- `BUG-HISTORY-DUPLICATE-IDENTITY-01`: generic HTTP(S) fragment-insensitive identity subcases;
- `BUG-METADATA-02`: fragment-sensitive source identity;
- later provider-backed archive/destination roots.

A verified F20 correction checkpoint requires treating provider document IDs as exact opaque payload and explicitly forbids trim/case-fold/path-normalize/prefix interpretation before provider-scoped identity comparison.

### Current corroboration — BUG-BACKUP-11

The current L6/L5 DEEP reviews add a stronger version of the same general lesson:

- the exact restored `content://` string can be preserved;
- yet that exact locator is semantically unauthorized on the destination installation if the corresponding persisted URI grant was never established there.

The L5 review states the distinction directly:

- provider URI string = locator/representation;
- persisted URI permission = external authorization/provenance.

Exact string equality therefore does not prove executable authority.

### Why prior review missed the current backup root

The current L6 final says directly:

> L1/L2/L3 DEEP runs focused on Terminal dispatch/recovery/identity. L6 had remained BASELINE and the producer graph had not been followed outward into app-data backup/restore.

This is not a v6 checklist gap; the same checkpoint records none because Module C, Module H and cross-feature propagation already require this traversal.

### Agent/process improvement

When a persisted/executable identity changes or is newly made authoritative, implementation planning must enumerate **all producers/writers**, not only consumers:

- normal UI/settings writer;
- import/backup restore;
- migration;
- default/upgrade initializer;
- recovery/reconstruction;
- test fixtures or compatibility adapters where they represent production state.

For each producer, distinguish:

- representation/locator;
- namespace identity;
- external grant/capability/provenance;
- destination-local versus portable dimensions;
- validation/admission boundary.

### Proposed protocol wording direction

Expand prompt-preflight “Shared-contract propagation” from “all relevant production consumers” to **all relevant production producers/writers/importers/migrations and consumers/final effects**.

v6 Module H already says to enumerate every write/import path and every consumer; the protocol should project that requirement into implementation prompts.

---

## Recurrent archetype D — safety fence mistaken for convergence

### Historical evidence

`BUG-OUTPUT-01` has a long history of progressively stronger recovery/fencing while the root remained open.

Representative verified checkpoints:

- `2026-09-09T072700Z__2e12975a__checkpoint.md` blob `529e9227808c4dd2e061468fa62ef32be748d0f9`
- `2026-09-09T094700Z__67c7a58a__checkpoint.md` blob `b1c13bc46a657d7791934a1177e93b3aef51fe19`

The system eventually acquired exact durable `QUARANTINED_UNKNOWN` identity and blocked duplicate replay. But independent review still found no production convergence owner that could discover/rollback/idempotently reconcile the external effect. Reconfigure could deterministically return to the same unresolved fence.

The later checkpoint explicitly states:

- quarantine or retry blocking alone is a safety fence, not correctness closure;
- retry/reconfigure must resolve, rollback, transfer old authority, or create a semantically independent operation under a defined duplicate-risk contract;
- deterministic re-entry into the same unresolved fence is not recovery convergence.

### Current governance assessment

v6 already encodes this via recovery liveness, discovery closure, retained unresolved carrier semantics, retry/reconfigure inventory and consumer/effect closure.

### Agent/process improvement

Every remediation that introduces `UNKNOWN`, quarantine, hold, tombstone, pending debt, or “fail closed” state must name a **finite convergence owner** in the implementation prompt:

- who wakes it;
- what exact evidence it consumes;
- which terminal outcomes it can reach;
- what happens after process death;
- what user retry/reconfigure means to the old authority;
- whether permanent unresolved state is an explicitly accepted product contract.

If no convergence owner exists, the prompt must classify the patch as a safety improvement / incomplete remediation rather than a closure candidate.

No new generic v6 rule is proposed here; enforce existing rules earlier.

---

## Recurrent archetype E — cross-feature producer/consumer fan-out deferred by lens-depth policy

### Current exact evidence

At `cf737451...`, L1/L2/L3 were already DEEP, while L6 remained BASELINE. A later L6 DEEP pass followed:

`Folder picker -> SharedPreferences -> backup serializer -> restore parser/writer -> Terminal materializer -> durable dispatch -> planner -> provider publication`

and confirmed provisional P2 `BUG-BACKUP-11`.

The L6 checkpoint explicitly says the earlier reviews missed it because the producer graph had not been followed into backup/restore, despite existing checklist rules already requiring that kind of traversal.

### Process weakness

The current per-SHA “L1-L6 BASELINE + one DEEP, then rotate” policy is useful for blind-spot accumulation, but it can defer a **triggered conditional module** or contract-fan-out boundary until a later repeated-SHA review.

That is acceptable for optional exploratory depth; it is not ideal when the changed contract itself triggers Module C/F/H or a full producer/consumer closure obligation.

### Proposed review-workflow rule

A triggered conditional module is **depth-independent and mandatory in the first review of the relevant SHA/semantic contract**.

- DEEP lens rotation remains an additional blind-spot search mechanism.
- It must not defer the complete execution of a blocker-relevant triggered module.
- If Module C/F/H or semantic-contract consumer closure is triggered, perform the required producer/writer/consumer/final-effect traversal immediately even if the associated lens would otherwise be BASELINE.
- A review may remain NOT_CLEAN for another blocker, but it should not knowingly leave a triggered contract-fan-out surface unexamined merely because its lens is scheduled for a later DEEP turn.

This is primarily a review-protocol/coverage-policy improvement; v6 already states that triggered modules are mandatory.

---

## Recurrent archetype F — fix-induced thread-affinity/blocking regression

### Historical evidence

`2026-09-14T114015Z__09058d57__checkpoint.md` blob `1fe8905d227f4e7a3e2e39b07283610be78bccf0` confirmed new P1 `CLEANUP-MAIN-THREAD-BLOCK-01`.

The corrected cleanup authority introduced/widened a mutex boundary. A synchronous preference UI consumer called a `runBlocking` configuration path that could wait for `destructiveEffectMutex` while a worker held that mutex across DB/cache deletion. The checkpoint identified ANR-class unresponsiveness and explicitly recorded a checklist gap.

The checkpoint's proposed change was:

- for every newly introduced/widened mutex/lease spanning suspension or slow I/O, enumerate synchronous callers and thread affinity;
- if a UI/main-thread caller can wait, require async/bounded handoff or proof the wait cannot approach ANR threshold;
- add deterministic production-wiring coverage.

### Current v6 comparison

Exact v6 blob `7b553328...` contains mutex/lock-order rules but contains no explicit occurrences of:

- `thread affinity`
- `main thread`
- `runBlocking`
- `ANR`
- `UI thread`
- `blocking wait`

This remains a real checklist-evolution candidate, unlike the preceding archetypes that are largely already covered semantically.

### Proposed v6 addition

Add a concurrency/thread-affinity clause:

> For every new or widened lock/mutex/lease/transaction wait that can span suspension, DB/filesystem/network/native work, enumerate all synchronous callers and their execution thread/dispatcher. A main/UI-thread path must not wait on an unbounded or slow owner. Require asynchronous or bounded handoff, or exact proof of a platform-safe bounded wait. Include a regression that holds the competing production owner while invoking the real synchronous caller.

False-positive guard:

- do not flag a lock merely because it exists;
- require a reachable synchronous caller plus a competing owner whose hold interval can include materially slow/blocking work.

---

## Recurrent archetype G — false positives and duplicate-root count churn

### Historical false positive

`BUG-LOWQUALITY-SAVED-01` was initially confirmed, then retracted.

- original checkpoint: `2026-09-10__c2294c87__low-quality-saved-authority.md`, blob `f46c1ae7a0ac7ea1aeb24683c85c7d27779d0a7b`
- correction: `2026-09-10__c2294c87__low-quality-saved-false-positive-correction.md`, blob `84f03476e2ef3590f3d4f11d10543ca4e865a93f`

The false-positive premise assumed `DownloadViewModel.updateToStatus(Saved)` delegated to a generic status writer. Exact production tracing showed the method special-cased Saved, called `moveToSaved()`, and refreshed the linked low-quality ledger. The review had stopped one call boundary too early.

### Historical duplicate root

`BUG-HISTORY-UNDO-PLAYLIST-01` was initially counted as a new P2, then reconciled as an evidentiary alias/subcase of the already-counted F17 History delete/Undo atomicity root.

### Current governance assessment

The current protocol already contains strong countermeasures:

- correctness before attribution;
- candidate rejection only after production-path proof;
- explicit duplicate/alias/root-count reconciliation before issuing a repair prompt;
- count one semantic root once;
- reconcile false positives explicitly.

Therefore no new checklist rule is justified from these examples. They are evidence that the current rules should be enforced, not duplicated.

### Review-agent discipline

Before promoting a candidate:

1. trace the real caller through at least the first semantic repository/service boundary rather than infer behavior from a UI/helper name;
2. search canonical inventory for same invariant/authority chain;
3. record the candidate in strongest concrete form;
4. reject or merge only after exact production proof.

---

## Verification-process pattern — source fix versus exact-final execution closure

Historical ledger repeatedly distinguishes:

- source-semantic fixed;
- test/harness fixed;
- execution not verified;
- exact-final execution closure.

`BUG-DUPLICATE-ADMISSION-01` is a representative validated sequence. Its final closure checkpoint `2026-09-12__a12c5805__duplicate-admission-execution-closure-basis-advance.md` blob `bd7766ef64ad025009f5c80d7c9fa558fef9610e` records a test-only follow-up and then exact-final-SHA execution evidence.

This is not automatically a production defect recurrence. It is a workflow cost pattern.

Current review protocol already has substantially stronger exact-final execution and harness discipline, including nonzero target execution, final-SHA binding, async quiescence, rerun policy, test-seam lifetime and stale-regression-contract handling.

No additional generic rule is proposed at this stage. The main recommendation is to enforce the existing execution packet in the original implementation wave whenever infrastructure permits, reducing avoidable test-only follow-up waves.

---

## Consolidated cause model

The historical evidence does **not** support the simple claim that “the reviewer needed a better lens.”

The recurring causes are better described as:

1. **semantic operation narrower than implementation scope** — helper/local fix versus full lifecycle;
2. **new-state correctness without old-state compatibility** — current writer path passes, persisted generation remains unsafe;
3. **representation mistaken for provenance/authority** — exact or normalized locator is treated as sufficient authority;
4. **safety fence without liveness/convergence** — duplicate prevention improves while unresolved debt has no owner;
5. **producer/consumer graph not fully enumerated** — especially import/restore/migration writers outside the triggering feature;
6. **fix-induced blocking/lock effects** — new authority serialization changes synchronous caller behavior;
7. **review classification errors** — helper-name/call-boundary assumptions and duplicate-root counting;
8. **verification fragmentation** — source fix and exact-final execution closure split by harness/infrastructure issues.

Items 1–5 are mostly already represented in current v6 semantics. The improvement target is therefore the bridge from checklist -> implementation prompt -> regression design -> first independent review.

---

## Proposed remediation-workflow changes

These are proposals only; no protocol/checklist files are modified by this checkpoint.

### P1 — Mandatory remediation closure packet before implementation prompt

For any blocker whose correction changes durable identity, ownership, recovery, destructive admission, scheduler handoff, publication, or executable persisted configuration, require the implementation prompt to contain a compact machine-reviewable packet:

- root/invariant;
- old contract -> new contract;
- all producers/writers/importers/migrations;
- durable carriers;
- consumers and final effects;
- retry/reconfigure/restart/process-death paths;
- persisted-generation compatibility;
- failure/partial-effect/convergence owners;
- affected sibling/shared helpers;
- exact focused regression matrix;
- exact-final execution gates.

The packet is derived from current source/governance; it does not require the implementation agent to reconstruct historical review archaeology.

### P2 — Extend prompt preflight shared-contract propagation to producers

Current protocol 7.1 item 7 should require:

> all relevant production producers/writers/import/restore/migration paths **and** all consumers/final effects

when a common persistence format, identity/provenance contract, executable configuration, or authority boundary changes.

This directly addresses the currently verified BUG-BACKUP-11 miss.

### P3 — Add persisted-generation compatibility to implementation prompt preflight

When v6 Module F is triggered, prompt preflight must reject a verification plan that only creates state through the new writer.

Require explicit old-state seeding and upgrade/restart/recovery regression.

### P4 — Triggered modules override DEEP-rotation deferral

Per-SHA lens DEEP rotation must not postpone a blocker-relevant triggered conditional module.

Triggered Module C/F/H or semantic-contract producer/consumer closure must be fully executed on the first review of that semantic change, regardless of which lens is designated primary DEEP.

### P5 — Add thread-affinity/slow-lock rule to v6

Adopt the explicit thread-affinity rule described under archetype F.

This is the strongest evidence-backed **new checklist rule** identified in this first retrospective pass.

### P6 — Safety-fence convergence owner as prompt field

Do not add another broad v6 invariant; instead require implementation prompts for UNKNOWN/quarantine/hold/debt states to name the positive convergence owner and finite terminal outcomes. If absent, label the wave incomplete rather than closure-targeted.

### P7 — Preserve current root/count and candidate-rejection rules

No new duplicate/alias rule is proposed. Current protocol already addresses the historical false-positive/root-count churn pattern.

---

## Items deliberately NOT concluded

- No claim is made that one lens is globally more effective than another.
- No historical-total lens effectiveness score is inferred; it remains `NOT_VERIFIED`.
- Repeated checkpoint mentions are not treated as repeated independent defects.
- A source-fix followed by missing runtime evidence is not counted as a new product defect.
- No historical finding severity/count/disposition is changed.
- No Master Plan, Checklist v6, private protocol, handoff, production source, or authoritative ledger is modified.

## Next retrospective scope

Before converting proposals into governance edits:

1. adversarially test P1-P6 against counterexamples where they would add ceremony without preventing a historical miss;
2. check whether later protocol revisions already partially implement each proposal;
3. separate rules suitable for stable `REVIEW_PROTOCOL.md` from v6 checklist wording and finding-specific prompt guidance;
4. produce exact minimal wording and false-positive guards;
5. only then propose/apply governance changes.

This checkpoint is evidence/proposal state, not a governance change.
