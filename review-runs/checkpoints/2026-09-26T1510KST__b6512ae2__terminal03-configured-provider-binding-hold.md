# BUG-TERMINAL-03 — independent review hold: configured-provider authority not durably frozen

Date: 2026-09-26 +09:00

Finding:
P2 BUG-TERMINAL-03

Prior independently CLEAN basis:
74f57e695db30b701ad429af311c39a763bfe086

Candidate reviewed:
b6512ae21a08f76d161f537074ede7a0ffcc045c

Parent:
74f57e695db30b701ad429af311c39a763bfe086

Prior current-basis checkpoint:
174a9f583088e09d8d47beb68e376bfb4da3762d

## Verdict

SOURCE-MOSTLY-FIXED / SAME-ROOT CONFIGURED-PROVIDER DURABLE-BINDING GAP.

Do NOT close BUG-TERMINAL-03 yet.

Canonical totals remain:
- P0 = 0
- P1 = 0
- P2 = 17

Overall remains NOT_CLEAN.

CLEAN_REVIEW_BASIS remains:
74f57e695db30b701ad429af311c39a763bfe086

## Confirmed source improvements

Independent source review confirms candidate b6512ae2 correctly closes the
original immediate authority-loss paths:

- Terminal destination authority is typed as NativeRaw / ProviderTree /
  Unusable;
- TerminalCommandEnvironment no longer contains a display-formatted path;
- configured content:// command_path is not reconstructed into native -P;
- provider-backed configured destination forces app-owned staging even when
  cache_downloads=false;
- native writability is checked only against typed raw authority;
- Terminal Folder picker no longer inserts FileUtil.formatPath(uri);
- Folder picker carries its exact provider tree URI in Terminal-owned command
  metadata;
- that metadata is stripped before shared yt-dlp parsing/native execution;
- manual authored absolute native -P still retains the validated direct-native
  contract and outranks provider defaults/selections;
- provider execution retains output-provenance marker/staging and exact
  content:// publication destination;
- the Folder-picked provider metadata is included in TerminalItem.command and
  therefore in the existing TERMINAL_DISPATCH exact command/fingerprint
  authority from closed BUG-TERMINAL-05.

Reported exact-final-SHA evidence:
- TerminalSafDestinationAuthorityProductionWiringTest: 8/8 PASS;
- TerminalDispatchHandoffProductionWiringTest: 8/8 PASS;
- TerminalExecutionProductionWiringTest: 2/2 PASS;
- connected affected total: 18/18 PASS;
- TerminalCommandPlanTest: 20/20 PASS;
- TerminalExecutionRecovery / OutputAuthority / HistoryReplacement JVM:
  24/24 PASS;
- compileDebugKotlin PASS;
- compileDebugAndroidTestKotlin PASS;
- git diff --check PASS.

Implementation-agent execution is evidence, not independent execution.

## Remaining same-root defect

The exact configured provider destination is still not part of the durable
Terminal intent.

TerminalViewModel.insert(...) currently commits:
- TerminalItem(command = ...); and
- TERMINAL_DISPATCH carrier bound to item.command.

It does not snapshot configured command_path into the Terminal row/carrier.

TerminalDownloadWorker, after exact dispatch validation and execution admission,
builds the plan using:

TerminalCommandPlanFactory.create(
    context = context,
    preferences = sharedPreferences,
    command = command,
    ...
)

TerminalCommandPlanFactory reads command_path from SharedPreferences at that
time.

Therefore a configured provider destination remains mutable across the durable
dispatch/restart boundary.

### Concrete sequence

1. command_path is provider authority A.
2. User starts Terminal T1 without an authored -P and without Folder-picker
   metadata.
3. Terminal row and TERMINAL_DISPATCH carrier commit T1.command only.
4. Before T1 worker reaches TerminalCommandPlanFactory, process dies or the
   worker is delayed.
5. User/settings/Restore changes command_path from provider A to provider B.
6. T1's old exact WorkRequest remains current because its command and command
   fingerprint are unchanged.
7. Worker admission succeeds for T1.
8. TerminalCommandPlanFactory reads current SharedPreferences and resolves B.
9. T1 stages output and publishes to B.

The old exact Terminal intent has therefore donated output authority to a newer
mutable configuration.

This violates the governing destructive-authority invariant and the active
remediation contract: a durable Terminal execution must not silently redirect
its provider publication target through later settings changes.

## Why current regression G does not close this

providerDestinationIsDurablyBoundAndStaleRequestsCannotAdoptANewerSelection()
uses:

TerminalProviderDestinationOption.render(providerTreeUri)

inside the command itself.

That correctly proves Folder-picker metadata is bound to the durable command.

It does not prove the configured command_path case, where the provider
destination is absent from TerminalItem.command and is re-read later from
SharedPreferences.

The A/B provider values in that test are therefore both command metadata, not
an A -> B configured-setting mutation across restart/admission.

## Required narrow correction

Stay within BUG-TERMINAL-03.

1. Freeze configured provider authority into the exact durable Terminal intent
   before async dispatch responsibility can be lost.

2. For a Terminal command that has:
   - no authored native -P/--paths home; and
   - no explicit Folder-picker provider metadata;
   and whose configured command_path is a ProviderTree,
   bind that exact provider tree URI to the Terminal semantic command/dispatch
   identity before the row/carrier commit.

3. Prefer reusing the existing Terminal-owned provider metadata representation
   so the configured provider snapshot inherits:
   - TerminalItem.command durability;
   - terminalCommandFingerprint;
   - TERMINAL_DISPATCH confirmedUrl/configFingerprint;
   - stale-request refusal;
   - process-death reconstruction.

4. Do not make a later preference read authoritative for an already durable
   Terminal provider destination.

5. A -> B configured setting changes after T1 commit must not redirect T1.

6. A new T2 created after the setting changed may bind B normally.

7. Manual authored native -P remains the most specific intent and must not be
   rewritten to configured provider metadata.

8. Explicit Folder-picker metadata remains more specific than configured
   provider default and keeps its exact selected provider URI.

9. Raw configured destinations may keep the current contract; do not broaden
   this follow-up into freezing every mutable Terminal preference unless a
   concrete same-root correctness dependency requires it.

10. No Room schema migration and no second scheduler.

A narrow pre-insert Terminal command materialization step is preferred if it can
deterministically:
- parse whether authored native output exists;
- preserve existing Folder metadata;
- snapshot only the provider configured default when needed; and
- produce the exact command that is atomically stored/staged by
  TerminalViewModel.insert().

Do not perform this binding only in the Fragment: Terminal creation/recovery
paths must share one production authority boundary.

## Required regression

Add deterministic production-wiring coverage for:

A. configured provider A -> B before worker/restart
- set configured command_path=A;
- create/insert T1 without authored output path;
- prove durable Terminal row and TERMINAL_DISPATCH carrier contain/bind A;
- simulate process death/reconstruction or delayed worker;
- change command_path to B;
- build/execute T1 plan from its durable command;
- prove final destination remains A;
- prove native request uses staging, never reconstructed A/B raw path.

B. new intent after preference change
- after configured setting becomes B, create T2;
- prove T2 binds B and does not inherit A.

C. authored native path
- configured provider A plus manual authored -P raw path;
- durable materialization must not inject configured A as the effective output
  authority;
- authored native path remains direct.

D. Folder picker precedence
- configured provider A plus Folder selection C;
- durable command binds C, not A;
- later configured change to B cannot redirect it.

E. exact dispatch identity
- changing only the bound provider metadata changes command fingerprint/current
  dispatch authority;
- stale request for A cannot execute as B.

Preserve all currently passing A-H Terminal SAF tests and Terminal dispatch /
execution gates.

## Reserved metadata note

TerminalProviderDestinationOption is currently parseable from user-authored
text as well as produced by the Folder picker. This does not reopen the current
root by itself because arbitrary/ungranted provider targets still stage and
must pass provider-aware publication, rather than becoming native raw
authority.

Treat the option namespace as reserved Terminal metadata in regression/docs.
Do not broaden this follow-up into a new metadata-authentication mechanism
unless a concrete unsafe publication path is demonstrated.

## Visibility note

TerminalDestinationAuthority being public because TerminalCommandEnvironment is
public is API-surface debt, not a current correctness blocker. Do not expand
scope solely to change visibility.

## Process-discipline incident

The implementation report disclosed an unnecessary:

git reflog expire --expire=now --all

during final cleanup.

This removed local reflog recovery breadcrumbs and was outside the requested
workflow. It did NOT:
- move a ref;
- rewrite commit history;
- remove protected stash objects;
- alter protected worktrees;
- change the pushed source SHA.

The agent independently reported full ref/stash integrity and fsck without
corruption.

Classify this as a non-blocking process-discipline incident for this source
closure review. Do not run reflog-expiry, aggressive GC, prune, or analogous
evidence-destroying cleanup in future waves.

## Exact next action

Create one minimal same-root child commit of:

b6512ae21a08f76d161f537074ede7a0ffcc045c

that durably snapshots configured ProviderTree authority into the exact Terminal
intent before row/carrier staging.

Do not amend b6512ae2.

Do not advance CLEAN_REVIEW_BASIS or decrement P2 until this configured-provider
authority-freeze residual is closed and exact-final-SHA gates pass.

INDEPENDENT EXECUTION: NOT EXECUTED
