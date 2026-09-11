# BUG-OBSERVE-01 — review-fix completion review at ebc3a4f7

Date: 2026-09-11

## Review basis and exact refs

- Previous independently CLEAN Review Basis: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Prior BUG-OBSERVE-01 implementation: `fffb6b131a7b9cb35cf8a218b42863df9e332430`
- Prior independent completion-review checkpoint: `2e37e7cb12de7319c3e4bd5c7fee7bd6606095c5`
- Reported review-fix final SHA: `ebc3a4f770a584a02f6ea2738dff9dd43e1fb267`
- Independently verified implementation branch HEAD: `ebc3a4f770a584a02f6ea2738dff9dd43e1fb267`
- Verified review-fix relation: `fffb6b13... -> ebc3a4f7...`, exactly one direct child commit, zero commits behind
- Verified cumulative relation: `aa1616a2... -> fffb6b13... -> ebc3a4f7...`, exactly two commits ahead, zero behind
- Review-fix changed files: exactly four:
  - `app/src/main/java/com/ireum/ytdl/util/SourceSnapshot.kt`
  - `app/src/main/java/com/ireum/ytdl/work/ObserveSourceWorker.kt`
  - `app/src/test/java/com/ireum/ytdl/util/SourceSnapshotTest.kt`
  - `app/src/androidTest/java/com/ireum/ytdl/work/ObserveSourceSnapshotProductionWiringTest.kt`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F3 `BUG-OBSERVE-01`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / P0 `BUG-OBSERVE-01` REMAINS OPEN — SOURCE SEMANTICS APPEAR FIXED, REQUIRED PRODUCTION-LIFECYCLE REGRESSION EVIDENCE IS STILL MISSING**

No new semantic root is counted. The review-fix corrects the previously identified source-level lifecycle coupling, but it does not satisfy the production-boundary regression requirement explicitly recorded by the prior independent review and reinforced by the v6 checklist's production-wiring / semantic-contract-delta rules.

Canonical blocker-count delta: **0**.
Canonical count remains **P0 3 / P1 3 / P2 25**.
The contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.

## Source-level semantic review

### F3 membership authority remains correct

The F3 Master Plan invariant remains satisfied in source:

- `AUTHORITATIVE` is the only authority that permits destructive source absence;
- `PARTIAL` and `FAILED` cannot authorize deletion by omission;
- authoritative empty remains authoritative and can legitimately drive source-removal reconciliation.

`ObserveSourceWorker` still guards the real sync-with-source destructive path with `sourceIsAuthoritative` and additionally computes deletion candidates through the authority-gated helper.

### Producer authority remains conservative

Cumulative `aa1616a2... -> ebc3a4f7...` source review preserves the correct producer contract established at `fffb6b13...`:

- NewPipe playlist/channel conversion drops and incomplete/failed continuation paths become `PARTIAL` or `FAILED` rather than silently complete membership;
- genuinely completed NewPipe membership, including a genuinely completed empty source, may be `AUTHORITATIVE`;
- yt-dlp source extraction remains conservatively `PARTIAL` on successful typed extraction because its request still uses `--ignore-errors` and tolerant child-line parsing;
- the review-fix does **not** solve lifecycle progress by falsely upgrading yt-dlp to authoritative;
- `ResultRepository.getSourceSnapshotFromSource()` preserves the typed contract and does not upgrade a primary `PARTIAL` snapshot through fallback.

### Prior lifecycle residual is source-level corrected

The review-fix adds a separate lifecycle-progress contract:

- `AUTHORITATIVE -> INITIAL_BASELINE_ELIGIBLE`;
- `PARTIAL -> FORWARD_PROGRESS`;
- `FAILED -> NONE`.

Production `ObserveSourceWorker` now consumes those meanings separately:

- `FAILED` returns through `finishRunAndSchedule(..., countRun = false)`;
- `PARTIAL` keeps destructive absence disabled but has `canAdvanceRun = true`;
- the `getOnlyNewUploads && runCount == 0` ignore-baseline branch is entered only when lifecycle progress is `INITIAL_BASELINE_ELIGIBLE`, therefore a conservatively PARTIAL yt-dlp source no longer repeats that branch forever;
- a PARTIAL snapshot flows through the normal positive-item filtering/queue path;
- the normal terminal call uses `countRun = canAdvanceRun && !canShowRetryConfirmation`, so usable PARTIAL runs can advance recurring lifecycle state while the existing explicit retry-confirmation wait remains non-counting;
- `finishRunAndSchedule()` increments `runCount` only when `countRun` is true and evaluates `endsAfterCount` from the updated count, so the prior permanent `runCount == 0` / inert `endsAfterCount` residual is removed at source level.

This is the intended separation between source-membership authority and safe Observe lifecycle progress. No source-level same-root residual was found in the reviewed cumulative range.

### Positive-item and initial-baseline behavior

For a first run with `getOnlyNewUploads=true` and a PARTIAL source, the worker does not claim a complete initial ignore baseline. Instead the returned positive items continue through the normal positive-item path while the run records `PARTIAL_SOURCE_SNAPSHOT`. This is non-destructive and avoids treating incomplete membership as a complete baseline. Later returned items are evaluated by the normal observed/processed/history filters rather than being silently added forever to the first-run ignore set.

For an AUTHORITATIVE first run, the existing ignore-baseline behavior remains available and advances the run. This preserves a complete baseline only when completeness is actually proven.

## Separate dependent roots / preserved contracts

- `BUG-KEYWORD-01` remains a separate existing P1 root. `AutomaticKeywordRuleSyncWorker` still refuses baseline/full-sync/discovery from `PARTIAL`, which is conservative under F12 and is not claimed closed here.
- `BUG-OBSERVE-HANDOFF-01` remains a separate P0 generation/supersession/revocation root; the review-fix does not claim or establish its closure.
- The review-fix does not touch NewPipe/yt-dlp/ResultRepository producer files, automatic-keyword code, History duplicate-identity files, or the earlier preserved P2-D/E/F/G/H implementation paths. The cumulative source review found no relevant regression that reopens the independently closed History duplicate-identity root.
- Master Plan and authoritative ledger remain unmodified.

## Blocking evidence gap — required production lifecycle coverage is still absent

The prior independent checkpoint at `fffb6b13...` explicitly required production-level regression coverage for the lifecycle composition, not merely the source-authority helper. In particular it required evidence for:

1. yt-dlp-backed usable `PARTIAL` + `getOnlyNewUploads=true` + `runCount=0` not remaining trapped in first-run semantics;
2. a later genuinely new item reaching normal positive processing without granting destructive absence authority;
3. PARTIAL remaining unable to produce destructive deletion candidates;
4. AUTHORITATIVE / authoritative-empty retaining legitimate removal behavior;
5. `endsAfterCount` / run lifecycle advancing rather than remaining inert;
6. the dependent automatic-keyword contract remaining conservative without being misclassified as closed.

The new test `partialSnapshotsAdvanceRunProgressWithoutEstablishingInitialBaseline()` in `ObserveSourceSnapshotProductionWiringTest` does **not** exercise that production composition. It directly calls companion/helper functions:

- `canAdvanceObserveRun(...)`;
- `canEstablishInitialNewUploadBaseline(...)`;
- `shouldUseInitialNewUploadBaseline(...)`.

It does not execute the real worker lifecycle through the relevant production effects. In particular it does not prove:

- `finishRunAndSchedule()` durably increments the `ObserveSourcesItem.runCount` for PARTIAL;
- `endsAfterCount` stops at the correct persisted count;
- a PARTIAL first run actually bypasses the ignore-baseline mutation and reaches the real positive-item filtering/queue path;
- a later newly observed item is admitted by that production path;
- FAILED source extraction leaves the persisted run count unchanged while scheduling/retry behavior remains correct;
- the authoritative first-run/authoritative-empty production path still composes correctly with the same durable state.

The v6 checklist explicitly requires material semantic-contract changes to close through the production consumer/effect graph and states that helper-level correctness is insufficient when the bug exists at Worker/Room/WorkManager composition. Renaming or locating the test under `androidTest` does not by itself make helper assertions production-wiring evidence.

No other Observe lifecycle test was found in the current `app/src/test/java/com/ireum/ytdl/work` inventory, and the only Observe-specific instrumentation file in `app/src/androidTest/java/com/ireum/ytdl/work` is the helper-oriented `ObserveSourceSnapshotProductionWiringTest` above.

Therefore the semantic correction is **SOURCE-LEVEL FIXED**, but independent CLEAN is withheld until the required production-lifecycle regression exists at the actual worker/durable-state boundary.

## Minimal next review-fix boundary

Do not redesign the authority model again unless the new production test exposes a real semantic defect. The next correction should be primarily verification/wiring coverage:

- add the smallest deterministic production-boundary test seam necessary to drive the real `ObserveSourceWorker` lifecycle against durable `ObserveSourcesItem` state;
- use the production worker/coordinator code that actually chooses initial baseline vs positive processing and calls `finishRunAndSchedule`, rather than testing duplicate policy helpers in isolation;
- prove PARTIAL first-run forward progress, a later positive item, durable run-count/`endsAfterCount`, FAILED non-advancement, and AUTHORITATIVE baseline/empty behavior;
- retain the existing destructive-absence helper assertions as lower-level evidence;
- do not weaken yt-dlp's conservative PARTIAL classification merely to make the test pass;
- do not claim `BUG-KEYWORD-01` or `BUG-OBSERVE-HANDOFF-01` closed;
- avoid unrelated implementation changes.

If the production-level test exposes a semantic issue, fix that same BUG-OBSERVE-01 lifecycle boundary in a separate additive child commit and re-run the focused/full verification required by the project protocol.

## Implementation-agent verification evidence

Reported by the implementation agent for `ebc3a4f7...` and treated as evidence, not independent execution:

- focused `SourceSnapshotTest`: 12 passed, 0 failed;
- full JVM suite: 610 passed, 0 failed/errors/skipped;
- KSP: PASS;
- debug Kotlin compile: PASS;
- Android-test Kotlin compile: PASS;
- `git diff --check`: PASS;
- instrumentation: `NOT EXECUTED — DEVICE/EMULATOR UNAVAILABLE`.

The reported passing tests do not remove the production-wiring coverage gap described above.

INDEPENDENT EXECUTION: NOT EXECUTED
