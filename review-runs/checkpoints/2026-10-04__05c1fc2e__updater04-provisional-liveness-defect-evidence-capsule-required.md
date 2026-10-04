# BUG-UPDATER-04 retry audit — provisional liveness defect requires exact-source evidence capsule

review_parent_sha: d238c9f7d5d7ffd70191745cc390f497bcde4e4f
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
PROVISIONAL_LIVENESS_DEFECT_REPORTED
INDEPENDENT_SOURCE_CONFIRMATION_PENDING
EVIDENCE_CAPSULE_REQUIRED
NO_CORRECTION_YET
PUSH_NOT_AUTHORIZED
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted audit report summary:
- provisional verdict: PROVEN_LIVENESS_DEFECT;
- claimed mechanism: generic mutation-classified requests can leave a live consumer:-scoped native generation outside mutation recovery selection while publication debt continues blocking ordinary readers;
- historical focused-test failure cause remains NOT_VERIFIED;
- local candidate, indexes, diagnostic overlay, and prior evidence remained unchanged;
- no edits/tests/commits/pushes occurred during the audit.

Audit report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-retry-audit-muspsrn4-aj60gbf4/REPORT.md

Independent-review limitation:
The exact candidate commit 05c1fc2ed53531da6935f93470df93028bd799f3 is local-only and is not readable through the authoritative GitHub implementation ref. The critical YtdlpRuntimeAuthority.kt candidate content is likewise not independently retrievable from GitHub. Therefore the provisional source conclusion cannot yet be promoted to a canonical PROVEN_LIVENESS_DEFECT solely from the summary.

Required evidence capsule:
Produce a read-only, reviewer-verifiable exact-source capsule from the preserved local candidate. It must include exact file hashes plus line-numbered excerpts sufficient to prove or disprove the claimed mechanism, specifically:

1. Mutation classification:
   - exact function(s) deciding that the relevant generic request is mutation-classified;
   - exact branch/condition;
   - exact authority acquired.

2. Native process identity producer:
   - exact function constructing/passing the processId for that mutation-classified request;
   - proof that the resulting identity can be consumer:-scoped rather than mutation-scoped;
   - exact generation-token binding path.

3. Durable carrier:
   - marker/generation publication path for that processId;
   - what survives writer release/restart.

4. Mutation recovery selector:
   - exact production function(s) enumerating/selecting mutation debt for recovery;
   - exact namespace/prefix/identity criteria;
   - proof that the consumer:-scoped generation is not selected.

5. Reader publication-debt fence:
   - exact production function/path that continues to block ordinary reader admission while the debt remains unresolved;
   - exact durable publication-debt carrier and release condition.

6. Liveness:
   - exact later retry/recovery entry points;
   - proof that none can select/recover that stranded generation automatically, OR proof that one can.

7. Narrow correction:
   - smallest same-root correction that aligns mutation classification, durable native identity, and recovery selection without adding a second durable store or weakening reader fencing.

For every excerpt record:
- repository-relative path;
- local candidate file SHA-256;
- line numbers;
- exact excerpt;
- function name;
- whether content is production or test;
- whether it existed before or was introduced/changed by the BUG-UPDATER-04 candidate if known.

No source/test/config edit, no device test, no commit, no push, and no cleanup/reconstruction is authorized.

The separate debug-package isolation follow-up remains mandatory and must remain separate.

INDEPENDENT EXECUTION: NOT EXECUTED
