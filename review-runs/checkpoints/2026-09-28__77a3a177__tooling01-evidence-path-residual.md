# BUG-DOWNLOAD-01 Outcome B hypothesis blocked by BUG-TOOLING-01 evidence-path residual

checkpoint_kind: REVIEW_FIX_STOP_RECONCILIATION
review_parent_sha: 5169c5abd46eb9312aecb0f342d93182baa47817
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 77a3a177eb775d0a17fe5286aa5fd33c7cd51220
reported_local_candidate_tree: 182cc3fc
reported_local_candidate_parent: faf9ec273cea90ce7479bc6f62e6fa79b6dc6fad
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## BUG-DOWNLOAD-01 classification status

The implementation agent classified the five original
DownloadWorkerCleanupProductionWiringTest failures as Outcome B:
TEST-ONLY SEMANTIC ASSERTION RESIDUAL.

That classification is NOT_VERIFIED.

Reported exact-source reasoning:
- the two PostProcessing/Active -> Queued observations can be compatible with
  the canonical invariant only when the exact E1 DownloadProducerRecovery
  record remains the durable authority and continues fencing incompatible E2
  admission until exact reconciliation retires E1;
- the three WorkInfo timeouts were lifecycle/representation assertions against
  a long-lived queue worker rather than direct proof that durable terminal or
  History outcomes failed;
- the proposed correction changes only the production-wiring test and asserts
  exact producer identity/phase and durable outcomes rather than transient
  row-status/WorkInfo representation.

Reported local test-only child:
77a3a177eb775d0a17fe5286aa5fd33c7cd51220

Reported parent:
faf9ec273cea90ce7479bc6f62e6fa79b6dc6fad

Reported changed path only:
app/src/androidTest/java/com/ireum/ytdl/database/DownloadWorkerCleanupProductionWiringTest.kt

No finding disposition changes. BUG-DOWNLOAD-01 remains OPEN P2 because the
corrected focused test did not execute.

## New verification blocker

The focused exact-SHA verifier stopped before ADB probes or Gradle execution.

Reported evidence:
- status: BLOCKED_DEVICE_HEALTH
- executed tests: 0
- gradleStarted: false
- the failure occurred while creating a nested device-health log path for the
  first ADB command;
- no device probes were recorded;
- independent read-only boot and PackageManager probes had passed immediately
  before the verifier invocation.

Therefore this is NOT an emulator-health result and NOT a semantic test result.

## Tooling root classification

BUG-TOOLING-01 remains OPEN P2 and gains a SAME-ROOT EVIDENCE-PATH RESIDUAL.
No new finding ID is created and tooling P2 remains 1.

Fresh authoritative tooling source shows:
- Invoke-Verification.ps1 constructs a per-connected-gate device-health
  directory using the sanitized full gateId;
- Get-RemediationDeviceHealth invokes ADB through Invoke-RemediationProcess;
- Invoke-RemediationProcess appends per-command stdout/stderr file names below
  that directory;
- the evidence root itself already contains repository, candidate SHA and run-id
  segments.

For long connected class/gate names, this composition can exceed the Windows
path boundary before adb.exe is launched. The reported failing nested
device-health/.../adb-devices-...stdout.log path is consistent with this source.

The current BLOCKED_DEVICE_HEALTH status is too coarse for this case because the
device probe never started; the verifier should preserve an infrastructure
evidence-path/bootstrap classification rather than imply an observed unhealthy
device.

## Narrow correction contract

Preserve the exact local candidate and all prior commits.

The tooling correction must:
1. keep the full semantic gateId/class in JSON evidence;
2. decouple filesystem artifact directory/file segments from unbounded gateId
   length by using a deterministic bounded segment, for example a readable
   prefix plus stable hash;
3. ensure device-health, watchdog, stall-diagnostic and main gate log paths stay
   bounded under the normal run-owned evidence root;
4. preserve one-to-one mapping from bounded artifact segment back to full
   gateId/class in evidence;
5. preserve unique per-run/per-command log identity;
6. never silently truncate in a way that can collide;
7. classify path/materialization failure before an ADB probe as tooling/
   infrastructure bootstrap failure, not observed BLOCKED_DEVICE_HEALTH;
8. keep all prior exact-source, local.properties non-exposure, canonical
   launcher, evidence binding, CAS and protected-state contracts unchanged.

A correction limited to Invoke-Verification.ps1 plus tooling acceptance/docs is
preferred if sufficient. If Remediation.Common.ps1 must change to guarantee the
bound globally, STOP before editing and report why so authority can be widened
explicitly.

## Required acceptance

Add deterministic tooling acceptance with an intentionally long connected test
class/gate identifier proving:
- run/device-health/log artifact paths remain within the supported Windows
  filesystem contract;
- the ADB probe is actually invoked rather than failing during log-path setup;
- the full original gateId/class remains present in evidence;
- bounded artifact-name mapping is deterministic and collision-resistant;
- two distinct long gate IDs cannot alias to the same artifact segment;
- all existing 16 tooling acceptance cells remain passing.

After tooling acceptance:
- rerun the focused exact-SHA
  DownloadWorkerCleanupProductionWiringTest on the unchanged test-only child plus
  the new tooling correction child;
- require actual nonzero execution and zero unexplained failures;
- run required compile/diff gates;
- only then treat Outcome B as verified enough to restart the full union.

## Canonical state

Remote implementation remains:
7d6a7b7c445d9e45297032fa0521a1fc1d732eb9

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.

BUG-DOWNLOAD-01 remains OPEN P2 / OUTCOME_B_HYPOTHESIS_NOT_VERIFIED.
BUG-TOOLING-01 remains OPEN P2 / SAME-ROOT RESIDUAL.
BUG-TOOLING-02 remains FIXED-CLOSED.

No publication is authorized until the focused corrected partition and the full
restarted exact-final-SHA union pass.

INDEPENDENT EXECUTION: NOT EXECUTED
