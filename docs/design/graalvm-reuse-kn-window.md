# GraalVM 경로에서 Kotlin/Native 창 코드를 재사용하는 방법

상태: 초안 (조사만 했고 구현과 빌드는 하지 않았습니다). 이슈 #145. 기한 2026-10-07.

## 0. 결정과 질문

소유자 결정(2026-10-04, 원문): "통합하지 않거나 Kotlin/native쪽에 맞출 방법이 필요할거 같아. graalvm 외부에서 역으로 연결되는게 좀 별로인데 컴파일 된 다음에 연결을 하든지 다른 방법도 찾아봐."

- 창 코드를 C/Obj-C 한 벌로 합치는 안은 기각됐습니다.
- 기준 구현은 Kotlin/Native 창 코드입니다: `renderer/macos/src/MacosWindow.kt`, `MetalSurface.kt`, `renderer/linux/src/`, 그리고 `feature/windows-kotlin-native`의 `renderer/windows/src/Win32Window.kt`.
- 묻는 것: GraalVM native-image 경로(`renderer/desktop`, `renderer/desktop/c/appkit_window.m`, `x11_window.c`, `win32_window.c`를 `@CFunction`과 upcall로 호출)가 그 코드를 재사용할 수 있는가. 바깥 C 코드가 GraalVM을 되부르는 것(upcall)은 피하고 싶다는 것이 소유자의 기호입니다.

표기: 확인하지 못한 주장은 "미확인"으로 적었습니다. 이 문서의 어떤 수치도 이번에 측정한 것이 아닙니다(빌드 슬롯이 모두 차 있어 빌드를 하지 않았습니다). 측정이 필요한 곳은 11절에 실험 제안으로 모았습니다.

## 1. 먼저 알아낸 사실 (저장소 코드에서 확인)

1. **macOS의 GraalVM 창 경로는 이미 upcall이 없습니다.** `appkit_window.m`은 이벤트를 C 쪽 큐에 쌓고 GraalVM이 `dxc_native_poll_event`로 당겨 갑니다(363행 부근). 접근성 트리는 GraalVM이 `dxc_native_set_accessibility`로 밀어 넣습니다(168행). 그리기 콜백 `dxc_native_set_draw_callback`과 `dxc_native_set_frame_callback`은 macOS에서 아무 일도 하지 않는 빈 함수입니다(312, 357행). 즉 "되부르기"가 실제로 남아 있는 곳은 Win32(`Win32DrawCallback.java`, 창 가장자리를 끄는 동안 Windows가 자체 루프를 돌기 때문)와 X11(`X11FrameCallback.kt`)입니다. 소유자의 불편은 사실상 Windows와 Linux에 걸려 있습니다.
2. **`MacosWindow.kt`는 "창만 있는 모듈"이 아닙니다.** 이 파일은 `NSWindow`/`NSView`/`NSTextInputClient` 서브클래스와 Compose 장면(`ComposeScene`, `PlatformContext`, `paintFrame(canvas, ...)`)이 한 클래스 안에서 섞여 있습니다(118, 168, 217행 등). GraalVM 경로가 재사용하려면 창 층과 장면 층을 먼저 나눠야 합니다. 이 분리가 모든 선택지의 공통 선행 작업입니다.
3. **이미 공유되는 것**: `renderer/macos/src/shared/`는 `renderer/desktop/src/`의 심볼릭 링크로, 키 변환(`AppKitKeys.kt`), 접근성 모델(`AppKitAccessibility.kt`), 인터프리터가 한 벌입니다. 창 서브클래스 부분만 두 벌(Kotlin/Native의 `MacosWindow.kt`와 C의 `appkit_window.m`)입니다. SPEC 5.5는 창 코드의 최종 자리를 Compose 포크(INTENT D19)로 적고 있어, 어느 안이든 이 방향과 충돌하지 않는지 확인이 필요합니다(미확인: D19 본문을 이번에 다시 읽지 않았습니다).
4. 두 경로의 Skia: GraalVM 경로는 JVM 용 Compose와 JNI 형태의 skiko를 `StaticSkikoLoader.java`로 정적 링크하고, K/N 경로는 Compose의 K/N 산출물과 skiko의 네이티브 부분을 씁니다. 한 실행 파일에 둘을 같이 넣으면 Skia가 두 번 들어갑니다.

## 2. 선택지 A: 컴파일 후 연결 (K/N 창 모듈을 C ABI 라이브러리로, GraalVM은 아래로만 호출)

### 2.1 구성

- 창 층만 담은 K/N 모듈(Compose에 의존하지 않음)을 `-produce static`(권장) 또는 `dynamic`으로 만들고, native-image가 `--native-compiler-options`/링커 옵션으로 링크합니다. 링크 방식 자체는 이미 `renderer_entry.c`와 `c/*.m`을 native-image에 넘기는 방식(`renderer/desktop/scripts/linux-build-evidence.md` 73행 부근)과 같습니다.
- 이벤트는 K/N 안의 큐에 쌓이고 GraalVM이 매 프레임 `poll_event`로 당깁니다(1절의 현재 방식과 같음). 접근성 트리와 IME 상태는 GraalVM이 밀어 넣고 K/N이 캐시합니다. upcall은 없습니다.
- 사실상 `appkit_window.m`을 Kotlin(ObjC 연동)으로 옮기는 일입니다. 얻는 것은 "K/N 렌더러와 같은 Kotlin 창 코드 한 벌"이고, 값은 프로토콜(`dxc_*`)이 같은 채로 유지됩니다.

### 2.2 두 런타임이 한 프로세스에 공존하는가

| 항목 | 판단 |
|---|---|
| GC와 메모리 모델 | 각 런타임이 자기 힙과 자기 GC를 가집니다. 경계를 넘는 것은 포인터와 길이뿐이라(PR-2) 서로의 객체를 참조하지 않습니다. K/N은 외부 스레드가 처음 진입할 때 스레드 상태를 스스로 초기화합니다(`MacosEntryPoints.kt` 머리말에 같은 서술). GraalVM 쪽 스레드가 K/N을 호출하는 동안 두 GC가 서로를 멈추게 하는 교착은 이론상 없으나 확인한 적이 없습니다: 미확인 |
| 시그널 핸들러 | SubstrateVM과 K/N 모두 자기 핸들러를 설치할 수 있습니다(스택 오버플로, 널 접근, 크래시 보고). 나중에 설치한 쪽이 앞선 것을 덮습니다. 어느 쪽이 무엇을 설치하는지 이번에 확인하지 못했습니다: 미확인. 11절 실험 E1에서 `sigaction` 조회로 확인합니다 |
| 표준 라이브러리 두 벌 | 가능합니다. K/N의 런타임 심볼은 `Kotlin_*` 계열이고 SVM 것은 `svm_*`/`com_oracle_svm_*` 계열이라 이름 충돌은 드물 것으로 보이나, 정적 링크 시 전역 심볼 중복은 링커가 판정하므로 확인 필요: 미확인(E1) |
| 심볼 충돌 | `-produce static`이 생성하는 C 어댑터 헤더가 공개 심볼만 내보내도록 모듈을 진입점만으로 만든 선례가 있습니다(`staticlib-macos/module.yaml` 머리말). 같은 규칙으로 창 모듈의 공개면을 `dxc_*` 이름으로 고정하면 충돌 면이 줄어듭니다 |
| 선례 | Windows 브랜치에 "서로 다른 C 런타임이 한 실행 파일에 섞이는" 소비자 시험(`fixtures/consumer-mixed-runtime`)이 있습니다. 런타임 두 개의 공존이 이미 한 번 시험된 경험이 있다는 뜻이지, K/N과 GraalVM의 공존을 시험한 것은 아닙니다 |

### 2.3 AppKit 메인 스레드와 런루프의 소유

- AppKit의 `NSApplication`과 모든 창 조작은 프로세스 메인 스레드여야 합니다. 현재 macOS는 C shim(`macos_main_thread.m`)이 메인 스레드를 쥐고 렌더러는 보조 스레드에서 돕니다(`MacosEntryPoints.kt` 머리말의 서술; 미확인: 현재 develop에서도 그대로인지).
- A에서 K/N 창 모듈은 "메인 스레드에서 호출되는 `pump`"와 "아무 스레드에서 호출 가능한 `poll_event`/`push_*`"로 나눕니다. 메인 스레드의 소유자는 기존과 같이 shim(또는 Rust 호스트)이고, K/N은 빌려 쓸 뿐입니다. 이 소유 규칙은 기존 `dxc_native_pump`과 동일해서 바뀌는 것이 없습니다.

### 2.4 라이브 리사이즈 중의 동기 그리기: 당기기만으로 충분한가

**충분하지 않습니다(근거와 가정 포함).** macOS에서 창 가장자리를 끄는 동안 AppKit은 `sendEvent` 안에서 추적 루프를 돌고, 그 동안 `pump`는 반환하지 않습니다. 반환하지 않는 `pump` 위에서 GraalVM이 당길 기회가 없으므로 드래그 중에는 새 크기로 그릴 수 없습니다(Windows가 `Win32DrawCallback`을 쓰는 이유와 같은 구조). 이 동작 자체는 일반적인 AppKit 지식이며 이 저장소에서 측정한 적은 없습니다: 미확인(E3에서 확인).

해법 후보:
1. **블로킹 핸드셰이크(upcall 없음)**: GraalVM에 "그리기 스레드"를 따로 두고, 그 스레드가 K/N의 `dxc_wait_frame_request()`에서 아래로 대기합니다. K/N의 `setFrameSize`가 신호를 주고 GraalVM이 그린 뒤 `dxc_frame_done()`을 호출할 때까지 메인 스레드가 기다립니다. 모든 호출이 GraalVM에서 아래로 향하므로 소유자의 조건을 지킵니다. 대가: 메인 스레드가 GraalVM의 그리기 시간만큼 막힙니다(프레임 예산 안이면 문제 없음). 장면이 보조 스레드가 아니라 메인 스레드에서 돌아야 한다는 제약(AGENTS의 PR-1 VirtualDom은 렌더러 UI 스레드)과 충돌하는지는 확인이 필요합니다: 미확인.
2. 리사이즈 중에는 늘려 그리기(이전 프레임을 레이어 gravity로 늘림)만 하고 끝난 뒤 한 번 그림. 구현은 쉬우나 NFR-9의 체감을 해칩니다. SPEC FR-19 수용 기준과 충돌할 수 있습니다: 미확인.
3. 그리기 콜백(upcall) 유지. 소유자가 기피합니다.

### 2.5 AppKit이 동기로 답을 요구하는 호출

- `NSTextInputClient`(`markedRange`, `attributedSubstringForProposedRange`, `firstRectForCharacterRange` 등)와 `NSAccessibility`(`accessibilityChildren` 등)는 AppKit이 호출 스택 안에서 바로 값을 기다립니다. 이 호출 안에서 GraalVM으로 가면 upcall이고, 거기서 실행이 막히면 교착 위험이 있습니다.
- 그래서 **K/N 쪽 캐시(IME 문자열과 선택 범위, 캐럿 사각형, 접근성 요소 배열)를 GraalVM이 프레임마다 밀어 넣는** 현재 C 구현의 모델을 그대로 씁니다(`appkit_window.m`의 `dxc_accessibility_children`, 마킹 텍스트 보관).
- 낡음의 한계: 캐시는 마지막으로 밀어 넣은 프레임의 것이므로 최대 한 프레임(60Hz에서 약 16 ms)입니다. 한글처럼 글자를 조합하는 입력기는 `setMarkedText` 직후 `markedRange`를 되묻습니다. 이 값은 K/N이 `setMarkedText`를 받은 즉시 자기 캐시에서 갱신해야 하며(`appkit_window.m` 488행 부근의 주석이 같은 문제를 설명), GraalVM이 나중에 확인하는 것으로는 부족합니다. 캐럿 사각형은 한 프레임 낡아도 후보 창 위치가 한 프레임 늦는 정도입니다: 미확인(수동 확인, 7절).

### 2.6 성능

- 이벤트 경로: 지금도 뮤텍스 하나를 잡는 큐라서 A는 기존 대비 바뀌는 것이 거의 없습니다(큐 연산은 마이크로초 미만으로 추정: 미확인). 핸드셰이크 안은 세마포어 깨움 한 번(수십 마이크로초 추정: 미확인)이 리사이즈 프레임마다 더해집니다.
- VoiceOver 질의: K/N 캐시에서 답하므로 질의당 GraalVM 호출이 없습니다. 처리량은 캐시 구조 선택에 달렸고 현재 C 구현과 같은 수준이 될 것으로 보입니다: 미확인(E4).

### 2.7 바이너리 크기와 이중으로 존재하는 것

- 창만 담은 K/N 모듈(Compose 없음)은 K/N 런타임과 stdlib의 사용된 부분과 ObjC 연동 코드뿐입니다. 크기는 측정하지 않았습니다: 미확인(E2). Compose까지 담은 `MacosWindow.kt` 전체를 쓰면 Compose와 Skia가 GraalVM 쪽과 두 벌이 되어 NFR-3/NFR-15의 무게 기준에 불리합니다. 따라서 A는 "창 층만" 분리하는 안으로만 의미가 있습니다.
- 두 벌이 되는 것: K/N 런타임 및 stdlib 일부(필연), 접근성 모델과 키 변환(공유 소스이므로 코드는 한 벌이나 산출물은 두 번 컴파일됨). Skia는 창 층만이면 한 벌(GraalVM 쪽)로 남습니다. 단 K/N 창 층이 Metal 레이어를 만들어 GraalVM에 텍스처 포인터를 넘기는 현재 방식(`dxc_native_frame_begin`)을 유지해야 합니다.

### 2.8 평가

- 가능성: 중간. 기술적으로 막히는 곳은 보이지 않으나 2.2의 공존(시그널, 심볼)과 2.4의 핸드셰이크가 시험 전까지 가정입니다.
- 위험: 시그널 핸들러 충돌(크래시 보고가 침묵으로 바뀜), 두 GC 사이의 스레드 상태, 라이브 리사이즈 중 메인 스레드 블로킹, K/N 빌드 시간과 번들 크기 증가.
- 노력: 창 층/장면 층 분리(공통 선행) 약 1주, macOS 한 플랫폼 기준 모듈화와 연결 약 1~2주, Linux와 Windows 각각 1주 이상(추정, 미확인).
- 실제 이득: 코드 중복이 두 벌에서 한 벌로 줄어드는 대신 빌드와 런타임이 복잡해집니다. 이득은 macOS의 `appkit_window.m`(841줄), Linux의 `x11_window.c`(620줄), Windows의 `win32_window.c`(1892줄)를 대체하는 것입니다.

## 3. 선택지 B: 그리기도 K/N에서 (GraalVM은 UI 기술만 보냄)

### 3.1 구성

창과 Compose 장면 전체를 K/N 라이브러리(현재의 배포 렌더러, `staticlib-macos` 등)에 맡기고 GraalVM(pythonx)은 compose-rust 프로토콜 레코드(배치 버퍼)만 보냅니다. 렌더러는 이미 이 모양으로 배포됩니다(SPEC 5.5: macOS와 Linux는 K/N 정적 라이브러리가 배포 렌더러).

### 3.2 기술적으로 가능한가

가능합니다. 다만 호스트 쪽 경계가 문제입니다. 경계는 동기 직접 호출이고 렌더러가 호스트 함수(`compose_rust_host_*`)를 부릅니다(PR-1, PR-2). 호스트가 Rust이면 이 호출은 Rust 함수 호출입니다. 호스트가 GraalVM 안의 Python이면 K/N이 GraalVM의 `@CEntryPoint`를 부르는 것, 곧 **바로 소유자가 기피하는 되부르기**입니다. 소유자의 기준을 지키려면 "이벤트는 K/N 큐에 쌓고 GraalVM이 당겨 가는" 형태의 호스트 어댑터를 K/N 쪽에 하나 더 만들어야 하며, 이는 경계 표면(PR-2)의 변경이라 SPEC 변경이 필요합니다.

### 3.3 pythonx-compose의 요구와의 적합성

읽은 이슈:
- python-multiplatform #191 <https://github.com/thisisthepy/python-multiplatform/issues/191>: 사용자 선택지로 (1) 확장 포크의 GraalVM native-image 데스크톱 앱, (2) K/N 데스크톱 앱을 추가. 요구는 "upcall은 빌드 시점 표, 런타임 리플렉션 없음", 같은 pythonx-compose 앱이 세 경로에서 바뀌지 않고 돌 것. K/N 쪽은 "인터프리터의 데스크톱 K/N 타깃이 아직 없음"이라고 적혀 있습니다.
- pythonx-compose #110 <https://github.com/thisisthepy/pythonx-compose/issues/110>: `pythonx.*`가 JVM 전용 동작(리플렉션, AWT)에 기대지 않을 것, 세 경로 모두에서 노트북 앱이 그대로 돌 것.
- pythonx-compose #54 <https://github.com/thisisthepy/pythonx-compose/issues/54>: `pythonx.compose.material3`를 포크의 material3로 매핑, 공개 표면 비교 회귀 가드.

이 이슈들에서 읽히는 것: pythonx의 바인더는 **Compose의 Kotlin 공개 API(Modifier, mutableStateOf, material3 컴포저블)를 파이썬에서 직접 호출하는 바인딩**을 만듭니다(#54 끝줄이 `androidx.compose.ui.Modifier`, `mutableStateOf`를 하드코딩된 이름으로 언급). B에서는 Compose가 K/N 쪽에 있고 GraalVM에는 없으므로 이 바인딩이 닿을 대상이 사라집니다. 대신 compose-rust의 프로토콜 레코드를 만드는 별도의 파이썬 계층이 필요하고, 이는 #191이 말하는 "같은 앱이 바뀌지 않고 돈다"와 어긋납니다. 이슈 본문만으로는 pythonx가 어떤 파이썬 구현 위에서 도는지(GraalPy 여부)와 upcall 표가 정확히 어떤 경계를 건너는지까지는 읽히지 않습니다: 미확인. B는 사실상 #191의 두 번째 경로(K/N 데스크톱)에 해당하며, 그 경우 GraalVM에는 인터프리터와 호스트 어댑터만 남습니다.

### 3.4 GraalVM 쪽에 남는 것

파이썬 인터프리터 실행, upcall 표(파이썬이 부르는 바인딩), 프로토콜 레코드 작성, 이벤트 당기기. 창, Compose, Skia, IME, 접근성은 모두 K/N으로 갑니다. 즉 GraalVM 경로의 창 코드(`c/*.m`, `*.c`, `AppKitWindow.kt` 등)는 삭제됩니다.

### 3.5 평가

- 가능성: 렌더러 쪽은 높음(이미 배포됨), pythonx 적합성은 낮음(3.3).
- 성능: 이벤트 경로와 VoiceOver는 K/N 단독과 같습니다(접근성 질의가 경계를 안 넘음). 프레임당 경계 호출은 배치 한 번이 전부이므로 GraalVM 경계 비용은 작습니다: 미확인(측정 없음).
- 위험: pythonx의 Kotlin API 바인딩 모델과 정면 충돌, GraalVM이 Compose를 갖는다는 전제를 쓰는 이슈 두 개를 다시 열어야 함, 경계 표면 변경(PR-2).
- 노력: 렌더러는 없음, 호스트 어댑터와 pythonx 쪽 재설계가 큼(수 주 이상, 추정). 결정권은 pythonx 쪽 소유자에게 있습니다.
- 권고: GraalVM 경로를 유지하는 이유가 "pythonx가 Compose Kotlin API를 직접 부른다"이면 B는 그 이유를 지웁니다. 별도의 경로(#191의 2번)로 두고, GraalVM 경로의 대체안으로는 권하지 않습니다.

## 4. 선택지 C: 통합하지 않고 표류를 시험으로 막기 (기준선)

### 4.1 구성

현재처럼 두 구현을 유지하되 다음을 CI에 둡니다.
1. **공유 체크리스트**: 창 동작 목록(키 변환, 마우스 버튼/스크롤, 드래그 앤 드롭, 리사이즈, 가장자리 드래그, 캡션 영역, 커서 모양, 클립보드, 메뉴, IME 아홉 항목, 접근성 라벨)을 한 파일에 두고 두 구현이 같은 항목 번호로 통과/미통과를 보고합니다. 사람 확인 항목(IME, VoiceOver, 리사이즈 감각)은 표에 "수동"으로 표시합니다.
2. **자동 시험**: 이미 공유되는 소스(`shared/` 링크)의 단위 시험은 두 경로가 같이 씁니다. 창 서브클래스는 공통 프로토콜 `dxc_*`(구조체 `dxc_event`)에 대한 이벤트 벡터를 만들어, 두 구현이 같은 합성 입력에서 같은 이벤트 열을 내는지 비교하는 시험(체크인된 프로토콜 벡터와 같은 방식, 시험 규칙 6)을 둡니다. 키 변환은 이미 공유라 대상에서 줄어듭니다.
3. **지연과 리사이즈 측정을 두 경로에서 CI로**: 이벤트 입력부터 첫 프레임 제시까지, 라이브 리사이즈 중 제시된 프레임 수와 늘려 그린 프레임 비율, 접근성 트리 갱신 시간. 측정 도구는 `renderer/desktop/scripts/measure-memory.sh` 계열 옆에 둔다고 보되, 입력을 합성하는 방법(CGEvent 등)은 CI 러너의 권한에 달려 있습니다: 미확인.

### 4.2 평가

- 가능성: 높음. 선례(프로토콜 벡터, `windows-build-contract.test.sh`)가 있습니다.
- 성능 영향: 없음.
- 위험: 표류를 "잡을" 뿐 "없애지" 못하므로 한 구현의 버그를 다른 쪽에 고치는 비용이 계속 듭니다. 측정이 러너 하드웨어에 민감합니다.
- 노력: 체크리스트와 이벤트 벡터 시험 약 1주, 측정 하네스 약 1주(추정).
- 위치: 다른 안의 기준선이자, 어느 안을 택해도 최종 검증 장치로 남습니다.

## 5. 선택지 D: 그 밖의 방법

### D1. Panama(FFM)로 K/N 동적 라이브러리를 부르기

- JEP 454가 JDK 22에서 FFM API를 확정했고, GraalVM native-image는 FFM의 downcall과 upcall을 지원하되 reachability 메타데이터(`foreign` 설정)에 사용할 시그니처를 미리 적어야 합니다. 정확한 지원 버전과 제약은 문서에서 다시 확인해야 합니다: 미확인. 참고: <https://openjdk.org/jeps/454>, <https://www.graalvm.org/latest/reference-manual/native-image/>.
- downcall만 쓰면 A와 같은 방향의 호출이고 `@CFunction`보다 나은 점이 없습니다(링크 시점이 아니라 실행 시점에 `dlopen`을 하게 되어 오히려 NFR-15의 "실행 파일 하나"와 어긋남).
- upcall은 FFM에서도 여전히 upcall입니다. 소유자의 기피 대상을 피하지 못합니다.
- 평가: 가능은 하나 이득이 없습니다. 비권장.

### D2. 소유를 뒤집기: K/N이 main, GraalVM은 `--shared` 라이브러리

- GraalVM native-image를 공유 라이브러리로 만들고(`@CEntryPoint`가 공개면), K/N이 프로세스와 AppKit 메인 스레드를 소유해 프레임마다 GraalVM의 진입점을 부릅니다. K/N이 창과 메인 스레드를 갖는다는 점에서 소유자의 "K/N쪽에 맞춘다"에 가장 가깝습니다.
- 그러나 K/N이 GraalVM을 부르는 것은 "바깥이 GraalVM으로 들어오는" 호출이므로 소유자의 기피와 같은 계열입니다. 기피하는 것이 "콜백 함수 포인터를 등록하는 형태"인지 "바깥에서 GraalVM으로 들어오는 모든 호출"인지는 소유자에게 확인이 필요합니다. 이 질문의 답이 D2의 가부를 정합니다.
- 장점: 라이브 리사이즈 중 K/N이 메인 스레드에서 직접 GraalVM의 그리기 진입점을 부르면 핸드셰이크 없이 동기 그리기가 됩니다.
- 한 실행 파일 요건(NFR-15): `--shared`의 산출물을 K/N 실행 파일에 정적으로 합칠 수 있는지는 미확인(GraalVM은 공유 라이브러리와 정적 라이브러리 산출을 둘 다 지원한다고 알고 있으나 현재 버전의 조건은 확인하지 못했습니다: 미확인). 이 저장소의 호스트는 Rust인데, 그러면 Rust, K/N, GraalVM 세 런타임이 됩니다.

### D3. 소스 공유를 늘리기 (창 서브클래스는 두고 로직을 올린다)

- 창 서브클래스(ObjC/X11/Win32 바인딩) 부분만 플랫폼 코드로 남기고 결정 로직(리사이즈 규칙 `ResizeSync.kt`, `WindowResize.kt`, 이벤트 로그, 텍스트 입력 세션 `TextInputSession.kt`)을 Kotlin 공통 소스로 올려 두 경로가 같은 소스를 컴파일하게 합니다. 이미 `shared/` 링크로 일부가 이렇게 되어 있고, 남은 것을 같은 방식으로 더 옮기는 일입니다. 런타임 공존이 필요 없습니다.
- 한계: 창 서브클래스 자체는 계속 두 벌입니다(C와 Kotlin). 소유자가 기각한 "C 한 벌 통합"과 방향이 다르고(코드를 합치지 않고 로직만 공유) 기준선 C와 잘 맞습니다.

### D4. JVM 쪽에서 objc 런타임을 Panama로 직접 부르기(Rococoa 식)

- `NSView` 서브클래싱에 `objc_allocateClassPair`와 메서드 구현 포인터(IMP)가 필요하고, IMP는 FFM upcall입니다. 소유자의 기피와 정면으로 겹치므로 기각을 권합니다.

## 6. 요약 표

| 안 | 가능성 | upcall | 바이너리 영향 | 노력 | 권고 |
|---|---|---|---|---|---|
| A 창 층만 K/N 정적 라이브러리 | 중간 (공존 시험 전) | 없음 (핸드셰이크 스레드 필요) | K/N 런타임 추가, 크기 미확인 | 큼 | 단계적 실험 후 결정 |
| B 그리기도 K/N | 렌더러 높음, pythonx 낮음 | 호스트 어댑터에 달림 | GraalVM에서 창과 Skia 제거 | pythonx 재설계 | GraalVM 대체안으로 비권장, 별도 경로로 유지 |
| C 두 벌 유지 + 패리티 시험 | 높음 | macOS 없음, Win/X11 있음 | 변화 없음 | 작음 | 즉시 시작, 기준선 |
| D1 FFM | 가능 | upcall 그대로 | 동적 로드 | 중간 | 비권장 |
| D2 K/N이 main | 미확인 | 바깥에서 GraalVM 진입 | 미확인 | 큼 | 소유자 질문 필요 |
| D3 로직 소스 공유 확대 | 높음 | 영향 없음 | 변화 없음 | 작음 | C와 함께 |
| D4 JVM에서 objc 직접 | 가능 | upcall 필수 | - | 큼 | 비권장 |

## 7. 사람이 확인해야 하는 것(실기기)

IME 아홉 항목(특히 한글 조합과 후보 창 위치의 한 프레임 낡음), VoiceOver의 Text/Button 라벨 읽기와 트리 갱신 지연, 라이브 리사이즈의 체감(핸드셰이크 안이 프레임을 놓치는지), 세 플랫폼의 가장자리 드래그. 이는 SPEC 5.5와 5.6의 수동 항목과 같고 자동화할 수 없습니다.

## 8. 권고

1. **C를 지금 시작**(체크리스트, 이벤트 벡터 시험, 지연과 리사이즈 측정). 어느 안을 택해도 필요합니다.
2. **D3로 로직을 더 공유**. 창 서브클래스를 제외한 결정 로직이 두 벌인 것을 줄입니다.
3. **A는 macOS 한 플랫폼에서 실험 E1부터 E4로 가부를 정한 뒤** 확장합니다. 먼저 `MacosWindow.kt`를 창 층과 장면 층으로 나누는 것이 선행 작업이며, 그것은 K/N 경로 자체에도 이득입니다. 핸드셰이크 안(2.4의 1번)이 라이브 리사이즈의 유일한 upcall 없는 해법입니다.
4. **B는 GraalVM 경로의 대체안이 아니라 pythonx의 K/N 경로(#191의 2번)로 다룹니다.**
5. 소유자에게 물을 것: (a) 기피 대상이 콜백 등록 형태의 upcall인지, GraalVM으로 들어오는 모든 호출인지(D2의 가부), (b) 소유자가 말한 "컴파일 된 다음에 연결"이 A의 정적 링크를 뜻하는지, (c) Windows와 X11에서 남은 upcall(그리기 콜백)을 macOS처럼 당기기 방식으로 바꾸는 것(1절 1번)을 A와 별개로 먼저 해도 되는지. (c)는 A 없이도 소유자의 불편 상당 부분을 줄일 수 있는 후보이며, 라이브 리사이즈 때문에 2.4와 같은 핸드셰이크 스레드가 필요합니다.

## 9. 이 문서가 하지 않은 것

구현, 빌드, 측정. 이유: 빌드 슬롯이 모두 차 있어 빌드를 금지당했습니다. 따라서 모든 수치와 공존 가정은 미확인이며 아래 실험이 닫아야 합니다. Windows 브랜치(`feature/windows-kotlin-native`)의 창 코드는 파일 목록만 확인했고 줄 단위로 읽지 않았습니다: 미확인.

## 10. 근거 목록

- 저장소: `renderer/desktop/c/appkit_window.m`, `x11_window.c`, `win32_window.c`, `renderer_entry.c`, `macos_main_thread.m`; `renderer/desktop/src/AppKitWindow.kt`, `ResizeSync.kt`, `Win32DrawCallback.java`, `X11FrameCallback.kt`; `renderer/macos/src/MacosWindow.kt`, `MetalSurface.kt`; `renderer/staticlib-macos/`; `renderer/linux/`; `origin/feature/windows-kotlin-native`의 `renderer/windows/`; `renderer/desktop/scripts/linux-build-evidence.md`; `docs/SPEC.md` 5.5, 5.6.
- 외부: python-multiplatform #191, pythonx-compose #110, #54(위 링크). JEP 454 <https://openjdk.org/jeps/454>. GraalVM native-image 문서 <https://www.graalvm.org/latest/reference-manual/native-image/>. Kotlin/Native 동적/정적 라이브러리 <https://kotlinlang.org/docs/native-dynamic-libraries.html>(이번에 본문을 읽지 않았고 존재만 알고 있습니다: 미확인).

## 11. 실험 제안 (빌드 슬롯이 비면 실행. 지금은 실행하지 않음)

모두 `.scratch/kn-window-probe/` 안에서 하고 저장소 밖에는 쓰지 않습니다. 에이전트는 빌드하지 않으므로 병합하는 세션이 한 번에 하나씩, `CARGO_BUILD_JOBS=2`로 실행합니다.

**E1 공존: 두 런타임, 시그널, 심볼.** 파일:
- `.scratch/kn-window-probe/kn/module.yaml`과 `src/Probe.kt`: `@CName("probe_ping")` 함수 하나(정수를 받아 +1, 내부에서 `kotlin.collections` 몇 개를 할당)를 가진 K/N `macosArm64` 정적 라이브러리.
- `.scratch/kn-window-probe/graal/Probe.java`: `@CFunction`으로 `probe_ping`을 불러 결과를 찍고, 시작 전후에 `sigaction`으로 SIGSEGV, SIGBUS, SIGABRT의 핸들러 주소를 찍는 native-image 앱.
- 명령: K/N 쪽은 `renderer/kotlin`의 Amper 래퍼로 `-produce static` 산출(기존 `staticlib-macos` 모듈과 같은 설정), 그 뒤 `nm -g libprobe.a | grep -E "Kotlin_|svm_"`로 전역 심볼 이름을 대조, native-image 링크 시 `-H:NativeLinkerOption=/path/libprobe.a`.
- 합격 기준: 링크가 중복 심볼 없이 성공, 호출 100만 번 중 크래시 없음, 핸들러 주소 변화 기록.

**E2 크기.** E1의 K/N 모듈에 `NSWindow` 하나를 여는 코드를 더해 `-produce static` 산출물과 링크 후 실행 파일 증가량(`size`, `ls -l`)을 기록. Compose를 넣은 변형과 비교.

**E3 라이브 리사이즈.** `.scratch/kn-window-probe/`에 `NSView.setFrameSize`에서 타임스탬프를 남기는 K/N 창을 만들고, 별도 스레드가 `dxc_wait_frame_request`에서 대기하다 단색 프레임을 그려 제시하는 핸드셰이크 안과, `pump`이 반환하기를 기다리는 당기기 안을 각각 만듭니다. 자동화한 가장자리 드래그(`osascript`가 아니라 CGEvent 합성, 권한 필요)로 2초간 끌며 제시된 프레임 수와 `setFrameSize` 호출 수의 비를 비교. 합격 기준은 핸드셰이크 안이 호출 수와 같은 수의 프레임을 제시하는 것. 당기기 안이 드래그 동안 `pump`에서 반환하는지 여부도 기록.

**E4 VoiceOver 질의 처리량.** K/N 캐시에 접근성 요소 500개를 두고 `accessibilityChildren`을 반복 호출하는 시험으로, 현재 `appkit_window.m`과 같은 호출의 지연 분포(p50, p99)를 비교.

**E5 이벤트 경로 지연.** 입력 합성에서 `poll_event`가 값을 돌려줄 때까지의 시간을 두 구현에서 측정(1만 번, 분포 기록).
