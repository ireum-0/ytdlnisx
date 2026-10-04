# BUG-UPDATER-02 — e3ded5a independent completion review

checkpoint_kind: INDEPENDENT_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO

review_parent_sha: e1e36611747692186d3af4215423c043d7f7ce0e
implementation_parent_sha: 58e631394b3f5a868307fba8e9f8382436023949
implementation_sha: e3ded5accb08064d84f5d2ff1d2ea84b61cf6254
canonical_count_change: 0

overall_verdict: PARTIAL_SOURCE_ACCEPTED_PERSISTED_STATE_RESIDUAL
canonical_p0: 0
canonical_p1: 0
canonical_p2: 3

## Accepted source correction

e3ded5a is exactly one normal-forward commit from 58e6313 with merge base 58e6313.

Changed production/test paths are limited to:
- BackupSettingsUtil.kt
- UpdateUtil.kt
- SettingsViewModel.kt
- RestoreTransactionCoordinator.kt
- focused UpdateUtil production-wiring tests
- focused JVM tests

The new source semantics correctly close the two residuals identified at 58e6313 for newly produced/restored state:

1. Foreign updater provenance is no longer portable:
   - ytdlp_source and ytdlp_source_label remain portable intent;
   - ytdlp_source_generation, committed source/generation/result, pending source/generation,
     and future ytdlp_* runtime-authority keys are destination-local;
   - BackupRestoreParser normalization filters those destination-local keys from typed/current/legacy restore input.

2. Merge/Reset reconcile restored source intent against destination-local state:
   - same effective source preserves compatible destination-local proof/generation;
   - changed effective source advances the local generation and invalidates committed/pending proof;
   - Reset-to-default advances generation when the previous effective source differs;
   - absent Merge source preserves current intent.

3. Every successful updater entry path now requires exact pending identity and exact current desired
   source/generation at durable committed-result publication. The former startup-only desired-at-commit
   distinction was removed.

4. Lock/admission order is consistent:
   RestoreMutationAdmission -> UpdateUtil stateLock for Merge, Reset and updater durable publication.
   No inverse blocking order was found.

Reported verification:
- production compilation PASS;
- AndroidTest compilation PASS;
- focused JVM units: 13 PASS / 0 FAIL / 0 skipped;
- runtime/device execution: NOT_VERIFIED.

The independent reviewer did not rerun execution.

## Same-root persisted-state residual

The patch prevents future import of foreign updater authority, but it does not reconcile installations
that were already contaminated by a pre-e3ded5a supported restore before upgrading to e3ded5a.

A reachable pre-change state is:

- a valid backup produced by another installation contains ytdlp_source_generation and committed
  source/generation/result using the normal supported Long/String storage types;
- pre-e3ded5a restore imports those values as portable settings;
- the destination therefore holds foreign committed proof whose source/generation can exactly match
  its desired source/generation;
- the app then upgrades to e3ded5a.

On e3ded5a there is no provenance-schema/epoch migration or startup invalidation of this preexisting
authority. Backup filtering only affects future backups/restores.

StartupYtdlpUpdateOwner calls updateStartupGeneration(... automaticUpdatesEnabled=false). UpdateUtil
still treats committedMatches(current) && !pendingMutationExists() as already committed. Therefore
a preexisting foreign committed proof can remain accepted after upgrade and suppress destination
runtime re-convergence.

This is the existing BUG-UPDATER-02 foreign-provenance root, not a new root.

## Required narrow correction

Add one destination-local provenance-schema/epoch migration for supported pre-change persisted state.

The correction must:

- distinguish state that has been reconciled under the new destination-local provenance contract;
- on the first eligible startup after upgrade from the old contract, invalidate preexisting
  committed/pending updater proof that cannot be proven destination-local;
- ensure the current desired source receives fresh destination proof before startup responsibility
  can consider it committed;
- preserve portable source/source-label user intent;
- preserve the e3ded5a Merge/Reset reconciliation contract for all future state;
- be idempotent across restart/process death;
- coordinate with active Restore recovery so migration cannot race or overwrite an authoritative
  Restore publication;
- not require another app launch after prerequisites clear;
- avoid broad preference migrations or BUG-UPDATER-03 changes.

A small destination-local provenance epoch/version marker is an acceptable design if it is written
only after the old proof is durably retired and the migration is restart-safe. The implementation
may preserve the existing desired generation if it clears unverifiable proof and forces exact runtime
re-proof; if it rebases generation instead, it must preserve monotonic/in-flight correctness.

Focused regression must seed the old persisted state directly as a pre-change fixture, not through the
new writer, then prove upgrade/startup cannot accept the foreign committed proof and converges to fresh
destination proof.

## Disposition

- e3ded5a publication: ACCEPTED as a coherent forward BUG-UPDATER-02 checkpoint.
- New/future backup/restore provenance semantics: SOURCE_FIXED.
- Uniform exact desired-at-commit: SOURCE_FIXED.
- Pre-e3ded5a persisted foreign-proof upgrade compatibility: OPEN same-root residual.
- BUG-UPDATER-02: OPEN P2.
- Runtime/device verification is not yet the next closure gate because this source residual remains.
- No new finding/root and no canonical count change.

INDEPENDENT EXECUTION: NOT EXECUTED
