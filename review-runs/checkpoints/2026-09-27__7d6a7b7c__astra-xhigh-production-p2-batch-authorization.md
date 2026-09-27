# Remaining production P2 Astra XHigh batch authorization

checkpoint_kind: BATCH_IMPLEMENTATION_AUTHORIZATION
review_parent_sha: d0686a058f6aa8b4672bca8890a00e6ca76775cc
implementation_base_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
target_agent: ASTRA_XHIGH
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
production_open_p2_count: 13
tooling_open_p2_count: 0
overall_verdict: NOT_CLEAN

## User-authorized batch shape

The user explicitly requested that the remaining download-related production
bugs be implemented in one Astra XHigh session.

This authorizes one implementation-only batch wave containing the currently open
13 production P2 roots, while preserving per-root semantic attribution.

The implementation agent may process the declared roots sequentially in one
linear commit series without waiting for an independent review between each
root, subject to all of the following:
- one root per semantic commit boundary where practical;
- do not combine unrelated roots in the same commit;
- run each root's canonical focused acceptance before proceeding;
- do not change any finding disposition or claim FIXED-CLOSED inside the batch;
- do not rely on an earlier batch root as independently reviewed closure;
- before each next root, reread current live source and the canonical
  remediation-ready checkpoint so earlier batch edits do not make stale
  assumptions authoritative;
- if a new semantic root, dependency conflict, schema/migration expansion not
  already authorized, protected-state problem, or non-forward/ref conflict
  appears, STOP the batch at that root rather than skipping ahead;
- after the final root, rerun the union of all root-relevant regressions against
  the exact final committed SHA/tree before publication;
- publish the final linear series only through the current Section 1.1 exact
  expected-old CAS path;
- independent review after publication owns every root's final disposition.

## Ordered batch roots

1. BUG-DOWNLOAD-01
2. BUG-LOCALADD-06
3. BUG-UPDATER-02
4. BUG-SCHEDULER-05
5. BUG-ABI-01
6. BUG-HISTORY-04
7. BUG-MIGRATION-01
8. BUG-RUNTIME-01
9. BUG-TERMINAL-06
10. BUG-COOKIE-03
11. BUG-BACKUP-11
12. BUG-PAUSE-03
13. BUG-RESUME-01

The order follows the current authoritative remediation queue. Reorder only when
current exact source proves a dependency requires it; record the dependency and
keep each root separately attributable.

## Counts and closure

Tooling remains FIXED-CLOSED with tooling P2=0.
Production P2 remains 13 until independent post-batch review closes roots.
No repository-wide CLEAN claim is authorized.
