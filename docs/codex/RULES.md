# Durable Engineering Rules

These rules describe correctness constraints for current source. They apply even when a task document is older than the implementation.

## 1. Preserve observable behavior unless the task changes it

Do not alter queue ordering, retry semantics, download quality, storage destination, History deduplication, incognito behavior, or playback state as a side effect of an unrelated change.

## 2. Current download result model

The current structured outcome model is:

```text
DownloadOutcomeStatus
- SUCCESS
- SUCCESS_WITH_WARNINGS
- WAITING_FOR_ACCESS
- RETRYABLE_FAILURE
- FINAL_FAILURE
- CANCELED
```

A `DownloadIssue` carries stage, code, severity, retryability, suggested actions, source, and bounded redacted detail. Valid media creation followed by a History/notification/post-processing problem must not be represented as if no usable output exists.

## 3. Redaction is a boundary requirement

Commands, URLs, headers, cookies, tokens, credentials, proxy values, private paths, and extractor output can be sensitive. Apply the shared redaction path before persistence, notification, clipboard/export, or diagnostic presentation. Do not add a second weaker sanitizer for a new feature.

## 4. Persistent references must survive ID remapping

Backup/import and merge-restore code must treat database primary keys as local identifiers, not durable identities. Any persisted value that embeds or references another row ID must be remapped in the same restore operation.

Never execute a destructive History replacement from a restored numeric marker without verifying that:

- the marker was successfully remapped;
- the target row still represents the intended source/media;
- deletion is limited to the verified previous target.

## 5. Derived automatic-keyword state is revision-sensitive

RULE assignments are derived state. Their validity depends on the current rule revision/condition and current video membership. Do not restore an old RULE assignment merely because the same numeric rule ID still exists.

Manual/user-owned keyword state may be restored from a snapshot; derived RULE state should be recomputed or validated against a durable revision.

## 6. Empty extractor output is not completeness proof

A successful API return containing zero items can still represent an incomplete extractor response. Baselines, membership removal, or other irreversible semantic conclusions require an explicit trustworthy completeness signal. Distinguish:

- authoritative complete snapshot;
- authoritative empty snapshot;
- partial/incomplete result;
- failed lookup.

## 7. Background metadata workers own only their fields

A worker that enriches metadata must not write back a stale full database row after a network/native call. Patch only fields owned by that operation, or use a revision/compare-and-set contract that detects concurrent edits.

## 8. “No data” and “lookup failed” are different states

Do not convert an exception into an empty collection when the caller will treat empty as a durable negative result. This applies to subtitle scans, source discovery, metadata backfills, and membership/availability checks. Preserve coroutine cancellation separately from ordinary failure.

## 9. Retry must be bounded and identity-preserving

Do not retry user-canceled work. Preserve one logical operation identity across retries when persistence requires it. Enforce attempt limits and require confirmation when a retry changes output settings or quality.

## 10. File deletion requires ownership and revalidation

Never delete a directory/tree root as though it were a media item. Revalidate target identity and permission immediately before deletion, deduplicate aliases, and never broaden a failed exact-target operation into recursive deletion.

## 11. App-owned cache cleanup must remain app-owned

Cache deletion may touch only verified app-owned roots/categories. Active work must be gated out, protected persistent entries must remain excluded, and partial deletion must be reported accurately.

## 12. Preview and execution share one command plan

Terminal dry-run and execution must be generated from the same sanitized representation. A preview-only parser is not an execution security boundary.

## 13. Tests do not substitute for device evidence

JVM policy tests are valuable, but storage providers, WorkManager, foreground services, notifications, Media3, Room migrations, native binaries, and ABI behavior require emulator/device validation for release confidence.
