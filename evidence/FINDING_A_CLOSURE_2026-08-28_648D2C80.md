# Finding A Closure — 2026-08-28

This is a metadata-only ledger closure record. It records semantic and verification decisions already established by independent review; it does not introduce a new blocker-impacting classification, attribution decision, waiver, or semantic finding.

## Authority

- Implementation branch: `checkpoint/pre-baseline-review`
- Independently reviewed checkpoint: `648d2c8044e9d67f8a7367c54e3185f28206b636`
- Ledger branch reviewed for attribution/status truth: `ledger/remediation@276d578df85704e6b86d8342d31abe968e55b5e2`
- Governing review checklist: `REVIEW_CHECKLIST_V5_OPERATIONAL.md`
- Independent verdict: **Finding A — CLEAN**
- Waivers: **none**

The implementation and ledger refs were fresh-read again at the end of the independent review and remained identical to the SHAs above.

## Closed Finding-A scope

The frozen original Finding-A scope is closed at the reviewed checkpoint:

- A1 `LOWQUALITY-NO-CANDIDATE-CANCEL-RACE-01` — `SOURCE-LEVEL FIXED`
- A2 `WORKER-CLEANUP-SIBLING-FAULT-ISOLATION-01` — `SOURCE-LEVEL FIXED`
- A3 `CROSS-DOMAIN-DOWNLOAD-CANCEL-TERMINAL-ROW-01` — `SOURCE-LEVEL FIXED`
- A4 `WORKER-OWNERSHIP-HISTORY-CLEANUP-LEASE-01` — `SOURCE-LEVEL FIXED`
- A5 `WORKER-EXECUTION-LOCK-LEASE-ORDER-DEADLOCK-01` — `SOURCE-LEVEL FIXED`
- A6 `LOWQUALITY-CANCELLATION-PHASE2-CONVERGENCE-DEBT-01` — `SOURCE-LEVEL FIXED`
- A7 `LOWQUALITY-TERMINAL-DEBT-RETRY-RACE-01` — `SOURCE-LEVEL FIXED`
- A8 `HISTORY-STALE-FULLROW-REFERENCE-WRITERS-01` — `SOURCE-LEVEL FIXED`
- A9 `HISTORY-POSTCOMMIT-FINALIZATION-DEBT-01` — `SOURCE-LEVEL FIXED`
- A10 `HISTORY-REFERENCE-LOCK-ROOM-ORDER-DEADLOCK-01` — `SOURCE-LEVEL FIXED`
- A11 `LOWQUALITY-COORDINATOR-FAILURE-CLAIM-RACE-01` — `SOURCE-LEVEL FIXED`
- A12 `HISTORY-POSTCOMMIT-LATE-STOP-RECLASSIFICATION-01` — `SOURCE-LEVEL FIXED`

The remediation regression `BUG-ADMISSION-01 — Keep a successful Download claim recoverable across post-claim publication failure` is closed for the reviewed production checkpoint. Its exact claim CAS and claimed-row materialization remain one Room transaction, and the relevant production-path execution evidence passed.

The later P1 live-owner recovery regression found during final second-opinion review is also closed at `648d2c8044e9d67f8a7367c54e3185f28206b636`. `DownloadExecutionRecovery.reconcile()` now treats an exact `DownloadWorkerExecutionOwners` owner as positive liveness evidence after the per-Download side-effect lease and current Room reread, before abandoned-recovery mutation. Recovery therefore does not requeue, terminalize, finalize committed History, quiesce as stale, clear recovery carriers, or release the exact live worker owner. The discovery-to-claim race is revalidated at the mutation boundary under the same Download-ID lease used by production admission.

## Review Checklist v5 closure

At the reviewed checkpoint:

- open current-change Finding-A P1/P2: **0**
- accepted/waived current-change Finding-A P1/P2: **0**
- mandatory terminal fault matrix: **closed**
- mandatory cross-attempt matrix: **closed**
- mandatory live-owner matrix: **closed**
- recovery discovery closure for Finding-A durable carriers: **closed**
- blocker-relevant strengthened shared-helper contract propagation: **closed**
- blocker-relevant triggered conditional modules: **closed**
- production wiring was not replaced by helper-only evidence

The live-owner matrix was independently re-established in both directions:

1. no exact live owner + stale/running state -> abandoned recovery converges;
2. exact current live owner -> recovery preserves the exact owner and durable authority;
3. stale E1 debt + newer exact live E2 -> stale debt cannot mutate/cancel/revoke E2;
4. candidate discovered stale/unowned, then E2 claims before mutation -> final lease/reread revalidation preserves E2.

## Accepted execution evidence

The final remediation pass reported actual execution at the reviewed checkpoint, and the independent review confirmed that the current remote code still contained the corresponding production/test wiring.

- `git diff --check` — **PASS**
- `:app:compileDebugKotlin -x lint` — **PASS**
- `:app:testDebugUnitTest -x lint` — **PASS**
- focused Finding-A JVM set — **PASS, 27/27**
- `:app:compileDebugAndroidTestKotlin -x lint` — **PASS**
- direct Android instrumentation `FindingAProductionWiringTest` — **PASS, 34 tests**
- all six A11 production Room races — **PASS**
- direct Android instrumentation `HistoryReplacementBarrierPersistenceTest` — **PASS, 20 tests**

A separate uncached JVM rerun was **FAIL BEFORE EXECUTION** because AAPT2 could not start its daemon; no tests ran in that attempt. The standard connected Android-test Gradle path was also **FAIL BEFORE EXECUTION** because of the pre-existing `app/build.gradle:89` `applicationVariants` configuration error. Those infrastructure failures do not replace or negate the focused JVM/Room/instrumentation executions above. Direct adb instrumentation executed the required Android tests successfully.

GitHub did not expose CI status contexts or workflow runs for the reviewed checkpoint; remote CI evidence is therefore not part of this closure.

## Separately owned defects

This closure does not close, waive, reclassify, or reattribute unrelated baseline/post-split defects that remain independently owned in `TASKS.md` / `TASKS_DELTA.md`.

In particular, independently reviewed items such as `BUG-DOWNLOAD-01`, `BUG-SCHEDULER-06`, and `BUG-KEYWORD-05` remain separately owned according to their existing ledger records and are not Finding-A blockers merely because they are still open.

## Closure consequence

Finding A implementation/remediation is **CLOSED** at checkpoint `648d2c8044e9d67f8a7367c54e3185f28206b636` for the frozen Finding-A scope and the remediation regressions reviewed as part of that closure.

No further Finding-A production remediation is recommended from this closure record.

Build/verification performance stabilization may proceed as a separate semantic-neutral activity. Any stabilization commit above the semantic-clean checkpoint requires its own targeted review before it is treated as a new implementation baseline. Finding B has not been started by this ledger closure.
