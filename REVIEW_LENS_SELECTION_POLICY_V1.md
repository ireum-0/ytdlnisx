# Review Lens Selection Policy v1 — Trigger-First Rotation

This policy governs lens selection for repeated correctness reviews. It does not rank lenses by historical effectiveness. Historical total lens effectiveness remains NOT_VERIFIED, so no global best-to-worst lens order is justified.

Governing sequence:

BASELINE L1-L6
-> determine semantic triggers
-> fully close triggered modules/contracts
-> choose one primary DEEP lens from current unresolved risk
-> rotate remaining not-yet-DEEP lenses deterministically

## New implementation SHA

For every newly observed implementation SHA:

1. Pin the exact implementation SHA and governing refs.
2. Perform at least BASELINE review for L1-L6.
3. Build a trigger map from the material semantic-contract delta, the current open root, persisted/legacy state, external/platform authority, scheduler/publication/destructive/cancellation boundaries, and alternate producer/write/import/restore/migration/recovery paths.
4. Complete every blocker-relevant triggered conditional module and contract-closure obligation in the same run. Do not defer it because another blocker already makes the run NOT_CLEAN or because the associated lens is not the current DEEP turn.
5. Then select one primary DEEP lens using the relevance rules below.
6. Record lens coverage and the deterministic next not-yet-DEEP lens.

Triggered-module execution is independent of lens depth. Running a related module does not automatically mark the corresponding lens DEEP.

## Same SHA on later runs

For each later review of the same SHA:

1. Perform the full governing checklist again.
2. Recompute the trigger map from current evidence.
3. Complete newly triggered blocker-relevant obligations immediately.
4. Promote one not-yet-DEEP lens using the relevance rules.
5. If all lenses are already DEEP, revisit the lens directly implicated by materially new evidence or move to the next governed root/action. Do not create a fake rotation.

## Primary DEEP selection

Choose only among lenses not yet DEEP for the current SHA.

Apply these rules in order:

R1 — Direct current-root ownership.
Prefer the lens whose core invariant directly owns the confirmed residual or material changed contract.

R2 — Triggered-module unresolved risk.
If a triggered module remains open or materially uncertain, prefer the lens most directly responsible for that unresolved cell.

R3 — Materially changed production boundary.
If R1/R2 do not distinguish candidates, prefer the lens whose production boundary changed materially.

R4 — Preserved-closure risk.
Prefer the lens most likely to invalidate a preserved closure that the current fix depends on.

R5 — Tie-break only.
If candidates remain genuinely tied, use:
L1 -> L2 -> L3 -> L4 -> L5 -> L6.

R5 is only a deterministic tie-break. It is not an effectiveness ranking.

## Trigger examples

These examples are trigger guidance, not fixed lens rankings:

- durable representation, migration, or old persisted state -> Module F immediately;
- external provider/URI/authority representation -> Module C immediately;
- persistent setting/import/restore later affects executable behavior -> Module H plus producer/consumer/effect closure immediately;
- WorkManager/scheduler durable handoff -> Module B plus exact request/acceptance/recovery ownership immediately;
- cancellation/publication/cleanup -> live-owner, cross-attempt, and final-effect/destructive review immediately;
- proven-invalid identity normalization/equality -> targeted sibling-pattern identity search immediately.

A triggered obligation must be closed or left explicitly OPEN/NOT_VERIFIED in that run. It must not be silently postponed to a future DEEP turn.

## Latest persisted-generation lesson

The review of implementation 58c067b8a7a82320e9a369b77c642b025579d8c8 confirmed a same-root BUG-TERMINAL-03 residual: a current-format marker stored inside a user-controlled durable command string was not independent proof of the generation that wrote the row, because pre-marker rows could already contain the exact future marker bytes.

The stable lesson is not “always review L1 before L2.” It is:

- strengthening durable representation triggers Module F immediately;
- compatibility tests seed the old representation directly rather than passing through the new writer;
- generation/provenance proof cannot be inferred from bytes that old state could already legally contain;
- unresolved identity/provenance discovered by Module F raises L2/L1 relevance for primary DEEP selection.

## Checkpoint fields

Every manual multi-lens review checkpoint should record:

- implementation_sha
- lens_coverage_current_sha for L1-L6
- trigger_map with trigger, module/contract, reason, status, and evidence
- primary_deep_lens
- primary_deep_selection_reason with R1-R5
- remaining_not_yet_deep
- next_not_yet_deep_lens
- next_lens_selection_reason

next_not_yet_deep_lens is a continuation hint, not an immutable lock. Materially new evidence may change it; the next checkpoint must record why.

## Guardrails

- Do not derive a global lens ranking from historical finding counts.
- Do not mark a lens DEEP merely because a related module ran.
- Do not postpone a known triggered module until that lens’s turn.
- Do not run unrelated modules merely to increase coverage.
- Do not let relevance-first selection starve remaining lenses once higher-relevance obligations are closed.
- Do not reuse a prior verdict on the same SHA.

This policy changes review scheduling and coverage only. It does not create, close, waive, reclassify, or reattribute production findings.
