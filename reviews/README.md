# Independent Correctness Review Records

This branch is the additive independent-review history for the correctness-remediation workflow.

## Branch authority

- Semantic implementation and test changes belong on `checkpoint/pre-baseline-review`.
- Independent review records belong on `review/remediation`.
- Independently established closure records belong on `ledger/remediation`.
- Implementation agents may read this branch but must not author independent verdicts on behalf of the reviewer.

## Exact-SHA rule

Every review record targets an exact implementation SHA. A review of SHA A does not automatically review a later SHA B.

Recommended layout:

```text
reviews/
  <DEFECT-ID>/
    <reviewed-implementation-sha>-review.md
```

Each review record should include at least:

- `Defect-ID`
- `Reviewed-Implementation-SHA`
- `Review-Base` where relevant
- `Verdict`: `CLEAN`, `NOT_CLEAN`, or `CLEAN_WITH_WAIVERS`
- P0/P1/P2 blockers
- blocker attribution separately from blocking status
- exact source paths/functions
- production authority/reproduction chain
- execution evidence
- required remediation boundary
- reviewer/date
- relation to prior findings
- whether ledger closure is now permitted

## History discipline

Review history is additive only. Do not amend, rebase, squash, force-push, or rewrite prior review records. If a prior review needs factual correction, add a new correcting review record.

A review commit must not modify production implementation semantics or create a closure decision that was not established by the review evidence itself.
