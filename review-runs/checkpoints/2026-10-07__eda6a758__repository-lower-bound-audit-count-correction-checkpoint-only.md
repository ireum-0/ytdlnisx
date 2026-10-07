# Repository lower-bound audit count correction — checkpoint-only audited roots

checkpoint_kind: REPOSITORY_FINDING_AUDIT_COUNT_CORRECTION
checkpoint_status: FINAL
review_parent_sha: be0e8f545359eca96d3015ab9337692112310bcf
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

## Problem

The handoff arithmetic reported:
- registry-derived/later-current candidate lower bound: 136;
- audited: 88;
- not yet audited: 48.

That arithmetic incorrectly assumes every one of the 88 audited roots belongs to the 136-ID candidate
population extracted from TASKS.md + TASKS_DELTA.md + fourteen then-current review IDs.

Three roots already audited in Batch A are not members of that 136-ID population:
- BUG-BACKUP-11
- BUG-UPDATER-03
- BUG-HISTORY-05

All three are checkpoint-only/later-discovered production roots and were VERIFIED_CLOSED by Batch A.

## Correct lower-bound accounting

For the exact 136-ID inventory:
- audited IDs: 85
- verified closed/currently not reproduced: 66
- verified open: 19
- not yet audited: 51

Separately already audited checkpoint-only roots outside that 136 population:
- BUG-BACKUP-11 — VERIFIED_CLOSED
- BUG-UPDATER-03 — VERIFIED_CLOSED
- BUG-HISTORY-05 — VERIFIED_CLOSED

Therefore:
- total already-audited distinct roots across the 136 population plus these three checkpoint-only roots: 88;
- 136-ID remaining count: 51, not 48;
- repository discovered-root total remains NOT_YET_VERIFIED because additional checkpoint-only roots are
  already known (for example provisional BUG-TERMINAL-11 and BUG-OBSERVE-HANDOFF-01) and still require
  alias/distinct-root reconciliation.

No finding disposition changes.
Current download canonical remains P0=0 / P1=0 / P2=8.
No production source, prompt, active implementation scope, Master Plan or ledger was changed.
