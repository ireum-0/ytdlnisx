# Automatic YouTube PO Token Provider — Implementation Plan

record_kind: FEATURE_IMPLEMENTATION_PLAN
record_status: PROPOSED
production_change_authorized_by_this_document: NO
correctness_baseline_reopened: NO

planning_branch: plan/remediation
planning_basis_sha: 2145847a1054da28398b730b9be0ca728668f967

known_good_source_sha: adf2f347ce9e20ec9f9376cf94053694353c9961
published_baseline_marker: db29f63ce169176b4c8ade4cec01f66cc0307ec8
baseline_tree: 5a548066be27295a76ee46547818935bc4add95a
baseline_tag: known-good-2026-10-05-adf2f347

## 1. Purpose

Add an automatic YouTube PO Token generation path to YTDLnisX without weakening the existing Known-Good download/runtime invariants.

The new path should let bundled yt-dlp request a PO Token when it actually needs one instead of relying primarily on long-lived PO Token strings persisted in SharedPreferences.

The first production target is deliberately narrow:

> Opt-in, unauthenticated, default-network, MWEB GVS PO Token generation on demand through yt-dlp's public PO Token Provider framework, backed by the app's existing WebView/BotGuard generator.

The first target is NOT:
- a replacement for every existing manual PO Token path;
- authenticated/Data Sync ID generation;
- player/subtitle context support;
- proxy-aware generation;
- a generic third-party provider installer;
- automatic runtime download of arbitrary current upstream code.

The initial implementation must preserve all current manual behavior when the automatic mode is disabled.

## 2. Why provider-first

The present fork largely treats PO Tokens as configuration values:
- manual client/token configuration through YoutubePlayerClientItem;
- manually generated token sets stored as YoutubeGeneratePoTokenItem;
- direct youtube:po_token extractor-arg construction in YTDLPUtil;
- direct subtitle timedtext pot fallback;
- Visitor Data / Data Sync ID assembly inside application code.

The current yt-dlp API provides an explicit public PO Token Provider framework. A provider receives the actual request context and can obtain the WebPO content binding from yt-dlp itself.

That makes provider-first preferable to a new periodic "generate tokens and persist them" worker because:
- the token can be minted against the exact current binding;
- yt-dlp owns its WebPO memory-cache semantics;
- application code does not need to duplicate all client/context binding rules;
- a new yt-dlp process naturally starts with a fresh provider cache;
- manual tokens can remain available as an independent compatibility mode.

## 3. Current source facts

The following blobs describe the baseline implementation that this plan is designed against.

- YTDLPUtil.kt
  - blob: 7229e7116ccf45495a7e14db21cf8c180d35a9d7
  - owns current player-client / PO-token / Visitor Data / Data Sync ID extractor-arg construction.
- YoutubeDLCompat.kt
  - blob: 035915754ef66bd8dae42c1333a6eb652c826e2c
  - owns bundled Python/yt-dlp native process creation, environment, runtime authority and exact native process finalization.
- NewPipePoTokenGenerator.kt
  - blob: d5eb9b0cb45ad39492b0dab2b5c523818fbf4858
  - currently creates Visitor Data, a streaming token, and a per-video player token using PoTokenWebView.
- PoTokenWebView.kt
  - blob: 21176977e82dd28ab1d7b8f3752c5965ba2a493a
  - contains the reusable BotGuard/JNN + obtainPoToken(identifier) engine.
- AutoGenerateWebPoTokenWorker.kt
  - blob: affc95649a2bfe0f9b5b0ceb0e650fac66a23f26
  - currently returns success and is not the active automatic generation mechanism.
- GenerateYoutubePoTokensFragment.kt
  - blob: ba248f862201193214dce3fb5a12d14faed1513e
  - owns current manual/generated-token UI.
- YoutubeGeneratePoTokenItem.kt
  - blob: 361b1251fdcb47502f472f806089fbd6e0c7bb1f
- YoutubePlayerClientItem.kt
  - blob: 3406fa6317541c54556a3fdeaa0cb82bc91d1bf0
- DownloadWorker.kt
  - blob: c0a772d926ec8b33fb9588f4cc7b305304965f82
  - owns current YouTube 403 fallback and execution/retry authority.

Current PoTokenWebView properties relevant to this plan:
- network loads are blocked inside WebView;
- BotGuard bootstrap uses explicit OkHttp calls;
- the WebView can mint a token from an arbitrary String identifier;
- the generated token is base64url-compatible;
- the WebView generator has an expiration instant for its integrity state;
- current OkHttp BotGuard traffic does not follow the app's yt-dlp proxy preference.

That last fact is why the MVP must reject proxy/source-address-dependent automatic generation instead of pretending it has equivalent network identity.

## 4. Current upstream references

These are research references, not runtime dependencies.

### yt-dlp public provider API

Current reviewed PO Token provider README blob:
- yt-dlp/yt-dlp
- yt_dlp/extractor/youtube/pot/README.md
- blob: f39e2907108db01eb405389b3ec6984c37fa4175

Current public WebPO binding helper establishes:
- WebPO clients include WEB, MWEB, TVHTML5 and related web clients;
- unauthenticated GVS binds to Visitor Data by default;
- authenticated GVS binds to Data Sync ID;
- PLAYER and SUBS bind to video ID;
- GVS may also bind to video ID under yt-dlp's internal request policy.

Therefore the initial provider must validate the binding type that yt-dlp gives it rather than deriving one from app assumptions.

### BgUtils reference implementation

Reviewed upstream:
- Brainicism/bgutil-ytdlp-pot-provider
- current release: 2.0.1
- release asset bgutil-ytdlp-pot-provider.zip
- asset SHA-256: 6fc9d757578949ba3cad2f561f57dfbc16142dbf8d485fe1bf9f0733f23bf9e3

Its HTTP provider demonstrates:
- use of yt-dlp PoTokenProvider;
- use of get_webpo_content_binding();
- localhost provider communication;
- separate provider plugin and generator server;
- server/script version compatibility checks.

YTDLnis upstream additionally integrates BgUtils through a Deno foreground service and runtime download/install flow.

This plan does NOT copy that runtime download model.

## 5. Architecture decision

The default architecture is:

Android app
  -> app-owned PoTokenProviderCoordinator
      -> strict loopback-only local HTTP endpoint
          -> PoTokenGenerationEngine
              -> existing PoTokenWebView/BotGuard engine

bundled yt-dlp
  -> bundled first-party YTDLnisX provider plugin
      -> reads private endpoint configuration supplied by the app
      -> POSTs a validated content-binding request to loopback endpoint
      -> receives PO Token
      -> returns PoTokenResponse to yt-dlp

The provider plugin is small, first-party, version-pinned and packaged with the app.

BgUtils remains:
- a behavioral/reference implementation;
- a possible future alternative provider backend;
- NOT a runtime dependency of the MVP.

## 6. Trust boundaries

### 6.1 No arbitrary runtime provider download

Forbidden in the MVP:
- downloading GitHub master.zip at runtime;
- pip install -U of provider code;
- executing unpinned external provider source;
- selecting "latest" provider dynamically.

The first-party provider plugin must ship as an app asset and be materialized from an integrity manifest.

### 6.2 Loopback only

The provider transport must bind only to:
- 127.0.0.1;
- an ephemeral port selected by the OS.

Do not bind:
- 0.0.0.0;
- a Wi-Fi/LAN address;
- all interfaces;
- a fixed externally discoverable port.

### 6.3 Per-generation authentication

Each provider-server generation must own:
- a random generation ID;
- a 256-bit random secret;
- an ephemeral loopback port.

The secret must not be:
- placed in yt-dlp command-line arguments;
- logged;
- included in user diagnostics;
- persisted in SharedPreferences;
- included in review/runtime evidence.

Recommended secret carrier:
- app-private temporary secret file;
- path only is supplied to the native process environment;
- file deleted on normal teardown;
- stale secret files deleted at next coordinator startup;
- file excluded from backup.

Environment names:
- YTDLNISX_POT_HOST
- YTDLNISX_POT_PORT
- YTDLNISX_POT_SECRET_FILE
- YTDLNISX_POT_GENERATION

YTDLNISX_POT_HOST must be the literal loopback host selected by the app and must never come from user input.

The plugin reads the secret from the file and authenticates the framed socket request before the Android generator performs any expensive work.

### 6.4 Sensitive fields

Never log raw:
- PO Token;
- provider secret;
- Visitor Data;
- Data Sync ID;
- content_binding;
- cookies;
- proxy credentials.

Safe diagnostics may contain:
- request context;
- normalized client name;
- binding type;
- content-binding length;
- token length;
- provider generation ID;
- provider/plugin version;
- latency;
- typed error code.

Current PoTokenWebView DEBUG logging is not safe for provider use because generatePoToken() logs the raw identifier. Before production provider wiring, that logging must be changed to length/type-only diagnostics.

SensitiveTextRedactor must be extended only if new field names are introduced that current redaction does not catch.

### 6.5 No broad WebView cache destruction

Current PoTokenWebView.close() calls WebView.clearCache(true), which clears process-wide WebView cache state rather than only this generator's state.

The provider implementation must not use broad WebView cache deletion as part of ordinary provider lease teardown.

Required change before provider production use:
- close only the generator-owned WebView lifecycle;
- do not call global clearCache(true) during normal provider shutdown;
- if a full global cache reset is ever required for a proven recovery case, design and review it separately.

### 6.6 Exact runtime/plugin compatibility identity

Provider compatibility is keyed to:
- exact bundled yt-dlp runtime identity/version;
- provider plugin version/blob/hash;
- provider protocol version.

A prior compatibility result must not be reused after yt-dlp runtime mutation/update or plugin replacement.

If yt-dlp self-update changes the runtime:
- discard any cached provider-compatibility verdict;
- re-run cheap provider import/discovery compatibility before the next AUTO request;
- fail closed to provider unavailable rather than silently executing an incompatible plugin.


## 7. MVP support contract

Initial automatic provider support is deliberately restricted to all of the following:

- mode: automatic provider;
- media access profile: AUTO_POT_MWEB_GVS;
- yt-dlp provider request client: MWEB;
- context: GVS;
- authenticated: false;
- WebPO binding type: VISITOR_DATA;
- app proxy: absent;
- request source-address override: absent;
- TLS verification override: default;
- generated by the app-owned PoTokenWebView BotGuard engine after an explicit MWEB/GVS compatibility proof.

Reject, do not guess, for:
- authenticated GVS / Data Sync ID;
- PLAYER;
- SUBS;
- WEB unless separately enabled after evidence;
- TV clients;
- video-ID GVS binding;
- visitor-ID binding;
- configured proxy;
- configured source-address override;
- unsupported network feature;
- blank binding;
- oversized binding;
- malformed framed-protocol payload.

A reject is not a production crash. It is a typed provider-unavailable/rejected result that yt-dlp can handle.

The AUTO profile must be represented inside the existing YoutubeMediaAccessPolicy/YoutubeMediaAttemptSet state machine. It must not be implemented as an unrelated second retry/fallback state machine.


## 8. Preference model

Add one preference with a stable enum-like String value:

youtube_po_token_mode

Initial accepted values:
- manual
- auto_mweb_gvs

Migration/default:
- existing installations with no value -> manual;
- no existing manual PO Token data is deleted or modified;
- switching back to manual restores current behavior exactly.

In auto_mweb_gvs mode:
- stored manual/generated token data remains persisted but is not automatically injected into the same request;
- legacy youtube_generated_po_tokens is ignored for media PO Token injection;
- existing manual UI remains available for explicit fallback/testing;
- the automatic provider is not silently combined with stale persisted automatic tokens.

Do not add auto+manual mixed precedence until a later explicit design wave.

## 9. New components

Recommended package:

app/src/main/java/com/ireum/ytdl/util/pot/

### 9.1 YoutubePoTokenMode.kt

Responsibilities:
- parse preference;
- known values only;
- unknown future values fail back to MANUAL;
- no side effects.

Suggested shape:

enum class YoutubePoTokenMode {
    MANUAL,
    AUTO_MWEB_GVS;

    companion object {
        fun fromPreference(raw: String?): YoutubePoTokenMode
    }
}

### 9.2 Youtube automatic access policy integration

Do not introduce a competing top-level retry policy beside YoutubeMediaAccessPolicy.

Extend the existing production media-access state machine so automatic PO Token execution is one explicit access profile.

Recommended additions:

YoutubeMediaAccessProfile:
- AUTO_POT_MWEB_GVS

YoutubeMediaAccessFamily:
- AUTO_POT

YoutubeMediaAttemptSnapshot must retain enough profile-level state to distinguish:
- first AUTO_POT attempt;
- fresh AUTO_POT retry;
- PUBLIC_DEFAULT fallback;
- AUTHENTICATED path where separately allowed by existing behavior.

A family-only attempted set is insufficient if it collapses first and fresh automatic attempts into one state.

A small YoutubePoTokenPolicy helper may still exist, but only as a pure eligibility/configuration helper consumed by YoutubeMediaAccessPolicy. It must not own independent retry sequencing.

Inputs:
- configured youtube_po_token_mode;
- URL is YouTube;
- media/data-fetch purpose;
- cookies enabled;
- proxy configured;
- raw access configuration;
- subtitles requested;
- current YoutubeMediaAccessProfile;
- whether this is the fresh-provider retry generation.

Outputs:
- AUTO profile eligible;
- requested player client;
- manual PO token injection allowed;
- provider rejection/ineligibility reason;
- whether one fresh-provider retry remains.

Required state-machine routing for MVP:

MANUAL/default existing path:
- preserve current behavior exactly.

AUTO eligible path:
- AUTO_POT_MWEB_GVS(first generation)
  -> if eligible 403 and no semantic publication/quiescence proven:
     AUTO_POT_MWEB_GVS(fresh generation)
  -> if same eligible 403 and current clean-public policy permits:
     PUBLIC_DEFAULT
  -> otherwise fail according to existing classification.

Do not classify AUTO_POT_MWEB_GVS as AUTHENTICATED merely because a PO Token exists.

Do not let the existing "exclude web clients for media" helper silently remove MWEB from the AUTO profile. AUTO profile selection must explicitly override that one client-selection rule while preserving all unrelated quality/output policy.

MVP eligibility:
- app-managed YouTube request only;
- DownloadType.command/raw arbitrary commands are not rewritten to force provider usage;
- proxy configured -> automatic provider unsupported;
- cookies/authenticated path -> automatic provider unsupported;
- subtitles do not automatically enable SUBS token generation;
- public fallback must not automatically re-enable provider.


### 9.3 PoTokenProviderManifest.kt

Represents the bundled plugin manifest:
- schema_version;
- provider_version;
- plugin_relative_path;
- plugin_sha256;
- minimum_yt_dlp_version if needed;
- protocol_version.

No network.

### 9.4 PoTokenProviderRuntime.kt

Responsibilities:
- read manifest from assets;
- materialize exact provider files under noBackupFilesDir;
- verify SHA-256 before publication;
- atomic temp -> final rename;
- retain known-good prior materialization until replacement is verified;
- never follow a symlink outside app-owned runtime root;
- return canonical plugin root.

Suggested root:

<noBackupFilesDir>/ytdlnisx-pot-provider/v1/

Suggested plugin materialization:

plugins/
  yt_dlp_plugins/
    extractor/
      getpot_ytdlnisx.py

The provider runtime is ordinary app-owned read-only execution support, not a self-updating package manager.

### 9.5 PoTokenGenerationEngine.kt

Own the reusable BotGuard/WebView generation state.

Responsibilities:
- lazily create PoTokenWebView on the main thread;
- keep one active integrity-state generator;
- recreate when expired;
- accept the exact content binding supplied by the provider request;
- serialize generation initially with Mutex;
- mint a fresh PO Token for each actual generator call;
- one recreate-and-retry on generator failure;
- never persist generated PO Tokens.

Do not embed Visitor Data derivation here.

Important:
PoTokenGenerationEngine takes:
- binding: String
and returns:
- token: String

It does NOT decide that binding should be Visitor Data/video ID/etc.
yt-dlp provider request owns that decision.

Required PoTokenWebView hardening before provider production use:
- remove DEBUG logging of the raw identifier/content binding;
- log only non-sensitive metadata such as binding length;
- preserve raw token non-logging;
- remove ordinary close()-path use of global WebView.clearCache(true);
- keep WebView create/destroy on the main thread;
- add cancellation cleanup so a cancelled generation does not leave a stale continuation entry;
- define behavior for duplicate concurrent identical bindings; the initial implementation may serialize all generation rather than support parallel minting.

The existing NewPipe path may retain its own behavior only if these shared PoTokenWebView safety changes do not regress it. Add regression coverage for both consumers.


### 9.6 PoTokenProviderProtocol.kt

Use a compact framed loopback protocol instead of implementing an HTTP server/parser.

Transport v1:
- TCP socket to 127.0.0.1:<ephemeral-port>;
- exactly one request and one response per connection;
- fixed 4-byte unsigned big-endian payload length;
- UTF-8 JSON payload;
- connection closed after response.

Request v1 JSON:
- schema_version: 1
- request_id: UUID
- provider_generation: String
- secret: String
- client: String
- context: String
- binding_type: String
- content_binding: String
- bypass_cache: Boolean

Response v1 success:
- schema_version: 1
- request_id: UUID
- po_token: String

Response v1 error:
- schema_version: 1
- request_id: UUID
- error_code: String
- retryable: Boolean

Initial error codes:
- UNAUTHORIZED
- BAD_REQUEST
- UNSUPPORTED_CLIENT
- UNSUPPORTED_CONTEXT
- UNSUPPORTED_BINDING
- UNSUPPORTED_AUTH
- UNSUPPORTED_NETWORK_MODE
- GENERATOR_UNAVAILABLE
- GENERATION_FAILED
- SERVER_SHUTTING_DOWN

Limits:
- frame payload <= 8 KiB;
- content binding <= 4096 UTF-8 bytes unless implementation evidence justifies a tighter bound;
- one request per connection;
- fixed connect/read/write timeouts;
- reject zero/negative/oversized declared lengths before allocation;
- reject trailing bytes after the declared frame where observable;
- constant-time secret comparison where practical;
- authenticate generation + secret before generator work;
- no HTTP semantics, no transfer encoding, no path/method parser, no file serving.

The goal is to reduce implementation volume and parser attack surface while retaining the same loopback/private-secret boundary.


### 9.7 PoTokenLoopbackServer.kt

Use a small app-owned ServerSocket implementation.

Bind:
ServerSocket(0, backlog, InetAddress.getByName("127.0.0.1"))

Responsibilities:
- accept one framed v1 request per connection;
- read exactly the 4-byte length prefix;
- reject invalid/oversized lengths before allocating the payload;
- read exactly the declared payload;
- parse JSON;
- verify generation ID and secret before expensive generation;
- enforce client/context/binding policy;
- return one framed typed response;
- close the connection;
- use a bounded worker executor;
- initially serialize actual PO Token minting;
- no sensitive logging.

Deterministic JVM tests:
- loopback-only bind;
- valid roundtrip;
- wrong secret;
- wrong generation ID;
- zero/negative/oversized length;
- truncated frame;
- malformed JSON;
- unsupported schema/client/context/binding;
- timeout/early disconnect;
- response request_id correlation;
- server stop while a client is idle;
- bounded concurrency.

Do not add HTTP method/path/header parsing unless a later requirement proves framed TCP insufficient.


### 9.8 PoTokenProviderCoordinator.kt

Lifecycle authority.

State:
- STOPPED
- STARTING
- READY
- STOPPING
- FAILED

Own:
- runtime/plugin materialization;
- loopback server;
- generation engine;
- generation secret;
- active lease count;
- delayed idle shutdown.

API:

suspend fun acquire(context, policy): Lease

Lease exposes only:
- pluginDir: File
- host: String\n- port: Int
- generationId: String
- secretFile: File

Lease.close():
- decrement reference count;
- after 5-10 s idle grace, stop server and close WebView if no active lease.

Concurrency:
- first caller starts once;
- concurrent callers share one READY generation;
- stop cannot race an acquire into use-after-close;
- failed STARTING publishes failure to all waiters;
- next later acquire may retry with a fresh generation.

Process death:
- no durable "provider is running" marker;
- stale secret temp files cleaned on next acquire;
- plugin materialization remains immutable and reusable.

### 9.9 PoTokenProviderDiagnostics.kt

Expose sanitized state only:
- mode;
- runtime materialized yes/no;
- provider version;
- coordinator state;
- active lease count;
- last typed error code;
- last successful generation timestamp;
- last latency;
- no binding/token/secret.

## 10. Bundled Python provider plugin

Asset source location:

app/src/main/assets/ytdlnisx-pot-provider/
  manifest.json
  plugins/
    yt_dlp_plugins/
      extractor/
        getpot_ytdlnisx.py

Class:
YtdlnisxLocalPTP

Requirements:
- class name ends with PTP;
- PROVIDER_NAME = "ytdlnisx:local";
- fixed PROVIDER_VERSION matching manifest;
- public yt-dlp imports wrapped safely;
- is_available() performs no network call and no expensive operation;
- compatibility identity includes exact yt-dlp runtime identity + plugin version/hash + protocol version.

MVP provider declaration:
- _SUPPORTED_CLIENTS = ("MWEB",)
- _SUPPORTED_CONTEXTS = (PoTokenContext.GVS,)
- _SUPPORTED_EXTERNAL_REQUEST_FEATURES = () so yt-dlp itself rejects unsupported proxy/source-address/TLS override requests before provider work.

_real_request_pot(request):
1. reject if request.is_authenticated;
2. call get_webpo_content_binding(request);
3. require binding type == ContentBindingType.VISITOR_DATA;
4. require nonblank binding;
5. read host, port, secret file and generation from environment;
6. require host == 127.0.0.1;
7. open one bounded-timeout TCP connection directly to the loopback server;
8. send one framed v1 JSON request;
9. receive one framed v1 response;
10. map typed local errors to:
   - PoTokenProviderRejectedRequest for unsupported policy;
   - PoTokenProviderError for generator/server failure;
11. return PoTokenResponse(po_token=...);
12. do not log token, secret or content binding.

The local socket connection must not inherit yt-dlp proxy settings.

Caching:
- do not add an Android token cache in MVP;
- do not add a plugin-side persistent cache;
- return expires_at=None initially so yt-dlp's existing WebPO cache specification applies;
- current yt-dlp WebPO cache specification defaults to a six-hour TTL and includes content binding/binding type/network identity in its key;
- honor request.bypass_cache by calling the Android generator whenever yt-dlp actually invokes the provider.

Compatibility:
- manifest records protocol version and provider plugin hash;
- runtime records/observes the exact yt-dlp version or another deterministic runtime identity available from the bundled executable;
- after yt-dlp self-update, invalidate provider discovery compatibility before the next AUTO request.

Preference:
- register a provider preference high enough to select YTDLnisX local provider when auto mode explicitly installs its plugin path;
- do not globally override unrelated third-party providers when auto mode is off.


## 11. YoutubeDLCompat integration

This is a high-risk integration point because YoutubeDLCompat owns:
- runtime authority;
- native process environment;
- generation barriers;
- process registry;
- W^X-safe launch path.

Do not move PO Token lifecycle outside those invariants.

Add an internal per-request trusted provider marker similar in spirit to allowedConfigFilesByRequest.

Suggested API:

internal fun requireAutomaticPoTokenProvider(
    request: YoutubeDLRequest,
    policy: AutomaticPoTokenRequestPolicy,
)

The request marker is:
- stored in a WeakHashMap keyed by exact request instance;
- app-owned only;
- consumed once at execute boundary;
- not created by raw user arguments.

At executeWithQuiescence:
1. take provider marker;
2. if absent, preserve exact current path;
3. if present, verify provider compatibility identity for the current yt-dlp runtime;
4. acquire PoTokenProviderCoordinator lease before ProcessBuilder.start();
5. append internal --plugin-dirs pointing only to the verified app-owned plugin root;
6. add exact provider environment variables to ProcessBuilder;
7. start the existing supervisor/native path unchanged;
8. retain lease until root process + exact descendant finalization is complete;
9. close lease in finally only after finalization handling.

Do not expose a generic arbitrary environment setter.

Add exact environment keys only:
- YTDLNISX_POT_HOST
- YTDLNISX_POT_PORT
- YTDLNISX_POT_SECRET_FILE
- YTDLNISX_POT_GENERATION

processStarterOverrideForTesting currently receives the environment map.
Tests involving it must assert secret-file/path presence structurally without printing secret contents.

Provider acquisition/compatibility failure before native start:
- fail the AUTO request as typed provider setup failure;
- no native marker debt should be created.

Failure after native start:
- preserve all existing native finalization semantics;
- provider lease is not released until native finalization finishes or unresolved native debt is durably retained.

Runtime mutation interaction:
- yt-dlp self-update remains governed by existing mutation authority;
- a successful runtime mutation invalidates any cached provider compatibility verdict;
- provider runtime files are not mutated by yt-dlp update;
- the next AUTO consumer must re-check provider import/discovery compatibility before launch.


## 12. YTDLPUtil and YoutubeMediaAccessPolicy integration

Do not fold provider mechanics into setYoutubeExtractorArgs.

Do not implement AUTO selection as a separate top-level policy that bypasses YoutubeMediaAccessPolicy.

Required production changes:
- add AUTO_POT_MWEB_GVS to YoutubeMediaAccessProfile;
- add AUTO_POT to YoutubeMediaAccessFamily or otherwise preserve an equally explicit distinct family/state;
- update YoutubeMediaAttemptSet/snapshot so first AUTO attempt and one fresh AUTO retry are distinguishable;
- update requestPolicy/routing so AUTO profile can request provider wiring without being treated as authenticated;
- update quality/fallback routing so AUTO -> fresh AUTO -> PUBLIC_DEFAULT is explicit and bounded;
- preserve existing PUBLIC/AUTHENTICATED/USER_PINNED behavior when mode is MANUAL.

Manual mode:
- exact current behavior;
- current YoutubePlayerClientItem and YoutubeGeneratePoTokenItem injection preserved.

AUTO_POT_MWEB_GVS:
- do not inject stored media po_token values;
- do not inject generated persisted PO tokens;
- explicitly select mweb for the automatic provider media client;
- do not let canUseAsMediaPlayerClient(excludeWebClientForMediaFormats=true) replace mweb with DEFAULT_NON_WEB_MEDIA_PLAYER_CLIENTS for this profile;
- attach provider requirement marker to the request;
- preserve non-PO unrelated extractor args;
- reject/ineligible auto mode for proxy/cookies/raw unsupported requests according to policy.

Do not force automatic provider into:
- arbitrary DownloadType.command;
- raw commands that supply their own access-sensitive player/client/token behavior;
- PUBLIC_DEFAULT fallback requests;
- subtitle-only SUBS token generation.

Diagnostics:
extend buildRequestDiagnostics with non-sensitive fields:
- youtubePoTokenMode
- youtubeMediaProfile
- youtubeAutoProviderRequested
- youtubeAutoProviderEligible
- youtubeAutoProviderReason
- youtubeProviderClient
- youtubeAutoProviderGenerationKind=initial|fresh-retry
- never provider secret/token/binding.

Required regression:
- MANUAL mode preserves exact existing player-client selection and quality routing;
- AUTO profile does not accidentally count as AUTHENTICATED;
- existing quality replacement/max-transfer bounds remain intact.


## 13. DownloadWorker retry ladder

Current 403 handling can broadly fall back from authenticated/tokenized YouTube access to a clean public request.

With automatic provider mode, use a strict bounded ladder.

Attempt 1:
- AUTO_MWEB_GVS request;
- ordinary yt-dlp provider cache behavior.

If and only if:
- attempt reached native yt-dlp;
- failure text matches current narrow YouTube 403 classifier;
- no output was semantically published;
- native generation is proven quiescent;
then:

Attempt 2:
- create a NEW yt-dlp invocation;
- acquire a fresh provider execution generation;
- no app token cache;
- one automatic provider retry only.

If Attempt 2 gives the same eligible 403:
- existing clean public-client fallback may run if current canBuildCleanPublicRequest policy allows it.

Never:
- loop indefinitely;
- retry unchanged within the same process just to seek green;
- mix automatic token with persisted stale automatic token;
- fall back after partial publication.

Record diagnostic reason:
- AUTO_POT_INITIAL
- AUTO_POT_FRESH_RETRY
- PUBLIC_FALLBACK_AFTER_AUTO_POT

## 14. UI plan

Advanced -> YouTube PO Token screen.

Add:
Automatic PO Token mode

Options:
- Manual (current behavior)
- Automatic — MWEB/GVS (experimental)

Display:
- provider runtime version;
- status: Disabled / Ready / Active / Unavailable;
- last provider error code;
- short explanation that tokens are generated on demand and not displayed/stored.

Keep current manual token editor/generator.

When automatic mode is selected:
- manual entries remain visible/editable;
- explain that they are not injected into automatic requests in MVP;
- no destructive migration.

Do not display:
- generated token;
- content binding;
- provider secret.

## 15. AutoGenerateWebPoTokenWorker disposition

Do not use AutoGenerateWebPoTokenWorker for token minting.

For MVP:
- leave it behaviorally inert or remove only in a separate cleanup commit after proving no call sites;
- do not create a periodic token refresh schedule.

Potential future reuse:
- provider health diagnostics;
- optional plugin/runtime integrity verification.

Do not make a background job persist newly generated PO Tokens.

## 16. Proxy/auth future work boundary

MVP rejects proxy/source-address/authenticated automatic generation.

Reason:
PoTokenWebView BotGuard traffic currently uses a default OkHttp client and does not inherit yt-dlp's proxy/source address.

Future proxy support requires a separate design that proves:
- supported proxy URL schemes;
- credential handling;
- same network identity for BotGuard generation and target request where required;
- no proxy credentials in protocol logs/evidence;
- cancellation/timeouts;
- test coverage.

Future authenticated support requires a separate design that proves:
- Data Sync ID binding;
- cookie/session identity;
- account switching;
- stale Data Sync ID retirement;
- interaction with existing CookieProjectionCoordinator;
- no cross-account cache reuse.

Do not smuggle either into MVP.

## 17. PLAYER/SUBS future work boundary

Do not use the existing current UI behavior where playerRequestPoToken is also stored as subs token as evidence that PLAYER and SUBS are interchangeable.

Before adding either:
- verify current yt-dlp request binding;
- add context-specific provider acceptance;
- run live evidence for that context;
- add independent regression tests.

MVP success does not imply PLAYER or SUBS correctness.

## 18. Accelerated single-run implementation

The preferred execution model is one continuous implementation run, not a sequence of user-gated waves.

The implementation agent should proceed through the internal stages below without stopping for renewed approval after each successful stage. Each stage remains independently reviewable and should normally end in a small normal-forward commit or equivalent durable checkpoint, but those commits are internal checkpoints inside one continuous run.

The agent should continue automatically when a stage's acceptance checks pass.

The agent should stop only for a material blocker that cannot be safely resolved within the plan, including:
- current bundled yt-dlp cannot load the public provider API or the bundled first-party plugin without an incompatible runtime replacement;
- implementing the feature would require weakening YtdlpRuntimeAuthority, native process finalization, protected output ownership, or other existing Known-Good invariants;
- the provider can only be made to work by executing unpinned external code or by introducing runtime master/latest installation contrary to this plan;
- the loopback provider cannot be confined to app-owned loopback/private-secret boundaries;
- exact production semantics diverge materially from this plan in a way that makes the intended support contract ambiguous;
- an early controlled compatibility proof demonstrates that the existing WebView/BotGuard minter cannot produce a usable MWEB/GVS token for the binding supplied by current yt-dlp without a material architecture change.

Ordinary compile failures, test failures, protocol bugs, plugin import mistakes, lifecycle races, UI wiring errors, and other implementation defects are not stop reasons by themselves. Fix them within the same run, add regression coverage, and continue.

Do not squash, amend, rebase, or rewrite the internal stage history merely to make it look like one implementation action.

### Stage A — provider contract, framed transport and plugin discovery

Implement:
- bundled Python provider asset;
- manifest;
- PoTokenProviderRuntime;
- framed loopback protocol/server with fake generator;
- deterministic tests proving current bundled yt-dlp plugin discovery and request roundtrip.

Acceptance:
- current bundled yt-dlp loads YtdlnisxLocalPTP from verified app-owned plugin dir;
- fake endpoint returns a fixed non-secret test token;
- request context/client/binding shape reaches fake endpoint;
- loopback framing/authentication limits pass;
- no default production download behavior changes while the feature remains disabled.

On PASS:
continue immediately to Stage B.

### Stage B — WebView generation hardening + EARLY MWEB/GVS compatibility proof

Implement:
- PoTokenGenerationEngine;
- safe PoTokenWebView changes required by Section 9.5;
- PoTokenProviderCoordinator lifecycle sufficient for a controlled provider request.

Before changing production media-access policy or DownloadWorker, run an early controlled proof using:
- current bundled yt-dlp;
- MWEB;
- GVS;
- unauthenticated request;
- VISITOR_DATA binding obtained from current yt-dlp;
- the actual app PoTokenWebView minter.

Acceptance:
- raw content binding is not logged;
- normal generator close does not clear global WebView cache;
- exact MWEB/GVS request reaches the Android generator;
- generated token is accepted by yt-dlp sufficiently to progress to usable GVS format acquisition on a controlled public video;
- if external service availability is transiently unavailable, preserve evidence and retry only according to the verification plan; do not declare compatibility PASS without one actual successful token-use proof.

If this compatibility proof shows an ordinary implementation bug, fix it in the same run.

If it shows the existing minter architecture is materially incompatible with current MWEB/GVS semantics, hard-stop before implementing UI/retry policy and report the architecture blocker.

On PASS:
continue immediately to Stage C.

### Stage C — YoutubeDLCompat trusted provider execution wiring

Implement:
- trusted provider request marker;
- exact app-owned plugin-dir injection;
- provider environment wiring;
- lease/finalization integration;
- runtime/plugin compatibility invalidation;
- exact execution/finalization tests.

Acceptance:
- no marker -> current command/environment semantics unchanged;
- marker -> exact verified plugin dir + private environment present;
- secret absent from command line;
- provider lease survives until exact native finalization;
- start failure and unresolved finalization preserve existing authority semantics;
- yt-dlp runtime mutation invalidates provider compatibility verdict;
- user-provided --plugin-dirs remains restricted by existing argument policy.

On PASS:
continue immediately to Stage D.

### Stage D — integrated AUTO_POT_MWEB_GVS access state + UI

Implement:
- YoutubePoTokenMode;
- YoutubeMediaAccessPolicy/YoutubeMediaAttemptSet extension;
- optional pure YoutubePoTokenPolicy eligibility helper;
- YTDLPUtil integration;
- settings UI;
- diagnostics;
- policy/JVM tests.

Acceptance:
- default/manual mode preserves current behavior;
- AUTO profile is distinct from AUTHENTICATED and PUBLIC_DEFAULT;
- eligible auto request selects mweb and provider marker;
- existing web-client exclusion does not remove mweb from AUTO profile;
- proxy/cookies/raw unsupported requests do not pretend to be supported;
- no stored po_token is injected into the same automatic media request;
- existing manual data remains untouched;
- no automatic mode is silently enabled for existing users.

On PASS:
continue immediately to Stage E.

### Stage E — deterministic full production wiring proof

Run deterministic and instrumentation verification before adding retry semantics.

Acceptance:
- focused JVM tests PASS;
- production compile PASS;
- AndroidTest compile PASS;
- bundled yt-dlp discovers the real bundled provider plugin;
- app loopback provider receives the expected MWEB/GVS request shape;
- runtime/plugin compatibility invalidation behavior is covered;
- token, binding and secret values are absent from logs/evidence.

Stage E does not replace the successful live token-use proof already required in Stage B.

On PASS:
continue immediately to Stage F.

### Stage F — bounded fresh-provider 403 retry

Implement:
- DownloadWorker integration into the same YoutubeMediaAccessPolicy/YoutubeMediaAttemptSet state machine;
- typed retry diagnostics;
- policy tests;
- production-wiring runtime tests.

Acceptance:
- first AUTO attempt may transition to exactly one fresh AUTO generation after eligible 403;
- retry only after exact native quiescence and before semantic output publication;
- second eligible 403 may enter existing clean PUBLIC_DEFAULT fallback only when current policy permits;
- no retry on unrelated failure;
- no output ownership regression;
- no third retry or parallel competing retry state machine.

On PASS:
continue immediately to Stage G.

### Stage G — full regression and exact-final-SHA closure

Publish the completed implementation normally, then verify the exact published SHA.

Required code/runtime closure:
- all new focused JVM tests PASS;
- complete production compilation gates PASS;
- AndroidTest compilation PASS;
- existing blocker-relevant download/runtime regression classes PASS;
- complete PoTokenProviderProductionWiringTest PASS, nonzero, zero skip;
- artifact proof on exact published SHA;
- clean worktree/index;
- exact remote equality;
- independent completion review.

Required live acceptance:
- on the exact final published SHA, one controlled public YouTube case must prove:
  1. YtdlnisxLocalPTP was selected;
  2. MWEB/GVS/VISITOR_DATA request reached the app;
  3. PoTokenWebView generated the token;
  4. yt-dlp accepted the token for the request;
  5. a usable GVS format URL was obtained and download began or completed according to the test contract;
  6. no raw token, binding or secret appeared in evidence.

If deterministic closure passes but the exact-final-SHA live acceptance cannot be completed because of an external outage:
- implementation may be recorded as IMPLEMENTED_NOT_RUNTIME_ACCEPTED;
- preserve the external failure evidence;
- do NOT declare the automatic provider feature ACCEPTED;
- do NOT make it default;
- no false green by substituting plugin discovery for token-use proof.

Do not stop after an earlier stage merely because that stage is independently valid. The target of the accelerated run is the complete MVP through Stage G.

### Stage H — post-implementation default decision

This is NOT part of the accelerated implementation run's production behavior change.

After the feature has independent exact-SHA closure and repeated real-world evidence:
- keep AUTO_MWEB_GVS opt-in initially;
- collect real usage evidence;
- decide separately whether automatic mode should ever become default.

Any default change requires its own later review and must not be silently bundled into the accelerated implementation run.


## 19. Test matrix

### JVM / host tests

YoutubePoTokenModeTest
- null/blank/unknown -> MANUAL;
- known values parse.

YoutubeMediaAccessPolicy automatic-provider tests
- MANUAL preserves existing profile selection;
- eligible unauth YouTube media -> AUTO_POT_MWEB_GVS;
- AUTO profile is not AUTHENTICATED;
- cookies -> AUTO ineligible;
- proxy -> AUTO ineligible;
- command/raw access config -> no forced AUTO;
- PUBLIC_DEFAULT fallback -> provider disabled;
- subtitle request does not imply SUBS provider;
- first AUTO 403 -> one fresh AUTO transition;
- second eligible AUTO 403 -> PUBLIC_DEFAULT only if current clean-public policy allows;
- no third AUTO retry;
- existing quality-route and completed-transfer bounds remain intact.

PoTokenProviderRuntimeTest
- manifest parse;
- plugin hash match/mismatch;
- exact yt-dlp runtime compatibility key;
- runtime mutation invalidates compatibility;
- atomic materialization;
- no path traversal;
- interrupted publication keeps old known-good runtime.

PoTokenProviderProtocolTest
- framed roundtrip;
- zero/negative/oversized declared length;
- truncated frame;
- malformed JSON;
- wrong generation/secret;
- unknown schema;
- unsupported client/context/binding;
- response request_id correlation.

PoTokenLoopbackServerTest
- loopback-only bind;
- random port;
- valid request;
- timeout/early disconnect;
- stop while idle connection exists;
- bounded concurrency;
- one request/response per connection.

PoTokenGenerationEngineTest
- raw binding not logged;
- generator recreation after expiry;
- one recreate/retry after generation failure;
- cancellation removes pending continuation;
- normal close does not call global WebView.clearCache(true);
- shared NewPipe consumer remains functional under the hardening changes.

PoTokenProviderCoordinatorTest
- concurrent acquire shares generation;
- close/refcount;
- idle delayed shutdown;
- acquire racing shutdown;
- failed startup;
- stale secret cleanup;
- generation secret rotates.

YoutubeDLCompat provider wiring tests
- exact internal plugin-dir injection;
- exact environment names;
- secret value absent from command;
- provider marker consumed once;
- no marker no change;
- native failure retains lease through finalization;
- yt-dlp runtime mutation invalidates compatibility.

Download retry tests
- eligible first AUTO 403 -> one fresh AUTO retry;
- second eligible AUTO 403 -> public fallback if allowed;
- no third retry;
- unrelated failure no retry;
- partial publication no retry;
- retry remains integrated with YoutubeMediaAttemptSet.

### Android instrumentation

PoTokenProviderProductionWiringTest

Minimum deterministic cases:
1. app starts local framed provider;
2. bundled yt-dlp discovers YtdlnisxLocalPTP;
3. MWEB/GVS request produces local framed request;
4. binding type received is VISITOR_DATA;
5. fake generation returns test token and yt-dlp accepts provider response;
6. wrong secret/generation rejected;
7. provider shutdown after lease release;
8. concurrent two requests share coordinator without duplicated server generation;
9. WebView generator recreation after expiry/failure;
10. no token/secret/content binding in captured app diagnostics;
11. MANUAL mode leaves old media-client selection unchanged;
12. AUTO mode keeps MWEB instead of being replaced by DEFAULT_NON_WEB_MEDIA_PLAYER_CLIENTS.

### Early compatibility proof

Before production UI/retry integration is considered architecture-safe:
- actual current PoTokenWebView;
- actual current bundled yt-dlp;
- public YouTube video;
- MWEB/GVS;
- unauthenticated;
- VISITOR_DATA binding from yt-dlp;
- token generation succeeds;
- yt-dlp progresses to usable GVS media format with that token.

This is an architecture gate, not merely an optional smoke.

### Exact-final-SHA feature gate

Before declaring automatic provider feature ACCEPTED:
- focused JVM PASS;
- production compile PASS;
- AndroidTest compile PASS;
- current existing complete download/runtime regression classes PASS;
- new complete PoTokenProviderProductionWiringTest PASS, nonzero, zero skip;
- exact published SHA artifact proof;
- exact-final-SHA successful live token-use proof;
- clean worktree/index;
- independent completion review.

If all deterministic gates pass but exact-final live token use is unavailable due to external outage, use IMPLEMENTED_NOT_RUNTIME_ACCEPTED rather than ACCEPTED.


## 20. Failure semantics

### Provider runtime corrupt

- automatic request fails closed to typed provider unavailable;
- manual mode remains usable;
- do not execute corrupt plugin.

### Runtime/plugin compatibility stale or broken

- automatic request fails closed;
- invalidate cached compatibility;
- manual mode remains usable;
- do not auto-update yt-dlp or fetch arbitrary provider code to chase compatibility.

### Server fails to bind

- auto request fails;
- no silent external bind;
- later request may start fresh generation.

### Framed protocol violation

- close the connection;
- return/record typed BAD_REQUEST where safe;
- never continue with partially parsed attacker-controlled length/content;
- no generator work before authentication and validation.

### WebView/BotGuard initialization fails

- one internal generator recreation allowed;
- then typed GENERATOR_UNAVAILABLE/GENERATION_FAILED;
- no infinite retry.

### MWEB/GVS architecture incompatibility

- if early Stage B proof shows the current WebView/BotGuard minter cannot create a usable token for current yt-dlp MWEB/GVS semantics, stop before UI/retry rollout;
- do not relabel WEB-only success as MWEB success;
- evaluate a revised first-party minter or pinned BgUtils backend in a separate plan update.

### Exact-final live outage

- deterministic implementation may be complete;
- feature acceptance remains NOT_RUNTIME_ACCEPTED until one exact-final live token-use proof succeeds.

### Process cancellation

- existing YoutubeDLCompat native quiescence remains authoritative;
- provider lease closes only after safe finalization path;
- server cancellation does not authorize output publication.


## 21. Observability

Add sanitized diagnostics:

ProviderRuntime:
- provider version;
- plugin hash status;
- protocol version;
- yt-dlp runtime compatibility identity;
- compatibility status;
- materialized path basename only.

ProviderCoordinator:
- state;
- generation short ID;
- active leases;
- loopback port may be shown only in debug diagnostics if useful.

Provider request:
- client=MWEB;
- context=GVS;
- bindingType=VISITOR_DATA;
- bindingLength;
- bypassCache;
- duration;
- result code.

Retry:
- AUTO_POT_INITIAL;
- AUTO_POT_FRESH_RETRY;
- PUBLIC_FALLBACK_AFTER_AUTO_POT.

Never emit actual:
- binding;
- token;
- secret;
- secret-file content;
- Visitor Data;
- Data Sync ID.

Add counters if useful:
- auto requests;
- auto generation success;
- rejected unsupported request;
- generation failure;
- 403 fresh retry;
- public fallback after auto.

No analytics/network telemetry is required by this plan.


## 22. Backup/restore behavior

youtube_po_token_mode is a normal portable setting only if it is added to the destination restore schema deliberately.

Do not rely on generic backup type acceptance.

If added to backup/restore:
- exact type String;
- accepted values only;
- unknown/malformed -> absence/manual semantics;
- no token/provider secret/runtime path is portable;
- provider runtime materialization is reconstructed from bundled assets.

No generated token is backed up.

## 23. Upgrade / downgrade behavior

Upgrade to a build with provider:
- default remains MANUAL;
- nothing starts until user opts in.

Downgrade to old build:
- unknown youtube_po_token_mode key is ignored by old app;
- existing manual token data remains intact.

Re-upgrade:
- mode may still be present if old app preserved unknown preference;
- parser must validate it before use.

## 24. Explicit non-goals for initial release

Not in MVP:
- authenticated account PO Token;
- Data Sync ID PO Token generation;
- PLAYER context;
- SUBS context;
- proxy-aware generator;
- external BgUtils download;
- Node/Deno runtime installation;
- periodic token persistence worker;
- cross-process Android service;
- public/LAN provider endpoint;
- automatic provider updates independent of app release;
- deleting manual token UI;
- making automatic mode default.

## 25. Review lenses specific to this feature

Before acceptance, independent review should explicitly trace:

### Durability/recovery
- provider runtime materialization crash;
- secret-file crash residue;
- process death;
- runtime upgrade.

### Identity/provenance
- exact plugin hash/version;
- exact content-binding source;
- no stale request marker reuse;
- no cross-generation secret reuse.

### Concurrency/authority
- multiple downloads;
- metadata + download overlap;
- coordinator acquire/idle-stop race;
- YoutubeDLCompat finalization.

### Destructive ownership
- provider cleanup only removes app-owned provider temp/runtime files;
- no broad cache deletion.

### Platform contract
- WebView created/destroyed main thread;
- no exported Android component required;
- loopback-only socket;
- background worker process lifetime.

### Cross-feature propagation
- metadata fetch;
- filename preview;
- normal download;
- quality retry;
- subtitle flow;
- public fallback;
- cookie mode;
- command/raw request behavior;
- backup/restore.

## 26. Decision gates

The gates below are continuation checkpoints inside one accelerated implementation run.

A PASS automatically authorizes proceeding to the next gate within the already-approved plan. The agent must not stop merely to ask whether it should continue.

Gate 0 — architecture
PASS only if:
- provider-first remains preferred;
- no external runtime downloader is required;
- AUTO access is represented inside the existing YoutubeMediaAccessPolicy state machine.

Gate 1 — provider discovery + framed transport
PASS only if:
- current bundled yt-dlp loads the app-owned provider plugin from a pinned verified path;
- fake framed loopback request/response works with generation+secret authentication.

Gate 2 — EARLY real MWEB/GVS compatibility
PASS only if:
- actual PoTokenWebView mints from the current yt-dlp VISITOR_DATA binding;
- current bundled yt-dlp accepts the resulting token for MWEB/GVS;
- usable GVS media format acquisition is observed;
- raw binding/token/secret are absent from evidence;
- normal generator close does not clear global WebView cache.

Failure due to fundamental binding/minter incompatibility is a hard-stop before production policy/UI rollout.

Gate 3 — safe process integration
PASS only if:
- provider execution does not weaken YtdlpRuntimeAuthority/native finalization;
- runtime mutation invalidates provider compatibility identity.

Gate 4 — integrated opt-in production policy
PASS only if:
- MANUAL remains unchanged by default;
- AUTO_POT_MWEB_GVS is a distinct bounded access state;
- existing mweb exclusion is overridden only for AUTO profile;
- retry/public fallback transitions are represented by the same attempt state machine.

Gate 5 — deterministic production wiring
PASS only if complete provider/runtime instrumentation and compile gates pass.

Gate 6 — retry integration
PASS only if one fresh AUTO retry remains bounded, quiescence-safe and publication-safe.

Gate 7 — accelerated implementation closure
Deterministic implementation closure requires:
- complete focused/regression execution;
- exact published-SHA runtime/artifact proof;
- clean repository state;
- independent source/completion review.

Feature ACCEPTED additionally requires:
- successful exact-final-SHA live token-use proof through actual GVS format acquisition/download start.

If deterministic closure passes but external conditions prevent that live proof:
- status = IMPLEMENTED_NOT_RUNTIME_ACCEPTED;
- do not claim ACCEPTED;
- do not promote AUTO mode to default.

A failed gate should first be corrected inside the same implementation run when the failure is an ordinary implementation defect.

Hard-stop only when the failure demonstrates one of the material blockers listed in Section 18.

Failure of this new feature path does not reopen the historical Known-Good baseline.


## 27. Recommended implementation execution scope

Use one implementation agent for the complete MVP.

The execution instruction should cover Stages A through G in one run:

"Implement the complete Automatic YouTube PO Token Provider MVP described by this plan. Proceed continuously through pinned provider plugin discovery, framed loopback transport, PoTokenWebView hardening, an early real MWEB/GVS compatibility proof, YoutubeDLCompat trusted wiring, integrated YoutubeMediaAccessPolicy AUTO_POT_MWEB_GVS state/UI, deterministic production wiring, bounded fresh-provider 403 retry, and final exact-SHA regression/live closure. Use small normal-forward internal commits/checkpoints as useful, but do not stop for approval after successful intermediate stages. Fix ordinary implementation/test failures within the same run. Stop only for a material blocker explicitly defined by the plan."

Required implementation constraints for the single run:
- default mode remains MANUAL;
- AUTO_POT_MWEB_GVS only;
- AUTO state is integrated into YoutubeMediaAccessPolicy/YoutubeMediaAttemptSet;
- unauthenticated/default-network only;
- no proxy support;
- no PLAYER/SUBS support;
- no Node/Deno requirement;
- no BgUtils runtime integration;
- no runtime master/latest download;
- no pip install -U;
- no generated-token persistence;
- no HTTP server/parser unless framed TCP proves insufficient and the plan is revised;
- no raw binding/token/secret logging;
- no normal-path WebView.clearCache(true);
- no removal of manual PO Token UI/data;
- no default-mode promotion;
- no weakening of runtime/output/provenance invariants.

The implementation agent may create several commits during the run. The speed goal is fewer handoffs and fewer repeated bootstraps, not fewer evidence boundaries.

Before each production write/push:
- use current exact repository state;
- keep normal-forward history;
- preserve protected evidence;
- run the stage-appropriate focused verification.

At the end:
- publish the complete MVP;
- run final exact-SHA deterministic verification;
- run/attempt the exact-final live token-use acceptance proof;
- report IMPLEMENTED_NOT_RUNTIME_ACCEPTED if deterministic closure passes but external live acceptance cannot be completed;
- stop for independent review.

No intermediate user interaction is required unless a Section 18 hard-stop condition is reached.


## 28. Final recommendation

Proceed with one accelerated implementation run for the complete narrow MVP, with the reviewed corrections in this revision.

The MVP is:
- first-party bundled provider plugin;
- app-internal framed TCP loopback transport;
- existing PoTokenWebView as generation engine after sensitive-log/global-cache hardening;
- explicit AUTO_POT_MWEB_GVS media-access state integrated into YoutubeMediaAccessPolicy;
- unauthenticated/default-network only;
- opt-in;
- no token persistence;
- no runtime third-party download;
- one bounded fresh-provider retry after eligible 403;
- manual mode preserved unchanged.

The implementation order intentionally proves the riskiest semantic assumption early:
- plugin/transport discovery;
- actual MWEB/GVS token-use compatibility;
- only then deeper production state-machine/UI/retry integration.

Acceleration means:
- one implementation owner;
- continuous Stages A-G;
- automatic continuation after passing internal gates;
- small reviewable normal-forward commits;
- one final exact-SHA closure and independent review.

Acceleration does NOT mean:
- one giant unreviewable commit;
- skipping tests;
- treating plugin discovery as proof that generated tokens work;
- weakening exact-SHA evidence;
- hiding failed attempts;
- broadening MVP scope;
- bypassing hard-stop conditions.

Do not start with BgUtils runtime integration.

If the first-party WebView-backed minter fails the early real MWEB/GVS compatibility gate for a fundamental semantic reason, stop before broader production integration. The second architecture option is a separately pinned BgUtils backend using the same provider policy/lifecycle interfaces; that alternative requires a later plan revision and must not be silently substituted during this run.

