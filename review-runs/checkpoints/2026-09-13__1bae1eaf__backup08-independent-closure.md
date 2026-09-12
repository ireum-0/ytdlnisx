# BUG-BACKUP-08 — independent closure at final wave HEAD

Date: 2026-09-13

## Exact review state

- Previous independently CLEAN contiguous basis: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- F7 implementation commit: `908cb7075689105cfa4e3b67375aded7af9186d3`.
- Final completed wave HEAD independently verified at remote: `1bae1eafea08942f11e6df30e4a13a515dda621c`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F7 / `BUG-BACKUP-08`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.

## Verdict

**CLEAN / CLOSED — P2 `BUG-BACKUP-08` is independently closed at exact final HEAD `1bae1eafea08942f11e6df30e4a13a515dda621c`.**

Canonical P2 count changes **29 -> 28**.

Resulting canonical blocker count: **P0 2 / P1 1 / P2 28**.

Overall project remains `NOT_CLEAN`.

The contiguous independently CLEAN basis does **not** advance because earlier F4/F5/F6 scopes in the same cumulative implementation state remain open on execution-evidence gates. The CLEAN basis therefore remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.

## Source-semantic closure

The final settings backup/restore composition was reviewed through both the serializer/helper and the actual production import parser.

The current contract is:

- portable `String` round-trips as String;
- portable `Boolean` is parsed strictly as `true`/`false`;
- portable `Int` round-trips without widening/narrowing ambiguity;
- portable `Long` is restored as Long and is not narrowed to Int;
- portable `Float` is restored as Float;
- portable StringSet is serialized as a JSON array and restored only when every admitted element is a string;
- malformed primitive values throw/fail restoration instead of being silently omitted;
- malformed StringSet payloads fail rather than dropping bad elements and reporting success;
- unknown preference types fail restoration rather than becoming successful omission;
- generic settings backup excludes `app_language` and the F6-defined nonportable identity-bearing preference keys/prefixes;
- primitive type support therefore does not reintroduce backup-local DB identity authority.

`MainSettingsFragment` parses the settings JSON into `BackupSettingsItem` objects and passes them to `SettingsViewModel.restoreData()`; it does not pre-filter unknown or malformed admitted preference types in a way that would bypass the strict restore boundary.

The F7 hard-prerequisite portion of F6 that F7 relies upon is the portable/transient preference policy. That exact policy is present in final source and is directly exercised by the F7 production-wiring test. F6 remains independently OPEN for separate, unexecuted relational-ID consumers; this F7 closure does not imply F6 closure and does not unblock F8/F9.

Backup format remains version 3; no format bump was introduced solely for this primitive-type correction.

## Exact-final execution evidence

The implementation report records exact-final-wave execution on `Medium_Phone_API_36.1`, API 36, x86_64:

- `BackupPreferenceProductionWiringTest`: 3/3 PASS;
- focused JVM `BackupSettingsUtilTest`: 4/4 PASS;
- full JVM suite: 617/617 PASS, 0 failures, 0 errors, 0 skipped;
- KSP PASS;
- debug Kotlin compile PASS;
- Android-test Kotlin compile PASS;
- debug/android-test APK assembly PASS;
- `git diff --check` PASS.

The production-wiring test explicitly covers:

1. round-trip of String, Boolean, Int, a Long value outside Int range, Float, and StringSet through real backup plus restore;
2. exclusion of `app_language`, `history_visible_child_youtuber_groups`, and `player_playback_position_*` from generic settings backup;
3. malformed Int/Boolean/StringSet values failing restoration without successful persistence;
4. an unknown preference type failing instead of being silently omitted.

This directly satisfies the F7 invariant and required execution matrix at the exact reviewed final state.

## Dependency consequence

F7 is no longer an open prerequisite for F11 / `BUG-BACKUP-03`.

F11 remains blocked by other prerequisites, including currently open F4, F5, F6, F8, F9, and F10. Do not start F11 planning yet.

INDEPENDENT EXECUTION: NOT EXECUTED