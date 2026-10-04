# Debug/test package isolation — implementation preparation authorized

review_parent_sha: c531a0bb4194e6294c84da170e9433b407a65fbf
implementation_sha: ee75b75786b8b6182dfc31946b6325b20294e73b

Purpose:
Prevent connected debug/instrumentation work from replacing or uninstalling the personal release package.

Current source facts:
- app/build.gradle namespace = com.ireum.ytdl;
- defaultConfig applicationId = com.ireum.ytdl;
- debug build type has no applicationIdSuffix;
- no app/src/debug/AndroidManifest.xml;
- no app/src/androidTest/AndroidManifest.xml;
- main FileProvider authority uses ${applicationId}.fileprovider.

Authorization:
- separate development/test-safety change, not BUG-UPDATER-04;
- no canonical finding-count change;
- inspect all package-name-sensitive production/test/build/script paths before edit;
- preferred minimal direction is a distinct debug applicationId, expected via debug applicationIdSuffix, only if full audit confirms compatibility;
- preserve release applicationId com.ireum.ytdl and release behavior;
- verify built debug APK package, test APK package, instrumentation target package, provider authorities and package-dependent runtime/test assumptions from artifacts;
- stop before any device install or connected test;
- no unrelated production behavior change.

INDEPENDENT EXECUTION: NOT EXECUTED
