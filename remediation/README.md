# Correctness Remediation Master Plan

The authoritative remediation plan for this project is stored on this branch as:

`remediation/YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md.gz`

The artifact is gzip-compressed only because the plan is large. Decode it before reading.

Recommended run-bootstrap sequence:

```bash
git fetch origin
PLAN_SHA=$(git rev-parse origin/plan/remediation)
git show "$PLAN_SHA":remediation/YTDLnisX_CORRECTNESS_REMEDIATION_MASTER_PLAN.md.gz | gzip -dc
```

Record the exact `PLAN_SHA` used for the run and keep that Plan SHA pinned for the duration of the run unless the user explicitly instructs adoption of a newer plan.

Do not check out `plan/remediation` into the implementation worktree merely to read the plan.

Branch authority remains separated:

- `plan/remediation`: planning/governance only
- `checkpoint/pre-baseline-review`: semantic implementation/test corrections
- `review/remediation`: independent exact-SHA review history
- `ledger/remediation`: independently established closure records

Fresh current production source is authoritative for source-state facts. The pinned Master Plan is authoritative for workflow, gates, invariants, review discipline, branch authority, and execution policy. A plan commit never creates an independent CLEAN verdict.
