# BUG-SCHEDULER-01 — ETW StartTraceW denied; restricted-token discriminator required

Date: 2026-10-08
checkpoint_kind: IMPLEMENTATION_DIAGNOSTIC_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_parent_sha: e6a9b288292894422f9611a7954bdfd3706dad37
reviewed_remote_implementation_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
protocol_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
finding_id: BUG-SCHEDULER-01
finding_disposition: EXISTING_OPEN_P2_SAME_ROOT
new_finding_ids: NONE
root_count_change: NONE
production_source_edit: NO
independent_execution: NOT_EXECUTED

## Implementation completion / exact stop

The previous persisted instrumentation continuation executed through the REQUIRED feasibility boundary.
Sealed report:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-java-open-observability-20261008-9AyoFo/BUG_SCHEDULER_01_JAVA_OPEN_OBSERVABILITY_PREFLIGHT_STOP_REPORT.md

On 2026-10-08 the controller PID 9736 invoked StartTraceW for a fresh owned ETW file session:
- return value: Win32 5 ERROR_ACCESS_DENIED;
- trace handle 0, session NOT_STARTED, no ETL file;
- EnableTraceEx2 and sacrificial native open NOT_REACHED;
- Java child/Gradle/build/test invocations: 0;
- no environment, source, cache, ACL or protected state mutation.

This status is the OBSERVER SETUP error and MUST NOT be equated with the historical Java
RandomAccessFile lock-file-open error number, which was not captured.

## Independent classification

Microsoft StartTraceW documentation limits tracing-session control to appropriately privileged
accounts/Performance Log Users/authorized service accounts and explicitly lists ERROR_ACCESS_DENIED.
Thus ETW failure is consistent with an insufficiently privileged observer; exact ETW ACL origin is
NOT_VERIFIED, and tracing configuration/privileges must not be changed on this evidence.

The current controller token:
- user SID S-1-5-21-1250005489-1569851321-1199252671-1005;
- seven restricting SIDs, medium integrity, non-elevated;
- is NOT the previous unrestricted inspector token ending 1001.

Earlier exact Gradle lock ACL review established owner dh2 (SID ending 1001) FullControl while the
CodexSandboxUsers group was reported ReadAndExecute/Synchronize only. Restricted Windows tokens
must pass BOTH normal enabled-SID and restricting-SID access checks to obtain access. Therefore
restricted execution identity is now a specific testable explanation for why a current unrestricted
inspector could open the lock but historical Java children could not.

However neither failed Java child's actual token was recorded and the current controller's
seven restricting SIDs have not yet been evaluated against the exact lock DACL for required
read/write access. Historical Java cause remains NOT_VERIFIED. No permission/ACL/cache repair is
authorized on this observation alone.

Remote implementation SHA unchanged. Protected local candidate:
- local HEAD c34b8aa57e01803c9960e4ad873d1ed5b68e019c;
- dirty tree df2d52c20c6928bf74b7d1c4f230483189b5aa5b;
- three tracked unstaged files, empty index;
- prior compiled APKs/evidence and five protected parallel states preserved;
- 12 reported regressions remain unexecuted.

Classification:
EXECUTION_HARNESS_BLOCKED=YES
ETW_OBSERVER_BLOCKED=ERROR_ACCESS_DENIED_5
GRADLE_HISTORICAL_NATIVE_CODE=NOT_VERIFIED
GRADLE_HISTORICAL_JAVA_TOKEN=NOT_VERIFIED
POSSIBLE_CAUSE=RESTRICTED_PROCESS_TOKEN_RIGHTS_NOT_YET_TESTED
MUTATING_REMEDY_AUTHORIZED=NO
CHAINED_CLOSURE_POLICY=BLOCKED
CHAINED_CLOSURE_BREAK_REASON=ETW_OBSERVER_ACCESS_DENIED_AND_GRADLE_JAVA_EFFECTIVE_TOKEN_UNVERIFIED

## Next bounded independent discriminator

Do NOT attempt ETW again, elevate, install tools, manipulate the host security policy, repeat Gradle,
or modify source/cache/lock/ACL. Authorize one diagnostic-only continuation that:

1. Reuses existing exact lock and parent DACL metadata, not a filesystem scan; performs in-memory
   Windows restricted-token AccessCheck under the CURRENT controller token (including all restricting
   SIDs), for required RandomAccessFile("rw") read/write and parent traversal; no file write/open
   requiring write rights.
2. If the current controller token differs from the intended Java child identity, permits at most ONE
   innocuous, finite JBR Java process (e.g. -version, NOT gradlew/wrapper) created through the same
   non-elevated parent context only to read its effective user/group/restricting SID token using
   ordinary same-user token-query rights; compare it to the controller token. Do not inspect arbitrary
   unrelated processes or inject/code-patch; abort if querying needs elevation/privilege change.
3. Use in-memory AccessCheck with that actually-observed Java child token where available, against
   EXACT existing lock/parents; no target file mutation.
4. Report the observed current-token access status, token mismatch/proof, all remaining historical
   uncertainty, and one evidenced next execution-context remedy proposal, if supported, as a proposal
   ONLY. No actual environment repair or Gradle build is authorized.
5. Stop for reviewer decision. A denied in-memory check may support a current-token causal mechanism
   but does NOT retroactively prove the tokens of the historical failed Java processes.

This is a targeted discriminator, not another instrumentation or Gradle retry loop. Keep historical
evidence immutable and append new diagnostic records separately.

INDEPENDENT EXECUTION: NOT EXECUTED
