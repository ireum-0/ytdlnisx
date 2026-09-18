# YTDLnisX lens-history-v1 — NEXT CHAT

## Purpose

This file is the cross-chat handoff for the historical lens coverage / effectiveness reconstruction work.

It is intentionally stored on branch `review/lens-history-handoff` so that documentation commits never enter the live materialization ancestry on `review/lens-history-v1`.

**Authoritative live state always comes from branch `review/lens-history-v1`, not from this handoff branch.**

Do not modify production/application source for this workflow.

---

## Repository / branches

- Repository: `ireum-0/ytdlnisx`
- Live reconstruction branch: `review/lens-history-v1`
- Handoff-only branch: `review/lens-history-handoff`
- Stable protocol / semantics:
  - `review-runs/lens-history-v1/README.md`
  - `review-runs/lens-history-v1/lens-history-v1.schema.json`
  - `review-runs/lens-history-v1/rules/**`
  - frozen manifest and verified seeds under `review-runs/lens-history-v1/**`

The live branch and its exact Git objects always override any historical values written below.

---

## Mandatory first action in a new chat

Before giving advice or writing anything, fresh-fetch from **`review/lens-history-v1`**:

1. exact branch HEAD;
2. `review-runs/lens-history-v1/README.md`;
3. `review-runs/lens-history-v1/automation/materializer-state.json` and its blob SHA;
4. current run's one-rank receipts:
   `automation/receipts/materializer-rank/<run_id>/`;
5. current run's five-rank aggregate receipts:
   `automation/receipts/materializer/<run_id>/`;
6. corrections, if any;
7. previous final Triage/Auditor certifications named by state;
8. every governing pin named by state.

Do not ask the user for information already available there.

---

## Critical resume rule: state.next_rank is not sufficient

The pipeline uses a two-level checkpoint model.

A one-rank evidence receipt may exist after the latest state update. Therefore:

1. start from the state-bound completed aggregate prefix;
2. enumerate the current run's rank receipts after that point;
3. verify each candidate receipt's actual Git blob, parent ancestry, run_id, governing pins, and previous-rank binding;
4. accept only the **highest contiguous valid rank-evidence prefix**;
5. compute:

`effective_next_rank = highest_contiguous_valid_rank + 1`

If no rank receipt exists beyond the state-bound aggregate prefix, use `state.next_rank`.

Never redo or overwrite an already valid immutable rank receipt.

---

## Two-level checkpoint model

For Materializer runs:

1. Fully verify a rank.
2. Seal it as immutable:
   `automation/receipts/materializer-rank/<run_id>/rNNN.json`
3. After five contiguous valid rank receipts exist, create:
   `automation/receipts/materializer/<run_id>/rNNN-NNN.json`
4. Only after the five-rank aggregate is sealed, advance `materializer-state.json`.
5. For a new materialization range, do **not** write official inventory / ledger / validation / progress until the entire logical batch is complete.
6. No partial official batch output.

A rank receipt must preserve exact history resolution, complete changed-file pagination through explicit `files: []` closure, exact source/frozen blobs, implementation provenance, structural-kind evidence, effectiveness eligibility, lens/effectiveness evidence, factual exception triggers, and previous-rank binding.

---

## Reconstruction safety rules

- Explicit historical evidence only => DIRECT / Tier A.
- DERIVABLE / Tier B only when complete inputs plus a versioned deterministic rule uniquely determine the result.
- Otherwise => `NOT_VERIFIED`.
- Never map legacy `audit_lens` to modern L1-L6 coverage.
- Never fill a missing metric with `0`.
- Never fill missing coverage with `NOT_RUN`.
- Never infer checkpoint kind from filename, commit message, chronology, or vague implication.
- Bootstrap/intermediate effectiveness values are not cumulative.
- Only eligible final checkpoints enter cumulative effectiveness.
- Multi-lens findings count once using `primary_detecting_lens`; supporting lenses are not duplicate counts.
- Never silently resolve semantic exceptions.

Lens definitions remain:
- L1 Durability & recovery
- L2 Identity & provenance
- L3 Concurrency & authority
- L4 Destructive ownership
- L5 Platform contract closure
- L6 Cross-feature semantic propagation

---

## Write / race safety

Before every live-branch write:

1. fresh-fetch `review/lens-history-v1` HEAD;
2. require the expected parent;
3. ensure no other Materializer writer has advanced the branch;
4. never amend, rebase, squash, force-push, or rewrite referenced history;
5. never update/delete immutable receipts.

After every write:

1. re-fetch the new file;
2. record the actual blob SHA;
3. re-fetch the commit;
4. require its parent equals the prior expected HEAD.

If another writer advances the branch, stop writing, reconstruct the new authoritative prefix, then continue from the new effective next rank.

### Scheduled-task interaction

Before manually writing, inspect whether Lens History Materializer / Lens Exception Triage / Lens History Auditor automations are enabled or have just run.

Do not let a manual writer race an active scheduled writer on the same branch.

If necessary, temporarily disable the role that would conflict, perform the serialized work, then re-enable only after the branch is in a safe gate state.

---

## Completed replay anchor

The prior replay run is complete and certified:

- run_id: `R0B-20260917T1112Z-001`
- authoritative final audit commit:
  `c494a1385242a68a88cc5e656f2a4258f95d7379`
- authoritative final audit blob:
  `afa1bf6e278ef39543de74e9593ec1d1c8b20a35`
- status: PASS
- unlock_allowed: true
- independently preserved exception ranks:
  `[35,36,44,45,47,48,52,60]`
- Triage/Auditor disagreement: 0

Do not redo R0B unless a fresh exact-binding verification fails.

---

## Current materialization run

The current logical run is:

- run_id: `M01-20260917T2345Z-001`
- stage: `MATERIALIZE_061_090`
- logical range: 61–90
- target batch size: 30
- start head:
  `c494a1385242a68a88cc5e656f2a4258f95d7379`

### Last observed state when this handoff was created

Observed live branch HEAD:
`b801e1542f5366e561e2644c528ea02541922b06`

Observed HEAD message:
`review: seal M01 rank 066 evidence`

Observed Materializer state blob:
`0e61ce55676657bdbc85ae9a66d2e547f55ec8cc`

That state still recorded:
- completed aggregate: 61–65
- `next_rank=66`

But an additional valid one-rank receipt had already been observed:

- path:
  `review-runs/lens-history-v1/automation/receipts/materializer-rank/M01-20260917T2345Z-001/r066.json`
- blob:
  `8ff9b35d3d198c8263a36787e90d923503123c11`
- commit:
  `b801e1542f5366e561e2644c528ea02541922b06`
- parent:
  `83fbfa3aff3faa283b36bb7636fc7225eb6ec5c7`

Therefore, **at handoff creation time only**, the effective next rank was 67.

This is an OBSERVED ANCHOR, not a future authoritative value. A new chat must fresh-fetch because rank 67+ may already exist.

---

## Governing pins observed for M01

Fresh state at handoff creation bound:

- frozen snapshot:
  `25a554d1768f8d3cdd09a6e384a89d915eeeace4`
- frozen checkpoints tree:
  `bd72cf55e5e876f46ab1e592bf3008e6044d977b`
- schema blob:
  `b2fe91adda91ecfbd1cfb1d6195f6ec1df68cf96`
- document identity rule blob:
  `982d8fda98d70c5a196e470b24facf1b7c337cda`
- verified seed blob:
  `531dbcbe9dd6854d969d8f1bc2b8a1a532788d51`
- frozen manifest blob:
  `8e327e4b0bde29586c3d512f8bd0b077a1a32b8c`

Re-fetch and require exact equality before relying on them.

---

## M01 semantic exceptions observed so far

At the time of this handoff:

- rank 61: `AMBIGUOUS_KIND`; commit-message "bootstrap" was not accepted as structural evidence.
- rank 65: implementation transition boundary and `BUG-CLEANUP-01` same-root recovery subcases.
- rank 66:
  - `AMBIGUOUS_KIND`
  - substantive same-root `BUG-CLEANUP-01` recovery subcases
  - a NOT_VERIFIED semantic candidate concerning History deletion authority normalization

Preserve these as factual semantic-review candidates. Do not promote or resolve them without explicit deterministic support.

---

## End-of-batch gate for M01

After ranks 61–90 are fully sealed into six five-rank aggregates:

1. validate the complete logical range;
2. only then create official inventory / ledger / validation / progress outputs;
3. verify output blobs and progress delta;
4. set Materializer state to COMPLETE;
5. require `requires_triage=true` and `requires_audit=true`;
6. Triage the full logical range, not a sample;
7. independently Audit the full logical range;
8. only a valid final Auditor PASS with exact binding may unlock the next materialization range.

Do not start the next logical batch from Materializer COMPLETE alone.

---

## Expected behavior in a new chat

After fresh verification, report only:

- authoritative live HEAD;
- authoritative current run/state;
- highest contiguous valid rank prefix;
- effective next rank;
- whether another writer is active;
- exact next action.

Then immediately execute the safe next action without asking for information already present in GitHub.

If live GitHub disagrees with this handoff, **live GitHub wins**.
