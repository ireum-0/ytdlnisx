# Terminal/Download notification collision candidate — exact CLEAN-basis classification

Date: 2026-09-20

## Exact review state

- Exact independently CLEAN implementation basis reviewed: `90afaec157607669ea32fa41877e7f0efcdcca86`.
- Historical parent root: `BUG-TERMINAL-02`, previously rejected as a current P1 at `4ef990e0...`.
- Residual candidate from that review: cross-domain running-notification ID collision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`.
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`.
- Active F11 implementation remained frozen from inspection.

## Verdict

**NOT REPRODUCED / REJECT AS CURRENT P0/P1/P2 CANDIDATE at `90afaec1...`.**

- Historical `BUG-TERMINAL-02` remains non-promoted.
- The previously noted notification-only residual is also no longer reproduced by current notification identity.
- Canonical blocker-count delta: `0`.
- No CLEAN-basis movement.

## Exact current evidence

### 1. Running notification IDs are domain-namespaced

`NotificationUtil` defines:

- ordinary Download running notification ID: `DOWNLOAD_RUNNING_NOTIFICATION_ID + downloadId` where base = `90000`;
- Terminal running notification ID: `DOWNLOAD_TERMINAL_RUNNING_NOTIFICATION_ID + terminalId` where base = `99000`.

These helpers are distinct even when Download ID and Terminal ID are numerically equal.

### 2. Ordinary Download production uses the Download namespace

`DownloadWorker` publishes running notifications with:

`NotificationUtil.downloadRunningNotificationId(downloadItem.id.toInt())`

and removes them with `cancelRunningDownloadNotification(...)`, which cancels the same namespaced ID.

### 3. Terminal production uses the Terminal namespace

`TerminalDownloadWorker` enters foreground with:

`ForegroundInfo(NotificationUtil.terminalNotificationId(itemId), ...)`.

Terminal progress updates publish using `terminalNotificationId(id)`, and Terminal cancellation uses `cancelTerminalDownloadNotification(id)`, which cancels that same Terminal namespace.

### 4. Action identity is also domain-separated

Terminal cancel PendingIntents are built with Terminal-specific receiver/data identity using the `terminal` action URI domain.

Download pause/cancel/retry actions use Download-specific receivers and `download` action URI identity, including execution identity where required.

Thus equal numeric row IDs do not give one domain's notification action authority over the other domain's row/process.

## Final-effect classification

The earlier historical P1 mutation hazard is not present, and the residual UI-notification collision noted at the older basis is not present under the current namespaced IDs.

No current cross-domain notification replacement/cancellation effect was established at `90afaec1...`.

## Reopen condition

Reopen only if a production caller bypasses the namespaced helpers and publishes/cancels a Terminal or ordinary Download running notification using the raw numeric row ID, or if a future notification channel/action collapses the two domains again.

## Verification

- Exact `NotificationUtil`, `DownloadWorker`, `TerminalDownloadWorker`, and Terminal cancellation consumers reviewed at `90afaec1...`.
- Independent execution: not performed.

INDEPENDENT EXECUTION: NOT EXECUTED
