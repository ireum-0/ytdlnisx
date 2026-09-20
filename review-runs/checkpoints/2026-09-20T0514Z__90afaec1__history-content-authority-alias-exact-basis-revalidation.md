# HISTORY-CONTENT-AUTHORITY-ALIAS-01 — exact CLEAN-basis revalidation

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Active F11 implementation wave remained frozen from inspection; no in-progress F11 implementation commit/diff was inspected or used as evidence.
- Canonical later finding name: `HISTORY-CONTENT-AUTHORITY-ALIAS-01`.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `HISTORY-CONTENT-AUTHORITY-ALIAS-01` is already present at exact CLEAN basis `90afaec1...`.**

- Canonical blocker-count delta: `0`.
- This checkpoint moves no CLEAN basis and creates no new root.
- Overall canonical state remains `NOT_CLEAN`.

## Governing invariant

Checklist v6 identity/equality rules require every destructive key that merges, suppresses, or authorizes deletion to preserve the exact semantic identity of the underlying authority/resource. A coarser key must not widen destructive authority.

For Android content providers, provider authority matching is exact string equality in AOSP `ContentProvider.matchesOurAuthorities`; application-side case folding therefore widens provider identity beyond the platform admission contract.

## Exact current production evidence at 90afaec1

### 1. Content-document identity case-folds provider authority

`HistoryDeletionTargetParser.parseContentUri()` builds document identity as:

`opaqueKey("content-document", "${uri.authority.lowercase(Locale.US)}:$id")`

for document URIs.

Therefore two stored content-document targets with the same document ID but authorities that differ only by case receive the same destructive key.

### 2. The aliased key is not merely display/dedup metadata

`HistoryFileDeletionEngine.validate()` uses `gateway.referenceKeys(target)` and `canonicalKeyByReferenceKey` to collapse targets into one canonical deletion target.

Each owning History record stores that canonical key in `recordTargetKeys`.

Thus case-folded provider authority participates directly in:

- target canonicalization;
- retained-reference exclusion;
- deletion validation/publication;
- final per-record removal eligibility.

### 3. One deletion outcome can authorize multiple exact-distinct History records

`HistoryFileDeletionEngine.execute()` evaluates each canonical target once and then computes:

`removableRecordIds`

from records whose canonical keys all have a deletion outcome that allows record removal.

Concrete sequence:

1. History record A stores a document URI under provider authority `Example.Documents`, document ID `D`.
2. History record B stores a URI under authority `example.documents`, same document ID `D`.
3. Parser case-folds both authorities, producing one canonical key.
4. Validation/deletion runs only for the canonicalized target chosen for that key.
5. If that one outcome is `DELETED` or `ALREADY_ABSENT`, both record IDs can satisfy the same canonical outcome even though their provider namespaces were exact-distinct.

The later HistoryViewModel/ObserveSource paths revalidate record target snapshots and retained references, but those protections operate on the already-widened deletion identity and do not restore the lost authority dimension.

### 4. Existing tests do not close the negative case

Current `HistoryFileDeletionTest` covers:

- content document parsing;
- equivalent tree/single-document URIs under the same authority;
- duplicate target collapse;
- retained reference exclusion;
- raw/content equivalence and trusted association.

No reviewed test proves that two case-distinct provider authorities with the same opaque document ID remain separate destructive identities.

## Correctness impact

This is a destructive identity-widening defect.

A deletion decision for one exact provider namespace can be propagated to a History row associated with a different exact provider namespace when the only difference is authority case. The result can remove a History record without having established deletion/absence for that record's exact content-provider target.

Retained-reference protection can also become over-broad under the same aliased key, but that is a non-destructive manifestation of the same identity root and is not counted separately.

## Root reconciliation

- Keep `HISTORY-CONTENT-AUTHORITY-ALIAS-01` counted once as P2.
- Canonical count delta: `0`.
- Do not merge with F20 / `BUG-LOCALADD-01`: F20 concerns LocalAdd admission/identity; this root concerns destructive History content-target canonicalization.
- Do not merge with History retained-reference TOCTOU roots: those govern revalidation timing/ownership, not loss of the provider-authority identity dimension.
- No new canonical root is created.

## Stable correction boundary

A future correction should:

1. preserve provider authority exactly when constructing content-document destructive identity;
2. preserve the provider-defined opaque document ID exactly;
3. allow tree-document and single-document representations to collapse only when they retain the same exact provider authority and exact document identity;
4. propagate the exact corrected key consistently through retained-reference exclusion, target canonicalization, validation, deletion, and `removableRecordIds`;
5. add a negative regression with two case-distinct authorities and the same document ID, proving neither deletion outcome nor retained-reference ownership crosses provider namespaces;
6. preserve existing raw/content trusted-association behavior only where exact identity equivalence is actually proven.

## Verification

- Source-level production-path review: completed at exact `90afaec1...`.
- Existing regression-source review: completed.
- Independent runtime/JVM execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
