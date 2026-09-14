# F20 / BUG-LOCALADD-01 — completed-wave reconciliation at f1a159db

## Scope

- implementation branch: `checkpoint/pre-baseline-review`
- previous completed implementation head: `f6e7cf72e00c013ee9b770bf748cba7f38848256`
- completed review head: `f1a159db41f1281a31e1e06df486e4f67cdc3d89`
- F20 implementation commits in this completed range:
  - `d86513a4915a2842ab4842d420b5acd0562cd2a0` — strong local-add storage identity and atomic admission
  - `c85899306fcda53063ba28d5b66963673949356f` — provider-scoped tree identity
  - `aa09271c9cb1196e03f4fe8aaae0d7944d344c41` — provider guard for tree metadata
  - `f1a159db41f1281a31e1e06df486e4f67cdc3d89` — retain validated local-match tree metadata
- governing finding: F20 / `BUG-LOCALADD-01`, P2

## What the implementation correctly changes

The completed implementation removes filename/basename-only exclusion from the LocalAdd worker/UI paths and introduces a shared `LocalAddStorageIdentityPolicy`.

It also improves identity/final-admission semantics materially:

- provider document identity includes provider authority;
- same filename in distinct file paths is not considered identity;
- cross-provider document IDs remain distinct;
- unknown/unproven identity can fail open;
- LocalAdd final publication is routed through `HistoryKeywordAssignmentRepository.insertLocalHistory()`;
- `insertLocalHistory()` holds `HistoryReferenceMutationCoordinator` and one Room transaction while re-reading current History rows, comparing URL/storage identity, and either returning `Inserted` or `AlreadyPresent`;
- this closes the prior stale precheck race where concurrent LocalAdd producers could both observe absence and then serialize duplicate publication.

## Residual — existing F20 root remains OPEN

Tree-relative identity is still synthesized by interpreting provider-defined document IDs as path strings.

Exact final source in both the shared policy and production tree-metadata construction performs the equivalent of:

`documentId.removePrefix("$treeId/").removePrefix(treeId).trimStart('/')`

once the tree URI and file URI merely share provider authority. The resulting string is then used as a durable tree-relative identity and can be preferred over provider-scoped document identity.

Android's `DocumentsContract.Document.COLUMN_DOCUMENT_ID` contract explicitly states that the ID is provided/interpreted by the `DocumentsProvider` and must be treated as an opaque value by client applications. Android separately defines descendant testing as a provider contract (`DocumentsProvider.isChildDocument(parentDocumentId, documentId)`). Therefore string-prefix subtraction is not proof either of descendant relation or of a unique relative path.

Concrete failure sequence:

1. LocalAdd receives two distinct documents from the same provider/tree context whose opaque IDs differ but collapse under the prefix-stripping transformation. For example, with tree ID `A`, document IDs `A/child` and `Achild` are distinct provider IDs but both map to the synthesized relative value `child`.
2. `LocalAddStorageIdentityPolicy.identityForEntry()` can therefore emit the same `tree:<treeUri>|child` identity for the two distinct documents.
3. Worker batch dedupe can drop the second candidate before extraction/admission; alternatively the stored `localTreeUri/localTreePath` precheck or final `insertLocalHistory()` strong-identity comparison can classify the distinct second file as already present.
4. A distinct local file is silently omitted.

The same-authority check added by later F20 commits prevents cross-provider collisions but does not establish tree membership or make document-ID prefix parsing valid.

This is not a new blocker. It is the same F20 semantic root: insufficiently proven local storage identity can silently discard a distinct local file.

## Required correction boundary

- Treat provider document IDs as opaque.
- Do not derive tree-relative identity by string prefix removal from document IDs.
- Use tree-relative identity only when tree membership/path semantics are proven by a provider/API-backed contract.
- When tree-relative identity is unavailable or unprovable, fall back to provider-scoped exact document identity (or another exact normalized full-URI identity where applicable) rather than suppressing the candidate.
- Keep one shared identity policy across worker, pending/UI, persisted metadata, and final admission; remove/route duplicated ad-hoc tree-prefix logic through that policy.
- Preserve the transactional final-admission primitive under `HistoryReferenceMutationCoordinator` + Room transaction.

Regression coverage should include same-provider opaque IDs that would collide under prefix stripping, same-provider non-descendant/tree-metadata mismatch, exact provider-document duplicate, cross-provider same document ID, unknown identity fail-open, and concurrent exact duplicate admission.

## Disposition

- F20 / `BUG-LOCALADD-01`: **OPEN / NOT_CLEAN**
- severity/root count: existing P2, count delta `0`
- canonical blocker total remains **P0 2 / P1 0 / P2 20**
- contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`
- exact completed implementation head `f1a159db...` remains overall `NOT_CLEAN` because F10 and F20 remain open.

Completed-wave target dispositions are now:

- F10 / `BUG-CLEANUP-01`: OPEN, existing P2
- F18 / `BUG-KEYWORD-02`: CLOSED_AT_F1A159DB
- F20 / `BUG-LOCALADD-01`: OPEN, existing P2

INDEPENDENT EXECUTION: NOT EXECUTED
