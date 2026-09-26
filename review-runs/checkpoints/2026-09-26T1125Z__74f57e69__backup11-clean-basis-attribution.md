# CLEAN-basis exploratory review — BUG-BACKUP-11 attribution refinement

checkpoint_kind: EXPLORATORY_REVIEW
review_mode: implementation_diff_frozen_clean_basis
review_parent_sha: `ed4ef3cc32d71c56737eef7f80ec5a8d5c1f98dd`

reviewed_implementation_sha: `74f57e695db30b701ad429af311c39a763bfe086`
clean_review_basis: `74f57e695db30b701ad429af311c39a763bfe086`

current_remote_implementation_at_prewrite:
`41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a`

implementation_agent_currently_working: YES
active_implementation_start_head:
`41ef1c5a33a248f50e6b579c70ae1aee5aea9c0a`

Active-wave commits/diffs were not inspected.

governing_ledger: `b98d315006fa19fc6f22b017f43a91899db5fb81`
governing_checklist: v7
governing_checklist_blob: `e758358ff6d8952470ef3b07f5b18fb26ed4c05c`
lens_policy_blob: `49600871d632fd8612bbabec80dfaa996afb54d3`
protocol_blob: `c6cac5f3d7ad10dddb68343f6684e95ae915366c`

## Independent verdict

Overall workflow remains **NOT_CLEAN**.

This exploratory review does not create a new finding ID and does not change canonical totals.

It independently confirms that provisional P2 BUG-BACKUP-11 was already present at the
independently CLEAN basis `74f57e695db30b701ad429af311c39a763bfe086`.

Attribution refinement:
- BUG-BACKUP-11 is proven present no later than 74f57e69;
- later Terminal provider-binding changes did not introduce the backup/settings authority root;
- exact introducing commit remains **NOT_VERIFIED**.

## Findings

### Existing P2 BUG-BACKUP-11 — confirmed at CLEAN_REVIEW_BASIS

Invariant:
portable settings restore must not recreate executable external provider authority from a serialized
locator alone when destination-side authorization is not independently established.

Exact 74f57e69 production path:

1. `FolderSettingsFragment` configures `command_path` through
   `ACTION_OPEN_DOCUMENT_TREE`.
2. The normal command-folder result path calls `takePersistableUriPermission` before storing the
   selected `command_path`.
3. `BackupSettingsUtil.nonPortablePreferenceKeys` explicitly contains `cache_path`, but not
   `command_path`.
4. `backupSettings()` therefore serializes provider-backed `command_path` as an ordinary portable
   String setting.
5. Merge restore in `SettingsViewModel` filters only through
   `BackupSettingsUtil.isPortablePreferenceKey()` and writes String values with
   `putString(key, prefValue)`.
6. Reset restore in `RestoreTransactionCoordinator.publishPreferences()` likewise preserves only
   keys classified non-portable, then applies admitted portable settings with `putPortable()`;
   String values are committed with `editor.putString(item.key, item.value)`.
7. Neither restore writer repeats the Folder-picker grant acquisition step or requires proof of an
   equivalent destination-side persisted write permission.
8. At 74f57e69, `TerminalCommandPlanFactory.create()` reads `command_path` directly as the
   Terminal destination configuration.
9. For cached Terminal execution, the worker stages output under its app cache and later calls
   `FileUtil.moveFile(..., destDir = downloadLocation)` with that restored locator.
10. Provider publication resolves the destination later through DocumentFile/provider state. A
    restore can therefore successfully commit a provider locator whose execution authority was
    never established by the restore itself.

The durable preference commit is real, but it proves only string persistence, not provider grant
provenance.

### Distinctness

This remains the existing BUG-BACKUP-11 root.

It is distinct from:
- BUG-BACKUP-08, which concerns preference value-type round-trip support;
- BUG-BACKUP-10, which concerns preference commit-result propagation;
- cache-path portability, because `cache_path` is already explicitly destination-local;
- later Terminal generation/provenance findings, because this root exists upstream at backup/restore
  configuration authority.

No duplicate finding ID is created.

## Trigger map

### Module C — External representation / authority projection
**TRIGGERED / FAIL**

The backup artifact preserves a provider locator but not the destination-side authorization
dimension that made that locator usable.

### Module H — Persisted executable configuration fan-out
**TRIGGERED / FAIL**

The restored `command_path` is persistent configuration later interpreted by Terminal planning and
publication. Restore validates/persists its type and value, but not the external authority required
for that value to be executable.

### Platform capability/admission
**TRIGGERED / FAIL for this root**

The normal producer establishes a persistable URI permission separately from the stored preference.
The restore producer writes only the preference representation.

### Semantic-contract delta / consumer closure
No new semantic-contract delta was introduced by this exploratory run. The run re-proves the
already-canonical producer/consumer mismatch on an earlier implementation basis.

## L1-L6 review

### L1 — Durability & recovery
**BASELINE**

The defective state is durably committed. This is not a missing-write problem: restore can
successfully persist a semantically incomplete external-authority configuration.

### L2 — Identity & provenance
**BASELINE**

Exact URI/string equality is not provider-grant provenance. The missing dimension is destination
authorization, not locator identity.

### L3 — Concurrency & authority
**BASELINE**

No race is required. A fully serialized successful restore can commit an unproved provider
locator.

### L4 — Destructive ownership
**BASELINE**

No new destructive-root evidence was found. The primary impact is invalid executable destination
authority and later publication failure, not independently proven deletion/data loss.

### L5 — Platform contract closure
**BASELINE**

Normal configuration crosses Android SAF authorization before persisting the value. Backup/restore
does not reconstruct or prove that capability boundary.

### L6 — Cross-feature semantic propagation
**DEEP**

Producer/consumer graph reviewed at 74f57e69:
Folder picker + persisted grant
-> SharedPreferences command_path
-> settings backup serializer
-> merge/reset restore writer
-> Terminal planner
-> cached native output
-> provider publication.

The missing authorization dimension survives unchanged through that graph.

Primary DEEP lens:
**L6 Cross-feature semantic propagation**

Selection reason:
R1 — the root is an upstream producer/consumer mismatch across backup/restore and Terminal.
R2 — Module C and Module H are both directly triggered and fail on that propagation.

## Review retrospective

The later first-discovery checkpoint correctly classified BUG-BACKUP-11 as a backup/settings
portability root rather than a Terminal provider-binding regression.

Reviewing the earlier CLEAN basis proves that attribution more strongly: the source/destination
authorization mismatch already existed before the later current-format Terminal materializer and
generation work.

The historical 74f57e69 Terminal planner also had provider/raw representation behavior that was
subsequently covered by separate Terminal remediation. This exploratory run does not create or
recount that as a new backup finding.

## Checklist evolution

No checklist change is proposed.

Checklist v7 Module C, Module H, identity/provenance review, and L6 producer/consumer traversal are
sufficient to detect the root.

## Checkpoint summary

- basis: `74f57e695db30b701ad429af311c39a763bfe086`;
- active implementation wave remained frozen from inspection;
- BUG-BACKUP-11 confirmed present at the CLEAN basis;
- exact introduction remains NOT_VERIFIED;
- no new finding ID;
- no canonical count change;
- overall workflow remains NOT_CLEAN;
- independent execution: NOT EXECUTED.
