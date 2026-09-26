# Manual v7 review — 359ddbf9 — L2 DEEP final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: a56a43c62d26396b1c1a6383ece4a5285294ddbe

checkpoint_kind: MANUAL_CORRECTNESS_REVIEW
review_parent_sha: cdd5a5abea7714c9550280f52b7a2c67e574747e
intermediate_checkpoint:
`review-runs/checkpoints/2026-09-26__359ddbf9__manual-v7-l2-deep-intermediate.md`
intermediate_commit: `cdd5a5abea7714c9550280f52b7a2c67e574747e`

implementation_sha: `359ddbf9bf534009be095ad1bffea8ec45c899e4`

## Pinned governance

- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- plan/remediation tip at run start: `2145847a1054da28398b730b9be0ca728668f967`
- protocol blob: `b9ff2c984a1655f0f214ab0ec89d3ccb18c0f913`
- checklist v7 adoption: `b98d315006fa19fc6f22b017f43a91899db5fb81`
- checklist v7 blob: `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`
- lens-policy adoption: `822ffe6a9cd45b951550fcb559557f0cf0798610`
- lens-policy blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
- ledger/remediation: `b98d315006fa19fc6f22b017f43a91899db5fb81`

Governance remained fixed for the complete manual run.

## Independent verdict

**NOT_CLEAN.**

A fresh full-checklist review was performed on exact implementation
`359ddbf9bf534009be095ad1bffea8ec45c899e4`; the prior verdict was not merely reused.

BUG-TERMINAL-11 remains independently FIXED-CLOSED.

Existing canonical P2 roots still confirmed open on exact current source:
- BUG-BACKUP-11;
- BUG-PAUSE-03;
- BUG-CANCEL-02 Terminal cancellation/publication/live-cache subcase.

New P0/P1/P2 finding IDs: **0**.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

CLEAN_REVIEW_BASIS remains:
`74f57e695db30b701ad429af311c39a763bfe086`.

INDEPENDENT EXECUTION: NOT EXECUTED.

## Reviewed implementation delta

Exact implementation range:
`dcad84ac30aa6dac7096961e408b48971c2f0376..359ddbf9bf534009be095ad1bffea8ec45c899e4`.

The range is one forward test-only commit:
- changed file:
  `app/src/androidTest/java/com/ireum/ytdl/work/TerminalPersistedGenerationAuthorityProductionWiringTest.kt`;
- 38 insertions / 1 deletion;
- no production/application source changed.

## BUG-TERMINAL-11 — closure re-proof

Disposition: **FIXED-CLOSED**.

The final fixture:
- snapshots the existing reconciliation root Job before the real reconcile call;
- waits for the complete positive sibling enqueue count;
- repeatedly joins all newly-created children of that root under a bounded timeout;
- repeats enumeration after each join until no new relevant child remains;
- executes malformed negative-enqueue assertions only after that bounded drain.

Production wiring was re-opened:
- Terminal reconciliation creates runnable handoff dispatches through
  `convergenceScope.launch`;
- each dispatch awaits the exact enqueue attempt;
- retry work is also created in the same root scope;
- child creation occurs before its creator returns.

The new test therefore observes completion/quiescence rather than elapsed time. No same-root
harness or production residual was found.

Triggered disposition for this root:
- Module B: PASS;
- Module C: PASS;
- Module F: PASS;
- Module H: PASS.

Implementation-agent exact-SHA execution evidence is preserved as evidence:
- connected scoped total 79/79;
- JVM scope 68/68;
- compileDebugKotlin PASS;
- compileDebugAndroidTestKotlin PASS;
- git diff --check PASS.

No independent Gradle/instrumentation execution was performed in this manual review.

## BUG-BACKUP-11 — OPEN current exact-source root

Disposition: **OPEN P2 / CONFIRMED**.

The complete authority chain remains:

normal picker
-> `takePersistableUriPermission`
-> `command_path = content://...`
-> backup serializes locator String
-> restore writes locator String on destination
-> no destination grant reconstruction/proof
-> new Terminal materializes imported locator into durable provider metadata
-> planner stages safely but retains provider URI as final destination
-> provider publication later requires authority the restore never established.

### Identity/provenance finding

The exact URI string is a locator, not a transferable capability.

`TerminalDestinationAuthority.classify()` distinguishes provider vs native representation but is
syntax-only for provider authority. It cannot establish that the destination installation holds the
grant that made the source installation's provider URI executable.

Restore therefore preserves identity bytes but not the provenance/capability required by the final
effect.

### Producer/write/import/restore inventory

- settings Folder picker: acquires persistable read/write grant, then writes command_path;
- default initialization: writes default command path;
- backup: command_path remains portable;
- parser: validates type/representation, not provider permission;
- merge restore: replays portable String;
- reset restore: replays portable String;
- no restore path invokes a fresh provider picker/grant flow.

### Consumer/effect inventory

- TerminalViewModel materializes configured provider into the durable Terminal command;
- Terminal planner classifies provider URI and forces staging rather than native direct output;
- Terminal worker publishes through provider-aware FileUtil;
- DownloadViewModel also reads command_path for command-type download defaults/reconfiguration;
- HistoryFileDeletion reads command_path, but destructive provider authorization additionally
  requires actual persisted write permission and therefore fails closed.

No second root was found in that fan-out.

### Triggered modules

- Module C: FAIL;
- Module H: FAIL.

## BUG-PAUSE-03 — OPEN current exact-source root

Disposition: **OPEN P2 / CONFIRMED**.

Pause All still:
1. snapshots a fixed Active/PostProcessing target set under the global execution lock;
2. releases that lock;
3. durably pauses/quiesces only those exact saved executions;
4. later issues tag-wide `cancelAllWorkByTag("download")`.

A queued sibling can be normally claimed after the snapshot because the production admission path
has no Pause-All generation/barrier.

That sibling can then be stopped by the later coarse tag cancellation without USER_PAUSE authority.

Generic stopped-worker recovery has no user-stop disposition for the sibling and may requeue its
exact Active/PostProcessing execution to Queued. Resume All selects Paused rows, so this does not
become the same Pause-All semantic outcome.

### Root-rejection proof

Do not merge BUG-PAUSE-03 into:
- BUG-PAUSE-01 — the late sibling receives no first Pause write;
- BUG-PAUSE-02 — no successful Pause write followed by quiescence failure is required;
- BUG-CANCEL-02 — this root is Pause-All target-set authority, not user Cancel final-effect
  authority;
- BUG-QUEUE-05 — the victim is newly admitted after the Pause-All snapshot, not an already-Paused
  queue member.

Module B: FAIL for this root.

## BUG-CANCEL-02 Terminal subcase — OPEN current exact-source root

Disposition: **OPEN under existing canonical P2 BUG-CANCEL-02**.

Terminal cancellation still allows:
- dispatch supersession;
- native/execution STOPPED convergence;
- process-local active-token retirement;

without proving that a live worker's post-native provider publication has quiesced.

`rowDeletionAuthorized` does not require WorkManager cancellation acknowledgement.

NATIVE_FINISHED can be terminalized as STOPPED, but the worker has no durable STOPPED recheck
immediately before provider/file publication. `markCommitting()` occurs only after publication may
already have created external effects.

The active token can also disappear before publication quiescence, weakening
`TerminalCacheOwnership.isLiveOwnedRoot()` and exposing still-owned staging to cache maintenance.

### Root-rejection proof

This remains BUG-CANCEL-02 rather than a new Terminal/cache ID because the violated invariant is
the same:
once durable cancellation wins, stale success/final effects must be vetoed before the irreversible
effect boundary.

The cache-maintenance behavior is a destructive consequence of prematurely retiring the same
cancellation/effect owner, not an independent semantic origin.

Triggered modules:
- Module B: NOT_CLOSED;
- Module I: FAIL.

## L1-L6 review

A fresh BASELINE pass was performed for all six lenses.

Accumulated current-SHA depth:
- L1 Durability & recovery: DEEP;
- L2 Identity & provenance: **DEEP — promoted by this manual run**;
- L3 Concurrency & authority: DEEP;
- L4 Destructive ownership: BASELINE;
- L5 Platform contract closure: BASELINE;
- L6 Cross-feature semantic propagation: BASELINE.

### L1 baseline recount

No new carrier-loss root was found. Existing gaps remain:
- imported provider locator without destination authorization provenance;
- Pause-All generic requeue preserving liveness but not Pause semantic identity;
- Terminal native STOPPED convergence narrower than publication-effect ownership.

### L2 DEEP

Primary lens.

BUG-BACKUP-11 was traced from capability-producing picker through backup carrier, restore/import,
durable Terminal materialization, typed planning, and final provider publication.

Finding:
locator equality/syntax is insufficient proof of provider authorization provenance.

No additional L2 root was confirmed.

### L3 baseline recount

BUG-PAUSE-03 and BUG-CANCEL-02 concurrency/authority cells remain present.

BUG-TERMINAL-11's test observation closure remains deterministic.

No new L3 root.

### L4 baseline recount

BUG-CANCEL-02 retains a real destructive/final-effect consequence: post-cancel publication and
premature cache-live-owner retirement.

No separate destructive root was split out.

### L5 baseline recount

SAF authorization is a platform capability not reconstructible from a serialized provider locator.
WorkManager cancellation acknowledgement and exact effect-owner quiescence also remain distinct.

No additional platform-contract root.

### L6 baseline recount

Cross-feature paths were traced across:
- backup -> restore -> Terminal -> provider publication;
- Pause All -> scheduler admission -> WorkManager stop -> generic recovery -> Resume All;
- Terminal cancellation -> execution witness -> provider publication -> cache maintenance.

No new semantic root was found.

## Lens scheduling

primary_deep_lens: L2

primary_deep_selection_reason:
- R1: BUG-BACKUP-11 is directly owned by identity/provenance;
- R2: Module C and Module H remain open on the same missing-capability provenance.

remaining_not_yet_deep:
- L4
- L5
- L6

next_not_yet_deep_lens: L4

next_lens_selection_reason:
R1 — BUG-CANCEL-02 directly implicates irreversible publication and live-cache destructive
ownership, making L4 the strongest remaining direct-root lens.

## Trigger map final

- Module B:
  - BUG-TERMINAL-11 PASS;
  - BUG-PAUSE-03 FAIL;
  - BUG-CANCEL-02 Terminal NOT_CLOSED.
- Module C:
  - BUG-TERMINAL-11 PASS;
  - BUG-BACKUP-11 FAIL.
- Module F:
  - BUG-TERMINAL-11 PASS;
  - no new 359ddbf9 production schema/generation delta.
- Module H:
  - BUG-TERMINAL-11 PASS;
  - BUG-BACKUP-11 FAIL.
- Module I:
  - BUG-CANCEL-02 Terminal FAIL.
- Thread-affinity:
  - no new production synchronization/wait boundary from 359ddbf9;
  - no new blocker classified.
- Invalid identity-transformation propagation:
  - not triggered by a newly proven normalization/equality transformation in this run.

Every blocker-relevant triggered module identified in this run was executed to PASS or explicit
OPEN/FAIL disposition; none was silently deferred.

## Terminal fault / cross-attempt / live-owner summary

### BUG-BACKUP-11

- source installation has provider locator + grant;
- backup carries locator only;
- destination restore may have no grant;
- new Terminal can durably bind imported locator;
- publication fails later rather than restore refusing/re-authorizing the setting.

Result: OPEN.

### BUG-PAUSE-03

- A is in Pause-All snapshot;
- B is queued outside it;
- B claims after snapshot;
- broad tag cancel stops B;
- B has no USER_PAUSE carrier;
- generic cleanup may requeue B;
- Resume All does not identify B as paused.

Result: OPEN.

### BUG-CANCEL-02 Terminal

- native reaches NATIVE_FINISHED;
- cancel durably wins and terminalizes witness;
- active token can retire;
- worker/effect may still be alive;
- publication can cross irreversible boundary;
- cache maintenance may no longer see live Terminal ownership.

Result: OPEN.

## Candidate-rejection / alias reconciliation final

- BUG-BACKUP-11 is not BUG-TERMINAL-03: Terminal provider/native typing is fixed; the unresolved
  authority originates at backup/restore grant provenance before a new Terminal is created.
- BUG-BACKUP-11 is not cache-path portability: cache_path is already destination-local/non-portable;
  command_path is still portable.
- BUG-BACKUP-11 is not a HistoryFileDeletion root: that consumer checks persisted write permission
  before provider destructive authorization.
- BUG-PAUSE-03 remains distinct from per-item pause write/quiescence roots because the late sibling
  receives no Pause semantic write at all.
- BUG-CANCEL-02 Terminal is one same-root extension of authoritative-cancel-at-final-effect and is
  not double-counted as a cache finding.
- BUG-TERMINAL-11 has no remaining same-root residual in this reviewed cell.

## Review retrospective

The 359ddbf9 implementation change itself is narrow and test-only, and its intended
BUG-TERMINAL-11 closure survives independent source review.

The manual run's highest-value additional observation is not a new finding. It is the strengthened
current-SHA proof that BUG-BACKUP-11 is fundamentally an authority-provenance defect:
the application already distinguishes provider vs native execution correctly, but restore still
transfers the provider locator as though it were portable authorization.

The run also re-proved that stronger per-item recovery/ownership machinery does not automatically
close broader operation-set or effect-owner gaps:
- BUG-PAUSE-03 remains a batch target-set expansion;
- BUG-CANCEL-02 remains an effect-quiescence/ownership problem.

No current evidence supports changing canonical severity/counts.

## Checklist evolution

Checklist gap: **none confirmed**.

No checklist or lens-policy change is proposed.

v7 already requires the rules that detected/confirmed the open cells:
- identity/provenance granularity;
- producer/import/restore/consumer closure;
- external representation authority;
- persisted executable configuration fan-out;
- scheduler handoff;
- sibling isolation;
- asynchronous completion;
- live-owner/final-effect destructive authority;
- triggered-module completion.

The remaining defects are implementation gaps, not governance omissions.

## Final checkpoint summary

- manual run start parent:
  `a56a43c62d26396b1c1a6383ece4a5285294ddbe`;
- pinned implementation:
  `359ddbf9bf534009be095ad1bffea8ec45c899e4`;
- intermediate:
  `cdd5a5abea7714c9550280f52b7a2c67e574747e`;
- verdict: NOT_CLEAN;
- BUG-TERMINAL-11: FIXED-CLOSED;
- BUG-BACKUP-11: OPEN P2;
- BUG-PAUSE-03: OPEN P2;
- BUG-CANCEL-02 Terminal subcase: OPEN under existing P2 root;
- new finding IDs: 0;
- canonical totals: P0=0 / P1=0 / P2=19;
- L2 promoted DEEP;
- remaining lenses: L4, L5, L6;
- next same-SHA lens hint: L4;
- independent execution: NOT EXECUTED.
