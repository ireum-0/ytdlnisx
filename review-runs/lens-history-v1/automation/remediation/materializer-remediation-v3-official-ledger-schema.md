# Materializer remediation protocol v3 — official ledger schema conformance

This protocol applies only to `lens-history-v1` artifacts on branch `review/lens-history-v1`.

It exists to repair an already-materialized official ledger representation that is semantically faithful to sealed Materializer evidence but fails the exact pinned `lens-history-v1.schema.json` only because a schema state-value wrapper contains disallowed diagnostic metadata. It is append-only and does not rewrite history.

## Scope

Version 3 is intentionally narrow. It may be used only when all of the following are true:

1. The live Materializer state is `HOLD` because of a pinned-schema validation contradiction.
2. The exact governing schema blob is `b2fe91adda91ecfbd1cfb1d6195f6ec1df68cf96`.
3. Every affected official ledger record has exact provenance to immutable Materializer evidence and its semantic `state`, `value`, and, for `DERIVABLE`, `rule`, are already supported by that evidence.
4. The only schema defect being repaired is extra diagnostic metadata inside a schema `stateValue` object, including `evidence` on `DIRECT` or `reason`/`evidence` on `DERIVABLE`.
5. No history commit, checkpoint path, source/frozen blob, implementation value, schema class, structural kind state/value/rule, effectiveness-eligibility state/value/rule, lens field state/value/rule, finding attribution, effectiveness metric, exception trigger, count, or progress value changes.
6. The replacement is one-for-one, record-for-record, in the same historical rank order.
7. If the affected output was already certified, the canonical Audit PASS has first been append-only invalidated and has zero unlock authority.

Any other defect remains HOLD and requires another explicitly scoped protocol.

## Deterministic state-value projection

For every field governed by `#/$defs/stateValue`, including nested structured effectiveness state values, the effective ledger projection is exactly:

- `DIRECT` -> `{"state":"DIRECT","value":<original value>}`
- `DERIVABLE` -> `{"state":"DERIVABLE","value":<original value>,"rule":<original rule>}`
- `NOT_VERIFIED` -> `{"state":"NOT_VERIFIED"}` plus the original `reason` only when present
- `NOT_APPLICABLE` -> `{"state":"NOT_APPLICABLE","reason":<original reason>}`

No `state`, `value`, `rule`, or allowed `reason` may be added, removed, normalized, inferred, or changed.

The transformation may only remove properties prohibited by the pinned schema. It may not transform ordinary non-stateValue objects.

## Immutable originals

Original official ledger files, validation files, Materializer/Triage/Auditor receipts, final audits, progress records, and Git commits remain immutable historical evidence.

For already certified ranges:

- the original ledger blob remains the historical ledger object;
- its original PASS validation and canonical Audit PASS remain historical artifacts but have no current certification authority after valid append-only invalidation;
- no file is overwritten, deleted, amended, rebased, or force-pushed.

For an unfinalized current range, any schema-invalid candidate output already written also remains historical and must not be silently treated as authoritative.

## Required replacement artifacts

For each affected official batch create a NEW replacement JSONL under:

`automation/remediation/materializer/<run_id>/official-ledger-schema/<batch>-replacement-<n>.jsonl`

The replacement MUST:

- bind the original official ledger path/blob in a companion manifest;
- contain the same number of records in the same order;
- preserve every non-stateValue field byte-for-semantic-value;
- apply only the deterministic state-value projection above;
- validate with zero errors against the exact pinned Draft 2020-12 schema.

Create a companion JSON manifest:

`automation/remediation/materializer/<run_id>/official-ledger-schema/<batch>-replacement-<n>.json`

It MUST bind:

- run_id and logical batch range;
- original ledger path/blob;
- replacement ledger path/blob;
- governing schema path/blob;
- record count;
- ordered historical rank range;
- per-record projection result;
- number and exact field paths of removed prohibited properties;
- full-schema validation result;
- `changes_semantic_values=false`;
- `changes_history=false`;
- `changes_source_frozen_binding=false`;
- `changes_implementation_provenance=false`;
- `changes_schema_class=false`;
- `changes_checkpoint_kind=false`;
- `changes_effectiveness_eligibility=false`;
- `changes_lens_attribution=false`;
- `changes_effectiveness_metrics=false`;
- `changes_finding_attribution=false`;
- `changes_exception_detection=false`;
- `changes_progress=false`.

After all affected batches for one run are replaced, create a NEW run-level remediation manifest under:

`automation/remediation/materializer/<run_id>/official-ledger-schema-remediation-<n>.json`

It MUST bind all original and replacement ledger blobs, all batch manifests, the immutable Materializer COMPLETE state or current HOLD state as applicable, the canonical audit invalidation evidence, governing pins, and zero semantic-value-change assertions.

## Certified historical runs

For a previously certified run, replacement ledger evidence does not by itself restore certification.

The recovery order is:

1. canonical PASS invalidation;
2. complete official-ledger replacement under this protocol;
3. independent full-range re-audit against replacement ledger blobs;
4. replacement Auditor receipts under append-only remediation paths;
5. one `SUPERSEDING_REAUDIT` PASS only if every exact-binding, schema, provenance, semantic, exception, and aggregate gate passes.

The superseding re-audit MUST bind this protocol blob, the run-level remediation manifest, every replacement ledger/manifest blob, the original immutable Materializer state/pins, the existing Triage evidence when still semantically applicable, and the prior invalidation evidence.

A downstream run may regain unlock authority only after its immediately preceding certification chain has been restored. For the current systemic defect this means recovery proceeds in order from M05, then M06, M07, M08, M09, M10.

## Current M11 rule

M11 ranks 441-490 and their immutable one-rank/five-rank receipts remain historical evidence.

The already-written schema-invalid candidate ledgers 0048-0052 are not authoritative official outputs while M11 is HOLD.

M11 MUST remain HOLD until:

1. M05-M10 have valid superseding certification authority in sequence;
2. M11 candidate ledgers are replaced under this protocol;
3. M11 official validation/progress/COMPLETE are generated only from schema-conformant effective ledger evidence;
4. normal Triage and independent Auditor gates subsequently pass.

## Validation requirements

A replacement is valid only when all of the following are independently checked:

- exact original ledger blob matches the manifest;
- exact replacement blob matches the manifest;
- record count/order is unchanged;
- checkpoint commit/file binding is unchanged;
- implementation state/value is unchanged;
- structural kind state/value/rule is unchanged;
- effectiveness eligibility state/value/rule is unchanged;
- lens/effectiveness semantic values are unchanged;
- finding attribution is unchanged;
- full pinned JSON schema validation returns zero errors;
- no original artifact was rewritten.

A field-presence-only check is insufficient.

## Prohibited use

This protocol MUST NOT:

- change any semantic state/value/rule;
- repair unsupported DIRECT/DERIVABLE claims;
- change checkpoint kind or effectiveness eligibility;
- change source/frozen identity, history order, implementation provenance, schema class, finding attribution, lens attribution, metrics, exceptions, or progress;
- make a legacy-to-modern lens inference;
- convert absent values to zero or NOT_RUN;
- make a schema-invalid original ledger authoritative by declaration alone;
- restore Audit unlock authority without a full independent superseding re-audit;
- rewrite or delete any original artifact.

If any replacement requires more than deterministic removal of schema-prohibited diagnostic metadata, stop and remain HOLD.
