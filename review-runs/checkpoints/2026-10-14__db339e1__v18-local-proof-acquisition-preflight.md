# V18 unpublished-local evidence acquisition — bounded independent reviewer preflight

date: 2026-10-14_KST
checkpoint_kind: V18_READ_ONLY_PROOF_ACQUISITION_SCOPE_AND_BOUNDARY
checkpoint_status: FINAL
manual_review_run: NO
manual_review_run_status: NOT_APPLICABLE
expected_review_parent: 66866dd72e1c92351f321850d09e3bbb29c9308c
pinned_implementation_sha: db339e1e90392cfd994e2d42bca7ce88afb3be21
applicable_protocol_blob: d136b46290cf259ea07f9a81706f25d89ea72641
governing_v18_stop_review_blob: 422c253d1f49ef437c946b4ffd3ab403a91f52d1
governing_v18_report_blob: 8a9ebae0de209f494e53887ee5f32c165fba113f
governing_c1_c5_contract_blob: 5738c9756fda5d24b06e224a49ac6ee9986f8658
pinned_plan_tip: 8491528b730abea17de22013bca4288e1549a39e
pinned_ledger_tip: 50f43b4710a0865fd2a79d779186ce252cc9ff7f
source_mutation_authorized_by_this_checkpoint: NO
device_install_test_authorized_by_this_checkpoint: NO
prompt_role_proposed: BOUNDED_READ_ONLY_LOCAL_V18_EVIDENCE_ACQUISITION
evidence_pub_scope_proposed: PRIVATE_FULL_REPORT_APPEND_ONLY_ONLY
canonical_active_p2_open: 6
canonical_active_p2_ambiguous: 0
canonical_root_delta: 0
review_basis: NO_REPOSITORY_WIDE_CLEAN
independent_execution: NOT_EXECUTED

## GitHub-source assertions actually rechecked

At the fixed remote implementation db339e1, `app/src/main/java/com/ireum/ytdl/database/DBManager.kt` blob `3ac3dc3b64afe84f4ec5a11924441bea6f6123b1` declares `version = 63` at line 100 and migration registration at line 173; `Migrations.kt` blob `969233a246b92aada763878abdefa8e5306c23f2` has no published 63->64 or 64->65 migration. `ResultDao.kt` blob `d6c22f6fd6c77f107dc134e7b189269163ac83df` has `@Insert(REPLACE)` at lines 36-40 and `@Update(REPLACE)` at lines 50-51. `DownloadDao.kt` blob `e0b260e5d23f5d1f3b10fc51880bbbc5465ee9c1` has direct download status mutation paths and `insertRaw(REPLACE)` around lines 177-184, 234-237 and 524-540, all relevant producer/revoker obligations to inspect, not newly discovered root counts. There are no published `work/FormatAuthority.kt`, `work/FormatBatchHandoff.kt` or schema `DBManager/64.json` artifacts at this pinned SHA. This only establishes the PUBLISHED baseline; it does not disprove agent-reported unpublished local draft changes.

The V18 private Full Report (blob `8a9ebae0de209f494e53887ee5f32c165fba113f`) describes 32 currently present candidate files, 26 intentionally changed paths, a local-only manifest SHA256 `0649942c1615a54a9bf755f9dc274d10e838a9da4aa022f69d3595c11c8adb72` and original 17-file source and index preservation. These hashes and file bodies cannot be independently inspected through the published GitHub implementation SHA. The old protected draft64 export SHA256 `7ed06954b01f466497ba2f67b72f592a54134a4c76bd13b92e28be07ce691b14` and 63->64 migration are likewise agent-attested local-only artifacts, not the published Room64 contract.

## Next bounded evidence acquisition

The reviewer can reach a concrete decision only if the exact local candidate and its producer-to-effect closure claims are exposed in immutable, sanitized read-only evidence with verifiable identities. Authorize a NEW evidence-only/diagnostic agent task (after separate user go-ahead and persisted-prompt preflight), without any production/source/test/schema/index/device modifications. It should:
1. Revalidate exact protected worktree identity, 17 pre-edit preserved snapshots, 32 current-path SHA256 manifest and physical empty index; report discrepancies without overwrite.
2. Produce a precise path-by-path original-versus-current and published-db339e1-versus-current provenance matrix for all 26 intentional paths; include reviewable source-level changed hunks in a sanitized immutable private Full Report when within report limits. Never claim the reviewer independently verified local bytes merely by accepting the agent report. If complete hunks are too large for the report, give hash-anchored bounded excerpts and mark the unshared diff portions NOT_VERIFIED; do not publish raw private databases, credentials or APKs.
3. Enumerate actual current local M(D)/C(K) creation/invalidation for Download, Result, foreground UI, requeue, Restore, direct SQL, full-row REPLACE, retry/recovery; show concrete old H1/new H2 and Result cache collision/refusal traces using real lines and exact persistent columns/triggers. No source edits to force a proof.
4. Specify one durable old non-authoritative WorkManager/legacy debt discovery and wakeup owner, each genuinely owed outcome, fixed-point convergence under restart/Restore, deduplication and no false success; distinguish PRESENT, MISSING or NOT_PROVEN in the exact current draft. A DESIGN PROPOSAL must not be recorded as implemented.
5. Compare ORIGINAL64 exported SHA and 63->64 body to the saved originals; show 64->65 generated-export status, DDL/trigger registration, migration coverage, old-version 61/63/64 fixture-state provenance and whether tests really executed. Never run Gradle through blocked wrapper, change execution policies, modify user data or assert a model equals Room verification.
6. Inspect the isolated read-only native Gradle distribution/cache evidence only if needed to classify the previous wrapper failure, with no unapproved launcher switch/download/policy bypass/retry.

Final evidence should be the independently distinguishable local-source proof packet and a mandatory sanitized append-only Full Report under `ireum-0/private:ytdlnisx-review/ytdlnisx/reports/`, with exact private commit/blob readback. Reviewer may then inspect the actual immutable report and decide if a source correction design is sufficiently authorized; this checkpoint does not authorize a production implementation, re-run V18 or claim any CLOSED root.

INDEPENDENT EXECUTION: NOT EXECUTED
