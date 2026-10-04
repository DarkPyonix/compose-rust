# 창 코드를 포크로 옮기는 계획 (D19를 D21의 구조에 맞춰 다시 짬)

상태: 계획 (구현 전). 이슈 #26. 시작 2026-10-09.

D21이 데스크톱 창을 "두 벌 + 공통 로직 + 일치 테스트"로 정했으므로, D19의 이동 단위가 "창 코드 한 덩어리"에서 세 조각으로 바뀝니다. 포크의 규칙은 그대로입니다: 포크에만 있는 코드는 `extended/` 아래에 두고, upstream 파일은 가능한 한 건드리지 않으며, upstream(`jb-main`)은 병합으로만 따라갑니다. 공개 API는 바꾸지 않습니다.

## 1. 모듈 배치 (`thisisthepy/compose-multiplatform-core-extended`, `extended/` 아래)

```
extended/window/
  common/                  공통 로직 (Kotlin 소스 하나, D3)
  native/                  Kotlin/Native 경로의 OS 층 (Kotlin cinterop, 손으로 쓴 C 없음)
    macos/                 AppKit + Metal 표면 (renderer/macos/src의 MacosWindow.kt, MetalSurface.kt)
    linux/                 X11 + GL/Vulkan 표면, XIM (renderer/linux/src)
    windows/               Win32 + Direct3D/ANGLE 표면 (Win32Window.kt, #119)
  graalvm/                 GraalVM native-image 경로의 OS 층
    macos/                 appkit_window.m + Kotlin 래퍼(@CFunction 선언, 이벤트 당기기)
    windows/               win32_window.c + Kotlin 래퍼
    linux/                 x11_window.c + Kotlin 래퍼
  parity/                  두 경로에 같은 점검표와 측정을 돌리는 시험
```

- **`common/`**: 창의 상태 기계와 정책을 한곳에 둡니다. 최소 크기, 이벤트 정규화(키, 포인터, 휠), 프레임 요청 합치기, IME 조합 상태, 다크 모드와 DPI 변화, 클립보드 텍스트, 컨텍스트 메뉴 항목, 접근성 트리 캐시. 플랫폼 타입을 모릅니다. 두 경로가 이 모듈 하나를 컴파일해 씁니다(`expect`/`actual` 또는 작은 인터페이스 `WindowPlatform`). 렌더러의 `renderer/desktop/src/renderer/`와 같은 방식으로 한 벌의 소스를 두 빌드가 공유합니다.
- **`native/<os>/`**: `common`의 `WindowPlatform`을 구현하는 얇은 OS 층입니다. 창 만들기, 이벤트 펌프, 표면, 입력기 연결만 하고 정책은 `common`에 맡깁니다.
- **`graalvm/<os>/`**: 같은 `WindowPlatform`을 구현하되, 아래는 C/Obj-C 파일이고 위는 Kotlin 래퍼(`@CFunction`, 생성된 upcall 표)입니다. 그리기 콜백이 upcall이어야 하는 곳(Win32 `WM_SIZE`, X11 `dxc_request_frame`)은 E0(upcall 비용 측정)의 결과로 정합니다. 2026-10-04 측정에서 upcall은 약 9 ns, 핸드셰이크는 2.75 us였습니다.
- **`parity/`**: 같은 점검표(최소 크기, 라이브 리사이즈 프레임 수, 다크 모드, 클립보드, IME 조합, 접근성 질의)와 측정(이벤트 지연, 프레임 시간, RSS)을 두 경로에 돌립니다. CI에서 도는 것은 창을 띄울 수 있는 러너에서만 돌고, 나머지는 사람 확인 목록으로 남깁니다(IME, VoiceOver, 리사이즈 감).

빌드 파일은 포크의 기존 `extended/design-systems/<시스템>/`과 같은 방식(Amper 또는 Gradle 모듈, 좌표 `org.thisisthepy.compose.window.*`)으로 둡니다. 포크 밖의 upstream 파일은 모듈 등록(settings 한 줄 수준)만 건드립니다.

## 2. 이 저장소에 남는 것

- `renderer/`의 인터프리터와 `HostConnection`은 포크가 내는 `window` 모듈을 의존성으로 받습니다. `patches/`와 `scripts/build-compose.sh`는 포크가 같은 결과를 내는 것이 확인될 때까지 남깁니다(D19의 규칙). 옮기는 동안 렌더러가 빌드되지 않는 기간을 두지 않습니다.
- 이동 한 단계마다 이 저장소의 해당 복사본은 포크 산출물을 쓰도록 바꾸고, 그 PR이 녹색일 때 복사본을 지웁니다.

## 3. 순서와 날짜

날짜는 한 사람이 병렬 없이 이어서 한다고 가정한 현실적인 추정입니다. 앞 단계가 늦으면 뒤가 밀립니다.

| 단계 | 기간 | 내용 | 끝났다고 할 조건 |
|---|---|---|---|
| 0. 합의 | 2026-10-05 ~ 10-08 | 이 계획과 D21 리뷰. 포크 이슈와 브랜치 `feat/window-common` 생성 | PR 승인 |
| 1. 공통 모듈 | 2026-10-09 ~ 10-16 | `extended/window/common/`: `WindowPlatform`, 상태 기계, 정책, 이벤트 정규화. 두 경로가 모두 의존하므로 가장 먼저 | 단위 시험 녹색, 현재 macOS K/N 창이 이것 위에서 같은 동작 |
| 2. macOS K/N | 2026-10-17 ~ 10-27 | `native/macos/`. 이슈 #146의 빠진 것(최소 크기, Dock 아이콘, 다크 모드, 텍스트 클립보드)이 `common`에서 채워짐 | 점검표 통과, 실기기 확인 |
| 3. Linux K/N | 2026-10-28 ~ 11-10 | `native/linux/`: X11, XIM, 표면 | 점검표 통과, 두 아키텍처(x86-64, arm64) 확인 |
| 4. Windows K/N | 2026-11-11 ~ 11-24 | `native/windows/`. #119(링크와 예외 탐침)가 병합된 뒤에 시작. 그 전에 시작하면 링크 방식이 바뀔 때 두 번 합니다 | 점검표 통과, 실기기 확인 |
| 5. GraalVM 층 | 2026-11-25 ~ 12-19 | `graalvm/macos`, `graalvm/windows`, `graalvm/linux`: 기존 C/Obj-C와 Kotlin 래퍼를 `WindowPlatform` 뒤로 옮김. 그리기 콜백은 E0 결과로 정함(E0은 단계 1 동안 끝냄) | `parity/`가 두 경로에서 같은 결과, upcall은 생성된 표를 통하고 런타임 리플렉션 없음 |
| 6. 정리 | 2026-12-20 ~ 12-23 | 이 저장소의 복사본과 `patches/`의 창 관련 부분 제거, D19의 현황 갱신 | 렌더러 빌드 녹색 |

`parity/`는 단계 2부터 자라고(점검표 항목이 생길 때마다 추가), 단계 5에서 두 경로가 모두 있을 때 완성됩니다.

## 4. 위험

- 공통 모듈이 한쪽 모양에 치우칠 수 있습니다. 그래서 macOS K/N(기준 구현)을 먼저 올리고, GraalVM 쪽이 필요로 하는 호출(당기기, 큐)이 `WindowPlatform`에 들어갈 자리를 1단계에서 미리 비워 둡니다.
- Windows는 #119의 링크 결과에 달려 있습니다. 막히면 4단계와 5단계의 Windows 부분이 함께 밀립니다.
- 포크가 upstream과 만나는 곳은 모듈 등록뿐이라 `jb-main` 병합 충돌은 작을 것으로 보지만, 측정한 적은 없습니다.
