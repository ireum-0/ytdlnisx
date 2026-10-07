# Repository current-existence audit — clipboard/template/updater immediate dispositions

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 8b96d298b274e0b23bae5b2dd4b2e57c92aaa3ad
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-COOKIE-02 — VERIFIED_OPEN P3

Historical root:
Cookie clipboard export reports success before the actual projection/read/clipboard mutation completes.

Current exact source still reproduces the contract violation:
- CookiesFragment handles Export Clipboard by calling cookiesViewModel.exportToClipboard() and
  immediately showing copied_to_clipboard;
- CookieViewModel.exportToClipboard() still creates a detached viewModelScope.launch(Dispatchers.IO);
- the coroutine now correctly awaits updateCookiesFile(), which closes the old detached projection-
  rebuild sub-race, but the Fragment still does not await that coroutine;
- projection-not-ready simply returns from the detached coroutine;
- read/service/clipboard exceptions are caught and only printed;
- none of those outcomes reaches the Fragment's already-published success Snackbar.

Disposition:
BUG-COOKIE-02 = VERIFIED_OPEN P3.

The historical missing-file subcase was partially improved by making updateCookiesFile suspending, but
the root success-before-result / swallowed-failure contract remains.

Required direction:
return one awaitable typed export result through projection, file read and clipboard mutation; publish UI
success only after that result succeeds.

## BUG-TEMPLATE-01 — VERIFIED_OPEN P3

Historical root:
Command Template clipboard export returns a detached Job and the Fragment reports success before the
clipboard mutation, while background failures are swallowed.

Current exact source still reproduces it:
- CommandTemplatesFragment calls exportToClipboard() inside withContext(Dispatchers.IO), but the called
  function itself only launches a separate viewModelScope coroutine and returns a Job;
- withContext therefore does not await repository reads, serialization, or ClipboardManager.setText();
- the Fragment then publishes copied_to_clipboard;
- the detached coroutine catches Exception and only prints it.

Disposition:
BUG-TEMPLATE-01 = VERIFIED_OPEN P3.

Required direction:
make export a suspending/awaitable typed operation and couple the success Snackbar to completed clipboard
publication.

## BUG-TEMPLATE-02 — VERIFIED_OPEN P2

Historical root:
user/imported persistent URL regex can be malformed and raw Regex construction in download configuration
can abort unrelated valid requests.

Current exact source still reproduces the root:
- UiUtil.showCommandTemplateCreationOrUpdatingSheet turns URL-regex chips directly into strings and
  inserts/updates the CommandTemplate without compiling/validating them;
- CommandTemplateViewModel.importFromClipboard deserializes and inserts templates without regex
  validation;
- DownloadViewModel.createDownloadItemFromHistory still evaluates extra-command filters with raw
  Regex(u).containsMatchIn(historyItem.url);
- DownloadViewModel.getFormat still evaluates preferred Command Template filters with raw
  Regex(u).containsMatchIn(url);
- sibling YTDLPUtil consumers already use safeRegexMatches, proving invalid syntax is expected to be
  treated as data rather than a process invariant.

Disposition:
BUG-TEMPLATE-02 = VERIFIED_OPEN P2.

Required direction:
validate at persistence/import, centralize safe matching for every legacy/current consumer, and ensure one
malformed template cannot abort fallback selection or independent download configuration.

## BUG-UPDATER-01 — VERIFIED_CLOSED

Historical root:
custom update output containing ERROR fell through an independent second if/else and was returned as DONE.

Current exact source:
- custom updates execute under exact runtime mutation authority;
- classification is centralized in customUpdateResponse(output);
- customUpdateResponse uses one mutually exclusive when:
  ERROR -> ERROR,
  "yt-dlp is up to date" -> ALREADY_UP_TO_DATE,
  otherwise -> DONE;
- only DONE/ALREADY_UP_TO_DATE proceed to runtime validation and committed-result publication.

The historical ERROR -> DONE fallthrough is therefore absent.

Disposition:
BUG-UPDATER-01 = VERIFIED_CLOSED.

## Immediate audit result

new_candidate_ids_audited_here: 4
verified_open_here: 3
verified_closed_here: 1

Corrected cumulative lower-bound progress:
- candidate IDs: 136
- audited unique IDs: 81
- verified closed/currently not reproduced: 67
- verified open: 14
- not yet audited inside lower bound: 55

New verified-open non-canonical roots from this checkpoint:
- BUG-COOKIE-02 — P3
- BUG-TEMPLATE-01 — P3
- BUG-TEMPLATE-02 — P2

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
