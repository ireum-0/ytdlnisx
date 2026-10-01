# 43bcebf8 Complete-Wave review ancestry stop — stale recorded tip / local object precondition classified

checkpoint_kind: REVIEWER_COMPLETE_WAVE_REVIEW_ANCESTRY_CLASSIFICATION_AND_BOUNDED_RETRY_AUTHORIZATION
review_parent_sha: e45ffdb6ac9e42cf76aa74c613da8a7f8ff83f82
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 43bcebf8d4f796ed6e6759ebe03b0e5925442444
reported_local_candidate_parent: 250b495941237f207537b244fca8c92f62de0e62
reported_local_candidate_tree: c187d28025799cb38869d251bed0795801d5936a
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Preserved exact-candidate evidence

Implementation-agent evidence remains:

- DownloadOutputProductionWiringTest: 21 PASS, 0 FAIL.
- detached exact-SHA diff: PASS.
- complete exact-final-SHA union: 19/19 gates PASS.
- aggregate union: 242 PASS, 0 FAIL, zero skips/errors.
- verifier finalization: PASS.
- exact candidate remains
  43bcebf8d4f796ed6e6759ebe03b0e5925442444 /
  c187d28025799cb38869d251bed0795801d5936a /
  250b495941237f207537b244fca8c92f62de0e62.
- no source/test/config edit, new commit, or publication occurred.

The corrected branch-name Complete-Wave invocation successfully read both remote refs, then stopped
because it could not prove recorded review tip 201085120292ce160edf158212e11db1caceb146 in the
local ancestry of live review tip e45ffdb6ac9e42cf76aa74c613da8a7f8ff83f82.

API24/25 therefore remains NOT_EXECUTED.

## Independent GitHub classification

GitHub authoritatively proves:

- review/remediation live tip is e45ffdb6ac9e42cf76aa74c613da8a7f8ff83f82;
- compare(20108512...e45ffdb6) is status ahead, ahead_by=1, behind_by=0;
- merge base is exactly 201085120292ce160edf158212e11db1caceb146;
- e45ffdb6 parent is exactly 201085120292ce160edf158212e11db1caceb146;
- the only delta is
  review-runs/checkpoints/2026-10-01__43bcebf8__complete-wave-ref-invocation-classification.md;
- that delta does not change candidate source, tests, verification scope, canonical counts, or the
  already-passing exact-candidate evidence.

Complete-Wave.ps1 obtains the live review SHA with git ls-remote, then uses local
merge-base --is-ancestor only when live review differs from RecordedReviewTip. ls-remote alone does
not materialize the live review commit object in the local candidate repository.

Therefore the failed local ancestry proof does not contradict the GitHub-authoritative forward
relationship. It reflects a stale RecordedReviewTip combined with an unavailable local live-review
object for Complete-Wave's mechanical ancestry check.

Classification:

COMPLETE_WAVE_REVIEW_TIP_STALE_LOCAL_ANCESTRY_OBJECT_UNAVAILABLE

This is an orchestration/tooling-precondition failure. It is not:
- a production semantic failure;
- a test semantic failure;
- a review divergence/rewrite;
- a new canonical production root.

Canonical production counts remain unchanged.

## Bounded retry design

Do not keep reusing RecordedReviewTip=20108512 after the reviewer has already reconciled the
forward review movement.

This checkpoint becomes the new canonical review baseline for the next Complete-Wave Check.
The next handoff must record this checkpoint commit as REVIEW_TIP and must pass that exact SHA as
Complete-Wave -RecordedReviewTip.

For this one bounded retry, require an exact hard review-tip gate:
- live review/remediation must equal the newly recorded checkpoint SHA before Complete-Wave starts;
- if review/remediation moves again, STOP for reviewer reconciliation rather than attempting another
  local ancestry proof inside the same task.

Because live review and RecordedReviewTip will then be identical, Complete-Wave does not require a
local merge-base proof for review movement.

Preserve:
- ImplementationRef=checkpoint/pre-baseline-review;
- ReviewRef=review/remediation;
- branch-name form only, without refs/heads/ prefix;
- exact candidate 43bcebf8;
- existing verification evidence path and exact RequiredGateIds from the preserved 19-gate union;
- ExpectedRemoteBaseSha=7d6a7b7c445d9e45297032fa0521a1fc1d732eb9;
- all protected evidence from both prior Complete-Wave failures.

Do not rerun:
- DownloadOutput class;
- detached diff;
- 19-gate union.

Authorize exactly one further Complete-Wave Check with the refreshed RecordedReviewTip.

If it fails for any reason, preserve evidence and STOP. No fourth attempt is authorized by this
checkpoint.

If it PASSes:
- proceed only to the existing required RESUME01 API24/25 proof;
- execute only if both an exact previously authorized verifier definition and an approved API24/25
  execution target exist;
- otherwise record API24/25 NOT_EXECUTED with the exact blocker and STOP;
- do not provision/recreate/substitute/reconfigure/wipe a device.

No source/test/config/tooling edit, new commit, or publication is authorized.

BUG-DOWNLOAD-01 remains OPEN P2 until all governing publication and independent review gates close.
No root is FIXED-CLOSED.
Repository-wide CLEAN remains unsupported.

INDEPENDENT EXECUTION: NOT EXECUTED
