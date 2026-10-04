# Debug/test package isolation checkpoint review — self-update residual

review_parent_sha: 2fe4d0e50c1dc7c3128c9b35ec5a9cf67802003b
implementation_base_sha: ee75b75786b8b6182dfc31946b6325b20294e73b
implementation_checkpoint_sha: 69b352a18ae580d4de5fd49f0a20d3e66d3d5966
scope: DEBUG_TEST_PACKAGE_ISOLATION
canonical_count_change: 0

Disposition:
CHECKPOINT_PUBLICATION_ACCEPTED
PACKAGE_ID_ISOLATION_SOURCE_ACCEPTED
SELF_UPDATE_RESIDUAL_CONFIRMED
DEVICE_EXECUTION_NOT_VERIFIED
NOT_CLOSED

History/scope:
- checkpoint/pre-baseline-review is exactly 69b352a18ae580d4de5fd49f0a20d3e66d3d5966;
- range ee75b757..69b352a is one forward commit, ahead 1 / behind 0;
- changed paths are exactly:
  app/build.gradle
  app/src/androidTest/java/com/ireum/ytdl/ExampleInstrumentedTest.kt
  app/src/debug/res/xml-v25/shortcuts.xml.

Accepted source semantics:
- debug applicationIdSuffix ".debug" makes debug package com.ireum.ytdl.debug while release/default remains com.ireum.ytdl;
- instrumentation target-context expectation is variant-aware through BuildConfig.APPLICATION_ID;
- debug source-set overrides the release shortcut resource so shortcut intents target com.ireum.ytdl.debug while class namespace remains com.ireum.ytdl;
- FileProvider authority remains ${applicationId}.fileprovider and therefore follows the debug applicationId;
- package-sensitive production paths reviewed use context.packageName or BuildConfig.APPLICATION_ID where variant identity is required.

Confirmed residual:
The app self-update path is still reachable in the debug variant.

Production chain:
MainActivity.onCreate()
-> checkUpdate()
-> UpdateUtil.tryGetNewVersion()
-> releases from https://api.github.com/repos/ireum-0/ytdlnisx/releases
-> UiUtil.showNewAppUpdateDialog()
-> select release asset by ABI
-> DownloadManager download
-> FileUtil.openFileIntent()
-> ACTION_VIEW of the downloaded APK.

UpdateSettingsFragment exposes the same path manually through its version preference.

No BuildConfig.DEBUG/applicationId guard exists on either route. The downloaded project release APK keeps release applicationId com.ireum.ytdl. Therefore a debug install with package com.ireum.ytdl.debug can still prompt installation/update of the personal release package. This violates the purpose of test-package isolation even though instrumentation itself is now side-by-side.

Correction contract:
- debug/non-release application variants must fail closed before a release APK self-update can be downloaded/opened for installation;
- release behavior for com.ireum.ytdl must remain unchanged;
- yt-dlp binary update behavior must remain unchanged;
- prefer one central package-identity safety gate covering all current app-self-update callers, with caller UI suppression only if needed for clear debug behavior;
- do not weaken package isolation, shortcut override, FileProvider identity, or instrumentation target identity;
- add focused regression coverage for release-id allowed vs debug-id denied if it can be expressed without device installation.

Pre-publication verification for the correction:
- git diff --check;
- debug production and complete AndroidTest compilation;
- debug + AndroidTest assembly;
- artifact identity proof remains com.ireum.ytdl.debug / distinct test package / targetPackage debug / debug provider authority;
- no device install or connected test required before checkpoint publication.

Under the current checkpoint-publication policy, a coherent correction checkpoint may be committed and normal-fast-forward pushed after those gates. Device side-by-side validation remains a later closure gate.

INDEPENDENT EXECUTION: NOT EXECUTED
