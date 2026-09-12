# Independent correctness review final checkpoint

- Exact implementation SHA: `1bae1eafea08942f11e6df30e4a13a515dda621c`
- Frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review-governance SHA at run start: `87293cafed34f24958a842af07ffa3d3285b7100`
- Frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Review checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Review completed

- Full current backup path: selected-category capture -> operation staging pathname -> serialization -> exact `sourceFiles` publication -> direct/provider move outcome -> returned path -> manual caller result.
- Full current restore-thumbnail path: payload validation -> UUID staging -> destination History identity allocation -> destination-local UUID publication -> DB binding -> failure cleanup.
- Reviewed current manual backup caller for overlapping invocation authority.
- Reviewed current focused production-wiring test source; tests are evidence artifacts only, not a substitute for source-semantic review.
- Checked exact implementation SHA GitHub status/workflow evidence: no commit status contexts and no associated workflow runs were found.

## Provisional/final blocker recount

- P0: 2
- P1: 1
- P2: 29
- Canonical count change from previous user-visible review: none.

## Confirmed fixed invariants

### `BUG-BACKUP-04` partial source progress

The prior sibling-widening residual is fixed. `backupInternal()` passes only its `saveFile` through `sourceFiles = listOf(saveFile)` to an operation-local `moveFileWithResult()` and treats that invocation's structured failures as its publication result. A stale sibling in the shared staging directory is no longer deliberately swept into the current publication.

### `BUG-BACKUP-02` source-semantic fix

The historical old-backup-ID alias path is removed in current source:

- restore payload bytes first enter a UUID-scoped staging directory;
- staging uses create-new semantics and is not a live History reference;
- restored History is inserted with `id = 0` and `customThumb = ""`;
- the destination History ID is obtained before live thumbnail publication;
- live file name uses `history_<destinationId>_<UUID>.<ext>` with create-new reservation;
- only after publication does the DAO bind that exact path to the destination History row;
- binding failure deletes the just-published file;
- staging is cleaned after the restore attempt.

Canonical P2 closure is not claimed because exact-SHA actual-execution evidence is absent in GitHub status/workflow data and this reviewer did not independently execute instrumentation/device tests.

## Open finding / candidate

### `BUG-BACKUP-04` — existing P1 remains OPEN

Current staging identity is still not operation-unique. `backupInternal()` constructs the source pathname from app version plus `Calendar` fields only through seconds. If that path already exists it explicitly deletes it, then calls `createNewFile()`, writes JSON and later publishes that pathname. The manual settings caller launches backup in a lifecycle coroutine and no reviewed path establishes a process-wide single-flight backup authority.

Concrete interleaving:

1. backup A and backup B begin within the same wall-clock second, yielding the same `saveFile` pathname;
2. A creates its staging file;
3. B observes the same pathname as existing and deletes it, then recreates that pathname;
4. B writes its own artifact to the pathname;
5. A reaches publication with its `File` object, which names that same pathname, and can therefore publish B's artifact or fail based on B's lifecycle rather than A's exact artifact;
6. A's returned success/failure is no longer necessarily attributable to A's captured payload.

This is classified as a residual subcase of existing `BUG-BACKUP-04`, not a new canonical finding: F4 already owns final backup write/move correctness and the established review history treats exact publication identity as part of the same defect closure. The blocker count therefore does not increase.

The new `publicationUsesOnlyTheCurrentBackupArtifact` test covers a pre-existing stale sibling but does not force two same-second overlapping backup invocations. The new move-result test covers operation-local failure-result isolation, not shared staging-path identity.

## Remaining / NOT_VERIFIED

- Runtime manifestation of the same-second collision: `NOT_VERIFIED` by execution in this independent review; source interleaving is sufficient to keep the existing P1 open.
- Exact-SHA execution closure for the source-fixed `BUG-BACKUP-02`: `NOT_VERIFIED`.
- `BUG-BACKUP-06` and `BUG-BACKUP-08` commits present in the frozen implementation were not promoted to independent CLEAN by this review; their canonical prior states remain unchanged unless separately established by governance evidence.

## Exact upstream semantic basis

- Master Plan F4/F5 from exact plan SHA `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- v6 checklist blob `7b553328dfcd9941d783658f49ecb16c71b98c56`, especially complete current production tracing, exact identity/provenance, sibling isolation, concurrency/interleaving review, final mutation-boundary authority, consumer/effect closure, and actual-execution separation.
- Kotlin stdlib `File.writeText`: an existing file's content is overwritten.
- Oracle Java `File.createNewFile()`: file existence check + creation are atomic for the single create operation, but the API is explicitly not a file-lock protocol. It does not serialize the surrounding delete/write/publish transaction across two backup invocations.

## Final disposition before verdict

- `BUG-BACKUP-04`: EXISTING / P1 / OPEN. Prior sibling-publication residual fixed; same-second overlapping staging identity collision remains.
- `BUG-BACKUP-02`: EXISTING / P2 / source-semantic FIXED / canonical OPEN / execution NOT_VERIFIED.
- New canonical P0/P1/P2 findings: none.
- Overall gate: `NOT_CLEAN — P0 2 / P1 1 / P2 29`.
