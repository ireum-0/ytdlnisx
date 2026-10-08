# 2026-10-08 — prompt integrity guard and platform refusal disposition

checkpoint_kind: REVIEWER_WORKFLOW_INTEGRITY_RECONCILIATION
checkpoint_status: FINAL
manual_review_run: NO
review_start_parent: 598c74b2d323ffe005a94fb2bdcf39c045a49603
implementation_pinned_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
previous_governance_blob: c4abfadcd1aa3e58d2e1f862985ac78a381fa934
new_future_applicable_governance_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
canonical_production_root_count_delta: 0
new_production_finding_ids: NONE
BUG-SCHEDULER-01: OPEN P2; implementation correctness NOT_VERIFIED
independent_test_execution: NOT_VERIFIED

## Trigger and actual evidence

The original restricted-token diagnostic prompt (private blob
03fe488c76294b6a68c19331f330c0eac44c74e2) accidentally records a 45-hex
index SHA256 prefix:
f9f5ab150f4938d0ba3973a84b1c9d371d8518e60ecd3

Earlier sealed evidence and the latest handoff record the complete 64-hex index SHA256:
f9f5ab150f4938d0ba3973a84b1c9d371d8518e60ecd3d609bc80e94628cf664

The implementation agent correctly stopped fail-closed at bootstrap, repeatedly. No
diagnostic or build/Gradle activity occurred in the latest stopped attempts. The user
requested an end-to-end recurrence-prevention effort.

Reviewer-created private branch files, all re-read:
- ytdlnisx/review-tools/validate_persisted_prompt.py
  blob 7c881a1c6f25f85e382a10bb2c3f36dd5fe07fc4; fail-closed 64-hex SHA256 and source/ref preflight.
- ytdlnisx/review-tools/test_validate_persisted_prompt.py
  blob cb38f5dd9c0b25673780634d38c3636523ff985d; eleven authored regression cases including the 45-hex incident,
  duplicated/missing digest, source mismatch, protocol/review mismatch, and launch state.
- .github/workflows/ytdlnisx-prompt-integrity.yml
  blob 2e3a22e0e654cf696ff41c2b7f3bc36f16318079; push-triggered private-branch check requested.
- ytdlnisx/REVIEW_PROTOCOL.md, revised blob
  0a32a5df9db4fe5a92ca7f921e1b939d1159eb79; Section 7.1 rule 27 defines
  typed-literal length, independent evidence match, GitHub post-write readback and
  persistent-prompt launch gating.
- ytdlnisx/NEXT_CHAT.md, updated to the revised CURRENT expected protocol blob while
  preserving historical governance identities and reviewer-only no-prompt state.

IMPORTANT: GitHub current commit status queried, but no CI status was returned. Successful
CI run, executing tests in a Python interpreter, and branch-protection enforcement are
NOT_VERIFIED. The 11 cases are authored, not independently claimed to have run or passed.
GitHub contents API readback of static validator/tests/workflow and protocol passed.

## Blocked platform write

Repeated reviewer attempts to create a corrected prompt containing the previously authorized
restricted-token Windows access diagnosis were blocked by platform safety inspection.
The original invalid prompt was NOT overwritten. Safety-check refusal is an authorization
boundary: do not retry indefinitely, disguise equivalent instructions, or use a different
transport to bypass that check. No new launch-ready prompt exists.

## Current state and next boundary

- private reviewer handoff NEXT_ACTION_OWNER=REVIEWER;
- PROMPT_PATH=NONE; PROMPT_READINESS=NOT_PERSISTED;
- IMPLEMENTATION_AGENT_CURRENTLY_WORKING=NO;
- implementation SHA unchanged; local protected draft as last sealed:
  HEAD c34b8aa57e01803c9960e4ad873d1ed5b68e019c,
  dirty tree df2d52c20c6928bf74b7d1c4f230483189b5aa5b,
  three unstaged files and empty index;
- protected worktrees and prior evidence remain as last sealed, not independently
  re-inspected from this GitHub-only reviewer.

Next reviewer action:
1. establish actual validator test execution/CI availability and correct any demonstrated
   regression or workflow failure without claiming unobserved success;
2. resolve the platform refusal via approved support/review or independently authorize a
   substantively narrower nonintrusive task; never conceal or bypass a refused capability;
3. persist/re-read a valid prompt only when legitimately authorized and the write succeeds;
4. advance handoff to launch ready only after protocol/ref/checksum/preflight and real
   postwrite validation succeed.

No Gradle build, Windows token probe, source mutation, commit/push or fixed/clean claim is
authorized by this checkpoint. Historical pinned governance is unchanged.
