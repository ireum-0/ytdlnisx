# Broader-registry BUG-MOVE-01 — partial SAF publication current-basis revalidation

Date: 2026-09-11

## Exact basis

- Fixed independently CLEAN basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Broader historical registry: `review/remediation:TASKS.md`
- Active implementation wave: P2 `BUG-METADATA-02` / F13
- Moving implementation diff inspected or relied on: **NO**

## Historical root

Historical P2 `BUG-MOVE-01` described a SAF fallback that copied files one-by-one, considered the fallback recovered if **any** file copied, then recursively deleted the whole source directory. A later per-file provider failure could therefore destroy uncopied source files.

## Current verdict

**The historical P2 data-loss sequence is not reproduced at exact `4ef990e0...`; do not promote `BUG-MOVE-01` into the current canonical blocker inventory.**

Count delta: **0**.

Canonical count remains **P0 2 / P1 2 / P2 28**.

## Exact-source closure evidence

Current `FileUtil.moveFile()` routes provider-backed publication through exact per-source output tracking.

For SAF destinations, `moveFilesToSafWithExactOutputs()`:

1. enumerates exact eligible source files;
2. creates/reserves one provider destination for each source;
3. requires publication commitment through the supplied durable callback when present;
4. adds the exact returned provider URI to the output carrier;
5. deletes only that exact source file after its own publication succeeds;
6. catches a per-file ordinary failure into the result error list while leaving that failed source in place.

At the outer SAF branch, `hasMoveFailure` is set when any per-file error exists. Whole-source cleanup is performed only when `!keepCache && !hasMoveFailure`.

Therefore partial success no longer authorizes recursive retirement of the unprocessed/failed suffix.

`moveFileInputStream()` also treats provider create/reservation/publication failures explicitly:
- provider object creation is exact;
- reservation failure attempts provider-object rollback and durable reservation retirement;
- unresolved provider side effects raise `UnresolvedProviderPublicationException`, fencing ordinary retry/fallback;
- a failed publication attempts to remove the exact provider object rather than reporting subset success and deleting unrelated sources.

The MediaStore publication path applies the same exact-output/rollback discipline.

## Reconciliation

This review does not claim all filesystem/provider publication roots are globally closed. It rejects only the historical `BUG-MOVE-01` sequence where partial SAF fallback success caused whole source-directory deletion.

Keep separate:
- current cache-maintenance/live-owner root (`cbb91dc8ae8e01642807ca0316567197245afd63`);
- provider authority roots such as `BUG-CACHE-02`;
- Terminal or Download post-commit publication/finalization roots if independently reproduced.

## Independent execution

INDEPENDENT EXECUTION: NOT EXECUTED
