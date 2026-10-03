# compose-rust

[![CI](https://github.com/DarkPyonix/compose-rust/actions/workflows/ci.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/ci.yml)
[![Native renderer](https://github.com/DarkPyonix/compose-rust/actions/workflows/native-renderer.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/native-renderer.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](../../LICENSE)
[![Rust 1.85+](https://img.shields.io/badge/rust-1.85%2B-orange.svg)](https://www.rust-lang.org)

[English](../../README.md) · **한국어**

**Rust로 선언형 UI를 작성하면, AOT 컴파일된 Compose Multiplatform 엔진이 그립니다.**

*웹뷰도 동봉된 JVM도 없는 Rust 네이티브 데스크톱 UI.*

```rust
rsx! {
    Column {
        fill_max_width: true,
        Text { text: "compose-rust chat" }
        Button { text: "Send", on_click: move |_| send() }
    }
}
```

`rsx!`와 훅, 시그널로 컴포넌트를 씁니다. `dioxus-core`의 VirtualDom이 이를 mutation으로 바꾸고,
좁은 C ABI가 그 mutation을 경계 너머로 넘기면, Kotlin/Compose 인터프리터가 실제 Compose 트리로
만들어 냅니다. Compose의 텍스트 레이아웃과 위젯, 그리고 플랫폼 IME를 그대로 쓰면서요.

---

## 📑 목차

- [왜 만드는가](#-왜-만드는가)
- [현재 상태](#-현재-상태)
- [API 맛보기](#-api-맛보기)
- [디자인 시스템](#-디자인-시스템)
- [아키텍처](#-아키텍처)
- [시작하기](#-시작하기)
- [성능](#-성능)
- [저장소 구조](#-저장소-구조)
- [기여하기](#-기여하기)
- [문서](#-문서)
- [라이선스](#-라이선스)

---

## 🎯 왜 만드는가

두 가지 문제가 만나는 지점입니다.

**웹 기반 데스크톱 앱은 무겁습니다.** 하루 종일 켜 두는 애플리케이션에서 문제는 반응성이 아니라
메모리 사용량과 배포 용량입니다. 브라우저를 품거나 JVM을 함께 배포하면, 내 코드가 한 줄 돌기도
전에 수십에서 수백 MB를 씁니다.

**Rust에는 Compose 수준의 텍스트를 갖춘 툴킷이 없습니다.** Rust GUI 생태계는 렌더링은 잘합니다.
그러나 텍스트 셰이핑, 선택, 접근성, 무엇보다 **IME**가 Compose가 몇 년 전에 도달한 수준에
미치지 못합니다. 타이핑이 곧 인터페이스인 앱에서 90%만 동작하는 IME는 동작하지 않는 앱입니다.
한글·일본어·중국어 조합은 있으면 좋은 기능이 아닙니다.

compose-rust는 Compose의 런타임 비용을 빼고 렌더러만 가져옵니다. Kotlin 쪽은 사전에 네이티브
라이브러리로 컴파일되므로(macOS와 Linux, iOS는 Kotlin/Native, Skia와 링크할 수 있는 Kotlin 타깃이
없는 플랫폼은 GraalVM 네이티브 이미지) 배포 산출물에 JVM이 없습니다.

### 무게, 대략의 비교

비교 대상의 수치는 [`docs/INTENT.md`](../INTENT.md)에서 가져온 **근사치**이고 자릿수 비교일 뿐
벤치마크가 아닙니다. 이 프로젝트가 얼마인지는 실측입니다.

| 방식 | 대략의 무게 | 비고 |
|---|---|---|
| 웹뷰 스택 (Electron, Tauri 계열) | 가장 무거움. 앱마다 혹은 시스템마다 브라우저 엔진 | **C1**으로 탈락: 메모리와 용량 |
| Compose + JVM 동봉 (jlink) | JVM만 약 80~120MB | **C2**로 탈락. AppCDS는 시작 시간 해법이지 용량 해법이 아님 |
| 순수 Rust 툴킷 (Iced 계열) | 약 10~20MB | **C5**로 탈락: 텍스트와 IME 성숙도 |
| **compose-rust** | macOS에서 28.76MB, 파일 하나 | Iced보다는 크고, 웹뷰나 JVM 스택보다는 훨씬 작음 |

### 실제로 얼마인가

notepad 샘플을 릴리스로 빌드해 스트립한 것입니다. 실행 파일 하나가 전부이고, 옆에 놓이는
런타임도 안에 든 가상 머신도 없으며, 링크된 것은 시스템 자신의 라이브러리뿐입니다.

| 플랫폼 | 실행 파일 | 물리 메모리 | 방식 |
|---|---|---|---|
| macOS (arm64) | **28.76MB** | **35.1MB** | Kotlin/Native, Metal로 그림 |
| Linux (x86-64) | **37.93MB** | 아직 재지 않음 | Kotlin/Native, GLX로 그림 |
| Windows | 아직 재지 않음 | 아직 재지 않음 | GraalVM 네이티브 이미지, Direct3D 12로 그림 |

비교하자면, 같은 샘플이 Kotlin/Native로 옮기기 전에는 파일 네 개에 93.1MB였고 물리 메모리는
56MB였습니다. 그 대부분이 네이티브 이미지가 지고 다니던 자바 런타임입니다.

`NFR-3`은 배포 100MB 미만, 빈 창 물리 메모리 56MB 미만을 요구합니다. macOS는 둘 다 여유 있게
넘겼고, 잰 것은 빈 창이 아니라 실제 응용 프로그램입니다. Linux와 Windows는 메모리를 아직 재지
않았고, 여기서 그 둘에 대해 주장하는 것은 없습니다.

### 협상 불가 조건

| ID | 조건 |
|---|---|
| **C1** | 웹뷰 금지: WKWebView, WebView2, WebKitGTK, Tauri, wry 모두 불가 |
| **C2** | JVM 동봉 금지: JVM은 개발 셸에서만 허용 |
| **C3** | 수동 JNI·cinterop 글루 금지. 경계 심은 생성물 |
| **C4** | UI는 **Rust에서** 선언형으로 작성. Kotlin은 렌더러 구현 세부 |
| **C5** | Compose 수준의 텍스트·IME·위젯 품질. 우회 경로를 만들지 않음 |

---

## 🚦 현재 상태

스펙을 먼저 쓰는 방식으로 **활발히 개발 중인 초기 프로젝트**입니다. crates.io에 게시하지
않았으며 API는 바뀝니다.

### 플랫폼

| 플랫폼 | 상태 | 내용 |
|---|---|---|
| 🍎 **macOS (arm64)** | **처음부터 끝까지 동작** | Rust 호스트 → C ABI → Kotlin/Native 렌더러 → 화면의 창까지. 자바 런타임이 들어 있지 않은 파일 하나입니다. 창과 Metal 레이어, 텍스트 입력은 이 렌더러가 직접 소유합니다. 한글 입력의 기본 경로는 동작하고, IME 체크리스트(`SPEC §6`) 전체는 아직 미완 |
| 🪟 Windows 데스크톱 | 빌드되고 실행됨 | 업스트림 GraalVM 25로 빌드되며, 렌더러가 바뀔 때마다 스모크 테스트를 통과합니다. |
| 🐧 Linux 데스크톱 | 빌드되고 실행됨 | 두 아키텍처(x64, arm64) 모두 CI의 Xvfb 환경에서 빌드 및 헤드리스 시작 스모크 테스트를 통과합니다. |
| 📱 iOS | 빌드되고 실행됨 | Kotlin/Native `-produce static`으로 동일한 C 심볼을 내보냅니다. XCFramework로 배포됩니다. |
| 🤖 Android | 빌드됨, 실행 미확인 | Kotlin Activity가 프로세스와 루프를 소유하고 Rust는 cdylib이며, 양쪽 JNI 심은 스키마에서 생성됩니다. 앱과 라이브러리 모두 `arm64-v8a`로 빌드되지만 아직 기기나 에뮬레이터에서 실행해 보지 않았고, 그것이 `PR-5`가 요구하는 부분입니다 (마일스톤 M6) |
| 🌐 Web (wasm) | **엔드 투 엔드 동작** | Kotlin/Wasm 모듈이 정의한 `WebAssembly.Memory` 하나를 Rust가 import하므로 배치를 쓴 자리에서 그대로 읽고 복사하지 않습니다. Renderer→Host 호출은 생성된 JS forwarder를 거치며 12ns로 실측했습니다. 브라우저에서 메모리 공유와 직접 바인딩을 동시에 가질 수 없기 때문이고, 반대 방향에는 JS가 없습니다. 데스크톱 데모와 같은 Rust 소스로 M0 화면이 브라우저에 뜨고, 클릭이 Rust 핸들러에 도달하며, 상태 변경이 페이지에 나타납니다. `web/scripts/test-boundary.sh`와 `screenshot.sh`가 브라우저에서 테스트하고 사진으로 남깁니다 (`PR-6`, 마일스톤 M7) |

### 양쪽의 진도 차이

Rust **Host**가 Kotlin **Renderer**보다 앞서 있습니다. 지금 마무리 중인 것이 Compose
인터프리터라, Host 쪽은 통과했지만 Renderer 쪽이 아직인 요구사항이 몇 개 있습니다.

| 기능 | Host (Rust) | Renderer (Kotlin) |
|---|---|---|
| 노드 트리 mutation: `FR-1` | ✅ | ✅ |
| 스키마 기반 렌더링: `FR-2` | ✅ | ✅ 33개 컴포저블 모두 구현 |
| 동기 이벤트 전달: `FR-3`, `FR-12` | ✅ | ✅ `on_click`, `on_change`, `on_dismiss`, 키 소비 연결 |
| 비제어 `TextField`, IME 소유권: `D5` | ✅ | ✅ |
| 스키마 코드젠 lockstep: `FR-7` | ✅ | ✅ 생성된 `Protocol.gen.kt` |
| `LazyColumn` 윈도잉: `FR-8` | ✅ | ✅ Renderer가 구간 요청 |
| 스트리밍 텍스트 `AppendText`: `FR-9` | ✅ | ✅ 프레임 단위로 병합 |
| Modifier: `FR-10` | ✅ | ✅ 13개 속성 전부 구현 |
| 디자인 프리미티브·디자인 시스템: `FR-13`, `FR-14` | ✅ | ✅ 일곱 가지 시스템, 역할 기반 명세 |
| 창 크기 클래스 (Window size classes) | ✅ | ✅ `use_window_size()`로 compact/medium/expanded 제공 |
| 내비게이션 및 시트 (Navigation and sheets) | ✅ | ✅ 레일, 드로어, 하단 바, 시트 구현 |
| 서드파티 위젯 확장: `FR-11` | ⚠️ 컴파일 타임에만 가능 | ⚠️ `LinearProgressIndicator`가 그 예시 |

마일스톤은 [`PROJECT.md`](../../PROJECT.md)에 있습니다(M0~M8). **M1이 프로젝트의 생사를
가릅니다.** native-image 빌드에서 한글 조합이 정상이면 나머지는 분량 문제입니다.

---

## ✨ API 맛보기

아래는 실제
[`compose-rust/examples/desktop_demo.rs`](../../compose-rust/examples/desktop_demo.rs)를 길이만
줄인 것입니다. 이 저장소에서 컴파일됩니다.

```rust
use compose_rust::prelude::*;

fn app() -> Element {
    let mut messages = use_signal(Vec::<String>::new);
    let mut draft = use_signal(String::new);

    rsx! {
        Column {
            fill_max_width: true,
            Text { text: "compose-rust chat" }
            for message in messages() {
                Text { text: message }
            }
            TextField {
                placeholder: "Write a message",
                multiline: true,
                on_value_change: move |value| draft.set(value),
                on_key_down: move |event: KeyEvent| {
                    if event.key() == Key::Enter && !event.shift_key() {
                        let message = draft().trim().to_owned();
                        if !message.is_empty() {
                            messages.write().push(message);
                            draft.set(String::new());
                        }
                        event.consume();   // Renderer의 onKeyEvent가 true를 반환합니다
                    }
                }
            }
            Button {
                text: "Send",
                on_click: move |_| { /* ... */ }
            }
        }
    }
}

fn main() {
    compose_rust::launch(app);
}
```

눈여겨볼 곳이 두 군데 있습니다.

- **`event.consume()`**은 핸들러가 그 키를 처리했다고 Renderer에 알리는 방법입니다. 웹의
  `preventDefault()`, Compose의 `PointerInputChange.consume()`과 같은 모델입니다. Dioxus 0.7의
  핸들러에는 반환값이 없어서, 이 표시를 이벤트 객체에 실어 돌려보냅니다(`FR-12`).
- **IME 조합 중에는 키 이벤트를 Rust로 보내지 않습니다.** 조합 중의 Enter는 제출이 아니라 조합
  확정입니다. 이걸 어기면 한글 입력 중이던 글자가 사라진 채 제출됩니다.

> ⚠️ `rsx!` 안에서는 `compose_rust::Box`처럼 경로를 붙여 써야 합니다. `dioxus-core` 0.7의 매크로
> 확장이 경로 없는 `Box<T>`를 쓰는데, prelude의 glob이 그것을 가리기 때문입니다.

---

## 🎨 디자인 시스템

**Material 3**, **Apple HIG**, **WinUI/Fluent**, **Liquid Glass** 등 일곱 가지를 1급으로 지원하는 것이 계획입니다.
애플리케이션이 고르며, 모든 플랫폼에서 동일하게 쓰거나 호스트 플랫폼을 따라가게 할 수 있습니다.

```rust
// 모든 플랫폼에서 같은 디자인 시스템
LaunchBuilder::new().with_theme(Theme::unified(DesignSystem::Material3)).launch(app);

// 호스트 플랫폼을 따라감. fallback 인자는 필수입니다
LaunchBuilder::new().with_theme(Theme::adaptive(DesignSystem::Material3)).launch(app);
```

설계는 [`docs/SPEC.md`](../SPEC.md)의 `FR-13`, `FR-14`에 자세히 있습니다.

- 위젯은 리터럴이 아니라 **역할**만 내보냅니다. `ColorRole`, `TypeRole`, `ShapeRole`,
  `SpaceRole`, 그리고 `ButtonVariant::{Filled, Tonal, Outlined, Text}` 같은 컴포넌트 변형입니다.
- **역할을 토큰으로 푸는 쪽은 Host가 아니라 Renderer입니다.** 그래서 다크모드 전환이 트리 전체에
  대한 `SetProp` 폭풍(`O(노드 수)`)이 아니라 `SetTheme` 한 건과 `CompositionLocal` 무효화로
  끝납니다. 프레임 예산에서 빠지는 비용의 차이입니다.
- 여덟 번째 디자인 시스템을 추가할 때 위젯 코드, 속성, Modifier, 와이어 포맷은 건드리지 않습니다.
  Rust enum 변형 하나, Kotlin 토큰 테이블 하나, 규칙 구현 하나면 됩니다.
- `adaptive`는 **기본값이 아닙니다.** `with_theme`을 부르지 않으면
  `Theme::unified(DesignSystem::Material3)`입니다. 플랫폼마다 다르게 보이는 것은 기본값으로
  적절하지 않기 때문입니다.

> **상태: `Draft`, 설계뿐입니다.** ⚠️ 아직 코드는 하나도 없습니다. `Theme`, `DesignSystem`,
> `ColorRole`, `TypeRole`, `ScrollColumn`은 지금 `compose-rust/src/`에도 Kotlin 렌더러에도
> 없습니다. 현재 동작하는 것은 리터럴 부분집합, 즉 `Modifier::Background(u32 ARGB)`와
> `Modifier::Padding(f32)` 같은 것들입니다. 위 코드는 **명세된 API의 예시(illustrative)**이며
> 동작하는 API가 아닙니다.

---

## 🏗 아키텍처

```
┌────────────────────── Host (Rust) ───────────────────────┐
│  사용자 컴포넌트, rsx!, hooks, signals                    │
│  dioxus-core VirtualDom                                  │
│  compose-rust 렌더러:    Mutations ──► 고정 레이아웃        │
│                                        바이트 레코드       │
└───────────────────────────┬──────────────────────────────┘
                            │
             동기·동일 스레드 직접 호출 (JSI 방식)
             + 호출 1회당 배치 버퍼 1개
                            │
┌───────────────────────────┴──────────────────────────────┐
│  생성된 심  (@CEntryPoint / @CName / JNI / wasm)          │
│  프로토콜 디코더 ──► 노드 테이블 (Compose snapshot state)   │
│  스키마 인터프리터  @Composable RenderNode                 │
│  Compose Desktop (AWT) · Compose iOS (UIKit)             │
└────────────────────── Renderer (Kotlin) ─────────────────┘
```

**Rust는 UI를 기술하고, Compose가 해석해서 그립니다.** Rust는 Compose API를 직접 호출하지
않습니다. 할 수도 없습니다. GraalVM의 `@CEntryPoint`는 primitive와 word 크기 값만 넘길 수 있어서
`Modifier`나 `MutableState` 같은 객체는 원리적으로 경계를 넘지 못합니다. 대신 UI 트리를 **값**으로
보내고, Kotlin 쪽의 범용 인터프리터가 그것을 다시 조립합니다. Cash App의 Redwood와 Jetpack
Glance가 쓰는 것과 같은 패턴입니다.

**경계는 동기·동일 스레드입니다**(`PR-1`). VirtualDom이 Renderer의 UI 스레드에서 돌고, 양쪽이
서로를 직접 호출합니다. JSI가 React Native의 옛 브리지를 대체한 방식 그대로입니다. 큐도, 링
버퍼도, 스레드 홉도 없고, 이벤트 핸들러는 같은 호출 안에서 결과를 돌려줄 수 있습니다. I/O, 네트워크,
PTY 같은 무거운 작업은 Host 워커 스레드에서 돌면서 시그널을 갱신하고 프레임을 요청합니다
(`PR-3`). 사용자 코드가 경계 함수를 직접 부르는 일은 없습니다.

**경계를 넘는 것은 primitive와 포인터, 길이뿐입니다**(`PR-2`). 페이로드는 코드젠이 만든 고정
레이아웃 zero-copy 레코드이고 제자리에서 읽습니다. 핫 패스에 postcard, bincode, JSON은 없습니다
(`PR-4`). `bytes` 경계 위의 타입 안전은 Rust 단일 소스에서 Kotlin 타입을 생성해서 되찾고, 스키마
해시가 어긋나면 빌드가 실패합니다(`FR-7`, `D6`).

**UI 로컬 상태는 Kotlin이 갖습니다**(`D5`). `TextField`는 비제어 위젯이고, 조합 중인 텍스트는 Rust를
왕복하지 않습니다. 스크롤 위치, 포커스, 애니메이션 상태도 Renderer의 것입니다.

<details>
<summary><b>네이티브 이미지에 Liberica NIK과 작은 우회책 세 개가 필요한 이유</b></summary>

아래는 GraalVM 네이티브 이미지 이야기입니다. Windows를 빌드하는 방식이고, macOS도 Kotlin/Native
경로 이전에는 이것이었습니다. macOS가 배포하는 렌더러는 AWT를 링크하지 않으므로 아래의 어떤 것도
필요하지 않습니다.

Compose Desktop의 창은 AWT `JFrame`이고, AOT 컴파일은 어떤 코드 경로가 도는지를 바꾸지 않습니다.
그래서 native-image에서도 AWT의 IME 경로가 유지됩니다(`D4`). 문제는 upstream GraalVM이 **Darwin에서
AWT를 통째로 건너뛴다**는 점입니다([oracle/graal#13272](https://github.com/oracle/graal/issues/13272)).
정적 AWT 아카이브가 없으니 렌더러를 링크할 방법이 없습니다. Liberica NIK Full은 AWT를 정적으로
링크합니다.

그렇게 정적 링크된 macOS AWT는 런타임에 세 가지를 파일 경로로 찾습니다. 각각
`renderer/desktop/c/`의 얇은 우회책으로 메웁니다.

| 찾는 것 | 우회책 |
|---|---|
| libawt가 경로로 로드하는 `libawt_lwawt.dylib` | 자리만 채우는 dylib. JNI 함수는 실행 이미지 안에서 해석됩니다 |
| Skiko가 `<java.home>/lib`에서 `dlopen`하는 `libjawt.dylib` | 이미지 안의 `JAWT_GetAWT`로 넘기는 포워더 |
| 정적 링크된 JNI 라이브러리가 반드시 정의해야 하는데 NIK 아카이브에 없는 `JNI_OnLoad_osxui` | 직접 정의 |

그리고 **AppKit은 메인 스레드를 요구합니다.** 렌더러는 보조 스레드에서 돌고, 메인 스레드는
`NSApplication`을 직접 만들어 실행합니다. 이렇게 하면 AWT가 임베디드 모드로 동작합니다. SWT나
JavaFX 호스트가 쓰는 것과 같은 방식입니다. AWT가 자기 루프를 갖게 두면 `[NSApp run]`을 무한히 다시
들어가서, 창을 닫아도 제어가 Host로 돌아오지 않습니다.

여기서 말하는 JNI는 전부 JDK 내부 이야기입니다. Host ↔ Renderer 경계는 순수 C ABI입니다.
</details>

---

## 🚀 시작하기

### 내 프로젝트에서 쓰기

한 줄이면 끝입니다. `cargo build`가 이 타깃에 필요한 렌더러를 알아내고, 크레이트 버전에
정확히 맞는 릴리스 아티팩트를 내려받고, 게시된 `.sha256`으로 검증하고, `target/` 밖의
캐시에 풀어서 링크합니다.

```toml
[dependencies]
compose-rust = "0.0.1"
```

설정할 환경 변수도, 손으로 내려받을 파일도, 실행할 스크립트도 없습니다. 캐시는 버전과
타깃으로 키가 잡혀 있어서 `cargo clean`을 견디고 같은 기계의 프로젝트끼리 한 벌을
공유합니다.

필요한 경우를 위한 변수가 둘 있고, 둘 다 설치에 필요하지는 않습니다.

| 변수 | 효과 |
|---|---|
| `COMPOSE_RUST_RENDERER_DIR` | 이 디렉터리의 렌더러를 씁니다. 가장 먼저 확인하고, 설정돼 있으면 아무것도 내려받지 않습니다. 직접 빌드한 렌더러, 벤더링한 사본, 망 분리 빌드가 모두 이것 하나로 해결됩니다. |
| `COMPOSE_RUST_CACHE_DIR` | 캐시를 `$HOME/.cache/compose-rust`(Windows는 `%LOCALAPPDATA%\compose-rust`)에서 옮깁니다. |

네트워크가 없는 빌드는 어떤 파일 둘을 어디에 두면 되는지 말하고, 그 자리에 두면 그것으로
끝입니다. `default-features = false`는 렌더러 없이 빌드합니다(헤드리스, 문서 빌드). 그렇게
만든 바이너리를 실행하면 무엇이 없는지 말하고 0이 아닌 상태로 끝납니다. 창을 열지 않은 채
0을 반환하지 않습니다.

아래는 전부 **이 저장소에서 작업할 때** 필요한 내용이며, 렌더러 툴체인까지 갖춰야 합니다.

### 0. 개발 환경 점검

```bash
./scripts/setup-check.sh          # CI 스타일 출력은 --quiet
```

빌드에 필요한 도구를 전부 확인하고, 빠진 것이 있으면 고치는 명령을 그대로 알려줍니다. 여기서
시작하세요. 아래 내용은 이 스크립트가 통과한다는 전제입니다.

### 1. Rust

[rustup](https://rustup.rs)으로 설치합니다. `scripts/check.sh`가 `cargo fmt`와 `cargo clippy`를
쓰므로 두 컴포넌트가 필요합니다. 워크스페이스는 Rust **1.85+**(edition 2024)를 씁니다.

```bash
rustup component add rustfmt clippy
```

### 2. Liberica NIK 25 **Full**, GraalVM 네이티브 이미지에만 필요

macOS가 배포하는 렌더러를 빌드하는 데는 필요하지 않습니다. 그쪽은 Kotlin/Native이고, 필요한 것은
3번의 패치된 Compose입니다.

> ⚠️ **macOS에서는 upstream GraalVM이 동작하지 않습니다.** Darwin에서 AWT 지원을 건너뛰기
> 때문에([oracle/graal#13272](https://github.com/oracle/graal/issues/13272), 2026-09 기준 open)
> Compose Desktop을 이미지에 링크할 수 없습니다. BellSoft **Liberica NIK 25 Full**을 쓰세요.
> 표준 버전이 아니라 *Full*이어야 합니다.

```bash
brew install --cask liberica-nik-full
# 또는 https://bell-sw.com/pages/downloads/native-image-kit/ 에서 "NIK 25 Full" 내려받기
# 또는 버전이 고정된 설치 스크립트 (macOS 전용):
./scripts/install-nik.sh
```

`renderer/desktop/scripts/env.sh`가 다음 순서로 찾습니다.

1. `$GRAALVM_HOME`(설정된 경우)
2. `~/Library/Java/JavaVirtualMachines/bellsoft-liberica-vm-full-openjdk25*/Contents/Home` 중 최신

개발에 쓰는 설치는 `bellsoft-liberica-vm-full-openjdk25-25.0.4.1`입니다. 스크립트는
`lib/static/darwin-*/libawt_lwawt.a`가 없는 설치를 **거부합니다.** 순정 GraalVM을 긴 빌드 끝의
링크 실패가 아니라 시작 전에 잡아내기 위한 것입니다.

macOS에서는 Xcode 명령줄 도구(`xcode-select --install`)도 필요합니다. `cc`, `ld`와
`renderer/desktop/c/`가 쓰는 AppKit 헤더 때문입니다.

**현재 스크립트가 지원하는 것은 macOS뿐입니다.** Linux와 Windows native-image 빌드는 아직입니다.

### 3. 패치된 Compose

렌더러는 JetBrains가 발행한 것이 아니라 로컬 Maven 저장소에서 Compose를 찾습니다. 필요한 세 가지가
이 플랫폼용 공개 빌드에서는 비어 있고, 그것을 담고 있는 모듈 밖에서는 채울 수 없기 때문입니다.
텍스트 선택 메뉴가 내주는 항목, 복사에 쓰이는 키, 그리고 Linux 타깃입니다. 이 변경은 Compose의 포크
[`thisisthepy/compose-multiplatform-core-extended`](https://github.com/thisisthepy/compose-multiplatform-core-extended)에
커밋으로 들어 있고, 빌드 스크립트가 그 커밋 하나에 고정합니다.

```bash
./renderer/scripts/build-compose.sh
```

고정된 커밋을 받아, 렌더러가 요구하는 모듈을 발행합니다. 오래 걸리지만 빌드마다 할
일은 아니고 한 번이면 됩니다. 포크는 자기 좌표(`org.thisisthepy.compose.*`,
`<상류 버전>-ext.<N>`)로 발행하고 아직 공개 저장소에는 없으므로, Compose는 JetBrains가 발행한
것에서 하나도 오지 않습니다. 빌드하는 타깃마다 이 스크립트를 돌려야 합니다. 예를 들어
`--target macosArm64 --target jvm`이고, 타깃은 `macosArm64`, `linuxX64`, `iosArm64`,
`iosSimulatorArm64`, `wasmJs`, `jvm`, `android`입니다.

### 4. Kotlin

설치할 것이 없습니다. `renderer/kotlin`(Windows는 `kotlin.bat`)이 자체 부트스트랩
래퍼라, 처음 실행할 때 고정된 버전의 툴체인을 내려받습니다.

### 5. 렌더러 빌드

`renderer/build/macos/`에 Compose와 Skia, 인터프리터가 들어 있는 정적 라이브러리
하나를 만듭니다(`PR-8`). 몇 분 걸립니다.

```bash
cd renderer
./desktop/scripts/build-macos.sh --release
```

```
build/macos/
  libcompose_rust_renderer.a       렌더러 (Compose, Skia, 인터프리터, 우리 코드)
  libcompose_rust_renderer_api.h   Kotlin/Native가 생성한 헤더
```

<details>
<summary><b>GraalVM 네이티브 이미지와 <code>dist/lib/</code>에 생기는 것</b></summary>

`./desktop/scripts/build-native.sh`는 대신 네이티브 이미지를 빌드합니다. Windows를 빌드하는
방식이고 `smoke-test.sh`가 링크하는 대상이며, macOS의 배포 방식은 더 이상 이것이 아닙니다.
결과물은 파일 하나가 아니라 디렉터리입니다. 정적으로 링크된 AWT가 일부 항목을 경로로 찾기
때문입니다.

```
build/native-image/dist/lib/
  libcompose_rust_renderer.dylib   렌더러 (AWT, Skiko JNI, Compose, 우리 코드)
  libskiko-macos-<arch>.dylib        Skia. Skiko가 경로로 로드합니다
  libjawt.dylib                      JAWT_GetAWT를 렌더러로 넘기는 포워더
  libawt_lwawt.dylib                 libawt가 경로로 로드하는 자리 채움
```
</details>

### 6. 스모크 테스트

최소한의 C 호스트를 라이브러리에 링크해 `compose_rust_renderer_run`을 호출합니다. 창이 뜨고,
닫으면 0을 반환해야 합니다. `PR-8`의 수용 기준입니다.

```bash
cd renderer
./desktop/scripts/smoke-test.sh
```

무인 실행이 필요하면 `COMPOSE_RUST_AUTOEXIT_MS=6000`으로 창이 스스로 닫히게 할 수 있습니다.

### 7. Rust 데모 실행

```bash
cargo run -p compose-rust --example desktop_demo --features native-renderer
```

이 저장소의 체크아웃에서는 빌드 스크립트가 방금 빌드한 워크스페이스의
`renderer/build/native-image/dist/lib`를 내려받기보다 먼저 씁니다. 전체 순서는
`COMPOSE_RUST_RENDERER_DIR`, 워크스페이스 빌드 결과물, 캐시, 크레이트 버전의 릴리스
순입니다(`NFR-10`).

### 8. JVM 개발 셸

렌더러 자체를 손볼 때 가장 빠른 반복 경로입니다. hot reload와 `@Preview`를 쓸 수 있고
native-image 빌드가 필요 없습니다(`NFR-5`, `D7`).

```bash
cd renderer
./kotlin run -m desktop   # Compose 개발 셸
./kotlin run -m desktop    # 렌더러 모듈 자체를 JVM에서, 스크립트된 Host로 구동
```

> JVM은 **여기서만** 허용됩니다. 배포 산출물에는 절대 들어가지 않습니다(`C2`).

---

## 📊 성능

목표(`NFR-9`)는 **같은 화면을 Kotlin/Compose로 직접 작성한 것과 체감 차이가 없는 수준**입니다.
기준은 120Hz 디스플레이, 프레임당 8.33ms입니다.

### 예산, `SPEC §5.1`

| 항목 | 기준 (p99, 릴리스 빌드) |
|---|---|
| 순수 Compose 기준선 대비 오버헤드 | 프레임 시간 증가 ≤ 10% |
| Host 처리 (핸들러 + diff + 배치 인코딩) | 일반 상호작용 ≤ 0.5ms, 스트리밍 프레임 ≤ 1ms |
| 경계 호출 1회 | 데스크톱/iOS ≤ 100ns, Android는 `@FastNative` 적용 시 ≤ 200ns |
| 배치 적용 (디코드 + 스냅샷 적용) | Mutation 100건당 ≤ 0.3ms |
| 입력 → 화면 | 기준선과 같은 프레임 수. 추가 프레임 지연 0 |
| 정상 상태 할당 | 경계 인코딩(arena 재사용) 0회. Host 전체 경로는 프레임당 200회 이하이며 증가하지 않을 것 |
| 프레임 드랍 | 초당 100회 추가 스트리밍 + 1만 개 목록 스크롤 중 0 |

예산 초과는 버그로 취급하며, CI가 빌드를 실패시킵니다.

### 실측, 2026-09-20

[`compose-rust/benches/baseline.json`](../../compose-rust/benches/baseline.json)에 기록되어
있고, `scripts/check.sh` 안에서 `cargo bench`로 다시 돌립니다.

> **측정 환경:** Mac mini (Macmini9,1) · Apple M1, 8코어(성능 4 + 효율 4) · 16GiB ·
> macOS 26.5.1 (25F80) · `aarch64-apple-darwin` · rustc 1.98.1 · release 프로파일.

| 측정 항목 | 결과 | 예산 |
|---|---|---|
| 클릭 → 디스패치 → diff → 인코딩 | **13.3µs** p99 | ≤ 500µs |
| Mutation 100건 인코딩 | **2.9µs** p99, 정상 상태 할당 **0회** | ≤ 0.3ms |
| 스트리밍: 1만 개 메시지 대화에 100회 추가 | **220µs** p99 | ≤ 1ms |
| 상호작용당 할당 횟수 (Host 전체 경로) | **99회** | ≤ 200, 그리고 증가하지 않을 것 |

이 99회는 Dioxus가 diff와 이벤트 처리 과정에서 스스로 하는 할당입니다. 0으로 만들려면 Dioxus를
포크해야 하는데 이는 `D2`와 충돌하고, Rust에는 GC가 없어서 이 할당이 프레임 멈춤으로 이어지지도
않습니다. 기준은 같은 상호작용을 반복해도 이 숫자가 **늘지 않는지**입니다. 늘어나면 누수나 캐시
미작동으로 보고 조사합니다.

모두 **Host 쪽 수치**입니다. Renderer 쪽 프레임 시간과 기준선 대비 10% 비교는 native-image
빌드에서 아직 측정해야 합니다.

---

## 🗂 저장소 구조

```
compose-rust/
├─ compose-rust/                  # Rust: Host, Dioxus 렌더러 크레이트
│  ├─ src/
│  │  ├─ lib.rs                     #   공개 API, rsx! 엘리먼트, 이벤트 속성
│  │  ├─ widgets.rs                 #   Column, Row, Box, Text, TextField, Button, Spacer, LazyColumn
│  │  ├─ schema.rs                  #   와이어 스키마의 단일 소스
│  │  ├─ protocol.rs                #   고정 레이아웃 인코딩 (PR-4)
│  │  ├─ boundary.rs                #   C ABI 표면, launch / LaunchBuilder
│  │  └─ codegen.rs                 #   Rust 스키마 → Kotlin 타입
│  ├─ examples/desktop_demo.rs      #   실행 가능한 데모
│  ├─ benches/baseline.json         #   기록된 성능 기준선
│  └─ tests/vectors/                #   양쪽이 함께 검증하는 프로토콜 벡터
├─ renderer/         # Kotlin: Renderer (Kotlin Toolchain / Amper)
│  ├─ native/                       #   인터프리터, C 심, native-image 빌드 스크립트
│  ├─ desktop/                      #   JVM 개발 셸
│  ├─ shared/                       #   공용 Compose 코드
│  └─ ios/  android/  web/          #   플랫폼 타깃
├─ samples/                         # 11개의 샘플 앱 (4개는 adaptive, 7개는 unified)
├─ scripts/                         # setup-check.sh, check.sh, install-nik.sh, publish-main.sh
└─ docs/
   ├─ INTENT.md                     # 왜 만드는가, 결정 D1~D10, 폐기한 대안
   ├─ SPEC.md                       # FR-*, NFR-*, PR-* 와 수용 기준
   ├─ guide/                        # 사용자 가이드 사이트 (손으로 쓴 HTML, en + ko)
   └─ locales/README_ko.md          # 이 문서
```

---

## 🤝 기여하기

### Spec Driven Development

**SPEC이 기준입니다.** 동작을 구현하기 전에 해당 SPEC ID(`FR-*`, `NFR-*`, `PR-*`)를 찾으세요.
없으면 SPEC을 먼저, 별도 커밋으로 고칩니다. 코드와 SPEC이 어긋나면 코드가 틀린 것입니다. SPEC이
틀렸다면 SPEC을 먼저 고치고 이유를 설명합니다. 결정이 바뀌면
[`docs/INTENT.md`](../INTENT.md)부터 고치고, 그다음 SPEC, 그다음 코드입니다.

### Test Driven Development

SDD가 무엇을 만들지 정하고, TDD가 어떻게 만들지 정합니다. 테스트는 수용 기준에서 나오므로
**테스트가 없는 요구사항은 완료가 아닙니다.**

- 실패하는 테스트를 먼저 쓰고, 통과시키고, 정리합니다. 테스트와 구현은 **같은 커밋**에
  넣습니다. 모든 커밋에서 트리가 green이어야 합니다.
- 테스트 이름은 요구사항을 따릅니다: `fr4_set_prop_does_not_recompose_siblings`,
  `pr2_batch_applies_atomically`.
- 버그 수정은 그 버그를 재현하는 테스트에서 시작합니다.
- 공개 표면을 통해 테스트합니다. 크레이트 API와 C export, 인터프리터와 `HostConnection`입니다.
- 우리 프로토콜을 목으로 흉내 내지 말고, 체크인된 프로토콜 벡터와 `FakeHostConnection`을 씁니다.
- 성능도 테스트입니다. §5.1은 벤치마크 모음이고, 할당 상한은 단언입니다.

IME(`§6`)와 접근성(`§7`)은 **native-image 빌드**에서 사람이 직접 확인하는 체크리스트이며, SPEC에
그렇게 명시되어 있습니다. 조용히 미검증으로 두지 않습니다.

### 품질 게이트

```bash
./scripts/check.sh              # fmt, clippy, 테스트, 빠른 벤치마크, Kotlin 빌드와 테스트
./scripts/check.sh --full       # 위와 같되 벤치마크를 전체 샘플로
./scripts/check.sh --no-kotlin  # Rust만 (DXC_SKIP_KOTLIN=1도 동일)
```

CI도 같은 방식으로 나뉩니다. [`ci.yml`](../../.github/workflows/ci.yml)은 모든 푸시와 PR에서 Rust
게이트를 macOS와 Linux에서 돌리고,
[`native-renderer.yml`](../../.github/workflows/native-renderer.yml)은 `main`/`develop` 푸시와 매일
밤, 그리고 수동 실행에서 native-image 렌더러를 빌드하고 C 스모크 테스트를 돌립니다. 이 빌드는 약
1GB의 NIK 내려받기와 수십 분이 들어서 모든 푸시 앞에 두기에는 너무 느립니다(`NFR-5`, `D7`).

### 커밋

커밋 하나에 논리적 변경 하나. SPEC 수정과 리팩터링, 기능을 섞지 않습니다. 제목 형식은 다음과
같습니다.

```
<Type>: <명령형 요약>
```

`Feat` · `Fix` · `Refactor` · `Docs` · `Test` · `Chore`. 관련 있으면 SPEC ID를 적습니다. 예:
`Feat: Return handler result from dispatch_event (PR-2)`. `Co-Authored-By` 트레일러나 AI 표기는
**넣지 않습니다.**

---

## 📚 문서

**📖 가이드: [docs/guide](https://github.com/DarkPyonix/compose-rust/tree/develop/docs/guide)**, 영어와 한국어로 시작하기, UI
작성, 목록과 스트리밍, 아키텍처, 문제 해결을 다룹니다.

| 문서 | 내용 |
|---|---|
| [PROJECT.md](../../PROJECT.md) | 범위, 개발 방식, 마일스톤 M0~M8, 열린 질문 |
| [docs/INTENT.md](../INTENT.md) | 동기, 협상 불가 조건, 결정 D1~D10, 폐기한 대안 |
| [docs/SPEC.md](../SPEC.md) | 기능·비기능 요구사항, 경계 프로토콜, 수용 기준 |
| [AGENTS.md](../../AGENTS.md) | 이 저장소에서 일하는 방식 |

기획 문서(`PROJECT.md`, `INTENT.md`, `SPEC.md`)는 한국어로 씁니다. README와 가이드 사이트는
영어가 기본이고 한국어 번역을 함께 둡니다.

---

## 📄 라이선스

[Apache License 2.0](../../LICENSE).
