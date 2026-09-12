# BUG-BACKUP-08 — SharedPreferences type round-trip current-basis revalidation

Date: 2026-09-12

## Exact review state

- Exact independently CLEAN implementation basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Implementation branch: `checkpoint/pre-baseline-review`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F7 / `BUG-BACKUP-08`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Hard prerequisite F6 `BUG-BACKUP-06` remains OPEN at current basis, checkpoint `0d999d6467e65b3490186728bdd558b12fff12d2`.
- F7 is also a hard prerequisite of F11 `BUG-BACKUP-03`.
- No in-progress implementation diff was inspected or relied on.

## Verdict

**NOT_CLEAN — existing P2 `BUG-BACKUP-08` remains OPEN at exact canonical CLEAN basis `9edd3e23...`.**

- Canonical blocker-count delta: `0`
- Canonical blocker count remains **P0 2 / P1 1 / P2 34**.
- CLEAN Review Basis remains `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.
- Overall canonical state remains `NOT_CLEAN`.

## Exact current production evidence

### 1. Backup can serialize Long and Float preferences as admitted settings entries

`BackupSettingsUtil.backupSettings()` enumerates `SharedPreferences.all` and records each entry as `BackupSettingsItem` with:

- `value = value.toString()` for non-set values;
- `type = value::class.simpleName`.

Therefore normal Android SharedPreferences values whose runtime types are `Long` or `Float` are represented in the backup as explicit `type = "Long"` / `type = "Float"` entries. The backup side does not exclude those primitive types.

### 2. Restore handles String, Boolean, Int, and Set — but silently drops Long/Float

`SettingsViewModel.restoreData()` switches on the serialized preference type:

- `String` -> `putString`;
- `Boolean` -> `putBoolean`;
- `Int` -> `putInt`;
- otherwise, it proceeds only when the type string contains `Set` and writes a StringSet.

For a valid backed-up `Long` or `Float`, the `else` branch executes `return@forEach` because the type is not a Set. No exception or typed failure is produced, and the outer restore can still return success.

Concrete loss remains:

`portable preference K = Long/Float V`
→ backup records K, V, type`
→ restore recognizes neither Long nor Float`
→ entry is silently skipped`
→ restore may report success`
→ requested durable state is not reconstructed.

### 3. Unknown non-Set types can also become silent success

The same fallback silently ignores any unknown/malformed type string that is not Set-like. This violates F7's invariant that unsupported admitted input must not be represented as successful restoration.

Malformed values for explicitly handled types may throw during conversion and fail the broad restore, but that does not repair the unknown-type silent-drop branch.

### 4. F6 portable/transient preference policy must be resolved before F7 implementation

F7 must not simply round-trip every numeric-looking preference key. F6 owns which DB-local/transient ID-bearing preference state is portable and which must be excluded/remapped/validated.

Therefore current F7 implementation is blocked by F6 even though the local type-loss mechanism is independently reproduced.

## Correction boundary

Once the F6 preference portability policy is established, F7 must:

- round-trip every admitted portable SharedPreferences primitive with the matching API: String, Boolean, Int, Long, Float, and StringSet;
- preserve large Long values and Float values without coercing them through a different primitive type;
- validate the serialized type/value pair strictly before mutation;
- reject/fail unsupported or malformed admitted input rather than silently skipping it and reporting restore success;
- exclude or separately remap transient/ID-keyed preferences according to F6 rather than treating numeric equality as portability;
- integrate durable preference-write success with the broader restore failure contract without claiming that type correctness alone closes F11 atomicity.

Required focused scenarios: normal round-trip of every supported type, large Long, representative Float precision, StringSet, malformed Boolean/numeric/set representation, unknown type, excluded transient/ID-keyed key, repeated Merge, Reset, and restart verification of durable values.

## Dependency consequence

F7 remains OPEN and cannot be implemented safely before F6 portable/transient preference policy is resolved. It also remains a hard prerequisite blocking F11.

The next dependency-eligible exploratory boundary is F8 / `BUG-BACKUP-05` paused-download backup/restore, whose own hard prerequisites are F4 capture correctness and F6 ID policy; both are currently independently OPEN, so F8 can be revalidated but not safely implemented yet.

The separate Task 002 `BUG-KEYWORD-04` canonical replay remains the first implementation target unless an explicit workflow event changes that order.

INDEPENDENT EXECUTION: NOT EXECUTED
