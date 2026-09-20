# F11 completion re-review — intermediate R2 disposition

Date: 2026-09-20

## Exact reviewed state

- Repository: `ireum-0/ytdlnisx`
- Implementation branch: `checkpoint/pre-baseline-review`
- Original F11 base: `3072ce86f3887be1e4c044ec8cfe2b99b4f4f4ff`
- Previous reviewed candidate: `2ba43fd272967725b946b1a8fca4e6314107b03b`
- Remediation commit 1: `36215a22172fc7781d757ebe89faa7f8ea4381be`
- Final remote implementation SHA: `61304eb6f11b10ac66057a1978d5b1f8f75019b0`
- Governing canonical review: `8b5b064c63fc04de9b2d18346954ab5dfdec625e`
- Governing Checklist v6: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`

Remote/ancestry independently verified before source review:

- `origin/checkpoint/pre-baseline-review == 61304eb6f11b10ac66057a1978d5b1f8f75019b0`
- `2ba43fd2..61304eb6`: exactly 2 commits ahead / 0 behind / merge base exactly `2ba43fd2...`
- `3072ce86..61304eb6`: exactly 12 commits ahead / 0 behind / merge base exactly `3072ce86...`

## Current independent disposition

### F11-R2 — STILL_OPEN / HIGH

The remediation introduces `RestoreMutationAdmission`, and its process-global mutex correctly serializes ordinary repository mutations against active Restore-owner publication **when callers actually enter that boundary**.

However the portable SharedPreferences consumer closure required by the canonical R2 review is incomplete.

### Exact final-source gap

`BackupSettingsUtil.isPortablePreferenceKey()` treats every preference as portable except a very small explicit exclusion set (`app_language`, `cache_path`, `history_visible_child_youtuber_groups`, cleanup-owned keys, playback-position cache). Therefore ordinary settings such as:

- `use_alarm_for_scheduling`
- `use_scheduler`
- `preferred_download_type`
- `proxy`
- `limit_rate`
- `format_id`
- `format_id_audio`
- `recode_video`
- `compatible_video`

are part of the F11 settings Reset authority graph.

The remediation wraps several direct editor writes with `RestoreMutationAdmission.applyOrdinaryPreferences(...)`, including `subs_lang`, `audio_bitrate`, schedule times, archive path, and generic reset helpers.

But AndroidX Preference persistence remains reachable outside the shared mutation boundary.

Examples at exact final SHA:

1. `BaseSettingsFragment.onDisplayPreferenceDialog()` handles `ListPreference` by assigning `preference.value = newValue` after `callChangeListener`, and handles `EditTextPreference` by assigning `preference.text = newValue`. These assignments use the Preference framework's own persistence path, not `RestoreMutationAdmission`.
2. `DownloadSettingsFragment` standard `SwitchPreferenceCompat`, `ListPreference`, and `EditTextPreference` values such as `use_alarm_for_scheduling`, `preferred_download_type`, `proxy`, `limit_rate`, `buffer_size`, and `socket_timeout` retain framework auto-persistence. The `use_alarm_for_scheduling` listener has no Restore admission at its actual persistence boundary.
3. `ProcessingSettingsFragment` standard preferences such as `format_id`, `format_id_audio`, `recode_video`, and `compatible_video` likewise retain framework auto-persistence.

These are not non-portable/local-only values; they are included by the backup/Reset settings contract.

### Concrete forbidden interleaving remains

Reset publishes the active owner
→ a standard AndroidX Preference change executes through framework persistence outside `RestoreMutationAdmission`
→ portable SharedPreferences state mutates while Restore owns the graph, or after Restore's authoritative preference publication
→ final settings state can diverge from the immutable RestorePlan.

The new production test `restoreWinsAndRejectsOrdinaryMutations` only calls `RestoreMutationAdmission.applyOrdinaryPreferences(...)` directly. It therefore proves the helper rejects after Restore publication, but it does **not** prove the actual Preference framework writers are routed through that helper.

### Why this remains the same canonical root

This is not a new defect ID. It is the exact consumer-closure subcase already named in canonical `F11-R2`: portable SharedPreferences/UI writers must be serialized/revalidated at the **actual preference write boundary**; click-time or helper-only gate checks are insufficient.

## Interim verdict

- F11-R2: `STILL_OPEN / HIGH`
- F11 overall: cannot be CLEAN/CLOSED regardless of the remaining R1/R3/R4 results until this actual-write consumer gap is fixed.
- No canonical root-count delta.
- Full F11 independent re-review continues for R1, R3, R4, original architecture invariants, and preserved prior closures.

## Evidence confidence

- Exact remote SHA/ancestry: independently verified.
- R2 consumer gap: exact-source review at `61304eb6...`.
- Luna runtime claims: not independently executed by ChatGPT.

INDEPENDENT EXECUTION: NOT EXECUTED
