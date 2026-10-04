# BUG-UPDATER-04 source capsule reports liveness defect — canonical evidence persistence required

review_parent_sha: c64f9debf66afa278697e95f7a93acf0c165eca6
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
SOURCE_CAPSULE_REPORTS_LIVENESS_DEFECT
CANONICAL_PROMOTION_PENDING_REVIEWER_READABLE_EVIDENCE
NO_CORRECTION_YET
PUSH_NOT_AUTHORIZED
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted report summary:
- verdict reported: SOURCE_PROVES_LIVENESS_DEFECT;
- generic writer passes a consumer:-scoped identity into native execution;
- mutation recovery selection is limited to mutation_-scoped marker identities;
- later native-absence observation does not itself recover the retained consumer:-scoped generation;
- publication debt can therefore continue fencing readers while the retained live native debt is not selected by mutation recovery;
- historical focused-test false recovery cause remains NOT_VERIFIED;
- local candidate, indexes, diagnostic overlay, and prior evidence remain unchanged;
- no source/test/config edit, test execution, commit, or push occurred.

Local exact-source capsule:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-source-capsule-d4ffe947ec57407ea7920d2a78647bf4/REPORT.md

Independent-review limitation:
The exact candidate and capsule remain local-only. Before the provisional source verdict can become a canonical PROVEN_LIVENESS_DEFECT, the exact line-numbered capsule must be persisted to reviewer-readable GitHub evidence without changing candidate source.

Required next action:
Persist the complete exact-source capsule, or a byte-faithful reviewer-readable representation containing all 65 excerpts, hashes, line numbers, functions, provenance, and producer->carrier->selector->fence->liveness trace, under the private review branch.

The persisted evidence must bind:
- candidate commit 05c1fc2ed53531da6935f93470df93028bd799f3;
- tree d675b3b1bb2aa4e8570e0113d525b9582c3c367a;
- parent 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- the source file SHA-256 values recorded in the local capsule;
- verdict SOURCE_PROVES_LIVENESS_DEFECT;
- historical focused-test cause NOT_VERIFIED.

No source/test/config edit, device test, implementation commit, push, cleanup, reset, or evidence overwrite is authorized.

After persistence, stop for independent reviewer reading of the persisted evidence. Do not author a correction yet.

The separate debug-package isolation follow-up remains mandatory and separate.

INDEPENDENT EXECUTION: NOT EXECUTED
