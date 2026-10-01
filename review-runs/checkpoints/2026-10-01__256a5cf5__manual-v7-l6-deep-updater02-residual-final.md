# Manual correctness review — 256a5cf5 — L6 final

manual_review_run: YES
manual_review_run_status: FINAL
manual_review_start_parent: 38a0c32e01562a943d99e375780adfcdadd161e6
review_parent_sha: 916cf48659257b574ccbf64bd4a13df8b6707ce3
intermediate_checkpoint:
review-runs/checkpoints/2026-10-01__256a5cf5__manual-v7-l6-deep-updater02-residual-intermediate.md
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_tree: acc40abe31e99b78e76056eb0a002b584d00c64c

master_plan_tip: 2145847a1054da28398b730b9be0ca728668f967
master_plan_blob: 507a97c1455793b272298e29f37b945f4cfb55d7
protocol_blob: 3394e14db9f2bf79dcd8c1e3492537c58d4eb933
checklist_v7_adoption: b98d315006fa19fc6f22b017f43a91899db5fb81
checklist_v7_blob: e758358ff6d8952470ef3b07f5b18fb26ed4c05c
lens_policy_adoption: 822ffe6a9cd45b951550fcb559557f0cf0798610
lens_policy_blob: 49600871d632fd8612bbabec80dfaa996afb54d3

## Independent verdict

NOT_CLEAN.

BUG-UPDATER-02 is REOPENED P2 / SAME-ROOT RESIDUAL.

Canonical active-remediation counts:
- P0=0
- P1=0
- P2=1

Tooling counts remain P0=0 / P1=0 / P2=0.
New finding IDs: 0.

Known-Good Baseline remains NOT_CREATED.

## Finding

The durable updater generation/provenance contract is not destination-local
across backup/restore.

Current source stores these updater authority carriers in default
SharedPreferences:
- ytdlp_source_generation
- ytdlp_committed_source_generation
- ytdlp_committed_source
- ytdlp_committed_result
- ytdlp_pending_source_generation
- ytdlp_pending_source

BackupSettingsUtil.isPortablePreferenceKey() does not exclude those keys.
Therefore new backups export them and BackupRestoreParser accepts them from
legacy/current payloads.

Merge restore writes accepted portable settings directly.
Reset restore preserves only preferences classified non-portable, then applies
the imported portable settings. The updater authority carriers above are
therefore replaced by source-device values in both restore modes.

No restore completion path rebinds those carriers to the destination runtime.

UpdateUtil.updateOnStartup(false) calls update() with
skipWhenAlreadyCommitted=true. committedMatches() proves only that imported
SharedPreferences generation/source/result equal the imported desired
generation/source. If no imported pending marker exists, startup returns
ALREADY_UP_TO_DATE without mutating or independently proving the destination
yt-dlp runtime.

Concrete counterexample:
1. backup source device has desired nightly generation 5 and committed nightly
   generation 5, no pending marker;
2. destination actual runtime is stable or otherwise different;
3. restore imports the source updater carriers but does not mutate yt-dlp;
4. automatic updates are disabled;
5. startup observes desired==committed and no pending marker and returns
   ALREADY_UP_TO_DATE.

Foreign committed runtime provenance can therefore suppress destination
reconciliation.

This is the existing BUG-UPDATER-02 invariant failing after propagation through
backup/restore/startup, not a new root.

## Narrow correction

Portable user intent may remain:
- ytdlp_source
- ytdlp_source_label

Destination-local updater authority must not be portable:
- ytdlp_source_generation
- ytdlp_committed_source_generation
- ytdlp_committed_source
- ytdlp_committed_result
- ytdlp_pending_source_generation
- ytdlp_pending_source

Required behavior:
- new backups omit destination-local updater authority;
- legacy/current restore filters it;
- Merge and Reset never import foreign pending/committed generations;
- changed restored source is rebound to destination-local generation ordering
  and incompatible local committed/pending provenance is invalidated before
  startup can accept it;
- unchanged restored source may preserve valid destination-local committed
  provenance;
- post-restore startup with automatic updates disabled still reconciles a
  changed restored source;
- RestoreMutationAdmission and restore journal/recovery semantics remain intact;
- ordinary manual source selection and SUPERSEDED ordering remain unchanged.

Required regression coverage:
- cross-device desired/committed counterexample with auto-update disabled;
- new backup omission;
- legacy payload filtering;
- changed-source Merge and Reset force destination-local reconciliation;
- same-source restore preserves valid local committed provenance;
- stale foreign pending/committed carriers never suppress reconciliation;
- normal updater generation/SUPERSEDED tests remain green.

## Trigger/module status

Module C external representation / backup portability: FAIL.
Module F persisted generation/provenance compatibility: FAIL.
Updater producer -> preference carrier -> restore consumer -> startup final
effect: FAIL / BUG-UPDATER-02.

Other L6 propagation checks remain PASS at source review:
Download authority, LocalAdd result sessions, cookie projection, Pause-All,
scheduler capability, History duplicate cleanup, video-folder migration,
Terminal protection, Resume/Retry identity, bundled FFmpeg availability, and
tooling exact-source/launcher provenance.

No second semantic root established.

## L1-L6 coverage

L1 Durability & recovery: BASELINE
L2 Identity & provenance: BASELINE
L3 Concurrency & authority: BASELINE
L4 Destructive ownership: BASELINE
L5 Platform contract closure: DEEP from prior same-SHA run
L6 Cross-feature semantic propagation: DEEP / FAIL

primary_deep_lens: L6 Cross-feature semantic propagation
primary_deep_selection_reason:
R1/R3 — repaired durable updater authority propagated into backup/restore and
startup consumers, where foreign source-device provenance was incorrectly
accepted as destination runtime proof.

remaining_not_yet_deep_after_this_run:
- L1
- L2
- L3
- L4

next_lens_hint_after_remediation:
L1 Durability & recovery

## Final-heavy reconciliation

H1 remains accepted historical evidence on exact 256a5cf5:
694 PASS / 0 FAIL / 0 SKIP / 0 ERROR, BUILD SUCCESSFUL, exit-code metadata null
preserved as UNKNOWN.

H2-H6 must not continue on 256a5cf5 because production source correction is
now required. The verification continuation is superseded before execution.

After BUG-UPDATER-02 correction is published and independently reviewed,
final-heavy verification must be re-pinned to that new exact SHA. H1 on
256a5cf5 cannot close the corrected SHA.

INDEPENDENT EXECUTION: NOT EXECUTED
