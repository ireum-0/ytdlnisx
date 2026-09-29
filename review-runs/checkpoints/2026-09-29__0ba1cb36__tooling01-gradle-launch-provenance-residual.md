# BUG-TOOLING-01 Gradle launch-environment provenance residual

checkpoint_kind: COMPLETED_IMPLEMENTATION_DIAGNOSTIC_RECONCILIATION
review_parent_sha: 5f8f47843233c8878c27a9b5534c44fb572a4d4b
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 0ba1cb3673cad0237024283f5705a07b9500beb6
reported_local_candidate_parent: 94c33f29b37b65d56addd41b9adcfb52f5f5b9b5
reported_local_candidate_tree: 799be87c73df536b9167aaab255a8b93c9f69c9d
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Classification

D. EFFECTIVE_HOME_EXACT_BUCKET_COMPLETE_DOWNLOAD_ATTEMPT_UNEXPLAINED

No new production finding ID is created.
No new tooling finding ID is created.

BUG-TOOLING-01 remains OPEN P2 and gains a SAME-ROOT
GRADLE-LAUNCH-ENVIRONMENT-PROVENANCE RESIDUAL.

## Established current-process observations

The implementation agent reports:
- JAVA_HOME unset;
- gradlew.bat therefore selects the first PATH java.exe;
- selected Java resolves to the Oracle javapath shim and reports Java 21.0.6;
- java.home resolves to the installed JDK 21 directory;
- user.home resolves to the current Windows user profile;
- GRADLE_USER_HOME unset;
- JAVA_OPTS unset;
- GRADLE_OPTS unset;
- JAVA_TOOL_OPTIONS unset.

Under the wrapper's ordinary lookup contract, the effective Gradle user home is
therefore the user's default .gradle directory.

The exact candidate distribution URL:
https://services.gradle.org/distributions/gradle-8.13-bin.zip

maps to the sole observed gradle-8.13-bin wrapper bucket:
5xuhj0ry160q40clulazy9h7d

The implementation agent reports that this bucket:
- predates the failed run;
- contains an extracted Gradle 8.13 directory;
- contains the .ok marker;
- contains launcher scripts and launcher JAR;
- contains no .zip or .part;
- contains a zero-byte .lck;
- is readable and traversable under the observed ACL.

Therefore the CURRENT process environment sees an apparently complete exact
Gradle 8.13 wrapper cache bucket.

## Preserved failed-run evidence

The prior exact-SHA compile run on candidate 0ba1cb36 durably recorded:
- repository-local gradlew.bat invocation;
- canonical Gradle 8.13 distribution URL;
- a wrapper download attempt;
- SocketException: Permission denied: connect;
- compile gate FAILED_COMPILE_GATE;
- verifier finalization PASS.

However that preserved run did NOT record:
- the exact java.exe selected by gradlew.bat;
- Java user.home for the gate child;
- effective GRADLE_USER_HOME / gradle.user.home;
- the wrapper URL-derived cache bucket checked immediately before launch;
- bounded cache-bucket state at gate start.

Therefore the historical download attempt cannot be reconciled with the current
complete bucket using preserved evidence alone.

## Root relation

This is not evidence that:
- Kotlin/Android source failed;
- the Gradle cache is currently missing/corrupt;
- the verifier deliberately changed the Gradle home;
- a new tooling semantic root exists.

It is an evidence/provenance residual of BUG-TOOLING-01 because exact-candidate
verification cannot currently explain a wrapper bootstrap failure when the
post-hoc host state disagrees with the observed download behavior.

The verifier already binds candidate SHA/tree, execution worktree, launcher,
gate results, and durable report finalization. The missing seam is the launch
environment/cache selection immediately before the canonical Gradle wrapper is
started.

## Narrow correction boundary

Preserve exact local candidate:
0ba1cb3673cad0237024283f5705a07b9500beb6

Authorize exactly one separately attributable tooling-only child.

Preferred allowed files:
- tools/remediation/Invoke-Verification.ps1
- tools/remediation/Test-ExecutionLifetimeProvenance.ps1
- tools/remediation/README.md only if the recorded evidence contract changes.

Do not modify Android production/tests, Gradle wrapper properties, build scripts,
dependencies, caches, Complete-Wave.ps1, or earlier commits.

Do not modify Remediation.Common.ps1 unless exact implementation work proves the
launch-environment evidence cannot be collected at the verifier caller boundary.
If that file is genuinely required, STOP before editing and report exact proof.

## Required semantics

Immediately before every non-demo Gradle gate launch, record a bounded
Gradle-launch-environment evidence object tied to:
- candidate SHA/tree;
- gate ID;
- execution worktree;
- canonical gradlew.bat path.

The record must establish, without running GradleWrapperMain separately:

1. exact java.exe gradlew.bat will select:
   - JAVA_HOME/bin/java.exe when JAVA_HOME is defined and valid;
   - otherwise first java.exe resolved from PATH;
2. read-only Java property probe using ONLY that selected java.exe, sufficient to
   record:
   - java.home;
   - user.home;
   - java.version;
3. relevant environment override state only:
   - whether GRADLE_USER_HOME is set and its resolved path;
   - relevant -Duser.home / -Dgradle.user.home overrides from JAVA_OPTS,
     GRADLE_OPTS, and JAVA_TOOL_OPTIONS;
   - do not serialize unrelated option contents or secrets;
4. deterministic effective Gradle user home according to the wrapper/Gradle
   lookup contract;
5. exact distribution URL read from the candidate's
   gradle/wrapper/gradle-wrapper.properties;
6. exact wrapper distribution ID/name and deterministic URL hash/bucket token
   compatible with Gradle wrapper behavior;
7. exact expected bucket path under the effective Gradle home;
8. bounded pre-launch bucket state:
   - bucket exists;
   - extracted expected Gradle directory exists;
   - .ok present;
   - launcher executable/JAR presence;
   - .zip/.part/.lck presence;
   - accessibility/readability result;
9. timestamp the observation immediately before the Gradle process start and
   bind it to the gate evidence.

Do not:
- download or repair Gradle;
- copy/populate caches;
- change GRADLE_USER_HOME;
- change JAVA_HOME/PATH/options;
- treat a complete cache as proof the gate must pass;
- expose unrelated environment variables or secret option values.

If Java selection/property probing or bucket observation itself cannot be
performed, fail closed BEFORE launching Gradle with an explicit tooling
launch-provenance/bootstrap classification and preserve the exact reason.

A normal gate may still attempt a network download after a recorded complete
bucket. If so, retain both facts in evidence; do not rewrite one to fit the
other.

## Acceptance

Extend the deterministic tooling acceptance matrix while preserving all 23
existing PASS cells.

Add at minimum:

A. JAVA_HOME-selected Java:
- exact Java path recorded;
- user.home/java.home/version recorded;
- effective Gradle home derived correctly.

B. PATH-selected Java:
- exact first PATH java recorded;
- same bounded property evidence.

C. GRADLE_USER_HOME override:
- effective home uses the explicit override;
- no unrelated environment serialized.

D. -Duser.home / -Dgradle.user.home relevant-option handling:
- relevant override is recognized according to actual wrapper/Gradle precedence;
- unrelated/secret option text is not serialized.

E. Exact distribution URL -> bucket mapping:
- known Gradle 8.13 URL maps deterministically to the expected bucket token;
- distinct URL maps to a distinct token.

F. Complete bucket observation:
- extracted distribution/.ok/launcher state recorded.

G. Incomplete/missing bucket observation:
- state recorded without mutation or download.

H. Probe failure:
- Java/property/cache provenance failure stops before Gradle launch and cannot
  emit false gate PASS.

Report exact acceptance IDs and total count.

## After tooling acceptance

Only after acceptance passes:
1. create exactly one tooling-only child on 0ba1cb36;
2. report exact SHA/tree/parent and changed paths;
3. git diff --check;
4. fresh authorized AVD health;
5. rerun :app:compileDebugAndroidTestKotlin through the exact candidate verifier;
6. preserve the new Gradle-launch-environment evidence object;
7. if compile fails again, STOP and classify using the recorded exact launch
   environment/cache state; no second tooling/source edit in the wave;
8. if compile passes, run complete LocalAddWorkerProductionWiringTest and require
   valid verifier PASS evidence;
9. then detached exact-SHA diff gate;
10. then restart the complete exact-final-SHA union from partition 1.

Only after the complete restarted union passes may Complete-Wave Check and
publication gates run.

## Canonical state

BUG-TOOLING-01 = OPEN P2 /
SAME_ROOT_GRADLE_LAUNCH_ENVIRONMENT_PROVENANCE_RESIDUAL.
BUG-DOWNLOAD-01 = OPEN P2 / EXACT_FINAL_SHA_UNION_NOT_VERIFIED.
BUG-LOCALADD-06 = OPEN P2 /
EXACT_FINAL_SHA_FOCUSED_NOT_VERIFIED_AFTER_TOOLING_CHILD.

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.

No root is closed.
Overall verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
