# F10 frozen-start scope addendum — partial-effect retry/re-entry

## Authority

- Frozen implementation SHA reviewed: `a5160ab51dbe3c6f8d5f87853c4e4037469b9684`.
- Active implementation start SHA: `a5160ab51dbe3c6f8d5f87853c4e4037469b9684`.
- Implementation agent is active; **no post-start implementation commit/diff was inspected or relied upon**.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: Review Checklist v6 (`4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`).
- Prior canonical F10 occurrence-phase reconciliation: `ec1a441ccb2f02d7e0b987efec4775e5ed23487c`.
- Pre-start checkpoint reconciliation: `8151c278e6a6f1c28f47e52db14e6ebbe1faaa55`.
- Later review-only head before this addendum: `d8c9e3d94ecaaa6121f9912cab4240375e8b5680`.

## Verdict

**F10 / `BUG-CLEANUP-01`: OPEN P2 / NOT_CLEAN.**

This review confirms an additional blocker-relevant subcase of the already-open exact-occurrence destructive-effect re-entry root. Count delta: `0`.

## Confirmed same-root subcase — partial destructive effect can widen its target set on retry

The previously recorded residual proved that a fully successful D1 destructive effect can re-enter before durable WorkManager terminalization because there is no durable exact-occurrence effect-consumed phase.

A narrower implementation that adds only one final `effect completed` bit after the whole cleanup body is insufficient. The cleanup body itself contains multiple independently committed / fallible boundaries.

Frozen production path:

1. `CleanUpLeftoverDownloads.doWork()` enters the generation-owned destructive-effect gate.
2. It calls `downloadRepo.deleteCancelled()`.
3. `deleteCancelled()` snapshots the **current** Cancelled rows and calls `deleteKnownUserRemoval(items)`.
4. `deleteKnownUserRemoval` performs its Room destructive transition transactionally for those captured ids: linked-child terminalization, History-replacement-barrier deletion, and Download-row deletion; after commit it performs cache cleanup.
5. The worker then calls `LowQualityRedownloadLedger.refresh(context, operationIds)`.
6. `LowQualityRedownloadLedger.refresh()` is fallible. Frozen source has an explicit `refreshFailureForTesting` seam that throws before notification/progress refresh, and ordinary repository/notification operations are also throwable.
7. If this post-delete refresh throws, `CleanUpLeftoverDownloads` catches it as a cleanup failure and returns `Result.retry()` while retry budget remains.
8. Between attempts, a different Download B can newly enter `Cancelled`.
9. The same D1 retry calls `getCancelledDownloads()` again, now sees B, and can delete B.
10. Therefore a partially completed D1 can broaden its destructive target set across retry. B is deleted by an already-started calendar occurrence even though B was not in the target set observed for that occurrence's first pass.

The same structural problem applies between the Cancelled and Error phases, after either Room deletion and before later subeffects, and under process death at the same carrier boundaries.

Concrete impact: same-occurrence retry/restart can consume downloads that became eligible only after that occurrence had already committed an earlier destructive step, instead of leaving them for the next calendar occurrence.

## Required correction boundary

The current active F10 repair must cover both:

1. **fully-completed-effect re-entry** before worker terminalization; and
2. **partial-effect retry/restart** after one or more destructive subeffects have committed.

A single final completion marker written only after all cleanup calls finish does not close (2).

A marker written before the destructive body is also insufficient because a crash/failure after that marker but before all intended mutations would create false completion and silently skip unfinished destructive work.

The correction needs a durable/restart-reconstructible exact-occurrence protocol with one of the following equivalent properties:

- an exact per-occurrence target snapshot plus durable progress/journal through that snapshot; or
- another granular phase/claim protocol that proves which concrete targets/subeffects belonged to D1 and which have already committed.

Required semantics:

- retry/restart of D1 must not discover and consume newly Cancelled/Error rows outside D1's frozen/described target authority;
- already committed D1 subeffects must not be repeated merely because a later refresh/notification/cache/successor step failed;
- unfinished D1 subeffects must remain recoverable rather than being skipped by a premature completion marker;
- process death between any two durable/destructive boundaries must reconstruct the same D1 responsibility;
- cache/filesystem cleanup that follows Room deletion must have a defensible carrier/recovery contract rather than relying on the now-deleted Download row being rediscoverable;
- exact D1/D2 successor ownership and no-duplicate-successor semantics remain preserved;
- genuine pre-commit destructive failure may still retry, but retry must preserve D1's exact target/progress semantics.

## Additional implementation constraint — pending-to-active race

The existing scheduler commits an exact **pending** occurrence before WorkManager enqueue acceptance, and promotion to the durable **active** occurrence happens asynchronously after acceptance.

A new effect-phase protocol must therefore not assume that every worker which begins execution has already observed durable active-slot promotion. It must either:

- atomically establish/validate exact occurrence effect authority from a matching pending occurrence at destructive admission; or
- otherwise prove that WorkManager execution cannot overtake the active promotion.

This is a design/test constraint of the same F10 root, not a separately counted blocker.

Focused production-wiring coverage should include a deterministic worker-start / pending-to-active promotion race and prove that the exact occurrence neither loses cleanup authority nor obtains duplicate destructive authority.

## Existing source closures to preserve

This addendum does not reopen the source-level fixes already established for:

- exact pending/active D1/D2 scheduling ownership;
- failed promotion and failed successor publication recovery;
- missing-generation bootstrap replay ownership;
- Download Settings Reset coordinator ownership/order;
- narrow successor-handoff-only retry removal;
- UNKNOWN scheduler discovery handling;
- disable/supersession fencing;
- calendar DAILY/WEEKLY/MONTHLY and monthly-anchor semantics.

## Later review-only checkpoint precedence

Review-only checkpoint `d8c9e3d94ecaaa6121f9912cab4240375e8b5680` labels `BUG-CLEANUP-01` as `SOURCE-SEMANTIC FIXED / execution NOT_VERIFIED`, but also reports no material canonical status change and does not inspect or rebut either the already-canonical occurrence-phase residual or the partial-effect target-widening chain above. It therefore remains evidence for its inspected L6/cross-feature lens and does not close F10.

## Count / basis

- Existing F10 P2 root remains OPEN; count delta `0`.
- Canonical blockers remain **P0 2 / P1 0 / P2 21**.
- Overall: `NOT_CLEAN`.
- Contiguous independently CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.
- F11 remains blocked.

INDEPENDENT EXECUTION: NOT EXECUTED