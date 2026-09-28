# BUG-TOOLING-01 bounded-token present but custom EvidenceRoot exceeds final path budget

checkpoint_kind: COMPLETED_IMPLEMENTATION_DIAGNOSTIC_RECONCILIATION
review_parent_sha: 06d6b834b31611472dfd2bb8613057dcbe00385f
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: ff3a111a784fc50c43aed0aafc0f62776e7e0bd4
reported_local_candidate_parent: a27074d6ea8b56be01a9cfb3159a73c018d1b5d0
reported_local_candidate_tree: 804ce5ea9388308378fe54c6f6acb824688371c8
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Classification

B. PRIOR_BOUNDED_PATH_CORRECTION_PRESENT_SAME_ROOT_RESIDUAL

The prior bounded per-gate evidence-path correction is present in the exact
reported ff3a111a lineage, but its fixed per-gate token does not guarantee the
length of the complete filesystem path when the caller supplies a longer custom
EvidenceRoot.

No new finding ID is created.

BUG-TOOLING-01 remains OPEN P2 / SAME-ROOT RESIDUAL.
BUG-DOWNLOAD-01 remains OPEN P2 / FOCUSED NOT EXECUTED.

## Exact reported lineage/source trace

The implementation agent reports:
- exact HEAD ff3a111a784fc50c43aed0aafc0f62776e7e0bd4;
- parent a27074d6ea8b56be01a9cfb3159a73c018d1b5d0;
- tree 804ce5ea9388308378fe54c6f6acb824688371c8;
- behavior-relevant tracked/non-ignored-untracked state clean;
- prior commit 29034eaeac30fc68b5e45c9f8f182dc06ec5131e is an ancestor;
- that prior commit is titled
  "fix(remediation): bound per-gate evidence paths".

The failing full semantic gate ID is:
connected:com.ireum.ytdl.database.DownloadWorkerCleanupProductionWiringTest

The local verifier maps it to:
connecte-29e60e2b9f6d0fb85ce7879aa9c98f21

Reported token design:
- first 8 sanitized characters;
- 32 lowercase SHA-256 hex characters;
- deterministic mapping;
- in-run collision check;
- full semantic ID and token mapping retained in evidence.

The failing filesystem path uses that bounded token, not the raw/full gate ID.

Reported failing suffix:
adb-devices-8d99f50941.stdout.log

Reported lengths:
- bounded-token directory path: 232 characters;
- final failing file path: 266 characters;
- same file under the verifier's default run root would be 236 characters.

The custom EvidenceRoot added the
"download01-direct-output-hook" path segment and moved the final path beyond the
legacy Windows path boundary observed by this run.

## Failure point and classification semantics

The implementation agent reports:
- Process.Start() occurs first in Remediation.Common.ps1;
- the later five-argument FileStream constructor attempts to open the stdout
  evidence file and throws System.Management.Automation.MethodInvocationException;
- run evidence has deviceProbeStarted=null;
- no captured ADB probe result exists;
- tests=0;
- gradleStarted=false.

Therefore the independently successful boot/PackageManager readiness check does
not convert this verifier result into a successful health probe.

The verifier's overall BLOCKED_BY_TOOLING_INFRASTRUCTURE classification is
correctly non-semantic, but its inner event "adb_probe_start_failure" is too
coarse because source order indicates the process-start call was reached and
the preserved failure is evidence-file materialization, not a captured device
health result.

## Root semantics

The prior correction solved one dimension:
unbounded semantic gate identifiers in path segments.

It did not establish a whole-path invariant over:
- caller-supplied EvidenceRoot;
- run-owned subdirectories;
- per-gate directories;
- command log filenames.

The same BUG-TOOLING-01 root therefore remains open.

This is counted once:
- production P0=0 / P1=0 / P2=13;
- tooling P0=0 / P1=0 / P2=1.

## Narrow correction boundary

Preserve ff3a111a and every prior commit.

Authorize one separately attributable tooling-only child.

Preferred edit surface:
- tools/remediation/Invoke-Verification.ps1
- tools/remediation/Test-ExecutionLifetimeProvenance.ps1
- tools/remediation/README.md

Do not change Android production/test source, Gradle/schema/dependencies,
Complete-Wave.ps1, or earlier commits.

Do not change Remediation.Common.ps1 unless exact implementation work proves the
whole-path invariant cannot be enforced at the verifier caller boundary. If that
file is genuinely required, STOP before editing and report exact proof for an
authority decision.

Required semantics:
1. define a documented safe whole-path budget for verifier-generated evidence
   artifacts on supported Windows execution;
2. budget the complete final path from the actual caller-supplied EvidenceRoot,
   not only the semantic gate token;
3. use a fixed-depth, bounded relative artifact layout under EvidenceRoot whose
   maximum suffix length is known before process launch;
4. preserve the full gate/class and all semantic identifiers in JSON/evidence;
5. preserve deterministic, collision-resistant mappings from short filesystem
   tokens to full identifiers;
6. if the supplied EvidenceRoot is itself too long for even the shortest valid
   relative layout, fail before launching the probe with an explicit
   tooling/evidence-path-budget classification and exact calculated lengths;
7. classify wrapped FileStream/path materialization errors specifically as
   tooling evidence-path/bootstrap failures rather than generic
   adb_probe_start_failure;
8. do not weaken actual ADB/boot/PackageManager circuit breakers.

Do not rely solely on enabling OS long-path support, source-worktree relocation,
or asking the caller to shorten EvidenceRoot. The verifier must either generate
a path within its documented contract or fail closed before the probe with a
specific diagnostic.

## Acceptance

Acceptance must include:
- all previously passing tooling acceptance cells;
- the existing long semantic gate case;
- a custom EvidenceRoot reproducing the extra
  "download01-direct-output-hook"-style segment while staying within the
  supported root contract;
- proof that the final ADB stdout/stderr artifact paths remain within the
  documented whole-path budget;
- actual synthetic ADB probe invocation under that case;
- preservation of full semantic ID -> bounded token mapping;
- deterministic distinct tokens for distinct long IDs;
- a deliberately over-budget EvidenceRoot proving pre-launch explicit
  EVIDENCE_PATH_BUDGET-style failure with zero tests / gradleStarted=false and
  no false unhealthy-device claim;
- wrapped path/materialization exception classification coverage.

After tooling acceptance, rerun exact-candidate focused verification only on the
new committed tooling child plus unchanged ff3a111a test correction. Require
fresh authorized AVD health, compile, nonzero focused execution, zero unexplained
failures/errors, and detached exact-SHA diff gate.

Focused failure => STOP with first-failure evidence and no second semantic edit.
Focused pass => restart the full exact-final-SHA union from partition 1.

Publication remains gated by complete restarted union, Complete-Wave Check,
fresh refs, and protocol CAS rules.

## Canonical state

BUG-TOOLING-01 remains OPEN P2 / SAME_ROOT_WHOLE_PATH_BUDGET_RESIDUAL.
BUG-DOWNLOAD-01 remains OPEN P2 / FOCUSED_NOT_EXECUTED_TOOLING_BLOCKED.

No root is closed.
Overall verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
