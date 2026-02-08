# YTDLnisX

YTDLnisX는 YTDLnis를 기반으로 한 비공식 포크(Unofficial fork) 프로젝트입니다.
본 프로젝트는 원본 YTDLnis와 공식적으로 무관하며, 별도로 유지·관리됩니다.

- 원본 프로젝트: https://github.com/deniscerri/ytdlnis
- 원본 프로젝트 제작자: Denis Çerri (deniscerri)
- 포크 소스 코드: https://github.com/ireum-0/ytdlnisx
- 라이선스: GNU GPL v3.0

## 앱 특징 (원본과 차이점)

- 로컬 영상 폴더 관리: 기기 내 폴더를 추가해 영상 목록을 앱에서 통합 관리
- 다운로드 이력 기반 라이브러리: 히스토리에서 영상/오디오를 검색, 정렬, 필터링하며 빠르게 찾기
- 재생 상태 보존: 시청 중인 영상의 재생 위치를 저장하고 이어보기 지원
- 파일 직접 제어: 히스토리 항목에서 파일 열기, 공유, 삭제(기기 파일 동시 삭제 옵션 포함)
- 누락 파일 정리/복구: 삭제된 파일 항목 정리(`Remove deleted`) 및 누락 항목 재다운로드 흐름 지원
- 재다운로드 워크플로우: 실패/취소 항목을 다시 내려받아 로컬 보관 상태를 복구
- 로컬 분류 관리: 재생목록(Playlist) 생성/이름 변경/삭제 및 항목 추가·제거 지원
- 저장 경로 커스터마이징: 오디오/비디오 폴더 분리, 파일명 템플릿 설정으로 로컬 정리 규칙화
- 프라이버시 모드: Incognito 모드로 다운로드 시 히스토리/로그 기록 최소화

## 라이선스

이 프로젝트(YTDLnisX)는 GNU GPL v3.0 라이선스를 따릅니다.
자세한 내용은 [라이선스 파일](LICENSE)을 확인하세요.

본 프로젝트는 YTDLnis 기반의 비공식 포크이며, 원본 프로젝트에 대한 저작권 및 라이선스 고지를 유지합니다.
또한 원본 프로젝트의 정책에 따라 "YTDLnis"라는 이름을 사용하지 않으며, 본 프로젝트는 **"YTDLnisX"**라는 별도 이름으로 배포됩니다.

## 빌드

디버그 빌드:

```bash
./gradlew :app:assembleDebug
```

릴리스 빌드:

```bash
./gradlew :app:assembleRelease
```

릴리스 빌드를 위해서는 keystore.properties 설정이 필요합니다.

## 패키지명

현재 애플리케이션 ID:

- com.ireum.ytdlnisx

## 고지

본 프로젝트는 YTDLnis 기반 비공식 포크이며, 원본 프로젝트와는 별개의 프로젝트입니다.
원본 프로젝트에 대한 저작권 및 라이선스 고지를 존중하고 유지합니다.

YTDLnis에 대한 공식 정보 및 배포는 아래 원본 프로젝트를 참고하세요:
https://github.com/deniscerri/ytdlnis

## 배포

YTDLnisX의 바이너리(APK)는 GitHub Releases를 통해 배포됩니다:

https://github.com/ireum-0/ytdlnisx/releases

## 감사의 말

이 프로젝트는 원본 YTDLnis 프로젝트를 기반으로 합니다.
모든 주요 아이디어와 기반 구현에 대한 공은 원본 프로젝트의 제작자 및 기여자들에게 있습니다.

- Denis Çerri 및 YTDLnis 기여자들
- yt-dlp 및 관련 오픈소스 프로젝트 기여자들
