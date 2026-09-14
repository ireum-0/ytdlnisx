# F20 review-fix reconciliation — 2026-09-14 — `09058d57`

## Authority

- Implementation branch: `checkpoint/pre-baseline-review`
- Review base: `f1a159db41f1281a31e1e06df486e4f67cdc3d89`
- F20 review-fix checkpoint / completed wave head: `09058d574d19d211cd0db36cbf47726bb8fa7dc5`
- Governing checklist: Review Checklist v6
- Prior F20 checkpoint: `5be6219ebf4444cd30720f837661dfd0cebff3b0`

## Verdict

**F20 / `BUG-LOCALADD-01`: OPEN / NOT_CLEAN**

The previous opaque document-ID/tree-prefix collision subcase is closed, but full production re-review finds another coarse LocalAdd precheck that belongs to the same semantic root and can still silently discard a distinct local file before the strong final admission is reached.

## Opaque document-ID residual — closed

`LocalAddStorageIdentityPolicy` no longer derives relative paths by stripping a tree document-ID prefix. Provider document identity is now `provider authority + exact opaque document ID`. Generic tree metadata is not synthesized; `validatedTreeMetadata()` returns no generic tree identity. Worker/UI tree metadata builders use that shared policy, and old local-tree prechecks were removed.

The policy ignores legacy `localTreeUri/localTreePath` when deciding current LocalAdd storage equality and instead derives identities from exact stored `downloadPath` values. Exact provider-document duplicates remain equal; cross-provider identical document IDs remain distinct; unknown identities fail open. Final `insertLocalHistory()` remains serialized under `HistoryReferenceMutationCoordinator` and one Room transaction.

Disposition of the prior opaque-ID prefix-collision subcase: **CLOSED at `09058d57...`**.

## Remaining same-root P2 — substring `downloadPath` precheck

The production LocalAdd paths still perform an earlier suppressing lookup that bypasses the shared strong identity policy:

- `LocalAddWorker` calls `db.historyDao.getItemByDownloadPath(escapeLikeQuery(uriString))` and immediately `return@forEach` on any match.
- `HistoryFragment` LocalAdd scanning calls the same DAO lookup and immediately counts/skips the candidate on any match.

`HistoryDao.getItemByDownloadPath()` is:

`SELECT * FROM history WHERE downloadPath LIKE '%' || :path || '%' ESCAPE '\\' LIMIT 1`

`downloadPath` is persisted by the Room converter as Gson JSON for `List<String>`. `escapeLikeQuery()` only escapes SQL LIKE wildcard characters (`\\`, `%`, `_`); it does not establish a JSON-element boundary or exact storage identity.

Concrete collision:

- existing stored path: `content://provider/document/Achild`
- distinct candidate: `content://provider/document/A`

The persisted JSON string for the existing row contains the candidate URI as a substring, so the `%candidate%` DAO query can return the existing row. Both worker and UI then silently skip the distinct candidate before `LocalAddStorageIdentityPolicy` / transactional `insertLocalHistory()` can make the exact provider-document comparison.

The same problem applies to raw/file/content paths whenever one valid stored target text contains another candidate target text. Escaping LIKE metacharacters prevents wildcard injection but does not convert substring equality into exact identity.

This precheck already existed at `f1a159db...`; it is not introduced by the opaque-ID review-fix commit. It is a previously unclosed production consumer/subcase of the existing F20 root: coarse local identity can silently discard a distinct file. Do not count it as a second F20 blocker.

## Required correction

All LocalAdd suppressing prechecks must use the same strong storage identity contract as final admission. Remove the substring `LIKE` precheck from LocalAdd paths or replace it with an exact shared-policy precheck. A fast precheck may be stale and therefore remains advisory; final `insertLocalHistory()` must continue to re-read and revalidate exact storage identity transactionally.

Required production regression should exercise the actual worker/UI precheck boundary, not only repository insertion. Include distinct valid URIs where one textual URI is a prefix/substring of the other, and prove both candidates survive to exact strong admission. Preserve exact duplicate suppression, provider scoping, unknown fail-open, pending-session behavior, reconnect behavior, cancellation, and concurrent final admission.

## F17/F18 preservation

`HistoryKeywordAssignmentRepository.kt` at `09058d57...` has the same blob SHA as at `f1a159db...` (`527a7f3342da8b632c48b44fa93f773091c1d4a5`). F20 changes do not alter the F17 atomic `HistoryUndoSnapshot` relationship transaction or the F18 current-rule recomputation closure. `HistoryFragment` changes in this commit are confined to LocalAdd tree/precheck code; the production Undo consumer is not changed.

F17 and F18 closures remain preserved.

## Count / basis

- Canonical blockers entering this F20 review after F10 reconciliation: P0 2 / P1 0 / P2 20.
- F20 remains one existing P2 root; opaque-ID subcase closes but the substring-precheck production subcase keeps the root OPEN.
- Net F20 count delta: **0**.
- Resulting canonical blockers: **P0 2 / P1 0 / P2 20**.
- Overall completed wave remains `NOT_CLEAN`.
- Contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

Implementation-agent compile/build claims are evidence only. Runtime instrumentation was reported not executed because no device was available.

INDEPENDENT EXECUTION: NOT EXECUTED
