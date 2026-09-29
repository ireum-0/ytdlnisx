# GPT-6.1 Sol long-run diagnostic continuation authorization

checkpoint_kind: REVIEWER_CONTINUATION_POLICY
review_parent_sha: 5aa798ab8b6d36bc9ebd058caa22d3b05062ff7b
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: c9c09796d1cb08d04763710194665ad6119f86bb
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
production_p2: 13
tooling_p2: 1
independent_execution: NOT_EXECUTED

## Purpose

Extend the current GPT-6.1 Sol remediation run so that ambiguous failures do
not cause an immediate handoff when safe evidence collection can continue.

This checkpoint does not weaken any existing semantic boundary, does not
authorize speculative production changes, and does not alter canonical finding
counts.

## Long-run continuation rule

When a focused test or full-union gate fails, the implementation agent must not
immediately stop solely because the failure is initially ambiguous.

Before stopping, it should exhaust a bounded, production-faithful diagnostic
pass sufficient to establish whether the failure:

1. maps to an already-documented canonical OPEN root;
2. is a same-root residual/subcase;
3. is a test/harness precondition or stale fixture defect with an explicit
   canonical invariant;
4. is infrastructure/tooling within an already authorized tooling boundary; or
5. is genuinely new/unclassified or outside current governance.

Safe diagnostic work includes read-only source tracing and capture of durable
state already exposed by production/test seams: Room row state, execution and
operation identity, process/execution owners, recovery carrier disposition and
phase, WorkManager WorkInfo/state, exact verifier/JUnit/log evidence,
side-effect/claim/recovery ordering, and exact candidate SHA/tree.

Diagnostic work must remain bounded to the failing semantic path and its direct
producer/carrier/consumer/recovery/final-effect dependencies. Do not perform
broad exploratory rewrites or unrelated repository-wide changes.

## Continue without reviewer handoff when classification is already supported

After diagnostics, continue automatically if the evidence is sufficient to map
the failure to an EXISTING canonical remediation-ready root and the correction
boundary is already explicit.

In that case:
- record the classification in implementation evidence;
- make one separately attributable forward correction for that root;
- run its focused verification;
- on focused PASS restart the required exact-final-SHA union;
- continue through subsequent existing canonical remediation-ready roots.

An unambiguous stale test/harness defect may also be corrected without reviewer
handoff when the canonical checkpoint explicitly defines the intended semantic
contract and the correction does not weaken coverage.

For infrastructure/tooling failures, continue only within an existing
authorized tooling boundary and preserve first-failure evidence. Do not
retry-until-green.

## Current BUG-PAUSE-03 verification

The immediately authorized action remains the deterministic late-admission
verification child on c9c09796 under:
review-runs/checkpoints/2026-09-30__c9c09796__pause03-late-admission-precondition-classification.md

No Pause-All, recovery, lease, scheduler, claim, or WorkManager semantic change
is authorized merely because the prior precondition failed.

If the corrected Pause03 acceptance fails, first capture enough durable state
to determine whether B:
- failed before production admission;
- reached Active/E2 and exact owner publication;
- acquired a recovery carrier;
- had its WorkManager carrier stopped;
- or failed for a distinct infrastructure/harness reason.

If B reached Active/E2 and the real Pause-All operation then revoked B contrary
to the canonical invariant, that is sufficient to classify the failure as the
existing BUG-PAUSE-03 root. The agent may then continue with one narrow,
separately attributable BUG-PAUSE-03 semantic correction bounded by the
canonical remediation-ready checkpoint, followed by focused verification and a
fresh full union.

If B never reaches Active/E2, continue read-only diagnosis far enough to
identify the exact recovery/admission barrier. A production correction may be
made without reviewer handoff only if that barrier maps unambiguously to an
already documented canonical root with an explicit remediation boundary.
Otherwise stop.

## Hard stops that remain mandatory

Stop without speculative source changes for:
- genuinely new/unclassified semantic root;
- ambiguous mapping between multiple canonical roots;
- missing canonical remediation boundary;
- governance ambiguity or incompatible governance movement;
- writer race, divergence, or history rewrite;
- required destructive action;
- required source/scope expansion outside existing authorization;
- protected-state ambiguity;
- force/CAS lease failure;
- inability to preserve exact evidence/history.

These hard stops are unchanged.

## Commit and publication discipline

Continue to preserve:
- one separately attributable forward commit per semantic root;
- no amend/rebase/squash/history rewrite;
- no destructive reset/cleanup of protected state;
- exact-SHA focused and union evidence;
- first valid failure evidence;
- fresh destination/review checks before publication;
- only protocol-authorized expected-old CAS publication.

Longer autonomous duration is not permission to weaken source-semantic review or
tests. Passing tests do not replace production-semantic proof.

No root is FIXED-CLOSED and repository-wide CLEAN cannot be claimed before
publication and independent post-publication review.
