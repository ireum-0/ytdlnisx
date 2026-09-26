# BUG-DUPLICATE-03 — moved-review-tip reconciliation hold

Date: 2026-09-26 +09:00

Finding:
P2 BUG-DUPLICATE-03

Implementation remote at reconciliation:
7e6e1b7f0a4c0e68b0c2f0c70cfe16710ee4b9da

Unpushed implementation candidate reported by implementation agent:
1de6e9146ca95b8dc8e3aa5d0039d585c9f3e4e9

Candidate parent:
7e6e1b7f0a4c0e68b0c2f0c70cfe16710ee4b9da

Prior review authorization checkpoint:
68caca77f7f63c2273c1619958f49891a78421fd

Review branch moved to:
d825d299614e7513111251785857917f0af1dca2

## Verdict

PUSH RE-AUTHORIZATION WITHHELD PENDING SAME-ROOT RECONCILIATION.

Do not discard, reset, amend, or rewrite local candidate 1de6e914.

The implementation agent correctly stopped before push because the mandatory
review-tip equality gate no longer matched.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 18

CLEAN_REVIEW_BASIS remains:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

## Review-tip movement classification

The two new review-side commits are not production changes.

04a0722d9ee79385702654a4c8b2ba3540084d61 independently re-proves the
already-authorized same-root provider partial-write/admission-fence gap.

d825d299614e7513111251785857917f0af1dca2 adds a distinct same-root
recovery-identity residual:

the generation-private archive is durable across process death, but at exact
base 7e6e1b7f its original configured archive authority is not durably bound.

At recovery time DownloadArchiveAuthority.prepare(...) re-resolves the current
download_archive_path preference. If the user changes configured archive
authority A -> B after generation E became durable but before finalization
recovery, E can be promoted into B instead of the original A.

This remains within BUG-DUPLICATE-03 and is material to push authorization.

## Required reconciliation against local candidate 1de6e914

Before any push, inspect the exact local candidate and prove whether its new
durable provider-promotion fence also binds the original configured archive
authority identity.

The proof must answer:

1. At initial generation/promotion responsibility creation under authority A,
   what exact durable artifact records A?

2. Does that artifact survive process death independently of the mutable
   preference?

3. If download_archive_path changes A -> B before recovery, does recovery of
   the old exact generation still target A and never B?

4. Does the durable identity distinguish at least:
   - provider tree URI authority;
   - raw/default authority where applicable;
   - exact generation/download/execution responsibility?

5. Is the identity used by the actual recovery path, not only recorded for
   diagnostics?

6. Is it retired only after exact verified promotion convergence?

7. Does malformed/corrupt durable authority identity fail closed rather than
   silently falling back to current preference B?

## Allowed outcomes

### Outcome A — local 1de6e914 already closes the new residual

If exact source review of local 1de6e914 proves the durable fence records and
reuses the original configured authority identity across process death:

- add deterministic regression for A -> B preference change before restart
  recovery if one does not already exist;
- recreate/reload the durable fence in the test to prove process-death
  independence;
- verify old generation repairs/promotes only A;
- verify B remains untouched;
- rerun exact-final-SHA affected gates on 1de6e914 if source remains unchanged;
- then fresh-check remote implementation and the current review tip before
  normal push.

No new production commit is required if production already satisfies the new
review condition and only missing regression evidence is already included in
the committed candidate. If test source must change, create a new child commit;
do not amend 1de6e914.

### Outcome B — local 1de6e914 does not close the new residual

Keep 1de6e914 intact.

Create one minimal same-root child commit on top of it that durably binds the
original configured archive authority to the exact generation/promotion debt.

Do not mutate the old generation toward the current preference.

Reuse the app-private durable fence/marker introduced by 1de6e914 if possible.

No Room schema migration.

Required regression:
- configure authority A;
- create exact generation/promotion debt under A;
- persist the durable fence/authority identity;
- simulate process death/recreation;
- change preference to authority B;
- run recovery;
- prove recovery targets A only;
- prove B is untouched;
- prove ordinary admission remains fail closed until A is verified complete;
- prove corrupt/missing authority identity does not fall back to B;
- retire fence/private generation only after verified completion at A.

Then run exact-final-SHA gates on the final child SHA and push the contiguous
local range normally.

## Previously completed candidate evidence

Implementation agent reports candidate 1de6e914 already closes the earlier
partial-write gap with:
- durable pre-mutation provider fence;
- ordinary admission reads unavailable while fenced;
- restart persistence;
- recovery to complete provider contents;
- legacy raw-folder semantics preserved;
- 22/22 archive/queue gates;
- 17/17 ObserveSourceWorkerProductionWiringTest;
- 60/60 JVM unit gate;
- both Kotlin compile gates PASS;
- git diff --check PASS.

That evidence remains useful and should not be discarded. It is insufficient
by itself to authorize push until the newly discovered A -> B recovery identity
condition is reconciled.

## Exact next action

Reconcile local exact candidate 1de6e914 against the d825d299 same-root
recovery-identity residual.

Do not push until the reconciliation result is proven and the current review
tip is explicitly authorized again.

INDEPENDENT EXECUTION: NOT EXECUTED
