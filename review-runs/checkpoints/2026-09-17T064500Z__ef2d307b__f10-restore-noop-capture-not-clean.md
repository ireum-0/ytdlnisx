# Independent correctness review — F10 restore no-op capture residual

- exact implementation SHA: `ef2d307b4e2c058719af5bb91623859fb3f9abce`
- implementation parent: `bdb70a7c1b79d1f14347aee55fa726f9c845380b`
- frozen Master Plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- frozen Master Plan SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- frozen ledger ref-only SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- Checklist v6 commit: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Verdict

`F10 / BUG-CLEANUP-01: P2 / OPEN / NOT_CLEAN`.

Canonical count delta: `0`.

This is another consumer-closure subcase of the already-counted F10 cache-root semantic contract. It supersedes the F10 source-semantically-FIXED statement in the prior ef2d final checkpoint; the separate existing roots recorded there are unaffected.

CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

## Git verification

Implementation branch `checkpoint/pre-baseline-review` was independently verified at exact `ef2d307b4e2c058719af5bb91623859fb3f9abce`.

Its parent is exact `bdb70a7c1b79d1f14347aee55fa726f9c845380b`.

Compare from bdb70a7c: ahead 1 / behind 0 / total commits 1.

Commit message:

`fix: protect cleanup state during settings restore`

Trailer:

`Defect-ID: BUG-CLEANUP-01`

## Accepted ef2d corrections — preserve

The following ef2d source changes are accepted:

- `cache_path` is explicitly destination-local/non-portable in `BackupSettingsUtil`;
- generic restore ignores imported/legacy backup `cache_path` values;
- Reset preserves the destination-local raw `cache_path` in the same default-SharedPreferences editor transaction;
- generic backup/restore excludes the cleanup coordinator's canonical critical namespace via `CleanupScheduleCoordinator.isCoordinatorOwnedPreferenceKey`;
- raw imported cadence/generation/debt/occurrence/effect-phase/effect-journal/migration-version values cannot become coordinator authority;
- Reset calls `CleanupScheduleCoordinator.prepareForSettingsReset()` before generic default-preference clear;
- that barrier runs under the coordinator destructive-effect mutex and requires the dedicated critical store to be durably initialized;
- failed critical-store migration therefore prevents Reset clear from erasing the unresolved legacy authority;
- the prior process-visible-vs-restart-durable critical-state fence remains preserved;
- prior cache journal/root binding, newer-owner protection, D1/D2, exact Room revalidation, scheduling, replay, and exactly-one-successor semantics remain source-preserved.

## Confirmed remaining P2 — restore requires cache-root capture despite there being no cache-root transition

The ef2d policy makes `cache_path` destination-local.

Production restore now filters `cache_path` through `BackupSettingsUtil.isPortablePreferenceKey`, so Merge never writes an imported cache path.

Reset also filters imported cache paths and preserves the destination-local `cache_path` in the same SharedPreferences editor transaction that performs `clear()`.

Therefore neither Merge nor Reset performs a semantic cache-root transition under the selected ef2d policy.

However `SettingsViewModel.restoreData()` currently does this whenever `data.settings != null`:

1. calls `DownloadCacheOwnership.captureEffectiveRootBeforePreferenceMutation(context)`;
2. requires that call to return true using `check(...)`;
3. only then snapshots the existing raw `cache_path` and continues restore.

Thus an inability to enumerate/bind the current cache root aborts an otherwise unrelated settings restore even though restore cannot change the cache root.

Concrete production sequence:

1. destination cache root remains R1;
2. imported backup contains only an ordinary portable setting and no effective cache-root mutation is permitted by policy;
3. current root capture returns false because the current root cannot be inspected/bound at that moment (for example a directory/marker read/listing failure);
4. `check(...)` throws before ordinary settings restore begins;
5. `restoreData()` returns false;
6. the unrelated portable setting is not restored even though retaining R1 required no transition and no cleanup locator was at risk.

This is exactly the no-op case the review-fix boundary required not to turn into a false failure.

The new Android regression `settingsRestoreRefusesMergeAndResetWhenCacheRootCaptureFails` encodes the incorrect expectation by requiring both Merge and Reset to fail under the capture-failure seam even though `cache_path` is now destination-local.

The safety gate is legitimate only for a production operation that can actually make the old effective root undiscoverable. Under ef2d restore semantics, generic settings restore is no longer such an operation.

## Required correction boundary

Do not re-portabilize `cache_path`.

Preserve the destination-local policy.

Do not weaken Folder Settings picker/Reset/default-initialization capture, because those paths can actually mutate/default the cache-path preference.

For generic settings restore:

- do not require old-root capture when the selected destination-local policy guarantees the effective cache root is retained;
- Merge of ordinary portable settings must not depend on cache-root inspection;
- Reset must preserve the local cache-path value atomically across generic clear without requiring an irrelevant transition capture;
- imported legacy `cache_path` must remain ignored;
- unresolved cleanup critical-store migration must still block Reset through `prepareForSettingsReset()`;
- coordinator critical namespace filtering must remain canonical and unchanged.

If implementation instead proves a real restore path still changes the effective cache root, identify that exact production mutation and gate only that transition; do not keep a blanket `settings != null` capture gate.

## Required regression coverage

1. Merge with destination `cache_path = R1`, ordinary portable imported setting, and forced cache-root capture failure: Merge succeeds for the ordinary setting and R1 remains unchanged.
2. Reset with destination `cache_path = R1`, forced cache-root capture failure, and ordinary settings payload: Reset preserves R1 and is governed by the cleanup-critical migration barrier, not by an irrelevant root-transition barrier.
3. Legacy imported `cache_path = R2` remains ignored for Merge and Reset; destination R1 remains unchanged without requiring R1 transition capture.
4. Reset with no imported `cache_path` preserves R1.
5. Folder picker and Folder-screen Reset still fail closed when their real old-root capture fails.
6. Existing pre-binding R1 / legacy ID-only journal / restart discovery coverage remains green; removing the restore no-op capture must not remove Folder transition capture or current Download root binding.
7. Failed critical-store migration still blocks destructive Reset clear and preserves the original legacy cleanup authority.

## Persisted-generation compatibility

The stronger root-binding and dedicated-critical-store contracts still trigger Checklist v6 persisted-generation review. Do not treat every intermediate remediation commit as a shipped generation. No additional blocker/count is created here without evidence of a concrete unsupported persisted generation.

## Execution gate

Implementation-side report at ef2d307b:

- `git diff --check`: PASS;
- committed-range diff check: PASS;
- `:app:compileDebugKotlin -x lint`: PASS;
- `:app:compileDebugAndroidTestKotlin`: PASS;
- focused BackupSettingsUtilTest: PASS;
- focused CleanupSchedulePolicyTest: PASS;
- focused DownloadCacheOwnershipTest: PASS;
- new Android production-wiring tests: compiled, NOT EXECUTED;
- `F10 instrumentation: NOT EXECUTED — DEVICE UNAVAILABLE`.

Independent GitHub evidence at exact ef2d307b:

- combined status contexts: 0;
- associated GitHub Actions workflow runs: 0.

Required actual production-wiring execution therefore remains open even after source remediation.

## Separate roots

The separate existing roots recorded by the current canonical inventory remain separate. This checkpoint changes only the disposition of F10 at ef2d307b from source-fixed back to OPEN due to the confirmed restore no-op capture residual.

F11 / `BUG-BACKUP-03` remains blocked until F10 source review and the required F10 execution gate are actually closed.

INDEPENDENT EXECUTION: NOT EXECUTED
