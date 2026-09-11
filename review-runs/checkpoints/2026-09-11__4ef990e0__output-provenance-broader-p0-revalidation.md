# Historical BUG-OUTPUT-01 — output-provenance broader P0 revalidation

Date: 2026-09-11

## Exact review basis

- Independently CLEAN implementation basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Governing Master Plan reference: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Broader-registry hypothesis source: `review/remediation:TASKS.md`

This review was performed from the fixed CLEAN basis while a separate Luna F13 implementation wave was active. No moving implementation commit/diff newer than `4ef990e0...` was inspected or relied on.

## Historical hypothesis

Historical P0 `BUG-OUTPUT-01` described privileged output recovery through `recoverPathsFromDirectory()`: a destination-directory scan accepted recently modified files by timestamp and could therefore adopt an unrelated concurrent file as this Download's output, after which hard-sub, History replacement, or previous-media deletion could act on the wrong persistent media.

The required invariant is provenance, not recency: every path that can authorize quality acceptance, hard-sub mutation, History publication/replacement, or old-media cleanup must be tied to the exact producer execution or a durable exact publication carrier.

## Exact-source revalidation

### Historical recency-based privileged recovery is absent

At exact `4ef990e0...`, the reviewed `DownloadWorker.kt` contains no production `recoverPathsFromDirectory()` call or definition. A search of the exact file yields no such symbol.

`lastModified()` remains in diagnostics, subtitle ordering/canonicalization, and logging, but not as a producer-output authorization predicate equivalent to the historical directory-recency fallback.

### Current producer paths are gated by DownloadOutputProvenance

`DownloadOutputProvenance` explicitly defines one execution-attempt authority contract. Its source comment states that directory membership, recency, and filename similarity are deliberately absent from the contract.

At `beginAttempt()` it snapshots the attempt-owned staging roots. A reported path becomes authoritative through `acceptYtdlpOutput(...)` only when:

- it was reported by the current yt-dlp output carrier;
- it resolves to an existing file;
- it is inside the current attempt's clean temp staging root, or inside a direct staging root whose ownership marker/baseline proves the current attempt owns that root.

An unproven file merely found in a directory is never promoted into `currentAttemptPaths`.

### Move/publication output remains tied to authoritative source files

`recordMoveResults(...)` accepts destination paths only when its source path set is nonempty and every source is already authoritative and owned by the current attempt. Exact returned move/copy destinations may therefore extend provenance, but a destination rescan cannot manufacture authority.

`DownloadWorker` uses this contract for direct-output publication and normal temp-to-destination publication. Final moved paths are constructed from exact publication recovery carriers plus `recordMoveResults(...)`, not destination mtime discovery.

### Hard-sub effects consume authoritative paths

The hard-sub production path derives pre-move and late-pre-move media candidates from `currentAuthoritativeOutputPaths()` and requires them to be files in the current staging directory. Stranded-temp recovery likewise searches only `currentAuthoritativeOutputPaths()`.

Derived hard-sub/canonicalized outputs are admitted with `recordDerivedOutput(...)`, which requires their input paths already be authoritative. Failure to establish derived-output provenance fails the transform path rather than broadening authority to directory contents.

### Staged quality consumes authoritative paths

The staged-video quality probe receives `authoritativeOutputPaths` from the current yt-dlp attempt and filters that set to existing nonempty files. It does not search a destination directory for recent files.

### Recovery uses exact durable carriers

Recovered publication paths are reintroduced only from a previously validated durable publication journal through `recordRecoveredPublishedPath(...)`; recovered producer paths are reintroduced only when they are inside the predecessor-owned staging root through `recordRecoveredProducerPath(...)`.

These recovery APIs re-establish exact producer/publication identity and do not use file recency as authority.

## Verdict

**CURRENT HISTORICAL P0 NOT REPRODUCED / DO NOT PROMOTE `BUG-OUTPUT-01`.**

The historical mtime-based unrelated-destination adoption path is absent at exact `4ef990e0...`. Current privileged output consumers are rooted in exact current-attempt yt-dlp carriers, attempt-owned staging, exact move/publication results, or durable recovery journals.

- Severity/count delta: `0`
- Canonical blocker count remains **P0 2 / P1 2 / P2 28**.
- CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.
- Overall project remains `NOT_CLEAN`.

## Root reconciliation

- Do not reopen any prior output/provenance closure solely because diagnostic mtime scanning or subtitle ordering still exists; those uses do not authorize primary output adoption.
- `BUG-HARDSUB-GENERATION-01` remains a separate current P2 concerning consumer-generation ownership and regular History-redownload marker admission, not unrelated-file output provenance.
- `BUG-CACHE-01` remains separate maintenance-vs-live-temp ownership.
- `BUG-CACHE-ROOT-01` remains separate cache-root generation identity.
- A future producer path that bypasses `DownloadOutputProvenance` and lets directory recency/filename similarity authorize privileged output would be a concrete regression, but no such current exact-basis path was established here.

INDEPENDENT EXECUTION: NOT EXECUTED