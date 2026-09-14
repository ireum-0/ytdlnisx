# F20 completed-wave reconciliation at 0968c7dd

- Reviewed implementation HEAD: `0968c7dda0bb0673ca055156f760e64e4fc6b446`
- Finding: F20 / `BUG-LOCALADD-01`
- Verdict: `NOT_CLEAN`
- Severity: P2
- Count delta: `0`
- CLEAN-basis consequence: no advance; contiguous CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## What is now closed

The completed F20 commit removes the suppressing `HistoryDao.getItemByDownloadPath()` substring-LIKE prechecks from both `LocalAddWorker` and the direct `HistoryFragment` LocalAdd scan. No `getItemByDownloadPath` call remains in either reviewed production consumer.

The production worker still dedupes a batch only through `LocalAddStorageIdentityPolicy.identityForEntry()`. Direct/manual/matched UI paths publish through `HistoryKeywordAssignmentRepository.insertLocalHistory()`, whose final admission remains serialized by `HistoryReferenceMutationCoordinator` plus one Room transaction and re-reads current History rows before exact URL/storage-identity comparison. `HistoryKeywordAssignmentRepository.kt` remains at blob `527a7f3342da8b632c48b44fa93f773091c1d4a5`, preserving the reviewed F17/F18 Undo implementation.

The prior tree-prefix interpretation remains removed: `validatedTreeMetadata()` does not synthesize generic tree-relative identity, and exact provider document identity remains the principal content-document identity.

## Residual: opaque document ID is still normalized with trim()

`LocalAddStorageIdentityPolicy.identityForUri()` currently obtains a provider document ID as:

`DocumentsContract.getDocumentId(uri).getOrNull()?.trim()?.takeIf(String::isNotBlank)`

Android's `DocumentsContract.Document.COLUMN_DOCUMENT_ID` contract states that the ID is provided/interpreted by the `DocumentsProvider` and must be treated as an opaque value by client applications. Provider-local uniqueness applies to the exact opaque ID; client normalization is not authorized by that contract.

Concrete collision:

- provider authority: `provider`
- document 1 ID: `A`
- document 2 ID: ` A `

These are distinct opaque strings a provider may assign. The current policy trims both to `A`, producing the same strong identity `provider:provider:A`.

Concrete impact paths:

1. `LocalAddWorker` calls `identityForEntry()` before processing and uses `seenStorageIdentities`; two distinct batch entries whose exact document IDs differ only by leading/trailing whitespace can collapse and the later file is silently discarded before admission.
2. `HistoryKeywordAssignmentRepository.insertLocalHistory()` calls `hasSameStorageIdentity()` against current History rows. An existing document with one exact ID can make the distinct candidate with the other exact ID return `AlreadyPresent`, silently suppressing insertion.

This violates the F20 invariant that only actual local identity may discard a candidate and that same-name/ambiguous distinct files proceed. It is the same existing `BUG-LOCALADD-01` semantic root, not a new finding/count.

## Required next correction

Treat the provider document ID as exact opaque payload. Do not trim, case-fold, path-normalize, prefix-interpret, or otherwise transform the ID before provider-scoped identity comparison. Any blank/invalid handling must not collapse two distinct provider IDs. Preserve provider authority scoping, exact URI fallback where no document ID is available, file canonical identity, URL duplicate semantics, pending/session recovery, reconnect behavior, cancellation, worker/UI shared policy, and final transactional admission.

Add deterministic production-boundary regression coverage for distinct same-provider document IDs that differ only by leading/trailing whitespace (built/encoded through legal document URIs), plus exact repeated document ID dedupe.

## F17/F18 preservation

`HistoryKeywordAssignmentRepository.kt` exact blob remains `527a7f3342da8b632c48b44fa93f773091c1d4a5`; no production Undo logic changed in this wave. F17/F18 remain CLOSED.

INDEPENDENT EXECUTION: NOT EXECUTED