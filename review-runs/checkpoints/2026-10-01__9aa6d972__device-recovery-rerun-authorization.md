# 9aa6d972 zero-test instrumentation attach — device-recovery rerun authorization

checkpoint_kind: INFRASTRUCTURE_RECOVERY_AUTHORIZATION
review_parent_sha: 41c87ec008fa6b728a565a06925d0ab0b9042a1d
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 9aa6d972eafd885046cc6b2643f7f3c053c2527e
reported_local_candidate_parent: c5df12ad37dc54aacdd6ba13262db23ac6f56beb
reported_local_candidate_tree: bad5a70d1887d18d90c4c484a39292cccad03123
publication_status: NOT_PUBLISHED
overall_verdict: NOT_CLEAN
new_finding_ids: 0
count_change: 0
production_p2: 13
tooling_p2: 1
independent_execution: NOT_EXECUTED

## Classification

The prior g16 full DownloadOutput class attempt remains classified as:

ZERO_TEST_INSTRUMENTATION_TARGET_PROCESS_ATTACH_INFRASTRUCTURE_FAILURE

It executed zero semantic tests. No production semantic failure is established.

The immediately preceding exact-method execution on the same candidate
completed 1/1 PASS and proved the intended DownloadOutput cleanup semantics for
the first previously failing method.

## Material infrastructure change

After the g16 stop, the user reports that a device-side error existed and has
now been resolved.

The reviewer does not independently classify the exact device defect from that
statement alone. The exact repair mechanism remains outside this checkpoint.

However, the reported device repair is a MATERIAL change to the instrumentation
startup precondition. Therefore one new full-class attempt after a fresh health
preflight is not an unchanged green-seeking rerun.

No source/test/tooling commit is required or authorized merely to exercise this
recovered infrastructure state.

## Authorized continuation

Preserve exact candidate:
9aa6d972eafd885046cc6b2643f7f3c053c2527e

Reuse the already completed:
- exact Android-test compile g14 PASS;
- exact original focused method g15 1/1 PASS and its durable semantic proof.

Do not recreate, amend, squash, or replace the 9aa6d972 child.

Before the new class run, perform a fresh read-only device/instrumentation
preflight sufficient to prove:
- the expected authorized device is visible and responsive;
- sys.boot_completed=1;
- PackageManager is responsive;
- ActivityManager/instrumentation launch service is responsive where directly
  checkable without mutation;
- no stale instrumentation session is reported as active;
- the target/test package identities selected by the verifier are the expected
  exact candidate artifacts;
- current candidate HEAD/tree remain exact.

Do not modify application data merely for the preflight.

## One recovered full-class attempt

After the preflight passes, run the complete
DownloadOutputProductionWiringTest once on exact candidate 9aa6d972.

The user's thread-scoped verifier-launch awake request remains permitted:
- only around the synchronous verifier launch;
- clear after launch completes;
- respect deliberate sleep;
- no persistent power-setting changes.

This execution is the first attempt after the materially changed device
precondition and is authorized even though the test command/class is otherwise
the same as g16.

Required result:
- nonzero executed test count;
- zero unexplained failures/errors;
- exact class identity;
- exact SHA/tree binding;
- preserve all durable semantic assertions introduced by 9aa6d972.

If a VALID semantic failure occurs:
- preserve first failure;
- use the existing long-run bounded-diagnostic policy;
- continue automatically only if it maps unambiguously to an existing canonical
  remediation-ready root.

If the run again produces ZERO TESTS:
- preserve exact startup/attach evidence;
- do not immediately repeat the unchanged class;
- perform bounded infrastructure diagnosis against the new post-repair state.

If the second zero-test cause is clearly a fresh recoverable device/ADB/service
availability regression, existing canonical infrastructure precedent may be
used for one materially different same-AVD recovery only within already
documented non-wiping boundaries.

If the device remains healthy but target-process attachment again times out
without a supported changed precondition, STOP for reviewer classification.
Do not kill arbitrary processes, wipe/recreate the AVD, alter SDK/config, or
weaken tests.

## After full-class PASS

Run the required detached exact-SHA diff gate.

Then restart the COMPLETE 19-gate exact-final-SHA union from partition 1 and
resume the existing long-run bounded-diagnostic remediation campaign.

Do not stop for intermediate successful gates.

## Complete-Wave and publication

Only after complete exact-final-SHA union PASS:
- run Complete-Wave.

Publication remains forbidden until the required RESUME-01 API24/25 real-device
verification passes.

If API24/25 remains unavailable after union and Complete-Wave, preserve the
candidate/evidence and stop with that exact publication blocker.

If every required verification gate passes:
- fresh-check destination implementation HEAD and review/remediation;
- require compatible expected ancestry;
- use only protocol-authorized expected-old strictly-forward CAS publication;
- verify remote equality;
- record final published SHA/tree/evidence;
- stop implementation campaign for independent post-publication review.

No root is FIXED-CLOSED and repository-wide CLEAN is unsupported before that
independent review.
