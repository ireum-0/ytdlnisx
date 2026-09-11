# BUG-KEYWORD-01 — authoritative automatic-keyword baseline/discovery revalidation

Date: 2026-09-11

## Exact review basis

- Independently CLEAN Review Basis: `9c5191c3539734fa1c9f1b63501def89f47b216a`
- `BUG-OBSERVE-01` / F3 closure checkpoint: `edff0aa8887c15aa9234c8a63c3270065fa4f46d`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F12 `BUG-KEYWORD-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

F12 became dependency-eligible only after F3 was independently CLEAN. This review therefore re-reads the exact current F12 consumers at `9c5191c3...` rather than assuming the old list-only defect still exists.

## Governing F12 contract

Historical root:

A keyword baseline/discovery fed only by a plain item list can mistake an incomplete empty extraction for complete source membership. A later non-empty discovery can then classify pre-existing playlist members as newly eligible even when apply-to-existing was not requested.

Required invariant:

- baseline completion proves source authority independently of item count;
- only an authoritative snapshot, including authoritative empty, may complete baseline/scheduled discovery;
- rule revision checks, apply-existing behavior, prior-match semantics, and bounded retry must be preserved.

## Verdict

**NOT_CLEAN / existing P1 `BUG-KEYWORD-01` remains OPEN — source semantics are corrected by the F3 typed authority work, but required production worker/durable-state closure evidence is missing.**

No new semantic root is counted.

- Count delta: **0**
- Canonical count remains **P0 2 / P1 3 / P2 25**
- CLEAN basis remains `9c5191c3539734fa1c9f1b63501def89f47b216a`

## Exact-source semantic review

### 1. Manual/baseline/apply-existing worker now consumes typed F3 authority

`AutomaticKeywordRuleSyncWorker` calls `ResultRepository.getSourceSnapshotFromSource(...)` rather than receiving an untyped `List<ResultItem>`.

Its authority branches are conservative:

- `FAILED` throws into the existing failure/retry classification path;
- `PARTIAL` records `AutomaticKeywordSyncStatus.PARTIAL` / `EXTRACTION` and returns a bounded retry while attempts remain; on final attempt it returns success only as WorkManager terminal handling, without invoking the keyword engine;
- only `AUTHORITATIVE` proceeds to baseline/full-sync/discovery semantics.

Therefore neither PARTIAL nor FAILED can call:

- `AutomaticKeywordRuleEngine.applyFullSync(...)`;
- `AutomaticKeywordRuleEngine.recordBaseline(...)`;
- `AutomaticKeywordRuleEngine.recordDiscovery(...)`.

This removes the original plain-list incomplete-empty authority bug at source level.

### 2. Rule revision authority remains revalidated before mutation

After an AUTHORITATIVE snapshot is obtained, the worker re-reads the rule and requires:

- row still exists;
- rule still enabled;
- current revision equals the starting revision.

Only then does it select:

- `pendingApplyToExisting -> applyFullSync`;
- `!baselineComplete -> recordBaseline`;
- otherwise `recordDiscovery`.

The engine itself also rechecks the relevant rule snapshot/revision inside its transactional item mutations. This preserves the historical F12 requirement that source authority must not weaken rule revision authority.

### 3. Authoritative empty remains semantically valid

`recordBaseline()` completes a baseline when its input is empty because there are zero item failures. That behavior was the historical bug only when the caller could not distinguish trustworthy empty from incomplete empty.

At `9c5191c3...`, the SyncWorker reaches `recordBaseline(emptyList())` only after receiving an `AUTHORITATIVE` snapshot. Therefore authoritative empty legitimately completes a baseline, while incomplete/partial empty cannot reach that engine call.

### 4. Managed Observe discovery is also authority-gated

`ObserveSourceWorker` snapshots enabled automatic-keyword rules before extraction, but it calls `AutomaticKeywordRuleEngine.recordDiscovery(...)` only when `sourceIsAuthoritative` is true.

For a `FAILED` source it records failure status. For a non-authoritative source it records PARTIAL status instead of passing returned membership into discovery/baseline state.

Thus the other production F12 consumer no longer turns PARTIAL membership into scheduled discovery authority.

### 5. yt-dlp-only sources remain conservative, not falsely complete

F3 closure intentionally leaves current yt-dlp source-list extraction `PARTIAL` because the production request still uses `--ignore-errors` and tolerant child parsing. Consequently a keyword baseline whose only available source producer is this conservative yt-dlp path may remain retry/partial rather than falsely complete.

That is not a justification to upgrade it to authoritative. F12 correctness requires proven membership completeness before baseline/discovery authority.

## Remaining closure gap: actual production authority-effect execution

The repository has substantial `AutomaticKeywordRuleEngine` instrumentation coverage, including:

- baseline exclusion followed by future discovery;
- repeated discovery preserving baseline exclusions;
- apply-existing/full-sync behavior;
- rule revision and assignment persistence cases.

However, those tests invoke the engine directly. They do not execute the production semantic boundary that was changed by F3:

`SourceSnapshot authority -> AutomaticKeywordRuleSyncWorker / ObserveSourceWorker -> Room rule/baseline/video-match/assignment state`.

No exact-current test was found that proves through the real keyword worker/durable state that:

1. a PARTIAL empty snapshot cannot set `baselineComplete`;
2. PARTIAL cannot consume `pendingApplyToExisting` or apply rule keywords;
3. FAILED cannot complete baseline/discovery;
4. AUTHORITATIVE empty can legitimately complete baseline;
5. an initial PARTIAL empty followed by an AUTHORITATIVE non-empty complete snapshot establishes those existing members as the baseline rather than treating them as newly eligible;
6. only a subsequent genuinely new authoritative discovery becomes eligible/applies keywords;
7. the same authority rule holds on managed Observe discovery;
8. a stale rule revision cannot commit after the authoritative fetch;
9. bounded retry/manual-sync status behavior remains coherent without being mistaken for baseline completion.

The v6 checklist requires material semantic-contract changes to close through the actual consumer/authority-effect graph and states that helper/engine-level correctness alone is insufficient. F3 changed the meaning of source extraction for this direct consumer, so source inspection alone is not enough for independent F12 CLEAN.

## Root reconciliation

- This remains the existing canonical P1 `BUG-KEYWORD-01`, counted once.
- It is distinct from P2 `BUG-KEYWORD-HANDOFF-01`, which owns durable DB-intent -> WorkManager acceptance/recovery ownership. This F12 review does not broaden into scheduler handoff.
- It is distinct from P2 `BUG-KEYWORD-02`, which depends on corrected History undo atomicity/revision semantics.
- No new F12 source-level semantic residual was established.

## Minimal correction / verification boundary

Do not redesign the F3 authority model.

The next review-fix should primarily add the smallest deterministic production-boundary coverage needed to execute the real keyword worker/Room effect graph with injected typed snapshots. If a concrete semantic defect is exposed by that production test, fix it within F12; otherwise keep production changes limited to narrow test seams.

Required coverage should prove at least:

- PARTIAL empty baseline attempt leaves `baselineComplete=false` and existing eligibility state unchanged;
- PARTIAL apply-existing attempt leaves `pendingApplyToExisting` and assignments unconsumed/unchanged;
- FAILED leaves baseline/discovery state unchanged and follows bounded failure/retry semantics;
- AUTHORITATIVE empty may complete baseline;
- PARTIAL empty -> AUTHORITATIVE full initial membership -> later AUTHORITATIVE new member preserves baseline exclusion and assigns only the later new member;
- managed Observe discovery does not call/commit keyword discovery from PARTIAL but does from AUTHORITATIVE;
- stale rule revision after fetch cannot commit old-rule baseline/discovery state;
- preserve current manual-sync status/retry semantics.

Production tests must drive the actual worker/Room composition rather than only `AutomaticKeywordRuleEngine` or a new pure helper.

## Independent execution

No independent JVM/Gradle/instrumentation execution was performed in this review.

INDEPENDENT EXECUTION: NOT EXECUTED
