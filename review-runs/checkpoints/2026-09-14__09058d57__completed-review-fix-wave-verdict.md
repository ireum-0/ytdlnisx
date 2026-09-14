# Completed F10/F20 review-fix wave verdict — 2026-09-14 — `09058d57`

## Authority

- Implementation start: `f1a159db41f1281a31e1e06df486e4f67cdc3d89`
- Completed implementation head: `09058d574d19d211cd0db36cbf47726bb8fa7dc5`
- Verified range: 2 commits ahead / 0 behind; merge base exactly `f1a159db...`
- F10 commit: `73c91aa8e951302fd1091f779cb5c621b655a170`
- F20 commit: `09058d574d19d211cd0db36cbf47726bb8fa7dc5`
- Ledger remains `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist: Review Checklist v6

## Sequential dispositions

### F10 / `BUG-CLEANUP-01` — NOT_CLEAN

Checkpoint: `fca257ee92a817e7d269beba85df52c9acc184ac`.

The prior stale destructive-effect TOCTOU is closed by the shared coroutine-mutex effect/authority gate. However `configure()` now waits through that mutex inside `runBlocking`, while the real `DownloadSettingsFragment` preference listener invokes `configure()` synchronously on the main/UI thread. If cleanup already owns the gate, changing/disabling the setting blocks the UI for the full Room/filesystem destructive effect interval. This is a fix-induced P2 semantic-contract consumer regression.

F10 therefore remains NOT_CLEAN and F11 stays blocked.

### F20 / `BUG-LOCALADD-01` — OPEN / NOT_CLEAN

Checkpoint: `ca969cae42a3f0596d7e43b752802ff007f0cd7e`.

The opaque document-ID prefix/tree collision subcase is closed. Exact provider-scoped document identity is now preserved and generic tree-relative identity is no longer invented.

However LocalAdd production still performs suppressing prechecks with `HistoryDao.getItemByDownloadPath()`, whose SQL is substring `LIKE '%' || :path || '%'` over Gson-serialized `List<String>`. Worker and UI skip immediately on any match before reaching the strong shared identity policy/final transactional admission. Distinct valid paths where one URI text is a substring/prefix of another can therefore still be silently discarded. This is another consumer/subcase of the same existing F20 P2 root, not a second blocker.

F20 remains OPEN / NOT_CLEAN.

## Preserved closures

F17 and F18 remain preserved. `HistoryKeywordAssignmentRepository.kt` has the same exact blob SHA at `f1a159db...` and `09058d57...` (`527a7f3342da8b632c48b44fa93f773091c1d4a5`), and the F20 diff does not modify the production Undo path.

No evidence in the two-commit range reopens prior F4/F5/F6/F7/F8/F9/F15/F16 closures.

## Canonical count / basis

State entering this completed review-fix wave: **P0 2 / P1 0 / P2 20**.

F10 reconciliation:
- prior stale-effect P2 subcase/root at the review-fix boundary closes;
- fix-induced main-thread consumer P2 opens;
- net delta 0.

F20 reconciliation:
- opaque-ID prefix subcase closes;
- existing same-root substring-precheck consumer remains open;
- net delta 0.

Resulting canonical blockers: **P0 2 / P1 0 / P2 20**.

Overall verdict: **NOT_CLEAN**.

Contiguous independently CLEAN basis remains:
`90afaec157607669ea32fa41877e7f0efcdcca86`.

F11 / `BUG-BACKUP-03` remains blocked until F10 actually closes; once F10 closes, F11 still requires `SOL_EXTRA_HIGH_PLAN_THEN_LUNA` before implementation.

## Verification evidence

Implementation report:
- `git diff --check`: PASS
- `:app:kspDebugKotlin`: PASS
- `:app:compileDebugKotlin -x lint`: initial failure, then PASS after correction
- `:app:compileDebugAndroidTestKotlin`: initial failure, then PASS after correction
- runtime instrumentation / Samsung SM-A546E: NOT EXECUTED — DEVICE UNAVAILABLE

GitHub exact-SHA evidence:
- combined commit statuses: none
- workflow runs associated with exact SHA: none

The implementation-agent execution claims are evidence only. No reviewer-run tests were executed.

## Next repair boundary

A follow-up implementation wave may repair only these two stable residuals:

1. F10: preserve the shared authority/effect ordering without synchronously blocking the UI/main thread during an in-flight cleanup. Production preference acceptance/failure and multiple rapid changes must remain ordered and honest.
2. F20: remove/replace LocalAdd substring `downloadPath LIKE` suppressing prechecks so every suppressing decision uses exact shared storage identity; final transactional admission remains authoritative.

Do not start F11, Observe, F21, F22, or unrelated work in that repair wave.

INDEPENDENT EXECUTION: NOT EXECUTED
