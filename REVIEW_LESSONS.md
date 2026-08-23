# Review Lessons and Checklist Improvement Log

This file is an append-only meta-review ledger for correctness reviews of `checkpoint/pre-baseline-review`.

It does **not** define production truth and it does **not** replace the governing Review Checklist v4. Production correctness is always determined from the exact reviewed checkpoint SHA. This file records why a newly confirmed defect escaped earlier reviews and what general review rule could reduce the chance of the same class of miss recurring.

## Recording rule

Add one entry for every newly confirmed correctness defect discovered by a review run. The entry must be written only after the defect is independently confirmed on a production-reachable path and deduplicated against `TASKS.md` and `TASKS_DELTA.md`.

Do not write vague explanations such as "the review was incomplete" or "the file was overlooked." Identify the concrete review blind spot: an untraced outer catch, missing second-fault injection, reversed lock order, cross-domain identifier assumption, stale full-row writer, post-commit semantic reclassification, retry/re-entry gap, batch fault isolation gap, missing durable convergence carrier, restore normalization gap, platform capability/version mismatch, or another specific mechanism.

Checklist improvement proposals must be **generalizable**. Do not propose a rule that merely names the just-discovered function or patches one exact code path. State what invariant or review question would have exposed the defect class elsewhere too.

This log may recommend changes to Review Checklist v4, but it must not silently modify or supersede the checklist. Checklist changes require separate review and an explicit decision.

## Entry template

### YYYY-MM-DD — `<DEFECT-ID>` — `<short title>`

- **Reviewed checkpoint SHA:** `<full SHA>`
- **Ledger defect entry:** `<TASKS.md / TASKS_DELTA.md section / ID>`
- **Production-reachable failure path:** `<entry point -> authority/validation -> service/repository -> DAO/filesystem/worker -> terminal result>`
- **Why earlier reviews missed it:** `<specific blind spot and the assumption or stopping point that hid it>`
- **Checklist coverage at discovery time:** `<which existing checklist items were relevant, and why they were insufficient or not applied deeply enough>`
- **General lesson:** `<invariant or review principle that applies beyond this defect>`
- **Proposed checklist improvement:** `<concrete review question / matrix row / fault injection / lock-order rule to add>`
- **Suggested checklist wording:** `<ready-to-review wording; recommendation only>`
- **False-positive guard:** `<what production evidence must exist before this proposed rule can create a defect>`
- **Disposition:** `PROPOSED | ACCEPTED | REJECTED | FOLDED_INTO_CHECKLIST`

## Maintenance rules

- Keep historical entries; do not rewrite an old miss explanation merely because later understanding improved. Append a correction/addendum instead.
- If a new defect shares the same root review blind spot as an earlier entry, still create a defect-specific entry, but link the prior lesson and explain what additional refinement was learned.
- A clean review run adds no entry.
- A new defect may update `TASKS_DELTA.md` and this file, but must never trigger an application-source change from the scheduled review task.

### 2026-08-23 — `BUG-SCHEDULER-05` — Pre-Android-12 exact-alarm capability false negative

- **Reviewed checkpoint SHA:** `dfa40697434b7d041bb0bc4f3d9cf2586dfb6d15`
- **Ledger defect entry:** `TASKS_DELTA.md` / `BUG-SCHEDULER-05`
- **Production-reachable failure path:** `DownloadSettingsFragment` permits `use_scheduler` on API 24–30 and calls `AlarmScheduler.schedule()` -> later queueing outside the window calls `AlarmScheduler.canSchedule()` -> helper returns false solely because `SDK_INT < 31` -> `DownloadViewModel` disables `use_scheduler`, does not persist the requested queue transition in that branch, and reports the exact-alarm permission failure.
- **Why earlier reviews missed it:** previous scheduler reviews concentrated on durable carrier identity, recurrence, alarm handoff, WorkManager cancellation domains, and stale ownership. The capability helper was treated as a trustworthy environment gate rather than reviewed as an authority function with version-dependent semantics. The inconsistency between callers also hid the defect: Settings explicitly treats the false value as permission-relevant only on API 31+, while the queue path treats the same false value as authoritative on every supported API level.
- **Checklist coverage at discovery time:** v4 already requires tracing production entrypoints and not stopping at helper names, but it does not explicitly require a platform-version truth table for capability/permission helpers. Retry/re-entry and carrier checks therefore focused on what happened after scheduling authority was granted, not whether the helper itself represented supported API bands correctly.
- **General lesson:** any platform capability or permission wrapper that gates a durable transition is itself an authoritative observation. Its truth value must be validated for every supported platform band, and all callers must assign the same semantic meaning to that value.
- **Proposed checklist improvement:** add a platform-capability/version matrix to the first-authoritative-observation and candidate-rejection steps whenever `Build.VERSION`, permission APIs, feature-detection helpers, or compatibility wrappers gate persistence, scheduling, destructive work, or terminal behavior.
- **Suggested checklist wording:** "For every platform capability/permission helper that gates a durable or user-requested operation, enumerate all supported API/version bands and permission states. Verify that each returned value matches the platform contract in that band, and compare all production callers for consistent interpretation. An API that does not exist on older supported versions must not be modeled as capability=false unless the underlying capability is actually unavailable there."
- **False-positive guard:** require (1) a supported runtime/API band, (2) a production-reachable caller whose behavior materially changes on the helper result, and (3) authoritative platform/library documentation or equivalent source proof that the helper's result misrepresents capability in that band.
- **Disposition:** `PROPOSED`
