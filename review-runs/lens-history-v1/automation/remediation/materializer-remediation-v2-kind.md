# Materializer remediation protocol v2 — checkpoint kind

This protocol applies only to `lens-history-v1` Materializer artifacts on branch `review/lens-history-v1`.

It provides an append-only repair mechanism for a deterministic `checkpoint.kind` classification defect in an already sealed immutable one-rank Materializer receipt. It does not weaken the evidence-state rules, document-identity rule, ancestry requirements, or immutable-receipt model.

## Scope

Version 2 is intentionally narrow. It may be used only when all of the following are true:

1. Materializer state is `HOLD`.
2. The original rank receipt already exists and remains immutable.
3. Exact history resolution, changed-file enumeration, source/frozen identity, implementation SHA, schema class, finding evidence, exception evidence, and rank ancestry remain valid.
4. The defect is limited to `checkpoint.kind`.
5. The correct replacement kind is uniquely determined by the already-pinned `lens-history-v1/document-identity-v1` rule and exact source evidence.
6. The corrected kind does not require changing finding attribution, semantic exception detection, provenance, lens attribution, or numeric effectiveness.
7. `checkpoint.effectiveness_eligibility` is already semantically valid under the corrected kind, or any distinct eligibility defect is handled separately by a valid applicable remediation protocol.
8. No official inventory, ledger, validation, progress, Triage, or Auditor output for the affected materialization batch has been written.

If any condition is not satisfied, this protocol MUST NOT be used and the run remains HOLD.

## Immutability and chain anchors

The original rank receipt is never overwritten, deleted, amended, rebased, force-pushed, or replaced as a Git ancestry object.

For chain validation, later one-rank receipts continue to bind the original receipt blob in `previous_rank_receipt`.

For semantic interpretation only, consumers apply the ordered valid kind replacement overlay.

## Required append-only artifacts

### 1. Kind invalidation

Path:

`automation/remediation/materializer/<run_id>/r<rank>-kind-invalidation-<n>.json`

It MUST bind:

- exact run/rank;
- HOLD state path/blob/commit;
- original rank receipt path/blob;
- exact source/frozen path/blob;
- pinned document-identity rule path/blob;
- recorded `checkpoint.kind`;
- deterministic expected `checkpoint.kind`;
- evidence that all non-kind fields remain authoritative;
- statement that the original rank blob remains the immutable chain anchor.

### 2. Kind replacement overlay

Path:

`automation/remediation/materializer/<run_id>/r<rank>-kind-replacement-<n>.json`

It MUST bind the invalidation path/blob and original receipt path/blob and MUST contain exactly one semantic replacement:

`checkpoint.kind`

The replacement MUST state:

- `changes_history=false`;
- `changes_source_frozen_binding=false`;
- `changes_implementation_provenance=false`;
- `changes_schema_class=false`;
- `changes_effectiveness_eligibility=false`;
- `changes_lens_attribution=false`;
- `changes_effectiveness_metrics=false`;
- `changes_finding_attribution=false`;
- `changes_exception_detection=false`.

### 3. Effective aggregate overlay after segment seal

If the affected rank's five-rank aggregate already exists, an effective aggregate overlay is required before leaving HOLD.

If the affected rank is in an unsealed five-rank segment, Materializer may leave HOLD after valid invalidation + replacement are bound in state, but the resumed state MUST mark the aggregate overlay as required. Later one-rank receipts continue to bind original blobs. Once the five original rank receipts are sealed and the original aggregate is written, create:

`automation/remediation/materializer/<run_id>/r<start>-<end>-kind-effective-<n>.json`

It MUST bind:

- original five-rank aggregate path/blob;
- all five original rank receipt paths/blobs;
- kind invalidation path/blob;
- kind replacement path/blob;
- target rank and field;
- corrected effective kind;
- statement that the original aggregate remains immutable;
- statement that downstream semantic consumers MUST apply the kind replacement overlay;
- statement that ancestry validation continues to use original immutable rank/aggregate blobs.

State MUST NOT advance past that five-rank segment until the effective aggregate overlay exists and is bound.

## Effective-view rule

For semantic interpretation:

`effective_rank_receipt = original_rank_receipt + ordered_valid_kind_replacement_overlays`

For Git ancestry and `previous_rank_receipt` validation:

`chain_rank_receipt = original_rank_receipt`

A corrected kind does not retroactively alter the original Git object.

## State binding

A pre-aggregate HOLD may be released only after Materializer state binds:

- this exact protocol path/blob;
- the exact invalidation path/blob;
- the exact replacement path/blob;
- the prior HOLD state blob/commit;
- the exact effective field change;
- `effective_aggregate_required=true` for the affected unsealed segment.

The resumed state:

- keeps all completed microsegments unchanged;
- keeps the original state-bound `next_rank` until the five-rank aggregate is sealed;
- keeps official outputs deferred;
- preserves existing exception ranks;
- records `hold_or_anomaly_occurred=true`;
- sets adaptive-size streak eligibility false for the affected batch;
- sets `resume_safe=true` only when the exact remediation chain validates.

The effective next rank is then reconstructed from the state-bound aggregate prefix plus valid original rank receipts, applying the bound kind overlay to the target receipt.

After the original five-rank aggregate is sealed, the effective aggregate overlay MUST be written and bound before state advances beyond the segment.

## Prohibited use

This protocol MUST NOT be used to:

- alter source/frozen identity or history order;
- alter implementation SHA provenance;
- alter schema class;
- alter `checkpoint.effectiveness_eligibility`;
- convert legacy lens evidence to modern L1-L6 attribution;
- create or change numeric effectiveness metrics;
- add/remove/reclassify a finding or status transition;
- hide, add, or remove semantic exceptions;
- repair a non-deterministic or ambiguous kind;
- repair a batch after official materialization/Triage/Auditor certification;
- rewrite any immutable receipt or aggregate.

Any such defect requires another explicitly scoped append-only protocol and HOLD remains in force.
