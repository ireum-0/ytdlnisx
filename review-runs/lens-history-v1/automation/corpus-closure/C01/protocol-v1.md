# lens-history-v1 / corpus-closure-v1

## Purpose

This protocol closes the frozen checkpoint-document corpus after all 764 checkpoint-path-touching history ranks have been materially reconstructed and independently audited.

CORPUS_CLOSURE is a distinct-document closure. It MUST NOT treat history-rank count as document count and MUST NOT rewrite any existing Materializer, Triage, Auditor, inventory, ledger, validation, progress, remediation, checkpoint, or production/application artifact.

## Governing pins

- frozen snapshot commit: `25a554d1768f8d3cdd09a6e384a89d915eeeace4`
- frozen checkpoints tree: `bd72cf55e5e876f46ab1e592bf3008e6044d977b`
- lens-history schema blob: `b2fe91adda91ecfbd1cfb1d6195f6ec1df68cf96`
- document identity rule blob: `982d8fda98d70c5a196e470b24facf1b7c337cda`
- verified seed blob: `531dbcbe9dd6854d969d8f1bc2b8a1a532788d51`
- frozen manifest blob: `8e327e4b0bde29586c3d512f8bd0b077a1a32b8c`
- terminal Materializer state blob: `c7d3584643e61d12ad6c06a4b59df79f9df63a45`
- terminal authoritative M17 Audit blob: `5aa46f29231166a8fb453c8c1ccb61526f677f67`
- terminal authoritative M17 Audit commit: `782fb35c499f98be7a7291688675a54fc798413c`

These pins are immutable for corpus-closure-v1. Any mismatch is a hard stop.

## Preconditions

Before any closure evidence write:

1. Fresh-fetch branch `review/lens-history-v1`.
2. Require the terminal M17 Audit to remain PASS with `unlock_allowed=true` and `next_logical_stage=CORPUS_CLOSURE`.
3. Require frozen history page 765 for `review-runs/checkpoints` to be explicitly empty.
4. Require all governing pins above to match actual Git objects.
5. Check for active/scheduled writers targeting `review/lens-history-v1`; do not race another writer.
6. Every closure write is append-only, one new file per commit, strict first-parent, non-force publication.

## Corpus identity

The closure corpus is the complete set of blob paths present under the exact frozen tree `bd72cf55e5e876f46ab1e592bf3008e6044d977b`.

- A history entry is a commit that touched `review-runs/checkpoints`.
- A frozen document is one distinct blob path present in the frozen checkpoints tree.
- A ledger record is one reconstruction record tied to exact checkpoint-document provenance.
- There is no assumed bijection among those three sets.

The frozen tree MUST be enumerated losslessly. A recursive tree response may be used only when GitHub reports `truncated=false`.

## Effective ledger

Closure MUST reconcile against the effective official ledger, not blindly against raw historical files.

For every ledger batch:
- bind the exact official ledger path/blob;
- apply only already-authorized append-only remediation overlays/replacements;
- preserve the original immutable ledger as provenance;
- never silently normalize a source/frozen mismatch, ambiguous kind, NOT_VERIFIED field, or historical semantic exception.

## Required closure artifacts

Closure run `C01` writes only new files under:

`review-runs/lens-history-v1/automation/corpus-closure/C01/`

Required artifacts:

1. `frozen-document-inventory.jsonl`
   - one row per distinct frozen checkpoint document;
   - exact path and frozen blob SHA;
   - deterministic lexical path order;
   - total count recorded in the final row metadata or accompanying reconciliation.

2. `reconciliation.json`
   - governing pins;
   - exact frozen-document count;
   - history-rank count = 764 and page-765-empty proof;
   - effective ledger record count;
   - duplicate ledger document paths;
   - frozen documents missing from effective ledger;
   - effective ledger paths absent from the frozen tree;
   - blob-identity disagreements;
   - history-only paths not present in the frozen tree;
   - exact treatment of authorized remediations;
   - recomputed `lens-history-v1/final-only-explicit` observed measured effectiveness and comparison with the frozen manifest;
   - `historical_total_effectiveness` state and evidence.

3. `certification.json`
   - authoritative closure verdict `PASS` or `FAIL`;
   - exact blobs of the inventory and reconciliation;
   - exact parent ancestry;
   - zero-unaccounted-document requirement for PASS;
   - no unsupported DIRECT/DERIVABLE inference;
   - no production/application source modification.

## Reconciliation rules

For each frozen document path:

1. Establish the exact frozen blob from the frozen tree.
2. Find all effective ledger records that claim that checkpoint path.
3. Preserve all history-event provenance; do not collapse multiple history ranks merely because they refer to the same path.
4. Select a canonical document record only from explicit/effective provenance. If multiple records disagree in a way not covered by an authorized remediation, closure FAILS.
5. A frozen document with no effective ledger coverage is an unaccounted document and closure FAILS.
6. An effective ledger document path absent from the frozen tree is recorded as historical-only. It is not automatically an error if its source history proves deletion/rename/non-final-tree status, but it MUST NOT satisfy frozen-document coverage.
7. Source/frozen blob mismatches already preserved in audited history remain explicit historical facts and MUST NOT be silently rewritten to MATCH.

## Aggregate rules

The only cumulative effectiveness aggregate is `lens-history-v1/final-only-explicit`.

Closure MUST:
- recompute eligible structured FINAL contributions from the effective ledger;
- exclude INTERMEDIATE/bootstrap/provisional and structurally non-final records;
- prevent duplicate finding/status attribution across supporting lenses;
- require exact equality with the frozen manifest's `observed_measured_effectiveness`.

`historical_total_effectiveness` MUST remain `NOT_VERIFIED` unless the frozen corpus itself contains complete comparable raw metrics sufficient to derive a historical total under an already-versioned deterministic rule. Corpus completeness by itself does not authorize inventing historical totals.

## PASS criteria

CORPUS_CLOSURE may PASS only if all are true:

- frozen tree enumeration is complete and untruncated;
- all 764 history ranks remain PASS-audited and rank 765 is absent;
- every distinct frozen checkpoint document is accounted for by effective ledger provenance;
- every effective ledger path is classified as frozen-present or explicitly historical-only with evidence;
- no unresolved duplicate-path contradiction exists;
- no unauthorized blob/provenance/classification/effectiveness rewrite exists;
- effective ledger required fields remain schema-valid after authorized remediations;
- recomputed observed measured effectiveness exactly matches the frozen manifest;
- historical uncertainty is preserved without unsupported inference;
- closure commits modify only `review-runs/lens-history-v1/automation/corpus-closure/C01/**`.

Otherwise certification MUST be FAIL and MUST name the exact blocking evidence.

## Publication and race safety

Before each closure write, fresh-fetch branch HEAD and require it equals the expected parent. Create a distinct commit for each new closure artifact. Publish with a non-force ref update only. After publication, re-fetch the file and commit; require the commit parent equals the prior expected HEAD. On any race or unknown ancestry, stop without force-push, amend, rebase, overwrite, rename, move, or deletion.
