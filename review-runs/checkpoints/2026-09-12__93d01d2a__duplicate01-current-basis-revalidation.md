# BUG-DUPLICATE-01 current-basis revalidation

Date: 2026-09-12

## Exact review basis

- Fixed independently CLEAN implementation basis: `93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`
- Active cache review-fix implementation diff inspected: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger: reference-only `899328bc91e4008e39a658387396a0106c8666ec`
- Historical F19 finding checkpoint: `review-runs/checkpoints/2026-09-09T234700Z__67c7a58a__duplicate-config-identity.md`
- Separate race checkpoint retained independently: `review-runs/checkpoints/2026-09-10__c2294c87__duplicate-admission-race.md`

A later scheduled/narrow cache checkpoint was appended at review commit `3d6e5fdcf77c865e328ea895b97f038f408eb810` after the canonical cache review. It is provisional cache evidence only and does not alter this fixed-basis F19 decision or the canonical cache disposition.

## Verdict

**CLEAN FOR F19 ROOT / `BUG-DUPLICATE-01` CLOSED at current basis.**

Canonical count delta: `P2 -1`.

Resulting canonical blocker count: **P0 2 / P1 1 / P2 32**.

Overall remediation state remains `NOT_CLEAN` because unrelated blockers remain open.

The contiguous independently CLEAN basis remains:

`93d01d2afbce2cfa62dc17fad4478416b3d7cf6c`

This finding is being closed by current-basis revalidation at an already accepted CLEAN implementation SHA; no later implementation SHA is promoted by this checkpoint.

## Root relation and scope separation

Master Plan F19 / `BUG-DUPLICATE-01` owns semantic duplicate identity for supported media representations while preserving genuine download-configuration differences.

The historical concrete defect at `67c7a58a...` was raw-URL-keyed configuration identity: equivalent supported YouTube representations such as `youtu.be/...` and `youtube.com/watch?v=...` could bypass config duplicate prevention in manual and Observe production paths.

This root is distinct from:

- `BUG-DUPLICATE-ADMISSION-01`, the check/snapshot -> insert race in which two concurrent producers may both admit semantically equivalent queue rows after both observe no duplicate. That separate root remains OPEN and is not closed here.
- `BUG-HISTORY-DUPLICATE-IDENTITY-01`, the destructive History title-grouping root. That separate History root has its own independent closure and is not reclassified by this checkpoint.

## Exact current-source closure

### Shared media identity policy

At exact `93d01d2a...`, `DownloadConfigurationDuplicatePolicy.findMatch(...)` and `matches(...)` first compare a canonical media identity and then compare request configuration.

For supported YouTube media, `canonicalMediaIdentity(...)` delegates to `MediaPublishedDateSource.youtubeVideoId(...)` and uses the proven 11-character YouTube media ID under a `youtube` namespace. For unsupported providers, it deliberately falls back to the trimmed raw URL under a separate `raw` namespace rather than aggressively normalizing unknown URLs into false matches.

`MediaPublishedDateSource.youtubeVideoId(...)` recognizes the current supported YouTube host/forms relevant to the historical F19 gap, including watch URLs, `youtu.be`, mobile and music YouTube forms, while requiring a valid 11-character media ID. Nearby unsupported/provider URLs therefore remain conservative rather than being collapsed by a generic URL normalizer.

### Configuration identity remains semantically selective

`requestConfiguration(...)` excludes volatile persistence/execution state such as Download row ID, status, retry counters/attempt, operation/execution IDs, transient stage/timestamps and scheduler runtime state.

It retains request-shaping fields including format, download path, media preferences, thumbnail behavior, extra commands, download sections, incognito, filename/chapter templates, playlist/item fields and other current request configuration. Therefore the correction does not turn F19 into media-only deduplication and does not suppress intentionally different requests merely because they target the same media.

### Manual queue production composition

The exact `DownloadViewModel` production config-duplicate path reads active/queued Downloads and calls `DownloadConfigurationDuplicatePolicy.findMatch(activeAndQueuedDownloads, requested)`.

The historical raw-source comparison is therefore no longer independently reimplemented in the manual config path: the real production caller uses the shared canonical-media + request-configuration policy.

For History command/config comparison, the current production path uses `DownloadConfigurationDuplicatePolicy.commandsMatch(...)`. That helper canonicalizes recognized media-source tokens to a stable YouTube source representation while leaving non-media command tokens/options semantically intact.

### Observe production composition

The exact `ObserveSourceWorker` config duplicate path also calls `DownloadConfigurationDuplicatePolicy.findMatch(...)` for active/queued Downloads and uses `commandsMatch(...)` for command/config History comparison.

Manual and Observe config duplicate prevention therefore share the same supported-media identity semantics rather than one path remaining raw-URL keyed.

## Focused source test evidence

The exact current `DownloadConfigurationDuplicatePolicyTest` covers the F19 semantic matrix directly:

- volatile database/execution state does not change request identity;
- request-changing fields such as destination path or extra commands prevent a duplicate match;
- YouTube watch, `youtu.be`, mobile and music URL forms for the same media ID match;
- different YouTube IDs do not match;
- unknown-provider URLs remain conservative;
- History command comparison canonicalizes recognized media source tokens while preserving actual option differences.

These tests are source evidence only in this independent review; the reviewer did not execute them.

## Residual explicitly outside this closure

`BUG-DUPLICATE-ADMISSION-01` remains the next relevant duplicate-domain review target. The F19 comparator can correctly decide that two rows are equivalent while two concurrent producers still race if the duplicate observation and durable insert are not serialized/revalidated at one admission boundary. This checkpoint does not infer that race fixed merely because comparator semantics are now correct.

## Count reconciliation

The historical F19 checkpoint explicitly introduced `BUG-DUPLICATE-01` as a distinct P2 root and advanced its then-working count by one. No later review checkpoint in the current review tree records an independent F19 closure or aliases this semantic root into another finding. The current source now closes the original F19 invariant, so one canonical P2 subtraction is appropriate.

Starting canonical count before this closure: `P0 2 / P1 1 / P2 33`.

After closing the one retained F19 root: **`P0 2 / P1 1 / P2 32`**.

No other root is added, removed, or reclassified by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED