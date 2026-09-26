# Downstream terminal-tail review protocol v1

This protocol applies only to the terminal `lens-history-v1` logical range whose Materializer evidence is validly sealed under the checked-in terminal-tail Materializer protocol.

It exists solely to let Triage and Auditor complete the final bounded corpus suffix when the logical range ends at the exact pinned frozen-history terminal rank with fewer than five ranks remaining.

## Preconditions

A shortened downstream terminal receipt is valid only when all of the following are true:

1. Materializer state is `COMPLETE`, `requires_triage=true`, and `requires_audit=true`;
2. the COMPLETE state binds the terminal Materializer protocol `materializer-remediation-v4-terminal-tail.md` and its exact blob;
3. the COMPLETE state binds an authoritative terminal Materializer aggregate whose end rank equals the exact frozen history count;
4. all preceding downstream receipts cover contiguous complete five-rank segments with no gap or duplicate;
5. the only uncovered suffix contains at least one and fewer than five ranks and ends exactly at the logical range end;
6. every suffix rank has sealed one-rank Materializer evidence and an official ledger row;
7. Triage or Auditor independently performs the same source/evidence review required for a normal receipt; no conclusion may be inherited merely because Materializer flagged it;
8. the shortened receipt binds this protocol path/blob and the terminal Materializer protocol path/blob;
9. no conflicting writer, existing receipt, correction, final marker, or certification exists at publication time;
10. no existing immutable artifact is rewritten.

If any condition fails, do not use this protocol.

## Triage terminal receipt

Triage may emit one final shortened receipt for the exact remaining suffix.

It MUST:
- use the ordinary Triage receipt schema and semantics;
- bind the exact COMPLETE state blob and data_head;
- bind the exact terminal Materializer aggregate and all relevant rank evidence;
- bind the actual previous Triage receipt blob;
- independently review every suffix source checkpoint;
- preserve all uncertainty and factual exception triggers;
- set `terminal_tail=true`, `configured_microsegment_size=5`, and exact `actual_segment_size`;
- bind this protocol path/blob;
- never infer closure, finding identity, modern lens attribution, numeric effectiveness, or status beyond explicit source support.

The shortened terminal receipt counts as contiguous Triage coverage for its exact suffix.

## Auditor terminal receipt

After a valid final Triage marker exists, Auditor may emit one final shortened receipt for the same exact suffix.

It MUST:
- satisfy every ordinary independent Auditor requirement, including exact frozen-history resolution, changed-file pagination closure, source/frozen identity, official-ledger replay, required-field comparison, exception completeness, and previous Auditor receipt binding;
- bind the exact COMPLETE state blob, final Triage marker, terminal Triage receipt, terminal Materializer aggregate, this protocol, and the Materializer terminal-tail protocol;
- record `terminal_tail=true`, `configured_microsegment_size=5`, and exact `actual_segment_size`;
- fail if any required field or semantic preservation check disagrees.

The shortened terminal receipt counts as contiguous Auditor coverage for its exact suffix.

## Final markers

A final Triage marker or final Auditor certification may treat a valid shortened terminal receipt as ordinary contiguous terminal coverage only for the exact terminal suffix authorized above.

The final Auditor PASS may unlock only the separately defined `CORPUS_CLOSURE` step. It does not by itself assert that the count of checkpoint-path-touching commits equals the count of distinct frozen checkpoint documents.

## Prohibited use

This protocol MUST NOT:
- shorten any non-terminal downstream segment;
- skip or synthesize ranks;
- convert a normal five-rank segment into a smaller segment for convenience;
- repair substantive review errors;
- override source semantics or conservative `NOT_VERIFIED`;
- bypass Triage, Auditor, official-ledger replay, exception completeness, or final exact-binding gates;
- authorize production/application-source changes.
