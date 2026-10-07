# Checkpoint-only production reconciliation — History content authority identity

checkpoint_kind: CHECKPOINT_ONLY_PRODUCTION_FINDING_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: 957061e073b202bdc4114ed7ca1b43de2092a4d2
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## HISTORY-CONTENT-AUTHORITY-ALIAS-01 — VERIFIED_OPEN P2

This named historical checkpoint-only production root remains distinct and current.

Exact eda6a758 HistoryFileDeletion target parsing still constructs document destructive identity as:

opaqueKey("content-document", "${uri.authority.lowercase(Locale.US)}:$documentId")

The provider-defined documentId is preserved, but provider authority is case-folded before the destructive
key is created.

History file deletion uses the resulting key for canonical target collapse, retained-reference
classification, deletion outcome publication, and per-record removal eligibility.

Therefore two exact content-provider namespaces that differ only by authority case can collapse onto one
destructive key when they expose the same documentId. One provider's deletion/absence outcome can then
authorize record removal or retained-reference behavior for the other exact provider namespace.

Disposition:
HISTORY-CONTENT-AUTHORITY-ALIAS-01 = VERIFIED_OPEN P2.

Historical reconciliation remains valid:
- distinct from BUG-LOCALADD-01, which governs LocalAdd admission/storage identity;
- distinct from History retained-reference TOCTOU roots, which govern timing/revalidation rather than
  loss of an identity dimension.

Correction boundary:
1. preserve content-provider authority exactly in destructive identity;
2. preserve opaque documentId exactly;
3. collapse tree/single-document forms only when exact provider authority and exact provider document
   identity both match;
4. propagate the corrected key consistently through retained-reference exclusion, deletion validation,
   execution and removableRecordIds;
5. add negative regression with case-distinct authorities sharing one documentId.

Project inventory effect:
- add one distinct checkpoint-only production root;
- current state: OPEN P2;
- current download canonical inventory unchanged.

No production source, implementation prompt, active implementation scope, Master Plan or ledger changed.
