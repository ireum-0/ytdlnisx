# YTDLnisX 1.8.8.7

## 주요 변경 사항

- 히스토리 화면에서 동영상 재생 후 돌아올 때 화면 상태 복원을 개선했습니다.
  - 정렬, 검색어, 필터, 그룹/선택 모드, 스크롤 앵커를 함께 저장해 복귀 시 같은 위치로 더 안정적으로 돌아오도록 했습니다.
  - 기존 위치 기반 복원에 더해 항목 ID/그룹/키워드 기반 앵커를 사용해 목록 갱신 후에도 복원 실패 가능성을 줄였습니다.
  - 같은 히스토리 화면이 이미 열려 있을 때 새로 navigate하지 않고 현재 화면에 복원 정보를 직접 전달하도록 변경했습니다.

- 히스토리 목록의 자동 최상단 스크롤과 복원 동작 충돌을 줄였습니다.
  - 복원 중에는 자동 top scroll, page update scroll, pending scroll이 복원 위치를 덮어쓰지 않도록 조정했습니다.
  - RecyclerView 레이아웃 높이를 제약 조건에 맞춰 고정해 스크롤 복원과 화면 배치 안정성을 높였습니다.

- 필터/그룹 선택 표시를 정리했습니다.
  - 유튜버, 키워드, 플레이리스트 선택 라벨이 개별 필터와 그룹 필터 상태를 일관되게 반영하도록 공통 갱신 흐름을 추가했습니다.

## 검증

- `./gradlew :app:compileDebugKotlin -x lint`
- `./gradlew :app:assembleDebug`

## 릴리스 산출물

- 디버그 서명 APK
  - `YTDLnisX-1.8.8.7-universal-debug.apk`
  - `YTDLnisX-1.8.8.7-arm64-v8a-debug.apk`
  - `YTDLnisX-1.8.8.7-armeabi-v7a-debug.apk`
  - `YTDLnisX-1.8.8.7-x86-debug.apk`
  - `YTDLnisX-1.8.8.7-x86_64-debug.apk`

## 참고

- 이번 릴리스는 디버그 서명 APK로 배포합니다.
- Gradle 빌드 중 Groovy DSL deprecation warning이 출력되지만 빌드는 성공했습니다.
