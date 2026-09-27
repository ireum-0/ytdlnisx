# BUG-TOOLING-02 user governance intent

checkpoint_kind: GOVERNANCE_INTENT
review_parent_sha: 2629309201acc5b631584213abf2a5c30252d916
implementation_head_sha: 52f84e49fafcc7e71fd3c19e2bc20e4e19985a9a
active_root: BUG-TOOLING-02
verdict: OPEN_P2_GOVERNANCE_BLOCKED

The user explicitly wants the no-force policy interpreted by its safety purpose:
preserve referenced history/evidence, forbid non-forward rewrite, and prevent
silent writer-authority overwrite.

The user also wants a narrow exact expected-old compare-and-swap exception to
be allowed when the resulting branch movement remains strictly forward-only.

This checkpoint records user intent only. It does not grant implementation
authorization where a higher-level project execution rule still prohibits the
mechanism.

Until that higher-level rule is changed, source/commit/push authorization for
this residual remains disabled and no Luna implementation prompt is authorized.

BUG-TOOLING-01 remains FIXED-CLOSED.
BUG-TOOLING-02 remains OPEN P2 / GOVERNANCE BLOCKED.
Production BUG-DOWNLOAD-01 remains blocked.
