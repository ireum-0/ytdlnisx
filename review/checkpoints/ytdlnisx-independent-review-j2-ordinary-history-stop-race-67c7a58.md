# Independent review checkpoint — ordinary History post-commit stop race

Review Basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`

## P2-J/J2 — confirmed subcase, no count increase

Replacement History has an explicit in-memory `historyReplacementCommitted` state after the atomic replacement transaction. The worker treats that committed History result as stronger than later cancellation and ancillary failure.

Ordinary History does not. `HistoryKeywordAssignmentRepository.insertHistory()` can durably commit an ordinary History row and return its exact `historyId`, but no durable or worker-local ordinary-success witness is established. After the insert's exact side-effect lease is released, a late Cancel/Pause may commit. The subsequent ordinary keyword side effect revalidates user-stop authority and can abort, and `publishCompletion()` explicitly returns STOP when `!historyReplacementCommitted && shouldStopForUserRequest()`.

Concrete outcome: exact publication + ordinary History may already be durable while the Download is subsequently left Cancelled/Paused/unfinished and restart recovery still does not recognize the ordinary History as finalization authority.

Classification: fold into existing P2-J as J2 — late user-stop can override an already-committed ordinary History success. No new P2 count.

Correction acceptance implications:
- bind the exact `historyId` returned by the ordinary insert to an execution/operation-scoped durable primary-success witness;
- once that witness commits, live user-stop/cancel authority must not downgrade the already committed primary result;
- startup recovery/admission must recognize the same witness and finalize rather than requeue;
- do not generalize using unordered `HistoryDao.getItemByDownloadId()`: `history.downloadId` is not unique and that DAO method is `LIMIT 1` without ordering;
- ancillary keyword convergence may continue as post-commit debt but must not reactivate the producer.

Canonical count remains P0 0 / P1 0 / P2 8.

INDEPENDENT EXECUTION: NOT EXECUTED
