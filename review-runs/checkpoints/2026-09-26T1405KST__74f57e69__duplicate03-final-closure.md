# BUG-DUPLICATE-03 — final independent closure

Date: 2026-09-26 +09:00

Finding:
P2 BUG-DUPLICATE-03

Prior independently CLEAN basis:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

Initial SAF authority implementation:
7e6e1b7f0a4c0e68b0c2f0c70cfe16710ee4b9da

Provider-promotion fence candidate:
1de6e9146ca95b8dc8e3aa5d0039d585c9f3e4e9

Recovery-identity correction:
9e7838f976ab3c832d6ed2d73dc9cd7edbd6330c

Final test correction:
74f57e695db30b701ad429af311c39a763bfe086

Exact final implementation HEAD independently reviewed:
74f57e695db30b701ad429af311c39a763bfe086

Prior moved-review reconciliation checkpoint:
7eb55364ed2d34953a5e6cdce98f3075d4301cae

## Verdict

CLEAN / FIXED-CLOSED for P2 BUG-DUPLICATE-03.

Canonical count delta:
- P0: 0
- P1: 0
- P2: -1

Resulting canonical totals:
- P0 = 0
- P1 = 0
- P2 = 17

Overall remains NOT_CLEAN because unrelated canonical P2 findings remain open.

Advance CLEAN_REVIEW_BASIS to:

74f57e695db30b701ad429af311c39a763bfe086

## Exact-source closure

The cumulative implementation now preserves configured download-archive
authority end to end.

### Typed configured authority

ConfiguredDownloadArchiveStore represents the configured archive as one of:
- RawFile;
- SafTree;
- Unresolved.

Persisted ACTION_OPEN_DOCUMENT_TREE content:// identity remains provider
authority. It is not reconstructed into assumed /storage/... File authority.

Historical absolute raw-folder preference semantics are preserved by appending
download_archive.txt rather than reinterpreting the stored folder as the file.

### Admission reads

Queue and Observe consume the provider-aware configured archive read contract.

An inaccessible/revoked/malformed configured archive is Unavailable rather
than an authoritative empty archive.

An unresolved provider-promotion fence also makes ordinary admission reads
Unavailable even when the provider document remains readable.

This prevents readable partial provider state from weakening duplicate
protection.

### Native yt-dlp authority

Provider-backed configured archive authority never becomes a native
--download-archive pathname.

DownloadWorker retains its app-owned generation-private raw archive as the
trusted native archive path.

The existing post-authored-command trusted archive reassertion remains intact.

### Provider destructive replacement

For provider-backed promotion, exact generation evidence remains app-owned.

A durable promotion fence is installed before the first destructive provider
replacement.

Provider replacement/flush/close/verification failure retains:
- the generation-private archive;
- the durable provider fence;
- existing finalization recovery responsibility.

Ordinary duplicate admission remains fail closed while this responsibility is
unresolved.

Promotion verifies by re-reading the provider authority before retiring
private evidence or the fence.

### Recovery authority identity

The moved review tip independently exposed an additional same-root residual:
a generation-private archive could survive process death but re-resolve the
mutable current download_archive_path, redirecting A-derived debt into newly
selected authority B.

Final source closes that residual.

DownloadArchiveProviderFenceRecord durably binds:
- exact generation key;
- exact configured authority identity and authority kind;
- download id;
- execution id;
- promotion unresolved state.

For a new generation:
1. configured authority is resolved;
2. its contents are successfully read;
3. exact authority identity is durably bound;
4. only then is the generation-private archive written.

A process death after the private generation exists therefore always leaves
the authority identity needed for exact recovery.

A surviving generation restores its target from the durable record rather than
from the mutable preference.

A provider A -> B preference change therefore cannot redirect old A debt to B.

Corrupt/unusable durable identity fails closed and never falls back to the
current preference.

File-name/generation-key integrity is checked before a record is adopted.

An outstanding unresolved generation cannot be rebound to another authority.

The fence state is monotonic: a later bind cannot silently clear an active
promotion fence.

### Partial-write recovery

Focused source/test composition proves the important failure sequence:
- provider starts with A + B;
- generation holds A + B + C;
- provider replacement leaves readable partial A and fails;
- promotion remains unresolved;
- ordinary admission is Unavailable;
- restart/store recreation observes the durable fence;
- recovery repairs the original provider authority from private generation
  evidence;
- exact complete provider state is verified;
- only then are fence/private generation retired;
- ordinary admission becomes Available again.

### Compatibility disposition

A surviving generation-private file with no durable authority record is
refused rather than rebound to the current mutable preference.

This state can arise only from an intermediate remediation-line build that
predates the authority-binding record; the generation-private implementation
commit 650884d11 is a descendant of current main, not a state contained in the
main release line.

For current production semantics, every newly created generation binds its
authority before private debt exists.

The legacy identity-less state therefore remains fail-closed ancillary
finalization debt rather than being silently redirected to a potentially wrong
archive. This does not reopen BUG-DUPLICATE-03.

## Regression evidence

Implementation agent reported exact-final-SHA verification at:

74f57e695db30b701ad429af311c39a763bfe086

with:
- ConfiguredDownloadArchiveStoreProductionWiringTest: 17/17 PASS;
- DownloadQueueArchivePreflightProductionWiringTest: 3/3 PASS;
- ObserveSourceWorkerProductionWiringTest: 17/17 PASS;
- affected connected gate total: 37/37 PASS;
- DownloadArchiveAuthorityTest JVM: 4/4 PASS;
- affected JVM gate total: 60/60 PASS;
- :app:compileDebugKotlin -x lint: PASS;
- :app:compileDebugAndroidTestKotlin -x lint: PASS;
- git diff --check: PASS;
- tracked tree clean.

Two infrastructure-invalid attempts were preserved before the valid gates:
- locked kotlin-classes directory;
- dexBuilderDebug cache file-mode failure.

They are not counted as semantic failures.

Implementation-agent execution is accepted as evidence; this reviewer did not
independently execute instrumentation.

## Test-harness reconciliation

The final child commit changes test expectations only.

The A -> B partial-write test originally assumed DownloadArchiveAuthority.promote
would report false. Production deliberately propagates provider write failure,
while outer finalization catches it and preserves private/fence debt.

The corrected test establishes the real semantic precondition and asserts the
durable unresolved state rather than requiring one reporting shape.

This does not weaken production semantics.

## Inherited failures

Previously attributed unrelated producer/fingerprint/Observe failures remain
INHERITED_OUT_OF_SCOPE when their exact-base signatures apply.

They are not relabeled PASS and were not remediated by this root.

## Scope review

The full final range modifies only the download-archive authority/store/fence
composition and focused affected regression classes.

No Room schema migration was introduced.

No duplicate identity matching, generic SAF/cache authority, Terminal SAF,
queue handoff, or unrelated publication root was changed.

## Push verification

Live implementation branch independently verified at:

74f57e695db30b701ad429af311c39a763bfe086

The final branch is a normal fast-forward descendant of exact prior remote
7e6e1b7f and includes the preserved candidate plus two same-root children.

Review/remediation remained at the exact authorized review tip through the
final implementation push.

## Queue consequence

BUG-DUPLICATE-03 no longer blocks basis advancement.

Continue established P2 current-basis review order with:

P2 BUG-TERMINAL-03 — Terminal SAF destination authority.

INDEPENDENT EXECUTION: NOT EXECUTED
