# Review Lessons and Checklist Improvement Log

This file is an append-only meta-review ledger for correctness reviews of `checkpoint/pre-baseline-review`.

It does **not** define production truth and it does **not** replace the governing Review Checklist v4. Production correctness is always determined from the exact reviewed checkpoint SHA. This file records why a newly confirmed defect escaped earlier reviews and what general review rule could reduce the chance of the same class of miss recurring.

## Recording rule

Add one entry for every newly confirmed correctness defect discovered by a review run. The entry must be written only after the defect is independently confirmed on a production-reachable path and deduplicated against `TASKS.md`.

Do not write vague explanations such as "the review was incomplete" or "the file was overlooked." Identify the concrete review blind spot: an untraced outer catch, missing second-fault injection, reversed lock order, cross-domain identifier assumption, stale full-row writer, post-commit semantic reclassification, retry/re-entry gap, batch fault isolation gap, missing durable convergence carrier, restore normalization gap, or another specific mechanism.

Checklist improvement proposals must be **generalizable**. Do not propose a rule that merely names the just-discovered function or patches one exact code path. State what invariant or review question would have exposed the defect class elsewhere too.

This log may recommend changes to Review Checklist v4, but it must not silently modify or supersede the checklist. Checklist changes require separate review and an explicit decision.

## Entry template

### YYYY-MM-DD — `<DEFECT-ID>` — `<short title>`

- **Reviewed checkpoint SHA:** `<full SHA>`
- **Ledger defect entry:** `<TASKS.md section / ID>`
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
- A new defect may update both `TASKS.md` and this file, but must never trigger an application-source change from the scheduled review task.
