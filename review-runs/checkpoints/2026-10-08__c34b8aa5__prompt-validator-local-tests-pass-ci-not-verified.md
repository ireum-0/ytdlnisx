# Prompt integrity regression test evidence; GitHub CI remains NOT_VERIFIED

Date: 2026-10-08 (Asia/Seoul)
record_kind: REVIEWER_PROMPT_INTEGRITY_TEST_CHECKPOINT
record_status: FINAL
manual_review_run: NO
review_parent_sha: 0d3c2e182b908b7a8ea047907752f0d47ccbd86b
implementation_pinned_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
protocol_blob_current: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
source_semantics_review: NOT_REACHED
production_root_count_delta: 0

## Test basis and local result

Read current private branch code through GitHub at private tip
ce4c4aad507d0dcac4731fe6b2a7e5af9a2ce6cd:

- ytdlnisx/review-tools/validate_persisted_prompt.py
  blob 7c881a1c6f25f85e382a10bb2c3f36dd5fe07fc4
- ytdlnisx/review-tools/test_validate_persisted_prompt.py
  blob cb38f5dd9c0b25673780634d38c3636523ff985d

Reviewer independently transcribed the just-fetched Python source/test text to an isolated
Python execution environment and executed the unittest suite. Result:
11 tests run; 11 PASS; 0 failure; 0 error, within 0.001s.
This validates the reproduced Python logic, not byte-for-byte GitHub Actions execution.
There was no project/Gradle/Windows token operation during this test.

Tests include accepted full 64-character digest, observed historic 45-character digest
rejection, wrong 64-hex digest, duplicated/missing declarations, handoff digest and review
mismatches, invalid launch-readiness, and a two-line full digest declaration.

## GitHub Actions coverage boundary

Configured workflow: .github/workflows/ytdlnisx-prompt-integrity.yml
blob 2e3a22e0e654cf696ff41c2b7f3bc36f16318079.

The available GitHub combined-status query returned no statuses. The available workflow-run
query is limited to pull-request-triggered runs and returned no corresponding data.
This does NOT establish that push-based Actions executed, failed, passed or are disabled.
The first GitHub-native CI run result remains NOT_VERIFIED.
Whether this workflow is a mandatory branch protection check is also NOT_VERIFIED.
Do not represent local unittest PASS as CI PASS.

## Safety/launch disposition

Correction prompt persistence is still blocked by platform safety inspection; the same
previously refused diagnostic prompt must not be repackaged or sent via alternate transport
to evade that refusal. No support ticket has been submitted through an authorized channel
in this run. Private incident report remains available at
ytdlnisx/incidents/2026-10-08_prompt-persistence-safety-refusal.md.

Next permitted steps:
- user or authorized support channel can submit/check the incident and return a documented
  permissible workflow (no assumption of automatic approval);
- if GitHub Actions push run data becomes independently available, verify that exact workflow;
- only then consider independently approved new agent prompt authoring/persistence through normal
  preflight/postwrite checks and correct current protocol/ref identities.

Do NOT route implementation agent, run Gradle, alter Windows permissions or protected files.
Implementation candidate remains pinned c34b8aa5; local dirty candidate is reported preserved,
not independently re-examined in this GitHub-only review.
CHAINED_CLOSURE_POLICY=BLOCKED
CHAINED_CLOSURE_BREAK_REASON=ETW_OBSERVER_ACCESS_DENIED_AND_GRADLE_JAVA_EFFECTIVE_TOKEN_UNVERIFIED
