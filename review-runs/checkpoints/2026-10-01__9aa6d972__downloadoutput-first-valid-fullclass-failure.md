# 9aa6d972 DownloadOutput full-class first valid failure — bounded durable-state diagnosis required

checkpoint_kind: COMPLETED_VERIFICATION_RESULT_REVIEWER_CLASSIFICATION
review_parent_sha: d24c0a1da8ef2f201c230ed363a0eeaa7834310b
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 9aa6d972eafd885046cc6b2643f7f3c053c2527e
reported_local_candidate_parent: c5df12ad37dc54aacdd6ba13262db23ac6f56beb
reported_local_candidate_tree: bad5a70d1887d18d90c4c484a39292cccad03123
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
independent_execution: NOT_EXECUTED

## Accepted execution evidence

After the same-AVD cold restart, the full
`com.ireum.ytdl.database.DownloadOutputProductionWiringTest` class actually
executed.

Reported verifier result:
- instrumentationStarted=true;
- executedTests=21;
- skippedTests=0;
- failureCount=1;
- errorCount=0;
- gate status FAILED_EXIT_CODE.

This is the first valid full-class semantic/test execution after the prior
zero-test attach failures.

The single failing method is:
`realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement`.

The visible failure is:
`IllegalStateException: Timed out waiting for real DownloadWorker <uuid>`
from the shared enqueue/wait helper.

The remaining 20 methods completed without reported failure.

## Classification

Do not rerun the class unchanged.

This is not an infrastructure attach failure.

The current evidence is insufficient to classify the failure as:
- a production semantic residual;
- a fixture-only lifecycle-observation residual;
- or a distinct canonical root.

Therefore same-root mapping remains NOT_VERIFIED and no new finding ID is
created.

The governing DownloadOutput lifecycle checkpoint already requires that if the
focused/full class still fails after the cleanup-observation correction, the
first failure be preserved and durable state be captured before any second
speculative edit.

## Semantic boundary

The failing method's intended contract is still:
- ambient high-quality media is not accepted as authoritative current output;
- quality probing is bound to authoritative staged output;
- insufficient staged quality must not replace/delete the prior history media.

The timeout occurs before those post-wait assertions can establish whether that
contract passed or failed.

Accordingly, the timeout alone is not evidence that ambient media was consumed
or that old media was replaced.

## Required bounded diagnosis

Use existing preserved execution artifacts first. Do not rerun merely to obtain
context.

Bound the diagnosis to the failing worker/request and the direct
producer/carrier/recovery/final-effect path.

Recover where available:
- exact test method and WorkRequest UUID;
- candidate SHA/tree and connected-class identity;
- Download row/status/executionId at or near failure;
- exact DownloadWorkerExecutionOwners state;
- DownloadWorkerProcessOwners state;
- DownloadExecutionRecovery disposition/phase/carrier;
- producer recovery/finality state;
- native process registry/debt;
- cleanup-observation presence/absence for the exact attempt;
- WorkInfo state;
- log evidence showing the latest worker stage reached;
- whether videoQualityProbeForTesting ran and with which authoritative paths;
- whether History/output/old-media effects occurred.

Read-only extraction from existing verifier/test/log artifacts is authorized.

## Decision rule after diagnosis

If durable evidence shows the exact attempt completed cleanup and semantic
state is correct while the helper failed only to observe that completion,
classify as the existing BUG-DOWNLOAD-01 fixture/lifecycle residual and derive
one narrow verification-only correction.

If durable evidence shows stale ownership/recovery, an Active/PostProcessing
row without valid authority, incorrect output/history replacement, ambient
quality contamination, or another violated BUG-DOWNLOAD-01 invariant, classify
as an existing BUG-DOWNLOAD-01 production residual and derive the narrow
production correction from the proven mechanism.

If evidence instead proves a distinct root or remains ambiguous after the
bounded trace, stop without source change.

## State

BUG-DOWNLOAD-01 remains OPEN P2.
No root is closed.
Canonical counts are unchanged.
No detached diff gate or 19-gate union continuation is authorized yet.
