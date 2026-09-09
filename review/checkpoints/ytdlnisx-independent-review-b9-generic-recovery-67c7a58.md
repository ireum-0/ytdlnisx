# Independent review checkpoint — no-History success / generic recovery

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

## P2-B/B9 — confirmed subcase, no count increase

`DownloadAttemptRunner.runHistoryPersistence()` skips History persistence when `downloadItem.incognito` is true, then continues to `publishCompletion()`. Exact output publication may already have completed and a DOWNLOAD publication journal may already be COMPLETE. A process death after exact publication but before `publishCompletion()`/Download-row finalization therefore has no History witness. Restart generic recovery can requeue the still-running row; the next execution can recover prior publication and still enter the native producer path (the previously established B6 behavior).

Classification: fold into P2-B as B9 — no-History primary success lacks a durable semantic-success/finalization witness. No new P2 count.

Acceptance implication: Cluster D must make exact successful publication itself finalization authority for incognito/no-History executions. A History-specific fix is insufficient.

## Generic recovery carrier ordering — rejected as a new blocker

Worker exceptional cleanup revalidates the exact execution under the per-Download side-effect lease, attempts `DownloadExecutionRecovery.recordPending()` before native cancellation, and then uses exact execution-scoped native cancellation. If carrier persistence fails, the code records recovery publication failure but the durable Active/PostProcessing row remains; if a crash occurs after exact native quiescence but before the exact requeue CAS, startup recovery can rediscover that row. `YtdlpNativeProcessBarrier` only clears/finalizes an exact marker after positive generation-quiescence proof; read/enumeration uncertainty fails closed, and marker deletion failure is represented as proven-quiescent cleanup debt.

Classification: no additional P1/P2 established.

Canonical current count remains: P0 0 / P1 0 / P2 8.

INDEPENDENT EXECUTION: NOT EXECUTED
