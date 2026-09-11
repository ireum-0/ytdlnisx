# Independent review checkpoint — History duplicate identity fragment reconciliation

Date: 2026-09-11
Reviewed implementation branch: `checkpoint/pre-baseline-review`
Reviewed start SHA: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
Reviewed final SHA: `4e693fdb73842df350f0635b88c5a0143e0f3c3d`
Verified implementation chain: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca` -> `afc11fbe5eb7c9a1e095cc72c87e6affacac3569` -> `4e693fdb73842df350f0635b88c5a0143e0f3c3d`
Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
Finding/root: `BUG-HISTORY-DUPLICATE-IDENTITY-01`
Verdict: **NOT_CLEAN / REVIEW COMPLETE / CORRECTION BOUNDARY STABLE**

## Reconciliation

This checkpoint independently re-reviews the full focused implementation scope and reconciles the concurrent/narrow review checkpoints through `1e2801af66b2f4510663384e2eb9ce4c6f571e07`. Their fragment-collision observation is confirmed by exact-current production source and an exact upstream extractor source. It is evidence within the already-counted `BUG-HISTORY-DUPLICATE-IDENTITY-01` root, not a new semantic root.

Canonical blocker-count delta: **+0**.
Canonical blocker count remains **P0 3 / P1 3 / P2 26**.
The canonical CLEAN implementation basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`; it must not advance to `4e693fdb73842df350f0635b88c5a0143e0f3c3d` yet.

## What the implementation fixed correctly

The two implementation commits remove title-only grouping from `HistoryRepository.getDuplicateGroups()` and introduce a destructive-grade key containing `DownloadType`, a source kind, and a source value. Supported YouTube URLs use a stable video ID; generic HTTP(S) sources use a conservative source key. Blank, malformed, command-like, local, and otherwise unprovable sources fail closed. The exact current survivor mechanics remain `time ASC`, then `id ASC`, with `group.first()` retained. `HistoryViewModel.deleteDuplicates()`, keyword-assignment merge behavior, playlist-reference deletion, and DB-only deletion behavior were not broadened into F17, and no physical-media deletion was introduced.

The follow-up commit `4e693fdb...` also correctly recognizes canonical short-form YouTube hosts before generic HTTP parsing.

## Remaining correctness defect

At exact final SHA `4e693fdb73842df350f0635b88c5a0143e0f3c3d`, `HistoryDuplicateIdentity.keyFor()` delegates generic WEB_URL identity to `WebUrlInput.strictSourceIdentityKey(source)`.

`WebUrlInput.resolveExtractorInput()` preserves the original trimmed WEB_URL as `dispatchValue`, including its fragment. In contrast, `strictSourceIdentityKey()` constructs identity from scheme, host, effective port, raw path, and raw query, but omits `URI.rawFragment`.

That omission is unsafe for this destructive duplicate-selection boundary because URI fragments can be extractor-significant. Exact upstream yt-dlp source `yt-dlp/yt-dlp@bbc809a1161d3bfca51fa36f59dda35556ee85a0`, file `yt_dlp/extractor/sina.py`, defines Sina video matching so a fragment `#<id>` is a media ID and includes `http://video.sina.com.cn/#250576776` as a matching example.

Therefore two same-type History rows such as:

- `http://video.sina.com.cn/#250576776`
- `http://video.sina.com.cn/#250576777`

are distinct extractor/media inputs but collapse to the same current generic strict key (`http://video.sina.com.cn:80/`). `HistoryRepository.getDuplicateGroups()` can consequently place them in one destructive duplicate group. `HistoryViewModel.deleteDuplicates()` then merges keyword assignments into the retained row and deletes the other History record; repository deletion also removes playlist cross-references for the deleted History ID. This is a real destructive false-positive equivalence, not only a helper-level theoretical mismatch.

The focused JVM and instrumentation source coverage added in the implementation does not contain a fragment-sensitive case, so the defect is not excluded by the reported passing tests.

## Root and scope reconciliation

This remains `BUG-HISTORY-DUPLICATE-IDENTITY-01`: it is an insufficiency in the authority used to decide which History rows may be treated as the same media strongly enough for destructive automatic merge/delete. It does **not** create a new root and does **not** merge with F17 / `BUG-HISTORY-01`, which continues to own mutation/playlist-reference/Undo/atomicity behavior after legitimate targets have been selected.

## Stable correction boundary

The next correction is narrowly bounded as follows:

1. Generic/unknown HTTP(S) identity used to authorize destructive History duplicate grouping must preserve URI fragment information conservatively; fragments cannot be discarded merely because ordinary browser navigation often treats them as client-side state.
2. Preserve all existing distinctions already carried by the strict key: scheme, host, effective port, raw path, and raw query. Add fragment sensitivity rather than relaxing any existing distinction.
3. Preserve supported provider-specific YouTube stable-video-ID equivalence exactly, including equivalent canonical YouTube URL forms and `youtu.be` handling.
4. Preserve `DownloadType` as part of duplicate identity so incompatible media types never group.
5. Blank, malformed, command-like, local, unsupported, ambiguous, or otherwise unprovable source values must continue to fail closed. Raw textual equality must not become universal authority for arbitrary non-web inputs.
6. Preserve exact current duplicate survivor mechanics: `time ASC -> id ASC -> group.first()`.
7. Preserve current UI confirmation, keyword-assignment merge, playlist-cross-reference cleanup, and DB-only/no-physical-media deletion behavior. Do not broaden this repair into F17.
8. `HistoryReplacementSourceIdentity` also consumes `WebUrlInput.strictSourceIdentityKey`. Therefore a global change to that helper is acceptable only if all affected production callers are audited and existing destructive-replacement/archive/identity contracts remain conservative. A duplicate-specific fragment-preserving key is also acceptable. Do not blindly change a shared identity primitive without caller/regression review.
9. No Room schema migration is required for this correction.

## Required verification matrix for the review-fix

- Same generic endpoint/path with distinct extractor-significant fragments (including Sina-style `#<id>` values) -> **must not group**.
- Same conservative generic source including the same fragment + same `DownloadType` -> may group when the identity proof is otherwise valid.
- Meaningful query differences -> preserved as distinct.
- HTTP vs HTTPS for generic providers -> preserved as distinct.
- Same source with incompatible `DownloadType` -> preserved as distinct.
- Equivalent supported YouTube URL forms with the same stable video ID and compatible type -> still group.
- Blank/command-like/local/malformed/ambiguous/unprovable sources -> fail closed.
- Legitimate duplicate group -> exact existing comparator and `group.first()` survivor preserved.
- Production-wiring coverage must prove fragment-distinct History rows are not returned by `getDuplicateGroups()` and therefore do not enter destructive deletion/merge flow.
- Re-run relevant regression coverage including `HistoryReplacementSourceIdentityTest`, `DownloadConfigurationDuplicatePolicyTest`, `DownloadArchiveIdentityTest`, focused History duplicate identity tests, full JVM suite, Kotlin/KSP/android-test compile checks, and instrumentation when a device/emulator is available.

## Preserved prior decisions

P2-B B10/archive identity and P2-K role-aware source/command identity remain closed/preserved unless exact-current regression evidence says otherwise. §9.7 canonical media/source identity conclusions remain governing. No Master Plan, authoritative ledger, or unrelated review finding is modified by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
