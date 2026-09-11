# BUG-KEYWORD-04 — stale History source identity at automatic-keyword write boundary

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Broader-registry source: `review/remediation:TASKS.md`, historical P2 `BUG-KEYWORD-04`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Active implementation wave: P2 `BUG-METADATA-02` / F13 from `4ef990e0...`
- Moving implementation diff inspected or relied on: **NO**

## Verdict

**NOT_CLEAN — broader-registry P2 `BUG-KEYWORD-04` is independently reproduced at exact `4ef990e0...` and is promoted into the current canonical blocker inventory.**

Canonical count delta:
- P0: `0`
- P1: `0`
- P2: `+1`

Resulting canonical count: **P0 2 / P1 2 / P2 26**.

CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

Overall remains `NOT_CLEAN`.

## Concrete current-source sequence

`AutomaticKeywordRuleEngine.recordDiscovery()` and `applyFullSync()` build a point-in-time History index with `buildHistoryIndex()` keyed by normalized video identity.

For an indexed History row representing source A:

1. automatic-keyword sync builds `historyByVideoKey[A] -> History id H`;
2. before that video's assignment transaction reaches its final write, a normal History metadata edit changes H from URL/source A to B;
3. the History edit commits the new URL and calls `reconcileHistoryUrlChange(H, A, B)` in the same Room transaction, removing A-derived RULE assignments and recomputing assignments for B;
4. the already-running A sync resumes;
5. its per-video transaction revalidates the automatic-keyword **rule** snapshot/revision, but `applyRuleToLocalHistory()` reuses the stale index and passes only numeric History id H into `applyRuleToHistory()`;
6. `applyRuleToHistory()` again revalidates only the rule's current existence/enabled/revision state;
7. `HistoryKeywordAssignmentRepository.replaceSourceKeywordsInTransaction()` validates assignment source type/source ID, deletes/reinserts RULE assignment rows for H, and materializes `HistoryItem.keywords`; it does not reload H and require H's current normalized source identity to still equal A.

Result: A-derived RULE keywords can be durably reintroduced onto History H after H authoritatively changed to source B.

## Why current F12 closure does not cover this

P1 `BUG-KEYWORD-01` / F12, closed at `4ef990e0...`, owns **source snapshot authority** for baseline/discovery: PARTIAL/FAILED source membership cannot authorize baseline/discovery mutation.

This P2 root is downstream and orthogonal: even when the source snapshot is fully AUTHORITATIVE and the rule revision is current, a cached History match is not durable write authority after the History source identity changes.

Checklist v6 core invariants 8, 11, 12, and 18 apply: discovery/candidate state is not final mutation authority; semantic identity must be preserved through consumers; a material authority contract is not closed until the final effect boundary is revalidated.

## Root reconciliation

Count once as existing broader-registry `BUG-KEYWORD-04`.

Keep distinct from:
- CLOSED P1 `BUG-KEYWORD-01`: source-list completeness/authority;
- P2 `BUG-KEYWORD-HANDOFF-01` / broader `BUG-KEYWORD-03`: durable queued sync -> WorkManager acceptance/recovery;
- P2 `BUG-KEYWORD-02`: History Undo restoring/recomputing RULE-derived state;
- P2 `BUG-HISTORY-01`: atomic History/playlist Undo snapshot;
- P1 `BUG-METADATA-01`: stale Download metadata full-row writers.

This is specifically **automatic-keyword stale History source-match write authority**.

## Required correction boundary

A coherent correction must:

1. treat `buildHistoryIndex()` output as candidate lookup only, never final mutation authority;
2. carry the expected normalized History source/video identity from the matched source video to the final assignment write;
3. inside the same Room transaction that persists RULE assignments, reload the target History row and require:
   - the row still exists; and
   - its current normalized video/source identity still equals the expected source identity;
4. if the row was deleted or changed A->B, skip the stale A assignment or recompute under B rather than reintroducing A-derived state;
5. preserve rule existence/enabled/revision fencing already present;
6. cover both `applyFullSync()` and `recordDiscovery()` consumers;
7. preserve valid canonical-equivalent URL forms through `AutomaticKeywordNormalizer.videoKey()` semantics;
8. add deterministic production/repository-level races:
   - build A index -> edit H A->B + complete reconciliation -> resume A full sync -> no A RULE assignment/materialized keyword on B;
   - same sequence through `recordDiscovery()`;
   - H deleted after index -> no stale assignment recreation;
   - unchanged A control still applies;
   - equivalent A URL spelling control still applies when normalized source identity remains A.

Do not broaden this repair into F12 source authority, keyword scheduling handoff, or Undo semantics.

## Existing evidence gap

Current persistence coverage tests ordinary URL-change reconciliation, but no deterministic test was found that pauses an already-running automatic-keyword sync after History-index construction, commits A->B reconciliation, and then resumes the stale A write candidate.

## Independent execution

INDEPENDENT EXECUTION: NOT EXECUTED
