# BUG-LOCALADD-01 — LocalAdd identity authority current-basis revalidation

Date: 2026-09-11

## Review basis

- Fixed independently CLEAN Review Basis: `4ef990e00a354a71b33c4df8f215cc27337cdce9`
- Prior exact-basis root checkpoint: `22dacb30e0874ad85f08b8ba4b7da2dd52d49626` at `aa1616a2c7710b878c44949a5f74ad02c6706d8d`
- Active implementation wave: P2 `BUG-METADATA-02` / F13, started from `4ef990e0...`
- Moving implementation diff inspected or relied on: **NO**
- Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, F20
- Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`

## Overlap guard

Exact compare `aa1616a2... -> 4ef990e0...` is eight additive commits. The changed-file set is limited to F3/F12 source-authority and automatic-keyword production-wiring files: `ResultRepository`, `SourceSnapshot`, NewPipe/YTDLP extraction, Observe worker/tests, AutomaticKeyword worker/tests, and SourceSnapshot tests.

No LocalAdd producer, `LocalAddWorker`, LocalAdd storage/session DTO, or LocalAdd UI admission file changed in that interval. The prior root therefore requires only narrow current-basis source confirmation rather than a duplicate full re-review.

## Verdict

**NOT_CLEAN / existing P2 `BUG-LOCALADD-01` remains OPEN at `4ef990e0...`.**

Count delta: **0**.

Canonical blocker count remains **P0 2 / P1 2 / P2 25**.

CLEAN basis remains `4ef990e00a354a71b33c4df8f215cc27337cdce9`.

## Exact current-source confirmation

`LocalAddWorker.kt@4ef990e0...` still has all established F20 authority gaps:

1. It builds `existingBaseNames` by flattening persisted History `downloadPath`, extracting only the filename stem, lowercasing it, and treating membership in that set as early exclusion authority.
2. After immediately inserting a matched History item, it adds that item's basename key to the same mutable set, allowing an earlier accepted same-name item to suppress a distinct later file in the same pass.
3. `localEntryIdentity(entry)` still prefers `doc:<documentId>` when tree metadata is unavailable, without including the document provider authority. Provider-local document IDs are therefore promoted as globally comparable identity.
4. Full normalized URI identity remains available only as the fallback after that weaker provider-unscoped document-ID key.
5. `getDisplayNameFromUri(uri) ?: return@forEach` still silently drops a candidate when display-name metadata cannot be resolved, even though metadata resolution failure is not duplicate proof.
6. Strong exact checks for tree URI + relative path and exact download URI remain present and should be preserved.

## Governing F20 invariant

The pinned Master Plan F20 states:

- filename stem alone never discards a local file;
- use actual local identity such as tree URI + relative path, provider-scoped document identity, or normalized full URI;
- same-name ambiguity proceeds rather than being silently dropped;
- preserve exact URI/tree dedupe, URL match detection, session recovery, and cancellation.

Current source still violates that invariant at the same suppression boundaries described by the prior checkpoint.

## Root reconciliation

- This is the already-counted P2 `BUG-LOCALADD-01` identity/admission root; no new root is introduced.
- Basename suppression, provider-unscoped document ID, and unresolved display-name drop remain same-root evidence.
- Keep P2 `BUG-LOCALADD-HANDOFF-01` separate: it owns persisted-session -> WorkManager ownership/recovery after candidate admission, not local-file identity suppression.
- F12 `BUG-KEYWORD-01` remains CLOSED at `4ef990e0...` absent concrete regression; this review found no shared-domain interaction.
- Active F13 implementation state is unchanged and was not inspected.

## Required correction boundary carried forward

1. One explicit local identity policy must govern input-batch dedupe, already-accepted same-session items, and persisted local History comparisons where strong identity exists.
2. Bare basename/filename must never authorize exclusion.
3. Provider-local document IDs must be provider-scoped or fall back conservatively to normalized full URI.
4. Exact URI/tree identity may dedupe.
5. Same-name but distinct/unresolved strong identities must continue through processing.
6. Display-name resolution failure must not silently mean duplicate; preserve the candidate or surface a truthful per-item failure according to current UX.
7. Add focused coverage for same-name different directories/providers, same document ID across authorities, earlier accepted same-name batch item, unresolved display name, exact repeated URI, and exact tree+relative-path repeat.

INDEPENDENT EXECUTION: NOT EXECUTED