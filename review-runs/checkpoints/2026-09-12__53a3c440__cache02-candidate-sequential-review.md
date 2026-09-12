# Task 005 / BUG-CACHE-02 candidate sequential independent review

Date: 2026-09-12

## Exact review state

- Candidate: `53a3c44037a4a4b08a3ab5f60b5ad768b6d1b39b`
- Candidate branch: `candidate/overnight-20260912-cache-saf`
- Queue base: `36b43464b8106d90d672ba94718ccee58f38974f`
- Latest independently CLEAN canonical basis: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`
- Governing promotion checkpoint: `6cb74ff40b754f780422f114ffff1e6cdfd5bc24`

The candidate branch remote HEAD was independently verified at the exact candidate SHA. Its sole parent is exact queue base `36b43464...`. Comparison against canonical `9edd3e23...` yields merge base `36b43464...` and a diverged one-commit branch, so the candidate has not been integrated into canonical history.

## Verdict

**CLEAN FOR ROOT / READY FOR SEPARATE REPLAY OR REIMPLEMENTATION.**

This is a candidate verdict only. It is not canonical integration, does not close canonical `BUG-CACHE-02`, and does not advance the CLEAN basis.

Canonical count delta: `0`.
Canonical count remains: **P0 2 / P1 1 / P2 34**.
CLEAN basis remains: `9edd3e2344caa9da0e576611eeb7d69e3cfd2d71`.

## Root revalidation and correction boundary

The governing root is loss of storage-authority type: a persisted SAF `content://` cache tree was previously transformed by `formatPath(...)` into a raw `/storage/...` pathname and then consumed by Download/Terminal native staging through ordinary `File`, marker/manifest, and yt-dlp semantics without proof of raw/native filesystem authority.

The candidate chooses the allowed smallest coherent authority model: cache staging is raw-filesystem-only.

`FileUtil.getCachePath(context)` now:

1. resolves the app-owned default first;
2. returns that default when the persisted value is blank;
3. calls `resolveRawCachePath(...)` for a configured value;
4. rejects every `content://` value rather than deriving a raw path from it;
5. accepts app-owned filesystem roots directly;
6. accepts a non-app-owned raw root only when an actual raw directory probe is directly writable;
7. falls back to the app-owned default when the persisted value no longer establishes direct raw authority.

This revalidation happens whenever production obtains the cache root, so an already-persisted provider-only or later-inaccessible value cannot donate SAF authority to a later native staging generation. Existing Download, Terminal, cache-maintenance, log/cookie staging, and related consumers that call `getCachePath(...)` therefore consume the same effective raw/fallback decision rather than a fabricated provider-derived pathname.

The candidate also changes cache configuration admission: `FolderSettingsFragment.changePath(..., CACHE_PATH_CODE)` calls `FileUtil.isSupportedCachePathSelection(...)` before persisting the selection. A SAF tree returned by `ACTION_OPEN_DOCUMENT_TREE` is not persisted as native cache authority. The summary instead reflects the effective fallback root.

## Production-wiring review

Relevant production consumers continue to use `FileUtil.getCachePath(...)` as an ordinary filesystem root. That is coherent with the selected raw-only authority model because provider-only values are removed at the resolver boundary before these consumers receive a path.

The app-owned default remains supported. An explicitly stored app-owned raw filesystem path remains supported. A non-app-owned raw path is accepted only on a positive direct-write probe rather than on a persisted URI grant.

The candidate therefore closes the original producer/consumer chain:

`SAF chooser/provider grant`
→ **rejected as cache staging authority**
→ `getCachePath()` revalidates stored mode on consumption
→ provider-only/revoked/unsupported value falls back before staging root construction
→ Download/Terminal/native consumers receive only a positively supported raw root.

## Tests as evidence

Luna reported focused emulator coverage showing:

- persisted primary/non-primary SAF tree strings resolve to fallback before native cache resolution;
- app-owned filesystem cache remains supported;
- focused instrumentation: 2/2 PASS;
- full JVM: 610/610 PASS;
- KSP/debug/androidTest compile PASS;
- `git diff --check` PASS.

These execution claims are treated as implementation-agent evidence only. No tests were independently executed in this review.

## Non-blocking integration note

The current cache preference UI still launches `ACTION_OPEN_DOCUMENT_TREE`, while the selected authority model deliberately rejects every returned `content://` tree for cache staging. As a result, that picker no longer provides a successful way to choose a new custom cache root through this UI. This is not a residual of `BUG-CACHE-02`: it fails closed rather than converting provider authority into raw authority, and the task explicitly allowed dropping unsupported SAF custom-cache locations. It should nevertheless be treated as a UX/product follow-up if custom raw-filesystem cache selection is intended to remain user-configurable.

The rejected selection also takes a persistable SAF permission before validation. That is unnecessary retained permission/hardening debt, but it does not grant the rejected provider tree cache-staging authority and does not reopen this correctness root.

## Integration consequence

Do not merge/cherry-pick/rebase/transplant automatically. The candidate is one commit off the old queue basis and canonical F14 is a sibling commit. Replay or reimplementation on the latest CLEAN/canonical line must remain a separate implementation step, followed by cumulative independent review.

`BUG-CACHE-02` remains canonically OPEN until such an implementation is actually integrated and independently reviewed in canonical history.

INDEPENDENT EXECUTION: NOT EXECUTED
