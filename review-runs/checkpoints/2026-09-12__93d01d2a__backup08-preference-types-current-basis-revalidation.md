# BUG-BACKUP-08 — SharedPreferences type round-trip current-basis revalidation

Date: 2026-09-12

## Exact review state

- Fixed independently CLEAN implementation basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.
- Current CACHE-02 verification-only target remains exact implementation SHA `3616ae02e56995e795cc52f3074d8c3d1cd2e330`; this exploratory decision does not rely on newer implementation code.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F7 / `BUG-BACKUP-08`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Upstream F6 / `BUG-BACKUP-06` remains OPEN at exact `93d01d2a...` and constrains which preferences are portable.
- F7 remains a hard prerequisite of F11 / P0 `BUG-BACKUP-03`.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-08` remains OPEN at exact fixed CLEAN basis `93d01d2a...`.**

- Canonical blocker-count delta: `0`.
- Canonical blocker count remains **P0 2 / P1 1 / P2 31**.
- Overall remediation state remains `NOT_CLEAN`.
- CLEAN Review Basis remains `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`.

## Intervening-range verification

The prior F7 current-basis checkpoint used exact basis `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

The exact range `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71..93d01d2afbce2cfa62dc17fad4478416b3d7cf6c` changes only `AutomaticKeywordRuleEngine.kt` and its focused persistence test; it does not alter backup preference serialization or restore type handling.

Exact `93d01d2a...` source was re-read.

## Exact current production evidence

### Backup admits Long and Float values

`BackupSettingsUtil.backupSettings()` enumerates `SharedPreferences.all` and serializes each entry as a `BackupSettingsItem` using:

- `value.toString()` for non-set values;
- `value::class.simpleName` as the serialized type.

Therefore valid Android `SharedPreferences` entries with runtime type `Long` or `Float` are explicitly emitted as backup settings with type `Long` / `Float`.

### Restore still silently drops those admitted types

`SettingsViewModel.restoreData()` still handles only:

- `String` -> `putString`;
- `Boolean` -> `putBoolean`;
- `Int` -> `putInt`;
- otherwise only type strings containing `Set` -> `putStringSet`.

For a valid backed-up `Long` or `Float`, the fallback executes `return@forEach` because the type is not Set-like. The entry is silently skipped and the surrounding restore may still return success.

Concrete loss remains:

`portable preference K = Long/Float V`
→ backup records K, V and its real primitive type
→ restore has no matching primitive handler
→ K is skipped without a typed failure
→ restore may report success
→ requested durable preference state is not reconstructed.

### Unknown non-Set input is also silent-success input

Any admitted or malformed unknown type that is not Set-like follows the same silent skip. Unsupported admitted input is therefore not distinguished from successful restoration.

Malformed values for explicitly handled primitive types may throw and fail the wider restore, but that does not close the silent-drop branch.

## F6 relation

F7 must not respond by blindly round-tripping every numeric-valued key. F6 owns the upstream portability decision for DB-local/transient ID-bearing preferences.

Therefore the local primitive-type defect is independently confirmed, but a safe implementation must consume F6's portable/excluded/remapped preference policy rather than inventing a conflicting one.

## Correction boundary retained

Once the F6 portability policy is established, F7 must:

- round-trip every admitted portable SharedPreferences primitive with the matching API: String, Boolean, Int, Long, Float and StringSet;
- preserve large Long values and representative Float values without coercing them through another primitive type;
- validate type/value pairs before mutation;
- reject/fail unsupported or malformed admitted input rather than silently skipping it and reporting success;
- exclude or separately remap transient/ID-keyed preferences according to F6;
- integrate durable preference-write failure into the broader restore failure contract without conflating type correctness with F11 restore atomicity.

Focused scenarios remain: each supported type, large Long, Float precision, StringSet, malformed Boolean/numeric/set input, unknown type, excluded transient/ID-keyed key, repeated Merge, Reset and restart verification.

## Dependency consequence

F7 remains OPEN and implementation-blocked by unresolved F6 portable/transient preference semantics. It remains a hard prerequisite of F11 / P0 `BUG-BACKUP-03`.

The next exploratory target is F8 / `BUG-BACKUP-05` paused-download backup/restore. Its implementation must remain constrained by unresolved F4 capture correctness and F6 identity policy; current-basis revalidation may proceed while the CACHE-02 verification runs.

INDEPENDENT EXECUTION: NOT EXECUTED