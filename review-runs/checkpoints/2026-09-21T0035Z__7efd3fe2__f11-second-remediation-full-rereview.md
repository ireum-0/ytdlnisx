# F11 second-remediation full independent re-review

Date: 2026-09-21

Verdict: NOT_CLEAN

Reviewed checkpoint SHA:
`7efd3fe2579e421c545d1ed6287713dec02e9225`

Review base:
`3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`

Reviewed ledger SHA:
`899328bc91e4008e39a658387396a0106c8666ec`

Current blocker:
- [P0] `BUG-BACKUP-03`

Residuals:
- F11-R1: STILL_OPEN
- F11-R2: STILL_OPEN
- F11-R3: CLOSED
- F11-R4: CLOSED

Canonical count delta: 0.

## R1

New LocalAdd owner markers correctly protect sessions created by the new code, but the previous exact implementation `61304eb6...` created live LocalAdd work as durable `local_add_entries_<sessionId>` plus an ordinary tagged WorkRequest, with no owner marker.

Such a live pre-upgrade WorkRequest can survive into `7efd3fe2...`. Final startup recovery and F11 History-quiescence discovery enumerate only new owner markers. `LocalAddWorker` checks RestoreGate before it can backfill legacy ownership. Therefore History Reset can still cancel a provably-live legacy LocalAdd worker while its durable session survives and no reconstruction occurs.

The new negative test proves arbitrary entry-only legacy keys are not revived, but does not cover an entry-only legacy session that still has a live WorkManager owner.

Required correction: migrate/backfill only provably-live legacy LocalAdd responsibility into the new owner model before F11 can destroy that owner. Do not treat all historical entry keys as live authority. Preserve F20 identity/session/cancellation semantics.

## R2

The new `RestoreAwarePreferenceDataStore` correctly serializes AndroidX framework auto-persistence, and the handoff-carrier final-mutation subcase is source-fixed.

However portable settings consumer closure remains incomplete. `BackupSettingsUtil.isPortablePreferenceKey()` makes keys portable by default except a narrow exclusion set, while final settings code still has direct default-SharedPreferences `apply()` writers outside `RestoreMutationAdmission`.

Examples include portable writes for `backup_path`, `music_path`, `video_path`, `command_path`, `cache_downloads`, `keep_cache`, filename templates, format-importance values, `youtube_player_clients`, and update-source preferences.

These direct writers bypass `PreferenceDataStore`, so the original ordinary-write vs Restore-publication race remains reachable.

The new 6-method Preference suite covers framework PreferenceDataStore persistence, not these direct writers.

Required correction: route every Reset-portable direct settings mutation through the shared actual-write admission boundary while preserving nonportable `cache_path`, F10 cleanup authority, types/defaults, and user-visible behavior.

## R3

CLOSED at source.

Restore-owned exact-alarm failure now establishes a delayed WorkManager request while Restore authority is valid, awaits `Operation.result`, durably records ACCEPTED ownership, retains that carrier across Restore retirement, and reuses it on same-operation replay. Failed fallback remains durable reconciliation debt and same-Restore recovery retries it before republishing an exact alarm.

The two new alarm-fallback production-wiring tests exercise accepted fallback/crash/replay and failed fallback/recovery.

## R4

CLOSED and preserved.

Restore immediate work remains operation-scoped with KEEP; delayed non-alarm restore work remains operation/time-scoped with KEEP; ordinary Download trigger identity remains independent.

## Preserved contracts

No new blocker was established in the immutable RestorePlan, durable phase journal, one restore-wide Room transaction, deterministic restored-file publication, checked Reset preference commit/compensation, typed outcomes, startup Restore ordering, malformed-carrier fail-closed behavior, overlapping Reset exclusion, F10 cleanup contract, or F20 storage-identity contract.

Second-wave range:
- 11 forward commits
- 17 files
- no schema/migration change
- no Cleanup production change
- no LocalAdd storage-identity-policy/final-admission change

Full F11 range:
- 23 forward commits
- 0 behind
- exact merge base `3072ce86...`
- 76 unique changed files
- no schema/migration change

## Verification gaps / evidence

Implementation-agent runtime evidence reports focused and broad gates green at exact final SHA, with the frozen 27 inherited failures unchanged. Those results are evidence only.

Independent runtime execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
