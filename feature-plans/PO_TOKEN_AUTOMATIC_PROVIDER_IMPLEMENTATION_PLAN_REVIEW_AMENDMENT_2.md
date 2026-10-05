# Automatic YouTube PO Token Provider — Review Amendment 2

record_kind: FEATURE_IMPLEMENTATION_PLAN_AMENDMENT
record_status: REVIEWED
production_change_authorized_by_this_document: NO
correctness_baseline_reopened: NO

applies_to_base_plan_blob: 9c31f078db9e7185bb4288052d256ee7b102e5e5
applies_to_review_amendment_blob: 9dd9b4b5469cb4f3677d8d78362a9509e08d08a5
precedence: THIS_AMENDMENT_OVERRIDES_CONFLICTING_EARLIER_PLAN_TEXT

## Effective plan chain

The effective implementation contract is:

1. base plan blob 9c31f078db9e7185bb4288052d256ee7b102e5e5;
2. first review amendment blob 9dd9b4b5469cb4f3677d8d78362a9509e08d08a5;
3. this amendment;
4. newest amendment wins on conflict.

This document does not authorize production implementation.

## 1. Exact yt-dlp plugin activation directory

The AUTO plugin path must match the reviewed yt-dlp loader shape exactly.

For a custom `--plugin-dirs DIR`, current yt-dlp enumerates the direct children of `DIR` as
candidate plugin packages and then looks for the `yt_dlp_plugins` namespace under each package.

Use an immutable activation layout equivalent to:

```
<provider-runtime-root>/
  active/
    <activation-id>/
      search-root/                    <-- exact value passed to --plugin-dirs
        ytdlnisx-local/               <-- only allowed direct-child package
          yt_dlp_plugins/
            extractor/
              getpot_ytdlnisx.py
  previous/
    ...
  staging/
    ...
```

Required invariants:
- `search-root` is the exact directory passed to `--plugin-dirs`;
- it contains exactly one allowed direct-child provider package;
- that package contains only the manifested provider payload and explicitly allowed package metadata;
- previous, staging, temporary, interrupted, and rollback materializations are outside the active
  search root and are never simultaneously discoverable;
- unexpected extra direct-child packages, plugin archives, symlinks, or executable plugin
  candidates fail AUTO preparation before native target start;
- activation publishes an already fully verified immutable directory;
- the provider version/hash discovered by yt-dlp must correspond to the same activation identity
  returned by PoTokenProviderRuntime.

Do not pass a directory whose direct child is already `yt_dlp_plugins` unless an enclosing package
directory makes the loader contract above true.

Required tests:
- real bundled yt-dlp discovers the provider from the exact search-root level;
- one-level-too-shallow and one-level-too-deep paths do not count as proof;
- current, previous, and staging materializations may coexist on disk while only current is
  discoverable;
- a planted second package, plugin archive, or symlink candidate causes fail-closed preparation;
- discovered provider identity equals the verified active materialization identity.

## 2. Exact-final live acceptance requires controlled completion

Progress from only one selected media component is not enough for feature acceptance.

On the exact final published SHA, the controlled public YouTube acceptance case must complete the
download successfully through normal semantic completion.

For the selected media set:
- every selected GVS media component that is actually fetched must carry a PO-token query value
  attributable to the current first-party provider generation;
- a correct token on one component does not validate another component;
- multi-stream selections require component-by-component token-application proof;
- merge/finalization and normal output-publication ownership checks must complete successfully.

A first-byte or generic progress event is not sufficient for `ACCEPTED`.

If external service availability prevents controlled completion, retain
`IMPLEMENTED_NOT_RUNTIME_ACCEPTED`; do not weaken the gate.

Deterministic verification must include a multi-component case where a missing or mismatched token on
any selected GVS component fails the acceptance predicate.

## 3. Secret-safe token-application observation

Token-application proof must not create a new raw token-bearing URL or token diagnostic surface.

Required design:
- compare provider-returned token material with selected GVS query material only inside a tightly
  scoped in-memory verification seam;
- reduce the result immediately to non-sensitive structured facts such as component identity,
  boolean match, count, and completion state;
- persist only reduced facts;
- do not use ordinary stdout URL printing, verbose command output, command logging, notifications,
  exported diagnostics, or production debug logs to expose token-bearing URLs;
- do not persist raw provider token values, query values, content binding, Visitor Data, generation
  secret, or secret-file contents;
- do not add a user-reachable production switch that exposes raw token material for verification.

Prefer an instrumentation-only or otherwise non-user-reachable observer. If a production seam is
required for testability, its outward-facing value must already be sanitized/reduced.

Required tests:
- successful evidence contains per-component boolean matches but no raw token-bearing URL;
- deliberate mismatch exposes only a sanitized mismatch fact;
- logging/export/notification paths never receive the compared raw values;
- PO-token trace logging remains disabled.

## 4. Non-reentrant compatibility probe under one held consumer interval

The earlier continuous-consumer requirement must not be implemented by recursively invoking the
public/top-level `YoutubeDLCompat.executeWithQuiescence()` path from inside an already-held AUTO
consumer interval.

Required structure:
1. ordinary runtime initialization completes through existing mutation authority;
2. AUTO execution acquires one outer consumer interval;
3. runtime identity and compatibility cache are evaluated inside that interval;
4. a compatibility miss uses a dedicated internal, non-mutating probe helper that operates under the
   already-held consumer context;
5. the probe has its own distinct process identity/native-generation barrier;
6. probe descendants must be proven quiescent;
7. without releasing the outer consumer interval, provider lease/arguments/environment are prepared
   and the real target is launched;
8. the outer consumer interval remains held through target native finalization.

The implementation may introduce a scoped Consumer authority token analogous to Mutation, or another
equivalent enforceable internal ownership mechanism.

The probe must not:
- perform runtime mutation or self-update;
- publish media output;
- reuse the target process identity;
- leave an unresolved native generation before target launch.

Required tests:
- compatibility miss performs exactly one dedicated probe;
- compatibility hit skips the probe;
- probe and target identities are distinct;
- top-level consumer execution is not recursively re-entered;
- a waiting mutation cannot enter between probe completion and target launch;
- unresolved probe quiescence blocks target launch and preserves existing durable recovery semantics.

## 5. Stage and gate overrides

Stage A additionally requires exact activation-root shape and real-loader path-level proof.

Stage C additionally requires the non-reentrant compatibility-probe structure.

Stage E additionally requires:
- previous/staging materialization isolation;
- multi-component deterministic token-application proof;
- secret-safe evidence reduction tests.

Stage G requires successful controlled download completion. Progress-only evidence is insufficient.
For multi-stream selection, every selected GVS component must satisfy the sanitized application
predicate.

Gate 1 fails if the active search root has any unexpected discoverable package/archive or if the
actual `--plugin-dirs` level does not match the reviewed loader contract.

Gate 3 fails if compatibility probing recursively reacquires the top-level consumer path or leaves an
unresolved probe generation before target launch.

Gate 7 fails if:
- only part of a multi-component selected media set is proven tokenized;
- the controlled download does not complete;
- acceptance evidence leaks or persists raw token-bearing URLs or raw token material.

## 6. Later implementation prompt requirements

The implementation prompt must include:
- exact single-package activation search-root structure and stale/staging isolation;
- real yt-dlp discovery proof at the exact directory level supplied to `--plugin-dirs`;
- non-reentrant compatibility probing under the already-held consumer interval;
- secret-safe in-memory component-level token-application observation;
- exact-final controlled download completion, covering every selected GVS component in multi-stream
  selection.

These requirements override earlier wording that would permit a broader plugin root, recursive
top-level compatibility execution, raw URL/token evidence, or progress-only final acceptance.
