# Correctness Remediation Plan

The authoritative versioned remediation Master Plan is on this branch under:

`remediation/master-plan/README.md`

The manifest defines the exact ordered-part representation, whole-plan SHA-256, bootstrap/pinning rules, and branch authority. Read every `remediation/master-plan/parts/*.part` file in lexical order from one exact pinned Plan SHA.

Do not use the historical single-file `.md.gz` transport attempt; it was detected as incomplete and is removed by the verified multipart publication.

Do not check out `plan/remediation` into the implementation worktree merely to read the plan. Fetch it and read it by exact commit.
