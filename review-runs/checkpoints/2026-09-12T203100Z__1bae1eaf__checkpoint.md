# Independent correctness review checkpoint

- Exact implementation SHA: `1bae1eafea08942f11e6df30e4a13a515dda621c`
- Frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Frozen review-governance SHA at run start: `87293cafed34f24958a842af07ffa3d3285b7100`
- Frozen ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Review checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md`, blob `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Completed scope

- Re-read current `SettingsViewModel.backup()/backupInternal()` from category capture through staging creation, serialization, exact-source publication and returned destination path.
- Re-read current `FileUtil.moveFileWithResult()` exact-source contract across its direct/provider publication boundary.
- Re-read manual backup caller lifecycle in `MainSettingsFragment`; the caller launches a coroutine for every accepted backup action and does not establish an application-wide single-flight backup authority.
- Re-read current restore-thumbnail staging, destination History-ID allocation, destination-local thumbnail publication and DB binding.
- Re-read Master Plan F4 (`BUG-BACKUP-04`) and F5 (`BUG-BACKUP-02`).

## Provisional blocker state

- P0: 2
- P1: 1
- P2: 29
- No new canonical blocker ID is being added at this checkpoint.

## Confirmed fixed invariants

- `BUG-BACKUP-04` prior sibling-widening residual is fixed in current source: backup publication now passes `sourceFiles = listOf(saveFile)` and consumes an operation-local `FileMoveResult`; an unrelated eligible sibling is no longer intentionally included by this invocation.
- `BUG-BACKUP-02` old-ID deterministic live-thumbnail publication path is source-semantically removed: payloads first use a UUID staging directory, destination History IDs are allocated before publication, live filenames include destination History ID plus UUID, and failed DB binding deletes the just-published file. Canonical closure is not yet claimed because exact-SHA execution evidence remains to be resolved.

## Open candidates / questions

- `BUG-BACKUP-04` remains source-NOT-CLEAN through an operation-identity collision: `backupInternal()` derives the staging filename only from version + wall-clock fields through seconds, explicitly deletes an existing file with that name, and recreates it. Two overlapping backup invocations in the same second can therefore address the same staging pathname. The manually triggered caller can launch multiple backup coroutines and no single-flight/mutex authority has been found in the reviewed path. This can make one invocation delete/replace the other invocation's exact source before publication, despite `sourceFiles=listOf(saveFile)`.
- Need final classification of that collision as an existing `BUG-BACKUP-04` residual rather than a new canonical root. Current evidence favors existing-root residual because F4 already owns final backup write/move correctness and the prior independent review tracked publication identity under this defect.
- Need check exact-SHA execution evidence and focused tests for the fixed F5 source semantics.
- Other remediation commits in the frozen implementation (`BUG-BACKUP-06`, `BUG-BACKUP-08`) are not yet independently closed by this checkpoint.

## Remaining review scope

- Verify current tests do or do not force concurrent same-second backup staging collision.
- Verify exact-SHA commit statuses/workflow evidence and any repository-recorded external execution evidence.
- Recount canonical blockers without treating source fixes or test source as executed evidence.
- Final fresh branch-head recount and final checkpoint.

## Exact upstream semantic basis used

- Master Plan F4/F5 at `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- v6 checklist blob `7b553328dfcd9941d783658f49ecb16c71b98c56`: exact identity/provenance, sibling isolation, final mutation-boundary authority, consumer/effect closure and concurrency review.
- Kotlin stdlib `File.writeText`: existing target content is overwritten.
- Java `File.createNewFile()`: existence-check + creation is atomic only for that create operation; Oracle explicitly warns it is not a file-lock protocol. This does not serialize two broader backup operations that deliberately delete an existing same-name file before recreating it.
