# F10 exact-SHA durability/cache reconciliation

- exact_implementation_sha: `e709c4257b947ec221f0ca0cb1da4941b158b571`
- implementation_parent_sha: `f4d0064944b69c7c3afd55bfe9ee20ef2aabc8a8`
- review_parent_sha: `f3cccb463098f6732d0c90ea52c684057e453cc1`
- frozen_plan_sha: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen_ledger_sha: `899328bc91e4008e39a658387396a0106c8666ec`
- checklist_v6_blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Verdict

`BUG-CLEANUP-01 / F10` remains `OPEN P2 / NOT_CLEAN`.

This checkpoint preserves the final exact-SHA finding at `f3cccb463098f6732d0c90ea52c684057e453cc1` and adds one further same-root cache-recovery subcase. No new independent root is counted here.

## Preserved confirmed residual — rejected SharedPreferences state can be persisted by a later successful critical write

The parent final checkpoint remains correct. `commitCritical()` fences coordinator reads to the last pre-failure snapshot after a `SharedPreferences.Editor.commit()` failure, but the underlying in-process SharedPreferences map can already contain the rejected mutation because Android commits to memory before the disk-write result is known.

A later critical editor is still created against that contaminated SharedPreferences instance. If that later editor successfully commits an unrelated effect-phase/debt transition, Android persists the current map snapshot, so rejected memory-only generation/cadence/debt values from the earlier failed transition can be written to disk as collateral. `commitCritical()` then clears `criticalDurabilityFence` on success and the coordinator accepts that state.

A read fence alone is therefore not a complete durable-write fence. A later successful critical write must be constructed from confirmed-durable authority plus the intended mutation, or critical authority must move to a primitive/protocol that cannot collateral-persist rejected in-memory state.

## Additional same-root residual — exact cache recovery can destroy its own retry authority

The new split cache journal materially fixes the former Room-commit -> cache-suffix loss: the frozen DownloadItem is retained after Room deletion, cache responsibility is journaled separately, and `deleteExactCacheForTarget()` returns failure instead of silently advancing the journal.

However the real filesystem helper can make an initially provable suffix permanently unrecoverable.

Production path:

1. Journal creation records a cache-cleanup-required id for a frozen Download target.
2. Room deletion commits and the worker enters `advanceExactCacheCleanup()`.
3. `DownloadCacheOwnership.deleteIfOwned()` validates the exact ownership marker and reads the operation artifact manifest.
4. It attempts to delete each manifest-listed file, then removes the artifact manifest before proving that the directory is empty.
5. If any file deletion did not complete, or another unproven entry remains, the helper sees remaining children, deletes the ownership marker, and returns `false`.
6. The worker correctly leaves the journal cache-completion id unset and requests recovery.
7. On retry, the frozen DownloadItem still exists in the journal, but the marker/manifest that proved its exact filesystem authority have been destroyed by the previous failed attempt. `deleteIfOwnedOrAlreadyAbsent()` sees that the directory still exists and delegates to `deleteIfOwned()`, which can no longer prove ownership, so it returns `false` again.
8. D1 remains incomplete and D2 remains blocked, but the exact suffix has no recoverable authority carrier left. The failure has converted a retryable exact responsibility into a permanently unprovable one.

This is an additional subcase of the existing F10 exact destructive-responsibility/recovery root, not a new finding.

Related capture issue: `hasCleanupResponsibility()` currently records responsibility when either the numeric directory or marker merely exists. A legacy/mismatched directory with no marker proving the frozen Download operation can therefore be journaled as mandatory cache work even though `deleteIfOwned()` can never authorize it. This can likewise make one D1 permanently incomplete. Journal creation should distinguish provable exact responsibility from unrelated/unproven numeric remnants.

Required correction semantics:

- never destroy the only exact ownership/recovery proof before cache completion is established;
- an exact cache deletion failure must leave a restart-safe carrier that can retry the same suffix if/when the transient failure clears;
- journal creation must not turn an unproven legacy/mismatched numeric directory into mandatory exact D1 work merely because a path exists;
- ownership mismatch caused by a genuinely newer authority must remain fail-closed, but the implementation must distinguish that case from self-induced loss of the old occurrence's retry proof;
- D2 must remain blocked while real exact D1 responsibility is incomplete, without making the recurring chain permanently unrecoverable through loss of its own carrier.

## Source improvements accepted at e709c425

The following prior residuals are materially improved and must be preserved:

- Room deletion and per-item cache suffix are separate journal steps;
- the frozen DownloadItem survives row deletion and is available to cache recovery;
- exact cache deletion false propagates as recovery-required rather than journal success;
- exact row status/operation/execution/start-time is revalidated inside the Room deletion transaction;
- incomplete D1 journal blocks D2 publication;
- `PhaseUnavailable` paths retain replay ownership and pending->active promotion retains incomplete-effect recovery ownership;
- settings UI reads the coordinator-confirmed cadence instead of the raw memory-visible SharedPreferences value;
- UNKNOWN WorkManager discovery, disable/supersession fences, Reset ordering, exact successor derivation and calendar cadence semantics remain expected preserved.

## Other canonical roots observed on the review branch

Since the earlier private `P2: 22` handoff, public review independently confirmed two additional P2 roots at the same frozen implementation lineage:

- `WORKER-FOREGROUND-COMPLETION-01` — NEW/OPEN at checkpoint `25a0f073e7368c399d0da1249953b78a15ec2c13`;
- `BULK-FORMAT-SILENT-PARTIAL-SUCCESS-01` — NEW/OPEN at checkpoint `f9b64265c3cce126547fd948fb42e643665d57e4`.

Therefore the reconciled canonical totals are `P0 2 / P1 0 / P2 24`, with no count delta from this checkpoint. Overall remains `NOT_CLEAN` and CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

The separate `CLEANUP-STALE-DOWNLOAD-ROW-01` root is not declared closed by the incidental row-revalidation strengthening in this F10 wave; it still requires its own exact-root closure decision.

## Execution evidence

Exact implementation SHA has no GitHub combined status contexts and no associated workflow runs. Implementation-side production-wiring instrumentation was reported ADDED but NOT EXECUTED because no device/emulator was available.

INDEPENDENT EXECUTION: NOT EXECUTED
