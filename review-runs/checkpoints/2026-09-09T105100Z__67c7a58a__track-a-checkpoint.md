# Independent Review Checkpoint — Track A

- Review basis: `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8`
- Implementation branch: `checkpoint/pre-baseline-review`
- Review branch: `review/remediation`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Master Plan SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- Ledger reference: `899328bc91e4008e39a658387396a0106c8666ec`
- Independent execution: **NOT EXECUTED**

## Checkpoint purpose

This checkpoint records independent source review performed while a separate Terminal lifecycle correction is being implemented from the fixed review basis above. The implementation branch is intentionally not modified by this checkpoint. Semantic review remains pinned to the exact SHA `67c7a58...` until the separate correction is reported ready for independent review.

## P2-A correction review

Correction commit:

`67c7a58aea22cd9e040daaeeaef2ae873e6b59c8` — `fix: preserve terminal unknown quarantine identity`

Independent source review result: **CLEAN**.

The correction preserves Terminal UNKNOWN quarantine identity by requiring an exact durable `QUARANTINED_UNKNOWN` successor with matching execution token, source root, and subject before the UNKNOWN journal may be retired. Generic recovery carriers cannot replace or retire UNKNOWN journals. UNKNOWN cleanup now writes the explicit UNKNOWN carrier before revoking/retiring the prior owner, and failure in the handoff leaves the stronger journal authority intact.

Working canonical count after this closure was reduced by one P2.

## Confirmed new finding: P2-J

### P2-J — ordinary History commit has no durable finalization witness

Severity: **P2 / HIGH**

Root sequence:

1. An ordinary Download execution publishes its final output.
2. A `HistoryItem` tied to the current Download ID is durably inserted.
3. The Download row has not yet reached its final `completeAndDelete()` convergence point.
4. Process death occurs.
5. Startup recovery sees the still-active/post-processing Download row.
6. Committed-History special handling is currently limited to the history-redownload/replacement case.
7. The ordinary row can therefore fall through generic recovery/requeue and later be admitted as a new execution even though durable success already exists.

Required invariant:

> Once ordinary History success for an exact Download execution has been durably committed, that success must become durable finalization authority. Recovery must continue semantic finalization and must not return the Download to generic requeue/new-producer admission.

Important correction constraint discovered during review: ordinary History cannot safely be identified only by `downloadId`. History rows do not currently carry the exact execution/operation identity needed to distinguish generations, and `downloadId` is not by itself a sufficient immutable success witness. A future correction should connect the exact execution/publication lifecycle to a durable semantic-commit phase and an exact `historyId` or equivalent immutable commit identity.

At least two crash sub-windows must be covered:

- History insert committed, before automatic keyword application finishes.
- Keyword application finished, before Download-row finalization/cleanup finishes.

Neither window may cause yt-dlp producer replay.

## P2-B refinement

Existing P2-B remains one canonical finding covering prior-execution semantic compatibility/supersession. The following additional concrete production path is confirmed and should be treated as part of P2-B, not as a new canonical P2.

### B6 — prior exact publication may be adopted while the new producer still runs

`recoverPriorPublication()` can recover exact prior destinations, mark/retire the prior publication state, and return those destinations as `recoveredPublishedPaths`. Those paths are registered into the current runtime provenance, but the current execution still proceeds into `executeYtdlpPhase()` / native producer execution.

This produces an authority ambiguity: the current generation may simultaneously adopt E1's exact output and execute E2's producer.

Required correction rule:

> Prior-generation publication adoption and current-generation producer execution must be an explicit authority choice. If exact prior publication is semantically adopted as the current result, the same current generation must not blindly execute a new producer for the same semantic work.

### Compatibility proof must not be `operationId` or `SAME_SETTINGS`

The current retry model can preserve a nonblank `operationId` across RECONFIGURED retry. In addition, effective yt-dlp/output behavior is reconstructed from current preferences on retry. Options affecting actual output naming/content/post-processing can therefore change even when retry metadata is not a trustworthy immutable semantic witness.

A future P2-B correction should use a durable normalized effective producer/output contract fingerprint or an equivalently strong immutable witness. It should not equate `operationId` or the label `SAME_SETTINGS` with semantic compatibility.

Relevant effective semantics include, as applicable, output-plan mode and destination contract, authored/normalized yt-dlp output options, filename restrictions, SponsorBlock/post-processing, metadata/subtitle/thumbnail embedding, codec/language/format selection, and other settings that materially change generated artifacts or output authority.

## P2-C refinement

P2-C remains provider-specific.

Concrete state:

1. Source `S` has already produced an exact provider destination `D1`.
2. `D1` is durably recorded as the publication destination.
3. Source retirement fails, leaving `S` present.
4. Retry sees the existing exact destination but `reserveIntent(S)` can still return success.
5. MediaStore/SAF creation may therefore cross the provider-create boundary again and produce `D2`.

Required future regression assertion:

> Given `D1 committed + source still exists`, provider create/insert/createDocument must **not** be invoked again. Recovery should perform only the remaining source-retirement/finalization work for the exact published artifact.

The same duplicate-create shape was not confirmed for raw-filesystem publication: filesystem collision/reservation handling blocks a conflicting second destination before copy, and atomic move removes the source in the successful path. Keep P2-C focused on provider publication unless new production evidence broadens it.

## Combined Download correction model

P2-B, P2-C, and P2-J remain strong candidates for one correction boundary because they share one durable publication/finalization authority model.

The target lifecycle should distinguish at least:

`immutable effective operation semantics`

→ `exact artifact publication/adoption authority`

→ `source retirement`

→ `publication complete / semantic commit pending`

→ `exact ordinary History semantic commit`

→ `Download finalization debt`

→ `finalization`

→ `journal/authority retirement`

Do not collapse `publication complete but History not committed` and `History committed but Download finalization pending` into the same recovery state. The former is publication continuation; the latter is committed semantic success and must never fall back to generic producer requeue.

## P2-H refinement

P2-H remains confirmed as recovery discovery fail-open.

The core in-scope production problems remain:

- `PublicationRecoveryJournal.readAll()` converting namespace enumeration failure to an empty collection.
- malformed/unreadable journal entries being silently dropped.
- Terminal recovery-carrier namespace discovery converting enumeration failure to empty and malformed/unreadable carriers to absence.
- Terminal admission being able to interpret this apparent absence as no recovery debt and grant a new execution.

Preferred correction shape:

Use typed discovery semantics that distinguish at least:

- healthy namespace, no records;
- healthy namespace, validated records;
- discovery/enumeration unavailable;
- opaque/malformed durable debt.

The last two states must fail closed at admission/recovery boundaries rather than being converted to a clean empty set.

A similar fail-open-looking helper exists in Download cache ownership enumeration, but a blocker-impacting production consumer has not yet been established, so it is not added to P2-H at this checkpoint.

Native process-marker recovery was also rechecked and is not added to P2-H: the startup/native-marker path has separate candidate/debt handling that preserves malformed filename evidence and treats enumeration failure conservatively.

## Rejected / non-new findings

### Download user-stop vs publication race

Rejected as a separate P2. Production publication/retry side effects and stop/cancel cleanup are serialized by the per-Download execution side-effect lease and authority rechecks. No concrete path was established where durable stop authority commits while an unleased publication/source-retirement effect continues independently.

### Cancelled Download journal auto-republishes stale source

Rejected as a separate P2. A generic `PUBLISHING` journal does not independently republish the source while the item remains cancelled. If a later admitted generation consumes/adopts prior publication/source state incorrectly, that behavior belongs to P2-B.

### History replacement old-media cleanup debt

Recorded as a **P3 / hardening-liveness candidate**, not a canonical P2 at this checkpoint. History replacement can commit before old-media deletion, and deletion failure/process death may leave old media without durable cleanup debt. Existing reference-validation and exact-execution gating prevent the reviewed path from becoming an incorrect-target deletion P2.

If the future P2-J/Download-finalization implementation naturally introduces durable post-History cleanup debt, this P3 may be addressed in the same correction, but it must not expand the blocker semantics artificially.

## Current correction clustering

Current remaining canonical P2 set at this checkpoint:

- P2-B — prior-execution publication semantic compatibility/supersession, including B1-B6
- P2-C — exact provider publication replay after source-retirement failure
- P2-D — Terminal direct/no-cache durable execution witness
- P2-E — Terminal generic recovery carrier + stale-row fixed point
- P2-F — Terminal post-admission/pre-handler throwable convergence gap
- P2-G — Terminal stop/cancel native-quiescence failure handling
- P2-H — recovery discovery fail-open
- P2-J — ordinary History semantic commit lacks exact durable finalization witness

Working canonical count:

- P0: 0
- P1: 0
- P2: 8

Preferred correction boundaries:

1. **Cluster T — P2-D + P2-E + P2-F + P2-G**
   - one durable Terminal execution/recovery lifecycle state machine
   - currently being implemented separately from this fixed review basis

2. **Cluster D — P2-B + P2-C + P2-J**
   - one exact Download publication/adoption/semantic-finalization authority model

3. **Cluster R — P2-H**
   - typed, fail-closed durable recovery discovery

The broad historical opaque-provider UNKNOWN convergence gate remains conceptually separate from the current independent-review canonical P2 count until explicit reconciliation establishes whether/how it maps into the canonical set.

## Review discipline / next steps

- Keep semantic Track A review pinned to `67c7a58aea22cd9e040daaeeaef2ae873e6b59c8` while Cluster T implementation is in flight.
- Do not review an unreported in-progress correction diff.
- When the Cluster T completion report arrives, independently verify exact remote HEAD/parent first and then review the whole D/E/F/G correction boundary.
- If Cluster T is CLEAN, advance Review Basis and reduce the working P2 count by four.
- Continue source review of Cluster D and Cluster R constraints between correction reviews.
- Do not update the authoritative ledger until semantic review/evidence has established the decisions intended for ledger closure.

## Evidence labels

- Source-level review: performed against exact GitHub SHA stated above.
- GitHub write in this checkpoint: review documentation only, on `review/remediation`.
- Implementation branch modified by this checkpoint: **NO**.
- Ledger modified by this checkpoint: **NO**.
- Independent tests executed by reviewer: **NO**.

`INDEPENDENT EXECUTION: NOT EXECUTED`
