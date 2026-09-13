# F9 / BUG-BACKUP-07 historical-mode gate reconciliation

Date: 2026-09-13

## Governing evidence

Authoritative Master Plan `fada33a7eed86b1fa2c07065af66f14bf4d24714`, Section 10 carry-forward rules, explicitly states that historical `Mode:` labels are archival model-selection metadata only and do not control the current implementation runner. Section 11 supersedes those labels; current implementation may proceed directly unless an explicit product/evidence/dependency gate exists.

F9's actual current hard prerequisites in the same Master Plan are F4 typed capture correctness and F6 ID maps.

At the current independent state:

- F4 `BUG-BACKUP-04` is independently CLOSED; semantic fix commit `90afaec1...`;
- F6 `BUG-BACKUP-06` is independently CLOSED at `f20833d6...` and preserved;
- therefore F9's explicit hard prerequisites are satisfied.

## Reconciliation

The older handoff wording that F9 still required a separate focused-plan gate is stale. It came from treating the historical mode label as an active gate. The authoritative current plan explicitly says not to do that.

F9 is now dependency-eligible/actionable. This changes workflow eligibility only; blocker count delta is `0` and F9 remains OPEN until implemented and independently reviewed.

F8 and F9 should share the first actual backup wire-format extension rather than mechanically bumping twice: F8 is the expected first real format extension, and F9 extends that same capability-based format.

Canonical blocker count remains **P0 2 / P1 0 / P2 26**. CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

INDEPENDENT EXECUTION: NOT EXECUTED