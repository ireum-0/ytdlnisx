# YTDLnisX 1.8.8.4

## 주요 변경 사항

- 히스토리 화면 안정성을 개선했습니다.
  - 히스토리 기타 필터에 오디오/비디오 타입 칩을 추가했습니다.
  - 타입 필터의 기본 상태를 정리해 오디오와 비디오를 함께 보거나 각각 따로 볼 수 있게 했습니다.
  - Paging 기반 히스토리 목록에서 수동 전체 갱신 호출을 줄여 `RecyclerView Inconsistency detected` crash가 재발할 가능성을 낮췄습니다.
  - 히스토리 empty state, 스크롤 복원, 로컬 비디오 추가 진행 표시를 더 안정적으로 갱신하도록 정리했습니다.

- 재생과 파일 열기 동작을 보강했습니다.
  - 오디오 히스토리 항목도 재생 진입 대상으로 처리되도록 개선했습니다.
  - raw file path가 직접 열리지 않는 경우 SAF document URI를 우선 시도해, 저장소 권한과 실제 파일 경로가 어긋나는 환경에서 재생 실패를 줄였습니다.
  - 오래된 streaming URL 판별 조건을 바로잡아 만료된 URL만 비우도록 했습니다.
  - streaming URL 조회 실패 시 빈 URL placeholder를 넘기지 않도록 정리했습니다.

- 다운로드와 WorkManager 안정성을 개선했습니다.
  - foreground service 진입 실패 시 장시간 다운로드 작업을 계속 진행하지 않고 retry하도록 변경했습니다.
  - detached coroutine으로 실행되던 다운로드/터미널 작업 일부를 worker lifecycle 안에서 기다리도록 정리했습니다.
  - 진행 로그와 cache 이동 작업이 worker 종료 뒤 유실되거나 실패가 무시되지 않도록 보강했습니다.
  - alarm cancel PendingIntent 타입을 schedule 타입과 맞췄습니다.

- 보안 및 개인정보 노출을 줄였습니다.
  - 내부 전용 activity/receiver의 exported 설정을 명시적으로 닫았습니다.
  - 공유 intent에서 외부 `TYPE`/`BACKGROUND` extra를 신뢰하지 않고 앱 내부 판단과 alias metadata만 사용하도록 했습니다.
  - yt-dlp config/ffmpeg-location/process-spawning 계열 옵션 주입을 차단했습니다.
  - data fetch URL에 공백, newline, 비 HTTP(S) 값이 들어가는 것을 거부하도록 했습니다.
  - po-token, 요청 URL, 실패 알림, crash/실패 logcat의 민감 정보 노출을 줄였습니다.

- 파일/저장소 처리 안전성을 높였습니다.
  - FileProvider 공개 범위를 `Download/YTDLnisx/`, app external files, cache로 줄였습니다.
  - SAF tree root URI 전체 삭제를 거부하되, tree 아래 실제 document URI 삭제는 유지했습니다.
  - cache cleanup은 앱 소유 cache 경로일 때만 재귀 삭제하도록 제한했습니다.
  - MediaStore/SAF 기반 파일 이동, 저장 공간 확인, document URI 생성 경로를 보강했습니다.

- 기타 수정
  - malformed Room SQL 조건을 명확한 boolean 조건으로 수정했습니다.
  - 같은 batch 안의 중복 다운로드 감지를 보강했습니다.
  - release signing 설정을 debug signing과 분리했습니다.
  - 존재하지 않는 Gradle module include를 제거했습니다.

## 검증

- `./gradlew :app:compileDebugKotlin -x lint`
- `./gradlew :app:assembleRelease -x lint`

## 참고

- 이번 릴리즈는 `v1.8.8.2` 이후의 안정성, 보안, 히스토리/재생/저장소 처리 개선을 포함합니다.
- FileProvider 범위가 좁아졌기 때문에 기본 `Download/YTDLnisx` 밖의 custom raw path 공유/open 동작은 기기별 확인이 필요할 수 있습니다.
