# 데스크톱 창의 공통 로직을 한 벌로 모으기

상태: 진행 중. 소유자 결정(2026-10-04): 데스크톱 창 코드는 구현 둘 + 공통 로직 + 일치 테스트입니다. 경로마다 OS를 부르는 얇은 층만 따로 두고, 나머지는 Kotlin 소스 한 벌(D3)입니다.

두 경로는 이렇게 부릅니다.

- GraalVM 경로: `renderer/desktop/c/appkit_window.m`, `win32_window.c`, `x11_window.c`와 Kotlin 래퍼(`AppKitWindow.kt`, `Win32Window.kt`, `X11Window.kt`).
- K/N 경로: `renderer/macos`, `renderer/linux`의 Kotlin cinterop 창(`MacosWindow.kt`, `LinuxWindow.kt`). Windows는 `feature/windows-kotlin-native`에 있습니다.

## 공유 방식

`renderer/desktop/src/`의 파일을 `renderer/linux/src/shared/`와 `renderer/macos/src/shared/`가 심볼릭 링크로 가리키는 기존 방식을 따릅니다. 이유는 셋입니다.

1. 이미 60개가 넘는 파일이 이 방식으로 공유되어 있어, 새 방식을 들이면 두 가지 규칙이 생깁니다.
2. desktop은 JVM 모듈이고 linux, macos는 각자 Kotlin/Native 라이브러리 모듈입니다. 공유 모듈을 따로 만들면 세 모듈의 의존과 컴파일러 옵션(opt-in, 컴파일 대상)을 새로 맞춰야 하고, 빌드를 로컬에서 돌릴 수 없는 이번 작업에서는 확인할 수 없습니다. 모듈 분리는 이 방식 위에서 나중에 할 수 있고, 지금은 링크가 이동 비용이 가장 작습니다.
3. 공유 파일은 JVM 이름(`System.getenv`, `System.nanoTime`)을 쓰는데, K/N 쪽은 `JvmCompatShims.kt`가 `java.lang`에 같은 이름을 채웁니다. 공유 파일은 그 이름만 쓰고 플랫폼 타입을 이름에 올리지 않습니다.

## 규칙: 콜백 안에서 동기로 도는 결정은 C 헤더에 둔다

창이 OS 콜백 안에서 곧바로 내려야 하는 결정(크기 조절 중 그릴지 말지, 드로어블 크기, 낡은 프레임 판정)은 헤더만 있는 순수 C(`renderer/desktop/c/win32_resize.h`, `appkit_resize.h`)로 둡니다. GraalVM은 C에서 곧바로 부르고, K/N 창은 같은 헤더를 cinterop으로 부릅니다. 한 구현이고, 모달 크기 조절 루프 안에서 upcall이 없습니다. 공유 Kotlin으로 옮기는 것은 OS 콜백 밖에서 쓰는 로직(키 표, IME 상태 모델, 접근성 트리, HostTextField 규칙)만입니다. 같은 입력을 두 경로의 C 헤더에 넣는 테스트를 둡니다.

## 중복 표

"공유 가능"은 Kotlin 한 벌로 합칠 수 있는가입니다. 줄 번호는 이 브랜치 기준입니다.

| 항목 | GraalVM 경로 | K/N 경로 | 공유 가능 | 처리 |
|---|---|---|---|---|
| 키 번호 표 (X11) | `x11_window.c:214` `dxc_key_code` (C 표, 글자와 숫자 포함) | `linux/src/LinuxInput.kt:79` `platformKey` (글자와 숫자 없음) | 예 | `X11Keys.kt` 한 벌. 두 경로가 키심을 그대로 넘기고 Kotlin이 번호로 바꿉니다. K/N 쪽에 글자가 빠져 ctrl+c/v/x/z/a가 Unknown으로 가던 틈이 닫힙니다 |
| 키 번호 표 (Win32) | `WindowEvents.kt:122` `win32ComposeKey` (Kotlin, 공유 파일) | `feature/windows-kotlin-native` | 이미 공유 | 변경 없음 |
| 키 번호 표 (AppKit) | `AppKitKeys.kt:26` `composeKey`, 이 번호가 공통 번호의 기준 | `MacosWindow.kt:775`가 같은 `composeKey`를 부름 | 이미 공유 | 변경 없음 |
| 수정키 비트 | `x11_window.c:200` `dxc_modifiers` (C), `win32_window.c:1083` `dxc_held_modifiers` | `LinuxInput.kt:62` `modifiersOf` (Kotlin) | 예 (X11) | `X11Keys.kt`의 `x11Modifiers`. Win32는 값이 달라 `WindowEvents.kt`의 win32 분기가 이미 해석합니다 |
| 버튼 비트 | `x11_window.c:192` `dxc_buttons` | `LinuxInput.kt:44` `buttonsOf` | 예, 가치 낮음 | 표에 남기고 이번에는 옮기지 않았습니다 |
| IME 조합 상태 (X11) | `x11_window.c:317-375` 조합 버퍼와 콜백 (C, `wchar_t` 배열) | `InputMethod.kt:33` `PreeditBuffer`, `:75` `ImeSession` (Kotlin) | 예 | `ImeComposition.kt`로 옮기고 GraalVM C는 조합 변경(위치, 길이, 새 글자)만 넘깁니다 |
| 키 하나가 이벤트가 되는 규칙 | `x11_window.c:888-906` (키 먼저, 글자는 뒤에 commit, 단축키는 글자 없음) | `InputMethod.kt:144` `keyEventsFor` | 예 | 같은 파일로 옮기고 C는 키심, 상태, 글자만 넘깁니다 |
| IME 조합 상태 (Win32) | `win32_window.c:1140-1180` 전체 문자열을 읽어 compose/commit 이벤트를 만듭니다 | `feature/windows-kotlin-native` | 부분 | 조합 문자열이 통째로 오는 모델이라 조합 버퍼는 필요 없습니다. 이벤트 규칙은 `TextInputSession.kt`(공유)를 같이 씁니다 |
| IME 조합 상태 (AppKit) | `AppKitTextInput.kt` | `MacosWindow.kt:414,419` `insertText`, `setMarkedText` | 이미 공유 | `TextInputSession.kt`의 `compose`와 `commit`을 둘 다 씁니다 |
| 접근성 트리 만들기 | `AppKitAccessibility.kt:52` `describe()` (공유, 평평한 목록) | `AtspiSemantics.kt:59` `capture` (SemanticsNode를 AT-SPI 트리로) | 부분 | 트리 만들기는 #140이 `AtspiModel`, `AtspiSemantics`를 `desktop/src`로 옮기는 중이라 거기서 한 벌이 됩니다. 이 작업은 건드리지 않습니다 |
| 크기 조절과 프레임 결정 | `appkit_resize.h`, `win32_resize.h` (헤더 C) | `LinuxWindow.kt:356` `WindowFrames`, `ResizeSync.kt` (Kotlin, 공유) | C 헤더로 | 위 규칙대로 C 헤더가 한 벌입니다. K/N 창에 같은 헤더를 cinterop으로 붙여 같은 통계(`steps`, `stale`, `stretched`)를 냅니다 |
| 드로어블 크기, 낡은 프레임 | `appkit_window.m:1130` `dxc_resize_present`, `win32_window.c:2203` | `LinuxWindow.kt:356-362` | C 헤더로 | 위와 같습니다 |
| HostTextField 보내기 규칙 | `renderer/HostTextField.kt` | 같은 파일의 심볼릭 링크 | 이미 공유 | 변경 없음 |
| 배율과 DPI | `X11Window.kt:43`, `Win32Window.kt:92`, `AppKitWindow.kt:176` (C가 `scale`을 잼) | `LinuxWindow.kt:971` 상수 1.0, `MacosWindow.kt:652` `backingScaleFactor` | 아니오 | OS를 묻는 일이라 경로별입니다. 결과를 `WindowMeasurement`로 넘기는 모양은 이미 같습니다 |
| 클립보드 | `x11_window.c:458,497`, `appkit_window.m:228,249`, `win32_window.c:1690` | `MacosClipboard.kt`(#147에서 추가), Linux는 없음 | 부분 | 장면 쪽 `NativeClipboard.kt:28`은 이미 공유입니다. OS 호출은 경로별입니다. K/N Linux에는 클립보드 구현이 없다는 점이 일치 점검표에서 드러납니다 |
| 창 크롬 수치 | `WindowChrome.kt:143,210` (캡션 높이, 버튼 폭) | `MacosWindow.kt:300` `measureCaption`, #147의 `MacosWindowChrome.kt` | 부분 | 최소 크기 규칙(`contentMinimum`)은 #147이 macOS 쪽에 두었습니다. 같은 규칙이 `appkit_window.m:909`에도 있어 공유 후보입니다. #147이 끝난 뒤 맞춥니다 |
| 지연 추적과 합성 입력 | `LatencyTrace.kt`, `SyntheticInput.kt` (AppKit 창에서만 씀) | 없음 | 예 | 두 파일을 K/N 창과 GraalVM X11 창이 씁니다 (일치 점검의 3절) |

## 일치 점검표

두 경로에서 같은 점검과 같은 측정을 돌립니다. 각 줄은 `scripts/parity/` 한 스크립트가 같은 표 모양(`parity <항목> <경로> <값> <단위>`)으로 출력합니다.

- 입력 지연: `DXC_SYNTH=type`과 `DXC_REPORT_LATENCY`
- 크기 조절 프레임 시간: `DXC_SYNTH=resize`와 `DXC_REPORT_RESIZE`
- 단축키(ctrl 또는 cmd 더하기 c, v, x, z, a): 키 번호 표를 같은 벡터로 검사
- 클립보드, 다크 모드, 최소 크기, 아이콘

어느 점검이 어느 CI 작업에서 도는지와 첫 수치는 PR에 적습니다.
