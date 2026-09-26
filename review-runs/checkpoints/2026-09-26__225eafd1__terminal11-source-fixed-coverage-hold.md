# 225eafd1 — BUG-TERMINAL-11 source-fixed / coverage hold

Reviewed implementation: 225eafd1904a6500d1e1aa6db2aa37d26e10650b
Parent: 3ffe5b3545dc3616616a9d1e8b3758b93147ddd0
Review parent: 74009591ae59cccc557d37f6fcefb14cf60ba2ad
Protocol: c6cac5f3d7ad10dddb68343f6684e95ae915366c
Governing checklist: v7 e758358ff6d8952470ef3b07f5b18fb26ed4c05c

Verdict: NOT_CLEAN overall.

BUG-TERMINAL-11 source semantics are fixed at 225eafd1:
- bare/empty provider-option occurrences are rejected;
- structurally non-ProviderTree provider metadata is malformed;
- current insertion rejects before row/carrier durability;
- durable state returns typed Malformed;
- generation 2 does not override malformed metadata;
- valid ProviderTree metadata remains legacy SelfBound;
- malformed rows stay per-row non-runnable, exact carriers are superseded, missing carriers are not reconstructed, siblings continue, and worker admission refuses before planner/native;
- BUG-TERMINAL-03 generation provenance remains closed.

Closure gap:
3ffe5b35 could itself persist generation-2 row/carrier state containing the malformed provider token. Current tests prove:
- provider malformed classification at generations 1 and 2;
- provider-malformed sibling isolation through production reconciliation at legacy/no-carrier state;
- malformed outstanding-carrier supersession through production reconciliation for another malformed metadata form;
- strict current insertion.

But no test directly seeds a generation-2 malformed-provider row plus outstanding carrier and runs production reconciliation. v7 Module F and the CLEAN gate require actual production-wiring coverage for this material persisted-generation cell rather than helper-only substitution.

Required final test-only cell:
seed row + generation-2 TERMINAL_DISPATCH carrier with current-format marker and bare/empty or structurally unusable provider metadata; run real reconcile; prove exact carrier SUPERSEDED, no enqueue/admission, row/command preserved, and a valid sibling still converges.

Status:
BUG-TERMINAL-11 = SOURCE-FIXED / TEST-COVERAGE-INCOMPLETE.
No new finding ID. Canonical totals remain P0=0 / P1=0 / P2=19.
CLEAN_REVIEW_BASIS remains 74f57e695db30b701ad429af311c39a763bfe086.

Lens coverage: L1 DEEP; L2-L6 BASELINE.
Primary DEEP: L1 by persisted-generation/recovery relevance.
Next not-yet-DEEP: L2.

Triggered modules:
- B PASS
- C PASS for structural provider representation; grant liveness remains BUG-BACKUP-11
- F GAP only for direct generation-2 malformed-carrier production wiring
- H PASS source-semantically
- v7 thread-affinity not triggered
- v7 invalid-identity-transformation propagation not triggered

Implementation-agent exact-SHA evidence:
27/27 focused persisted-generation; 23/23 SAF+dispatch+execution; 28/28 shared WorkManager; connected 78/78; JVM 68/68; both Kotlin compiles PASS; git diff --check PASS.
GitHub statuses/workflows/check-runs: none.

INDEPENDENT EXECUTION: NOT EXECUTED
