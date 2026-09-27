# Tooling wave independent completion review — BUG-TOOLING-01

checkpoint_kind: IMPLEMENTATION_COMPLETION_REVIEW
review_parent_sha: 1fdf2ba846c3b36afd96a00158c0e4e4e57762de
implementation_parent_sha: 31f55aca76efb1b956567fca5325b2575779a835
implementation_head: 21a04168286ae6562adbf219b8ea6a03984a2e25
reviewed_range: 31f55aca76efb1b956567fca5325b2575779a835..21a04168286ae6562adbf219b8ea6a03984a2e25
governing_protocol_blob: ca3164f27efb21ede4ce360eea01987f49ebf41b
governing_tooling_plan_blob: 37ea108f8bf84045b89afb1d94f6c1212fd46fb9
governing_tooling_prompt_blob: f39ba42dc983603645294f2365302826c1f296a6

verdict: NOT_CLEAN
finding: BUG-TOOLING-01
severity: P2
status: OPEN / CURRENT-CHANGE TOOLING BLOCKER
introduced_by: 21a04168286ae6562adbf219b8ea6a03984a2e25
new_finding_ids: 1
count_change: +1
canonical_p0: 0
canonical_p1: 0
canonical_p2: 18
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
independent_execution: NOT EXECUTED

## Scope and history integrity

The implementation branch is exactly one forward commit from the recorded tooling base:

31f55aca76efb1b956567fca5325b2575779a835
-> 21a04168286ae6562adbf219b8ea6a03984a2e25

The exact implementation diff adds only the five authorized files under tools/remediation/**:

- Complete-Wave.ps1
- Invoke-Verification.ps1
- Preflight-Wave.ps1
- README.md
- Remediation.Common.ps1

No app/src/**, Gradle dependency/build-semantic, schema, migration, or production-configuration file is changed by this commit.

The reported tooling acceptance/demo results and PUSHED_AND_VERIFIED result are implementation-agent evidence only. The demos used fake ADB/Gradle and no real Android instrumentation suite was independently executed by this reviewer.

## Preserved correct tooling behavior

The implementation materially satisfies several approved requirements and these behaviors must be preserved:

- preflight is read/check only apart from ignored evidence output;
- remote implementation/ref and review ancestry checks are explicit and fail closed;
- protected primary HEAD and named stash objects are checked without destructive cleanup;
- local.properties content is not read;
- connected gates require bounded ADB/shell/boot/PackageManager preflight;
- connected partitions are serialized and zero-test infrastructure failure can open a circuit breaker;
- recovery is not automatic;
- device/guest/host diagnostic capture is observational;
- UTC and Asia/Seoul derived timestamps are recorded without rewriting raw logs;
- Complete-Wave uses an explicit Push switch and only a normal push command;
- no force/rebase/amend/reset/clean/history-rewrite path was found;
- ToolingDemoMode labels verification evidence as tooling_demo, and Complete-Wave rejects tooling_demo as exact_source_verification.

These closures are not the blocker.

## BUG-TOOLING-01 — exact-source verification is not actually bound to the exact execution tree/toolchain

### Subcase A — untracked build-relevant files are invisible to every exact-source cleanliness gate

Remediation.Common.ps1 implements Get-RemediationTrackedTreeState() with:

- git diff --quiet <candidate> --
- git status --porcelain=v1 --untracked-files=no

The helper therefore deliberately ignores every untracked file.

Invoke-Verification.ps1 uses this helper before the gate and again after each gate, and refuses only when tracked files differ. Complete-Wave.ps1 uses the same helper before accepting verification evidence and before Push.

This creates a concrete false-attribution sequence:

1. committed candidate SHA X/tree T is checked out;
2. an untracked but build-relevant file exists under a Gradle source/config input, for example app/src/main/**, app/src/test/**, app/src/androidTest/**, or another non-ignored build input;
3. Get-RemediationTrackedTreeState() returns clean because that file is omitted by --untracked-files=no and git diff does not see it;
4. Gradle compiles/executes the untracked file because Git tracking is irrelevant to Gradle source discovery;
5. Invoke-Verification writes candidateSha=X and candidateTree=T and can report PASS;
6. Complete-Wave sees the same tracked-only clean state and can accept/push the evidence as exact_source_verification for X/T.

The executed program/test tree can therefore differ materially from the recorded committed tree while all exact-SHA/tree binding checks pass.

This violates the protocol exact-final-SHA requirement that no uncommitted behavior-relevant source/test/config changes be present in the execution tree.

### Subcase B — normal exact-source mode accepts an arbitrary Gradle executable

Invoke-Verification.ps1 defaults GradlePath to the repository gradlew.bat, but when the caller supplies GradlePath it only verifies that the path exists. It does not require normal-mode GradlePath to resolve to the repository-local wrapper or another pre-authorized exact launcher.

ToolingDemoMode correctly marks fake Gradle/ADB runs as tooling_demo, but that protection is caller-selected rather than enforced by the launcher path.

A caller can therefore:

1. omit ToolingDemoMode;
2. pass an arbitrary/fake Gradle executable through GradlePath;
3. have that executable return zero and create matching TEST-*.xml under the normal ignored app/build result roots;
4. satisfy the current test-result parser;
5. receive evidenceKind=exact_source_verification and PASS;
6. allow Complete-Wave to accept that evidence because it validates the JSON binding and gate status, not the actual Gradle launcher identity.

This contradicts the README/demo contract that fake Gradle is demo-only and the protocol rule that final exact-SHA verification use the established repository-local Gradle wrapper/launcher path rather than silently switching launcher mechanisms.

### Why one root

Both subcases break the same final-evidence invariant:

An artifact labeled exact_source_verification must prove that the requested gate executed the exact committed candidate tree using the authorized real verification launcher.

The first subcase breaks exact-tree identity; the second breaks exact-launcher identity. They should be corrected and re-reviewed as one tooling root rather than counted separately.

## Required correction

Keep the tooling narrow and do not modify Android production source.

1. Exact execution-tree cleanliness:
   - distinguish ignored evidence/build output from non-ignored untracked repository inputs;
   - before exact-source verification and completion acceptance, fail closed if any non-ignored untracked file can participate in the execution/build tree;
   - the smallest acceptable implementation may reject all non-ignored untracked files in the isolated implementation worktree rather than attempting an incomplete source-path allowlist;
   - preserve protected dirty/untracked primary-workspace semantics; do not clean/reset/delete anything;
   - record the observed untracked paths in evidence only when the exact implementation worktree check fails or in a bounded summary, without exposing local.properties content.

2. Exact launcher identity:
   - in normal exact_source_verification mode, require Gradle execution to use the established repository-local wrapper path (canonical-resolved repo/gradlew.bat) unless a future reviewer-authored prompt explicitly extends the launcher contract;
   - arbitrary/fake Gradle executables remain permitted only under ToolingDemoMode and must remain evidenceKind=tooling_demo;
   - a non-demo alternate GradlePath must fail closed before any gate starts;
   - do not add fallback launcher cycling.

3. Regression/acceptance tests:
   - create an untracked non-ignored synthetic source/test/config input in a safe fixture/repository harness and prove exact-source verification refuses to run/Complete-Wave refuses acceptance without deleting it;
   - prove ignored build/remediation-agent evidence and ignored local.properties do not create a false dirty failure;
   - prove a fake/alternate Gradle path without ToolingDemoMode is rejected;
   - prove the same fake Gradle path with ToolingDemoMode still runs the demo and remains un-acceptable to Complete-Wave as exact-source evidence;
   - prove canonical repo gradlew.bat normal mode still works;
   - preserve the existing 12 tooling acceptance scenarios and rerun the affected exact-source/completion checks.

4. History/scope:
   - correction must be a forward-only review-fix commit after 21a04168286ae6562adbf219b8ea6a03984a2e25;
   - modify only tools/remediation/** unless an explicit reviewer stop/replan occurs;
   - no amend/rebase/squash/reset/clean/force-push/history rewrite;
   - preserve protected primary HEAD, named stashes, local.properties, and prior evidence;
   - do not modify review/remediation, ledger/remediation, plan/remediation, or the private authoritative docs from the implementation agent.

## Closure gate

After the correction is pushed, independently re-review the full tooling range:

31f55aca76efb1b956567fca5325b2575779a835..NEW_HEAD

Do not review only the last fix commit.

Tooling is not accepted for correctness-gate use until BUG-TOOLING-01 is independently closed.

INDEPENDENT EXECUTION: NOT EXECUTED
