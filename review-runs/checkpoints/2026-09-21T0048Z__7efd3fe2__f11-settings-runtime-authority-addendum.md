# F11 second-remediation re-review — settings/runtime-authority addendum

Date: 2026-09-21

Reviewed implementation: `7efd3fe2579e421c545d1ed6287713dec02e9225`

Governing root: `[P0] BUG-BACKUP-03`

Verdict remains: `NOT_CLEAN`

This addendum extends the already-open F11-R1/R2 consumer-closure evidence. It does not create a new canonical root.

## LocalAdd runtime keys are inside the generic settings namespace

`LocalAddStorage` stores operational state in default SharedPreferences under:

- `local_add_open_session`
- `local_add_pending_*`
- `local_add_entries_*`
- `local_add_owner_*`
- `local_add_progress_*`

`BackupSettingsUtil.isPortablePreferenceKey()` currently excludes only a narrow set and does not exclude these LocalAdd runtime/session keys.

Therefore settings backup can serialize LocalAdd operational state as portable settings.

More importantly, F11 settings Reset currently calls `editor.clear()` and then reconstructs only selected preserved values plus imported settings. A settings Reset can therefore delete live destination-local LocalAdd session/owner state even when History is not being reset and `local_add_worker` is not quiesced.

This can destroy or import execution authority as ordinary settings data, violating F11 responsibility/provenance boundaries.

## Required correction

Treat LocalAdd operational/session namespaces as destination-local runtime authority, not portable settings payload.

The correction must preserve live LocalAdd state across settings Reset rather than merely excluding it from backup while still deleting it via `editor.clear()`.

Review the complete nonportable/runtime preference set before changing generic settings publication. Preserve established `cache_path`, cleanup-coordinator authority, and other accepted destination-local semantics.

Add production-wiring proof that:
- settings backup does not serialize LocalAdd runtime/session authority;
- settings-only Reset preserves a live LocalAdd session/owner and does not manufacture/import another installation's LocalAdd authority;
- History+Settings Reset still performs the exact R1 owner transfer/reconciliation contract.

F11-R1 and F11-R2 remain open.

INDEPENDENT EXECUTION: NOT EXECUTED
