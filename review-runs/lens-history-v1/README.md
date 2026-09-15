# lens-history-v1

This directory defines the historical lens-coverage/effectiveness reconstruction contract for YTDLnisX review checkpoints.

This is review-history instrumentation metadata only. It does not modify production/application source and it must not rewrite historical checkpoint files.

## Frozen reconstruction basis

- source snapshot commit: `25a554d1768f8d3cdd09a6e384a89d915eeeace4`
- source snapshot root tree: `65b83782fe5ca70974da0ea457b09d8d07722aa7`
- `review-runs` tree at the snapshot: `4c235538a24a599755240104a2a1144e7700e807`
- `review-runs/checkpoints` tree at the snapshot: `bd72cf55e5e876f46ab1e592bf3008e6044d977b`
- frozen checkpoint-path history size: `764` path-touching commits
- oldest checkpoint-path commit: `3385e695edd2fdbb84b2ed69b9a9820f6acfef9c` (`review: add correctness checkpoint run 1`)

The reconstruction target is the frozen snapshot above. Review commits after that snapshot must not be folded into historical values for this dataset.

## Evidence states

Every reconstructable field is represented with one of these states.

- `DIRECT`: explicitly recorded by the source checkpoint or exact GitHub provenance.
- `DERIVABLE`: uniquely determined from complete inputs by a versioned rule in this document; no semantic inference is required.
- `NOT_VERIFIED`: the source corpus does not safely determine the value.
- `NOT_APPLICABLE`: the field is structurally inapplicable to the record, not merely missing.

`DIRECT` corresponds to historical Tier A. `DERIVABLE` corresponds to Tier B. `NOT_VERIFIED` corresponds to Tier C.

## Prohibited reconstruction

`lens-history-v1` MUST NOT:

1. map a legacy `audit_lens` string to modern `BASELINE` or `DEEP` coverage;
2. replace an unrecorded metric with numeric zero;
3. replace unrecorded coverage with `NOT_RUN`;
4. infer lens effectiveness from a prose statement such as "no new findings";
5. include bootstrap/intermediate metrics in cumulative effectiveness totals;
6. duplicate a multi-lens finding or status change across supporting lenses;
7. treat absence of evidence as `NOT_APPLICABLE`;
8. use post-snapshot review results to backfill the frozen dataset.

## Lens identifiers

- `L1`: Durability & recovery
- `L2`: Identity & provenance
- `L3`: Concurrency & authority
- `L4`: Destructive ownership
- `L5`: Platform contract closure
- `L6`: Cross-feature semantic propagation

## Historical schema eras

Era classification is record-driven. Do not assign an era from date alone when the checkpoint fields disagree.

### PRE_LENS

The checkpoint contains neither a legacy `audit_lens` nor structured lens fields.

- generic checkpoint/provenance fields may be `DIRECT`;
- modern lens coverage/effectiveness is `NOT_VERIFIED` unless independently explicit.

### LEGACY_AUDIT_LENS

The checkpoint records `audit_lens` but does not record structured coverage/effectiveness.

- the exact raw `audit_lens` string is `DIRECT`;
- modern L1-L6 mapping is `NOT_VERIFIED`;
- effectiveness metrics not explicitly present are `NOT_VERIFIED`.

A verified transition example is the 2bcffa78 lifecycle: `0f8f305526458001cea3bf158d23cac7a2e1810e` records the legacy `audit_lens`; `9de92c350989a7a4da4bbfa48a7275dceeb57d27` is a final checkpoint with that legacy schema.

### STRUCTURED_PROVISIONAL

A non-final checkpoint records structured coverage and/or provisional effectiveness.

Recorded values are `DIRECT` as checkpoint evidence, but their cumulative-effectiveness contribution is `NOT_APPLICABLE`.

The first verified structured checkpoint in the frozen chronology is:

- commit `4d1f937c90d19234b57496c6d2288323fe2d9e4a`
- file `review-runs/checkpoints/2026-09-15T122700Z__a5160ab5__8151c278__checkpoint.md`
- explicitly an intermediate checkpoint
- first verified `primary_deep_lens`, `lens_selection_reason`, `lens_coverage_current_sha`, and provisional effectiveness block.

### STRUCTURED_FINAL

A final checkpoint explicitly records structured coverage/effectiveness.

- recorded values are `DIRECT`;
- compatible aggregates may be `DERIVABLE` only when every included input is complete and the aggregation rule below applies.

The first verified structured FINAL in the frozen chronology is:

- commit `d8c9e3d94ecaaa6121f9912cab4240375e8b5680`
- file `review-runs/checkpoints/2026-09-15T123300Z__a5160ab5__4d1f937c__final-checkpoint.md`
- primary deep lens `L6`.

The last verified legacy-only FINAL immediately before structured instrumentation is:

- commit `c497da58ac844efb1a8d28f2da328f0253d1b914`
- file `review-runs/checkpoints/2026-09-15T112900Z__a5160ab5__0248ef2e__checkpoint.md`
- exact legacy field: `audit_lens: 5 — exact upstream API semantics + caller/consumer/final-effect closure`.

The intervening reconciliation commit `8151c278e6a6f1c28f47e52db14e6ebbe1faaa55` resolves the F10 status conflict but does not introduce structured lens fields.

## Field policy

| Field | Historical policy |
|---|---|
| implementation SHA / frozen implementation SHA | `DIRECT` when explicitly recorded |
| checkpoint commit and file provenance | `DIRECT` from GitHub |
| checkpoint kind | `DIRECT` when explicitly recorded; otherwise `DERIVABLE` only from unambiguous final/intermediate document identity; else `NOT_VERIFIED` |
| `audit_lens` | exact string `DIRECT`; modern mapping `NOT_VERIFIED` |
| `primary_deep_lens` | `DIRECT` when recorded |
| `lens_selection_reason` | `DIRECT` when recorded |
| `lens_coverage_current_sha` | `DIRECT` when recorded; missing values `NOT_VERIFIED` |
| `coverage_level` | `DIRECT` when recorded in an effectiveness block |
| `lens_scope_reviewed` | `DIRECT` when recorded |
| `new_confirmed_findings` | `DIRECT` only when explicitly recorded; otherwise `NOT_VERIFIED` |
| `existing_finding_status_changes` | same |
| `confirmed_residuals_or_subcases` | same |
| `rejected_candidates_with_proof` | same |
| `not_verified_candidates` | same |
| `checklist_gaps_triggered` | same |
| `upstream_semantic_checks` | same |
| `cross_feature_propagation_hits` | same |
| cumulative contribution of bootstrap/intermediate records | `NOT_APPLICABLE` |
| duplicate supporting-lens contribution for one finding/status change | `NOT_APPLICABLE` |

## Final-only aggregation rule

The versioned aggregate rule is `lens-history-v1/final-only-explicit`.

For each metric:

1. include only checkpoints whose final status is established;
2. include only explicitly structured effectiveness fields;
3. never synthesize missing values;
4. attribute a multi-lens finding/status change once to its recorded `primary_detecting_lens`;
5. supporting lenses do not receive duplicate finding/status-change counts;
6. sum only compatible numeric values whose complete inputs satisfy rules 1-5.

A sum produced under those conditions is `DERIVABLE` and must be named `observed_measured_effectiveness` or equivalent. It MUST NOT be labeled the historical total unless the historical input set is complete.

## Frozen-snapshot measured effectiveness

At the frozen snapshot there are two cumulative-eligible structured FINAL checkpoints with measured raw-effectiveness data:

1. `d8c9e3d94ecaaa6121f9912cab4240375e8b5680`: L6 measured block.
2. `25a554d1768f8d3cdd09a6e384a89d915eeeace4`: L1-L6 measured blocks; L4 is primary DEEP.

Applying `lens-history-v1/final-only-explicit` gives the following `DERIVABLE` observed sum across the explicitly measured FINAL blocks:

- `new_confirmed_findings = 0`
- `existing_finding_status_changes = 0`
- `confirmed_residuals_or_subcases = 1`
- `rejected_candidates_with_proof = 3`
- `not_verified_candidates = 1`
- `checklist_gaps_triggered = 0`
- `cross_feature_propagation_hits = 2`

These numbers are NOT the complete historical totals.

The complete historical totals remain `NOT_VERIFIED` because pre-structured finals lack comparable raw metrics and substantive discoveries can exist outside cumulative-eligible structured finals. A verified example is `CLEANUP-STALE-DOWNLOAD-ROW-01`, recorded at `eb2dc35fc97e75963b75d63ddb42a6811bf8336f`: the new P2 root is explicit, but the record has no structured final effectiveness attribution. The finding exists; assigning it to a modern lens effectiveness total is `NOT_VERIFIED`.

## Machine-readable value wrapper

Machine-readable records should use this shape instead of null/zero substitution:

```json
{"state":"DIRECT","value":0}
{"state":"DERIVABLE","value":3,"rule":"lens-history-v1/final-only-explicit"}
{"state":"NOT_VERIFIED"}
{"state":"NOT_APPLICABLE","reason":"intermediate checkpoint excluded from cumulative statistics"}
```

Arrays/objects use the same wrapper. `value` is omitted for `NOT_VERIFIED` and normally omitted for `NOT_APPLICABLE`.

## Record schema

A reconstruction record should contain at least:

```json
{
  "schema_version": "lens-history-v1",
  "source_snapshot": "25a554d1768f8d3cdd09a6e384a89d915eeeace4",
  "checkpoint": {
    "commit": "...",
    "file": "...",
    "kind": {"state":"DIRECT","value":"FINAL"}
  },
  "implementation_sha": {"state":"DIRECT","value":"..."},
  "era": "STRUCTURED_FINAL",
  "raw_audit_lens": {"state":"NOT_APPLICABLE","reason":"structured schema"},
  "primary_deep_lens": {"state":"DIRECT","value":"L4"},
  "lens_selection_reason": {"state":"DIRECT","value":"..."},
  "lens_coverage_current_sha": {"state":"DIRECT","value":{"L1":"BASELINE","L2":"BASELINE","L3":"BASELINE","L4":"DEEP","L5":"BASELINE","L6":"BASELINE"}},
  "effectiveness_eligibility": {"state":"DIRECT","value":"CUMULATIVE_ELIGIBLE"},
  "effectiveness": {},
  "finding_attribution": {}
}
```

The record MAY preserve exact raw strings alongside normalized identifiers, but normalized values must never create semantic information that is absent in the source.

## Frozen pilot provenance

The Phase-0 pilot mapping used to validate the reconstruction contract is:

| implementation SHA | final checkpoint file | checkpoint commit | schema |
|---|---|---|---|
| `c4f630a8699662ebc07d94caa006d70b9779091d` | `review-runs/checkpoints/2026-09-15T042900Z__c4f630a8__checkpoint.md` | `077e226ac4f24a9274c4eabd9044e2e563989def` | pre-structured |
| `09058d574d19d211cd0db36cbf47726bb8fa7dc5` | `review-runs/checkpoints/2026-09-14T114015Z__09058d57__checkpoint.md` | `ea3ea5954b7f0a92e94444a90130ba80b9bd77a9` | pre-structured |
| `55e54888a0e03c17a4cbdb51817d3ad4336107f4` | `review-runs/checkpoints/2026-09-14__55e54888__completed-wave-verdict.md` | `c844cee44aca0e1c781fafbe9f1d05d238e8ca4a` | pre-structured |
| `a67cb4ff8a367f3a4261eb816d4abfe1290b8e4f` | `review-runs/checkpoints/2026-09-15T133900Z__a67cb4ff__9218b8d2__final-checkpoint.md` | `25a554d1768f8d3cdd09a6e384a89d915eeeace4` | structured final |

Implementation and review checkpoint commits may be on diverged ancestry. Provenance is established by the checkpoint's explicit frozen/exact implementation SHA plus inclusion of the review checkpoint in the frozen review history; do not require the implementation commit to be the checkpoint commit's ancestor.

## Status

`lens-history-v1` reconstruction contract: `GO`.

Safe to backfill:

- exact checkpoint/GitHub provenance;
- exact raw legacy `audit_lens` strings;
- explicit structured coverage;
- explicit final effectiveness blocks;
- deterministic final-only aggregates over complete explicitly measured inputs.

Must remain `NOT_VERIFIED` unless additional direct evidence exists:

- modern L1-L6 mapping of legacy `audit_lens` values;
- missing pre-structured coverage levels;
- missing pre-structured effectiveness metrics;
- modern lens attribution of substantive findings not explicitly attributed;
- complete historical effectiveness totals.
