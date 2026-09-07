# Hourly Correctness Review Checkpoint

## Fixed review target

- Implementation: `36ba1cac40b961ae876399fc5a9afe01749c5fe3`
- Parent: `6825c9317a34ef4cf47ba9388c83e17b724b96b4`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Review: `8790d4fd88953bcc69d80ce58aef8d7cf35094a3`
- Ledger: `899328bc91e4008e39a658387396a0106c8666ec`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`
- Upstream yt-dlp basis: `2025.11.12` / `5977782142ca7e41240f07202cc9b8dcc087b401`

## Reviewed so far

- Governance refs and v6 checklist identity
- BUG-OUTPUT command/parser policy at current production source
- `--rm-cache-dir` destructive-option rejection
- removal of post-validation `normalizeLegacyShellCommand`
- bundled-option arity / unknown-ambiguous parse certainty
- Android/POSIX Windows-drive path rejection
- Download output provenance
- direct/no-cache exact-move publication
- hard-sub provider publication ordering
- Terminal command plan
- Terminal current-attempt output authority
- Terminal cached publication through `FileUtil.moveFile`
- SAF/MediaStore/direct-filesystem publication branches
- downstream exact `finalPaths` publication path
- current GitHub commit status contexts

## Current provisional findings

### P0
- 0 new/current-review blockers confirmed in the reviewed scope.
- Previously open `--rm-cache-dir` P0 from the cc699 review is source-level fixed in the current checkpoint.

### P1
- 0 new blockers confirmed in the reviewed scope.

### P2
- Existing `BUG-TERMINAL-04` / BUG-OUTPUT publication-recovery obligation remains open at `36ba1cac...`.
- The new Terminal provenance code detects stranded authoritative source files after partial publication and throws, but the non-cancellation catch then recursively deletes the attempt staging root. This destroys the recoverable authoritative remainder that `FileUtil.moveFile` deliberately preserved on partial failure.
- This is not a new canonical defect ID; it is a still-open existing Terminal/output publication finding whose failure mode changed under the new remediation.

## Confirmed fixed invariants

- User-authored `--rm-cache-dir` is rejected by command policy.
- Preflight/execution command representation no longer diverges through `normalizeLegacyShellCommand`.
- Unknown/ambiguous native parse state does not grant later `-P/-o` authority.
- Built-in short-option model no longer treats `-X` as a valid bundled flag.
- Windows drive syntax does not gain Android absolute-path authority.
- Terminal staging uses a per-attempt UUID-scoped directory.
- Terminal output sources require current-attempt yt-dlp output evidence, not directory membership.
- Hard-sub provider publication is staged locally before provider publication.

## Open candidates / questions

- Terminal non-cancellation error cleanup must preserve exact current-attempt staging when publication is partial/ambiguous; determine correction shape without conflating user cancellation cleanup.
- Review existing `BUG-TERMINAL-01/03/05` separately before any broad Terminal CLEAN claim.
- No GitHub commit-status contexts are present for the reviewed SHA; actual execution evidence remains NOT_VERIFIED in this independent run.

## Remaining review scope

- Wider non-BUG-OUTPUT active registry was not exhaustively re-audited in this run.
- No independent local/emulator execution was performed.
- Next run should re-fresh all refs and only compare after fixing the new exact target for that run.
