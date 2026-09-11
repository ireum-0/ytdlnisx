# Broader-registry BUG-TERMINAL-02 — current-basis revalidation

Date: 2026-09-11

## Exact review basis

- Fixed independently CLEAN basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Broader historical registry: `review/remediation:TASKS.md`
- Active implementation wave: P2 `BUG-METADATA-02` / F13 from `4ef990e0...`
- Moving implementation diff inspected or relied on: **NO**

## Historical root

Broader-registry P1 `BUG-TERMINAL-02` described Terminal and ordinary Download rows from independent tables sharing an unqualified numeric task ID across yt-dlp process ownership, running notifications, and Terminal Cancel. Under the historical path, Terminal Cancel could call the ordinary Download cancellation path and mutate/terminate an unrelated Download with the same numeric ID.

## Current verdict

**The historical P1 cross-record task-identity root is NOT reproduced at exact `4ef990e0...`; do not promote it into the current canonical P0/P1/P2 inventory.**

Canonical blocker-count delta: **0**.

Canonical count remains **P0 2 / P1 2 / P2 25**.

CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

## Exact current-source evidence

1. Native process identity is now namespaced.
   - `YtdlpProcessIdentity.download(downloadId, executionId)` returns a `download:<id>:<execution>` identity.
   - `YtdlpProcessIdentity.terminal(terminalId)` returns `terminal:<id>`.
   - `TerminalDownloadWorker` uses `YtdlpProcessIdentity.terminal(itemId)` before native admission.

2. Terminal cancellation is now a domain-specific capability.
   - Terminal notification actions target `CancelTerminalNotificationReceiver`, not `CancelDownloadNotificationReceiver`.
   - `CancelTerminalNotificationReceiver` cancels Terminal unique work, delegates native quiescence to `TerminalExecutionRegistry.cancel(context, terminalId)`, cancels only the Terminal notification, and deletes only `terminalDao` state.
   - It does not call ordinary `DownloadRepository.cancelByUser()` and does not terminalize an unrelated low-quality Download child merely because numeric IDs match.

3. PendingIntent identity is domain-scoped through the Terminal-specific receiver/action URI/request-code path.

Therefore the original producer/authority/effect chain — `Terminal id N -> ordinary Download cancel/process authority for id N -> unrelated Download persistent mutation` — no longer exists in the reviewed source.

## Residual notification-ID candidate

Notification IDs are offset rather than mathematically disjoint:

- normal running Download: `90000 + downloadId`;
- Terminal running task: `99000 + terminalId`.

Both tables use unbounded auto-generated `Long` primary keys. Therefore distinct values can alias, e.g. Download `9001` and Terminal `1` both map to notification ID `99001` after `Int` conversion/offset use.

This can allow one domain's `notify()`/`cancel()` to replace or hide the other domain's notification. However, the exact current cancellation PendingIntent and native/persistent authority remain domain-typed, so this review does **not** treat the residual notification collision as evidence that the historical P1 cross-record mutation root remains open.

Disposition: **separate low-severity candidate requiring focused foreground/notification effect review before any promotion/count change.**

Do not merge that candidate into `BUG-TERMINAL-HANDOFF-01`, which owns durable Terminal Room-intent -> WorkManager acceptance/recovery.

## Independent execution

INDEPENDENT EXECUTION: NOT EXECUTED
