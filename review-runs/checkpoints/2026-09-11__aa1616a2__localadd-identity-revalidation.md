# BUG-LOCALADD-01 — LocalAdd identity authority revalidation

Date: 2026-09-11

## Review basis

- Reviewed implementation SHA: `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Review mode: independent exploratory review from the fixed independently CLEAN basis while a separate Luna `BUG-OBSERVE-01` implementation wave is active.
- The active Luna implementation branch HEAD, commits, and diffs were not inspected, compared, reviewed, or relied upon for this decision.
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Verdict

**NOT_CLEAN / EXISTING P2 ROOT RECONFIRMED**

Defect: `BUG-LOCALADD-01` / F20

The fixed CLEAN basis still admits weaker-than-local-file identity at multiple LocalAdd suppression boundaries. A filename/basename can suppress a distinct file from another directory/provider, and the document-ID fallback is not provider-scoped. Unresolved display-name candidates are also silently discarded instead of remaining available for conservative user processing.

These are subfindings of the already-counted `BUG-LOCALADD-01` identity/admission root. They are not new roots, and they remain distinct from `BUG-LOCALADD-HANDOFF-01`, which owns durability/locator/payload handoff semantics after a candidate has been admitted.

## Exact-source evidence

### 1. Existing History is collapsed by basename alone

`app/src/main/java/com/ireum/ytdl/work/LocalAddWorker.kt` loads all History rows, flattens `downloadPath`, extracts only a basename, lowercases it, and builds `existingBaseNames`.

For each LocalAdd URI it obtains the display name, derives `baseName = title.ifBlank { name }`, and then executes an early skip when `existingBaseNames` already contains that basename.

Therefore distinct local objects such as:

- `/directory-A/video.mp4`
- `/directory-B/video.mp4`

or equivalent same-name documents exposed by different providers can be silently discarded before actual local identity is established. The filename stem is being used as exclusion authority even though F20 explicitly forbids that.

### 2. Earlier accepted work can extend the same weak basename exclusion

When LocalAdd immediately inserts a matched History item, the worker adds that item's `baseKey` to the mutable `existingBaseNames` set. A later same-name candidate in the same processing pass can therefore be rejected by the same basename-only authority even when it denotes a different local object.

This violates the F20 requirement that an earlier accepted batch item be compared using the same full local identity semantics rather than merely its filename stem.

### 3. Document-ID fallback omits provider authority

`localEntryIdentity(entry)` first uses `tree:<tree-uri>|<relative-path>` when usable tree metadata exists. This is a useful strong-identity path.

Without usable tree metadata it calls `DocumentsContract.getDocumentId(uri)` and returns:

`doc:<documentId>`

The URI/content-provider authority is not part of that key. Android document IDs are provider-local identifiers, not globally portable identities. Two different document providers may legitimately expose the same document-ID string and will collide in `entries.distinctBy { localEntryIdentity(entry) }`.

A provider-scoped identity must include the relevant provider/URI authority, or conservatively fall back to normalized full URI identity.

### 4. Full normalized URI fallback is already available but is bypassed by weaker document identity

If tree metadata and document ID are unavailable, `localEntryIdentity()` returns:

`uri:${uri.normalizeScheme()}`

This demonstrates that full URI-based identity is already available as a conservative fallback. The problem is not lack of any usable identifier; it is that a provider-local document ID is currently promoted above the full URI without its provider scope.

Exact repeated URI dedupe itself is a valid behavior to preserve.

### 5. Unresolved display-name candidates are silently dropped

The worker performs:

`val name = getDisplayNameFromUri(uri) ?: return@forEach`

A URI whose display name cannot be resolved is therefore silently skipped. Failure to resolve metadata is not proof that the local object duplicates an existing file or should disappear from the admission flow.

F20 requires same-name/unresolved ambiguity to fail open for user processing unless a strong duplicate identity is actually proven.

### 6. Session payload retains stronger locator information

`LocalAddEntryDto` persists both:

- `uri`;
- optional `treeUri`.

`LocalAddCandidateDto` likewise preserves `uri` and `treeUri`, and History insertion for immediately matched entries records the concrete URI in `downloadPath` plus `localTreeUri` / `localTreePath` when available.

Thus current data structures already carry enough stronger locator information to avoid basename-only identity in the ordinary path. This review does not require a schema migration merely to prove F20 identity.

### 7. No focused LocalAdd identity regression was found at the exact basis

The exact `app/src/test/java/com/ireum/ytdl/work` listing contains no LocalAdd worker identity test covering the F20 matrix. In particular, no exact-basis focused regression was found for:

- same filename in different directories;
- same filename from different providers;
- equal provider-local document IDs under different authorities;
- earlier accepted same-name batch item;
- unresolved display-name candidate;
- exact repeated URI/tree identity.

## Preserved positive behavior

The current source contains useful behavior that a correction should preserve:

- tree URI + relative path is already used when both are available;
- exact/full URI identity exists as a fallback;
- URL-based History match detection is separate from basename suppression;
- LocalAdd session entries/candidates preserve URI/tree locator information;
- cancellation/foreground/session recovery behavior is outside this identity correction unless exact source proves an interaction.

## Governing correction boundary

The Master Plan F20 contract remains applicable:

1. Filename or basename alone must never authorize dropping a local candidate.
2. Use one explicit local identity policy shared by:
   - input-batch dedupe;
   - comparison with already accepted items in the same session/pass;
   - comparison with persisted local History where strong local identity is available.
3. Strong identity may use, in priority/order appropriate to current architecture:
   - canonical tree URI + relative path;
   - provider-scoped document identity including provider authority;
   - normalized full URI.
4. A provider-local document ID without provider scope is insufficient authority.
5. Exact repeated URI/tree identity may dedupe.
6. Same-name candidates with distinct or unresolved strong identity must proceed rather than be silently discarded.
7. Failure to resolve display name or another non-identity metadata field must not itself become duplicate/exclusion proof. Preserve the candidate conservatively or surface a truthful per-item failure path according to current LocalAdd UX.
8. Preserve URL match detection, session recovery, cancellation, and current strong tree/URI semantics.
9. Add focused semantic regression coverage for:
   - same name across directories;
   - same name across providers;
   - same document-ID string across different provider authorities;
   - earlier accepted same-name batch item;
   - unresolved candidate metadata;
   - exact repeated URI;
   - exact tree+relative-path repeat.
10. Keep `BUG-LOCALADD-HANDOFF-01` separate. F20 owns admission/identity suppression; locator/payload durability across scheduling/restart remains the handoff root.

## Root/count reconciliation

- This is a revalidation of existing canonical P2 root `BUG-LOCALADD-01`, not a new root.
- The provider-local document-ID collision, basename suppression, and unresolved-candidate drop are subfindings/evidence of this root.
- Count delta: `0`.
- Canonical blocker count remains **`P0 3 / P1 3 / P2 25`**.
- Contiguous independently CLEAN Review Basis remains `aa1616a2c7710b878c44949a5f74ad02c6706d8d`.
- The separate active Luna `BUG-OBSERVE-01` implementation was not inspected or relied upon.
- No Master Plan or authoritative-ledger modification is authorized by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
