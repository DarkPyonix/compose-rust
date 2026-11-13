# 데스크톱 창의 공통 로직을 한 벌로 모으기

상태: Linux와 macOS 부분은 들어갔다(#155의 나머지, Compose 포크 PR #127과 그 뒤 PR). Windows 부분은 포크의 Windows 창 모듈(포크 #32)이 맡는다.

소유자 결정(2026-10-04): 데스크톱 창 코드는 구현 둘(GraalVM 경로, Kotlin/Native 경로)에 공통 로직과 일치 테스트를 더한 것이다. 경로마다 OS를 부르는 얇은 층만 따로 두고 나머지는 한 벌이다. 이후 결정(INTENT D19)으로 창, 입력, IME, 메뉴, 크롬, 크기 조절, 접근성 코드는 모두 포크 `thisisthepy/compose-multiplatform-core-extended`의 `extended/window/`에 있다. 이 저장소에는 장면을 창에 붙이는 연결 코드만 남는다.

## 어디에 무엇이 있나

| 모듈 | 내용 |
|---|---|
| `extended/window/common` | 플랫폼을 부르지 않는 순수 Kotlin. `WindowPlatform`, 이벤트와 상태, X11 키 표(`X11Keys.kt`), IME 모델(`ImeComposition.kt`), 크기 조절 통계(`ResizeStats.kt`), AT-SPI 프로토콜(`atspi/`: D-Bus 와이어, 객체 모델, 서버, 버스 브리지) |
| `extended/window/native/linux` | Kotlin/Native X11 창. Xlib, GLX, XIM, posix 소켓 D-Bus 연결, Compose 시맨틱을 AT-SPI 트리로 읽는 `AtspiSemantics.kt` |
| `extended/window/graalvm/graalvm-linux` | GraalVM X11 창. `x11_window.c`와 `@CFunction` 래퍼, Java 소켓 채널 D-Bus 연결(`JvmBusConnection`), `X11Accessibility`. `AtspiSemantics.kt`는 native/linux 파일과 바이트까지 같은 사본이다. Windows 체크아웃은 심볼릭 링크를 경로가 적힌 파일로 바꾸는데 이 모듈은 Windows에서도 빌드되므로 링크를 쓰지 않고, 두 파일이 다르면 graalvm-linux 워크플로가 실패한다 |
| `extended/window/native/macos` | Kotlin/Native AppKit 창. 장면을 직접 가진다 |
| `renderer/desktop/src/X11Window.kt` (이 저장소) | GraalVM X11 창에 장면을 붙이는 연결 코드 |
| `renderer/linux/src/LinuxWindow.kt` (이 저장소) | Kotlin/Native X11 창에 장면을 붙이는 연결 코드 |
| `renderer/macos/src/MacosParityRun.kt` (이 저장소) | Kotlin/Native macOS 창의 일치 측정 실행 |

## 규칙: 콜백 안에서 동기로 도는 결정은 C 헤더에 둔다

AppKit과 Win32 창은 OS 콜백 안에서 곧바로 내려야 하는 결정(크기 조절 중 그릴지, 드로어블 크기, 낡은 프레임 판정)을 헤더만 있는 C(`appkit_resize.h`, `win32_resize.h`)로 둔다. 모달 크기 조절 루프 안에서 upcall이 없게 하려는 것이다. 두 Linux 창과 Kotlin/Native macOS 창은 프레임을 Kotlin에서 내보내므로 같은 규칙을 Kotlin으로 옮긴 `ResizeStats`로 센다. 두 구현은 `ResizeStatsTest`가 헤더 테스트와 같은 입력으로 맞춘다.

## 한 벌로 모은 것

| 항목 | 이전 | 지금 |
|---|---|---|
| X11 키 번호와 수정키 비트 | GraalVM은 C 표(`dxc_key_code`, `dxc_modifiers`), Kotlin/Native는 글자가 빠진 Kotlin 표 | common `X11Keys.kt` 하나. 두 창이 키심과 상태 값을 그대로 넘긴다 |
| X11 IME 조합 상태와 키 규칙 | GraalVM은 C의 `wchar_t` 버퍼와 C 규칙 | common `ImeComposition.kt`와 `X11Events`. GraalVM C는 `PREEDIT_START/DRAW/DONE` 기록과 키심, 상태, 글자만 넘긴다. 클릭으로 조합을 끝낼 때는 `dxc_native_ime_reset`을 묻는다 |
| AT-SPI | Kotlin/Native 창에만 있었다 | 프로토콜은 common `atspi/`, 시맨틱 읽기는 `AtspiSemantics.kt` 한 벌(두 모듈에 같은 사본). 두 X11 창이 같은 코드로 응답하고 소켓만 다르다 |
| 크기 조절 통계 | AppKit GraalVM 창만 `dxc resize:` 줄을 냈다 | 두 Linux 창과 Kotlin/Native macOS 창이 `ResizeStats`로 같은 줄을 낸다. `DXC_REPORT_RESIZE`가 있을 때만 present마다 서버에 창 크기를 묻는다 |
| 스크립트 드래그 | GraalVM X11 창은 비어 있는 함수였다 | 세 창 모두 `scriptedResize`가 있다 |
| 지연 추적과 합성 입력 | AppKit GraalVM 창에서만 썼다 | `LatencyTrace.kt`, `SyntheticInput.kt`를 Linux와 macOS Kotlin/Native 렌더러가 심볼릭 링크로 같이 쓴다 |
| 프레임 대기 깨우기 | 다른 스레드에서 온 일은 16 ms 대기가 끝나야 돌았다 | `WindowPlatform.wake`. GraalVM 창은 eventfd, Kotlin/Native 창은 파이프. 장면 디스패처와 `Dispatchers.Main`이 다른 스레드에서 일을 받으면 깨운다 |

## 일치 점검표

`scripts/parity/linux.sh`와 `macos.sh`가 같은 앱(`consumer --parity`)에 같은 환경 변수(`DXC_SYNTH`, `DXC_REPORT_LATENCY`, `DXC_REPORT_RESIZE`)를 주고 같은 행의 표를 낸다. Kotlin/Native 경로는 `static-renderer.yml`, GraalVM 경로는 `test-graalvm-renderer.yml`에서 돈다. 표는 작업 요약에 붙고, 실패한 행은 경고다(`PARITY_STRICT=1`이면 실패). `scripts/tests/parity-linux.test.sh`가 스크립트, 창, 워크플로가 같은 이름을 쓰는지 본다.

한글 입력과 AT-SPI 읽기는 `.github/scripts/check-linux-input-access.sh`가 두 X11 창 모두에서 실제 ibus-hangul과 at-spi2 레지스트리로 확인한다.

## 동작이 바뀌는 곳

- GraalVM X11: 평범한 ASCII 키는 키 이벤트 하나가 글자를 달고 간다. 이전에는 C가 같은 글자를 commit으로도 보냈다. Kotlin/Native 규칙에 맞췄다.
- GraalVM X11: AT-SPI 버스에 붙는다. `NO_AT_BRIDGE=1`이면 붙지 않는다.

## 사람이 확인할 것

- 실제 Linux 데스크톱에서 GraalVM X11 창에 영어와 한글 입력.
- Orca로 GraalVM X11 창 읽기.
