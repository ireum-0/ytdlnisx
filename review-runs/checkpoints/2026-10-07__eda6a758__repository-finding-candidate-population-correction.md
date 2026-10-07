# Repository finding candidate-population correction — 2026-10-07

checkpoint_kind: REPOSITORY_FINDING_INVENTORY_CORRECTION
checkpoint_status: FINAL
review_parent_sha: 75908123a7c0ae33091e6a329dfae7ce2cb19511
implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

## Correction

The prior inventory-extraction checkpoint derived 136 candidate IDs from:
- TASKS.md;
- TASKS_DELTA.md;
- the then-current open-root list.

That value is a registry-derived lower bound, not a complete project-discovery population.

Fresh review of historical checkpoints proves that distinct production findings can exist only in
review checkpoint history without a TASKS/TASKS_DELTA heading or presence in the current open list.

Confirmed examples:
- provisional BUG-TERMINAL-11, discovered as a distinct startup sibling-isolation root while
  BUG-TERMINAL-03 closed;
- BUG-OBSERVE-HANDOFF-01, referenced as an independently owned Observe handoff root in later closure
  checkpoints.

Therefore:
- REPOSITORY_FINDINGS_REGISTRY_DERIVED_CANDIDATE_IDS=136
- REPOSITORY_DISCOVERED_CANONICAL_ROOTS_TOTAL=NOT_YET_VERIFIED
- CHECKPOINT_ONLY_FINDING_DISCOVERY=REQUIRED
- the full project total must not be published until checkpoint-only distinct roots, aliases,
  provisional roots, and later supersessions are reconciled.

The already completed current-existence classifications remain valid for the roots they reviewed.
Only the candidate-population completeness claim is corrected.

## Discovery rule

Checkpoint-only identifiers are counted as project-discovery candidates when the evidence establishes a
production semantic finding, even if the finding was later closed, aliased, renamed, or superseded.

They are not automatically counted as distinct canonical roots:
- provisional IDs must be reconciled with later aliases/renames;
- same-root residuals count once;
- rejected candidates/false positives are recorded but excluded from confirmed-root totals;
- tooling/harness-only blockers are tracked separately from production correctness roots.

Current download canonical scope and counts remain unchanged:
P0=0 / P1=0 / P2=8.

production_source_changed: NO
prompt_changed: NO
active_implementation_changed: NO
ledger_changed: NO
