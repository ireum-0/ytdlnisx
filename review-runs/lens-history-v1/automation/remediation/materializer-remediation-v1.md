# Materializer remediation protocol v1

This protocol applies only to `lens-history-v1` Materializer artifacts on branch `review/lens-history-v1`.

It exists to repair a deterministic semantic-field defect discovered in an already sealed immutable Materializer rank receipt without rewriting history, deleting receipts, or weakening ancestry guarantees.

## Scope

Version 1 is intentionally narrow.

It may be used only when all of the following are true:

1. Materializer state is `HOLD`.
2. The original rank receipt and any aggregate that includes it already exist and are immutable.
3. Exact source/frozen provenance, implementation SHA, schema class, checkpoint kind, finding evidence, exception evidence, and rank ancestry remain valid.
4. The defect is limited to `checkpoint.effectiveness_eligibility`.
5. The correct replacement value is uniquely determined by the already-pinned `lens-history-v1` protocol and existing exact evidence.
6. No source checkpoint, official inventory, official ledger, official validation, or progress record has to be rewritten.
7. No finding, finding disposition, modern lens attribution, metric, exception trigger, history binding, source/frozen blob, or implementation provenance changes.

If any condition above is not satisfied, this protocol MUST NOT be used. The run remains HOLD pending a broader append-only remediation design.

## Immutability

The original rank receipt and original aggregate are never overwritten, deleted, amended, rebased, or force-pushed.

The original rank receipt blob remains the ancestry/chain anchor for:

- `previous_rank_receipt` bindings in already sealed later rank receipts;
- Git commit ancestry;
- the original five-rank aggregate receipt.

A remediation does not replace the original Git object. It supplies an append-only effective-field overlay for semantic consumers.

## Required append-only artifacts

A valid remediation has three artifacts.

### 1. Field invalidation

Path:

`automation/remediation/materializer/<run_id>/r<rank>-effectiveness-invalidation-<n>.json`

It MUST bind:

- exact run/rank;
- HOLD state path/blob/commit;
- original rank receipt path/blob;
- original aggregate path/blob;
- exact source/frozen path/blob;
- recorded `checkpoint.kind`;
- recorded `checkpoint.effectiveness_eligibility`;
- deterministic expected value and protocol basis;
- statement that all non-target receipt fields remain authoritative;
- statement that original blobs remain immutable ancestry anchors.

### 2. Field replacement overlay

Path:

`automation/remediation/materializer/<run_id>/r<rank>-effectiveness-replacement-<n>.json`

It MUST bind the invalidation path/blob and original receipt path/blob, and MUST contain exactly one semantic replacement:

`checkpoint.effectiveness_eligibility`

No unlisted field may be changed by the overlay.

The replacement MUST state:

- `changes_history=false`;
- `changes_source_frozen_binding=false`;
- `changes_implementation_provenance=false`;
- `changes_checkpoint_kind=false`;
- `changes_lens_attribution=false`;
- `changes_effectiveness_metrics=false`;
- `changes_finding_attribution=false`;
- `changes_exception_detection=false`.

### 3. Effective aggregate overlay

Path:

`automation/remediation/materializer/<run_id>/r<start>-<end>-effective-<n>.json`

It MUST bind:

- original five-rank aggregate path/blob;
- all five original rank receipt paths/blobs;
- field invalidation path/blob;
- field replacement path/blob;
- target rank/field;
- statement that the original aggregate remains immutable;
- statement that downstream semantic consumers MUST apply the field replacement overlay when evaluating the segment;
- statement that ancestry validation continues to use the original immutable rank/aggregate blobs.

## Effective-view rule

For semantic interpretation only:

`effective_rank_receipt = original_rank_receipt + ordered_valid_field_replacement_overlays`

For Git ancestry and chain validation:

`chain_rank_receipt = original_rank_receipt`

Thus later immutable receipts that bind the original receipt blob remain valid. The remediation overlay does not create a new previous-receipt blob and does not cascade replacement through already sealed later receipts.

For an aggregate with a valid effective overlay:

`effective_segment = original_aggregate + effective_aggregate_overlay`

The original aggregate remains historical evidence and remains the immutable chain object.

## State binding

After all remediation artifacts are written and re-fetched, Materializer state may leave HOLD only if it binds the exact remediation protocol blob and all exact remediation artifact blobs.

The resumed state MUST:

- keep all original completed microsegment paths/blobs;
- add an explicit `materializer_remediations` binding;
- record the prior HOLD state blob/commit;
- keep the original next uncovered rank;
- keep official outputs deferred;
- preserve all existing exception ranks;
- mark adaptive-size streak eligibility false for the affected batch.

Future Materializer/Triage/Auditor consumers MUST apply bound remediation overlays before semantic comparison.

## Prohibited use

This protocol MUST NOT be used to:

- alter checkpoint kind;
- alter source/frozen identity or history order;
- alter implementation SHA provenance;
- convert legacy lens evidence to modern L1-L6 attribution;
- create or change numeric effectiveness metrics;
- add/remove/reclassify a finding or status transition;
- hide, add, or remove semantic exceptions;
- repair a mismatch after official batch materialization has already been certified;
- rewrite any immutable receipt or aggregate.

Any such defect requires a separate append-only remediation protocol and HOLD remains in force.
