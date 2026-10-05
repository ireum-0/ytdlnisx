# Automatic YouTube PO Token Provider — Review Amendment

record_kind: FEATURE_IMPLEMENTATION_PLAN_AMENDMENT
record_status: REVIEWED
production_change_authorized_by_this_document: NO
correctness_baseline_reopened: NO

applies_to_plan_path: feature-plans/PO_TOKEN_AUTOMATIC_PROVIDER_IMPLEMENTATION_PLAN.md
applies_to_plan_blob: 9c31f078db9e7185bb4288052d256ee7b102e5e5
applies_to_plan_commit: e025af45f697554b63939d578e5885e1bf1b8da4
precedence: THIS_AMENDMENT_OVERRIDES_CONFLICTING_BASE_PLAN_TEXT

## Purpose

This amendment closes the remaining implementation-review gaps found after the base plan was revised.

The base plan remains applicable for all non-conflicting architecture, scope, lifecycle, transport,
UI, verification, history-preservation, and acceptance requirements.

The effective implementation contract is:

1. base plan at the exact blob above;
2. plus this amendment;
3. where this amendment wins on any conflict.

This amendment does not authorize production implementation by itself.

## 1. Exact reviewed upstream basis

Current reviewed yt-dlp basis:
- master commit: 51bab8a0116f4d8004c315706d809782607d5847
- PO Token README blob: f39e2907108db01eb405389b3ec6984c37fa4175
- provider.py blob: 2511edf015582f8ee7675c1744c62e3fcdcb7380
- utils.py blob: 7f9ca078d6c74278b7b31615805d9b510c430510
- WebPO cache spec blob: 426b815c7ee09ad3b70b87e268b6df8732b96738
- PO Token director blob: 13616a940fd24718676df3e5e058b1c03103093a
- YouTube video extractor blob: 3f9ebe0e71d256c9d9dd00cc15268e01ef3b4600
- options.py blob: b46dba20f24c69ef479d388c9a71ca04fd12ef3

Reviewed facts that now form part of the architecture contract:
- provider preference controls ordering only;
- provider rejection/error can fall through to another discovered provider;
- --no-plugin-dirs clears default and previously supplied plugin directories;
- normal current GVS provider requests use bypass_cache=false;
- a new yt-dlp process starts with a new in-memory PO Token cache/director;
- current yt-dlp applies a GVS PO Token to the selected media URL as the pot query parameter.

If the exact yt-dlp runtime identity changes, these assumptions must be re-checked.

## 2. Mandatory first-party provider discovery isolation

The AUTO path must not rely on provider preference as an isolation boundary.

For every eligible AUTO native invocation:

1. sanitize external/user arguments first;
2. before the option terminator, internally inject --no-plugin-dirs;
3. immediately after it, inject exactly one --plugin-dirs pointing at the verified app-owned provider root.

The pair is app-owned and must not be user-overridable.

Required behavior:
- default plugin directories are not searched for the AUTO invocation;
- user-installed or unrelated third-party provider plugins are not discoverable;
- a YTDLnisX provider rejection/error cannot fall through to another external provider;
- the existing external argument policy continues to reject user-supplied plugin-dir process-spawning options;
- MANUAL/non-AUTO execution keeps its existing behavior.

Required tests:
- exact internal injection order;
- injection occurs after sanitizer and before option terminator;
- a planted competing provider in a default or unrelated plugin directory is not loaded or used;
- no AUTO marker means no new internal plugin-directory controls.

## 3. Fresh-retry semantics

A loopback server generation is a transport-authentication/lifecycle generation.
It is not a PO Token freshness generation.

The base-plan requirement for a fresh retry is revised to:

fresh AUTO retry =
- a new yt-dlp invocation;
- therefore a new yt-dlp in-memory PO Token director/cache;
- a new provider request;
- a new Android generator call;
- a newly minted PO Token.

A fresh retry does NOT require:
- loopback server restart;
- coordinator generation rotation;
- generation credential rotation;
- waiting for the coordinator idle grace period.

Concurrent AUTO invocations may share one READY coordinator generation.

Do not force-recycle a READY generation while another lease is active merely to simulate token freshness.

Required tests:
- an eligible first 403 causes exactly one new yt-dlp invocation;
- provider/generator invocation count increases and a distinct test token is minted;
- the retry still succeeds while another lease keeps the same coordinator generation READY;
- after the server genuinely stops, a later acquire still creates a new server generation/credential as a lifecycle property;
- no third AUTO retry.

## 4. Runtime compatibility proof and launch must share authority

There must be no time-of-check/time-of-use gap between runtime compatibility proof and the AUTO target launch.

After ordinary runtime initialization has completed through existing mutation authority, an eligible AUTO request must hold one continuous YtdlpRuntimeAuthority.withConsumer ownership interval covering:

1. resolving the exact executable yt-dlp artifact identity;
2. compatibility-cache lookup;
3. any required version/import/provider-discovery compatibility probe;
4. full quiescence of any compatibility-probe child process;
5. provider coordinator lease acquisition;
6. trusted internal plugin isolation argument injection;
7. native target ProcessBuilder/start;
8. target native finalization and provider-lease lifetime required by the base plan.

The consumer authority must not be released between compatibility proof and the target launch.

A concurrent self-update/mutation must wait until that consumer interval releases.

Required tests:
- exact executable identity used by compatibility equals the artifact launched;
- a racing mutation cannot replace the runtime between proof and launch;
- mutation proceeds only after the consumer interval releases;
- runtime mutation still invalidates the prior compatibility verdict.

## 5. PoTokenWebView JavaScript boundary hardening

Raw network-derived content binding must not be used as control/correlation data.

Required implementation:
- create an opaque per-call request ID independent of the binding;
- key pending continuations by that request ID, not by binding text;
- convert the binding to the required byte array on the Kotlin side;
- pass only the numeric byte-array expression and a safely encoded opaque request ID into evaluateJavascript;
- never interpolate the raw binding into JavaScript source;
- JavaScript callbacks return the opaque request ID rather than echoing the binding;
- cancellation removes the exact request-ID entry;
- JavaScript error diagnostics are sanitized/truncated and do not emit raw binding or token material;
- retain the base plan's raw-log removal and no-global-WebView-cache-clear requirements.

Required tests:
- quote, backslash, newline, U+2028, U+2029, and other nontrivial binding content cannot alter JavaScript source/control flow;
- duplicate identical bindings correlate independently by opaque request ID;
- cancellation removes only the intended pending request;
- no raw binding appears in diagnostics;
- existing NewPipe behavior remains functional.

## 6. AUTO selected but unsupported request

AUTO-mode ineligibility is no longer left to implementation interpretation.

For an app-managed YouTube media request with auto_mweb_gvs selected:

If cookies/authentication, proxy, source-address override, TLS-verification override,
or another unsupported MVP network/access condition is active:
- fail before native yt-dlp start;
- return a typed AUTO-provider-ineligible result;
- do not silently reinterpret the request as MANUAL, AUTHENTICATED, PUBLIC_DEFAULT, or stored-token mode;
- do not inject stored manual/generated PO Tokens.

Non-YouTube requests and arbitrary/raw DownloadType.command requests are not forced into AUTO.
They keep their preexisting non-AUTO semantics.

The user may explicitly switch to Manual or remove the unsupported access setting.

Required tests:
- each unsupported app-managed AUTO condition fails before native start;
- no provider marker is attached to an ineligible request;
- no stored PO Token is injected;
- raw/command request behavior remains unchanged;
- MANUAL behavior remains unchanged.

## 7. Strong token-application proof

Plugin discovery and provider receipt are not enough to prove successful GVS token application.

Deterministic wiring proof must show:
- a fixed non-sensitive test token is returned by the fake/local provider;
- the selected GVS format URL receives that same value as its pot query parameter.

Exact-final live acceptance must show, in memory:
- the provider-returned token equals the pot value on the selected GVS format URL;
- evidence persists only a boolean/match result, never the compared raw values;
- the token-bearing format yields a usable GVS URL and positive media-transfer progress or controlled completion.

The live acceptance record may contain a field such as:
- gvsPotMatchesProviderToken=true

It must not contain the token itself.

## 8. Trace/logging restriction

AUTO production and acceptance execution must not enable youtube:pot_trace=true.

Current yt-dlp trace output is not an approved secret-safe evidence surface.

This is in addition to the base-plan prohibition on raw token, content-binding, Visitor Data,
Data Sync ID, provider credential, cookie, or proxy-credential logging.

## 9. Stage amendments

Stage A additionally requires:
- AUTO plugin discovery isolation;
- a planted competing provider is not discoverable.

Stage B additionally requires:
- raw binding is not interpolated into JavaScript;
- raw binding is not the continuation/callback key.

Stage C additionally requires:
- compatibility proof and target launch under one continuous consumer-authority interval;
- self-update race exclusion proof;
- internal --no-plugin-dirs then verified --plugin-dirs injection after sanitizer.

Stage D additionally requires:
- explicit typed fail-before-native semantics for selected-but-ineligible app-managed AUTO requests;
- raw/command requests are not forced into AUTO.

Stage E additionally requires:
- planted-provider isolation test;
- deterministic provider-token-to-GVS-pot equality proof;
- runtime mutation versus AUTO consumer race test.

Stage F freshness is defined by:
- new yt-dlp invocation;
- new provider request;
- new generator call/token;
not by server-generation rotation.

Stage G additionally requires:
- first-party-only provider selection proof;
- sanitized provider-token-to-selected-GVS-pot equality proof;
- pot_trace disabled;
- positive media-transfer evidence or controlled completion;
- no raw sensitive values in evidence.

## 10. Decision-gate amendments

Gate 1 fails if a competing/default provider remains discoverable in AUTO mode.

Gate 3 fails if runtime identity/compatibility proof can be separated from target launch by a runtime mutation.

Gate 4 fails if selected-but-ineligible app-managed AUTO requests silently change access profile.

Gate 6 fails if retry freshness depends on coordinator restart/credential rotation rather than a new yt-dlp/provider/generator/token path.

Gate 7 cannot declare ACCEPTED without:
- exact-final successful token application to the selected GVS URL;
- positive transfer/completion evidence;
- sanitized equality proof only;
- pot_trace disabled.

External outage handling remains IMPLEMENTED_NOT_RUNTIME_ACCEPTED as defined in the base plan.

## 11. Implementation-run constraints added by this amendment

The later implementation prompt must include all of the following:
- first-party AUTO plugin discovery isolation;
- preference is not an isolation boundary;
- runtime compatibility proof and launch share one consumer authority;
- raw binding never enters JavaScript source as an interpolated string or callback identity;
- fresh retry does not require server generation rotation;
- unsupported app-managed AUTO requests fail typed before native start;
- deterministic and live token-to-GVS-pot application proof;
- pot_trace disabled for AUTO production/acceptance execution.

All base-plan history/workspace preservation, normal-forward commit, exact-SHA closure,
independent-review, and hard-stop requirements remain in force.
