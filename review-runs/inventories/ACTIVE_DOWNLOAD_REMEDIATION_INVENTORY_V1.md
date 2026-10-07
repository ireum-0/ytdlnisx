# Active Download Remediation Inventory V1

DERIVED_INDEX=YES
CANONICAL_COUNT_AUTHORITY=YES

ACTIVE_REMEDIATION_SCOPE_ID=DOWNLOAD_ACTIVE_BLOCKER_CLOSURE
ACTIVE_REMEDIATION_SCOPE_BOUNDARY=SCHEDULER_BACKGROUND_FORMAT_AND_INCOGNITO_DOWNLOAD_BLOCKERS
CANONICAL_COUNT_SEMANTICS=ACTIVE_REMEDIATION_OPEN_ROOTS_ONLY
implementation_verification_sha=eda6a7589af3a19a97eb38e869b47dabaf74388b

CANONICAL_P0=0
CANONICAL_P1=0
CANONICAL_P2=8

## Canonical roots

1. BUG-SCHEDULER-WINDOW-01 — P2 — REOPENED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-06__eda6a758__manual-v7-l6-repository-wide-reopen-final.md
   Current correction boundary: one coherent inclusive-minute/end-effect contract across contains(), nextEnd(),
   AlarmManager publication, durable handoff notBeforeAt, DownloadViewModel and DownloadWorker.
2. BUG-SCHEDULER-RESTORE-01 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-06__eda6a758__manual-v7-l6-repository-wide-reopen-final.md
   Current correction boundary: validate restored scheduler configuration and converge restored durable
   preferences with exact external AlarmManager/WorkManager ownership.
3. BUG-FORMAT-BG-01 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
4. BUG-FORMAT-BG-02 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
5. BUG-FORMAT-BG-03 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
6. BUG-FORMAT-BG-04 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
7. BUG-FORMAT-BG-05 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md
8. BUG-INCOGNITO-01 — P2 — VERIFIED_OPEN
   Latest evidence: review-runs/checkpoints/2026-10-07__eda6a758__repository-current-existence-audit-current-review-roots.md

## Current implementation priority

The active implementation routing remains the scheduler authority wave. The latest published implementation
is still eda6a758. The implementation-agent handoff currently routes to the persisted F11 handoff
test-contract diagnostic continuation; this inventory does not alter that prompt or launch state.

Latest review-only scheduler diagnostic evidence:
review-runs/checkpoints/2026-10-07__eda6a758__scheduler-broader-handoff-test-failures-classified.md

## Scope guard

Repository-wide findings are not adopted into this inventory without a separate explicit scope decision.
The repository-wide derived finding total and its OPEN/CLOSED counts are separate accounting domains.

production_source_changed=NO
implementation_prompt_changed=NO
