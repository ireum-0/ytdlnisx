# BUG-TOOLING-02 no-force governance clarification

checkpoint_kind: GOVERNANCE_CLARIFICATION
review_parent_sha: 59fa0be430da1e612cd6d806f5d0e30e3e544a6c
implementation_head_sha: 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a
protocol_commit_sha: 7053bfa8019acb327da0061ff10b3554fcc9a28f
protocol_blob_sha: 7827e3d0a3d9901bfbce00d87362a1df9d2efd35
active_root: BUG-TOOLING-02
verdict: OPEN_P2_GOVERNANCE_BLOCKED
new_finding_ids: 0
active_remediation_open_p2_count: 13
tooling_open_p2_count: 1

## Clarified governance

The no-force rule is now explicit about both purpose and mechanism.

Purpose:
- preserve append-only externally referenced implementation/review evidence;
- prevent one writer from replacing another writer's authority by bypassing
  ordinary forward-update admission;
- keep recorded SHA ancestry and checkpoint evidence reproducible.

Mechanism:
- force push, force-with-lease, leading-plus refspecs, API force updates, and
  equivalent fast-forward-bypass operations are not authorized;
- force-with-lease is not treated as a special compare-and-swap exception even
  when its proposed new tip would remain forward-only.

If exact expected-old authority is required at the actual remote write boundary,
the closure mechanism must instead be a separately governed server-side
serialization or sole-writer design that preserves ordinary forward-only branch
updates.

## Current BUG-TOOLING-02 disposition

No currently configured repository mechanism provides that serialization.
Existing evidence remains:
- implementation branch unprotected;
- branch protection disabled;
- repository rulesets empty;
- current workflows do not provide a serialized implementation-branch writer.

Therefore BUG-TOOLING-02 remains OPEN P2 / GOVERNANCE BLOCKED.
BUG-TOOLING-01 remains FIXED-CLOSED.
No Luna implementation prompt is authorized from this state.
Production BUG-DOWNLOAD-01 remains blocked.

This clarification changes governance wording, not the pinned verdict of prior
manual review runs.
