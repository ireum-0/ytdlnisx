# lens-history-v1/document-identity-v1

This rule is used only to normalize a checkpoint document's structural kind when the kind is not already explicitly recorded as a field.

It does not derive lens coverage, lens identity, effectiveness, finding counts, or final-review eligibility.

## DIRECT takes precedence

If the exact source document explicitly records a `checkpoint_kind` or an equivalent explicit kind field, that source value is `DIRECT` and this rule is not used.

## Allowed DERIVABLE structural kinds

A structural kind may be `DERIVABLE` only when the exact source document itself contains an unambiguous positive document-identity marker. Filename or chronology alone is insufficient.

### `SUBSTANTIVE_NON_FINAL`

Derive `SUBSTANTIVE_NON_FINAL` only when all of the following are true:

1. no explicit `checkpoint_kind` field is present;
2. the document positively identifies itself as a finding, addendum, reconciliation, or scope-expansion record rather than a run-final checkpoint;
3. its body records a finding/status/count reconciliation or a refinement of an already identified root;
4. the document does not claim to be the final checkpoint/verdict for a review run.

Examples in the frozen dataset that satisfy this rule:

- `review-runs/checkpoints/2026-09-15__a5160ab5__cleanup-stale-row-authority.md`: title identifies a focused cleanup-stale-row authority record and the body contains `## New finding` with a new P2 root.
- `review-runs/checkpoints/2026-09-15__a5160ab5__cleanup-stale-row-recovery-debt-addendum.md`: title explicitly identifies an addendum and the body records `Scope expansion — same root, count delta 0`.

## Failure mode

If the positive document identity is ambiguous, the kind is `NOT_VERIFIED`. Absence of `FINAL` text, filename suffixes, timestamp position, or neighboring commit order is not sufficient by itself.

## Cumulative-effectiveness consequence

`SUBSTANTIVE_NON_FINAL` is structurally excluded from cumulative structured-final effectiveness. Its cumulative contribution is therefore `NOT_APPLICABLE`. Any explicitly recorded substantive finding remains `DIRECT`, while modern lens attribution remains `NOT_VERIFIED` unless the source explicitly records it.
