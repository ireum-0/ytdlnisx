# Frozen-basis L2 review — ee75b757 — FINAL

checkpoint_kind: ACTIVE_WAVE_FROZEN_BASIS_EXPLORATORY_FINAL
checkpoint_status: FINAL
manual_review_run: NO
implementation_agent_currently_working: YES
active_wave_diff_inspected: NO
clean_review_basis: ee75b75786b8b6182dfc31946b6325b20294e73b
review_parent_sha: f4f9d302236083a4a362088007a3b664829df613

review_lens: L2 Identity & provenance
review_depth: DEEP
review_result: FAIL
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3
canonical_open_roots: BUG-UPDATER-02,BUG-UPDATER-03,BUG-HISTORY-05
new_finding_ids: NONE
count_change: 0
independent_execution: NOT_EXECUTED

## BUG-UPDATER-02

Current backup/restore still treats destination-local updater authority as portable:
ytdlp_source_generation,
ytdlp_committed_source_generation,
ytdlp_committed_source,
ytdlp_committed_result,
ytdlp_pending_source_generation,
ytdlp_pending_source.

BackupSettingsUtil.backupSettings() emits portable preferences.
BackupRestoreParser.normalize() uses the same portability predicate.
SettingsViewModel.restorePlan() revalidates before Merge or Reset.
Merge writes validated settings under RestoreMutationAdmission.
Reset publishPreferences() clears/replays portable settings with the same predicate.

UpdateUtil.committedMatches() accepts desired generation/source equality with
persisted committed generation/source as runtime proof.

Cross-device collision remains possible: a source backup with nightly generation
8 and committed nightly/8 can overwrite a destination that also happens to use
generation 8, making startup accept foreign numeric equality as destination
runtime provenance.

Status: OPEN P2 / same canonical root.

## BUG-UPDATER-03

BackupRestoreParser.validateSettings() validates only generic declared types.
It has no key-specific schema for ytdlp_source.

Known case:
- ytdlp_source="", type String is accepted by parser;
- UpdateUtil later rejects a present blank source.

Additional same-root case:
- ytdlp_source="1", type Int passes generic Int validation;
- Merge/Reset can persist it as Int;
- UpdateUtil consumes ytdlp_source as a String preference.

Therefore the source restore contract must require the key-specific representation
before mutation: ytdlp_source must be a valid String source value, not merely any
generic preference item whose declared type parses.

Status: OPEN P2 / same canonical root.

## Shared restore authority

parse(), fromTyped(), validatePlan(), Merge and Reset all pass through the common
BackupRestoreParser normalization/validation contract before restore mutation.
A narrow key-specific source validation can therefore close BUG-UPDATER-03 for
both modes without weakening the updater consumer.

## Test gap

Existing preference tests cover generic type round-trip, malformed Int/Boolean/
StringSet values, unknown generic types, cache_path and command_path portability.

They do not prove:
- updater authority keys are omitted/filtered;
- cross-device generation collision is impossible;
- blank ytdlp_source is rejected;
- wrong-type ytdlp_source is rejected;
- updater-specific validation works across supported restore forms.

Duplicate settings keys were observed as a possible ambiguity, but no distinct
production failure was established in this review. It remains NOT_VERIFIED with
no count impact.

This checkpoint supplies frozen-basis DEEP L2 evidence only. It does not create
or resume a manual-3 run and does not mutate the prior FINAL manual run coverage.

INDEPENDENT EXECUTION: NOT EXECUTED
