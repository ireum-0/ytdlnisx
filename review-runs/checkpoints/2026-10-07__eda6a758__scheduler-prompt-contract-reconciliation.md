# Scheduler authority prompt — correction-contract reconciliation

Date: 2026-10-07

record_kind: PROMPT_CONTRACT_RECONCILIATION
record_status: FINAL
manual_review_run: NO

implementation_basis: eda6a7589af3a19a97eb38e869b47dabaf74388b
review_parent_sha: be6298b38440edfaa2d4221dcde498afc2a9170f
protocol_blob: 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d
implementation_source_change: NONE
canonical_count_change: NONE
canonical_status_change: NONE
new_finding_ids: NONE

active_scheduler_findings:
- BUG-SCHEDULER-WINDOW-01
- BUG-SCHEDULER-RESTORE-01

## Reviewed refinement chain

The following forward review checkpoints were inspected as one supplement chain:

1. review-runs/checkpoints/2026-10-06__eda6a758__download-open-roots-historical-agent-error-hardening.md
2. review-runs/checkpoints/2026-10-06__eda6a758__download-open-roots-ambiguity-closure-addendum.md
3. review-runs/checkpoints/2026-10-07__eda6a758__download-open-roots-detailed-spec-review.md

They are forward-only from the previously recorded review tip and do not change implementation source,
canonical counts, finding identity, or implementation priority.

## Independent adequacy judgment

The added concretization is materially useful and internally consistent.

### BUG-SCHEDULER-WINDOW-01

No further implementation-contract expansion is required beyond the current hardened rules:
- minute-granular membership;
- inclusive start minute;
- inclusive end minute;
- equal start/end = one active minute;
- external END effect at the first instant after the inclusive end minute;
- for every now with contains(now)==true, the matching END boundary is strictly later than now;
- AlarmManager END timestamp and durable fallback notBeforeAt are exactly equal;
- no stale just-ended owner survives after the first instant outside the window;
- final-effect tests must compose membership and external publication on one timeline.

The latest detailed-spec review explicitly finds this root's correction contract sufficient.

### BUG-SCHEDULER-RESTORE-01

The earlier persisted scheduler prompt is no longer complete enough for launch because the refinement chain
adds material closure obligations not fully stated there.

The strengthened contract requires all of the following together:

1. one complete effective scheduler image:
   E=(use_scheduler,schedule_start,schedule_end);
2. partial merge restore overlays only present scheduler keys onto the authoritative pre-restore image;
3. reset/authoritative restore uses its defined reset/default image;
4. validation is performed on the complete final E, not merely incoming keys;
5. scheduler-domain duplicate keys are either:
   - rejected; or
   - canonicalized with exactly the same deterministic ordering semantics as final preference publication;
6. the duplicate-key rule is identical across merge, reset, typed restore input, persisted recovery and
   external effect publication;
7. malformed duplicates cannot validate one occurrence while persisting another;
8. a restore payload with no scheduler-domain key must not create a spurious merge scheduler generation;
9. the three values form one semantic external-effect generation;
10. no transient partially-overlaid image may publish alarms/handoffs;
11. durable transition/recovery responsibility exists before durable executable preference authority can
    outrun external convergence;
12. old AlarmManager/WorkManager owner generations are superseded/revoked before a stale owner can win;
13. enabled restored image publishes exact start/end owners whose AlarmManager millis and durable
    notBeforeAt values agree;
14. disabled restored image converges to no scheduler owner;
15. process death at every durability/external-effect boundary remains recoverable and idempotent;
16. all scheduler setting writers/importers at final SHA are inventoried and participate in or are fenced
    by the canonical authority protocol.

## Prompt compatibility result

Previously persisted active prompt:
ytdlnisx/prompts/2026-10-06_GPT61_SOL_SCHEDULER_BOUNDARY_RESTORE_AUTHORITY_CLOSURE.md

That prompt already covers:
- the reopened end-minute residual;
- scheduler-domain HH:mm/Boolean validation;
- durable restore convergence ownership;
- stale owner revocation;
- process-death recovery;
- writer inventory at a high level;
- exact-SHA closure chaining.

It does NOT explicitly close the later review requirements for:
- effective post-overlay E for partial merge restore;
- duplicate scheduler-key canonicalization/rejection matching final persisted ordering;
- no-scheduler-key merge no-op semantics;
- duplicate-key recovery/typed-input consistency.

Because the implementation agent has not started this scheduler wave, protocol preflight requires prompt
revision before launch rather than accepting the known omission or injecting a mid-wave addendum.

Therefore:

OLD_SCHEDULER_PROMPT_STATUS=SUPERSEDED_BEFORE_START_BY_REVIEW_CONTRACT_HARDENING
NEW_HARDENED_SCHEDULER_PROMPT_REQUIRED=YES

## Protected FMT candidate

The paused FMT-PRODUCER candidate remains protected and must not be consumed by the scheduler wave:

- base HEAD: eda6a7589af3a19a97eb38e869b47dabaf74388b
- dirty tree: b3e7718074f66e62413d9eaae745da2d26b41cb3
- 16 unstaged files
- empty index
- publication NOT_STARTED
- exact-SHA closure NOT_EXECUTED
- reported draft verification: 51 JVM + 70 Android PASS, 0 failure/skip
- 16,579 prior protected evidence records unchanged at the stop boundary

Scheduler implementation must use an isolated clean worktree and must not clean/reset/stash-pop/apply/
reconstruct/rebase or otherwise consume the protected FMT draft.

## Next governed action

Persist and route one hardened scheduler authority implementation prompt based on:
- implementation SHA eda6a7589af3a19a97eb38e869b47dabaf74388b;
- this reconciliation;
- the three refinement checkpoints;
- existing scheduler reopen checkpoint;
- protocol blob 0a36d0debbc68e97c95cdb4d633bf80b6a20e54d.

The prompt must preserve same-wave:
implementation -> pre-publication gates -> normal fast-forward publication -> exact-final-SHA closure ->
independent completion review boundary.

INDEPENDENT_REVIEW_REQUIRED=YES
