# BUG-DUPLICATE-03 — independent review hold: provider partial-write authority gap

Date: 2026-09-26 +09:00

Finding:
P2 BUG-DUPLICATE-03

Prior independently CLEAN basis:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

Candidate reviewed:
7e6e1b7f0a4c0e68b0c2f0c70cfe16710ee4b9da

Parent:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

Prior current-basis checkpoint:
266602665c304613abb315f4afac4dd68a0fd6e4

## Verdict

SOURCE-MOSTLY-FIXED / SAME-ROOT PROVIDER-PARTIAL-WRITE GAP.

Do NOT close BUG-DUPLICATE-03 yet.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 18

Overall remains NOT_CLEAN.

CLEAN_REVIEW_BASIS remains:
17a492a7891c159fcdd66905ecc72d8e9f90aadf

## Confirmed source improvements

Independent review confirms the candidate correctly:

- introduces typed configured archive authority:
  - RawFile;
  - SafTree;
  - Unresolved;

- preserves the persisted content:// tree URI instead of reconstructing it into
  native filesystem authority;

- distinguishes archive Available from Unavailable;

- makes queue duplicate preflight fail closed with ARCHIVE_UNAVAILABLE when the
  configured archive cannot be read;

- makes Observe duplicate preflight fail closed and keeps confirmed retry
  decisions recoverable rather than consuming them on archive-unavailable
  withholding;

- keeps DownloadWorker's app-owned generation-private archive as the exact raw
  archive passed to native yt-dlp;

- prevents YTDLPUtil from synthesizing a --download-archive pathname for
  provider-backed configured authority when no trusted raw/private path exists;

- seeds DownloadArchiveAuthority generations through the actual configured
  authority;

- merges provider archive lines and verifies by re-reading before retiring the
  private generation;

- retains private generation / primary-success finalization debt when provider
  promotion reports failure;

- leaves the settings preference as the original provider URI and makes the
  summary display-only.

Reported exact-candidate verification:
- authorized connected gate 33/33, 0 failures;
- JVM gate 65/65, 0 failures;
- compileDebugKotlin PASS;
- compileDebugAndroidTestKotlin PASS;
- git diff --check PASS.

The three reported producer/fingerprint failures were attributed by isolated
execution to exact base 17a492a7 with unchanged signatures. They remain
INHERITED_OUT_OF_SCOPE and are not relabeled PASS.

Implementation-agent execution is evidence, not independent execution.

## Remaining same-root defect

Production SAF replacement currently writes directly into the authoritative
archive document:

    contentResolver.openOutputStream(target.uri, "wt")

followed by write/flush.

This is not a transactional provider replacement boundary.

A provider may:
1. truncate or partially replace the existing document;
2. accept only part of the new bytes;
3. then throw from write/flush/close.

DownloadArchiveAuthority.promote() correctly treats that call as failure and
retains the private generation. However the configured provider document may
now be a readable but incomplete archive.

Queue and Observe duplicate preflights currently trust any successful
ConfiguredDownloadArchiveStore.read(...) result as authoritative.

They do not consult pending archive-promotion/finalization debt before treating
the provider document as complete.

Therefore a partially mutated provider archive can fail open even though durable
recovery evidence still exists.

## Concrete failure sequence

1. Provider archive contains entries A and B.
2. Generation-private archive is seeded with A and B and later adds C.
3. Primary media success becomes durable.
4. promotion begins.
5. provider openOutputStream(..., "wt") truncates the authoritative document.
6. only A is written before the provider throws.
7. promote reports failure and retains the generation/finalization debt.
8. provider archive remains readable and contains only A.
9. before recovery repairs it, a new queue/Observe duplicate preflight reads the
   provider successfully.
10. the read is classified Available([A]).
11. source B is treated as absent and may be admitted again.

This violates the root invariant: recoverable provider-write failure must never
make unknown/incomplete archive membership look authoritative.

## Why the current failure regression is insufficient

providerPromotionFailureRetainsPrivateGenerationAndReportsFailure() injects:

    provider.writeFailure = ...

and FakeProvider.replaceText() throws before mutating contents.

It proves fail-before-write behavior only.

It does not model:
- partial provider mutation followed by failure;
- crash after destructive provider mutation but before verification;
- queue/Observe admission while verified promotion is still pending.

## Required narrow correction

Stay within BUG-DUPLICATE-03.

Establish a durable provider-promotion fence before any destructive provider
archive replacement can begin.

The fence must:

1. be durable before provider mutation;
2. identify that configured provider archive membership is temporarily not safe
   for duplicate admission;
3. survive process death;
4. remain while provider replacement/verification is unresolved;
5. be cleared only after the configured provider authority is re-read and the
   complete intended archive is verified;
6. remain on provider write/open/flush/verification failure;
7. be consumed/retried by the existing archive finalization recovery path;
8. make queue and Observe archive preflight fail closed while such provider
   promotion debt exists, even if the provider document itself is readable.

Prefer an app-private durable marker/fence integrated with the existing
generation-private archive / primary-success recovery model. No Room schema is
expected or authorized for this follow-up.

Do not rely on SAF rename/replace operations being atomic across arbitrary
providers unless the implementation can prove the old authoritative document
remains intact across write failure and process death.

The provider promotion/recovery path may use an internal read that bypasses the
admission fence for exact repair/verification. Ordinary queue/Observe reads must
not bypass it.

## Required regression

Add deterministic production-wiring coverage where the provider:

1. starts with A + B;
2. generation-private archive contains A + B + C;
3. replacement mutates provider contents to a readable partial state such as A;
4. replacement then throws;
5. promotion reports failure;
6. private generation and durable promotion fence remain;
7. an ordinary configured-archive admission read is Unavailable, not
   Available([A]);
8. queue preflight with source B is withheld as ARCHIVE_UNAVAILABLE;
9. Observe preflight with source B is withheld and does not consume a confirmed
   retry;
10. recovery retry merges from the preserved generation and restores A + B + C;
11. verification succeeds;
12. only then is the promotion fence/private generation retired and ordinary
    admission reads become Available again.

Also test crash-equivalent persistence of the fence by recreating the store /
recovery owner rather than relying only on process-local state.

Preserve all currently passing provider/raw/native-path tests.

## Secondary compatibility note

FileUtil.getDownloadArchivePath() is reportedly now unused production dead code
but still contains the old lossy reconstruction. It is not used as closure
evidence and is not the blocker above.

ConfiguredDownloadArchiveStore also treats a legacy absolute persisted value as
a direct archive File, while the historical preference contract named
download_archive_path as a folder and the old helper appended
download_archive.txt. Before final closure, add a focused compatibility test or
explicitly prove that no supported persisted/restore path can contain such a
legacy raw-folder value. Do not silently change legacy authority semantics
without evidence.

## Exact next action

Create one minimal same-root child commit of
7e6e1b7f0a4c0e68b0c2f0c70cfe16710ee4b9da.

Do not broaden into duplicate identity matching or generic SAF/cache/Terminal
authority.

Do not advance CLEAN_REVIEW_BASIS or decrement P2 until the provider
partial-write fence is closed and exact-final-SHA gates pass.

INDEPENDENT EXECUTION: NOT EXECUTED
