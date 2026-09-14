# YTDLnisX independent review — F20 completed-wave reconciliation

- Exact completed implementation HEAD reviewed: `558d692dc95557080abe32eeedb51574b982aa01`
- Review base for this review-fix wave: `0968c7dda0bb0673ca055156f760e64e4fc6b446`
- F20 implementation checkpoints:
  - `c16b130037156c26f6d3d00b382f50292e550208`
  - `558d692dc95557080abe32eeedb51574b982aa01`
- Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Checklist v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Finding: F20 / `BUG-LOCALADD-01`
- Severity: P2
- Verdict: `NOT_CLEAN`
- Relation: same existing F20 semantic identity root; no new blocker count
- Count delta: `0`

## Closed subcase from the latest review-fix

The prior `0968c7dd...` document-ID whitespace residual is closed.

`LocalAddStorageIdentityPolicy.identityForUri()` now uses the exact non-null value returned by `DocumentsContract.getDocumentId(uri)` without trimming, case-folding, path parsing, prefix stripping, or blank/whitespace reinterpretation. The additive follow-up correctly permits a provider-returned whitespace-only document ID instead of applying client-side `isNotBlank` semantics.

This matches the Android DocumentsProvider contract: `DocumentsContract.Document.COLUMN_DOCUMENT_ID` is provider-provided/interpreted, unique within a provider, durable, and must be treated as an opaque value by client applications.

The new repository and worker tests construct document URIs with `DocumentsContract.buildDocumentUri()` and cover exact/leading/trailing/whitespace-only payloads. They are useful evidence, though instrumentation was not independently executed here.

Previously accepted F20 behavior remains source-level preserved:

- filename/basename is not storage identity;
- substring-LIKE `downloadPath` suppression is absent from LocalAdd worker/UI paths;
- generic tree document-ID prefix parsing remains removed;
- `validatedTreeMetadata()` does not invent generic tree-relative paths;
- unknown/unprovable identity fails open;
- final `insertLocalHistory()` still re-reads current History rows while holding `HistoryReferenceMutationCoordinator` and one Room transaction;
- exact URL duplicate semantics remain separate from storage identity;
- worker batch and HistoryFragment expansion both consume the shared identity policy;
- pending/session and cancellation paths remain present;
- reconnect remains an explicit user-selected mutation path rather than a basename-only silent duplicate decision.

## Residual — provider scope is still normalized even though Android provider authority matching is exact

The same shared policy currently computes provider scope as:

`uri.authority?.trim()?.lowercase(Locale.ROOT)`

and `sameAuthority()` applies the same normalization.

That is not an exact provider identity boundary.

Android `ContentResolver` extracts `uri.getAuthority()` and passes that authority to provider acquisition. AOSP `ContentProvider.matchesOurAuthorities()` compares configured authority strings using `String.equals()`, and the provider map is keyed by the authority string. There is no LocalAdd contract that authorizes case-folding or trimming this provider namespace.

Concrete collision:

- provider authority 1: `Provider.Example`
- provider authority 2: `provider.example`
- exact document ID in both providers: `A`

Android provider lookup treats those authority strings as distinct exact names when separately registered, but the current LocalAdd identity maps both to:

`provider:provider.example:A`

Concrete incorrect-impact paths:

1. `LocalAddWorker` computes `identityForEntry()` before processing and stores the result in `seenStorageIdentities`. Two entries from those distinct providers can collapse and the later file is silently discarded before metadata/match/admission.

2. `HistoryKeywordAssignmentRepository.insertLocalHistory()` calls `hasSameStorageIdentity()` against all current History rows. An existing file from one exact provider authority can make a candidate from the other exact authority return `AlreadyPresent` and suppress insertion.

3. `HistoryFragment` folder/direct expansion uses the same shared identity policy for its in-memory dedupe, so the same collision can occur there.

This is not a new semantic root. It is another provider-scoping subcase of existing `BUG-LOCALADD-01`, whose invariant requires actual provider-scoped document identity and forbids silently discarding distinct local files.

## Required correction

Provider scope used for strong LocalAdd identity must preserve the exact Android provider authority namespace used to resolve the provider.

Do not lowercase or trim provider authority as part of storage identity unless an exact Android contract proves those transformed strings resolve to the same provider. Preserve the exact authority string for document-provider identity.

At minimum add deterministic regressions proving:

- exact same document ID under authority `Provider.Example` and `provider.example` remains DISTINCT;
- worker batch does not collapse those two entries;
- final `insertLocalHistory()` inserts both;
- exact same authority + exact same document ID still dedupes;
- document-ID whitespace/prefix cases remain correct;
- unknown identity still fails open;
- no tree-prefix inference returns.

Also review whether any full-URI/bare-path preprocessing used by the same policy performs identity-changing normalization before claiming exact equality; do not broaden this fix into unrelated URL/source normalization.

## F17/F18 preservation

`HistoryKeywordAssignmentRepository.kt` exact production blob remains `527a7f3342da8b632c48b44fa93f773091c1d4a5`, unchanged from the previously reviewed F17/F18-preserving state. Final LocalAdd admission remains in that repository without modifying Undo semantics.

F17/F18 remain CLOSED.

## Canonical blocker consequence

Before this review: `P0 2 / P1 0 / P2 20`.

F20 delta: `0`.

After F20 reconciliation: `P0 2 / P1 0 / P2 20`.

Overall remains `NOT_CLEAN`.

Contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

INDEPENDENT EXECUTION: NOT EXECUTED
