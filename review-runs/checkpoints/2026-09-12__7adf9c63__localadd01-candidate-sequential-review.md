# Task 006 / BUG-LOCALADD-01 candidate sequential independent review

Date: 2026-09-12

## Review basis and candidate isolation

- Canonical independently CLEAN basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Overnight queue base: `36b43464b8106d90d672ba94718ccee58f38974f`
- Candidate: `7adf9c6399be9ab855972933782918ce25456be7`
- Candidate is exactly one commit ahead of the queue base.
- Compare against canonical CLEAN basis is diverged with merge base exactly `36b43464b8106d90d672ba94718ccee58f38974f`; the candidate is not integrated into canonical history.
- Governing root checkpoint: `23898ab6a38ba88a922ba8acc0e54434c6c0aa3a`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F20 `BUG-LOCALADD-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN for `BUG-LOCALADD-01`. Do not replay/integrate this candidate as-is.**

Count delta: **0**. This is a residual of the already-counted P2 `BUG-LOCALADD-01`, not a new root.

Canonical blocker count remains **P0 2 / P1 1 / P2 34**.

Canonical CLEAN basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## What the candidate fixes correctly

The candidate materially improves the original identity policy:

- basename/filename-stem exclusion is removed from the reviewed LocalAdd worker/UI paths;
- provider-unscoped `documentId` is no longer promoted as global identity;
- exact content/file URI and tree-URI + relative-path identities are represented by one shared `LocalAddIdentity` policy;
- unknown/malformed identity fails open instead of becoming duplicate authority;
- unresolved display-name metadata no longer silently drops the worker candidate; the worker uses a conservative fallback label;
- focused tests cover same-name distinct authorities/directories, exact URI repeats, malformed identities, and persisted tree/path identity.

These changes address the previously recorded coarse-identity subcases and should be preserved.

## Residual blocker: final durable admission is not revalidated

The candidate still makes duplicate-exclusion authority from a stale pre-insert snapshot rather than revalidating at the final durable admission boundary.

### Worker path

`LocalAddWorker.doWork()`:

1. reads `historyDao.getAll()` once;
2. derives `existingLocalIdentities` in memory;
3. for each entry, rejects when its exact identity is in that in-memory set and immediately adds the identity to the set;
4. performs metadata/match work after that decision;
5. later calls `HistoryKeywordAssignmentRepository(db).insertHistory(item)`.

There is no exact local-identity re-read/CAS inside the final insert transaction. Another LocalAdd producer can insert the same exact URI/tree identity after step 1/3 and before step 5.

### UI/manual paths

`HistoryFragment` repeats the same pattern in both pending-selection and direct local-add flows:

- initial `historyDao.getAll()` -> in-memory `existingLocalIdentities`;
- potentially long-running prompt/search/rename work;
- ordinary `getItem(url)` checks for source URL where applicable;
- final `insertHistory(item)` without a same-transaction exact local-identity guard.

The direct flow can additionally suspend for search and user interaction between identity selection and mutation, making the original observation explicitly revocable under checklist rules.

### Persistence boundary does not close the race

`HistoryKeywordAssignmentRepository.insertHistory()` acquires `HistoryReferenceMutationCoordinator.withLock` only around the transaction that inserts the row/keyword assignments. It does not re-check exact local identity under that lock/transaction before `historyDao.insertAndGetIdRaw(...)`.

The `history` entity has only non-unique indices (including `url`) and no unique exact-local-identity constraint. `HistoryDao.insertAndGetIdRaw()` therefore does not reject a second row that carries the same `downloadPath` or `(localTreeUri, localTreePath)` identity.

Concrete race:

```text
producer A: reads History snapshot -> exact identity X absent
producer B: reads History snapshot -> exact identity X absent
A: passes in-memory identity gate
B: passes in-memory identity gate
A: insertHistory(X) commits
B: insertHistory(X) commits
```

Both rows survive. The candidate therefore does not satisfy the checklist requirement that discovery is not mutation authority and that a stale candidate be revalidated at the final durable mutation boundary.

## Process-death / retry observations

For a single worker replay after a prior matched insertion committed, rebuilding `existingLocalIdentities` from current History can suppress a duplicate on the later attempt. That is useful retry behavior but does not repair the concurrent final-admission race.

Persisted-session -> WorkManager ownership/recovery remains owned by the separate `BUG-LOCALADD-HANDOFF-01` root and is not merged into this finding.

## Minimal correction boundary

Preserve the candidate's shared exact-identity policy, but move the authoritative duplicate decision into a single canonical durable admission primitive.

That primitive should, under the same serialization/Room transaction used for insertion:

1. derive/receive the exact comparable local identities for the row being admitted;
2. re-read current History identity carriers at the final mutation boundary;
3. insert only if no current row proves the same exact URI or exact tree-URI + relative-path identity;
4. return a typed inserted/already-present result so worker/UI counters/session cleanup remain truthful;
5. retain fail-open behavior when strong local identity cannot be proven;
6. preserve existing URL-match semantics without treating URL equality as a substitute for local-storage identity.

A schema migration is not inherently required; the smallest safe design may use the existing canonical History mutation lock plus a same-transaction exact-identity query/revalidation. If a uniqueness constraint is chosen instead, its compatibility/migration semantics must be reviewed separately.

## Required focused regression coverage

Add deterministic coverage for at least:

- two concurrent admissions of the same exact content URI;
- two concurrent admissions of the same exact tree URI + relative path through distinct equivalent entry carriers;
- stale precheck followed by another producer inserting before the tested producer's final mutation;
- same-name distinct directories/providers still both admitted;
- unknown/unprovable identity still fails open;
- exact repeated URI in one batch remains suppressed;
- worker and UI/manual admission paths both consume the same final durable guard.

## Candidate disposition

`7adf9c6399be9ab855972933782918ce25456be7` is **NOT_CLEAN** and must not be replayed as-is. A future reimplementation may reuse its `LocalAddIdentity` policy and tests, but must add final mutation-boundary revalidation.

INDEPENDENT EXECUTION: NOT EXECUTED
