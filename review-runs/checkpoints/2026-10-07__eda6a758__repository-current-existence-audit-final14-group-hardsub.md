# Repository current-existence audit — final-14 group/hard-sub dispositions

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 775c1d1ae3efb386a23ff26f1694723aee4e1c22
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-GROUP-01 — VERIFIED_OPEN P2

Exact current HistoryFragment still deletes one logical keyword group as:
deleteMembersByGroup(id) -> deleteGroup(id),
and one logical Youtuber group as:
deleteMembersByGroup(id) -> deleteRelationsByGroup(id) -> deleteGroup(id),
with separate DAO statements and no enclosing Room transaction.

A process death/Room failure can still commit only a destructive prefix and leave a surviving group with
lost membership/relationship state.

Correction boundary:
- one logical group deletion must be one transaction/cascade-equivalent atomic graph mutation;
- multi-selection must either be explicit per-group result or one defined all-or-nothing operation;
- UI completion must follow durable outcome rather than detached launch.

## BUG-HARDSUB-01 — VERIFIED_OPEN P2

Exact HardSubScanWorker still maps:
getResultsFromSource(...).firstOrNull()?.availableSubtitles.orEmpty()
so a nonthrowing absent ResultItem is indistinguishable from authoritative empty subtitles.
When the requested language is not found it writes hardSubScanRemoved=true, hardSubDone=false.

Thus ignored-error/ambiguous empty metadata can still create a durable negative exclusion.

Correction boundary:
carry typed lookup completeness; only authoritative successful no-match may set removed=true; ambiguous
empty must remain retryable and candidate-preserving.

## BUG-HARDSUB-02 — VERIFIED_OPEN P2

hydrateHistoryRedownloadBeforeQueue still resolves manual subtitles through
firstOrNull()?.availableSubtitles.orEmpty(). For an existing History row with hardSubDone=true this
nonthrowing absent-item result is treated as a valid empty subtitle set rather than a failed mandatory
hard-sub verification.

DownloadWorker replacement still preserves previous.hardSubDone/previous.hardSubScanRemoved whenever
completedHardSub is false. Therefore an unburned replacement can inherit the old media's hard-sub claim.

Correction boundary:
- authoritative lookup required when prior hard-sub guarantee must be preserved;
- ambiguous lookup blocks/retries replacement;
- replacement hardSub flags derive from the newly produced media/current burn result;
- prior media remains until the guarantee is re-established.

## BUG-HARDSUB-03 — VERIFIED_OPEN P2

mergeVideoAudioPairInDirectory still:
1. creates and validates mergedTemp;
2. deletes primaryVideo;
3. renameTo(primaryVideo);
4. on rename failure deletes mergedTemp and returns null.

A rename failure/process death after step 2 can still destroy the original video and then discard the
completed merged candidate.

Correction boundary:
never remove the last valid video before replacement is durably established; preserve either original or
candidate across failure/restart, and remove companion audio only after authoritative replacement commit.

## BUG-HARDSUB-04 — VERIFIED_OPEN P2

Current HardSubScanWorker still selects only:
hardSubScanRemoved=0 AND hardSubDone=0.
An authoritative no-match sets removed=true/done=false.

ProcessingSettingsFragment changes subs_lang by ordinary preference write and Scan Now simply enqueues a
new HardSubScanWorker generation. No current production path invalidates removed-only exclusions when the
language set changes. resetHardSubDoneForRescan only resets hardSubDone rows, not removed-only rows.

Therefore an old valid negative decision remains permanent authority after a supported language
reconfiguration or later source subtitle availability change.

Correction boundary:
bind negative eligibility to configuration/source generation or explicitly invalidate/reobserve it on
reconfiguration/manual rescan; preserve HARDSUB-01's rule that ambiguous lookup never creates the
negative authority.

## Immediate accounting

Lower-bound 136-ID population:
- audited IDs: 127
- VERIFIED_CLOSED/currently-not-reproduced: 76
- VERIFIED_OPEN: 50
- SUPERSEDED_ALIAS: 1
- remaining unaudited IDs: 9

Current narrow download canonical remains P0=0 / P1=0 / P2=8.
