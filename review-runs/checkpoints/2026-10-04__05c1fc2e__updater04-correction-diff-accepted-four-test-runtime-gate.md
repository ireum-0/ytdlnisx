# BUG-UPDATER-04 correction diff independently accepted — four-test focused runtime gate authorized

review_parent_sha: bea55be7a0892e3d3b957c20b09a372ca499d80c
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_parent_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
local_parent_candidate_tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a
local_parent_candidate_parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
CORRECTION_DIFF_ACCEPTED
FOUR_TEST_RUNTIME_GATE_AUTHORIZED
FULL_CLASS_NOT_YET_AUTHORIZED
COMMIT_NOT_AUTHORIZED
PUSH_NOT_AUTHORIZED
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Authoritative correction evidence:
private:ytdlnisx-review
path: ytdlnisx/evidence/2026-10-04__05c1fc2e__BUG-UPDATER-04_anonymous-mutation-identity-correction-diff.md
evidence_commit: 02318bc2a6298e5228c472af4d33df4320b86012
evidence_blob: 20dd3cec3386dd4e20c2fe0541b8a02ef35db45c

Independent source/diff confirmation:

1. Production correction is narrow and matches the canonical contract.
- requestRequiresMutation(request) is evaluated once before anonymous identity selection;
- processId == null + mutating request selects mutation:<UUID>;
- processId == null + ordinary request selects consumer:<UUID>;
- non-null caller-supplied processId is preserved byte-for-byte by the Elvis expression;
- the same ownedProcessId continues through runtimeAdmissions, executeNativeWithQuiescence, YtdlpNativeProcessBarrier.prepare, environment publication, process registry, finalization and cancellation/revocation.

2. Existing mutation/recovery contracts are unchanged.
- executeUnderMutation remains byte-for-byte semantically unchanged and still generates its own mutation:<UUID>;
- YtdlpNativeProcessBarrier selector/store code is unchanged;
- recoverRuntimeMutationNativeDebt remains mutation_-scoped and exact-token based;
- runtimeMutationIsQuiescent remains read-only observation across all native generations;
- reader fencing/publication validation remains unchanged;
- no second durable store and no broad recovery sweep were introduced.

3. Explicit owner identities are intentionally preserved.
- production explicit identities belong to owner domains with their own retained/recoverable identity relationships (Download, Terminal, History and explicit metadata ownership paths);
- the canonical defect was the anonymous generic mutator, whose consumer:<UUID> had no later automatic owner/discovery path;
- therefore preserving caller-supplied identities does not leave the proven anonymous-owner gap uncorrected.
- the new explicit-ID regression is accepted as an ownership-preservation test; it does not redefine mutation recovery selection.

4. Test scope is accepted.
- four new instrumentation methods are additive:
  anonymousLibraryMutationUsesMutationNativeIdentity
  anonymousOrdinaryLibraryRequestRemainsConsumerScoped
  mutationClassifiedRequestPreservesExplicitProcessIdentity
  retainedAnonymousMutationRecoversThroughExistingSelectorAndAllowsLaterProgress
- shared helper extension defaults to the old producer path and only selects the new anonymous producer when explicitly requested;
- all twelve pre-existing test methods and bodies are exact-source/hash identical to parent candidate 05c1fc2ed;
- no existing regression was deleted or weakened.

5. Static gates already passed on this exact dirty overlay.
- production Kotlin compile PASS;
- complete debug AndroidTest Kotlin PASS;
- complete debug AndroidTest Java PASS;
- git diff --check PASS;
- runtime behavior remains NOT_VERIFIED because no tests have executed after the correction.

Authorized focused runtime gate:
Run exactly the four new methods above on the existing prepared two-file unstaged correction overlay.

Use the same exact correction worktree:
D:/AndroidStudioProjects/ytdlnisx-f11/build/sol-remediation-20260930

Required device target:
- same previously authorized real SM-A546E;
- arm64-v8a;
- API 36;
- ADB state device/install-ready;
- no emulator or alternate phone.

Execution requirements:
- one focused invocation containing exactly those four test methods is preferred;
- require exactly 4 intended tests executed;
- PASS requires 4 PASS / 0 FAIL / 0 skipped;
- any semantic failure: stop immediately after the invocation, preserve logs/evidence, no retry;
- 0 tests or device/install infrastructure failure: no semantic result, stop without retry;
- do not run the old twelve tests or full sixteen-test class in this pass;
- do not edit source/test/config after the run;
- do not stage, commit, or push.

Required evidence:
- exact correction file hashes must still equal:
  YoutubeDLCompat.kt physical SHA-256 098a834d565a87c1203380098abd117768ff38b97f76bd4827dcca95e7bc436b
  YtdlpRuntimeAuthorityProductionWiringTest.kt physical SHA-256 a249a3b7d2327bf22d714948756b497db8def043d13badc6a3d90f7125da6810
- cached/staged diff remains empty;
- exact four method names and executed counts;
- full failure diagnostics if any;
- preservation of the historical laterUpdaterProgressAfterExactMutationRecovery false-recovery evidence as NOT_VERIFIED;
- no reinterpretation of that historical event.

If the four-test gate passes, stop for independent reviewer decision before any full-class run or commit.

Known device-package risk:
debug instrumentation still shares applicationId com.ireum.ytdl with the user's personal install. This focused device run remains within the user's explicit decision to continue the current BUG-UPDATER-04 wave despite that known collision risk. Do not perform unrelated device operations.

Separate debug applicationId isolation remains mandatory and must not be mixed into this correction.

INDEPENDENT EXECUTION: NOT EXECUTED
