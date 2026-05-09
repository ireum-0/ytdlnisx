# Codex Audit Verification Review

작성일: 2026-05-09

## 범위

이 문서는 `docs/codebase-audit.md`와 `docs/audit_verification_notes.md`를 기준으로 Gemini 검증 결과의 타당성을 재검토하고, Gemini가 직접 다루지 않은 항목을 소스 레벨에서 추가 확인한 결과다.

검증 방식은 정적 소스 검토다. Android 기기/에뮬레이터 실행, APK 설치, merged manifest 산출물 확인, Room migration 테스트, 네이티브 바이너리 런타임 테스트는 수행하지 않았다. 민감 파일(`local.properties`, `keystore.properties` 등)은 읽지 않았다.

## 요약 판단

Gemini가 검증한 항목은 대부분 소스 근거와 일치한다. 다만 일부 항목은 “취약점 자체”는 맞지만 영향 설명이나 심각도 표현을 좁혀 적는 편이 더 정확하다.

| 항목 | Codex 판단 | 비고 |
| --- | --- | --- |
| H1 cache recursive delete | 타당 | 사용자 설정 cache path와 recursive delete 조합이 현재 코드에 남아 있음 |
| H2 tree URI history deletion | 타당 | 저장된 `content://` tree URI와 delete-with-file 플로우가 결합되면 위험 |
| H3 yt-dlp option policy | 타당, 표현 보정 필요 | `--exec`류 옵션이 막히지 않는 것은 맞음. 단 “RCE equivalent”는 사용자 import/copy-paste 경로 의존성을 명시해야 함 |
| M1 DownloadWorker detached coroutines | 타당 | Gemini 판단보다 더 강함. `doWork()`가 작업 완료 전에 `Result.success()`를 반환할 수 있음 |
| M2 foreground setup swallowed | 타당 | 실패를 로그로만 삼키고 다운로드가 계속 진행됨 |
| M3 TerminalDownloadWorker detached move | 타당 | move 실패가 worker 결과에 반영되지 않음 |
| M4 metadata refresh overwrite | 타당 | 두 번째 metadata refresh에는 상태 재확인이 없음 |
| M5 alarm cancel mismatch | 타당 | schedule은 `getBroadcast`, cancel은 `getService` |
| M6 ShareActivity extras | 타당 | exported activity가 untrusted extras로 background queue 가능 |
| M7 ResumeActivity external requeue | 타당 | exported activity가 `itemID`만으로 requeue 수행 |
| M8 exported WebView extras | 타당 | 외부 intent로 URL 로드 및 cookie clearing 유발 가능 |
| M10 unbounded stream read | 타당 | exported SEND stream을 UI thread에서 무제한 문자 누적 |
| M14 incognito/log disabled failure log | 타당 | 실패 경로에서 로그 disabled/incognito라도 log insert 수행 |
| M15 sensitive logs/notifications | 타당, 범위 보정 필요 | URL/에러/notification 공개 노출은 맞음. 로그 확장 범위는 경로별로 나눠야 함 |

Gemini가 다루지 않은 원본 audit 항목 중 M9, M11, M12, M13, M16, M18, L1, L2, L3, L4는 소스 레벨에서 재현 가능했다. M17은 구조적으로는 맞지만 “빌드 실패” 영향은 과장됐을 가능성이 있다.

## Gemini 항목 재검토

### H1. Cache path recursive delete

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/work/CleanUpLeftoverDownloads.kt:40` 부근에서 active download가 없으면 `File(FileUtil.getCachePath(context)).deleteRecursively()`를 호출한다.
- `app/src/main/java/com/ireum/ytdl/ui/more/settings/FolderSettingsFragment.kt`에는 `cache_path`를 사용자가 바꿀 수 있는 설정 경로가 있다.
- `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt`의 `getCachePath(context)`는 preference 값을 반환 경로로 사용한다.

위험 조건은 “사용자가 cache path를 넓은 디렉터리로 설정했거나 잘못 복원/import된 경우”다. SAF tree URI가 항상 raw path로 안정적으로 변환되는지 여부는 기기/문서 제공자별 차이가 있을 수 있지만, 현재 구현은 삭제 대상 경로를 앱 전용 cache 하위로 강제하지 않는다.

### H2. Tree URI history deletion

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt:111` 부근의 `deleteDocumentUri(uri)`는 `DocumentFile.fromSingleUri` 이후 `DocumentFile.fromTreeUri(App.instance, uri)`까지 시도하고 `tree.delete()`를 호출한다.
- `HistoryRepository`의 delete-with-file 경로는 저장된 `downloadPath`를 `FileUtil.deleteFilesWithZeroByteSiblings(...)`로 전달한다.

저장된 history path가 `content://.../tree/...`이고 앱에 persistent URI grant가 있으면, 파일 하나 삭제 의도에서 tree root 삭제로 확대될 수 있다.

### H3. yt-dlp option policy

판단: 타당. 단 영향 설명은 “사용자 제공/import command가 yt-dlp option 실행면을 열어둔다”로 쓰는 것이 정확하다.

근거:

- `app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YoutubeDLCompat.kt`는 외부 `--ffmpeg-location`과 `--config*` 계열만 차단한다.
- `app/src/main/java/com/ireum/ytdl/util/extractors/ytdlp/YTDLPUtil.kt:876` 부근 `addConfig(commandString)`는 normalize 후 config 파일을 생성하고 `--config-locations`로 전달한다.
- `YTDLPUtil`의 다운로드 요청 생성 경로는 `extraCommands`를 그대로 option surface에 포함한다.

따라서 `--exec`, postprocessor 관련 옵션, network/output 관련 위험 옵션 등은 정책적으로 제한되지 않는다. 사용자가 외부 템플릿을 import하거나 command를 붙여넣는 흐름과 결합될 때 고위험이다.

### M1. DownloadWorker detached coroutine

판단: 타당. Gemini보다 더 강하게 봐야 한다.

근거:

- `app/src/main/java/com/ireum/ytdl/work/DownloadWorker.kt:193`, `207`, `300` 부근에서 `CoroutineScope(Dispatchers.IO).launch { ... }`를 직접 만든다.
- `DownloadWorker.kt`에는 `onStopped()` override가 확인되지 않았다.
- `DownloadWorker.kt:993` 부근에서 worker가 내부 launch 작업을 기다리지 않고 `Result.success()`로 끝날 수 있다.

WorkManager의 lifecycle과 다운로드 job lifecycle이 분리되어 cancel/retry/foreground 보장이 깨질 수 있다. 특히 “worker가 성공 처리된 뒤 실제 다운로드가 계속되는” 상태가 가능하다.

### M2. Foreground setup swallowed

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/util/extensions/WorkManagerExtensions.kt:28` 부근 `setForegroundSafely()`는 `IllegalStateException`, `InvalidForegroundServiceTypeException`을 catch 후 로그만 남기고 반환한다.
- `DownloadWorker.kt:85`는 이 결과를 확인하지 않고 계속 진행한다.

Android 12+ foreground service 제한과 결합되면 장시간 다운로드가 foreground 없이 진행될 수 있다.

### M3. TerminalDownloadWorker detached move

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/work/TerminalDownloadWorker.kt:141` 부근에서 cache move를 별도 `CoroutineScope(Dispatchers.IO).launch`로 시작한다.
- 같은 메서드는 그 job을 기다리지 않고 log update, notification cancel, row delete 후 `Result.success()`를 반환한다.

최종 파일 이동 실패가 WorkManager 결과와 사용자 상태에 반영되지 않는다.

### M4. Metadata refresh status overwrite

판단: 타당.

근거:

- `DownloadWorker.kt:207` 부근 첫 metadata refresh는 `dao.checkStatus(this.id) == Active`를 확인한다.
- `DownloadWorker.kt:308` 부근 두 번째 `resultRepo.updateDownloadItem(downloadItem)?.apply { dao.updateWithoutUpsert(this) }`는 상태 재확인이 없다.

사용자가 pause/cancel/delete한 뒤 늦게 도착한 metadata update가 row 상태를 덮어쓸 수 있다.

### M5. Alarm cancel PendingIntent type mismatch

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/util/AlarmScheduler.kt:30`, `56`, `75` 부근 schedule은 `PendingIntent.getBroadcast(...)`를 사용한다.
- `AlarmScheduler.kt:84` 부근 cancel은 같은 receiver에 대해 `PendingIntent.getService(...)`를 사용한다.

PendingIntent identity가 달라 cancel이 기존 alarm을 제거하지 못한다.

### M6. ShareActivity extras trust

판단: 타당.

근거:

- `app/src/main/AndroidManifest.xml:56` 부근 `ShareActivity`는 exported 상태로 SEND/VIEW intent를 받는다.
- `app/src/main/java/com/ireum/ytdl/ShareActivity.kt:185` 부근에서 `TYPE`, `BACKGROUND` extras를 신뢰한다.
- `ShareActivity.kt:201` 부근 `DownloadType.valueOf(...)`는 잘못된 값에서 crash가 가능하다.
- `BACKGROUND=true`이면 사용자가 확인하는 card UI를 거치지 않고 background queue로 이어진다.

외부 앱이 타입/백그라운드 동작을 제어할 수 있다.

### M7. ResumeActivity external requeue

판단: 타당.

근거:

- `app/src/main/AndroidManifest.xml:504` 부근 `ResumeActivity`가 exported다.
- `app/src/main/java/com/ireum/ytdl/ResumeActivity.kt:66` 부근에서 intent의 `itemID`를 읽고 notification cancel 후 `downloadViewModel.reQueueDownloadItems(listOf(id.toLong()))`를 호출한다.

내부 notification action 전용 entrypoint라면 exported를 피하거나 caller/token 검증이 필요하다.

### M8. Exported WebView extras

판단: 타당.

근거:

- `app/src/main/AndroidManifest.xml:463`, `476` 부근 `WebViewActivity`, `PoTokenWebViewLoginActivity`가 exported다.
- `WebViewActivity.kt:62`는 `intent.extras!!.getString("url")!!`를 강제 역참조하고, `WebViewActivity.kt:159`는 새 실행에서 `cookieManager.removeAllCookies(null)`를 호출하며, `WebViewActivity.kt:246`은 전달 URL을 로드한다.
- `PoTokenWebViewLoginActivity.kt:101`은 전달 URL을 preference에 저장하고, `PoTokenWebViewLoginActivity.kt:172`, `188`, `256` 부근에서 WebView load를 수행하며, `PoTokenWebViewLoginActivity.kt:192`는 `no_auth` 조건에서 cookie clearing을 수행한다.

외부 intent가 crash, cookie clearing, 임의 URL load, preference pollution을 유발할 수 있다.

### M10. Large stream unbounded read

판단: 타당.

근거:

- manifest의 launcher alias들이 `ACTION_SEND`와 `application/txt` stream을 받는다.
- `app/src/main/java/com/ireum/ytdl/MainActivity.kt:438` 부근에서 `openInputStream` 후 `BufferedReader`를 만들고 char 단위로 `StringBuilder`에 제한 없이 append한다.
- 이 처리가 `handleIntents` 내에서 수행되어 UI thread block/OOM 위험이 있다.

stream 크기 제한, line/URL count 제한, background parsing이 필요하다.

### M14. Incognito/log disabled failure log

판단: 타당.

근거:

- `DownloadWorker.kt:225` 부근 `logDownloads = log_downloads && !downloadItem.incognito`로 성공/일반 로그 여부를 정한다.
- 실패 catch 경로 `DownloadWorker.kt:949` 부근에서는 `!logDownloads`일 때도 error 내용을 `logRepo.insert(logItem)`로 저장하고 `downloadItem.logID`를 설정한다.

사용자가 log disabled 또는 incognito를 기대한 경우에도 실패 로그가 남는다.

### M15. Sensitive logs and public notifications

판단: 타당. 다만 하위 위험을 분리해야 한다.

근거:

- `app/src/main/java/com/ireum/ytdl/util/NetworkUtil.kt`는 request 실패 시 전체 URL을 error log에 남긴다.
- `YTDLPUtil.kt:704` 부근 `parseYTDLRequestString()`은 `--config`/`--config-locations` 파일 내용을 읽어 최종 command 문자열로 펼친다. 이후 이 문자열이 로그/히스토리/디버그 표면으로 이동하는 경로는 호출 지점별로 제한해 봐야 한다.
- `app/src/main/java/com/ireum/ytdl/util/NotificationUtil.kt:505` 부근 `createDownloadErrored()`는 title과 error를 public visibility notification에 표시한다. 여러 진행/완료 notification도 `VISIBILITY_PUBLIC`이다.

민감 URL, query, error text가 logcat이나 잠금화면 notification에 노출될 수 있다.

## Gemini 미검증 항목 추가 확인

### M9. FileProvider broad root and write grants

판단: 타당.

근거:

- `app/src/main/res/xml/provider_paths.xml`는 `<external-path path="."/>`, `<root-path path="."/>`, `<cache-path path="."/>`를 포함한다.
- `app/src/main/java/com/ireum/ytdl/util/FileUtil.kt:886` 부근 `openFileIntent()`는 임의 `downloadPath`를 FileProvider URI로 만들고 `FLAG_GRANT_READ_URI_PERMISSION`와 `FLAG_GRANT_WRITE_URI_PERMISSION`를 모두 부여한다.
- `shareFileIntent()`와 finished notification 경로는 주로 read grant이지만, provider path 자체가 매우 넓다.

write grant는 최소한 open intent 경로에서 확인된다. provider scope를 앱 관리 파일/다운로드 디렉터리로 좁히고 write grant는 명시적 필요가 없으면 제거해야 한다.

### M11. Concurrent mutation in ResultViewModel

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/database/viewmodel/ResultViewModel.kt:154` 부근에서 `mutableListOf<ResultItem?>()`를 공유한다.
- 여러 `viewModelScope.launch(Dispatchers.IO)` job이 `Semaphore(10)` 아래 병렬로 실행되며 같은 list에 `addAll(...)`한다.

Kotlin mutable list는 concurrent write-safe가 아니다. 결과 누락, 순서 이상, 간헐 crash 가능성이 있다.

### M12. Streaming URL expiry condition inverted

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/ui/downloadcard/DownloadBottomSheetDialog.kt:195` 부근 주석은 “1시간 지난 player URL 제거”를 뜻하지만 조건은 `result.creationTime > System.currentTimeMillis() - 3600000`일 때 `result.urls = ""`다.
- `app/src/main/java/com/ireum/ytdl/ui/downloadcard/ResultCardDetailsDialog.kt:152` 부근에도 같은 패턴이 있다.

조건상 최근 항목을 지우고 오래된 항목은 남길 수 있다. `creationTime` 단위가 ms인지 sec인지도 함께 확인해야 하지만, 현재 조건은 주석과 반대다.

### M13. Blank streaming URL fallback

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/database/repository/ResultRepository.kt:95` 부근 `getStreamingUrlAndChapters(url)`는 실패 시 `Pair(listOf(""), null)`를 반환한다.
- `ResultCardDetailsDialog.kt:286`와 `CutVideoBottomSheetDialog.kt:201` 부근은 list가 empty인지 정도만 확인하고 이후 `urls[0]`를 사용한다.

실패가 “빈 문자열 URL이 있는 성공”처럼 처리될 수 있다.

### M16. Release signing uses debug signingConfig

판단: 타당.

근거:

- `app/build.gradle:36` 부근 `signingConfigs.debug`는 keystore properties 기반이다.
- `app/build.gradle:70` 부근 `release.signingConfig signingConfigs.debug`로 release가 debug signing config를 재사용한다.

실제 keystore 값은 확인하지 않았다. 구성상 release/debug signing boundary가 분리되어 있지 않다.

### M17. Missing Gradle modules in settings

판단: 구조적 지적은 타당하지만, 영향은 보류/하향.

근거:

- `settings.gradle:19`는 `':common', ':app', ':library', ':ffmpeg'`를 include한다.
- 현재 repository file list에는 root `common/`, `library/`, `ffmpeg/` 디렉터리가 확인되지 않았다.

다만 이 세션에서 이전에 `./gradlew :app:compileDebugKotlin -x lint`가 성공한 이력이 있어 “항상 fresh checkout/CI가 실패한다”는 결론은 확정하기 어렵다. Gradle이 빈 프로젝트로 configure하거나 해당 project를 실제로 build하지 않아 통과할 수 있다. 정리 대상은 맞지만 severity는 build blocker보다 repository hygiene/build config risk에 가깝다.

### M18. Queue and duplicate consistency

판단: 타당.

근거:

- `DownloadViewModel.kt:1054` 부근 `queueDownloads()`는 먼저 `queuedItems`를 만들고 `detectAndMarkDuplicates(items, ignoreDuplicates)`를 실행한 뒤, 중복만 제거하고 `repository.updateAll(queuedItems)`를 호출한다.
- `detectAndMarkDuplicates()`는 active/queued snapshot을 `repository.getActiveAndQueuedDownloads()`로 한 번 읽는다.
- 같은 batch 안에 동일 URL이 여러 개 들어온 경우, 아직 DB에 queued 상태로 반영되기 전이라 snapshot에 서로가 보이지 않는다.
- 중복 감지 중 `id == 0L`이면 insert 후 duplicate 상태 update를 수행하는 경로가 있어, 이후 queue update와 id/status 일관성이 흐려질 수 있다.

동일 batch 내 중복, 병렬 queue 요청, 중복으로 표시된 item의 후속 update 순서가 꼬일 수 있다.

### L1. Pause receiver pending result leak

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/receiver/PauseDownloadNotificationReceiver.kt:18` 부근에서 `val result = goAsync()`를 호출한다.
- `itemID == 0`이면 coroutine을 시작하지 않고 빠져나가며 `result.finish()`를 호출하지 않는다.
- coroutine 시작 전 예외에도 `finish()` 보장이 약하다.

BroadcastReceiver pending result가 leak될 수 있다.

### L2. ShareActivity overlay view leak

판단: 타당.

근거:

- `ShareActivity.kt:86` 부근에서 `WindowManager.addView(myView, params)`를 호출한다.
- 같은 파일에서 대응되는 `removeView(...)` 호출이 확인되지 않았다.

Activity 종료/예외 시 overlay view leak 가능성이 있다.

### L3. PlaybackKeepAliveService sticky restart

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/service/PlaybackKeepAliveService.kt`는 STOP action에서만 `START_NOT_STICKY`를 반환한다.
- START action이 아니거나 action이 null인 restart 경로는 foreground 진입 없이 마지막에 `START_STICKY`를 반환할 수 있다.

시스템 재시작 시 foreground service 규칙 위반 가능성이 있다.

### L4. SAF playback path boundary and raw path precheck

판단: 타당.

근거:

- `app/src/main/java/com/ireum/ytdl/ui/player/VideoPlayerActivity.kt:591` 부근은 raw file path에서 `exists/isFile` precheck가 실패하면 SAF URI resolver까지 가지 않고 종료한다.
- `VideoPlayerActivity.kt:3625` 부근 `buildDocumentUriForPath()`는 `relPath.startsWith(treePath)`만 검사한다. 경계 검사가 없어 `Movies`와 `Movies2` 같은 prefix collision이 가능하다.

SAF 파일 재생 실패 및 잘못된 document URI 구성 가능성이 있다.

## Needs Verification 항목 상태

아래 항목은 소스만으로 일부 근거를 확인했지만, 최종 판단에는 런타임/빌드 산출물 검증이 필요하다.

| 항목 | 현재 상태 | 필요한 검증 |
| --- | --- | --- |
| ABI/runtime payload coverage | 미확정 | APK contents, supported ABI별 install/run |
| Native `.so` executable behavior | 미확정 | 실제 기기에서 bundled executable 실행/권한/SELinux 확인 |
| Merged manifest receiver defaults | 미확정 | `processDebugMainManifest` 또는 merged manifest 산출물 확인 |
| Notification channel timing | 미확정 | Android 8+ fresh install에서 channel creation 전 notify 경로 실행 |
| TerminalDownloadWorker foreground setup | 소스 위험 확인 | `setForegroundAsync(...)`를 await하지 않음. 실제 FGS 실패 재현은 기기 필요 |
| Sidecar subtitles | 미확정 | 실제 media item/subtitle 파일명/URI 조합으로 재생 테스트 |
| PiP aspect ratio | 미확정 | 화면 크기/회전/Android version별 PiP 진입 테스트 |
| Imported regex DoS/crash | 소스 위험 확인 | user/imported regex가 `Regex(...)` 또는 `toRegex()`로 직접 사용됨. ReDoS는 payload별 측정 필요 |
| Data-fetch config newline injection | 부분 확인 | `validateDataFetchUrl()`는 http prefix와 일부 blocked option만 본다. newline 뒤 다른 yt-dlp option 삽입 가능성은 별도 PoC 필요 |
| Streaming URL order assumption | 미확정 | yt-dlp output별 audio/video URL 순서 확인 |
| `getFormatsForAll` progress mapping | 소스 위험 확인 | callback line마다 `urlIdx`를 증가시키는 구조. warning/extra line 포함 시 mis-map 가능 |
| Room migrations | 미확정 | schema export와 migration test 필요 |
| DownloadDao scheduled SQL | 미확정 | DAO 호출 여부와 Room compile/runtime query 확인 필요 |
| CrashListener | 미확정 | listener 등록/throw 경로와 crash reporting behavior 확인 필요 |
| Debug po-token logging | 미확정 | build variant별 log path와 token 포함 여부 확인 필요 |

## 우선순위 제안

가장 먼저 볼 항목은 사용자 데이터 삭제 또는 외부 앱 트리거가 가능한 항목이다.

1. H1, H2, M9: 파일 삭제/파일 제공 범위 축소. 앱 전용 경로 강제, tree URI 삭제 금지, FileProvider scope 축소, write grant 제거.
2. H3, M6, M7, M8: exported component와 사용자 제공 option 정책 정리. 내부 activity는 `exported=false` 또는 caller/token 검증, yt-dlp 위험 옵션 allowlist/denylist 도입.
3. M1, M2, M3, M4: WorkManager lifecycle와 foreground 보장 수정. detached scope 제거, structured concurrency, cancellation propagation, 상태 재확인.
4. M10, M11, M12, M13, M18: 안정성/일관성 문제 수정. stream 크기 제한, concurrent collection 보호, streaming URL 실패 표현 정리, queue 중복 batch 검증.
5. M14, M15: incognito/log policy와 public notification visibility 정리.

## 검증 한계

이 문서는 소스 정적 검증 결과다. 실제 exploitability나 사용자 영향도는 Android 버전, device vendor, SAF provider, notification 설정, WorkManager 제약, bundled yt-dlp/ffmpeg 런타임 상태에 따라 달라질 수 있다. 위 “타당” 판정은 “현재 소스가 원본 audit의 위험 조건을 만족한다”는 의미이며, 모든 항목의 runtime PoC가 완료되었다는 뜻은 아니다.

## 수정 진행 기록

작성일: 2026-05-09

아래 내용은 이 문서 작성 후 우선순위에 따라 적용한 코드 변경 기록이다. 각 큰 묶음마다 자체 코드 리뷰를 한 뒤 `./gradlew :app:compileDebugKotlin -x lint`로 검증했다.

### P1. 파일 삭제와 FileProvider 범위

수정 항목: H1, H2, M9

- `CleanUpLeftoverDownloads`가 cache path를 무조건 `deleteRecursively()`하지 않도록 바꿨다. `FileUtil.deleteCachePathIfAppOwned(...)`를 추가해 앱 전용 `cacheDir`, `externalCacheDir`, `externalFilesDir` 하위일 때만 재귀 삭제한다. 사용자 지정 cache path가 넓은 공용 폴더이면 삭제를 거부한다.
- `FileUtil.deleteDocumentUri(...)`에서 `DocumentsContract.isTreeUri(uri)`인 URI 삭제를 거부하고, `DocumentFile.fromTreeUri(...).delete()` fallback을 제거했다.
- `provider_paths.xml`에서 `<root-path path="."/>`와 전체 external path 공개를 제거했다. FileProvider는 `Download/YTDLnisx/`, app external files, cache만 공개한다.
- `FileUtil.openFileIntent(...)`에서 `FLAG_GRANT_WRITE_URI_PERMISSION`을 제거하고, FileProvider URI 생성 실패를 예외 대신 사용자 오류로 처리한다.

남은 리스크:

- 사용자가 기본 `Download/YTDLnisx` 밖의 custom raw path에 저장한 파일은 FileProvider 공유/open이 제한될 수 있다. 보안상 의도한 축소지만 UX 회귀 가능성은 실제 기기에서 확인해야 한다.

### P2. 외부 entrypoint와 yt-dlp 옵션 정책

수정 항목: H3, M6, M7, M8

- `WebViewActivity`, `PoTokenWebViewLoginActivity`, `ResumeActivity`를 `android:exported="false"`로 바꿔 외부 앱이 직접 실행하지 못하게 했다.
- `ShareActivity`는 외부 intent의 `BACKGROUND` extra를 더 이상 신뢰하지 않고, alias metadata의 `quick_run_background`만 사용한다.
- `ShareActivity`는 외부 intent의 `TYPE` extra를 무시하고 앱의 `getDownloadType(...)` 결과만 사용한다. 이 과정에서 `DownloadType.valueOf(...)` crash surface도 제거됐다.
- `YoutubeDLCompat`의 sanitizer에 실행/외부 프로세스 계열 위험 옵션 차단을 추가했다. 차단 대상은 `--exec`, `--external-downloader`, `--downloader`, `--external-downloader-args`, `--downloader-args`, `--postprocessor-args`, `--ppa`, `--use-postprocessor` 등이다.
- `YTDLPUtil.validateDataFetchUrl(...)`에서 newline/whitespace가 포함된 URL을 거부해 data-fetch config newline injection 가능성을 줄였다.
- `TerminalDownloadWorker`의 command config 생성도 `YoutubeDLCompat.stripExternalFfmpegLocationOptions(...)`를 거치고 app-generated config allowlist에 등록하도록 정리했다.

남은 리스크:

- yt-dlp 옵션은 표면이 넓다. 이번 패치는 process spawning 중심의 denylist이며, 네트워크/출력/쿠키 파일 관련 고위험 옵션까지 완전한 allowlist로 잠근 것은 아니다.

### P3. WorkManager lifecycle과 foreground 처리

수정 항목: M1, M2, M3, M4

- `setForegroundSafely()`가 성공 여부를 `Boolean`으로 반환하도록 바꿨다. `DownloadWorker`, `LocalAddWorker`, `UpdateMultipleDownloadsDataWorker`는 foreground 진입 실패 시 작업을 계속하지 않고 `Result.retry()`를 반환한다.
- `TerminalDownloadWorker`는 `setForegroundAsync(...)` fire-and-forget 대신 suspend `setForeground(...)`를 사용하고 실패 시 retry한다.
- `DownloadWorker`의 다운로드 실행을 detached `CoroutineScope(Dispatchers.IO).launch`에서 `coroutineScope { launch(...) }` 구조로 바꿔 worker lifecycle 안에서 child job을 기다리게 했다.
- `DownloadWorker`의 progress callback 로그 업데이트는 detached coroutine 대신 callback thread에서 IO blocking으로 처리해 worker 종료 뒤 로그 job이 남지 않게 했다.
- 두 번째 metadata refresh에서도 현재 DB 상태가 `Active`인지 재확인한 뒤 `dao.updateWithoutUpsert(...)`를 호출하도록 바꿨다.
- `TerminalDownloadWorker`의 cache move를 detached coroutine에서 `withContext(Dispatchers.IO)`로 바꿔 이동 실패가 worker 실패로 반영되게 했다.

자체 리뷰에서 발견해 수정한 문제:

- 최초 구조화 변경 후 `DownloadWorker`의 닫는 brace가 하나 부족해 컴파일 실패가 발생했다. 바로 수정했고 이후 컴파일 통과했다.

남은 리스크:

- `DownloadWorker`의 flow 수집/동시 다운로드 모델은 여전히 복잡하다. 구조화는 했지만 실제 pause/cancel/retry 동작은 기기에서 장시간 다운로드로 회귀 테스트가 필요하다.

### P4. 안정성/일관성 버그

수정 항목: M5, M10, M11, M12, M13, M18, L1, L2, L3, L4

- `AlarmScheduler.cancel()`이 schedule과 같은 `PendingIntent.getBroadcast(...)` type을 사용하도록 고쳐 alarm cancel이 실제로 매칭되게 했다.
- `MainActivity`의 shared text stream 읽기에 `MAX_SHARED_TEXT_CHARS = 128 KiB` 제한을 추가하고 stream/reader를 `use`로 닫도록 했다.
- `ResultViewModel.parseQueriesImpl(...)`의 병렬 결과 list를 `Collections.synchronizedList(...)`로 바꿔 concurrent write를 방지했다.
- player URL expiry 조건을 `creationTime < now - 1h`로 고쳐 오래된 URL만 비우도록 했다.
- `ResultRepository.getStreamingUrlAndChapters(...)` 실패 fallback을 `listOf("")` 대신 `emptyList()`로 바꾸고 blank URL을 필터링한다. player/cut caller도 blank URL이 있으면 실패로 처리한다.
- `DownloadViewModel.detectAndMarkDuplicates(...)`에서 같은 batch 안의 `url_type` 중복을 `canonicalDuplicateUrl` 기준으로 잡아 duplicate 처리하도록 했다.
- `PauseDownloadNotificationReceiver`는 `itemID == 0` 또는 초기 예외 경로에서도 `goAsync().finish()`를 보장한다.
- `ShareActivity` overlay view를 field로 보관하고 `onDestroy()`에서 `WindowManager.removeView(...)`를 호출한다.
- `PlaybackKeepAliveService`는 `ACTION_START`/`ACTION_STOP`이 아닌 restart/null action에서 foreground 없이 sticky로 남지 않고 `START_NOT_STICKY`로 종료한다.
- `VideoPlayerActivity`는 raw path 파일이 직접 보이지 않아도 SAF document URI를 먼저 시도할 수 있게 했고, document URI 생성은 `FileUtil.buildDocumentUriForPath(...)`로 통합했다.
- `FileUtil.buildDocumentUriForPath(...)`의 tree path 검사를 prefix 문자열 비교에서 경계-aware 비교로 바꿔 `Movies`와 `Movies2` 같은 prefix collision을 막았다.

남은 리스크:

- `MainActivity`의 stream 파싱은 크기 제한을 추가했지만 여전히 UI 흐름 안에서 수행된다. 매우 느린 content provider에 대한 ANR 회피는 별도 background parsing으로 더 줄일 수 있다.
- batch duplicate 보강은 `url_type`에 초점을 맞췄다. `config` mode의 동일 batch 중복은 더 엄격한 command key 비교를 추가로 설계할 수 있다.

### P5. 로그와 notification 노출

수정 항목: M14, M15

- `DownloadWorker`는 `log_downloads=false` 또는 `incognito=true`이면 실패 경로에서도 새 `LogItem`을 insert하지 않는다. 이 경우 `downloadItem.logID`도 `null`로 유지한다.
- 실패 logcat은 exception message/stack 전체 대신 item id와 exception class 중심으로 축소했다.
- `NetworkUtil`은 요청 URL 전체를 logcat에 남기지 않고 generic request 이름과 실패 class만 기록한다.
- `NotificationUtil.createDownloadErrored(...)`는 실패 알림 제목/본문에 다운로드 제목, URL, raw error를 표시하지 않는다. visibility도 `VISIBILITY_PRIVATE`로 낮췄다.

남은 리스크:

- 앱 내 로그 화면에는 사용자가 명시적으로 로그를 켠 경우 command/output이 계속 저장된다. 이는 기능 의도와 디버깅 필요성을 유지한 상태의 축소다.

### 아직 코드 수정하지 않은 항목

- Needs Verification 항목 중 APK contents, Room migration, 실제 기기 SAF/notification/WorkManager/runtime behavior는 아직 별도 런타임 검증이 필요하다.
- Sidecar subtitles와 PiP aspect ratio는 소스 일부는 확인했지만, 실제 파일/기기 조합이 필요한 영역이라 이번 추가 패치에서 더 고정하지 않았다.

### 추가 잔여 항목 수정 기록

작성일: 2026-05-09

사용자가 “남은 항목 중 해결 가능한 항목” 수정을 요청한 뒤 추가로 적용한 변경이다.

수정 항목:

- M16 release signing config: `app/build.gradle`에서 release가 `signingConfigs.debug`를 재사용하지 않도록 분리했다. `keystore.properties`가 존재하고 비어 있지 않을 때만 `signingConfigs.release`를 만들고 release buildType에 연결한다. debug buildType은 Android 기본 debug signing을 사용한다. 민감 파일 내용은 읽거나 출력하지 않았다.
- M17 missing Gradle modules: `settings.gradle`의 stale include를 제거하고 실제 존재하는 `:app`만 include하도록 정리했다. `:common`, `:library`, `:ffmpeg`는 현재 Gradle project dependency로 참조되지 않는다.
- Merged manifest receiver defaults: manifest에 선언된 내부 broadcast receiver들(`CancelDownloadNotificationReceiver`, `CancelWorkReceiver`, `PauseDownloadNotificationReceiver`, `CancelScheduleAlarmReceiver`, `ScheduleAlarmReceiver`)에 `android:exported="false"`를 명시했다.
- Imported regex crash: command template URL regex와 YouTube player client URL regex를 `safeRegexMatches(...)`로 감싸 invalid regex가 crash를 만들지 않게 했다. 사용자 regex 길이는 512자로 제한했다.
- `getFormatsForAll` progress mapping: yt-dlp callback의 빈 줄/파싱 실패 라인에서 URL index가 증가하지 않도록 바꿨고, URL list는 `validateDataFetchUrl(...)`를 거쳐 사용한다. debug `println(...)`도 제거했다.
- `DownloadDao.checkAllQueuedItemsAreScheduledAfterNow(...)`: malformed `CASE` SQL을 boolean 조건식으로 바꿔 `inverted=false`일 때 “현재 시간 이하인 항목이 없어야 함”, `inverted=true`일 때 “현재 시간 이상인 항목이 없어야 함”으로 명확히 했다.
- Debug po-token logging: `PoTokenWebView`와 `NewPipePoTokenGenerator`의 debug log에서 po-token/visitor data 원문을 출력하지 않고 길이만 남기도록 바꿨다.
- CrashListener: uncaught exception 전체 stack을 logcat에 출력하지 않고 exception class만 기록하도록 줄였다. crash DB log는 비동기 fire-and-forget 대신 `runBlocking(Dispatchers.IO)`로 저장 후 종료해 종료 보장을 높였다.

추가 검증:

```text
./gradlew :app:compileDebugKotlin -x lint
```

결과: 성공.

### 수정 후 검증

실행한 검증:

```text
./gradlew :app:compileDebugKotlin -x lint
```

결과: 성공.

관찰된 기존 경고:

- Gradle Groovy DSL deprecation 경고.
- `settings.gradle`에 include된 `:common`, `:library`, `:ffmpeg` 디렉터리가 없다는 Gradle deprecation 경고.
- Room `TerminalDao` query mismatch warning.
- 여러 기존 Kotlin deprecation/불필요 safe-call 경고.

위 경고들은 이번 수정으로 새롭게 실패를 만들지는 않았고, compile task는 성공했다.

### FileUtil tree URI refinement

2026-05-09 추가 보정:

- `FileUtil.deleteDocumentUri(...)`의 SAF 삭제 정책을 tree 루트 URI 전체 삭제 차단으로 좁혔다. `DocumentsContract.isTreeUri(uri)`이면서 `DocumentsContract.isDocumentUri(...)`가 아닌 URI만 거부하고, tree 아래의 실제 document URI는 정상 삭제 경로를 유지한다.
- 목적은 H1의 “tree root 전체 삭제” 위험을 막으면서, SAF tree 권한으로 생성한 개별 document URI 삭제까지 막아버리는 회귀를 피하는 것이다.
- 동일 파일의 move/copy 경로에서 Kotlin 컴파일 경고를 만들던 불필요한 non-null assertion 두 곳을 제거했다.

### History RecyclerView inconsistency 대응

2026-05-09 추가 보정:

- 오래된 crash log의 `RecyclerView Inconsistency detected`는 현재 코드에서 `HistoryPaginatedAdapter`가 이미 `PagingDataAdapter`로 바뀌어 있어 원래 형태의 수동 backing-list 불일치 가능성은 상당 부분 줄어든 상태로 확인했다.
- 다만 `PagingDataAdapter` 위에서 선택 상태 갱신과 썸네일 표시 옵션 변경이 `notifyDataSetChanged()` 또는 `notifyItemRangeChanged(0, itemCount, ...)`를 직접 호출하고 있었다. Paging diff dispatch와 수동 전체 범위 notify가 겹치면 동일 계열의 item position inconsistency가 재발할 수 있어 제거했다.
- `HistoryPaginatedAdapter`가 attached `RecyclerView`를 추적하고, 선택 상태/visible item 갱신은 현재 붙어 있는 holder만 직접 갱신하도록 바꿨다. offscreen item은 다음 bind 시 adapter의 선택 상태와 썸네일 설정을 반영한다.
- `HistoryFragment`의 swipe cancel/reset 경로도 `historyAdapter.notifyItemChanged(...)`/`notifyDataSetChanged()` 대신 `historyAdapter.refreshVisibleItem(position)`을 호출하도록 바꿨다.
- 검증: `./gradlew :app:compileDebugKotlin -x lint` 성공.
