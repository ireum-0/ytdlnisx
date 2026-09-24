# Observe generation/ownership — exact-SHA compile failure

Date: 2026-09-24 +09:00

Implementation branch:
checkpoint/pre-baseline-review

Remote implementation HEAD:
ee7eea001462b77e88a201ed2f26c2385048d421

Local committed candidate:
aaf89b15224b814470c2efab548f4c3e40003de7

Candidate parent:
ee7eea001462b77e88a201ed2f26c2385048d421

Prior review checkpoint:
fc0c32366e40dad32012969d48c1c6f0a79ee6ff

## Verdict

OBSERVE_GENERATION_CANDIDATE_COMPILE_FAILURE

No implementation push occurred.

Canonical defect delta: 0.

Canonical totals remain:
P0 = 1
P1 = 0
P2 = 23
Overall = NOT_CLEAN

CLEAN_REVIEW_BASIS remains:
ee7eea001462b77e88a201ed2f26c2385048d421

## Exact-SHA gate result

ADB authorization was recovered before this attempt.

Preflight reportedly confirmed:
- local HEAD exactly aaf89b15224b814470c2efab548f4c3e40003de7;
- no tracked post-commit changes;
- emulator-5554 online;
- Android boot complete;
- PackageManager ready.

The required first gate was requested:

BackupSettingsProductionWiringTest#observeSourceBackupOmitsDestinationGenerationAndLegacyPayloadGetsLocalGeneration

The gate did not reach test execution.

Gradle stopped during:
:app:compileDebugKotlin

Compiler diagnostic:
ObserveSourcesRepository.kt:100:26: Unresolved reference 'inputData'

Therefore:
- requested test executed count: 0 / not reached;
- this is not a semantic test failure;
- this is not an ADB/UTP infrastructure failure;
- the committed candidate itself is not compilable and cannot satisfy the
  exact-final-SHA gate.

Evidence reportedly preserved under:
app/build/observe-generation-evidence/results/backupsettings-exact-sha-20260924-auth-recovered/

No instrumentation or other tests ran after the compile failure.
No retry was performed.
No source change was made.
No push occurred.

## Required remediation

Do not amend, reset, rewrite, or recreate commit aaf89b15.

Use the exact local candidate as the starting point and inspect only enough
local source/context to explain the compile diagnostic.

Determine the intended object/value at
ObserveSourcesRepository.kt around line 100 and why inputData is unresolved.

Apply the smallest semantically correct source fix.
Do not guess by renaming or deleting logic merely to make compilation pass.

After editing:
1. review the focused diff;
2. run git diff --check;
3. compile the affected production target first;
4. if compile passes, run the smallest direct wiring/unit coverage that proves
   the corrected path;
5. perform a focused whole-diff review against aaf89b15;
6. commit the correction as a NEW child commit of aaf89b15;
7. no amend/rebase/squash/force/history rewrite.

The new commit must preserve the original Observe defect/review trailers as
applicable and identify this compile correction in the commit message.

After the new committed SHA exists and the tracked tree is clean:
- rerun the previously blocked BackupSettings exact-SHA gate first;
- require nonzero execution count and PASS;
- if PASS, continue the full serial exact-final-SHA verification;
- a valid semantic failure stops immediately;
- infrastructure-invalid zero-test attempts do not count as PASS;
- if all exact-SHA gates pass, final self-review and fresh remote check, then
  normal push of the exact tested new candidate.

Never claim CLEAN before independent review.

## Preservation

Continue preserving:
- protected primary workspace/head;
- baseline;
- all three protected stash objects;
- ignored/uncommitted local.properties;
- prior JVM crash/replay logs;
- Observe verification and AVD diagnostic evidence.

INDEPENDENT EXECUTION: NOT EXECUTED
