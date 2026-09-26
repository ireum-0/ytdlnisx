# v7 pre-adoption validation matrix and historical backtest

review_parent_sha: `dba7320b70042101e984d763befb75c6d48fc382`
checkpoint_kind: CHECKLIST_CANDIDATE_VALIDATION
canonical_status_change: NONE
canonical_count_change: NONE
production_source_modified: NO
governing_checklist_changed: NO

## Candidate identities

- governing v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- earlier broad v7 draft blob: `6e322cadf8ed3065e45f2ec2dbf4f3834cb55275`
- revised minimal v7 candidate blob: `fa08097e75cdb20b89e1dbe1149f7bec4022846a`
- adopted lens-selection policy blob: `49600871d632fd8612bbabec80dfaa996afb54d3`

The revised candidate is intentionally based on v6 again rather than editing the broad draft. It removes checklist content that historical evidence showed belongs in protocol/scheduling rather than in a general correctness checklist.

## Validation result by candidate rule

### V7-A — thread-affinity / blocking-wait review

Decision: RETAIN.

Historical positive case:
`CLEANUP-MAIN-THREAD-BLOCK-01`, checkpoint blob
`1fe8905d227f4e7a3e2e39b07283610be78bccf0`.

Exact historical path:
- settings preference listener synchronously calls `CleanupScheduleCoordinator.configure()`;
- configure uses `runBlocking`;
- a background cleanup worker can hold `destructiveEffectMutex` across DB/cache deletion;
- UI/main thread can therefore wait for a materially slow competing owner.

The checkpoint explicitly classified this as a v6 checklist gap.

v6 has lock/mutex ordering review but no explicit thread-affinity/main-thread/ANR/blocking-wait rule.

False-positive guard in the candidate:
- a lock alone is not a defect;
- require a reachable synchronous caller and a competing hold interval that can contain materially slow or blocking work.

Backtest outcome: PASS. The candidate rule directly targets the historical miss without globally rejecting synchronization.

### V7-B — invalid identity-transformation propagation

Decision: RETAIN.

Historical positive case:
`HISTORY-CONTENT-AUTHORITY-ALIAS-01`, checkpoint blob
`2b00a7a3e772b3fb6cfc554fc92eb914e14c6917`.

Exact historical lesson:
- prior LocalAdd review had already established exact provider-authority semantics;
- History deletion independently lowercased provider authority in a destructive identity key;
- previous review stayed in the LocalAdd producer/admission graph and did not search sibling identity implementations for the same invalid transformation.

The checkpoint explicitly identified this as a checklist gap and proposed repository-wide targeted propagation of an established-invalid transformation.

False-positive guard in the candidate:
- the transformation is not presumed invalid in all namespaces;
- repository search finds candidates only;
- every hit is independently classified under its own authoritative namespace/equality contract.

Backtest outcome: PASS.

### V7-C — persisted-generation marker/sentinel collision proof

Decision: ADD / RETAIN.

This rule was not present in the earlier broad v7 draft and was discovered by current-SHA shadow review.

Current exact case:
- implementation: `58c067b8a7a82320e9a369b77c642b025579d8c8`;
- review checkpoint blob: `65d2535e310a6ed093f019c6a6557e41ff202b9e`;
- same BUG-TERMINAL-03 root.

The implementation introduced
`--ytdlnisx-terminal-command-format=1`
inside the durable user command as current-format evidence.

Immediately prior implementation `b6512ae2...` stored user command text unchanged and staged the same text in a generation-1 TERMINAL_DISPATCH carrier. Therefore old supported state could already legally contain the exact future marker bytes.

Current tests directly seed multiple legacy rows, but the persisted-generation production-wiring test contains zero instances of the exact future marker token. New-writer tests cannot distinguish a genuinely new marker from historical command text containing the same bytes.

The revised Module F candidate therefore requires:
- if a new marker/token/enum/sentinel/embedded option is used as generation/provenance proof, prove supported pre-change producers could not already legally persist the same representation without the new semantics;
- seed the exact collision when representable;
- build the compatibility fixture in the old representation directly rather than through the new writer;
- if collision is possible, marker equality alone is not generation/provenance authority.

False-positive guard:
- trigger only when a representation is relied on as proof of generation/provenance;
- old self-bound state with independently exact provider/native authority is not rejected merely because it is old.

Backtest/shadow outcome: PASS and materially improves Module F.

## Proposals removed from v7 checklist candidate

### Generic producer closure as a new core checklist rule

Decision: REMOVE FROM V7.

Reason:
`BUG-BACKUP-11` checkpoint blob
`b1c4ab391d3a1c15c446aed3234b77350cf039d3`
explicitly records:
- Checklist gap: none;
- existing v6 L6 + Module C + Module H already require traversal into backup/restore/import producers;
- the miss was that those existing rules were not executed early enough.

A generic producer-closure core rule would duplicate existing module semantics and broaden review cost for unrelated semantic deltas.

Correct home:
- stable Review Protocol prompt preflight;
- trigger-first lens/module scheduling policy.

### Triggered-module timing / DEEP deferral rule as duplicate v7 semantics

Decision: DO NOT DUPLICATE IN V7 BEYOND EXISTING v6 MANDATORY TRIGGER LANGUAGE.

Evidence:
- BUG-BACKUP-11 was found only when L6 was later DEEP;
- BUG-CANCEL-02 Terminal subcase was found in later L4 DEEP;
- both checkpoints state the applicable v6 rules already existed and the miss was coverage/execution.

Correct home:
`REVIEW_LENS_SELECTION_POLICY_V1.md` and stable Review Protocol, which now require triggered obligations before DEEP rotation.

### Unresolved-state convergence owner as a new generic v7 rule

Decision: DO NOT ADD A DUPLICATE CORE RULE.

Historical BUG-OUTPUT-01 checkpoint blob
`b1c13bc46a657d7791934a1177e93b3aef51fe19`
states quarantine/UNKNOWN was a safety fence without a production convergence owner, but also states no new core v6 gap was required. Existing recovery/liveness/retry closure already expresses the invariant.

Correct home:
implementation-prompt preflight, where convergence owner must be named for UNKNOWN/quarantine/debt states.

## False-positive / overreach backtest

Historical false positive:
`BUG-LOWQUALITY-SAVED-01`.

Correction blob:
`84f03476e2ef3590f3d4f11d10543ca4e865a93f`.

The earlier review stopped one production call boundary too early. Exact source showed `updateToStatus(Saved)` special-cased Saved, called `moveToSaved()`, and converged the linked low-quality ledger.

The revised v7 candidate does not create a new trigger for this case:
- no new/widened slow synchronization boundary;
- no previously proven-invalid identity transformation;
- no stronger persisted-generation marker/sentinel.

Existing candidate-rejection discipline remains the correct protection.

Backtest outcome: no added false-positive pressure identified.

## Historical cases intentionally handled by existing v6, not v7 additions

- BUG-CLEANUP-01 same-root durability/replay residuals: existing v6 recovery/durability/async ownership rules.
- BUG-OUTPUT-01 UNKNOWN convergence: existing v6 recovery/liveness/retry rules.
- BUG-BACKUP-11 alternate executable-config producer: existing Module C/H + L6, now scheduled earlier by protocol/policy.
- BUG-CANCEL-02 Terminal publication/cancellation subcase: existing live-owner/cross-attempt/Module B/I rules, now scheduled earlier by trigger-first policy.

## Candidate scope after backtest

The revised v7 candidate adds only:

1. thread-affinity / blocking-wait review;
2. invalid identity-transformation propagation;
3. Module F generation-marker/sentinel collision proof.

It explicitly leaves:
- lens scheduling to `REVIEW_LENS_SELECTION_POLICY_V1.md`;
- producer/import/restore implementation-prompt inventory to Review Protocol;
- unresolved-state convergence prompt fields to Review Protocol.

This is the preferred pre-adoption candidate for shadow review.
