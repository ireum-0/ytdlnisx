# BUG-UPDATER-03 — historical active-Reset compatibility stop reconciliation

checkpoint_kind: IMPLEMENTATION_STOP_RULE_RECONCILIATION
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: 1285c3a5bf863e69b891bee26717a3db19554d1a
implementation_sha: 39f971cdca7712e60046dbdf346847ce9c74924a
implementation_parent_sha: 383e06782c919ad5f436e2fd0d38814375ba0db9
implementation_tree: c69785b836a7635c2df9c92134b2e0c2c40d70df
implementation_remote_changed: NO
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
overall_verdict: VALID_SECTION_3_5_STOP_SAME_ROOT_ACTIVE_RESET_COMPATIBILITY_REFINEMENT
canonical_p0: 0
canonical_p1: 0
canonical_p2: 2
canonical_open_roots: BUG-UPDATER-03,BUG-HISTORY-05
clean_review_basis_advance: NO
independent_execution: NOT_EXECUTED

## Preserved implementation state

The implementation agent stopped before publication after discovering a material recovery-policy gap.

Reported preserved candidate:
- HEAD: 39f971cdca7712e60046dbdf346847ce9c74924a
- tree: c69785b836a7635c2df9c92134b2e0c2c40d70df
- parent: 383e06782c919ad5f436e2fd0d38814375ba0db9
- eight dirty files
- index empty
- source edits in this stopped wave: preserved locally, not published
- new commits: 0
- pushes: 0
- runtime reproductions: 0

Verification attempt:
- first compile attempt failed because App.kt lacked the new UpdateUtil import required by the local draft;
- tests executed: 0;
- retry after that compile failure: NO.

Reported evidence:
- all 4,028 prior sealed records verified;
- 479 new evidence records sealed;
- durable local report:
  C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater03-recovery-20261005-6c914a28/BUG_UPDATER_03_DIRTY_COMPATIBILITY_STOP_REPORT.md

The local sealed report and exact eight-file dirty diff are implementation-agent evidence and were not independently opened from GitHub.

## Independent classification

The stop is valid under REVIEW_PROTOCOL section 3.5.

BUG-UPDATER-03 remains one P2 root. The newly exposed condition is a same-root compatibility residual:

A stronger current BackupRestoreParser key-specific validator is correct for NEW restore admission, but RestoreOperationStore.readPlan() currently reparses and revalidates every already-owned durable Reset plan on recovery.

A Reset plan created by a supported historical writer may therefore:
1. have passed the then-current generic settings validation;
2. have been durably written to plan.json;
3. have a matching journal.planDigest and active pointer;
4. already own destructive Reset authority;
5. contain a BUG-UPDATER-03 updater setting representation that the new validator now rejects.

If recovery applies the new admission rule indiscriminately, RestoreOperationStore.load() throws RestoreRecoveryBlockedException. RestoreGate then remains DEFERRED indefinitely, while the active owner cannot roll forward and ordinary preference repair is correctly barred from bypassing the owner.

This is not evidence that new malformed Reset input should be accepted. It is evidence that NEW ADMISSION and ALREADY-OWNED RECOVERY require different validation semantics.

## Existing source semantics

Current production establishes these durable phase boundaries:
- PREPARED: active Reset owner exists; destructive apply has not started.
- QUIESCED: conflicting external owners may already have been cancelled/quiesced.
- FILES_READY: required files are durably staged/published; authoritative Room/preferences apply has not started.
- APPLYING: Room apply and preference publication are replayable; the phase can survive either before or after an old preference publication because DATA_COMMITTED is written only afterward.
- DATA_COMMITTED: authoritative data/preferences have committed; post-commit reconciliation remains debt.
- RECONCILING: post-commit external responsibility reconstruction is active.
- COMPLETE: reconciliation is complete, but active pointer/operation retirement may still be pending.

Existing restart tests prove these phases are intended to roll forward rather than be abandoned.

## Authorized historical active-Reset recovery boundary

### 1. New admission remains strict

BackupRestoreParser/fromTyped/validatePlan admission for every NEW Merge/Reset request must enforce the refined BUG-UPDATER-03 key-specific schema.

A malformed new payload must still be rejected before Reset ownership or Merge mutation.

Do not weaken the public/current admission contract to solve historical recovery.

### 2. An already-owned durable Reset is recovery authority, not a new admission request

A plan may use the historical compatibility recovery path only when existing durable ownership is independently established by the current carrier contract:
- active pointer exists;
- operation id is structurally valid;
- operation directory/journal exist and ownership matches;
- raw plan bytes exist;
- raw plan digest exactly matches both active pointer and journal;
- journal phase is recognized.

The immutable plan bytes, active pointer, journal identity and planDigest remain evidence. Do not rewrite plan.json or silently replace its digest to make it pass current admission.

### 3. Recovery validation is a narrow compatibility mode

For an already-owned plan only, recovery may tolerate exactly the BUG-UPDATER-03 updater-setting schema deviations that supported historical generic validation could admit:
- ytdlp_source generic-valid wrong declared type;
- ytdlp_source String blank/whitespace;
- auto_update_ytdlp generic-valid wrong declared type;
- ytdlp_source_label generic-valid wrong declared type.

All unrelated structural, metadata, capability, generic value/type, identity, path, payload and integrity validation remains fail-closed.

An unrelated current validation failure must not be relabeled as updater compatibility.

The historical compatibility reader must not make malformed input newly admissible through parse(), fromTyped(), validatePlan(), SettingsViewModel.restoreData(), restorePlan(), or RestoreTransactionCoordinator.begin().

### 4. Derive an effective recovery image; preserve immutable plan evidence

Do not coerce malformed updater bytes into user intent and do not modify the durable plan.

Derive an in-memory/effective updater-settings view for recovery:
- malformed ytdlp_source is treated as absent/default-source intent for the effective Reset image;
- a source label that would falsely describe the unknowable malformed source is absent from that effective image;
- malformed auto_update_ytdlp is absent;
- malformed ytdlp_source_label is absent;
- valid updater settings and unrelated valid plan content remain unchanged.

The effective image, not the raw malformed updater item, must drive Reset preference publication and source reconciliation.

### 5. Pre-commit phases must roll forward with sanitized publication

For PREPARED, QUIESCED and FILES_READY:
- keep the active owner;
- preserve ordinary quiescence/staging semantics;
- roll forward using the effective recovery image;
- never publish the malformed updater representation.

For APPLYING:
- recovery must be correct whether an older process had not yet published preferences OR had already published malformed preferences before crashing while the journal still said APPLYING;
- replay must converge to the same sanitized preference image;
- any raw malformed current preference state must be inspected without unsafe typed reads before source reconciliation/casts.

Do not clear the active pointer merely because the historical plan is no longer valid for new admission.

### 6. Post-commit phases must repair under the active Reset owner

For DATA_COMMITTED and RECONCILING:
- do NOT reapply authoritative Room data merely to fix updater preferences;
- while the active Reset owner still blocks ordinary mutation, inspect current updater preferences through raw SharedPreferences state;
- repair BUG-UPDATER-03 malformed updater carriers under Reset-owned mutation authority before post-commit reconciliation can finish and before the active pointer is retired;
- then resume the existing reconciliation state machine.

For COMPLETE:
- if old code reached COMPLETE with malformed updater preferences and crashed before retirement, perform the same owner-scoped compatibility repair before clearActiveIfOwned()/retireOperation().

The active Reset gate must remain DEFERRED until required compatibility repair is durably complete.

### 7. Malformed-source composite recovery including Long.MAX_VALUE

Historical active Reset compatibility extends the governing L1 composite-identity rule.

Malformed updater source intent cannot be interpreted as a valid source identity merely because the numeric generation/domain carrier is current.

For an already-owned historical Reset whose effective source component is malformed:
- do not stringify or preserve malformed source bytes as source intent;
- converge to the established absent/default stable source contract;
- remove stale source-label metadata when necessary;
- retire committed/pending source proof;
- establish a safe canonical destination generation before the repaired identity is admitted.

This compatibility migration may perform a one-time generation rebase even when a numeric carrier is Long.MAX_VALUE when the source identity being recovered is malformed/unknowable under this historical owned plan.

This is NOT permission to reset a genuine valid-source current-domain Long.MAX_VALUE identity during ordinary source selection, new valid Reset admission, or unrelated recovery. That valid cell remains exhausted/fail-closed.

### 8. Other malformed updater keys

For malformed auto_update_ytdlp:
- do not invent true/false;
- remove it to the existing absence representation.

For malformed ytdlp_source_label:
- remove it;
- label-only repair must not independently alter a valid source generation/proof.

### 9. Durability, replay and ownership

Compatibility repair/publication must:
- use the existing Reset ownership/mutation ordering;
- remain deterministic and idempotent across process death;
- treat failed SharedPreferences commit/process-map publication as untrusted;
- leave the active owner discoverable and retryable on failure;
- never expose a window where ordinary mutation is admitted while the historical Reset debt remains unresolved;
- not introduce a new marker whose historical collision safety is unproven.

The existing active pointer + matching journal + matching planDigest is authorization to RECOVER an already-owned plan. It is not a substitute for new-payload validation.

## Required regression coverage

Tests must seed historical durable carrier state directly, bypassing the corrected new admission path. Do not create these fixtures through the new strict RestoreTransactionCoordinator.begin() and then claim historical compatibility is covered.

Required negative/control cells:
- the same malformed updater payload is rejected as a NEW Reset before ownership;
- unrelated malformed/corrupt active plan carrier remains blocked;
- plan/journal/pointer digest mismatch remains blocked;
- immutable historical plan bytes/digest are not rewritten by compatibility recovery.

Required owned-plan phase coverage:
- PREPARED malformed updater settings roll forward safely;
- QUIESCED rolls forward without abandoning already-quiesced responsibility;
- FILES_READY rolls forward using sanitized updater publication;
- APPLYING recovers both before-publish and old-publish-before-journal-advance ambiguity;
- DATA_COMMITTED repairs updater preference state without reapplying committed Room data;
- RECONCILING repairs before reconciliation/retirement completes;
- COMPLETE repairs before owner retirement.

Required updater cells include:
- wrong-type ytdlp_source;
- blank/whitespace ytdlp_source;
- malformed source with current-domain Long.MAX_VALUE;
- wrong-type auto_update_ytdlp;
- wrong-type ytdlp_source_label;
- valid updater settings remain unchanged;
- valid-source current-domain Long.MAX_VALUE remains a fail-closed negative control.

Required failure/restart cells:
- compatibility preference commit failure leaves active owner/debt durable;
- restart retries and converges;
- second successful recovery is idempotent/no-op;
- no ordinary worker/preference mutation is admitted through the active Reset gate before completion.

## Preserved draft continuation

Do not clean/reset/discard/reconstruct the reported eight-file dirty candidate.

At bootstrap after GitHub authority checks:
1. verify remote implementation remains exact 39f971c;
2. verify the local worktree still contains the preserved eight dirty files with empty index;
3. inventory the existing diff without staging it;
4. classify every existing hunk against this refined active-Reset compatibility contract;
5. retain compatible work;
6. revise only incompatible/incomplete BUG-UPDATER-03 work;
7. preserve all prior sealed evidence and the durable stop report.

If the preserved worktree no longer matches the reported protected state, another writer touched it, or exact ownership of local changes is uncertain: STOP. Do not clean or reconstruct.

The reported first compile failure (missing UpdateUtil import in App.kt) is preliminary implementation evidence. It is not a semantic failure. After materially reconciling the draft to this refined contract, fix the within-scope import/build error if still applicable and rerun the required build/test gates. Do not rerun unchanged failing code merely to seek green.

## Disposition

BUG-UPDATER-02: CLOSED.
BUG-UPDATER-03: OPEN P2 / SAME ROOT / ACTIVE_RESET_COMPATIBILITY_REFINEMENT_REQUIRED.
BUG-HISTORY-05: OPEN P2.
Canonical P2 remains 2.

No implementation publication exists after 39f971c.
No closure evidence exists for BUG-UPDATER-03.

Next action:
refine the existing persisted BUG-UPDATER-03 prompt in place with this contract, keep the handoff launch-ready at implementation base 39f971c, and resume the preserved eight-file draft through the existing same-wave exact-final-SHA closure boundary.

INDEPENDENT EXECUTION: NOT EXECUTED
