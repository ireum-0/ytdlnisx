# BUG-TOOLING-02 no-force policy correction

checkpoint_kind: GOVERNANCE_CLARIFICATION
review_parent_sha: 2e2d9d3c72a83e543a8c522d5c665fd41465fa9e
implementation_head_sha: 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a
protocol_commit_sha: 27e6752f6404571fd2c8f998b8f2eb74ae7c33f2
protocol_blob_sha: 8699b62d62b96448a5213049ac1f6abe0ddd9018
active_root: BUG-TOOLING-02
verdict: OPEN_P2_GOVERNANCE_BLOCKED
tooling_open_p2_count: 1
active_remediation_open_p2_count: 13

The prior clarification was too broad.

The no-force rule exists to preserve referenced commits/evidence, forbid
non-forward history rewrite, prevent silent overwrite of another writer's
accepted authority, and keep ancestry reproducible.

An exact expected-old conditional branch update can be consistent with that
safety purpose when it is used only as a compare-and-swap guard and the
resulting branch movement remains strictly forward-only.

The repository protocol now records that distinction.

This governance correction does not itself authorize a new implementation write
mechanism. Current project execution rules still control what implementation
agents may use.

Until those rules explicitly authorize the narrow expected-old exception,
BUG-TOOLING-02 remains OPEN P2 / GOVERNANCE BLOCKED, BUG-TOOLING-01 remains
FIXED-CLOSED, no implementation prompt is authorized, and BUG-DOWNLOAD-01
remains blocked.

No production or tooling source changed.
