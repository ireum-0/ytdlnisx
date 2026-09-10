# Independent fixed-basis follow-up — Observe source edit/update identity

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Luna workflow state during review: `WORKING_FROZEN`
- Governing Plan: `plan/remediation@fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Verdict: `NOT_CLEAN` subcase confirmed under existing root `BUG-OBSERVE-SOURCE-IDENTITY-01`
- Count delta: `0`
- Canonical count remains: `P0 2 / P1 3 / P2 25`
- CLEAN basis consequence: none; remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`

## Confirmed update-path subcase

The existing `BUG-OBSERVE-SOURCE-IDENTITY-01` is not limited to concurrent insertion.

Production edit flow at the fixed basis:

1. `ObserveSourcesBottomSheetDialog` reconstructs an `ObserveSourcesItem` using the existing `currentItem.id` while taking the edited URL directly from the UI.
2. `ObserveSourcesViewModel.insertUpdate()` sees `id > 0` and calls `repository.update(item)` without checking raw or canonical semantic source identity against sibling source rows.
3. `ObserveSourcesRepository.update()` routes an ACTIVE source directly to `observeSourcesDao.update(item)`.
4. `ObserveSourcesDao.update()` is Room `@Update(onConflict = REPLACE)` keyed by the source primary key. The source entity has no unique URL/canonical-source identity constraint.
5. After the update, `insertUpdate()` calls `repository.observeTask(item)`, which replaces/schedules the `OBSERVE<edited-id>` worker for that row while any different sibling source ID remains independently scheduled.

Concrete consequence:

- S1 may already own semantic source U.
- Editing S2 to exact raw U, or to a canonical-equivalent spelling U', is accepted under S2's distinct primary key.
- S1 and S2 then remain separate durable source owners with separate WorkManager identities.
- The previously confirmed cross-worker duplicate-publication race can therefore be reached through ordinary source editing even without racing two inserts.

## Root reconciliation

This is the same durable identity root already recorded as `BUG-OBSERVE-SOURCE-IDENTITY-01`: one semantic Observe source can acquire multiple durable numeric owners because source mutation has no database-enforced semantic uniqueness authority. It strengthens the existing production proof but does not add another blocker count.

It remains distinct from:

- `BUG-OBSERVE-01` source-result completeness;
- `BUG-OBSERVE-HANDOFF-01` source-row -> WorkManager acceptance;
- P2-K command/source-token duplicate normalization;
- P2-B archive/media identity.

## Acceptance consequence

Any remediation for `BUG-OBSERVE-SOURCE-IDENTITY-01` must cover both INSERT and UPDATE mutation boundaries. Adding only an insertion pre-check is insufficient. A source edit that would collide with another durable semantic source must deterministically reject, merge under an explicit contract, or otherwise preserve one authoritative semantic owner. Canonical-equivalent source forms must be handled consistently with the persisted semantic identity contract.

The downstream enabled duplicate-policy publication boundary still requires atomic one-winner admission across concurrent Observe workers as recorded by the parent finding checkpoint.

No in-progress P2-B source was inspected.

INDEPENDENT EXECUTION: NOT EXECUTED
