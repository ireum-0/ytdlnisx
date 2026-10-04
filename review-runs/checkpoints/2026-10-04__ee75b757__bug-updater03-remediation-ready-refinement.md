# BUG-UPDATER-03 remediation-ready refinement — key-specific restore schema

checkpoint_kind: ACTIVE_WAVE_FROZEN_BASIS_EXPLORATORY_FINAL
checkpoint_status: FINAL
manual_review_run: NO
implementation_agent_currently_working: YES
active_wave_scope: BUILD_ENVIRONMENT_LOCAL_PROPERTIES_STABILIZATION
active_wave_diff_inspected: NO

review_parent_sha: 9ac59c4d401e62171d3f3cdcc9121da38e6a0ee2
clean_review_basis: ee75b75786b8b6182dfc31946b6325b20294e73b
implementation_sha_reviewed: ee75b75786b8b6182dfc31946b6325b20294e73b

verdict: BUG-UPDATER-03 OPEN P2 / CONFIRMED / REMEDIATION-READY_REFINED
canonical_count_change: 0
independent_execution: NOT_EXECUTED

## Refined root definition

BUG-UPDATER-03 is a key-specific restore schema mismatch for the portable executable
configuration key ytdlp_source.

The prior blank-String example remains valid but is only one instance.

BackupRestoreParser.normalize() filters portable settings and then validateSettings()
checks only generic declared type validity:
- String accepts any value;
- Int/Long/Float accept parseable numeric text;
- Boolean accepts true/false;
- StringSet accepts a string array.

There is no ytdlp_source-specific check that requires:
- declared storage type exactly String; and
- stored String value to satisfy UpdateUtil's present-source contract.

Merge and Reset both consume the validated BackupSettingsItem declaration as
authoritative storage type:
- Merge SettingsViewModel.restoreMergeData() writes ytdlp_source through the
  SharedPreferences.Editor method selected by item.type;
- Reset RestoreTransactionCoordinator.putPortable() does the same.

Therefore at least two same-root malformed states are admitted before mutation:
1. ytdlp_source / type=String / value blank or whitespace-only under the authoritative
   consumer's nonblank contract;
2. ytdlp_source / type=Int (or another otherwise generic-valid non-String type) /
   parseable value, which is durably stored under the wrong SharedPreferences type.

UpdateUtil.readDesiredSourceLocked() consumes present ytdlp_source through String
preference semantics and requires a nonblank value. Restore therefore accepts durable
configuration that its authoritative consumer cannot validly consume.

This is one BUG-UPDATER-03 root, not separate value/type findings.

## Exact invariant

Before any Merge or Reset mutation begins, every supported restore representation
that contains ytdlp_source must satisfy the exact storage-and-value contract of the
authoritative consumer:

- absence remains valid and preserves existing default/absence semantics;
- if present, declared type must be exactly String;
- if present, value must be nonblank under the same predicate used by UpdateUtil;
- valid built-in sources remain accepted unchanged;
- valid nonblank custom sources remain accepted unchanged;
- malformed input must be rejected before any preference/database/file mutation.

The restore boundary must not normalize malformed input into another meaning.

## Narrow correction boundary

Put the check in the shared BackupRestoreParser normalization/validation authority so
all supported JSON, legacy, current typed, and programmatic restore paths pass through
one contract before Merge/Reset ownership.

Preferred implementation:
- add one key-specific ytdlp_source branch inside shared settings validation;
- require type == "String" before applying generic type dispatch;
- require the String value to satisfy the same nonblank predicate as UpdateUtil;
- optionally share only a tiny pure validity predicate with UpdateUtil if needed to
  prevent drift; do not move rejection downstream.

Do not broaden generic preference schema validation beyond keys with independently
established contracts.

## Forbidden shortcuts

- accepting wrong declared type and converting it to String;
- converting blank/whitespace-only source to stable;
- deleting malformed ytdlp_source so absence/default semantics hide the input;
- trimming/canonicalizing valid custom source text without an established contract;
- validating only Merge or only Reset;
- validating only format-4 JSON while typed/programmatic or legacy forms bypass it;
- catching ClassCastException/consumer failure after durable restore mutation;
- folding BUG-UPDATER-02 generation/provenance changes into this root.

## Focused acceptance matrix

At minimum cover the shared parser/production restore boundary:

Reject before mutation:
- present ytdlp_source, type=String, value="";
- present ytdlp_source, type=String, whitespace-only value when UpdateUtil rejects it;
- present ytdlp_source, type=Int, value="1";
- representative other generic-valid non-String declaration for ytdlp_source;
- the same malformed schema through current typed/programmatic input;
- oldest still-supported legacy representation, where applicable.

Accept unchanged:
- absent ytdlp_source;
- String stable;
- String nightly;
- String master;
- representative valid nonblank custom source.

Mutation/effect assertions:
- rejected Merge leaves preexisting destination ytdlp_source and unrelated state unchanged;
- rejected Reset does not acquire destructive restore ownership or clear/publish preferences;
- accepted values are stored as String and remain exactly consumable by UpdateUtil;
- generic unrelated String/Int/etc preference behavior remains unchanged.

## Test gap

Existing malformed portable preference coverage proves generic value/type parsing, but it
does not bind ytdlp_source to its authoritative String + nonblank schema.

Future regression coverage must assert both key-specific declared storage type and value,
and must exercise the shared validation path used by Merge and Reset rather than testing a
standalone helper only.

## Disposition

The existing 2026-10-02 BUG-UPDATER-03 remediation-ready checkpoint remains valid but is
refined by this checkpoint. Any future implementation prompt for BUG-UPDATER-03 must include
wrong declared storage type as well as blank String source.

Canonical counts remain P0=0, P1=0, P2=3.

INDEPENDENT EXECUTION: NOT EXECUTED
