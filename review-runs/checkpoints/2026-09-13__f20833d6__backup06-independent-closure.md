# F6 / BUG-BACKUP-06 — independent closure

## Reviewed state
- Exact implementation HEAD: `f20833d6d74134ec52a33c6cdb4a862784d9c4bf`
- F6 production semantics were independently source-reviewed as clean at exact `1bae1eafea08942f11e6df30e4a13a515dda621c`.
- The verification-hardening range `1bae1eaf... -> f20833d6...` changes only F6 instrumentation for this root; no F6 production restore semantics changed.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict
`BUG-BACKUP-06`: **CLEAN / CLOSED** at exact `f20833d6...`.

## Source-semantic closure

The reviewed restore composition uses explicit backup-old-ID -> destination-new-ID maps rather than raw numeric equality for the material imported identity graph, including:
- History and History-redownload provenance;
- Observe Sources and embedded templates;
- restored `Download.observeSourceId`;
- keyword groups/members;
- Youtuber groups/members/relations;
- numeric visible-child Youtuber-group preference values;
- automatic-keyword rules, rule keywords, rule video matches, and History keyword assignments.

Missing mappings fail closed, are cleared, or are dropped according to the established field contract. Generic settings backup continues excluding nonportable identity-bearing preference state under the F6 policy.

## Required execution evidence now satisfied

The exact-final `BackupRestoreIdentityProductionWiringTest` adds the two previously missing material collision cases and reports 6/6 PASS on API 36 x86_64 emulator.

1. **Visible-child Youtuber-group preference collision/remap**
   - destination contains an unrelated group whose live numeric ID equals a backup-local group ID;
   - imported group receives a distinct destination ID;
   - the restored visible-child preference contains only the explicitly mapped destination ID;
   - an unmapped backup-local numeric ID is omitted;
   - raw numeric equality does not authorize the colliding destination group.

2. **Automatic-keyword relation/assignment collision/remap**
   - destination contains unrelated rule and History rows whose numeric IDs collide with backup-local IDs;
   - imported rule and History receive distinct destination IDs;
   - rule keywords and video matches bind to the mapped imported rule;
   - History keyword assignment binds to the mapped imported History and mapped imported rule;
   - relations with unmapped rule or History endpoints are absent;
   - the colliding pre-existing History does not receive the imported assignment.

Existing exact-final F6 tests continue covering missing/colliding Observe Source identity, embedded template rebinding, keyword-group mapping, and Youtuber relation endpoint mapping. F7's portable-preference instrumentation also preserves exclusion of the F6-defined nonportable keys.

## Regression / dependency assessment
- F7 / `BUG-BACKUP-08` remains independently CLOSED.
- F5 / `BUG-BACKUP-02` is independently CLOSED in the immediately preceding checkpoint.
- F4 remains independently OPEN for a distinct same-second backup staging artifact-identity residual.
- No new F6 blocker was found in the final verification-only range.

## Canonical consequence
- P2 count: `27 -> 26`.
- Resulting canonical blockers: `P0 2 / P1 1 / P2 26`.
- F6 hard-prerequisite side is now satisfied for F8/F9, but both remain blocked while F4 is open.
- Contiguous independently CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4` because the cumulative backup wave still contains open F4.

INDEPENDENT EXECUTION: NOT EXECUTED