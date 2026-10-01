# BUG-UPDATER-03 current-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 8e8cc0e1e35997ee3f4ec6534e089df4f46f51a5
clean_review_basis: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 4
primary_lens: L2 Identity & provenance DEEP
supporting_lenses:
- L6 Cross-feature semantic propagation
- L1 Durability & recovery
independent_execution: NOT EXECUTED

## Existing hardening to preserve

The updater now treats desired source as explicit durable configuration and
rejects a present blank source in readDesiredSourceLocked().

That consumer-side check is desirable and should remain fail-closed.

The restore stack also already centralizes supported payload normalization and
validation before durable Reset execution, and Merge/Reset share portable-key
filtering. The correction should strengthen that admission contract rather than
teach downstream updater paths to tolerate malformed configuration.

## Current source result

ytdlp_source is intentionally portable user intent.

BackupRestoreParser normalization accepts portable settings and validateSettings()
accepts String values by type without a ytdlp_source-specific semantic check.

A supported restore payload can therefore carry:
- key = ytdlp_source
- type = String
- value = blank

Merge can persist the blank String through its settings path.
Reset can publish the same accepted portable setting.

Current UpdateUtil then observes that the key is present and requires the value
to be nonblank.

The restore boundary therefore accepts durable executable configuration that its
authoritative consumer rejects.

This root is independent from BUG-UPDATER-02:
- BUG-UPDATER-02 is about provenance/generation authority and convergence;
- BUG-UPDATER-03 is about validity of the portable desired-source value itself.

## Exact invariant

Every supported restore representation that can persist ytdlp_source must apply
the same semantic validity contract required by the authoritative updater
consumer before any restore mutation begins.

For present ytdlp_source:
- blank is invalid;
- valid built-in sources remain valid;
- valid nonblank custom source remains valid under the existing product contract.

Malformed imported source must never become durable app configuration and must
never rely on a later startup exception/runCatching path for rejection.

Absence is not the same as present blank. Preserve existing absence/default
semantics rather than rewriting blank to absence or stable.

## Narrow implementation boundary

Add the semantic source check at the shared restore
validation/normalization authority before Merge/Reset mutation.

Preferred correction:
- centralize the exact "present source must be nonblank" predicate where every
  supported restore form passes through it;
- reject malformed payload before any setting/database/file mutation;
- preserve the original valid source text under current semantics;
- keep unrelated String settings type-only unless they have their own explicit
  semantic contracts.

Touch UpdateUtil only if a tiny shared validity predicate is required to prevent
restore/consumer drift. Do not move the defect downstream.

Preserve:
- valid stable/nightly/master values;
- valid nonblank custom sources;
- current backup portability of desired source intent;
- BUG-UPDATER-02 destination-local provenance rules;
- Restore transaction/journal/rollback behavior.

## Forbidden shortcuts

- converting blank restored source to stable
- deleting the blank key so default semantics hide malformed input
- trimming/canonicalizing all custom source strings without a separately proven contract
- accepting malformed restore and catching the updater exception later
- fixing only startup while manual updater still consumes malformed state
- validating only current typed payload while legacy supported forms bypass it
- rejecting every unknown/custom nonblank source
- partially applying Merge/Reset before discovering the malformed source
- folding provenance/generation changes from BUG-UPDATER-02 into this root

## Acceptance matrix

- Merge payload with present blank ytdlp_source is rejected before any mutation
- Reset payload with present blank ytdlp_source is rejected before destructive
  restore phase/publication
- oldest still-supported legacy payload carrying blank source is rejected
- current typed payload carrying blank String is rejected
- whitespace-only value is handled consistently with the authoritative consumer's
  exact blank predicate
- stable survives validation and restore unchanged
- nightly survives unchanged
- master survives unchanged
- representative valid nonblank custom source survives unchanged
- absent ytdlp_source preserves existing absence/default semantics
- malformed source rejection leaves unrelated destination preferences/data unchanged
- no partial Restore journal phase is entered solely because malformed source was
  discovered too late
- real parser/normalization/restore entrypoint is exercised; helper-only predicate
  tests are insufficient

## Test gap

Current restore coverage does not establish a key-specific semantic contract for
ytdlp_source across every supported payload form.

Current updater tests establish that a blank present source is not valid consumer
state, but that is downstream evidence and does not prove malformed restore is
rejected before mutation.

No focused current-basis test proves Merge + Reset + legacy/current typed restore
all share the same nonblank source admission contract.

The root is confirmed and remediation-ready on the exact current basis.
