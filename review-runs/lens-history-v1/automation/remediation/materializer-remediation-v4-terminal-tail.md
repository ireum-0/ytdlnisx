# Materializer remediation protocol v4 — terminal bounded remainder

This protocol applies only to `lens-history-v1` Materializer artifacts on branch `review/lens-history-v1`.

It closes the deterministic governance gap that occurs when an Auditor-certified terminal logical materialization range ends at the frozen checkpoint-path history boundary with fewer than `microsegment_size` ranks remaining after the last complete five-rank aggregate.

## Scope

This protocol is intentionally narrow. A shortened terminal aggregate is valid only when all of the following are true:

1. the immediately preceding authoritative Auditor certification is `PASS` with `unlock_allowed=true`;
2. that certification explicitly opens a terminal bounded Materializer range whose end rank equals the exact frozen checkpoint-path touching commit count;
3. all ranks before the terminal remainder are covered by valid complete five-rank aggregate receipts;
4. the remaining uncovered suffix contains at least one and fewer than five ranks;
5. every rank in that suffix has an immutable one-rank Materializer receipt with exact contiguous `previous_rank_receipt` binding;
6. each suffix receipt has exact frozen-history resolution, explicit changed-file pagination closure, and exact source/frozen blob identity;
7. no rank exists after the logical range end in the pinned frozen history;
8. no conflicting writer or artifact exists at publication time;
9. no immutable rank receipt or earlier aggregate is rewritten;
10. official inventory, ledger, validation, and progress outputs for the logical range are still deferred.

If any condition is not satisfied, this protocol MUST NOT be used.

## Terminal aggregate

The final aggregate may cover exactly the remaining suffix even when its length is less than five.

It MUST:

- use the normal Materializer aggregate schema fields;
- bind the exact previous full aggregate;
- list every terminal one-rank receipt path/blob in rank order;
- set `terminal_tail=true`;
- record `configured_microsegment_size=5`;
- record `actual_segment_size` equal to the suffix length;
- bind the exact prior Auditor certification that authorized the terminal logical range;
- bind the exact frozen history count proving that no later rank exists;
- state that the shortened size is permitted only because the logical range and pinned frozen history are exhausted together;
- preserve all factual exception ranks and all conservative NOT_VERIFIED semantics.

For Git ancestry and downstream Materializer/Triage/Auditor evidence, the shortened terminal aggregate is an authoritative aggregate for its exact terminal suffix.

## State advance

After the terminal aggregate is written and re-fetched, Materializer state may advance its completed aggregate prefix through the logical range end.

The state MUST bind:

- this exact protocol path/blob;
- the exact terminal aggregate path/blob;
- every terminal one-rank receipt through the logical range end;
- the prior state blob;
- the prior Auditor certification that authorized the terminal range;
- the pinned frozen history count and terminal rank;
- `next_rank = logical_end + 1`;
- official outputs still deferred until generated and validated.

The state remains `PAUSED_RETRYABLE` until official materialization outputs, run validation, exception queue, and progress are complete.

## Terminal official output batch

Normal official inventory and ledger batches remain ten ranks where ten ranks are available.

At frozen-corpus exhaustion, the final official batch MAY contain the remaining one to nine ranks only when:

- all preceding official batches in the run are complete ten-rank batches;
- the partial batch ends exactly at the pinned frozen history terminal rank;
- its validation records the actual record count and the terminal-tail protocol path/blob;
- progress advances exactly to the frozen history terminal rank;
- no placeholder rows are synthesized to pad the batch.

## Completion and downstream review

After all official outputs validate, Materializer may set the run to `COMPLETE` and require full-range Triage and independent Auditor review exactly as for a normal batch.

This protocol does not itself authorize `CORPUS_CLOSURE`. A valid final Auditor PASS for the terminal materialization range is still required before separate corpus-closure work begins.

## Prohibited use

This protocol MUST NOT be used to:

- shorten a non-terminal segment;
- skip any rank;
- pad the corpus with synthetic ranks;
- alter a sealed rank receipt;
- alter source/frozen identity;
- change finding/status semantics;
- infer modern lens data;
- bypass official output validation;
- bypass Triage or Auditor;
- unlock corpus closure before a valid terminal-range Auditor PASS.
