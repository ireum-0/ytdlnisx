# BUG-CANCEL-02 stop-rule result — local candidate b8117240

checkpoint_kind: IMPLEMENTATION_STOP_RULE_EVIDENCE
review_parent_sha: `f41c8b72048ecc6b033ea5e203b0614c24d869fd`

authoritative_remote_implementation_sha:
`359ddbf9bf534009be095ad1bffea8ec45c899e4`

reported_local_only_candidate_sha:
`b811724047f6ddfbd7c9c534f876e7f36e5f27d8`

reported_local_parent:
`359ddbf9bf534009be095ad1bffea8ec45c899e4`

## Independent status

This is a valid stop-rule report with no pushed implementation change.

The remote implementation branch remains authoritative at
`359ddbf9bf534009be095ad1bffea8ec45c899e4`.

The local-only candidate `b8117240...` is not independently reviewable as exact GitHub source
and must not be treated as a completed implementation, a CLEAN basis, or source-closure evidence.

BUG-CANCEL-02 remains OPEN.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 19

No new finding ID is created.

## Reported implementation evidence

Implementation-agent report states that local candidate `b8117240...`:
- adds durable post-native effect phases and a process-held lease;
- gates publication against cancellation;
- adds recovery and production-wiring coverage;
- changes TerminalExecutionRecovery.kt, TerminalExecutionRegistry.kt,
  TerminalDownloadWorker.kt, TerminalExecutionRecoveryTest.kt, and
  TerminalExecutionProductionWiringTest.kt;
- passes compileDebugKotlin, compileDebugAndroidTestKotlin, and git diff --check;
- fails the focused connected TerminalExecutionProductionWiringTest at reported line 452 because
  the Terminal row still exists after WorkManager reports the worker finished;
- stops before later gates or production changes;
- is not pushed;
- preserves the six preexisting crash/replay logs.

These are implementation-agent/runtime evidence only.

## Classification boundary

The reported failure is material but its cause is NOT_VERIFIED.

Before authorizing production changes, distinguish at least:
1. production residual: the new durable effect/lease state does not converge row deletion after
   the exact effect owner and worker have actually quiesced;
2. stale or premature regression observation: the new test observes an intermediate durable state
   that the governing contract permits to converge through an explicit bounded recovery/quiescence
   step;
3. harness/seam lifetime defect: the new test seam prevents the convergence object it later expects;
4. a separate implementation mistake inside the local-only patch.

Because the exact local candidate patch is not on GitHub, none of these mechanisms is independently
confirmed here.

## Next governed action

Do not push `b8117240...` and do not broaden production changes.

Run one bounded no-push diagnostic on the preserved exact local candidate and first-failure evidence.
The diagnostic must first classify the row's exact durable execution phase, publication phase,
active-token/lease state, dispatch state, and WorkManager state at the failed assertion.

No production edit is authorized until the failure is classified against the governing
BUG-CANCEL-02 contract.

If the failure is a harness/observer-contract defect, a narrow test-only correction may be proposed
only with exact evidence that production semantics already satisfy the contract.

If the failure is a production residual in the same BUG-CANCEL-02 root, return the exact residual
sequence and minimal correction boundary for independent prompt authoring.

INDEPENDENT EXECUTION: NOT EXECUTED
