# Manual correctness review — 256a5cf5 — L6 residual intermediate

manual_review_run: YES
manual_review_run_status: IN_PROGRESS
manual_review_start_parent: 38a0c32e01562a943d99e375780adfcdadd161e6
review_parent_sha: 38a0c32e01562a943d99e375780adfcdadd161e6
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c

master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3

scope: same-SHA governing checklist refresh, triggered contract closure, primary L6 DEEP
overall_verdict: NOT_CLEAN
active_remediation_scope: DOWNLOAD_CORRECTNESS_REMEDIATION
production_counts_before: P0=0 / P1=0 / P2=0
production_counts_after: P0=0 / P1=0 / P2=1
tooling_counts: P0=0 / P1=0 / P2=0
new_finding_ids: 0
reopened_root: BUG-UPDATER-02
reopened_root_severity: P2
known_good_baseline_status: NOT_CREATED_BLOCKED_BY_OPEN_PRODUCTION_ROOT
repository_wide_clean_claim: NOT_MADE

## Finding

### BUG-UPDATER-02 — REOPENED P2 SAME-ROOT RESIDUAL

The repaired updater contract makes desired source generation, pending mutation
state and committed source/generation result durable authority. Startup relies
on those carriers to decide whether an actual runtime mutation is still needed.

Exact 256a5cf5 source does not keep that runtime provenance destination-local
across backup/restore:

1. UpdateUtil stores:
   - ytdlp_source / ytdlp_source_label;
   - ytdlp_source_generation;
   - ytdlp_committed_source_generation;
   - ytdlp_committed_source;
   - ytdlp_committed_result;
   - ytdlp_pending_source_generation;
   - ytdlp_pending_source.
2. BackupSettingsUtil.isPortablePreferenceKey has no updater-runtime exclusion.
   backupSettings therefore exports all of those keys.
3. BackupRestoreParser uses the same portability predicate for legacy/current
   payload filtering, so those foreign provenance keys are accepted on import.
4. RestoreTransactionCoordinator Reset clears portable settings, preserves only
   non-portable destination state, then publishes imported portable settings.
   Because the updater provenance keys are currently portable, foreign
   desired-generation/committed/pending carriers replace destination authority.
   Post-commit reconciliation has no UpdateUtil runtime-provenance repair.
5. MainActivity startup calls UpdateUtil.updateOnStartup. With automatic updates
   disabled, UpdateUtil returns ALREADY_UP_TO_DATE before native mutation when
   committedMatches(desired) is true and no pending marker exists.
   committedMatches reads only the imported SharedPreferences carriers; it does
   not prove that the destination runtime was actually installed under them.

Concrete production counterexample:
- backup source device: desired nightly generation 5; committed nightly
  generation 5/result; no pending marker;
- destination device: actual runtime remains stable/other;
- Reset restore imports the source updater carriers but does not mutate the
  destination yt-dlp runtime;
- auto_update_ytdlp=false and no active/queued download;
- startup sees desired==committed and pending absent, returns
  ALREADY_UP_TO_DATE, and never calls the native updater.

The destination therefore accepts source-device committed runtime provenance as
proof of its own runtime. This violates BUG-UPDATER-02's existing invariant
rather than creating a new semantic root.

## Narrow correction contract

User intent may remain portable:
- ytdlp_source;
- ytdlp_source_label.

Destination runtime/order authority must not be portable:
- ytdlp_source_generation;
- ytdlp_committed_source_generation;
- ytdlp_committed_source;
- ytdlp_committed_result;
- ytdlp_pending_source_generation;
- ytdlp_pending_source.

Required semantics:
- new backups omit those destination-local updater authority keys;
- legacy/current backup input filters them too;
- restore must not import a foreign generation/pending/committed carrier;
- applying a restored source must use destination-local ordering. If the
  restored desired source differs, advance/rebind the local desired generation
  and invalidate incompatible local committed/pending provenance before
  startup can use it;
- if the restored source is unchanged, valid destination-local committed
  provenance may remain valid; do not destroy it merely because settings were
  restored;
- Merge and Reset must converge to the same authority rule;
- post-restore startup with automatic updates disabled must still reconcile a
  changed restored desired source rather than accepting foreign committed
  provenance;
- preserve RestoreMutationAdmission and restore journal/recovery semantics.

Required regression coverage:
- cross-device source/committed counterexample with auto_update_ytdlp=false;
- legacy payload carrying updater provenance is filtered;
- new backup omits updater authority carriers;
- changed restored source forces destination-local mismatch/reconciliation;
- same-source restore preserves valid destination-local committed provenance;
- stale pending/committed source-device carriers never suppress destination
  reconciliation;
- ordinary manual updater source selection generation/SUPERSEDED semantics
  remain unchanged.

## Trigger/module status

Module C external representation / backup portability: TRIGGERED / FAIL.
Module F persisted generation/provenance compatibility: TRIGGERED / FAIL.
Updater producer -> preference carrier -> restore consumer -> startup final
effect: TRIGGERED / FAIL, same-root BUG-UPDATER-02 residual.

Other refreshed propagation checks:
- Download authority typed read failure -> recovery final effect: PASS.
- LocalAdd UUID pending enumeration -> notification/MainActivity/History
  consumers -> exact retirement: PASS.
- cookie projection Ready/Unavailable -> acquisition result -> yt-dlp/Terminal
  request consumers: PASS.
- Pause-All snapshot/execution authority -> exact worker cancellation: PASS.
- scheduler capability -> supported Android consumers: PASS.
- History duplicate hint -> transactional identity revalidation/deletion: PASS.
- History video-folder migration -> reference CAS/source retirement: PASS.
- Terminal durable protection classifier -> AppCache deletion: PASS.
- Resume/Retry exact identity -> PendingIntent -> ResumeActivity consumer: PASS.
- bundled FFmpeg typed availability -> hard-sub consumer: PASS.
- tooling exact-source/launcher provenance -> verifier/Complete-Wave boundary:
  PASS source review.
No second semantic root was established.

## L1-L6 coverage

L1 Durability & recovery: BASELINE
L2 Identity & provenance: BASELINE
L3 Concurrency & authority: BASELINE
L4 Destructive ownership: BASELINE
L5 Platform contract closure: prior same-SHA DEEP checkpoint exists; not reused as this run's primary
L6 Cross-feature semantic propagation: DEEP / FAIL due BUG-UPDATER-02 residual

primary_deep_lens: L6 Cross-feature semantic propagation
primary_deep_selection_reason: R1/R3 — repaired durable authority contracts feed
backup/restore, startup, UI and worker consumers; the updater residual was
visible only after following its provenance across those feature boundaries.

remaining_not_yet_deep_after_this_run: L1,L2,L3,L4
next_lens_hint_after_remediation: L1 Durability & recovery

## Final-heavy reconciliation

The H1 classification at exact 256a5cf5 remains historical evidence:
694 JVM tests PASS / 0 failure / BUILD SUCCESSFUL, with exit-code metadata null
preserved as UNKNOWN.

H2-H6 continuation was persisted but not started when this residual was found.
Because production source correction is now required, that verification-only
continuation is superseded before execution. H1 cannot close a future corrected
SHA; final-heavy verification must be re-pinned after the correction is
published and independently reviewed.

INDEPENDENT EXECUTION: NOT EXECUTED
