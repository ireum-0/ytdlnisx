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

For Materializer runs, the logical order remains strict even when publication is optimized:

1. Fully verify a rank.
2. Seal it as an immutable rank-receipt Git object:
   `automation/receipts/materializer-rank/<run_id>/rNNN.json`
3. Preserve one distinct rank commit per rank. A rank commit may be staged as an unattached Git commit before publication, but it must remain a distinct ancestry node.
4. After five contiguous valid rank receipts exist in the staged ancestry, create a distinct aggregate commit:
   `automation/receipts/materializer/<run_id>/rNNN-NNN.json`
5. Only after that five-rank aggregate commit, create a distinct state-advance commit for `materializer-state.json`.
6. For a new materialization range, do **not** write official inventory / ledger / validation / progress until the entire logical batch is complete.
7. No partial official batch output.

A rank receipt must preserve exact history resolution, complete changed-file pagination through explicit `files: []` closure, exact source/frozen blobs, implementation provenance, structural-kind evidence, effectiveness eligibility, lens/effectiveness evidence, factual exception triggers, and previous-rank binding.

The required ancestry shape for one microsegment is therefore:

`base HEAD -> rank N -> rank N+1 -> ... -> rank N+4 -> aggregate N-(N+4) -> state advance`

Do not collapse multiple rank receipts, the aggregate, or the state advance into one commit. The optimization is **single publication of a prebuilt linear commit chain**, not squashing.

### Optimized atomic publication

Preferred Materializer publication mode:

1. Capture a fresh live base HEAD, live state blob, authoritative aggregate prefix, effective next rank, governing pins, remediation bindings, and writer status.
2. Prefetch and independently verify the target ranks' history commits, complete changed-file pagination, source checkpoint blobs, and frozen checkpoint blobs in parallel where possible.
3. Build rank receipt contents in rank order. Each receipt must bind the actual Git blob SHA of the immediately previous rank receipt.
4. Create the rank receipt blobs, trees, and distinct commits as a linear chain rooted at the captured base HEAD **without moving the live branch ref**.
5. After each complete five-rank staged segment, create its aggregate blob/tree/commit, then create its state blob/tree/commit. The next staged segment, if any, must bind the staged state blob produced by the preceding segment.
6. Verify the staged Git objects before publication: exact blob SHAs, expected file paths, expected parent chain, run_id/rank/range bindings, aggregate bindings, state bindings, and absence of unrelated or production/application-source changes.
7. Perform the final publication gate described in **Write / race safety**.
8. Advance `review/lens-history-v1` with exactly one non-force ref update to the final staged commit.
9. Perform the post-publication verification described below.

Git blob/tree/commit creation that is not reachable from `review/lens-history-v1` is staging only; it is not authoritative live state. If the final publication gate fails, abandon the staged chain and reconstruct from the new live state. Never force, rebase, transplant, or partially reuse a chain whose captured base HEAD/state is no longer current.

### Publication-unit rollout

- The first execution after this protocol amendment MUST use exactly one complete five-rank microsegment as the atomic publication unit.
- After that publication passes all post-publication verification, later executions MAY stage multiple complete five-rank microsegments in one publication, bounded by the current Materializer logical batch.
- A multi-segment publication must preserve the full alternating ancestry:
  `rank commits -> aggregate -> state -> next rank commits -> aggregate -> state -> ...`
- Do not cross from Materializer into Triage/Auditor in the same atomic publication.
- If tooling, validation, race conditions, or any anomaly makes atomic staging uncertain, fall back to the smaller five-rank publication unit or the legacy per-rank publication path. Safety wins over throughput.

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

Distinguish **Git-object staging** from **live-branch publication**:

- Creating blobs, trees, or commits that are not reachable from `review/lens-history-v1` does not change authoritative live state.
- Moving `review/lens-history-v1` is the publication event.

### Before starting an atomic publication unit

1. fresh-fetch `review/lens-history-v1` HEAD and capture it as `base_head`;
2. fresh-fetch `materializer-state.json` and capture its actual blob as `base_state_blob`;
3. reconstruct the authoritative aggregate prefix and highest contiguous valid one-rank suffix;
4. compute the effective next rank from evidence, never from `state.next_rank` alone;
5. ensure no conflicting Materializer writer is enabled, active, or has just advanced the branch;
6. fresh-fetch all governing pins, applicable corrections/remediations, and the prior certification required by state;
7. never amend, rebase, squash, force-push, rewrite referenced history, or update/delete immutable receipts.

### During staging

1. Keep every rank receipt as a distinct commit and preserve exact previous-rank blob binding.
2. Keep every five-rank aggregate and state advance as distinct commits in the required order.
3. Root the entire staged chain at the captured `base_head`.
4. Do not move the live branch ref while constructing the staged chain.
5. Verify staged commits by SHA before publication. Every commit must have the expected parent and expected tree/file blob mapping.
6. Do not include unrelated files or production/application source in the staged trees.
7. Unattached objects from an abandoned or failed staging attempt are non-authoritative and must be ignored on resume unless they later become reachable from the live branch through an explicitly verified publication.

### Final publication gate

Immediately before moving the live ref:

1. re-check writer/automation status;
2. fresh-fetch the live HEAD and require it is still exactly `base_head`;
3. fresh-fetch the live state and require its blob is still exactly `base_state_blob`;
4. require that no conflicting receipt/aggregate/state artifact has appeared on the live branch;
5. require the final staged commit descends linearly from `base_head` and every staged commit/object verification has passed.

Then perform exactly one **non-force** ref update from `base_head` to the final staged commit.

The non-force update is the race barrier. If the branch moved or GitHub rejects the update as non-fast-forward, do not retry with force and do not rebase/cherry-pick the staged chain. Reconstruct the new authoritative live prefix and build a new chain from that state.

### After publication

1. re-fetch `review/lens-history-v1` and require HEAD equals the final staged commit;
2. re-fetch the final state file and require its actual blob equals the staged state blob;
3. re-fetch/list every newly published rank receipt and aggregate and require their actual live blobs equal the staged blobs;
4. verify the published commit parent chain from the old `base_head` through the final state commit;
5. require no unrelated or production/application-source path entered the published chain.

These post-publication checks may be batched/parallelized because the ref has already been published, but every required binding must be checked before beginning another publication unit.

If post-publication verification fails, enter a fail-closed HOLD and perform no further repository write until the discrepancy is reconstructed.

The legacy per-rank publish-and-refetch procedure remains a safe fallback when atomic staging cannot be proven correct, but it is no longer the preferred path.

### Scheduled-task interaction

Before manually staging or publishing, inspect whether Lens History Materializer / Lens Exception Triage / Lens History Auditor automations are enabled or have just run.

Do not let a manual writer race an active scheduled writer on the same branch.

If necessary, temporarily disable the role that would conflict, perform the atomic publication unit, then re-enable only after the branch is in a safe gate state.

---

## Append-only Materializer remediation

If live `materializer-state.json` contains `materializer_remediations` or a completed microsegment contains a `remediations` binding, those artifacts are part of the authoritative current state and MUST be fresh-fetched before semantic comparison.

The current remediation protocol lives on the live branch at:

`review-runs/lens-history-v1/automation/remediation/materializer-remediation-v1.md`

Its purpose is narrow: repair a deterministic `checkpoint.effectiveness_eligibility` defect in an already sealed immutable Materializer receipt without rewriting that receipt or its aggregate.

For a valid bound remediation:

- original rank/aggregate blobs remain immutable Git ancestry anchors;
- later `previous_rank_receipt` bindings continue to validate against the original blobs;
- semantic consumers apply the append-only field-replacement overlay;
- the effective aggregate overlay binds the original aggregate plus the field remediation;
- all remediation protocol / invalidation / replacement / effective-overlay blobs must match the exact blobs named by live state;
- Triage and Auditor must compare against the effective semantic view while independently preserving and validating the original immutable chain;
- do not silently generalize this mechanism to checkpoint kind, findings, attribution, metrics, exceptions, provenance, or source/frozen identity.

A HOLD caused by a deterministic sealed-receipt mismatch may be released only when live state explicitly binds a valid remediation chain and records the prior HOLD state. If the live remediation artifacts do not satisfy their protocol, remain fail-closed.

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
