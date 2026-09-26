# Trigger-first lens policy adoption review

checkpoint_kind: REVIEW_METHOD_GOVERNANCE
canonical_status_change: NONE
canonical_count_change: NONE
production_source_modified: NO

## Decision

Historical review evidence does not justify a global fixed lens order. Historical total lens effectiveness remains NOT_VERIFIED.

The adopted lens-selection policy is:

- commit: 822ffe6a9cd45b951550fcb559557f0cf0798610
- blob: 49600871d632fd8612bbabec80dfaa996afb54d3
- path: REVIEW_LENS_SELECTION_POLICY_V1.md

The governing sequence is:

BASELINE L1-L6
-> determine semantic triggers
-> fully close triggered modules/contracts
-> choose one primary DEEP lens from current unresolved risk
-> rotate remaining not-yet-DEEP lenses deterministically

## Critical refinement

A triggered module does not automatically make every related lens DEEP.

The triggered module/contract is closed first. The unresolved evidence produced by that closure then selects the primary not-yet-DEEP lens using:

R1 direct current-root ownership
R2 triggered-module unresolved risk
R3 materially changed production boundary
R4 preserved-closure risk
R5 fixed L1->L2->L3->L4->L5->L6 tie-break only

The fallback is deterministic bookkeeping, not an effectiveness ranking.

## Latest review confirmation

Implementation 58c067b8a7a82320e9a369b77c642b025579d8c8 remains NOT_CLEAN for BUG-TERMINAL-03 under review checkpoint ae68d8a13c8da970c3417615d7117951d26525ec.

That review confirms a persisted-generation marker-collision residual:

- a new current-format marker was stored inside a durable user-command representation;
- supported pre-marker state could already contain the exact future marker bytes;
- therefore marker equality alone did not prove which generation created the state;
- current tests covered ordinary legacy rows and new-writer rows but did not cover the exact historical marker collision.

This supports trigger-first review:
- durable representation strengthening triggers Module F immediately;
- old-state compatibility fixtures must be seeded directly, not manufactured by the new writer;
- generation/provenance evidence exposed by Module F raises L2/L1 relevance as appropriate;
- this does not imply a fixed L1/L2 global order.

The earlier BUG-BACKUP-11 review independently supports the same scheduling rule from a different direction:
- executable configuration had an alternate backup/restore producer;
- the producer graph was discovered when L6 was examined deeply;
- Module C/H and producer/consumer closure should therefore run when triggered, not wait for a later lens turn.

The BUG-CANCEL-02 Terminal subcase similarly shows that cancellation/publication/live-owner obligations should run when their contract is touched, rather than waiting for a predetermined L4 turn.

## Manual-review consequence

For future manual multi-lens correctness reviews:

1. new SHA: BASELINE all L1-L6;
2. compute trigger map;
3. close triggered modules/contracts in the same run;
4. select one primary DEEP lens from current unresolved evidence;
5. on repeated same-SHA review, rerun the full checklist, recompute triggers, and promote one remaining not-yet-DEEP lens by R1-R5;
6. record trigger_map, lens_coverage_current_sha, primary_deep_lens, selection reason, remaining_not_yet_deep, and next_not_yet_deep_lens;
7. if materially new evidence appears, recompute the next lens and record why it changed.

No finding is created, closed, waived, reclassified, or recounted by this checkpoint.
