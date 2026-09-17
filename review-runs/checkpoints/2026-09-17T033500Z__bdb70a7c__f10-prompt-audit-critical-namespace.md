# Independent prompt audit — F10 restore consumer remediation

- exact implementation SHA: `bdb70a7c1b79d1f14347aee55fa726f9c845380b`
- governing plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- checklist v6 blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`

## Verdict
The previously prepared Luna prompt is incomplete and should not be sent unchanged.

`BUG-CLEANUP-01/F10` remains `P2 / OPEN / NOT_CLEAN`; canonical count delta remains zero.

## Missing requirement 1 — imported cache_path admission semantics
Folder picker validates a cache destination with `FileUtil.isSupportedCachePathSelection(...)` before persisting it. Generic settings restore does not. If `cache_path` remains portable, restore must consume the same supported-path/effective-root contract; an imported raw/SAF string is not automatically an admissible native cache root. Prefer destination-local/non-portable semantics unless repository history/tests establish a portable contract. Reset restore without a cache_path payload still changes the effective root through `clear()`, so that case must be covered explicitly.

## Missing requirement 2 — legacy cleanup critical namespace is also a generic restore consumer
The default SharedPreferences may still be the migration source while the dedicated cleanup critical store is uninitialized or its first durable migration commit has failed. `criticalPreferencesOrNull()` copies the legacy critical namespace into the dedicated store and only then marks it initialized.

Generic settings backup/restore currently treats default-preference keys as portable unless explicitly excluded. This includes the cleanup cadence mirror and can include legacy cleanup critical keys such as generation, anchor, pending/active debt, effect phases and effect journal. Reset restore calls `clear()` before replaying settings; Merge restore can raw-write imported values.

Therefore, while critical-store migration is incomplete, Merge/Reset restore can mutate or erase the very legacy authority snapshot that a later migration retry will consume. That can import backup-local generation/debt/journal identities or disable/change cadence without the coordinator transition protocol.

Required remediation must protect the complete legacy F10 critical namespace until dedicated-store initialization is durably complete. Raw generation/debt/effect-journal state must never be portable/imported. The cadence mirror must not become scheduler authority through generic restore. If cadence portability is desired, it must use the coordinator semantic transition at an authorized restore boundary; do not implement the full F11 restore protocol in this F10 wave. A safe narrow policy is to preserve destination coordinator authority and exclude generic cleanup-scheduler critical/mirror keys from raw settings restore, with tests proving Reset `clear()` cannot erase the legacy migration source.

Required migration-window test: seed exact legacy DAILY generation/debt/journal in default prefs; force first dedicated-store migration commit failure; execute Merge and Reset settings restore attempts containing conflicting cleanup keys / clear; release failure and restart/reconcile; prove the dedicated store receives the original legacy exact authority, not imported/cleared values, and no duplicate/new occurrence is invented.

## Missing requirement 3 — no-op and failure semantics
A restore that does not change the effective cache root must not be blocked merely because old-root capture fails. Compare effective/canonical old and target roots before requiring a transition. Conversely, if the effective root would change and capture fails, do not silently report a successful cache-path import unless policy explicitly defines cache_path as destination-local/non-portable. Reset with no cache_path entry must be tested because `clear()` alone changes the effective root.

## Scope boundary
Do not fix global restore atomicity, thumbnail staging, automatic-rule reset ordering, or full post-restore scheduler orchestration here; those remain F11 territory. This wave may touch narrow settings backup/restore filtering/transition code only as needed to close the F10 consumers above.

INDEPENDENT EXECUTION: NOT EXECUTED
