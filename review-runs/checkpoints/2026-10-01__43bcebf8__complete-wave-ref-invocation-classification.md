# 43bcebf8 Complete-Wave ref-read stop — invocation mismatch classified

checkpoint_kind: REVIEWER_COMPLETE_WAVE_INVOCATION_CLASSIFICATION_AND_BOUNDED_CONTINUATION_AUTHORIZATION
review_parent_sha: 201085120292ce160edf158212e11db1caceb146
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 43bcebf8d4f796ed6e6759ebe03b0e5925442444
reported_local_candidate_parent: 250b495941237f207537b244fca8c92f62de0e62
reported_local_candidate_tree: c187d28025799cb38869d251bed0795801d5936a
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Preserved execution evidence

Implementation-agent report for exact candidate 43bcebf8:

- DownloadOutputProductionWiringTest: 21 PASS, 0 FAIL.
- detached exact-SHA diff proof: PASS.
- complete 19-gate exact-final-SHA union: 19/19 gates PASS.
- aggregate union result: 242 PASS, 0 FAIL, zero skips/errors.
- verifier finalization: PASS.
- candidate HEAD/tree/parent remained exactly
  43bcebf8d4f796ed6e6759ebe03b0e5925442444 /
  c187d28025799cb38869d251bed0795801d5936a /
  250b495941237f207537b244fca8c92f62de0e62.
- no source edit, new commit, or publication occurred.

The subsequent Complete-Wave Check stopped before semantic completion because the remote-ref read
returned exit 2. API24/25 was therefore not executed.

These execution counts remain implementation-agent evidence. The reviewer did not independently
execute them.

## Independent exact-source/tooling classification

GitHub currently proves:

- refs/heads/checkpoint/pre-baseline-review exists and equals
  7d6a7b7c445d9e45297032fa0521a1fc1d732eb9.
- Complete-Wave.ps1 passes its ImplementationRef and ReviewRef values to
  Get-RemediationRemoteRefSha().
- Get-RemediationRemoteRefSha() treats the supplied value as a branch name and internally builds:
  refs/heads/<BranchName>
  for both check-ref-format and git ls-remote.
- Therefore the correct Complete-Wave values are:
  ImplementationRef = checkpoint/pre-baseline-review
  ReviewRef = review/remediation
  not full refs beginning with refs/heads/.

The reported error naming origin/refs/heads/checkpoint/pre-baseline-review is consistent with the
caller supplying refs/heads/checkpoint/pre-baseline-review as BranchName, causing the helper to
query a doubly-prefixed remote ref.

Classification:

COMPLETE_WAVE_INVOCATION_FULL_REF_BRANCH_NAME_MISMATCH

This is an invocation/orchestration failure, not a production semantic failure, not a test-harness
semantic failure, and not a new canonical production root.

No source/tooling edit is required to continue. Canonical production counts are unchanged.

## Evidence reuse

The exact candidate has not changed since the complete DownloadOutput class, detached diff, and
19-gate union PASS evidence was produced.

Under the exact-final-SHA execution contract, those passing results remain valid for the same
committed SHA/tree as long as no behavior-relevant source/test/config tree changes occur.

Do not rerun the DownloadOutput class, detached diff, or 19-gate union merely because the
Complete-Wave invocation was malformed.

## Bounded continuation authorization

Authorize one corrected Complete-Wave Check invocation on exact candidate 43bcebf8.

Use the same preserved verification evidence, required gate IDs, expected remote base, and all
other semantic inputs from the failed Complete-Wave attempt/canonical campaign.

The only invocation correction is the ref representation:

- RemoteName = origin
- ImplementationRef = checkpoint/pre-baseline-review
- ReviewRef = review/remediation

Do not pass either ref with a refs/heads/ prefix.

Before invocation:
- fresh-check exact local HEAD/tree/parent and clean behavior-relevant worktree;
- fresh-check remote checkpoint/pre-baseline-review remains 7d6a7b7c...;
- fresh-check review/remediation is compatible forward movement;
- preserve the failed Complete-Wave evidence.

If corrected Complete-Wave Check fails for any reason other than the already-classified malformed
ref representation, preserve evidence and STOP for reviewer classification. Do not retry again.

If corrected Complete-Wave Check PASSes:
- proceed only to the already-required RESUME01 API24/25 proof;
- execute it only if both the exact previously authorized verifier definition and an already
  approved API24/25 execution target are available;
- otherwise record API24/25 NOT_EXECUTED and STOP;
- do not provision, recreate, substitute, reconfigure, or wipe a device.

No source/test/config edit, no commit, and no publication is authorized by this continuation.

BUG-DOWNLOAD-01 remains OPEN P2 until publication and independent post-publication review.
No root is FIXED-CLOSED.
Repository-wide CLEAN remains unsupported.

INDEPENDENT EXECUTION: NOT EXECUTED
