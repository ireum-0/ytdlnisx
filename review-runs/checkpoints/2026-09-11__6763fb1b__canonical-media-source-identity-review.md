# Independent Master Plan §9.7 canonical media/source identity review — 6763fb1b

- Exact implementation SHA / contiguous independently CLEAN basis: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Implementation branch verified: `checkpoint/pre-baseline-review` = exact SHA above
- Review branch HEAD immediately before this checkpoint: `1da201ef724acecd52786b7ac89a06908cb53e7e`
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, §9.7
- Master Plan canonical SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Master Plan modified: NO
- Implementation branch modified by this review: NO
- §9.7 verdict: `NOT_CLEAN / REVIEW COMPLETE`

## Scope and identity layers traced

Fresh exact-current-source review distinguished the Master Plan §9.7 layers across real consumers:

1. requested source;
2. canonical extractor/media source identity;
3. source membership/provenance;
4. local History row identity;
5. privileged History replacement identity;
6. local-file/document identity;
7. duplicate/archive producer identity.

The review classified each observed weak/strong identity use by the authority of its final consumer rather than assuming one normalized string is valid for every purpose.

## 1. History title-only destructive duplicate selection — CONFIRMED distinct P2

### Canonical root

`BUG-HISTORY-DUPLICATE-IDENTITY-01`

### Exact current production path

`HistoryRepository.getDuplicateGroups()` at `6763fb1b...`:

- loads downloaded History rows;
- ignores only rows whose title is blank;
- groups remaining rows solely by `item.title.trim()`;
- treats every group with more than one row as a duplicate group;
- sorts the group by time/id.

`HistoryViewModel.deleteDuplicates()` then:

- chooses one row from the title group as retained;
- merges keyword assignments from the other rows into that retained row;
- passes all remaining row IDs to `HistoryRepository.deleteRecords()`.

`deleteRecords()` removes persistent History records and their playlist cross-references. This exact duplicate-removal path does not itself delete the physical media file, but it does irreversibly remove persistent History/relation state for rows selected by title equality alone.

### Why this is distinct from F17 `BUG-HISTORY-01`

F17 owns the mutation/Undo/relationship-convergence contract **after a History target has been selected for deletion**. This §9.7 defect is earlier authority: title coincidence selects unrelated media rows as the same semantic media and grants destructive deletion authority to that false equivalence.

Therefore a transactionally perfect F17 implementation would still delete the wrong rows if `getDuplicateGroups()` kept using title-only identity. Conversely, a strong duplicate-identity selector would not by itself fix F17's playlist/Undo/atomicity obligations for legitimately selected rows.

The prior review checkpoint `bc28e45045af7e4cb2a445495d589009c653c7cc` (`history-duplicate-identity.md`) already established this same semantic distinction. No later review checkpoint was found that semantically retracted or merged that root; later count-reconciliation checkpoints continued to treat semantic findings separately from F17 aliases. Exact-current source at `6763fb1b...` reproduces the same authority defect.

### Canonical reconciliation

The current private handoff listed `P0 3 / P1 3 / P2 25` but omitted `BUG-HISTORY-DUPLICATE-IDENTITY-01` while instructing §9.7 to reconcile History identity candidates against F17. That omission is inconsistent with the preserved distinct-root checkpoint and exact-current source.

Canonical correction in this checkpoint:

- add/adopt `BUG-HISTORY-DUPLICATE-IDENTITY-01` into the current open-root inventory;
- root-count delta: `+1 P2`;
- no new defect mechanism is invented here; this is a canonical handoff/count reconciliation of an already independently established root, freshly revalidated on the current implementation SHA.

Count:

- before: `P0 3 / P1 3 / P2 25`
- after: `P0 3 / P1 3 / P2 26`

## 2. LocalAdd provider-local document identity — CONFIRMED subfinding, +0

Exact current `LocalAddWorker.localEntryIdentity()` returns `doc:<documentId>` whenever `documentId` is nonblank and otherwise uses the URI. The worker then `distinctBy(::localEntryIdentity)` before processing.

Android document IDs are provider-local identifiers; the current key omits provider/document-authority namespace. Distinct documents from two providers can therefore expose the same `documentId`, collide in the process-local dedupe key, and cause one accepted LocalAdd entry to be silently dropped.

This is not a new root. It is the same weak-local-identity / silent-omission authority family as existing `BUG-LOCALADD-01`. The scheduled/narrow checkpoint `2026-09-10T224200Z__6763fb1b__checkpoint.md` is adopted as corroborating evidence only; its stale numeric count does not override the canonical count chain.

Disposition: `BUG-LOCALADD-01` subfinding, root delta `0`.

## 3. Privileged History replacement identity — CLEAN / PRESERVED

Exact current replacement authorization is stronger than numeric History row identity or title coincidence:

- `HistoryReplacementSourceIdentity.matches()` first accepts exact normalized source URL equality;
- supported YouTube forms may prove equivalence through the same stable video ID;
- otherwise source normalization is conservative rather than title-based;
- `HistoryKeywordAssignmentRepository.authorizeHistoryReplacementBlocking()` re-reads the current target row and requires source-identity match plus compatible type before granting replacement authority;
- replacement application independently requires the replacement source to match the expected source;
- `DownloadWorker` calls this authorization boundary with the expected source/type and exact replacement operation identity;
- source/type mismatch is mapped by `HistoryReplacementOutcomePolicy` to preserved failure rather than privileged success/deletion.

No title-only or bare-row-ID replacement authority was found in this exact path.

Disposition: `CLEAN / PRESERVED`, no root delta.

## 4. Requested source vs extractor/canonical source — CLEAN on reviewed metadata boundary

`ResultRepository` keeps the requested source separate from fetched extractor identity. Its metadata lookup builds/uses `ExtractorSourceIdentity` and calls `ExtractorSourceIdentityPolicy.matchesRequestedSource(requestedSource, identity)` before accepting fetched metadata for that source.

The reviewed boundary therefore does not treat a fetched title, row ID, or arbitrary redirected URL as sufficient attribution authority.

Disposition: `CLEAN on reviewed boundary`, no root delta.

## 5. Observe source/media identity — existing root, +0

The existing `BUG-OBSERVE-SOURCE-IDENTITY-01` checkpoint remains the owner of:

- semantic uniqueness of durable Observe source rows;
- canonical-equivalent source creation/update;
- concurrent Observe publication when a duplicate-prevention policy promises semantic duplicate suppression.

§9.7 does not create a second Observe identity root for the same producer/authority/fixed point.

Disposition: existing `BUG-OBSERVE-SOURCE-IDENTITY-01`, root delta `0`.

## 6. External input identity — existing earlier-layer root, +0

`BUG-LINK-INPUT-01` remains the §9.6 external input → typed semantic route → durable Download-admission root. It owns malformed/search/scheme-less/unsupported input being durably admitted under the wrong semantic source class.

§9.7 begins after semantic input admission and therefore does not duplicate that root.

Disposition: existing `BUG-LINK-INPUT-01`, root delta `0`.

## 7. Archive and command/source-role identity — CLOSED / PRESERVED

The independently closed P2-B review at `37d8007b8907274e0d72e2ee4d045d0124eb98d8` already verified B10 exact extractor-plus-media-ID archive identity and preserved P2-K role-aware command/source identity at this same implementation SHA.

No exact-current §9.7 path reviewed here weakens those identities. P2-B must not be reopened or decremented again absent a distinct current regression.

Disposition: B10 / P2-K `CLEAN / PRESERVED`, no root delta.

## Post-handoff scheduled/narrow checkpoint reconciliation

The review branch had advanced from the handoff-recorded `84218b43dd609a676c407acb05d3c9456bebbb72` to pre-write HEAD `1da201ef724acecd52786b7ac89a06908cb53e7e` through six scheduled/narrow checkpoints.

They were explicitly reconciled before this §9.7 decision:

- provider-local LocalAdd `documentId` collision: confirmed, adopted only as `BUG-LOCALADD-01` subfinding, +0;
- B7/finality material: corroboration of already-closed P2-B only, +0;
- Share global result-table/session-isolation candidate: still not independently established as a required invariant/effect, +0;
- backup sweep: existing roots only, +0;
- Youtuber restored visible-child-ID mismatch: still not verified through a concrete persisted-preference → runtime consumer correctness effect, +0;
- final no-material-change checkpoint: no semantic delta.

Their stale/provisional count statements do not override the canonical count in this checkpoint.

## §9.7 final disposition

- Review completed on exact implementation SHA `6763fb1b...`.
- One already-established but omitted distinct root is restored to the canonical handoff inventory: `BUG-HISTORY-DUPLICATE-IDENTITY-01` (P2).
- LocalAdd provider-local document-ID collision is a confirmed `BUG-LOCALADD-01` subfinding only.
- Privileged History replacement identity is independently source-reviewed CLEAN/PRESERVED.
- Requested-source vs extractor/canonical-source separation is CLEAN on the reviewed metadata boundary.
- Observe, external input, archive, and role-aware identity findings remain owned by their existing roots/closures.
- Canonical count is now `P0 3 / P1 3 / P2 26`.
- Contiguous independently CLEAN implementation basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`; this review changes review metadata/finding inventory, not implementation code.
- Overall repository verdict remains `NOT_CLEAN`.
- No Luna implementation prompt is issued by this checkpoint.

## Exact next review action

Continue sequentially to Master Plan §9.8 `History title-only duplicate deletion` on exact SHA `6763fb1b...`.

Fresh-trace the exact duplicate group construction, title normalization, retained-row ordering/choice, keyword/playlist/reference mutation, whether the production duplicate action itself deletes physical files, UI trigger, and Undo/recovery behavior. Use that trace to define the minimal correction boundary and acceptance matrix for `BUG-HISTORY-DUPLICATE-IDENTITY-01`, while keeping F17 `BUG-HISTORY-01` relationship/transaction/Undo obligations separate. Do not issue a Luna prompt until that §9.8 correction boundary is independently stable and unambiguous.

Independent source/structural review: EXECUTED.
Independent JVM/instrumentation/device execution: NOT EXECUTED.

INDEPENDENT EXECUTION: NOT EXECUTED
