# Repository current-existence audit — current-review roots

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: cf33c8b1c773f0d479c176b2f533916890a2f538
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## VERIFIED_OPEN current-review roots

The following roots were already revalidated directly against exact eda6a758 by the latest manual and
canonical review chain. This checkpoint records them into the repository current-existence inventory
without changing their ownership or severity.

### CURRENT-PLAYER-TIMELINE-INDEX — VERIFIED_OPEN P1
The callback-held old index is still dereferenced against the current player timeline after timeline
mutation. This is an independently deferred non-download root.

### BUG-APP-UPDATE-01 — VERIFIED_OPEN P2
Dotted version components are still flattened by dot removal/padding rather than compared as semantic
version components. The repository-wide L6 review kept this root open.

### BUG-APP-UPDATE-02 — VERIFIED_OPEN P2
DownloadManager completion is still not bound to exact request identity and has no durable completion
owner across process death. The L6 review explicitly retained this root.

### BUG-PLAYLIST-DELETE-01 — VERIFIED_OPEN P2
Playlist relationship deletion and endpoint deletion remain separate durable statements rather than one
Room transaction, so partial destructive commit remains reachable.

### BUG-COOKIE-RESTORE-01 — VERIFIED_OPEN P2
Ordinary cookie mutation still bypasses Restore mutation admission and can compete with authoritative
Restore reset/import.

### WORKER-FOREGROUND-COMPLETION-01 — REOPENED_OPEN P2
The latest L5 platform-contract review reopened this root: foreground-establishment completion is still
discarded before correctness-relevant worker effects. The former closure relied on request invocation,
not acknowledged completion, so the closure criterion was too weak rather than a proven later regression.

### BUG-STORAGE-ALLFILES-01 — VERIFIED_OPEN P2
The latest L5 review found API-30+ special shared-storage access UI/logic without the matching manifest
declaration required by the platform contract.

## Current canonical download roots — preserved VERIFIED_OPEN

These eight roots remain the fixed current canonical download campaign and are counted only in
CANONICAL_P0/P1/P2:

### BUG-SCHEDULER-WINDOW-01 — REOPENED_OPEN P2
Same-root residual. The earlier closure separately accepted:
- minute-inclusive contains();
- an END timestamp at the beginning of that same minute.
It failed to compose them into the final-effect invariant. At 05:00:xx the predicate can still be true
while the external END owner already fired at 05:00:00. This is a false-closure/application-depth error,
not evidence of an unrelated new root.

### BUG-SCHEDULER-RESTORE-01 — VERIFIED_OPEN P2
Portable Restore still needs exact scheduler-domain final-image validation plus convergence of durable
preference authority and external AlarmManager/WorkManager ownership, including duplicate-key semantics.

### BUG-FORMAT-BG-01 — VERIFIED_OPEN P2
Selected/captured Processing membership can still widen through broad status transition rather than
remaining coupled to the exact successful transition set.

### BUG-FORMAT-BG-02 — VERIFIED_OPEN P2
Durable Saved/background-format intent can outrun WorkManager enqueue acceptance/recovery ownership.

### BUG-FORMAT-BG-03 — VERIFIED_OPEN P2
Per-item format refresh failures/non-success outcomes can still be swallowed or aggregated as ordinary
batch success; typed extractor outcome and truthful terminal aggregation remain required.

### BUG-FORMAT-BG-04 — VERIFIED_OPEN P2
A stale pre-fetch Download snapshot can still publish format selection using authority/configuration that
changed during the external fetch; stable cross-store authority image remains required.

### BUG-FORMAT-BG-05 — VERIFIED_OPEN P2
Background-format cancellation still needs exact semantic batch/request identity and acknowledged durable
cancellation across retry generations.

### BUG-INCOGNITO-01 — VERIFIED_OPEN P2
The all-incognito predicate still implements "any true" rather than nonempty-all-true semantics, allowing
mixed selected rows to be represented as all-incognito while false rows still publish History.

## Inventory accounting

Newly accounted members of the 136-ID lower-bound population in this checkpoint: 15
- verified open: 15
- verified closed: 0
- reopened among them: 2
  - BUG-SCHEDULER-WINDOW-01
  - WORKER-FOREGROUND-COMPLETION-01

Corrected 136-ID lower-bound progress after this checkpoint:
- audited: 100
- verified closed/currently not reproduced: 66
- verified open: 34
- not yet audited: 36

Separately audited checkpoint-only roots remain 3, all VERIFIED_CLOSED.

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
