# F4 / BUG-BACKUP-04 — exact-final residual review

## Reviewed state
- Exact implementation HEAD: `f20833d6d74134ec52a33c6cdb4a862784d9c4bf`
- Review base / contiguous independently CLEAN basis entering review: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict
`BUG-BACKUP-04`: **P1 OPEN / NOT_CLEAN**.

The verification-hardening wave supplies the previously missing relational-snapshot and staging-write execution evidence, and the earlier F4 fixes remain present: typed capture failure, transactionally grouped Room capture, required-thumbnail failure, exact-source publication, and invocation-local move outcome.

However, exact-final source still has one blocker-relevant residual under the existing F4 operation-local artifact identity root.

## Residual — same-second staging pathname collision

`SettingsViewModel.backupInternal()` constructs the staging `saveFile` name from app version plus calendar year/month/day/hour/minute/second only. There is no operation-unique component and no serialization/lease covering all backup callers.

The production surface has independently launchable callers, including manual settings backup and automatic/update backup paths. Therefore two backup operations may overlap within the same second.

Concrete sequence:

1. Backup A and backup B compute the same staging pathname.
2. A creates that path.
3. B observes the path, deletes it, and recreates the same pathname.
4. A and B both retain `File` objects naming that same pathname.
5. One `writeText` can overwrite the other operation's bytes.
6. A later exact-source `moveFileWithResult(sourceFiles = listOf(saveFile))` proves only the pathname selected for publication, not that the bytes still belong to that backup operation.
7. One invocation can publish or return an artifact containing the other invocation's payload, while the sibling may fail or operate on a recreated path.

`File.createNewFile()` makes creation atomic for one pathname but does not provide operation ownership after another invocation deletes/recreates that pathname. The exact `File` object is therefore not operation-unique authority.

This is not a new root. It is another subcase of existing P1 `BUG-BACKUP-04`, whose invariant requires the successful artifact to belong to the exact current backup operation.

## Verification-wave assessment

At exact `f20833d6...`:
- the new Room snapshot test forces a competing writer to be released while the backup capture transaction is held and asserts coherent relationship output;
- the new staging-write fault hook is null by default and is used only to inject deterministic failure before the real `writeText` boundary;
- F5/F6 test additions do not change this F4 publication identity issue;
- the reported exact-final instrumentation/JVM/build results are useful execution evidence, but the matrix does not exercise two concurrent backup invocations colliding on the second-granularity staging filename.

## Required correction boundary

Use operation-unique staging identity or an equivalently strong serialization/ownership mechanism so two overlapping backup invocations can never share one mutable staging pathname. Preserve:
- exact-source publication;
- invocation-local `MoveFileResult` failure attribution;
- typed capture failures;
- transactionally coherent related Room snapshots;
- required-thumbnail failure behavior;
- truthful final write/move failure;
- backup format version 3;
- unrelated `FileUtil` behavior.

Add a deterministic regression that runs two backup invocations with deliberately overlapping staging/publication windows and proves each successful result contains its own requested payload and cannot delete/overwrite/publish the sibling's artifact.

## Canonical consequence
- Count delta: `0`.
- Canonical blockers remain `P0 2 / P1 1 / P2 28` before later independent F5/F6 closure decisions in this review sequence.
- Contiguous independently CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- F8/F9 remain blocked on F4.

INDEPENDENT EXECUTION: NOT EXECUTED