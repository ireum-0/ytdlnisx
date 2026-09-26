# Manual correctness review checkpoint — 58c067b8 — INTERMEDIATE CORRECTION

checkpoint_kind: INTERMEDIATE_CORRECTION
run_mode: manual_trigger_3
review_parent_sha: `b364a005fcdc92d4faa359a3be68828a02cdd62f`

## Why this correction exists

The immediately preceding intermediate checkpoint correctly recorded the trigger map and BUG-TERMINAL-03 marker-collision residual, but it treated L2 as newly selected DEEP for this run.

Fresh same-SHA checkpoint reconciliation shows that this is not the current lens state.

After Review Lens Selection Policy v1 was adopted, the later checkpoint:

`review-runs/checkpoints/2026-09-26__v7-shadow-58c067b8__37abf093.md`

performed a current exact-source review under governing v6, applied the candidate v7 rules only as a shadow addition, and explicitly recorded:

- L1 BASELINE
- L2 DEEP
- L3 BASELINE
- L4 BASELINE
- L5 BASELINE
- L6 BASELINE

with L2 selected by R1 + R2.

The v7 candidate was non-governing, but the source review and v6 lens coverage recorded in that checkpoint remain valid review evidence. The pre-adoption hold did not invalidate that lens coverage.

Therefore this manual run is a **later same-SHA review** for lens-rotation purposes.

No previous evidence is overwritten. This checkpoint supersedes only the lens-progression interpretation in the preceding intermediate file.

## Correct current lens state before this run's promotion

lens_coverage_current_sha:
- L1: BASELINE
- L2: DEEP
- L3: BASELINE
- L4: BASELINE
- L5: BASELINE
- L6: BASELINE

## Trigger map status

The trigger conclusions from the preceding intermediate remain unchanged:

- Module F: OPEN / FAIL — exact marker collision does not prove persisted generation.
- Module B: reviewed; no separate handoff root, but exact handoff cannot cure false generation provenance.
- Module C: OPEN under BUG-TERMINAL-03 and distinct BUG-BACKUP-11 authority projection.
- Module H: OPEN under BUG-TERMINAL-03 / BUG-BACKUP-11 executable-config fan-out.
- Module A: OPEN under BUG-BACKUP-11 SAF authorization.
- Module I: OPEN under BUG-CANCEL-02 Terminal publication/live-owner subcase.

## Primary DEEP lens for this run

primary_deep_lens: **L1 Durability & recovery**

primary_deep_selection_reason:
- R1: among remaining not-yet-DEEP lenses, L1 directly owns the durable generation/recovery-carrier compatibility question exposed by the current BUG-TERMINAL-03 residual.
- R2: Module F remains OPEN and its unresolved cell is whether supported old durable row/carrier generations can be distinguished and recovered safely by current startup/retry/worker gates.
- R3-R5 are not needed.

L2 remains DEEP and is revalidated in this run; it is not counted as a new promotion.

## Corrected target coverage for this run

After completion:
- L1: DEEP
- L2: DEEP
- L3: BASELINE
- L4: BASELINE
- L5: BASELINE
- L6: BASELINE

remaining_not_yet_deep:
- L3
- L4
- L5
- L6

Provisional next_not_yet_deep_lens:
**L3**

Reason:
R1/R2 continuation — once the durable-generation carrier question is fully explored under L1, the nearest remaining same-root question is whether exact stale/current authority can race or be reauthorized across reconcile/retry boundaries. This hint must be recomputed at FINAL from fresh evidence.

## Finding/count impact

None.

- BUG-TERMINAL-03 remains existing P2.
- provisional BUG-BACKUP-11 remains existing distinct P2.
- BUG-CANCEL-02 Terminal remains an existing-root subcase.
- new finding ID delta: 0.
