# Independent correctness review — final checkpoint

Timestamp: 2026-09-11T05:35:00Z

## Frozen basis
- implementation SHA: `fffb6b131a7b9cb35cf8a218b42863df9e332430`
- plan SHA: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- review bootstrap SHA: `2e37e7cb12de7319c3e4bd5c7fee7bd6606095c5`
- ledger SHA: `899328bc91e4008e39a658387396a0106c8666ec`
- v6 checklist blob: `7b553328dfcd9941d783658f49ecb16c71b98c56`
- Master Plan canonical SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`

Final ref recount immediately before this checkpoint confirmed implementation, plan, and ledger heads remain the frozen values above.

## Completed review scope
- Fresh-fetched required branches and froze exact SHAs.
- Read current production `SourceSnapshot`, `ResultRepository`, `NewPipeUtil`, `YTDLPUtil`, `ObserveSourceWorker`, `AutomaticKeywordRuleSyncWorker`, and Observe production-wiring test.
- Reconstructed source producer -> typed snapshot -> Observe baseline/positive-processing -> destructive absence -> scheduling/lifecycle path.
- Reconstructed dependent AutomaticKeywordRuleSyncWorker consumer of the new snapshot authority contract.
- Read governing v6 core invariants/consumer-closure rules and Master Plan F3 invariant/preserve clauses.
- Used diff only to select review priority; verdict is based on current full production paths.

## Final blocker state
- verdict: `NOT_CLEAN`
- canonical minimum: P0 3 / P1 3 / P2 25
- no new canonical root found in this run
- `BUG-OBSERVE-01` remains OPEN at P0

## Confirmed fixed part of BUG-OBSERVE-01
- `SourceSnapshot` carries AUTHORITATIVE/PARTIAL/FAILED.
- PARTIAL/FAILED cannot authorize destructive source absence.
- authoritative empty can still authorize legitimate absence reconciliation.
- NewPipe playlist/channel producers downgrade on conversion drops, incomplete continuation, and extraction failure.
- a PARTIAL NewPipe primary is not silently upgraded by fallback.

## Confirmed remaining same-root defect
Source-membership completeness authority is incorrectly reused as run-lifecycle completion authority.

Concrete current production sequence:
1. Observe source uses the typed yt-dlp path.
2. Current `YTDLPUtil.getFromYTDLSnapshot()` builds the source request with `--ignore-errors` and sets `ignoredChildErrors=true`, so successful typed extraction is PARTIAL by construction.
3. With `getOnlyNewUploads=true` and `runCount=0`, Observe records all returned items into `ignoredLinks` as the first-run baseline.
4. It calls `finishRunAndSchedule(..., countRun = sourceIsAuthoritative)`.
5. PARTIAL means `sourceIsAuthoritative=false`, so durable `runCount` stays 0.
6. Next scheduled run again takes the first-run baseline branch and newly appearing uploads can also be added to `ignoredLinks` instead of reaching ordinary positive processing.
7. This can repeat indefinitely; `endsAfterCount` can also fail to advance solely because destructive-absence authority is conservative.

This violates Master Plan F3 Preserve requirement for normal additions/retry/run behavior while fixing destructive absence. It also violates v6 semantic-contract consumer closure: a new typed source-membership contract changed meaning for downstream lifecycle consumers, but the consumer graph was not separated by purpose.

The same contract makes `AutomaticKeywordRuleSyncWorker` retry/stop applying usable PARTIAL results; this remains within the already-existing keyword root and is not double-counted here.

## Production evidence
- Current Observe deletion candidate construction is explicitly gated by `sourceSnapshot.authority` and therefore closes the original partial-list -> destructive absence chain.
- Current Observe first-run baseline branch uses `runCount == 0`, mutates `ignoredLinks`, and finishes with `countRun = sourceIsAuthoritative`.
- Current yt-dlp typed producer is conservatively PARTIAL for successful source extraction due to `--ignore-errors` plus tolerant child parsing.
- Added instrumentation coverage tests only the destructive absence gate/candidate helper; it does not cover lifecycle composition.

## Fixed invariants
- partial/failed omission cannot delete prior members;
- authoritative empty remains meaningful;
- partial primary extraction is not silently upgraded.

## Open candidates / questions
- No additional canonical P0/P1/P2 root proven in this run.
- A future fix must separate destructive-absence authority from usable-positive-run lifecycle authority without declaring tolerant yt-dlp output authoritative.
- Independent JVM/instrumentation/emulator/device execution: `NOT_VERIFIED`.

## Remaining review scope
For this frozen SHA, no additional material scope is required before verdict. Unchanged canonical roots retain their prior dispositions because no contrary current-source evidence was established in this run.

## Exact semantic basis used
- application production source at exact implementation SHA above;
- Master Plan exact pinned SHA and F3 contract: partial/failed extraction cannot make destructive absence claims; preserve normal additions/filtering/canonical URLs/retry behavior/authoritative-empty removal;
- v6 exact checklist blob above, especially material semantic-contract consumer closure and authority-effect closure;
- yt-dlp-facing application request semantics are taken from the exact current `YTDLPUtil` production source (`--ignore-errors`, tolerant callback parsing, `ignoredChildErrors=true`). No additional external upstream behavior is required to establish this lifecycle defect.
