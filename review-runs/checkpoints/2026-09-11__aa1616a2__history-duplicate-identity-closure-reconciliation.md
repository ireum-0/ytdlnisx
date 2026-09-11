# BUG-HISTORY-DUPLICATE-IDENTITY-01 — canonical closure reconciliation

Date: 2026-09-11

## Reviewed implementation range

- Previous contiguous independently CLEAN Review Basis: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
- Active review-fix start SHA: `4e693fdb73842df350f0635b88c5a0143e0f3c3d`
- Completed implementation SHA / remote branch HEAD: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Exact active review-fix relation: `4e693fdb... -> aa1616a2...`, one direct child commit.
- Full cumulative range for basis advancement: `6763fb1b... -> afc11fbe... -> 4e693fdb... -> aa1616a2...`.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

The implementation branch remote HEAD was independently verified as exactly `aa1616a2c7710b878c44949a5f74ad02c6706d8d`, and the reported parent relation to `4e693fdb73842df350f0635b88c5a0143e0f3c3d` was independently verified.

## Verdict

**CLEAN FOR THE FULL `BUG-HISTORY-DUPLICATE-IDENTITY-01` IMPLEMENTATION SCOPE / ROOT FIXED**

`BUG-HISTORY-DUPLICATE-IDENTITY-01` transitions from OPEN P2 to **FIXED/CLOSED** at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.

Overall project verdict remains `NOT_CLEAN` because unrelated canonical roots remain open.

## Exact completed change

The final review-fix adds `WebUrlInput.strictSourceIdentityKeyPreservingFragment()`, which reuses the existing strict HTTP(S) source key semantics while explicitly appending `URI.rawFragment` when present.

`HistoryDuplicateIdentity` alone switches its generic WEB_URL destructive identity to this fragment-preserving helper.

The pre-existing `WebUrlInput.strictSourceIdentityKey()` remains fragment-blind and its behavior remains the default because `preserveFragment` defaults to false. The underlying parser now retains `rawFragment`, but no existing caller changes output unless it explicitly requests fragment preservation.

## Full original finding re-review

### Generic extractor-significant fragments

The previously confirmed Sina-style counterexample is now separated:

- `http://video.sina.com.cn/#250576776`
- `http://video.sina.com.cn/#250576777`

produce distinct generic destructive identity keys because the raw fragment is preserved.

Identical fragment-bearing URLs continue to share identity when all other strict key components and DownloadType match. An explicit empty fragment (`...#`) is also distinct from no fragment, which is conservative at this destructive boundary.

### Scheme / host / effective port / raw path / raw query

The fragment-preserving helper reuses the existing strict source-key construction:

- HTTP and HTTPS remain distinct;
- normalized host identity remains unchanged;
- default ports continue to normalize within the same strict scheme while non-default effective ports remain identifying;
- raw path remains identifying;
- raw query remains identifying and is not passed through the non-identifying tracking-parameter drop policy for this destructive key;
- raw fragment is now additionally identifying.

No newly relaxed equivalence was introduced.

### YouTube provider identity

Official YouTube hosts are handled before generic URL identity. A valid stable YouTube video ID remains the identity, preserving equivalence across supported canonical/watch/short forms such as `youtube.com/watch?v=<id>` and `youtu.be/<id>`.

A recognized YouTube host without a valid stable video ID remains ambiguous and fails closed rather than falling back to generic URL equality.

### DownloadType boundary

`HistoryDuplicateIdentity.Key` still includes `DownloadType`. Equivalent source/media identity with incompatible types does not enter the same destructive group.

### Fail-closed boundary

Blank, command-like, local path, `content://`, malformed/unsupported, and otherwise unprovable source forms continue to return no destructive identity key and are excluded from automatic grouping.

### Production grouping and survivor semantics

`HistoryRepository.getDuplicateGroups()` still:

1. derives a `HistoryDuplicateIdentity.Key` per downloaded row;
2. excludes rows without a provable key;
3. groups by that typed key;
4. filters to groups larger than one;
5. sorts each group by `time ASC` and then `id ASC`.

`HistoryViewModel.deleteDuplicates()` still retains `group.first()` and merges/deletes only subsequent members. The review-fix did not change survivor selection, keyword-assignment merge, playlist-reference cleanup, DB-row deletion behavior, or physical-media behavior.

The added Android production-wiring regression directly inserts two fragment-distinct Sina-style rows and proves `HistoryRepository.getDuplicateGroups()` returns no duplicate group for them.

## Shared-helper / preserved-contract audit

### `HistoryReplacementSourceIdentity`

Preserved. It continues to use the original `WebUrlInput.strictSourceIdentityKey()` rather than the new fragment-preserving duplicate-specific helper. Its established replacement-source contract was therefore not silently changed.

### P2-K / Download configuration duplicate policy

Preserved. `DownloadConfigurationDuplicatePolicy` was unchanged by the cumulative range and continues its role-aware yt-dlp command/source normalization. It does not consume the new fragment-preserving helper.

### B10 / download-archive identity

Preserved. `DownloadArchiveIdentity` was unchanged and continues exact extractor-token + media-ID archive semantics. It does not consume the new helper.

### F17 / `BUG-HISTORY-01`

Not merged or closed here. The identity repair changes which rows may legitimately enter duplicate selection; it does not establish mutation/reference/Undo/atomicity correctness after a legitimate destructive target set has already been selected. F17 remains a separate open root.

## Tests/evidence present in final source

Exact final source includes focused coverage for:

- equivalent YouTube forms;
- incompatible DownloadTypes;
- meaningful generic query distinction;
- HTTP-vs-HTTPS distinction;
- fragment-distinct and same-fragment generic URLs;
- no-fragment versus explicit-empty-fragment distinction;
- conservative generic canonical host equivalence;
- fail-closed unsupported/unprovable sources;
- provider/generic separation;
- real Room-backed `getDuplicateGroups()` production wiring for same-title different sources, typed YouTube grouping/survivor order, unknown/ambiguous rejection, and fragment-distinct Sina-style sources.

Implementation-agent test/build reports, where available, remain implementation evidence only and are not converted into independent execution claims.

## Cumulative carry-forward / CLEAN-basis decision

Independent compare of `6763fb1be188fb000b9e9a665c7b3fe349fd40ca..aa1616a2c7710b878c44949a5f74ad02c6706d8d` shows exactly three commits and only these production-domain files:

- `HistoryRepository.kt`;
- new `HistoryDuplicateIdentity.kt`;
- additive `WebUrlInput.kt` fragment support;
- focused unit/instrumentation tests.

The only shared-helper production change is additive fragment capture plus an opt-in fragment-preserving key; existing key outputs remain unchanged by default. No intervening cumulative commit semantically modifies the independently revalidated Observe, backup capture/restore, or metadata-enrichment roots recorded from the former CLEAN basis.

Therefore the full cumulative History duplicate implementation range is independently CLEAN for its scope, and the contiguous independently CLEAN Review Basis advances to:

`aa1616a2c7710b878c44949a5f74ad02c6706d8d`

Existing unrelated open-root evidence from the former basis carries forward where the three-commit range is demonstrably unrelated; those roots remain open rather than being implicitly re-reviewed or closed.

## Canonical root/count reconciliation

Before this closure: `P0 3 / P1 3 / P2 26`.

- `BUG-HISTORY-DUPLICATE-IDENTITY-01`: OPEN P2 -> FIXED/CLOSED.
- Count delta: `P2 -1`.
- Resulting canonical count: **`P0 3 / P1 3 / P2 25`**.
- Overall project verdict: `NOT_CLEAN`.
- Authoritative ledger and Master Plan are not modified by this checkpoint.

This disposition is consistent with the earlier independent current-SHA closure checkpoint `bc770362e44c3a33a98437f9c2f2c1b7dc01364e`; this record reconciles that closure with the current protocol/handoff and explicitly seals the CLEAN-basis advancement after the completion report was received.

INDEPENDENT EXECUTION: NOT EXECUTED