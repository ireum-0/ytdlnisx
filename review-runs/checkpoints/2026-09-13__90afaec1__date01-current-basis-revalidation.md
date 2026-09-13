# BUG-DATE-01 revalidation at advanced CLEAN basis 90afaec1

Date: 2026-09-13

## Scope

- New contiguous independently CLEAN basis: `90afaec157607669ea32fa41877e7f0efcdcca86`
- Prior exact-basis F15 review: `e4dd561fb9270a535c8aef44f63dbe8c488fec88` at `3616ae02...`
- F15 was directly re-read during the active F4/F10 wave at `a12c58055fff51b104f8b56fd53b534b8d7e5df4`.
- Exact compare `a12c5805... -> 90afaec1...` modifies only the F4-F7 backup/restore implementation/test surface and does not modify `HistoryDateFetchWorker.kt`, `HistoryDateFetchPolicy.kt`, `HistoryDateFetchRepository.kt`, or the relevant YTDLP date helper contract.

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN** — existing P2 `BUG-DATE-01` remains open. Count delta `0`.

The production semantic collapse remains:

- minimal extractor ordinary failure is caught and reduced to nullable `null`;
- compatibility nullable/unproven result can become `HistoryDateLookupOrigin.NONE`;
- `checkpointSourceGroup(...)` persists every no-date result other than explicit `FAILED` as durable `NO_DATE`;
- therefore failure/ambiguity can still become durable absence evidence.

Required invariant remains: extractor failure/ambiguity must remain distinct from authoritative absence; `NO_DATE` is permitted only when successful matching extraction proves date absence.

F16 `BUG-DATE-02` remains hard-dependent on F15's typed child lookup outcome.

Canonical blocker count remains **P0 2 / P1 0 / P2 26** after the separate F4 closure reconciliation. CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

INDEPENDENT EXECUTION: NOT EXECUTED