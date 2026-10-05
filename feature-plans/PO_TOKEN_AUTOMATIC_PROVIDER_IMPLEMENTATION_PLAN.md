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

The server must bind only to:
- 127.0.0.1
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
- an ephemeral port.

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
- YTDLNISX_POT_BASE_URL
- YTDLNISX_POT_SECRET_FILE
- YTDLNISX_POT_GENERATION

The plugin reads the secret from the file and sends it in:
- X-YTDLnisX-POT-Secret

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

SensitiveTextRedactor must be extended only if new field names are introduced that current redaction does not catch.

## 7. MVP support contract

Initial automatic provider support is deliberately restricted to all of the following:

- mode: automatic provider;
- yt-dlp provider request client: MWEB;
- context: GVS;
- authenticated: false;
- WebPO binding type: VISITOR_DATA;
- app proxy: absent;
- request source-address override: absent;
- TLS verification override: default;
- generated by current PoTokenWebView BotGuard engine.

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
- malformed protocol payload.

A reject is not a production crash. It is a typed provider-unavailable/rejected result that yt-dlp can handle.

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

### 9.2 YoutubePoTokenPolicy.kt

Pure policy.

Inputs:
- mode;
- URL is YouTube;
- media/data-fetch purpose;
- cookies enabled;
- proxy configured;
- raw access configuration;
- subtitles requested;
- current fallback profile.

Outputs:
- provider enabled;
- requested player client;
- manual PO token injection allowed;
- provider rejection reason if policy makes auto impossible;
- whether a 403 fresh-provider retry is allowed.

MVP rules:
- automatic provider only for app-managed YouTube requests;
- DownloadType.command/raw arbitrary commands are not rewritten to force provider usage;
- proxy configured -> automatic provider unsupported in MVP;
- cookies/authenticated path -> automatic provider unsupported in MVP;
- subtitles do not automatically enable SUBS token generation.

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
- accept exact content binding from provider request;
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

### 9.6 PoTokenProviderProtocol.kt

Shared Android protocol model.

Request v1:
- schema_version: 1
- request_id: UUID
- provider_generation: String
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
- request headers bounded;
- request body <= 8 KiB;
- content binding <= a documented small bound, e.g. 4096 bytes;
- one request per TCP connection;
- Connection: close;
- JSON only;
- fixed request timeout.

### 9.7 PoTokenLoopbackServer.kt

Use a small app-owned ServerSocket implementation, not a network-exposed framework.

Bind:
ServerSocket(0, backlog, InetAddress.getByName("127.0.0.1"))

Responsibilities:
- strict HTTP/1.1 subset;
- only POST /v1/pot;
- optional authenticated GET /v1/health for tests only;
- reject missing/incorrect secret before JSON generation work;
- enforce size/time limits before allocation;
- no path traversal;
- no file serving;
- bounded worker executor;
- initially one active token generation at a time;
- typed response;
- no sensitive logging.

The parser itself must have deterministic JVM tests for:
- oversized headers/body;
- malformed Content-Length;
- duplicate Content-Length;
- unsupported transfer encoding;
- wrong method/path;
- secret mismatch;
- early disconnect;
- valid request.

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
- baseUrl: String
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
- is_available() performs no network call and no expensive operation.

MVP provider declaration:
- _SUPPORTED_CLIENTS = ("MWEB",)
- _SUPPORTED_CONTEXTS = (PoTokenContext.GVS,)

_real_request_pot(request):
1. reject if request.is_authenticated;
2. reject any request/network feature outside MVP;
3. call get_webpo_content_binding(request);
4. require binding type == ContentBindingType.VISITOR_DATA;
5. require nonblank binding;
6. read base URL, secret file and generation from environment;
7. POST bounded JSON to /v1/pot with proxies disabled for the local request;
8. map typed local errors to:
   - PoTokenProviderRejectedRequest for unsupported policy;
   - PoTokenProviderError for generator/server failure;
9. return PoTokenResponse(po_token=...);
10. do not log token or content binding.

Caching:
- do not add an Android token cache in MVP;
- do not add a plugin-side persistent cache;
- return expires_at=None initially so yt-dlp's existing WebPO cache specification applies;
- honor request.bypass_cache by always calling the Android generator if yt-dlp invokes the provider.

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
3. if present, acquire PoTokenProviderCoordinator lease before ProcessBuilder.start();
4. append internal --plugin-dirs pointing only to the verified app-owned plugin root;
5. add provider environment variables to ProcessBuilder;
6. start the existing supervisor/native path unchanged;
7. retain lease until root process + exact descendant finalization is complete;
8. close lease in finally after finalization.

Do not expose a generic arbitrary environment setter.

Add exact environment keys only:
- YTDLNISX_POT_BASE_URL
- YTDLNISX_POT_SECRET_FILE
- YTDLNISX_POT_GENERATION

processStarterOverrideForTesting currently receives the environment map.
Tests involving it must assert secret presence structurally without printing the secret value.

Provider acquisition failure before native start:
- fail the request as typed provider setup failure;
- no native marker debt should be created.

Failure after native start:
- preserve all existing native finalization semantics;
- provider lease is not released until native finalization finishes or unresolved native debt is durably retained.

## 12. YTDLPUtil integration

Do not fold provider mechanics into setYoutubeExtractorArgs.

Add policy reading near request construction.

Manual mode:
- exact current behavior;
- current YoutubePlayerClientItem and YoutubeGeneratePoTokenItem injection preserved.

AUTO_MWEB_GVS:
- do not inject stored media po_token values;
- do not inject generated persisted PO tokens;
- select mweb as the automatic provider media client for the narrow eligible request;
- attach provider requirement marker to the request;
- preserve non-PO unrelated extractor args;
- reject/disable auto mode for requests with unsupported proxy/auth network state according to policy.

Do not force automatic provider into:
- arbitrary DownloadType.command;
- raw commands that supply their own access-sensitive player/client/token behavior;
- fallback profiles that intentionally remove authentication/access configuration.

Diagnostics:
extend buildRequestDiagnostics with non-sensitive fields:
- youtubePoTokenMode
- youtubeAutoProviderRequested
- youtubeAutoProviderEligible
- youtubeAutoProviderReason
- youtubeProviderClient
- never provider secret/token/binding.

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

## 18. Commit / implementation waves

Each wave should be independently reviewable and normal-forward only.

### Wave A — provider contract POC, no production selection

Expected changes:
- bundled Python provider asset;
- manifest;
- PoTokenProviderRuntime;
- fake local provider test harness;
- tests proving yt-dlp plugin discovery.

Production automatic mode remains unreachable/off.

Acceptance:
- current yt-dlp loads YtdlnisxLocalPTP from verified app-owned plugin dir;
- fake endpoint returns a fixed non-secret test token;
- request context/client/binding shape reaches fake endpoint;
- no current download request behavior changes with feature disabled.

### Wave B — loopback protocol + generation engine

Expected changes:
- PoTokenProviderProtocol;
- PoTokenLoopbackServer;
- PoTokenGenerationEngine;
- PoTokenProviderCoordinator;
- tests.

No YTDLPUtil production selection yet.

Acceptance:
- strict parser tests;
- secret/auth tests;
- concurrency/lifecycle tests;
- WebView main-thread lifecycle tests;
- generatePoToken(binding) fake/controlled instrumentation test;
- no token persistence.

### Wave C — YoutubeDLCompat trusted provider execution wiring

Expected changes:
- YoutubeDLCompat;
- provider request marker;
- environment/plugin-dir wiring;
- exact execution/finalization tests.

Acceptance:
- no marker -> byte/semantic-equivalent current command/environment;
- marker -> exact plugin dir + private environment present;
- secret not in command line;
- provider lease survives until exact native finalization;
- start failure and unresolved finalization preserve existing authority semantics;
- user-provided --plugin-dirs remains restricted by existing argument policy.

### Wave D — opt-in YTDLPUtil AUTO_MWEB_GVS

Expected changes:
- YoutubePoTokenMode;
- YoutubePoTokenPolicy;
- YTDLPUtil;
- settings UI;
- policy/JVM tests.

Acceptance:
- default/manual mode exact old behavior;
- auto eligible request selects mweb and provider marker;
- proxy/cookies/raw unsupported requests do not pretend to be supported;
- no stored po_token is injected into same auto media request;
- manual data remains untouched.

### Wave E — real exact-SHA live GVS proof

No broad feature expansion.

Controlled live smoke:
- current exact published SHA;
- one public YouTube video;
- auto mode;
- provider discovery proven;
- provider generation line proven without token value;
- actual GVS format URL obtained/download begins or completes according to test contract;
- generated binding/token not logged;
- compare with manual mode control.

A live YouTube smoke is evidence, not a deterministic CI substitute.

### Wave F — bounded 403 fresh-provider retry

Expected changes:
- DownloadWorker retry ladder;
- policy tests;
- production-wiring runtime tests.

Acceptance:
- one fresh automatic retry maximum;
- only after quiescence/no-publication;
- then existing clean public fallback;
- no retry on unrelated failure;
- no output ownership regression.

### Wave G — stabilization / default decision

After sufficient live evidence:
- decide whether AUTO_MWEB_GVS remains experimental opt-in;
- do not change default automatically in same implementation wave;
- any default change requires a separate review.

## 19. Test matrix

### JVM / host tests

YoutubePoTokenModeTest
- null/blank/unknown -> MANUAL;
- known values parse.

YoutubePoTokenPolicyTest
- manual unchanged;
- eligible unauth YouTube media -> auto;
- cookies -> unsupported MVP;
- proxy -> unsupported MVP;
- command/raw access config -> no forced auto;
- public fallback -> no auto;
- subtitle request does not imply SUBS provider.

PoTokenProviderRuntimeTest
- manifest parse;
- hash match;
- hash mismatch fail;
- atomic materialization;
- no path traversal;
- interrupted publication keeps old known-good runtime.

PoTokenProviderProtocolTest
- roundtrip;
- body/header limits;
- malformed JSON;
- secret reject;
- unknown schema;
- unsupported fields/contexts.

PoTokenLoopbackServerTest
- loopback bind;
- random port;
- wrong method/path;
- duplicate/invalid Content-Length;
- timeout;
- one connection response.

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
- secret absent from command;
- provider marker consumed once;
- no marker no change;
- native failure retains lease through finalization.

Download retry tests
- eligible 403 -> one fresh auto retry;
- second eligible 403 -> public fallback if allowed;
- no third retry;
- unrelated failure no retry;
- partial publication no retry.

### Android instrumentation

PoTokenProviderProductionWiringTest

Minimum cases:
1. app starts local provider generation;
2. bundled yt-dlp discovers YtdlnisxLocalPTP;
3. MWEB/GVS request produces local request;
4. binding type received is VISITOR_DATA;
5. fake generation returns test token and yt-dlp accepts response;
6. wrong secret rejected;
7. provider shutdown after lease release;
8. concurrent two requests share coordinator without duplicated server generation;
9. WebView generator recreation after expiry/failure;
10. no token/secret in captured app diagnostics.

### Exact-final-SHA feature gate

Before declaring automatic provider feature accepted:
- focused JVM PASS;
- production compile PASS;
- AndroidTest compile PASS;
- current existing complete download/runtime regression classes still PASS;
- new complete PoTokenProviderProductionWiringTest PASS, nonzero, zero skip;
- exact published SHA artifact proof;
- controlled live smoke evidence;
- clean worktree/index;
- independent completion review.

## 20. Failure semantics

### Provider runtime corrupt

- automatic request fails closed to typed provider unavailable;
- manual mode remains usable;
- do not execute corrupt plugin.

### Server fails to bind

- auto request fails;
- no silent external bind;
- later request may start fresh generation.

### WebView/BotGuard initialization fails

- one internal generator recreation allowed;
- then typed GENERATOR_UNAVAILABLE/GENERATION_FAILED;
- no infinite retry.

### yt-dlp provider API incompatibility

- plugin safe import/is_available failure;
- diagnostics identify provider incompatible;
- manual mode remains available;
- do not mutate yt-dlp runtime automatically to chase compatibility.

### Process cancellation

- existing YoutubeDLCompat native quiescence remains authoritative;
- provider lease closes only after safe finalization path;
- server cancellation does not authorize output publication.

## 21. Observability

Add sanitized diagnostics:

ProviderRuntime:
- version;
- manifest/hash status;
- materialized path basename only.

ProviderCoordinator:
- state;
- generation short ID;
- active leases;
- server loopback port may be shown only in debug diagnostics if useful.

Provider request:
- client=MWEB;
- context=GVS;
- bindingType=VISITOR_DATA;
- bindingLength;
- bypassCache;
- duration;
- result code.

Never emit actual binding/token/secret.

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

Gate 0 — architecture
PASS only if:
- provider-first remains preferred;
- no external runtime downloader required.

Gate 1 — plugin discovery POC
PASS only if bundled current yt-dlp loads the app-owned provider plugin from a pinned verified path.

Gate 2 — Android generator protocol
PASS only if content binding from yt-dlp can be minted by PoTokenWebView and returned without persistent token storage.

Gate 3 — safe process integration
PASS only if provider execution does not weaken YtdlpRuntimeAuthority/native finalization.

Gate 4 — opt-in real download
PASS only if exact-SHA live evidence shows the provider was actually invoked, not merely discovered.

Gate 5 — retry integration
PASS only if retry remains bounded and ownership-safe.

Gate 6 — feature acceptance
PASS only after independent source review + exact-SHA runtime closure.

Failure at any gate does not reopen the historical Known-Good baseline. It simply leaves this new feature unaccepted.

## 27. Recommended first implementation prompt scope

The first implementation agent should NOT attempt the whole plan.

First prompt should implement Wave A only:

"Create the bundled, pinned first-party yt-dlp PO Token provider plugin runtime and deterministic fake endpoint discovery proof. Do not select it for production downloads, do not modify player-client policy, do not implement WebView generation, and do not modify DownloadWorker."

Expected Wave A changed files:
- new provider asset + manifest;
- new PoTokenProviderManifest.kt;
- new PoTokenProviderRuntime.kt;
- focused tests;
- minimal test-only/production seam to pass an app-owned plugin directory to an explicit verification request if required.

Stop after:
- plugin discovered by exact bundled yt-dlp;
- provider request reaches fake endpoint;
- no default production behavior change.

This isolates the highest compatibility uncertainty — whether the bundled current yt-dlp and Android runtime can load and execute the provider plugin exactly as expected — before introducing WebView/server/download policy complexity.

## 28. Final recommendation

Proceed with the feature, but keep the first release narrow.

Preferred MVP:
- first-party bundled provider plugin;
- app-internal loopback server;
- existing PoTokenWebView as generation engine;
- AUTO_MWEB_GVS only;
- unauthenticated/default-network only;
- opt-in;
- no token persistence;
- no runtime third-party download;
- one bounded fresh-provider retry after eligible 403;
- manual mode preserved unchanged.

Do not start with BgUtils runtime integration.

If the first-party WebView-backed provider proves unreliable under real current YouTube behavior, the second architecture option is a separately pinned BgUtils backend using the same provider policy/lifecycle interfaces. That fallback should be evaluated as a new implementation wave, not silently substituted into the MVP.
