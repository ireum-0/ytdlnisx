# Testing Documentation

The normal engineering verification path is [`../codex/CHECKS.md`](../codex/CHECKS.md). Use [`release-checklist.md`](release-checklist.md) for a release candidate.

Current automated PR checks compile debug Kotlin and run JVM unit tests. Android instrumentation, storage-provider behavior, WorkManager/device lifecycle, Media3, native runtime/ABI behavior, and migration execution still require separate emulator/device evidence.

The current correctness regressions that should be added/executed before their fixes are closed are listed in `codex/CHECKS.md`, including backup/restore History marker remapping, automatic-keyword baseline completeness, rule-edit/Undo behavior, concurrent metadata refresh, and hard-sub lookup failure handling.
