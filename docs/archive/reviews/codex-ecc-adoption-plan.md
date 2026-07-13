# Codex 및 Everything Claude Code 부분 도입 검토

> Status: Archived
> Snapshot date: 2026-05-02
> Revalidate all findings against the current source before treating them as current.

작성일: 2026-05-02

## 0. 검토 범위와 자료 확인

이 문서는 Codex 설정과 Everything Claude Code, 이하 ECC,를 이 프로젝트에 부분 도입할지 판단하기 위한 사전 검토 문서이다. 실제 적용은 하지 않았다.

이번 작업에서 생성한 항목은 이 파일 하나와, 이 파일을 담기 위한 `docs/` 디렉터리뿐이다. `AGENTS.md`, hooks, MCP, skills, dependency, Codex 설정 파일은 만들지 않았다.

확인한 자료:

| 자료 | 확인 여부 | 메모 |
|---|---:|---|
| OpenAI Codex 공식 문서: Custom instructions with AGENTS.md | 확인 | https://developers.openai.com/codex/guides/agents-md |
| OpenAI Codex 공식 문서: Agent Skills | 확인 | https://developers.openai.com/codex/skills |
| OpenAI Codex 공식 문서: Subagents | 확인 | https://developers.openai.com/codex/subagents |
| OpenAI Codex 공식 문서: Hooks | 확인 | https://developers.openai.com/codex/hooks |
| OpenAI Codex 공식 문서: Model Context Protocol | 확인 | https://developers.openai.com/codex/mcp |
| OpenAI Codex 공식 문서: Rules | 확인 | https://developers.openai.com/codex/rules |
| OpenAI Codex 공식 문서: Plugins | 확인 | https://developers.openai.com/codex/plugins |
| GitHub `affaan-m/everything-claude-code` README 및 디렉터리 목록 | 확인 | https://github.com/affaan-m/everything-claude-code |
| 로컬 프로젝트 `README.md`, `settings.gradle`, `build.gradle`, `app/build.gradle`, `AndroidManifest.xml`, 파일 목록 | 확인 | 읽기 전용으로 확인 |

판단 원칙:

- OpenAI 공식 Codex 문서와 ECC 방식이 충돌하면 OpenAI 공식 Codex 문서를 우선한다.
- ECC는 전체 설치 대상이 아니라 참고 자료로만 본다.
- 불확실한 내용은 “확인 필요”로 표시한다.

## 1. 프로젝트 기술 스택과 구조 요약

### Android/Gradle 프로젝트 여부

이 프로젝트는 Android/Gradle 프로젝트로 보는 것이 적절하다.

근거:

- 루트에 `settings.gradle`, `build.gradle`, `gradlew`, `gradlew.bat`, `gradle/`이 있다.
- `app/build.gradle`은 `com.android.application`, Kotlin Android, KSP, Kotlin serialization, Kotlin parcelize, Compose 플러그인을 사용한다.
- `compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`이다.
- `applicationId`와 namespace는 `com.ireum.ytdl`이다.
- 주요 소스는 `app/src/main/java/com/ireum/ytdl/**/*.kt`에 있는 Kotlin 코드다.
- Room schema가 `app/schemas/...`에 다수 존재한다.
- WorkManager 관련 클래스가 `app/src/main/java/com/ireum/ytdl/work/`에 있다.
- Media3/ExoPlayer, youtubedl-android, aria2c, ffmpeg 관련 dependency와 asset/native library가 포함되어 있다.

주의할 점:

- `settings.gradle`에는 `:common`, `:app`, `:library`, `:ffmpeg`가 선언되어 있으나, 현재 로컬 루트에서 `common/`, `library/`, `ffmpeg/` 디렉터리는 발견되지 않았다. 이 선언이 의도된 잔존 설정인지, 삭제된 모듈인지, 외부에서 생성되는 모듈인지는 확인 필요.
- 루트에 `.tmp_*` 디렉터리와 `.tmp_*` 바이너리/압축 파일이 매우 많다. Codex 지시에는 이 임시 산출물을 무조건 읽거나 수정하지 않도록 명시하는 편이 좋다.

### Python 프로젝트 여부

현재 로컬 파일 목록 기준으로 Python 프로젝트로 보기는 어렵다.

근거:

- `*.py` 파일은 발견되지 않았다.
- `pyproject.toml`, `requirements.txt`, `setup.py`도 프로젝트 루트 파일 목록에서 발견되지 않았다.

단, 프로젝트가 yt-dlp, ffmpeg, aria2c, NewPipe Extractor, WebView 기반 po token 생성과 관련되어 있어 외부 Python 생태계와 기능적으로 연결될 수 있다. 이 저장소 자체의 Python 코드 변경 기준은 “현재 없음”으로 두고, 추후 Python 스크립트가 추가되면 별도 기준을 만든다.

### 주요 구조

- `app/src/main/java/com/ireum/ytdl/`: Kotlin 애플리케이션 코드
- `app/src/main/res/`: XML layout, navigation, menu, drawable, values 리소스
- `app/src/main/assets/bin/`: ffmpeg, yttml, native binary payload
- `app/src/main/jniLibs/`: arm64-v8a native libraries
- `app/schemas/`: Room schema JSON
- `fastlane/metadata/android/`: 배포 메타데이터와 changelog
- `.tmp_*`: ffmpeg, media3, termux package, native library 추출/검토 임시 산출물

## 2. Codex/ECC 도입 목적

이 프로젝트에서 Codex/ECC 도입이 해결할 수 있는 문제는 다음이다.

- Android/Kotlin/Gradle 변경 시 영향 범위를 놓치지 않도록 반복 검토 기준을 제공한다.
- Room schema, WorkManager, Manifest, Media3/ExoPlayer, youtubedl-android, ffmpeg/aria2c 같은 민감한 영역 변경 전에 확인 항목을 고정한다.
- 빌드와 테스트 명령을 매번 추정하지 않고 일관되게 사용하게 한다.
- 대형 바이너리, 임시 추출물, secrets 파일을 실수로 읽거나 수정하는 위험을 줄인다.
- 코드 리뷰 시 보안, 권한, 백그라운드 작업, 네트워크, 파일 시스템 접근을 우선적으로 보게 한다.

도움이 큰 영역:

- 반복 작업: 높음. 빌드/테스트/검토 기준을 AGENTS.md에 두면 효과가 크다.
- 코드 리뷰: 높음. Android 권한, exported 컴포넌트, Room migration, native binary 변경 검토에 유용하다.
- 테스트 검증: 중간 이상. Gradle 명령 후보를 명시할 수 있다.
- 성능 개선: 중간. WorkManager, Media3, 파일 스캔, 캐시, 썸네일 로딩 등 영역별 체크리스트가 유용하다.
- 보안 점검: 높음. Manifest, cookies, token, WebView, external storage, MCP/hooks 권한 제한에 중요하다.

## 3. OpenAI Codex 공식 기능 기준 검토

### AGENTS.md

도입 적절성: 높음.

OpenAI 공식 문서 기준 Codex는 작업 전에 `AGENTS.md`를 읽고, 전역 지침과 프로젝트 지침을 계층적으로 합친다. 기본 최대 크기는 `project_doc_max_bytes` 기준 32 KiB로 설명되어 있다.

이 프로젝트에는 루트 `AGENTS.md` 하나만 최소한으로 두는 방식이 적절하다. 내용은 Android/Gradle 특화 규칙, 금지 파일, 검증 명령 후보, 보안 주의점 정도로 제한해야 한다.

현재는 만들지 않는다.

### Skills

도입 적절성: 중간.

OpenAI 공식 문서 기준 skill은 `SKILL.md`가 있는 디렉터리이며, Codex가 필요할 때만 전체 내용을 읽는 progressive disclosure 구조다. repository skills는 `.agents/skills` 아래에서 읽힌다.

이 프로젝트에는 처음부터 여러 skills를 넣기보다, AGENTS.md로 충분한지 본 뒤 1~3개만 추가하는 것이 안전하다. 후보는 Android review, Room migration review, media/download pipeline review 정도다.

현재는 만들지 않는다.

### Subagents

도입 적절성: 중간.

OpenAI 공식 문서 기준 Codex subagents는 명시적으로 요청했을 때만 생성되며, 병렬 탐색이나 다단계 기능 검토에 유용하지만 토큰을 더 쓴다. 기본 제공 역할로 `default`, `worker`, `explorer`가 있고 custom agent도 가능하다.

이 프로젝트에서는 reviewer/explorer 성격으로만 제한적으로 유용하다. 예를 들어 큰 PR에서 Manifest/보안, Room/schema, WorkManager/download pipeline을 병렬로 검토할 때 의미가 있다. 평소 작업에는 과하다.

현재는 custom subagent 설정을 만들지 않는다.

### Hooks

도입 적절성: 낮음에서 중간.

OpenAI 공식 문서 기준 Codex hooks는 feature flag와 설정이 필요하며, `PreToolUse`, `PostToolUse`, `Stop` 등 lifecycle에 deterministic script를 실행한다. 여러 hook source가 있으면 매칭 hook들이 함께 실행될 수 있다.

이 프로젝트에는 자동 실행 hooks를 바로 도입하지 않는 것이 좋다. Gradle build, native binary, Android resource 작업은 시간이 길고 환경 의존성이 크며, hook이 자동 실행되면 작업 지연과 오탐이 생길 수 있다.

후보가 있다면 “위험 명령 경고”, “secrets 접근 차단”, “대형 바이너리 생성 경고” 정도지만, 실제 도입은 별도 승인 후 최소 구성으로만 한다.

현재는 만들지 않는다.

### MCP/plugins

도입 적절성: 낮음.

OpenAI 공식 문서 기준 MCP는 Codex에 외부 도구와 문서 접근을 연결한다. 설정은 `config.toml`에 저장되며, STDIO/HTTP 서버, bearer token, OAuth, enabled/disabled tool 제한 등을 다룬다. Plugins는 skills, apps, MCP servers를 묶는 배포 단위다.

이 프로젝트는 우선 로컬 Android 코드 검토가 중심이므로 MCP/plugins를 바로 도입할 필요가 낮다. 외부 문서 확인이 필요할 때는 OpenAI Docs MCP나 Android/Kotlin 문서용 MCP를 개인 환경에서 쓰는 것은 가능하지만, 프로젝트 설정으로 커밋하는 것은 보류가 낫다.

현재는 만들지 않는다.

### Rules

도입 적절성: 낮음에서 중간.

OpenAI 공식 문서 기준 Codex rules는 sandbox 밖에서 실행 가능한 명령 prefix를 제어하는 실험적 기능이다. 프로젝트 로컬 rules는 trusted project `.codex/` layer가 필요하다.

이 프로젝트에는 `./gradlew` 계열 검증 명령을 승인 후보로 둘 수는 있지만, 프로젝트에 `.codex/rules`를 바로 추가할 필요는 없다. 우선 AGENTS.md에 “실행 전 승인 필요 명령”을 문서화하는 수준이 낫다.

## 4. ECC에서 참고할 만한 항목

ECC는 agents, skills, rules, hooks, MCP configs, plugins 등을 포함한 대규모 agent harness 설정 모음이다. README 기준 Codex도 지원 대상으로 언급되며, 2026년 4월 rc 문서에는 다수의 agents와 skills가 포함되어 있다고 설명되어 있다. 이 프로젝트에는 전체 설치가 아니라 선택적 참고만 적절하다.

### 가져올 만한 agents

직접 복사보다는 역할 개념만 참고한다.

- `code-explorer`: 큰 Kotlin 코드베이스에서 영향 범위 탐색 역할로 참고.
- `code-reviewer`: 일반 리뷰 관점 참고.
- `kotlin-reviewer`: Kotlin 코드 리뷰 관점 참고.
- `kotlin-build-resolver`: Gradle/Kotlin 빌드 실패 분석 관점 참고.
- `java-reviewer`: Android/Java interop 또는 JVM API 검토 관점 참고.
- `database-reviewer`: Room entity/DAO/migration 검토 관점 참고.
- `security-reviewer`: Manifest, WebView, token, storage, exported component 검토 관점 참고.
- `performance-optimizer`: 파일 스캔, media playback, background work 성능 검토 관점 참고.
- `silent-failure-hunter`: WorkManager나 background download 실패 누락 탐지 관점 참고.

현재 Codex 공식 subagent 형식과 ECC agent 파일 형식이 완전히 동일하다고 단정하면 안 된다. Codex에서 쓰려면 OpenAI 공식 custom agent TOML 형식으로 재작성해야 한다.

### 가져올 만한 skills

직접 설치하지 말고 주제만 참고한다.

- `android-clean-architecture`: Android 계층화, 유지보수성 검토 참고.
- `database-migrations`: Room schema/migration 체크리스트 참고.
- `documentation-lookup`: API 변경 전 공식 문서 확인 습관 참고.
- `context-budget`: AGENTS.md와 skills가 과도하게 커지지 않도록 참고.
- `git-workflow`: 커밋/PR 전 검증 흐름 참고.
- `coding-standards`: Kotlin/XML/Gradle 코딩 기준으로 축약 가능.
- `eval-harness`: 검색/추천 로직이나 po token 생성 흐름에 평가셋이 생길 경우 참고.
- `browser-qa`, `e2e-testing`: WebView 기반 po token 흐름을 검증할 때만 참고.

### 가져올 만한 rules

ECC rules는 common과 언어별 디렉터리로 구성되어 있으며 Kotlin 디렉터리도 보인다. 이 프로젝트에서는 다음 원칙만 추려서 AGENTS.md 후보로 바꾸는 것이 좋다.

- 수정 전 영향 범위 파악.
- build.gradle, manifest, schema, worker 변경 시 전용 체크리스트 적용.
- secrets와 대형 바이너리 접근 제한.
- 테스트와 빌드 검증 명령을 변경 범위에 맞게 선택.

Codex 공식 `rules` 기능 파일로 바로 옮기는 것은 보류한다. 공식 rules는 command approval 정책이고, ECC의 rules는 일반 개발 규칙 문서에 가까울 수 있어 의미가 다르다.

### 참고만 하고 직접 도입하지 않을 항목

- ECC 전체 installer.
- ECC hooks runtime.
- continuous learning, session persistence, memory extraction.
- dashboard GUI.
- 다수 MCP configuration.
- legacy command shims.
- 대량 skills.
- npm/yarn/python 의존성 기반 운영 도구.

### Codex와 호환성이 애매한 항목

- Claude Code 전용 hook schema와 Codex hooks schema.
- ECC agent markdown 파일과 Codex custom agent TOML 파일.
- ECC installer가 생성하는 Claude/Cursor/OpenCode 설정과 Codex 공식 설정 계층.
- ECC plugin packaging과 OpenAI Codex plugin packaging.
- ECC rules 문서와 Codex 공식 `.rules` command policy.

## 5. 이 프로젝트에 유용할 가능성이 높은 설정

### AGENTS.md 규칙 후보

나중에 승인 후 `AGENTS.md`를 만든다면 다음 정도가 적절하다.

- 이 저장소는 Android/Kotlin/Gradle 프로젝트이며, Python 프로젝트로 가정하지 않는다.
- 기존 파일 수정 전 관련 Gradle, Manifest, Room schema, WorkManager 영향 범위를 확인한다.
- `app/src/main/assets/bin/`, `app/src/main/jniLibs/`, `.tmp_*`, release artifact, keystore 관련 파일은 명시 요청 없이는 수정하지 않는다.
- `local.properties`, `keystore.properties`, `.env`, token/cookie/API key로 보이는 파일은 출력하거나 문서화하지 않는다.
- `build.gradle`, `settings.gradle`, `AndroidManifest.xml` 수정은 최소화하고 변경 이유와 검증 방법을 함께 남긴다.
- Room entity/DAO/DBManager/migration 변경 시 schema 변경과 migration 검증 필요 여부를 확인한다.
- WorkManager worker 변경 시 constraint, retry, foreground service, notification, cancellation 동작을 확인한다.
- Media3/ExoPlayer, youtubedl-android, ffmpeg, aria2c 변경은 native library, ABI split, packagingOptions, startup crash 위험을 검토한다.
- 검증 명령 후보는 변경 범위에 따라 `./gradlew :app:compileDebugKotlin -x lint`, `./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`, `./gradlew :app:connectedDebugAndroidTest` 중 선택한다.
- shell/network/dependency 설치는 사용자 승인 없이는 하지 않는다.

### skills 후보

1단계에서는 skills 없이 시작한다.

중간 도입 시 후보:

- `android-gradle-review`: Gradle, Manifest, permissions, build variants, ABI split 검토.
- `room-migration-review`: Room entity, DAO, migration, schema JSON 검토.
- `download-pipeline-review`: youtubedl-android, ffmpeg, aria2c, WorkManager, notification, storage 검토.
- `media-playback-review`: Media3/ExoPlayer, playback state, PiP, foreground service 검토.
- `security-privacy-review`: cookies, po token, WebView, external storage, exported component, secrets 검토.

### subagents 후보

프로젝트 설정 파일로 custom agent를 만들기보다는, 필요할 때 Codex 기본 subagent를 명시 요청으로 쓰는 편이 낫다.

- explorer: 큰 변경 전 영향 범위 탐색.
- reviewer: PR/패치 리뷰.
- worker: 구현 분할이 필요할 때만, 파일 소유 범위를 명확히 나눠 사용.

custom subagent 후보는 보류한다. 필요해지면 `android-reviewer`, `room-reviewer`, `security-reviewer` 정도를 Codex 공식 TOML 형식으로 따로 설계한다.

### hooks 후보

현 시점에서는 원칙적으로 보류한다.

후보를 적는다면 다음 정도만 검토한다.

- secrets 접근 경고 hook.
- `.tmp_*`, native binary, keystore 파일 수정 경고 hook.
- Gradle/Manifest/Room schema 수정 후 검증 명령 안내 hook.

자동 formatter, 자동 test, 자동 build hook은 현재 프로젝트에 과하다.

### MCP 후보

현 시점에서는 프로젝트에 MCP를 추가하지 않는다.

개인 환경에서만 쓸 수 있는 후보:

- OpenAI Docs MCP: Codex 기능 확인용.
- Android/Kotlin/Gradle 공식 문서 검색용 MCP 또는 Context7: dependency/API 변경 전 확인용.
- GitHub MCP: PR/issue 작업이 많아질 때만.

프로젝트 `.codex/config.toml`에 MCP를 커밋하는 것은 보류한다.

## 6. 도입하지 않는 것이 나은 항목과 이유

### 기술 스택과 맞지 않는 항목

- Python reviewer, Django, FastAPI, PyTorch, Node/TypeScript frontend 중심 skills는 현재 저장소에는 맞지 않는다.
- browser QA는 앱 WebView 특정 흐름을 검증할 때만 의미가 있고, 일반 Android UI 테스트 대체 수단은 아니다.
- web/frontend 디자인 hook은 XML/Android UI 중심인 현재 프로젝트에는 직접성이 낮다.

### 과도하게 복잡한 항목

- ECC 전체 설치.
- 대량 agents/skills 동시 설치.
- session persistence와 continuous learning.
- dashboard, daemon, state store.
- 다수 MCP 서버 활성화.

### 컨텍스트를 많이 쓰는 항목

- 긴 AGENTS.md.
- ECC rules 전체를 AGENTS.md에 붙여넣는 방식.
- language별 rules를 모두 넣는 방식.
- 여러 custom agents에 긴 developer instructions를 넣는 방식.

### 보안상 위험한 항목

- MCP에 bearer token/OAuth를 연결하고 프로젝트 설정으로 커밋하는 방식.
- hooks가 prompts, tool input/output, 파일 경로, command output을 외부로 전송하는 방식.
- SessionStart에서 이전 대화나 파일 내용을 자동 주입하는 방식.
- secrets 탐지 명목으로 `.env`, keystore, local properties를 읽는 hook.

### 자동 실행 위험이 있는 항목

- Gradle build/test 자동 실행 hook.
- formatter가 XML/Kotlin/Gradle 파일을 자동 수정하는 hook.
- dependency update 자동화.
- ffmpeg/native binary 파일 검증을 자동으로 긴 시간 수행하는 hook.

## 7. Android/Gradle 프로젝트 기준 주의점

### build.gradle 수정

- 루트 `build.gradle`과 `app/build.gradle`은 AGP, Kotlin, KSP, Room, Media3, youtubedl-android, aria2c, desugaring, ABI split에 영향을 준다.
- dependency 추가는 APK 크기, minSdk, ProGuard/R8, native library 충돌, GPL 고지에 영향을 줄 수 있다.
- `signingConfigs`는 `keystore.properties`를 읽는다. secrets 출력 금지.
- 현재 `settings.gradle`에 선언된 `:common`, `:library`, `:ffmpeg` 디렉터리 부재는 확인 필요다.

### AndroidManifest.xml 수정

- exported activity/receiver/service 변경은 보안과 Android 12+ 동작에 직접 영향이 있다.
- `INTERNET`, storage/media permissions, notification, exact alarm, foreground service 권한을 변경할 때는 Android 버전별 동작을 확인해야 한다.
- `ShareActivity`, `TransparentActivity`, settings/terminal/WebView activity 등 exported 컴포넌트의 intent filter 변경은 외부 앱 진입점이므로 보안 리뷰가 필요하다.
- `FileProvider`, backup/dataExtractionRules, requestLegacyExternalStorage 변경은 파일 접근과 개인정보에 영향이 있다.

### Room schema 변경

- `DBManager`, entity, DAO, migration 수정 시 schema JSON 갱신 여부를 확인한다.
- migration 누락은 기존 사용자 데이터 손상으로 이어질 수 있다.
- schema package가 `com.deniscerri...`와 `com.ireum...` 양쪽에 존재하므로 현재 migration 이력의 의미는 확인 필요다.

### WorkManager 변경

- `DownloadWorker`, `TerminalDownloadWorker`, `ObserveSourceWorker`, update workers, cleanup workers 변경 시 cancellation, retry, foreground service notification, network/storage constraint를 확인한다.
- Android 14+ foreground service type과 notification permission 영향도 함께 봐야 한다.

### Media3/ExoPlayer 관련 변경

- Media3 dependency는 `1.9.0` 계열로 보인다.
- playback state 보존, PiP, foreground media service, file URI/content URI 처리, subtitle/audio track 처리 변경은 실제 기기 검증이 필요하다.
- legacy `exomedia`와 Media3가 함께 있으므로 역할 분리가 명확한지 확인 필요다.

### youtubedl-android, ffmpeg, aria2c 관련 변경

- `io.github.junkfood02.youtubedl-android:library:0.18.1`, `aria2c:0.18.1`를 사용한다.
- ffmpeg wrapper dependency는 주석 처리되어 있고, hard-sub은 bundled/runtime executable path를 쓴다는 주석이 있다.
- `app/src/main/assets/bin/`과 `jniLibs/`의 native files는 ABI, startup crash, APK 크기, 라이선스 고지에 영향이 크다.
- `.tmp_ffmpeg_*`, `.tmp_termux_*`, `.tmp_media3_*`는 작업 산출물로 보이며, 명시 요청 없이 수정/삭제하지 않는 규칙이 필요하다.

### 테스트/빌드 검증 방식

변경 범위별 후보:

- Kotlin compile: `./gradlew :app:compileDebugKotlin -x lint`
- Debug APK build: `./gradlew :app:assembleDebug`
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Instrumented tests: `./gradlew :app:connectedDebugAndroidTest`
- Release smoke build: `./gradlew :app:assembleRelease -x lint`

실제 명령은 환경, SDK, 연결 기기, 시간이 필요하므로 자동 실행 hook으로 강제하지 말고 작업자가 선택하도록 두는 것이 낫다.

## 8. Python 프로젝트 기준 주의점

현재 이 저장소 자체는 Python 프로젝트로 확인되지 않았다. 아래 항목은 향후 Python 스크립트나 eval harness를 추가할 때의 기준이다.

### 의존성 추가

- `requirements.txt`나 `pyproject.toml`을 새로 만들기 전 목적을 명확히 해야 한다.
- Android 앱 빌드에 필요 없는 Python dependency는 repo를 복잡하게 만들 수 있다.
- ffmpeg/yt-dlp 보조 스크립트가 필요하더라도 앱 runtime dependency와 혼동하지 않는다.

### API 호출 방식 변경

- YouTube, GitHub, 외부 검색 API 호출은 token, rate limit, privacy 이슈가 있다.
- 앱 코드의 OkHttp/Retrofit 호출과 별도 Python 호출이 중복되지 않도록 한다.

### 캐시 구조

- Python 캐시가 추가되면 `.tmp_*`, app cache, Room DB, Android external storage와 역할을 구분해야 한다.
- 대용량 media metadata 캐시는 repo에 커밋하지 않는다.

### 검색/추천 로직

- 현재 앱에는 history, playlist, keyword, youtuber/group, search suggestion 관련 모델/DAO가 있다.
- Python 기반 추천/eval을 추가한다면 Android DB schema와 데이터 export/import 규칙을 먼저 정해야 한다.

### 평가셋/eval harness

- 추천/검색 품질을 평가하려면 fixture, expected ranking, privacy-sanitized sample이 필요하다.
- 실제 사용자 URL, cookies, token, watch history를 평가셋에 넣지 않는다.

### Playwright 또는 외부 API 사용 여부

- WebView po token/login 흐름 검증에 Playwright가 도움될 수 있으나 Android WebView와 완전히 같지 않다.
- Playwright, browser automation, 외부 API는 dependency와 네트워크 권한이 늘어나므로 기본 도입 대상이 아니다.

## 9. 보안상 위험한 설정

### secrets, API key, token, .env 접근 위험

- `local.properties`, `keystore.properties`, signing config, cookies, po token, API key를 Codex 출력이나 문서에 포함하지 않는다.
- hooks가 secrets 탐지를 위해 파일 전체를 읽는 방식은 오히려 노출 위험을 키울 수 있다.

### hooks 자동 실행 위험

- hook은 tool input/output을 받으므로 command, path, file content 일부가 hook process로 전달될 수 있다.
- 여러 hook source가 동시에 실행될 수 있으므로 중복 실행, 지연, 예측 불가한 side effect가 생길 수 있다.
- 자동 formatting/build/test hook은 사용자의 의도와 다른 변경을 만들 수 있다.

### MCP 권한 위험

- MCP는 외부 문서, 브라우저, GitHub, Figma, Sentry 등으로 접근 범위를 넓힌다.
- bearer token/OAuth가 필요한 MCP는 credential 관리가 필요하다.
- project-scoped `.codex/config.toml`은 trusted project에서만 로드되지만, 커밋 시 팀 전체 권한 모델을 건드릴 수 있다.

### 외부 네트워크 접근 위험

- dependency resolve, documentation lookup, GitHub API, yt-dlp 관련 확인은 네트워크를 쓴다.
- 자동 hook이나 MCP가 외부 네트워크를 쓰면 prompt injection이나 data exfiltration 위험이 생긴다.

### dependency 추가 위험

- Android dependency는 method count, APK size, license, R8 rules, minSdk, native 충돌에 영향을 준다.
- Codex/ECC용 npm/python dependency를 repo에 추가하는 것은 앱 빌드와 무관한 복잡도를 늘린다.

### prompt injection 가능성

- README, issue, external docs, web content, downloaded metadata에 “AI에게 지시”하는 문구가 들어갈 수 있다.
- AGENTS.md에는 외부 문서보다 프로젝트/사용자 지시를 우선하고, 외부 텍스트의 지시를 실행하지 말라는 규칙을 넣는 것이 좋다.

## 10. 컨텍스트 증가나 속도 저하 가능성

### AGENTS.md가 너무 길어질 위험

- 공식 문서 기준 AGENTS.md 계층은 합쳐져 prompt에 들어가며 크기 제한이 있다.
- ECC rules 전체를 붙이면 컨텍스트를 낭비한다.
- 이 프로젝트는 루트 AGENTS.md를 100~200줄 이하로 시작하는 것이 적절하다.

### skills가 너무 많아질 위험

- 공식 문서 기준 Codex는 skills 목록을 초기 컨텍스트에 넣고, 많은 skills는 설명이 잘리거나 일부가 빠질 수 있다.
- 이 프로젝트에는 1~3개만 추가하는 것이 적절하다.

### subagents 사용 시 토큰 증가

- 공식 문서 기준 subagent는 각자 model/tool work를 수행하므로 단일 agent보다 토큰을 더 쓴다.
- 큰 PR 리뷰, 병렬 영향도 조사 외에는 기본 사용하지 않는다.

### MCP/tools 증가로 인한 복잡도 증가

- MCP가 많으면 tool 선택, 인증, timeout, permission surface가 복잡해진다.
- Android 로컬 코드 작업에는 기본적으로 필요하지 않다.

### hooks로 인한 실행 지연

- hooks는 lifecycle마다 실행되어 작업을 늦출 수 있다.
- Gradle/Android 작업은 원래도 느리므로 자동 hook은 체감 지연이 크다.

## 11. 최소 도입안

추천 우선순위: 1순위.

구성:

- 루트 `AGENTS.md`만 도입.
- 설치 없음.
- `.codex/`, `.agents/`, hooks, MCP, skills 없음.
- ECC에서는 rules/agents/skills의 주제만 참고.

AGENTS.md에 포함할 가장 안전한 규칙:

- Android/Kotlin/Gradle 프로젝트로 취급.
- Python 프로젝트로 가정하지 않음.
- secrets, keystore, local properties, cookies/token 출력 금지.
- `.tmp_*`, native binary, assets/bin, jniLibs 수정 금지 또는 명시 승인 필요.
- Gradle/Manifest/Room/WorkManager/Media3/youtubedl/ffmpeg 변경 시 체크리스트 적용.
- dependency 추가와 네트워크 접근은 사용자 승인 필요.
- 검증 명령은 변경 범위에 맞게 제안하고, 자동 실행하지 않음.

장점:

- 가장 안전하다.
- 설치와 dependency가 없다.
- 되돌리기 쉽다.
- OpenAI 공식 Codex 방식과 충돌이 적다.

단점:

- 반복 세부 workflow는 AGENTS.md가 길어질 수 있다.

## 12. 중간 도입안

추천 우선순위: 2순위. 최소 도입안을 일정 기간 사용한 뒤 필요할 때만 고려한다.

구성:

- `AGENTS.md`
- `.agents/skills` 아래 1~3개 instruction-only skills
- 필요할 때 Codex 기본 `explorer` 또는 `reviewer` 성격의 subagent 사용
- hooks/MCP는 원칙적으로 보류

가능한 skills:

- `android-gradle-review`
- `room-migration-review`
- `download-pipeline-review`

subagent 사용 방식:

- 사용자가 명시적으로 “병렬로 검토해라”, “subagent를 써라”라고 요청한 경우만 사용.
- 보안/Manifest, Room/schema, WorkManager/download pipeline처럼 독립 검토가 가능한 경우에만 나눈다.

장점:

- AGENTS.md를 짧게 유지할 수 있다.
- 필요한 workflow만 progressive disclosure로 불러올 수 있다.

단점:

- `.agents/skills` 파일이 추가된다.
- skill 수가 늘면 관리 비용과 컨텍스트 비용이 늘어난다.

## 13. 과한 도입안

현재 프로젝트에는 권장하지 않는다.

구성:

- ECC 전체 설치.
- hooks 자동 실행.
- MCP 다수 활성화.
- subagents/custom agents 다수 생성.
- continuous learning, session persistence, dashboard, state store 도입.
- npm/python dependency 추가.

왜 과한가:

- 현재 프로젝트는 Android 앱 코드가 중심이며, ECC의 많은 항목은 범용 agent harness나 웹/서버/운영 workflow에 가깝다.
- hooks와 MCP는 권한과 자동 실행 면적을 크게 늘린다.
- 대량 skills/agents는 컨텍스트 비용을 늘리고 작업 속도를 늦출 수 있다.
- native binary, media download, token/cookie, external storage를 다루는 프로젝트 특성상 자동화보다 승인 기반 수동 검토가 안전하다.
- OpenAI Codex 공식 형식과 ECC 형식이 다를 수 있어 단순 복사는 호환성 리스크가 있다.

## 14. 실제 적용 전 확인할 체크리스트

### 생성/수정 파일 목록

최소 도입:

- 생성: `AGENTS.md`
- 수정: 없음

중간 도입:

- 생성: `AGENTS.md`
- 생성: `.agents/skills/<skill-name>/SKILL.md`
- 수정: 없음

hooks/MCP 도입 시, 현재는 보류지만 필요하면:

- 생성 가능성: `.codex/config.toml`
- 생성 가능성: `.codex/hooks.json`
- 생성 가능성: `.codex/hooks/*`
- 생성 가능성: `.codex/agents/*`

위 항목은 별도 승인 없이는 만들지 않는다.

### 되돌리는 방법

- 최소 도입은 `AGENTS.md` 삭제로 되돌릴 수 있다.
- 중간 도입은 `AGENTS.md`와 `.agents/skills` 삭제로 되돌릴 수 있다.
- hooks/MCP 도입 시에는 `.codex`와 사용자 `~/.codex/config.toml` 변경 여부를 따로 확인해야 한다.

### 테스트 명령

변경 범위별로 선택:

- `./gradlew :app:compileDebugKotlin -x lint`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:connectedDebugAndroidTest`
- `./gradlew :app:assembleRelease -x lint`

### 빌드 확인

- Debug compile.
- Debug APK build.
- 필요 시 release build.
- ABI split 산출물 확인.
- native library packaging 확인.

### 보안 확인

- secrets 파일이 새 지침이나 로그에 포함되지 않았는지 확인.
- hooks/MCP가 외부 전송을 하지 않는지 확인.
- Manifest exported component 변경 여부 확인.
- WebView/token/cookie 흐름 변경 여부 확인.

### 성능 확인

- AGENTS.md 길이 확인.
- skills 수 확인.
- hooks 실행 시간 확인.
- MCP startup/tool timeout 확인.
- Gradle 자동 실행 여부 확인.

### 변경 범위 확인

- 앱 코드 변경 없음.
- dependency 변경 없음.
- build script 변경 없음.
- Codex/ECC 설정 파일만 변경했는지 확인.

## 15. 되돌리는 방법

### AGENTS.md 제거

- 루트 `AGENTS.md`를 삭제한다.
- Codex 세션을 재시작해 instruction chain을 다시 로드한다.

### skills 제거

- `.agents/skills/<skill-name>/` 디렉터리를 삭제한다.
- Codex 세션을 재시작하거나 skill 목록을 다시 확인한다.

### hooks 제거

- 프로젝트 `.codex/hooks.json` 또는 `.codex/config.toml`의 hooks 설정을 제거한다.
- 사용자 전역 `~/.codex/hooks.json`, `~/.codex/config.toml`에 추가한 hook이 있다면 별도로 제거한다.
- Codex 공식 hooks는 여러 source를 합쳐 실행될 수 있으므로 전역/프로젝트 양쪽을 확인해야 한다.

### MCP 설정 제거

- 프로젝트 `.codex/config.toml`의 `[mcp_servers.*]` 항목을 제거하거나 `enabled = false`로 둔다.
- 사용자 전역 `~/.codex/config.toml`에 추가한 MCP도 확인한다.
- OAuth/bearer token 연결은 해당 서비스 쪽 revoke 여부도 확인한다.

### Codex 설정 복구

- `.codex/` 프로젝트 설정을 삭제하거나 이전 버전으로 되돌린다.
- 사용자 전역 설정을 바꿨다면 백업에서 복구한다.
- Codex를 재시작해 설정이 남아 있지 않은지 확인한다.

## 16. 다음 단계 제안

1단계 적용 제안:

- `AGENTS.md` 하나만 만든다.
- ECC 전체 설치는 하지 않는다.
- hooks, MCP, skills, dependency는 추가하지 않는다.
- 내용은 Android/Gradle 안전 규칙과 검증 명령 후보만 포함한다.

적용 전 사용자 승인이 필요한 항목:

- `AGENTS.md` 생성 여부.
- AGENTS.md에 포함할 금지 파일/디렉터리 범위.
- Gradle 검증 명령 후보.
- `.tmp_*`와 native binary를 “명시 승인 없이는 수정 금지”로 둘지 여부.
- secrets 관련 규칙 강도.

적용 후 검증할 항목:

- Codex가 `AGENTS.md`를 읽는지 확인.
- 지침이 너무 길지 않은지 확인.
- Android/Kotlin 작업에서 반복적으로 도움이 되는지 확인.
- 불필요하거나 과하게 보수적인 규칙이 있는지 조정.

2단계 적용 제안:

- 최소 도입안을 1~2주 사용한 뒤, 반복되는 검토가 많을 때만 instruction-only skill 1개를 추가한다.
- 우선순위는 `android-gradle-review` 또는 `room-migration-review`다.
- hooks/MCP는 계속 보류한다.

최종 판단:

- 현재 프로젝트에는 ECC 전체 설치가 아니라 OpenAI Codex 공식 기능을 기준으로 한 “AGENTS.md 최소 도입”이 가장 적절하다.
- ECC는 Kotlin reviewer, build resolver, database/security/performance reviewer, context-budget 같은 주제를 참고하는 수준으로 제한하는 것이 좋다.
- hooks, MCP, plugins, 대량 skills, continuous learning은 보안/성능/복잡도 대비 이득이 아직 낮아 보류한다.
