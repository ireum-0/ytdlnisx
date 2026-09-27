# Remaining production P2 Luna batch authorization

checkpoint_kind: BATCH_IMPLEMENTATION_AUTHORIZATION
review_parent_sha: c5af819dc9189776bdd4e04baa7684a851a816a9
implementation_base_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
target_agent: GPT_6_LUNA
active_remediation_scope_id: DOWNLOAD_CORRECTNESS_REMEDIATION
production_open_p2_count: 13
tooling_open_p2_count: 0
overall_verdict: NOT_CLEAN

## Routing decision

The user requests that the next implementation wave use Luna when Luna usage
becomes available again.

This checkpoint supersedes only the implementation-agent routing in the earlier
Astra XHigh batch authorization. It does not change finding identity, severity,
count, ordering, correction contracts, protected-state requirements, or final
independent-review ownership.

The current manual L4 review at
c5af819dc9189776bdd4e04baa7684a851a816a9 reconfirmed the 13-root production
inventory and added no new root. The batch therefore remains implementation-ready
at the same implementation SHA.

## Authorized batch shape

Luna may process all 13 production P2 roots sequentially in one implementation
session as one linear, reviewable commit series.

This is one implementation session, not one semantic mega-commit.

Requirements:
- keep every root separately attributable;
- use one logical root-level commit boundary where practical;
- before each root, reread current live source after all earlier batch commits;
- preserve the root's canonical invariant, forbidden shortcuts, production wiring,
  and affected regression contracts;
- execute focused verification before proceeding to the next root;
- do not self-declare any root FIXED-CLOSED;
- do not change canonical P0/P1/P2 counts inside the implementation wave;
- do not skip a failed root and continue to later roots;
- stop the batch on a new semantic root, unauthorized schema/dependency expansion,
  protected-state failure, governance conflict, implementation divergence, or
  unprovable destructive/publication authority;
- after all roots, rerun the union of materially affected regressions against the
  exact final committed SHA/tree;
- publish the full linear series only through the current Section 1.1 exact
  expected-old CAS path;
- independent post-publication review owns every root's final disposition.

## Ordered roots

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

Reordering is allowed only when current exact source proves a dependency requires
it. Any such reorder must remain separately attributable and must not weaken
another root.

## Preservation and closure

BUG-TOOLING-01 and BUG-TOOLING-02 remain FIXED-CLOSED.
TOOLING_OPEN_P2_COUNT=0.
Production P2 remains 13 until independent post-publication review records
closures.

The earlier Astra XHigh prompt/authorization remains historical evidence only
and is no longer the active implementation route after the private handoff is
updated to this Luna authorization.

No repository-wide CLEAN claim is authorized.

INDEPENDENT EXECUTION: NOT EXECUTED
