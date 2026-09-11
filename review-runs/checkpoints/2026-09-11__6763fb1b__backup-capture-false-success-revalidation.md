# BUG-BACKUP-04 — selected-category capture false-success revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna implementation wave is active.
- The active Luna implementation commits/diffs were not inspected, compared, reviewed, or relied upon.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P1 ROOT RECONFIRMED**

Defect: `BUG-BACKUP-04`

At the fixed CLEAN basis, selected backup categories can still experience capture/read/serialization failure while the backup pipeline receives an ordinary empty payload and proceeds toward a successful artifact. Related Room state is also captured through multiple independent reads rather than one consistent capture boundary.

## Exact-source evidence

### 1. Production selection and success boundary

`app/src/main/java/com/ireum/ytdl/ui/more/settings/MainSettingsFragment.kt` collects selected backup categories and calls `SettingsViewModel.backup(selectedItems)`. The UI reports success whenever that function returns `Result.success(path)`.

`app/src/main/java/com/ireum/ytdl/database/viewmodel/SettingsViewModel.kt` builds one `JsonObject`, iterates the selected categories, and wraps each category dispatch in an outer `runCatching`. That outer boundary would correctly return `Result.failure(err)` if a capture helper propagated its error.

### 2. Multiple selected-category helpers erase capture failure into empty success

`app/src/main/java/com/ireum/ytdl/util/BackupSettingsUtil.kt` locally wraps many capture helpers in `runCatching`, but ignores the failure and returns a new empty `JsonArray()` afterward. This pattern is present for, among others:

- `backupSettings()`;
- `backupHistory()`;
- `backupCookies()`;
- `backupCommandTemplates()`;
- `backupShortcuts()`;
- `backupSearchHistory()`;
- `backupObserveSources()`;
- `backupKeywordGroups()`;
- `backupKeywordGroupMembers()`;
- `backupYoutuberGroups()`;
- `backupYoutuberGroupMembers()`;
- `backupYoutuberGroupRelations()`;
- `backupYoutuberMeta()`.

For those helpers, a DAO read, preference conversion, Gson serialization, or per-item conversion exception does not reach `SettingsViewModel.backup()`'s outer failure boundary. It becomes indistinguishable from a genuinely empty selected category.

Concrete false-success chain:

`selected category`
→ `capture/read/serialization exception inside BackupSettingsUtil helper`
→ `helper-local runCatching absorbs exception`
→ `JsonArray()`
→ `SettingsViewModel adds empty category to backup JSON`
→ `backup artifact write/move continues`
→ UI can report backup success.

This directly violates the F4 invariant that an empty captured category means successful capture of genuinely empty state.

### 3. Required custom-thumbnail capture can silently omit failed reads

When `downloads` is selected, `SettingsViewModel.backup()` also calls `backupCustomThumbnails()`.

`backupCustomThumbnails()` walks History custom-thumbnail references with `mapNotNull`. A referenced thumbnail is silently omitted when:

- the file does not exist;
- it is not a regular file;
- it cannot be read; or
- `file.readBytes()` throws (`runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null`).

The History payload can therefore retain a custom-thumbnail reference while the corresponding backup-owned thumbnail payload is silently absent. This is exactly the category-owned required-read failure class that the governing F4 contract says must not silently become success.

### 4. Related Room state is not captured under one consistent transaction

`SettingsViewModel.backup()` performs related capture as separate helper/DAO calls without a shared Room transaction or equivalent immutable snapshot boundary. Examples include:

- `keywordData`: groups and group-members are fetched separately, then visibility preferences are read separately;
- `youtuberData`: groups, members, relations, and metadata are fetched through separate helper calls, with visibility preferences read separately;
- `downloads`: History is captured first, then History is read again for custom thumbnails, then automatic-keyword rules/keywords/matches/assignments are queried separately.

Concurrent relational mutation can therefore produce a mixed-time backup in which related parent/member/reference data does not represent one coherent captured state. This remains part of the existing `BUG-BACKUP-04` capture-boundary root rather than a separate count.

### 5. Final artifact failure is not the confirmed false-success mechanism here

At this basis, the temporary backup file creation/write path does not locally convert write exceptions into success. `FileUtil.moveFile(...)` is invoked before `Result.success(res[0])`; a zero-result move does not itself yield a normal successful return because indexing the empty result fails. This review therefore does not attribute the F4 root to final move failure.

The confirmed blocker is the earlier selected-category capture boundary, where helper-local error erasure and silent required-read omission can construct a superficially valid artifact from failed capture.

## Governing correction boundary

The Master Plan F4 contract remains applicable:

- selected-category capture failure must be typed/propagated rather than collapsed to an empty successful payload;
- an empty category must mean a successfully captured genuinely empty category;
- selected capture/read/serialization failure must prevent successful backup artifact creation;
- related Room state that must agree semantically must be captured under a consistent boundary sufficient to prevent mixed-time false success;
- required category-owned thumbnail/read failures must not silently disappear;
- preserve legitimate true-empty categories;
- do **not** introduce a backup format bump solely for `BUG-BACKUP-04`.

Focused regression evidence should include per-category fault injection, true-empty categories, serialization/read failure, concurrent relational change, required-thumbnail read failure, and final artifact write/move failure handling.

## Root/count reconciliation

- This is a revalidation of existing canonical P1 `BUG-BACKUP-04`, not a new root.
- Count delta: `0`.
- Canonical blocker count remains `P0 3 / P1 3 / P2 26`.
- `BUG-BACKUP-03` remains dependency-gated on the applicable backup/restore prerequisites and is not merged with this root.
- `BUG-BACKUP-05` / `BUG-BACKUP-07` remain downstream payload-extension/relationship defects with F4 capture correctness as a prerequisite; they are not counted here.
- The contiguous independently CLEAN Review Basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED