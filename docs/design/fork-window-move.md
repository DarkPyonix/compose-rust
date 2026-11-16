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
  windows-shared/          Windows의 공유 C 층: win32_window.c + Kotlin 래퍼. GraalVM과 K/N이 함께 링크함 (소유자 결정 2026-10-04)
  graalvm/                 GraalVM native-image 경로의 OS 층
    macos/                 appkit_window.m + Kotlin 래퍼(@CFunction 선언, 이벤트 당기기)
    linux/                 x11_window.c + Kotlin 래퍼
  parity/                  두 경로에 같은 점검표와 측정을 돌리는 시험
```

- **`common/`**: 창의 상태 기계와 정책을 한곳에 둡니다. 최소 크기, 이벤트 정규화(키, 포인터, 휠), 프레임 요청 합치기, IME 조합 상태, 다크 모드와 DPI 변화, 클립보드 텍스트, 컨텍스트 메뉴 항목, 접근성 트리 캐시. 플랫폼 타입을 모릅니다. 두 경로가 이 모듈 하나를 컴파일해 씁니다(`expect`/`actual` 또는 작은 인터페이스 `WindowPlatform`). 렌더러의 `renderer/desktop/src/renderer/`와 같은 방식으로 한 벌의 소스를 두 빌드가 공유합니다.
- **Windows 예외 (소유자 결정, 2026-10-04: "C 한 벌 유지").** #119의 Windows K/N 렌더러는 `win32_window.c`를 GraalVM 경로와 같이 링크하므로 Windows의 창은 C 구현 하나입니다. `native/windows/`는 만들지 않습니다. 두 경로가 함께 쓰는 층이라 `graalvm/windows/`가 아니라 `windows-shared/`라는 이름을 씁니다(GraalVM 전용으로 읽히지 않게). 일치 테스트는 구현이 하나라 Windows에는 따로 필요하지 않습니다.
- **폐기한 대안: K/N이 자기 Kotlin 창(`native/windows/`)을 갖는 것.** 이유는 둘입니다. 첫째, Kotlin/Native의 Windows 대상은 MinGW이고 애플리케이션은 MSVC 실행 파일이라 창 코드까지 MinGW 객체로 넘기면 링크 위험이 커집니다. 둘째, 구현이 둘이 되면 Windows에도 일치 테스트와 그 유지 부담이 생깁니다.
- **`native/<os>/`**: `common`의 `WindowPlatform`을 구현하는 얇은 OS 층입니다. 창 만들기, 이벤트 펌프, 표면, 입력기 연결만 하고 정책은 `common`에 맡깁니다.
- **`graalvm/<os>/`**: 같은 `WindowPlatform`을 구현하되, 아래는 C/Obj-C 파일이고 위는 Kotlin 래퍼(`@CFunction`, 생성된 upcall 표)입니다. 그리기 콜백이 upcall이어야 하는 곳(Win32 `WM_SIZE`, X11 `dxc_request_frame`)은 E0(upcall 비용 측정)의 결과로 정합니다. 2026-10-04 측정에서 upcall은 약 9 ns, 핸드셰이크는 2.75 us였습니다.
- **`parity/`**: 같은 점검표(최소 크기, 라이브 리사이즈 프레임 수, 다크 모드, 클립보드, IME 조합, 접근성 질의)와 측정(이벤트 지연, 프레임 시간, RSS)을 두 경로에 돌립니다. CI에서 도는 것은 창을 띄울 수 있는 러너에서만 돌고, 나머지는 사람 확인 목록으로 남깁니다(IME, VoiceOver, 리사이즈 감).

빌드 파일은 포크의 기존 `extended/design-systems/<시스템>/`과 같은 방식(Amper 또는 Gradle 모듈, 좌표 `org.thisisthepy.compose.window.*`)으로 둡니다. 포크 밖의 upstream 파일은 모듈 등록(settings 한 줄 수준)만 건드립니다.

## 2. 이 저장소에 남는 것

- `renderer/`의 인터프리터와 `HostConnection`은 포크가 내는 `window` 모듈을 의존성으로 받습니다. `patches/`와 `scripts/build-compose.sh`는 포크가 같은 결과를 내는 것이 확인될 때까지 남깁니다(D19의 규칙). 옮기는 동안 렌더러가 빌드되지 않는 기간을 두지 않습니다.
- 이동 한 단계마다 이 저장소의 해당 복사본은 포크 산출물을 쓰도록 바꾸고, 그 PR이 녹색일 때 복사본을 지웁니다.

## 3. 순서와 날짜

**가정.** 에이전트가 병렬로 일합니다(조각마다 에이전트 하나, 각자 자기 worktree). 로컬 빌드는 동시에 두 개까지만 돌리고, 기본 빌드 경로는 CI입니다. #119는 녹색이라 Windows K/N은 바로 시작할 수 있습니다.

| 단계 | 기간 | 내용 | 끝났다고 할 조건 |
|---|---|---|---|
| 0. 합의 | 2026-10-05 ~ 10-08 | 이 계획과 D21 리뷰. 포크 이슈와 브랜치 생성 | PR 승인 |
| 1. 공통 모듈 | 2026-10-09 ~ 10-13 | `extended/window/common/`: `WindowPlatform`, 상태 기계, 정책, 이벤트 정규화. 셋 모두가 의존하므로 먼저. 이 기간에 E0도 끝냄 | 단위 시험 녹색, `WindowPlatform`이 GraalVM의 당기기와 큐를 담을 자리를 가짐 |
| 2. K/N OS 층 셋(병렬) | 2026-10-14 ~ 10-24 | `native/macos`(#146의 빠진 것 포함), `native/linux`(X11, XIM), macOS와 Linux 둘(Windows는 `native/windows`가 없음) | 각 점검표 통과, 실기기 확인 |
| 3. GraalVM 층 셋 | 2026-10-25 ~ 10-31 | `graalvm/macos`, `windows-shared`(K/N도 링크하는 공유 C 층), `graalvm/linux`를 `WindowPlatform` 뒤로 옮김. 그리기 콜백은 E0 결과로 정함 | `parity/`가 두 경로에서 같은 결과, upcall은 생성된 표를 통하고 런타임 리플렉션 없음 |
| 4. 정리 | 2026-11-01 이후 | 이 저장소의 복사본과 `patches/`의 창 관련 부분 제거, D19 현황 갱신 | 렌더러 빌드 녹색 |

`parity/`는 단계 2에서 점검표 항목이 생길 때마다 함께 자라고, 단계 3에서 두 경로가 모두 있을 때 완성됩니다.

## 4. 일정을 늘일 수 있는 위험

- **실기기 확인.** IME, VoiceOver, 리사이즈 감은 사람이 실기기에서 봐야 하므로 에이전트가 끝낸 날과 "끝났다"가 같지 않습니다. 단계 2의 끝(10-24)에서 확인이 밀리면 단계 3이 같이 밀립니다.
- **Windows 빌드 메모리.** Windows K/N과 skiko mingw 빌드는 메모리를 많이 쓰고, 로컬 빌더가 둘까지라 다른 빌드와 겹치면 기다립니다. CI에 의존하는 만큼 CI 러너의 메모리와 시간 제한이 일정을 정합니다.
- **공통 모듈이 한쪽 모양에 치우침.** 병렬로 세 층을 쓰다가 `WindowPlatform`이 모자라면 단계 1로 돌아가 셋이 같이 다시 맞춥니다. 단계 1에서 GraalVM의 당기기와 큐를 미리 고려해 줄입니다.
- **빌더 두 개 제한.** 세 층을 병렬로 쓰는 것은 코드이고, 로컬 검증은 둘씩 돌아가며 합니다.
- upstream(`jb-main`) 병합 충돌은 모듈 등록 한 줄 수준이라 작게 보지만 측정한 적은 없습니다.

