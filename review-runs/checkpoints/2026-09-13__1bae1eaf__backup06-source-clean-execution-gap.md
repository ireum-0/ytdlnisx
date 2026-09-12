# BUG-BACKUP-06 — final-wave independent review

Date: 2026-09-13

## Exact review state

- Previous independently CLEAN contiguous basis: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- F6 implementation commit: `d75dec962bb57fed2828a6b76a30690150ed40d4`.
- Final completed wave HEAD independently verified at remote: `1bae1eafea08942f11e6df30e4a13a515dda621c`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F6 / `BUG-BACKUP-06`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**SOURCE_CLEAN / EXECUTION_EVIDENCE_INCOMPLETE — existing P2 `BUG-BACKUP-06` remains OPEN.**

Canonical blocker-count delta: `0`.

Canonical count remains **P0 2 / P1 1 / P2 29** at this boundary.

The contiguous CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.

## Source-semantic closure

The exact final restore source implements the governing invariant that a backup-local DB numeric identity is not destination authority merely because an equal destination number exists.

Independent source review confirmed:

1. imported History gets fresh destination identity and an explicit old-History-ID -> new-History-ID map;
2. restored Download durable identity fields are reset rather than retaining backup-local IDs;
3. History-redownload markers are remapped through the imported History map and do not retain an unmapped raw old ID;
4. imported Observe Sources are allocated/resolved to destination rows and recorded in an explicit old-source-ID -> destination-source-ID map;
5. embedded Observe download templates are rebound to the destination source ID;
6. restored Downloads use only the explicit Observe Source map and otherwise clear the nonportable source reference rather than falling back to the old numeric ID;
7. membership-waiting work whose required Observe Source cannot be mapped fails closed instead of binding a numerically colliding destination source;
8. keyword-group members require an explicit old-group-ID -> destination-group-ID map;
9. Youtuber group members and parent/child relations require explicit destination mappings for their referenced endpoints;
10. the numeric `history_visible_child_youtuber_groups` preference payload is remapped through the destination Youtuber-group map and unmapped old IDs are dropped rather than retained by numeric equality;
11. automatic-keyword rules, rule-keyword/video-match relations, and History assignments are rebuilt through explicit rule/History maps rather than raw ID fallback;
12. identity-bearing SharedPreferences such as `history_visible_child_youtuber_groups` and `player_playback_position_*` are excluded from the generic portable settings path so primitive-type restoration cannot re-authorize backup-local IDs.

No raw numeric fallback reproducing the established F6 root was found in the reviewed final restore composition.

## Execution evidence

The implementation report provides exact-final-wave external evidence including `BackupRestoreIdentityProductionWiringTest` 4/4 PASS plus cumulative JVM/build verification.

Those tests directly execute:

- a destination Observe Source whose numeric ID collides with the backup-local source ID, proving the missing source does not bind by raw equality;
- a mapped imported Observe Source, its embedded template, and a restored Download, proving explicit remap ownership;
- keyword-group member admission with a colliding mapped group and an unmapped member reference;
- Youtuber group/member/relation admission with explicit mappings and an unmapped endpoint.

However the final execution inventory does not directly execute the numeric relationship-preference remap path for `history_visible_child_youtuber_groups` under a destination-ID collision/unmapped-ID case, and it does not directly execute the automatic-keyword rule/assignment old-to-new mapping path. Both are source-reviewed F6 identity consumers governed by the same no-raw-ID invariant.

Because the governing F6 test boundary requires the portable/transient preference policy and all material DB-local identity consumers to be covered, these unexecuted identity-consumer paths keep the actual execution gate incomplete under Review Checklist v6.

## Next required evidence

Add deterministic production-path tests for at least:

- `history_visible_child_youtuber_groups` containing a backup-local ID that collides with an unrelated live destination group plus a genuinely mapped imported group; prove only the explicit mapped destination identity survives;
- automatic-keyword rule keywords/video matches and History assignments where backup-local rule/History IDs collide with unrelated live destination IDs; prove every surviving relation follows explicit old-to-new maps and unmapped relations are rejected/dropped according to the established contract.

Production changes are not requested unless those tests reveal a semantic residual.

INDEPENDENT EXECUTION: NOT EXECUTED