# Observe worker semantic-failure correction and push authorization

Date: 2026-09-25 +09:00

Implementation remote before push:
ee7eea001462b77e88a201ed2f26c2385048d421

Exact tested local candidate:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

Parent:
2d86ca9869721f3b1d482d801463c0024ffaee21

Prior review checkpoint:
7eecb9d013a45e4da51669114f71d044d016cf7b

## Correction

The prior checkpoint recorded the failing worker assertion as if runCount had
been observed as 2 instead of 1.

That interpretation was incorrect.

The preserved failure at
ObserveSourceWorkerProductionWiringTest.kt:188
was a queue-size assertion.

The worker behavior was:
- persisted runCount began at 0;
- the production worker advanced it once to 1;
- ends-after-count stopped the source;
- no successor was published;
- the queue correctly contained two URLs:
  1. the already waiting template URL;
  2. the newly observed threshold URL.

No production runCount increment was removed or changed.

The correction was test-only: assert the exact two queued URLs and two
cancellation-hook calls while retaining runCount == 1.

The correction does not rewrite the prior checkpoint; this checkpoint
supersedes its failure attribution.

## Verification reported on exact committed SHA

Candidate:
252adf8c0cb3762b4dd1335a7c24fd912be683e5

Parent:
2d86ca9869721f3b1d482d801463c0024ffaee21

Precommit:
- corrected focused test: PASS 1/1;
- full ObserveSourceWorkerProductionWiringTest: PASS 14/14.

Exact-final-SHA reported PASS:
- BackupSettings: 1/1;
- migration: 1/1;
- generation/ownership: 6/6;
- handoff admission: 7/7;
- source snapshot: 3/3;
- worker class: 14/14;
- git diff --check: PASS;
- :app:compileDebugKotlin: PASS;
- :app:compileDebugAndroidTestKotlin: PASS.

No behavior-relevant tracked changes occurred after exact-SHA verification.

Evidence paths were preserved in the local worktree.

## Trailer mismatch classification

Commit 252adf8c reportedly contains:
Reviewed-Checkpoint: 8608e4...

The continuation prompt had requested:
Reviewed-Checkpoint: 7eecb9d013a45e4da51669114f71d044d016cf7b

Targeted protocol review found no rule making a Reviewed-Checkpoint trailer
value an independent push or execution-closure gate.

REVIEW_PROTOCOL.md Section 16.3 instead permits closure-grade execution from a
clean exact committed SHA before push when:
- no behavior-relevant source/test/config change follows; and
- the normal push makes remote implementation HEAD exactly that same SHA.

Therefore:
- do NOT amend 252adf8c;
- do NOT rewrite history;
- do NOT create a metadata-only child commit merely to replace the trailer;
- do NOT rerun expensive exact-SHA verification solely because of the stale
  trailer.

The stale trailer is recorded here as governance metadata debt/correction and
does not invalidate the exact tested tree or execution evidence.

## Authorized next action

If local HEAD is still exactly
252adf8c0cb3762b4dd1335a7c24fd912be683e5,
the tracked tree has no post-commit behavior-relevant changes, protected state
is intact, and a fresh remote check still shows implementation HEAD
ee7eea001462b77e88a201ed2f26c2385048d421:

normal-push exact SHA
252adf8c0cb3762b4dd1335a7c24fd912be683e5
to
origin/checkpoint/pre-baseline-review.

No amend.
No rebase.
No squash.
No force-push.
No history rewrite.

After push, verify:
- remote implementation HEAD == 252adf8c0cb3762b4dd1335a7c24fd912be683e5;
- local/remote ahead=0 behind=0.

Then stop implementation mutation and hand off for independent exact-source
review of the full Observe generation/ownership finding scope.

Canonical defect delta remains 0 pending independent review.
Overall remains NOT_CLEAN.
CLEAN_REVIEW_BASIS remains ee7eea001462b77e88a201ed2f26c2385048d421.

INDEPENDENT EXECUTION: NOT EXECUTED
