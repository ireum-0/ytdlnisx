# F4 / BUG-BACKUP-04 — exact publication identity addendum

- Reviewed implementation SHA: `909d6e68a12e84b78455ec600bdc7a9b1727e0e2`
- Review base remains: `a12c58055fff51b104f8b56fd53b534b8d7e5df4`
- Parent review checkpoint: `360ea02f2c3eede2363e72c8b1ed0f95cdcdf4a7`
- Finding: existing P1 `BUG-BACKUP-04`
- Count delta: `0`
- Canonical count remains **P0 2 / P1 1 / P2 29**
- CLEAN basis remains `a12c58055fff51b104f8b56fd53b534b8d7e5df4`

## Additional exact-source residual at the same publication boundary

`SettingsViewModel.backupInternal()` creates one exact `saveFile`, but publishes by calling the legacy `FileUtil.moveFile(originDir = saveFile.parentFile!!, ..., sourceFiles = null)` overload.

With `sourceFiles == null`, `FileUtil.moveFile()` enumerates `originDir.walkTopDown().filter { it.isFile }` and therefore treats every eligible file remaining in the shared `Backups` staging directory as part of the move operation.

This creates an exact-artifact identity problem in addition to the global failure-sidecar race already recorded:

1. an earlier failed/interrupted backup can leave a non-empty staging sibling in `Backups`;
2. a later backup creates a new exact `saveFile` in the same directory;
3. the later `moveFile` enumerates both the stale sibling and the new file;
4. both may be published even though only the new file is the current backup artifact;
5. `movedPaths.firstOrNull()` is not proven to correspond to the current `saveFile` and may return the stale sibling's destination path.

A successful F4 backup result must identify and publish the exact current artifact, not an unproven directory sibling.

## Review-fix consequence

The focused F4 review-fix should bind publication to the exact `saveFile` as well as making failure outcome operation-local. Prefer passing an exact source set (`sourceFiles = listOf(saveFile)`) or an equivalent strict exact-source API rather than moving the entire shared staging directory.

Required regression coverage should include:

- a stale eligible sibling already present in the backup staging directory;
- current backup publishes/returns only the current exact artifact;
- partial failure for the current artifact cannot be hidden by a successful sibling output;
- concurrent unrelated move operations cannot erase, replace, or donate publication outcome to the current backup.

This addendum is a subcase of the same existing F4 publication correctness root, not a new blocker.

INDEPENDENT EXECUTION: NOT EXECUTED