# YTDLnisX Correctness Remediation — Master Plan & Session Handoff

> 이 문서는 `ireum-0/ytdlnisx` correctness remediation 작업의 장기 기준 문서다.
> 이전 Codex Plan Mode의 revised remediation plan과 이후 독립 검토에서 확정한 보정사항,
> 현재 F1 진행 상태, F1~F22 실행 순서, 모델/검증/commit/push/review 규칙을 한 파일로 통합한다.
>
> 새 ChatGPT/Codex 대화에서는 이 파일을 먼저 제공하고, 가장 최신 구현 결과나 review finding만 추가로 전달한다.
> 새 대화의 assistant는 구체적 수정안을 내기 전에 `checkpoint/pre-baseline-review` 최신 GitHub 상태를 확인해야 한다.

---

## 1. Repository / baseline

- Repository: `https://github.com/ireum-0/ytdlnisx`
- Local checkout: `D:\AndroidStudioProjects\ytdlnisx`
- Working branch: `checkpoint/pre-baseline-review`
- Reviewed production checkpoint: `73d3836665f5f2e6e232e327eef1d968054d0539`
- Pre-remediation docs-only state used by the master plan: `5a07bbdf438d03d51d0be1872f71e0659b887c92`
- Confirmed Room version: **56**
- Active confirmed correctness defects: **22**
- `BUG-BACKUP-09` is excluded as a false positive.

### BUG-BACKUP-09 false-positive rationale

Production restore parsing resets imported IDs to `0L` before creating restored `CookieItem`,
`CommandTemplate`, and `TemplateShortcut` objects in both Merge and Reset. Downstream insertion
therefore receives already-normalized IDs. The false-positive determination depends on this parser
normalization, not on Room `autoGenerate` semantics. Defense-in-depth normalization at a deeper
boundary is optional but is not an active correctness defect.

---

## 2. Current F1 state — read first in a new session

### F1 — BUG-BACKUP-01
**Remap and authorize History replacement targets**

Initial implementation checkpoint:

`d5a6708157b4f2f98de41ca98d09e7bf840f49a6`

Commit message:

`fix: authorize restored history replacements`

This commit was pushed to `checkpoint/pre-baseline-review`.

Initial implementation introduced:

1. restore-time `HistoryRedownloadMarker` remapping through `importedHistoryIdMap`;
2. fail-closed handling for unmappable replacement markers;
3. transactional execution-time authorization before History replacement;
4. source/type validation against the current History target;
5. exact authorized previous-target snapshots for target-derived cleanup;
6. authorization on hard-sub previous-media and quality-rejection cleanup paths;
7. no Room schema migration.

Verification at that checkpoint:

- `git diff --check`: **PASS**
- focused JVM Gradle test: **ATTEMPTED, NOT COMPLETED**
- reason: known pathological Kotlin coroutine code-generation path in `DownloadWorker.kt`
- no broad compile/test PASS was claimed.

### F1 review-fix Finding A — CURRENT WORK IN PROGRESS

**P2 — SourceMismatch / TypeMismatch is incorrectly terminalized as target deletion**

Bad behavior introduced by F1:

- `HistoryReplacementOutcome.SourceMismatch`
- `HistoryReplacementOutcome.TypeMismatch`

can enter the same `historyTargetDeleted` path as `TargetMissing`. The common completion path can then:

- mark linked low-quality redownload child `SKIPPED`;
- use `HISTORY_TARGET_DELETED`;
- delete the Download recovery row;
- allow the parent operation to become `COMPLETED`;
- even though replacement authority actually failed.

Required correction:

- preserve genuine `TargetMissing` behavior;
- SourceMismatch / TypeMismatch must not use target-deleted semantics;
- keep a diagnosable non-running Download recovery row;
- linked low-quality child becomes `FAILED`, not `SKIPPED`;
- existing History row and old media remain untouched;
- no previous-media cleanup;
- no automatic retry against the mismatched target;
- observable Download/ledger/outcome state must describe a failed History commit.

This fix must be a **separate review-fix commit** and then pushed.

Recommended commit message:

`fix: preserve failed history replacement state`

**At the time this document was created, the user was actively implementing Finding A.**
A new session must verify whether it has actually been committed/pushed.

### F1 review-fix Finding B — CONFIRMED, NEXT AFTER A

**P2 — source-less command History redownloads are rejected by F1 authorization**

Confirmed path:

1. `DownloadCommandFragment` supports local `.txt` command input.
2. It stores the file input in command format and intentionally clears `downloadItem.url`.
3. A valid `HistoryItem` can therefore be `DownloadType.command` with blank URL.
4. `createDownloadItemFromHistory()` copies the blank URL and attaches `HistoryRedownloadMarker`.
5. F1 authorization rejects `expectedSourceUrl.isBlank()` as SourceMismatch.
6. replacement validation also rejects `replacement.url.isBlank()`.

Therefore a legitimate source-less command redownload can never replace its intended History row.

Required correction:

- **never** fix this by `DownloadType.command + blank URL + matching numeric History ID` authorization;
- numeric History ID alone must remain insufficient authority;
- add explicit source-less command target identity;
- an opaque/fingerprinted identity carried by `HistoryRedownloadMarker` is acceptable;
- do not serialize raw command text in the marker;
- identity originates from the original History target when redownload is created;
- later replacement-command editing must not destroy original target authority;
- restore remapping changes only History ID and preserves command identity;
- existing regular/quality marker parsing remains backward-compatible;
- blank URL for non-command replacement remains fail-closed;
- source-less command marker without sufficient identity must not fall back to ID-only authority.

Finding B should be a second **separate review-fix commit** and pushed.

### F1 close-out gate

After Finding A and Finding B are committed and pushed:

1. verify latest branch HEAD;
2. run Luna Max `/review` over the **full F1 scope**, not only the last fix;
3. every new finding goes to ChatGPT for latest-GitHub verification before implementation;
4. if clean, F1 becomes Luna-clean;
5. proceed to F2;
6. do not run Sol High final review after F1 alone.

---

## 3. Workflow and model policy

Core workflow:

`implementation`
→ `focused verification`
→ `logical commit`
→ `push checkpoint branch`
→ `separate Luna Max /review`
→ `ChatGPT finding verification/attribution`
→ `review-fix commit`
→ `push`
→ `re-review full original scope`
→ `clean checkpoint`
→ `next finding`

Non-negotiable rules:

- Never hand a review finding directly to Codex for fixing before ChatGPT verifies it against latest GitHub.
- One logically attributable correction per commit.
- Review fixes use separate commits.
- Do not amend/rebase/squash/rewrite referenced commits.
- No force-push.
- Do not commit/push broken scratch states.
- Preserve unrelated dirty work.
- No unrelated feature/refactor work inside a correctness finding.
- Re-review the original finding range, not just the last fix.

### Model selection

**Luna Max**
- default implementation;
- clear/local review fixes;
- repeated `/review`.

**Sol High**
- focused Plan Mode only when the then-current code still leaves a material architectural ambiguity;
- final review after the full remediation is Luna-clean.

**Sol Extra High**
- only very difficult/high-coupling architectural planning;
- currently known mandatory case: `BUG-BACKUP-03`.

Step labels:

- `DIRECT_LUNA_IMPLEMENTATION`
- `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`
- `SOL_EXTRA_HIGH_PLAN_THEN_LUNA`

`CONDITIONAL_FOCUSED_PLAN_THEN_LUNA` does not mean Plan Mode is automatically required.
Inspect the then-current boundary first.

---

## 4. Verification / build-cost policy

Known toolchain:

- Gradle 8.13
- AGP 8.13.2
- Kotlin 2.3.0
- KSP 2.3.4
- JDK 21
- one `app` module
- `org.gradle.caching=true`

Useful commands:

```powershell
.\gradlew.bat :app:compileDebugKotlin -x lint --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain
git diff --check
.\gradlew.bat :app:assembleDebug
```

`DownloadWorker.kt` is a known Kotlin coroutine compiler hotspot. Historical compile time has
reached roughly 18–53 minutes in JVM coroutine spill/control-flow analysis.

During implementation loops:

- always prefer `git diff --check`;
- use focused JVM tests;
- use focused Room/instrumentation tests for transactional behavior;
- use WorkManager-focused tests for scheduling;
- do not repeatedly trigger full Kotlin compile after every small commit;
- do not start duplicate/parallel Gradle builds;
- a test that enters the known compile hotspot but never finishes is **NOT COMPLETED**, never PASS.

Final heavy verification happens after the complete remediation is Luna-clean.

---

## 5. Active defect inventory

### P0
1. `BUG-BACKUP-01` — remap/authorize History replacement markers
2. `BUG-BACKUP-03` — fail-safe destructive Reset restore
3. `BUG-OBSERVE-01` — authoritative source snapshots before destructive sync deletion
4. `BUG-OUTPUT-01` — reject unrelated recent destination files as current outputs

### P1
5. `BUG-BACKUP-04` — fail backup if selected category capture fails
6. `BUG-KEYWORD-01` — authoritative automatic-keyword baseline
7. `BUG-METADATA-01` — prevent stale full-row metadata writes

### P2
8. `BUG-KEYWORD-02` — recompute RULE assignments on History Undo
9. `BUG-METADATA-02` — source identity validation before metadata apply
10. `BUG-DATE-01` — preserve extractor failure vs NO_DATE
11. `BUG-DATE-02` — no false COMPLETED with failed date children
12. `BUG-BACKUP-02` — collision-free restored thumbnail paths
13. `BUG-BACKUP-05` — paused downloads in backup/restore
14. `BUG-BACKUP-06` — remap/reject all imported numeric references
15. `BUG-BACKUP-07` — playlists and playlist groups in backup
16. `BUG-BACKUP-08` — restore all supported SharedPreferences types
17. `BUG-DUPLICATE-01` — canonical media identity in duplicate checks
18. `BUG-CLEANUP-01` — truly recurring leftover cleanup
19. `BUG-LOCALADD-01` — no filename-stem local identity
20. `BUG-HISTORY-01` — playlist membership across delete/Undo
21. `BUG-PLAYER-01` — ordered playback-position persistence

### P3
22. `BUG-QUEUE-01` — waiting selections must not silently enter queue reorder


---

## 6. Hard dependencies and preferred ordering

Only the following are hard semantic dependencies unless fresh code evidence proves another one:

```text
BUG-OBSERVE-01
    └── BUG-KEYWORD-01
        shared authoritative SourceSnapshot contract

BUG-METADATA-02
    └── BUG-METADATA-01
        metadata patch inputs must be identity-validated

BUG-DATE-01
    └── BUG-DATE-02
        typed child lookup outcome drives retry/terminal semantics

BUG-HISTORY-01
    └── BUG-KEYWORD-02
        keyword Undo consumes atomic HistoryUndoSnapshot

BUG-BACKUP-04
    ├── BUG-BACKUP-05
    └── BUG-BACKUP-07
        later categories consume typed/consistent capture boundaries

BUG-BACKUP-03 requires:
    ├── BUG-BACKUP-01
    ├── BUG-BACKUP-04
    ├── BUG-BACKUP-02
    ├── BUG-BACKUP-06
    ├── BUG-BACKUP-08
    ├── BUG-BACKUP-05
    ├── BUG-BACKUP-07
    └── BUG-CLEANUP-01
```

`BUG-BACKUP-03` does **not** require completion of:

- `BUG-OUTPUT-01`
- `BUG-KEYWORD-01`
- metadata/date findings
- `BUG-DUPLICATE-01`
- `BUG-LOCALADD-01`
- `BUG-HISTORY-01`
- `BUG-PLAYER-01`
- `BUG-QUEUE-01`

Its Extra High plan must inspect/quiesce the worker contracts that exist then, but unrelated
correctness findings must not block fixing this P0.

Preferred, not blocking:

- `BACKUP-01` before `OUTPUT-01` is safety ordering, not implementation dependency.
- `BACKUP-02` and `BACKUP-06` are independently implementable.
- `BACKUP-08` and `PLAYER-01` are independent.
- destructive P0s are intentionally early.

---

## 7. Corrections that override older Codex plan text

### 7.1 BACKUP-04 does not own format 4

`BUG-BACKUP-04` specifically fixes hidden capture failures becoming `[]`.

Its ownership is:

- typed capture failure;
- consistent snapshot boundary;
- selected-category failure prevents successful backup artifact.

Do **not** mechanically introduce format 4 solely to close F4.

### 7.2 Backup format evolution belongs to the first real wire-format extension

Current expected order:

- F4 `BACKUP-04`: capture correctness only, retain existing format if possible.
- F8 `BACKUP-05`: first actual payload extension; if a new self-describing capability format is required, this is the preferred place to introduce it.
- F9 `BACKUP-07`: extend the same capability-based format with playlist/group payloads.

Do not bump versions per finding.

### 7.3 Never invent legacy backup semantics

Before defining formats 1–3 or unversioned compatibility:

- inspect repository history;
- inspect existing tests/artifacts if available.

Do not assume “missing version = format 1” just because the current parser could be made to accept it.
Unknown historical input must fail safely instead of being guessed into a schema.

### 7.4 BACKUP-01 unmappable marker is not a late restore-wide exception before BACKUP-03

Before a fully validated pre-mutation restore plan exists:

- stale backup-local ID must never survive;
- marker must not remain executable;
- affected item may be preserved in a safe non-running diagnosable state.

Do not throw a late restore-wide exception after earlier live mutations may already have occurred.

Later F11 may reject an invalid Reset **before mutation**.

### 7.5 BACKUP-03 must not preselect Room journal / 56→57 migration

Required invariants:

- fully parsed/validated plan before destructive Reset;
- related Room changes atomic;
- staged filesystem artifacts;
- explicit SharedPreferences and WorkManager commit/compensation;
- deterministic process-death recovery;
- no worker observes partial restore;
- scheduling only after durable commit;
- idempotent scheduling reconciliation.

The Extra High plan chooses the smallest durable design:

- Room journal,
- app-private file/preferences journal,
- another durable coordinator,
- or no new DB schema.

Only add a migration if the chosen design actually needs persisted schema.

### 7.6 Playlist identity must not be invented

`Playlist` has no stable unique semantic identity.

Import rule:

- allocate fresh destination playlist ID for each imported playlist;
- map backup playlist IDs explicitly;
- rebuild crossrefs through explicit maps;
- never merge by numeric ID, name, or `(name, description)`.

`PlaylistGroup` is different: exact-name merge may follow its current unique-name DB constraint.

### 7.7 Cleanup monthly cadence is calendar month, not fixed 30 days

Preferred design:

- one stable unique one-time-work chain;
- compute calendar daily/weekly/monthly next occurrence;
- monthly advances by one calendar month;
- preference change replaces;
- disable cancels;
- worker schedules successor only if cadence token still matches;
- startup reconciles to exactly one correct schedule.

---

## 8. Implementation waves

### Wave 1 — destructive guards
- F1 `BUG-BACKUP-01`
- F2 `BUG-OUTPUT-01`
- F3 `BUG-OBSERVE-01`

### Wave 2 — backup capture and restore model
- F4 `BUG-BACKUP-04`
- F5 `BUG-BACKUP-02`
- F6 `BUG-BACKUP-06`
- F7 `BUG-BACKUP-08`
- F8 `BUG-BACKUP-05`
- F9 `BUG-BACKUP-07`

### Wave 3 — reset scheduling and fail-safe coordinator
- F10 `BUG-CLEANUP-01`
- F11 `BUG-BACKUP-03`

### Wave 4 — extractor, metadata, date semantics
- F12 `BUG-KEYWORD-01`
- F13 `BUG-METADATA-02`
- F14 `BUG-METADATA-01`
- F15 `BUG-DATE-01`
- F16 `BUG-DATE-02`

### Wave 5 — persistent/UI correctness
- F17 `BUG-HISTORY-01`
- F18 `BUG-KEYWORD-02`
- F19 `BUG-DUPLICATE-01`
- F20 `BUG-LOCALADD-01`
- F21 `BUG-PLAYER-01`
- F22 `BUG-QUEUE-01`

---

# 9. Exact F1–F22 implementation plan

## F1 — BUG-BACKUP-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`  
**Status:** initial implementation committed/pushed; two fix-induced P2 regressions are being closed.

### Root cause
History IDs are remapped during restore but replacement markers previously retained backup-local IDs.
`DownloadWorker` could trust the numeric target for replacement and cleanup.

### Invariant
Numeric History ID alone never authorizes:

- previous-media access;
- History replacement;
- quality-rejection cleanup;
- old-media deletion.

### Correction
- remap regular and quality markers in every restored download category;
- fail closed when no mapping exists;
- authorize current target inside/adjacent to authoritative Room replacement transaction;
- validate canonical/stable source identity and compatible type;
- return exact authorized previous snapshot for cleanup.

### Review additions
Close F1 Finding A and Finding B from Section 2 before declaring F1 clean.

### Verification
Focused marker tests, Room authorization tests, cleanup-safety tests.

---

## F2 — BUG-OUTPUT-01
**Mode:** `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`  
**Hard prerequisites:** none; F1 is preferred safety ordering.

### Root cause
`recoverPathsFromDirectory()` can recursively accept destination files based on recency/mtime without proving current-operation ownership.

### Risk
An unrelated recent file can become `finalPaths` and then feed History persistence/replacement/cleanup.

### Invariant
**mtime alone never establishes output ownership.**

### Preferred correction
Use operation/attempt-scoped artifact provenance. Accept only evidence such as:

- exact extractor-reported path;
- artifact produced in fresh current-attempt temp scope;
- structured move result tied to current attempt;
- explicit ffmpeg/hard-sub product owned by this operation.

Destination scans/mtime may remain diagnostic, never authoritative.

### Preserve
Normal direct/cached downloads, retries, sidecars, cancellation, hard-sub, recoverable temp output.

### Tests
Unrelated recent destination files, stale retry artifacts, missing parsed output, hard-sub/replacement,
exact structured moves, ambiguous candidate preservation.

---

## F3 — BUG-OBSERVE-01
**Mode:** `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`

### Root cause
A plain item list cannot distinguish complete source state from yt-dlp ignored errors, parse drops,
or incomplete NewPipe pagination.

### Invariant
Partial or failed extraction cannot make a destructive absence claim.

### Preferred correction
Typed snapshot contract:

- `AUTHORITATIVE`
- `PARTIAL`
- `FAILED`

Only authoritative snapshot, including authoritative empty, may permit destructive reconciliation.

### Preserve
Normal additions, filtering, canonical URLs, retry behavior, authoritative-empty removal.

### Tests
Ignored child errors, parse drops, continuation failure, empty partial page, transient failure,
complete source and authoritative empty.

---

## F4 — BUG-BACKUP-04
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Capture helper failure is collapsed into an empty array.

### Invariant
An empty captured category means a successful capture of genuinely empty state.

### Correction
- typed capture failures;
- consistent transactionally captured related Room state;
- selected capture failure prevents successful artifact creation;
- category-owned required thumbnail/read failures cannot silently become success.

### Boundary
Do **not** introduce format 4 merely for F4.

### Tests
Per-category fault injection, true empty category, serialization/read failure, concurrent relational change,
final backup write/move failure.

---

## F5 — BUG-BACKUP-02
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Restored thumbnail names derived from backup History IDs can collide before destination IDs are allocated.

### Invariant
Staged restore files cannot overwrite live files or become live references prematurely.

### Correction
- collision-resistant no-overwrite staging names;
- bind only during mapped History insertion;
- clean staged artifacts on failure;
- no old-ID deterministic overwrite path.

### Tests
Same old ID across backups, repeated Merge, extension/content variations, write/insertion failure, Reset.

---

## F6 — BUG-BACKUP-06
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Imported relations/preferences can retain backup-local numeric IDs or trust equal live numbers.

### Invariant
No DB-local numeric reference survives import through numeric equality alone.

### Correction
Inventory every imported ID-bearing field and preference:

- explicit destination map, or
- stable identity proof,
- otherwise reject/clear/quarantine as appropriate.

No “same numeric ID exists” fallback.

### Preserve
Manual assignments, valid source identity merge, unrelated destination state during Merge.

### Tests
Group/source collisions, missing parents, group prefs, waiting downloads, Observe provenance,
transient preference exclusion.

---

## F7 — BUG-BACKUP-08
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`  
**Hard prerequisite:** F6 portable/transient preference policy.

### Root cause
Some serialized SharedPreferences types such as Long/Float are not restored; malformed/unknown types may be ignored.

### Invariant
Every admitted portable preference type round-trips losslessly; unsupported input never reports success.

### Correction
Restore every supported primitive/set type with matching API and strict type validation.
Exclude transient ID-keyed state under F6 policy.

### Tests
String/Boolean/Int/Long/Float/StringSet, large long, float precision, malformed/unknown input, excluded keys.

---

## F8 — BUG-BACKUP-05
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`  
**Hard prerequisites:** F4 capture correctness, F6 ID policy.

### Root cause
Paused downloads are persistent but omitted from backup/restore.

### Invariant
All-category backups preserve paused jobs and restore never auto-starts them.

### Correction
- add paused payload/category;
- preserve config/order/retry/operation metadata;
- restore as `Paused`;
- never enqueue paused rows.

### Backup-format ownership
If a self-describing new backup format is needed, this is the expected first real wire-format extension.
Before defining legacy compatibility, inspect Git history/tests/artifacts.

### Tests
Paused-only, mixed states, order/metadata, Merge, Reset, repeated import, no worker enqueue.

---

## F9 — BUG-BACKUP-07
**Mode:** `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`  
**Hard prerequisites:** F4 typed capture boundary, F6 ID maps.

### Root cause
Playlists, History membership, playlist groups and group membership are absent from backup/restore.

### Invariant
Every imported relationship references an explicitly mapped live row.

### Playlist policy
- fresh destination playlist ID for every imported playlist;
- no merge by backup numeric ID;
- no merge by name;
- no merge by `(name, description)`;
- rebuild crossrefs only through History + playlist maps.

### PlaylistGroup policy
Exact-name merge may follow the current unique-name DB constraint.

### Format policy
Extend the same capability-based format introduced by the first actual wire-format extension.
Do not bump another version mechanically.

### Tests
Reset/Merge roundtrip, duplicate same-name playlists remain distinct, repeated Merge produces distinct imports,
multi-playlist History membership, multi-group membership, exact-name group merge, missing refs, numeric collisions.

---

## F10 — BUG-CLEANUP-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Automatic cleanup is effectively one delayed request and does not maintain a durable recurring schedule.

### Invariant
Exactly one logical cleanup schedule preserves the selected **calendar** cadence.

### Preferred correction
Stable unique one-time work chain:

- compute next daily/weekly/monthly occurrence with calendar/time-zone semantics;
- monthly = one calendar month, never 30 days;
- cadence change replaces;
- disable cancels;
- successful run schedules next only if cadence token matches;
- retry does not create duplicate successor;
- startup reconciles to one correct request.

### Tests
28/29/30/31-day transitions, year boundary, DST/time-zone, repeat, retry, cadence change, disable, restart.

---

## F11 — BUG-BACKUP-03
**Mode:** `SOL_EXTRA_HIGH_PLAN_THEN_LUNA`  
**Hard prerequisites:** F1, F4, F5, F6, F7, F8, F9, F10.

### Root cause
Destructive Reset spans Room, SharedPreferences, filesystem, WorkManager and notifications without a restore-wide commit/recovery protocol.

### Required invariants for the Extra High plan

1. immutable fully parsed/validated restore plan before destructive mutation;
2. app marker/version/capability/reference validation before Reset;
3. related Room mutation atomicity;
4. staged filesystem artifacts;
5. explicit SharedPreferences commit/compensation;
6. conflicting worker quiescence/gating;
7. post-commit scheduling/reconciliation;
8. deterministic process-death recovery at every phase;
9. no worker sees partial restore;
10. scheduling failure is represented honestly and retried idempotently.

### Do not preselect
- Room restore journal;
- Room 56→57 migration.

The plan selects the smallest durable design that proves the invariants.

### Legacy rule
Never invent old format semantics. Inspect repository history/tests/artifacts.

### Tests
Invalid manifest/reference, early/mid/late fault injection, preferences failure, scheduling failure,
worker races, process death at each phase, compensation, restart recovery, staged-file cleanup,
migration test only if schema actually changes.


---

## F12 — BUG-KEYWORD-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`  
**Hard prerequisite:** F3 authoritative SourceSnapshot contract.

### Root cause
Automatic-keyword baseline/discovery receives only item lists, so an incomplete empty extraction can falsely complete baseline.

### Invariant
Baseline completion proves source authority independently of item count.

### Correction
Consume F3 snapshot authority. Only an authoritative snapshot, including authoritative empty,
may complete baseline/scheduled discovery.

### Preserve
Rule revision checks, apply-existing behavior, prior matches, bounded retry.

### Tests
Partial empty then success, authoritative empty, partial non-empty, rule change during fetch, retries, normal discovery.

---

## F13 — BUG-METADATA-02
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Fresh metadata candidates in some lookup/cache paths can bypass requested-source identity validation.

### Invariant
Unrelated fresh metadata contributes no field to a Download row.

### Correction
Validate every fresh candidate before merging/applying it. Reuse the existing canonical-equivalent and approved redirect/source policy rather than inventing another URL model.

### Preserve
Cache fallback, equivalent YouTube URL forms, approved redirects, cancellation.

### Tests
Both lookup orders, valid cache + mismatched fresh, missing provenance, equivalents, redirects.

---

## F14 — BUG-METADATA-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`  
**Hard prerequisite:** F13 validated metadata candidate contract.

### Root cause
Both metadata writers enrich stale `DownloadItem` snapshots and perform full-row writes.

Affected writers:

- `UpdateMultipleDownloadsDataWorker`
- `DownloadWorker.persistDownloadMetadata`

### Invariant
Metadata enrichment cannot overwrite:

- status;
- path;
- queue/order;
- configuration;
- scheduling;
- retry state;
- operation state.

### Correction
Return an immutable metadata-only patch and apply through DAO updates limited to metadata-owned columns,
while verifying the requested source is still current.

### Tests
Deterministic concurrent mutation in both writers, source change, deletion, successful patch.

---

## F15 — BUG-DATE-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Minimal extractor lookup failures collapse to `null`; ambiguous fallback empty output can become durable `NO_DATE`.

### Invariant
Extractor failure/ambiguity remains distinct from authoritative absence.

### Correction
Typed lookup outcome, e.g.:

- found;
- authoritative absence;
- ambiguous;
- retryable failure;
- final failure.

Persist `NO_DATE` only when successful matching extraction proves date absence.

### Preserve
Local/cache precedence, compatibility fallback, grouping, cancellation.

### Tests
Minimal failure + fallback empty/ignored error, authoritative absence, malformed/mismatched result, cache hit, cancellation.

---

## F16 — BUG-DATE-02
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`  
**Hard prerequisite:** F15 typed child outcome.

### Root cause
Parent finalization can mark `COMPLETED` when pending is zero while failed children exist;
retryable child failures may still produce WorkManager success.

### Invariant
Child distribution, parent state, WorkManager result and notification agree.

### Correction
- retryable children remain pending while budget remains;
- return `Result.retry()` while retry is valid;
- terminalize failure at exhaustion;
- mixed terminal outcomes use explicit partial-failure semantics;
- all failed = failed;
- zero failed = completed;
- notifications expose failed counts.

### Preserve
Cancellation, resumable ledger, idempotent terminalization, metrics/cleanup.

### Tests
Mixed, all-failed, retryable, exhausted, process recreation, cancellation during retry, terminal notification uniqueness.

---

## F17 — BUG-HISTORY-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Playlist-reference deletion and History deletion are separate operations; record Undo does not restore playlist membership.

### Invariant
History existence and playlist membership converge atomically; Undo restores the relationship state it claims.

### Correction
Introduce `HistoryUndoSnapshot` that owns at least:

- History row;
- keyword assignments;
- playlist refs.

Capture/delete and restore atomically via Room transaction.
Route bulk and file-backed record-removal through the same relationship primitive.

### Preserve
File ownership checks, retained references, bulk behavior, non-file Undo UX.

### Tests
Multi-playlist Undo, injected transaction failure, bulk delete, file-backed delete followed by DB failure, unrelated membership.

---

## F18 — BUG-KEYWORD-02
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`  
**Hard prerequisite:** F17 atomic HistoryUndoSnapshot.

### Root cause
Undo restores RULE-derived assignments by numeric rule ID even when the rule was edited/deleted/recreated with different meaning.

### Invariant
Manual state comes from snapshot; RULE-derived state comes from **current rules**.

### Correction
Restore non-RULE snapshot assignments, then recompute RULE assignments from currently enabled rules and current keyword sets inside the atomic Undo contract.

### Tests
Rule edit, delete/recreate, changed condition/keywords, disabled/new rule, unchanged rule.

---

## F19 — BUG-DUPLICATE-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Duplicate configuration matching includes raw URL spelling where canonical media identity should be used.

### Invariant
Equivalent forms of one supported media ID share duplicate identity; distinct media/configuration remain distinct.

### Correction
Separate canonical media identity from configuration fields. Normalize only source identity semantics,
without collapsing meaningful format/path/subtitle/config differences.

### Preserve
Archive behavior and intentional redownload bypass.

### Tests
Queue/History/Observe with youtu.be/watch/mobile/music forms, different IDs, changed configuration, non-YouTube URLs.

---

## F20 — BUG-LOCALADD-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
A bare extensionless filename/basename can be treated as identity, causing distinct local files to be skipped.

### Invariant
Filename stem alone never discards a local file.

### Correction
Use actual local identity, such as:

- tree URI + relative path;
- provider-scoped document identity;
- normalized full URI.

Same-name ambiguity should proceed rather than be silently dropped.

### Preserve
Exact URI/tree dedupe, URL match detection, session recovery, cancellation.

### Tests
Same name across directories/providers, earlier accepted batch item, unresolved candidate, exact repeated URI, provider-scoped IDs.

---

## F21 — BUG-PLAYER-01
**Mode:** `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`

### Root cause
Independent lifecycle IO coroutines can persist playback positions out of logical order, and Activity destruction can cancel pending saves.

### Invariant
The final durable playback position for a History item equals the latest logical save.

### Preferred correction
Application-scoped ordered/coalescing writer with per-History logical ordering:

- queue/cache update at submission;
- serialize Room persistence per History item;
- completion zero is a later ordered event and cannot be overtaken;
- avoid new schema unless focused inspection proves serialization insufficient.

### Preserve
Resume threshold, near-end reset, seek/pause/stop, PiP/background, queue transitions.

### Tests
Delayed earlier write then zero, rapid seeks, pause/stop/destruction, ID transitions, Activity recreation, cache/Room convergence.

---

## F22 — BUG-QUEUE-01
**Mode:** `DIRECT_LUNA_IMPLEMENTATION`

### Root cause
Contextual selection can include `WaitingForMembership`, but DAO reorder affects only `Queued`, silently applying to only part of the selected set.

### Invariant
A visible reorder action applies to the entire selected set.

### Correction
- hide/disable Up/Down when resolved selection includes waiting rows;
- revalidate immediately before execution to close async status/menu races;
- apply to direct, inverted, select-all and select-between selections.

### Preserve
Waiting rows remain selectable for delete/copy; queued-only reorder remains valid.

### Tests
Direct waiting, mixed, inverted, select-all, select-between, status race, queued-only.

---

## 10. Exact implementation order summary

1. F1 — `BUG-BACKUP-01` — `DIRECT_LUNA_IMPLEMENTATION`
2. F2 — `BUG-OUTPUT-01` — `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`
3. F3 — `BUG-OBSERVE-01` — `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`
4. F4 — `BUG-BACKUP-04` — `DIRECT_LUNA_IMPLEMENTATION`
5. F5 — `BUG-BACKUP-02` — `DIRECT_LUNA_IMPLEMENTATION`
6. F6 — `BUG-BACKUP-06` — `DIRECT_LUNA_IMPLEMENTATION`
7. F7 — `BUG-BACKUP-08` — `DIRECT_LUNA_IMPLEMENTATION`
8. F8 — `BUG-BACKUP-05` — `DIRECT_LUNA_IMPLEMENTATION`
9. F9 — `BUG-BACKUP-07` — `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`
10. F10 — `BUG-CLEANUP-01` — `DIRECT_LUNA_IMPLEMENTATION`
11. F11 — `BUG-BACKUP-03` — `SOL_EXTRA_HIGH_PLAN_THEN_LUNA`
12. F12 — `BUG-KEYWORD-01` — `DIRECT_LUNA_IMPLEMENTATION`
13. F13 — `BUG-METADATA-02` — `DIRECT_LUNA_IMPLEMENTATION`
14. F14 — `BUG-METADATA-01` — `DIRECT_LUNA_IMPLEMENTATION`
15. F15 — `BUG-DATE-01` — `DIRECT_LUNA_IMPLEMENTATION`
16. F16 — `BUG-DATE-02` — `DIRECT_LUNA_IMPLEMENTATION`
17. F17 — `BUG-HISTORY-01` — `DIRECT_LUNA_IMPLEMENTATION`
18. F18 — `BUG-KEYWORD-02` — `DIRECT_LUNA_IMPLEMENTATION`
19. F19 — `BUG-DUPLICATE-01` — `DIRECT_LUNA_IMPLEMENTATION`
20. F20 — `BUG-LOCALADD-01` — `DIRECT_LUNA_IMPLEMENTATION`
21. F21 — `BUG-PLAYER-01` — `CONDITIONAL_FOCUSED_PLAN_THEN_LUNA`
22. F22 — `BUG-QUEUE-01` — `DIRECT_LUNA_IMPLEMENTATION`

---

## 11. Review attribution / ledger rules

Recommended finding fields:

- `id`
- `title`
- `severity`
- `status`
- `area`
- `primary_category`
- `related_categories`
- `checkpoint`
- `introduced_by`
- `triggered_by`
- `discovered_in`
- `fixed_by`
- `attribution_confidence`
- `files`
- `locations`
- `tests`
- `relation`
- `invariants`
- `review_cycle`
- `review_base`
- `detected_by`
- `escape_stage`
- `change_kind`
- `risk`
- evidence/notes

Useful taxonomy:

- `POLICY_LEAKAGE`
- `STALE_AUTHORITY`
- `STATE_CONVERGENCE`
- `PERSISTENCE_COMMIT_RACE`
- `USER_OVERRIDE_PRECEDENCE`
- `RESTART_RETRY_BOUNDARY`
- `QUEUE_ORDERING`
- `PERFORMANCE_SCALING`
- `QUERY_BOUNDARY`

Use one primary category and optional related categories.

Do not claim `introduced` or `pre_existing` without diff/baseline evidence.

### Review scope

For each implementation unit:

- `review_base` = clean commit before implementation;
- `checkpoint` = first implementation checkpoint;
- `review_head` = current HEAD.

Re-review `review_base..HEAD`, not only the last review-fix commit.

### Ledger closure

Ledger closure is metadata-only.

It may record decisions already established by review/evidence, but must not introduce a new blocker-impacting:

- classification;
- attribution;
- waiver;
- semantic finding decision.

Review/evidence makes the decision. Ledger closure seals it.

---

## 12. Clean policy

For current-change P1/P2 findings:

**CLEAN**
- open current-change P1/P2 = 0
- accepted/waived current-change P1/P2 = 0

**CLEAN_WITH_WAIVERS**
- open = 0
- accepted current-change P1/P2 exists

**NOT_CLEAN**
- open current-change P1/P2 exists

Accepted P1 normally blocks Known-Good Baseline.
Accepted P2 requires explicit owner waiver to remain at baseline.

---

## 13. New-session protocol

When this file is attached in a fresh conversation, the assistant should:

1. read this document as the remediation baseline;
2. do not ask for information already recorded here;
3. inspect latest `checkpoint/pre-baseline-review` GitHub state before a concrete fix recommendation;
4. determine whether the user supplied:
   - implementation result,
   - review finding,
   - or request for next prompt;
5. for review findings:
   - do not immediately hand to Codex;
   - verify actual source/diff/call path;
   - classify true/false positive;
   - determine impact/attribution;
   - define minimal safe fix boundary;
   - decide whether focused Plan Mode is actually needed;
6. if clear, produce one ready-to-paste Codex prompt in a single fenced code block;
7. include focused tests, verification, separate commit, push, and history-preservation rules;
8. after review-fix push, re-review the full original finding scope;
9. continue F1→F22 unless fresh evidence explicitly revises the plan.

### Immediate expected next actions from the current state

1. finish and verify F1 Finding A;
2. commit and push Finding A separately;
3. implement F1 Finding B;
4. commit and push Finding B separately;
5. Luna Max `/review` over the full F1 scope;
6. every new finding → ChatGPT GitHub verification first;
7. once F1 is Luna-clean → begin F2 `BUG-OUTPUT-01`.

---

## 14. Final heavy verification and Known-Good Baseline

After F1–F22 and repeated Luna reviews are clean:

1. full JVM test suite;
2. Android-test compile;
3. full Kotlin compile;
4. `assembleDebug`;
5. representative filesystem/SAF/WorkManager/Media3/restore smoke checks where practical;
6. migration tests only if the final F11 design introduces migration;
7. Sol High final `/review` over the agreed full remediation scope;
8. fix/re-review any final findings;
9. update authoritative review/ledger documentation;
10. create Known-Good Baseline commit;
11. create immutable baseline tag only after policy gates pass.

Do not let unrelated feature work outrun unresolved correctness findings in this remediation scope.

---

## 15. When this master plan may be revised

Revise only when fresh evidence warrants it, for example:

- implementation proves a hard dependency is incorrect;
- prior fixes materially change a later finding boundary;
- Git history proves a legacy backup contract;
- a fix-induced regression changes required semantics;
- F11 Extra High planning establishes a concrete durable restore coordinator;
- a schema migration becomes demonstrably necessary;
- a confirmed finding becomes false positive or vice versa.

Any revision should preserve referenced historical SHAs and clearly distinguish:

- new evidence;
- changed plan;
- unchanged historical attribution.
