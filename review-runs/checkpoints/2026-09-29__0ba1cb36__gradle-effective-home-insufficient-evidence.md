# Gradle wrapper infrastructure diagnosis — historical effective home not recoverable

checkpoint_kind: COMPLETED_IMPLEMENTATION_DIAGNOSTIC_RECONCILIATION
review_parent_sha: 487ef2ce86a2568afa05db7f4454411bb3e41447
remote_implementation_sha: 7d6a7b7c445d9e45297032fa0521a1fc1d732eb9
reported_local_candidate_sha: 0ba1cb3673cad0237024283f5705a07b9500beb6
reported_local_candidate_parent: 94c33f29b37b65d56addd41b9adcfb52f5f5b9b5
reported_local_candidate_tree: 799be87c73df536b9167aaab255a8b93c9f69c9d
protocol_blob_sha: d9d112148965c0e4151e653015842dc783f52916
overall_verdict: NOT_CLEAN

## Classification

D. OTHER_OR_INSUFFICIENT_EVIDENCE

No source edit, tooling edit, cache mutation, Gradle invocation, retry, commit, or
publication was performed during this diagnostic.

## Established observations

Current shell:
- GRADLE_USER_HOME unset;
- HOME unset;
- USERPROFILE = C:\Users\dh2;
- therefore the ordinary default Gradle user home for this shell is
  C:\Users\dh2\.gradle.

Candidate wrapper contract:
- distributionUrl points to gradle-8.13-bin.zip;
- distributionBase=GRADLE_USER_HOME;
- distributionPath=wrapper/dists;
- zipStoreBase=GRADLE_USER_HOME;
- zipStorePath=wrapper/dists.

Current default cache metadata:
- an apparently complete Gradle 8.13 extracted distribution exists;
- .ok marker exists;
- launcher files and launcher JAR exist;
- timestamps predate the current run;
- no .zip or .part file is present;
- a zero-byte .lck is present.

Preserved failed exact-SHA verifier evidence confirms:
- :app:compileDebugAndroidTestKotlin was the first exact-candidate gate;
- the repository wrapper attempted the canonical Gradle 8.13 distribution URL;
- the wrapper download/install path raised
  SocketException: Permission denied: connect;
- verifier finalization completed and classified the gate FAILED_COMPILE_GATE /
  FAIL_OR_INCOMPLETE;
- Kotlin compilation did not begin.

No prior successful compile evidence for exact candidate 0ba1cb36 was found.

Candidate remediation tooling contains no explicit GRADLE_USER_HOME or
--gradle-user-home override.

## Remaining ambiguity

The preserved failed run did not record the gate process's effective:
- GRADLE_USER_HOME;
- Java user.home;
- exact wrapper cache directory/hash bucket examined by GradleWrapperMain.

Therefore the presence of a complete Gradle 8.13 installation in the current
default cache does not prove that the failed wrapper process used that same home
or that same distribution bucket.

The evidence cannot yet distinguish:
- process environment drift to a different Gradle home;
- Java user.home differing from USERPROFILE;
- JAVA_OPTS/GRADLE_OPTS overriding user.home or Gradle home semantics;
- a URL/hash bucket mismatch within the same wrapper/dists root;
- another wrapper cache usability condition.

No new tooling finding ID is created.

The observed network-denied download remains an external build infrastructure
blocker unless later evidence proves the verifier changed the relevant process
environment or cache selection incorrectly.

## Fresh GitHub wrapper-source corroboration

The repository gradlew.bat:
- uses JAVA_HOME/bin/java.exe when JAVA_HOME is defined;
- otherwise resolves java.exe from PATH;
- passes JAVA_OPTS and GRADLE_OPTS to the wrapper JVM;
- does not itself set GRADLE_USER_HOME;
- therefore Java selection and JVM options are material to the effective
  wrapper home/cache behavior.

## Narrow next diagnostic boundary

Remain read-only.

On exact candidate 0ba1cb36, without invoking gradlew/Gradle:

1. identify the exact java.exe that gradlew.bat would select in the current
   unchanged agent shell:
   - if JAVA_HOME is defined, resolve JAVA_HOME/bin/java.exe;
   - otherwise resolve java.exe from PATH;
2. report JAVA_HOME, JAVA_OPTS, and GRADLE_OPTS only to the extent needed to
   identify Java selection or -Duser.home/-Dgradle.user.home style overrides;
3. invoke only that exact java executable with a read-only property-reporting
   command such as -XshowSettings:properties -version, capturing user.home and
   java.home; do not run GradleWrapperMain;
4. derive the effective Gradle user home under the wrapper contract;
5. inspect only the gradle-8.13 wrapper/dists bucket(s) under that exact home;
6. identify which bucket corresponds to the candidate's exact distribution URL,
   by wrapper-compatible deterministic derivation or bounded structural
   matching;
7. report whether that exact bucket is complete, incomplete, missing, or
   inaccessible;
8. inspect JAVA_TOOL_OPTIONS only if the exact Java process reports or inherits
   it as relevant to user.home; do not dump unrelated environment data.

Return exactly one refined classification:

A. EFFECTIVE_HOME_CACHE_MISSING_NETWORK_BLOCKED
B. EFFECTIVE_HOME_DIFFERS_FROM_CURRENT_ASSUMPTION
C. EFFECTIVE_HOME_EXACT_BUCKET_INCOMPLETE_OR_UNUSABLE
D. EFFECTIVE_HOME_EXACT_BUCKET_COMPLETE_DOWNLOAD_ATTEMPT_UNEXPLAINED
E. INSUFFICIENT_EVIDENCE

No retry or recovery is authorized by this checkpoint.

## Canonical state

BUG-TOOLING-01 = OPEN P2 /
LOCAL_CORRECTION_ACCEPTANCE_PASS_EXACT_VERIFICATION_INFRA_BLOCKED.
BUG-DOWNLOAD-01 = OPEN P2 / EXACT_FINAL_SHA_UNION_NOT_VERIFIED.
BUG-LOCALADD-06 = OPEN P2 /
EXACT_FINAL_SHA_FOCUSED_NOT_VERIFIED_AFTER_TOOLING_CHILD.

Production P0=0 / P1=0 / P2=13.
Tooling P0=0 / P1=0 / P2=1.

No root is closed.
Overall verdict remains NOT_CLEAN.

INDEPENDENT EXECUTION: NOT EXECUTED
