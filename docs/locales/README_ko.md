# compose-rust

[![Test](https://github.com/DarkPyonix/compose-rust/actions/workflows/test.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/test.yml)
[![Native renderer test](https://github.com/DarkPyonix/compose-rust/actions/workflows/test-native-renderer.yml/badge.svg)](https://github.com/DarkPyonix/compose-rust/actions/workflows/test-native-renderer.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](../../LICENSE)
[![Rust 1.85+](https://img.shields.io/badge/rust-1.85%2B-orange.svg)](https://www.rust-lang.org)

[English](../../README.md) · **한국어**

**Rust로 Compose 모양의 UI를 쓰면, AOT 컴파일된 Compose 렌더러가 그립니다.**

*웹뷰도, 동봉된 JVM도 없는 네이티브 UI. 데스크톱 앱 하나를 실행 파일 하나로 배포하는 것이 목표입니다.*

compose-rust는 네 가지를 합친 것입니다.

- **Compose 모양의 Rust API.** 슬롯 테이블과 recomposition 런타임을 갖추며, 지금 만드는 중입니다
  ([현재 상태](#-현재-상태) 참고). composable은 함수이고, 상태는 `remember`에 두며, 상태가 바뀌면
  그 상태를 읽은 스코프만 다시 실행됩니다. 무엇이 바뀌었는지 트리를 비교해서 찾지 않습니다.
- **좁은 C ABI 경계와 스키마.** 위젯, 속성, 레코드를 Rust에서 한 번 정의하고, Kotlin 타입과
  플랫폼 심은 그 정의에서 생성합니다. 손으로 쓰지 않습니다.
- **AOT 컴파일된 Compose 렌더러.** 모든 플랫폼에서 미리 네이티브 코드로 컴파일하고, 브라우저는
  Kotlin/Wasm, Android는 Android 라이브러리입니다. 그리기와 입력은 Compose 자신의 텍스트 레이아웃, 위젯,
  플랫폼 IME가 맡습니다.
- **웹뷰 없음, 동봉된 JVM 없음.** 데스크톱의 목표는 시스템 라이브러리만 링크하는 실행 파일
  하나입니다. 0.0.1 릴리스는 아직 그렇지 않습니다. 데스크톱 렌더러가 애플리케이션 옆에 놓이는
  GraalVM 네이티브 이미지 공유 라이브러리입니다. 실행 파일 안에 링크되는 Kotlin/Native 렌더러는
  macOS와 Linux에서 이미 CI로 돌고 있고, 다음 릴리스부터 기본이 됩니다. Windows가 그 뒤를 따릅니다.

---

## 🚦 현재 상태

**0.0.x는 이름을 확보하고 렌더러를 배포하는 단계입니다.** API는 바뀝니다.

지금 크레이트에 있는 것:

- **경계.** 렌더러가 부르는 `compose_rust_host_*` C 함수들, `Host`, `LaunchBuilder`,
  `launch_runtime`, 그리고 워커 스레드에서 프레임을 깨우는 `request_frame_from_worker`.
- **프로토콜.** 고정 레이아웃 mutation 레코드. 배치 버퍼 하나에 쓰이고 렌더러가 그 자리에서
  읽으며, 핫 패스에 직렬화 포맷이 없습니다.
- **스키마.** 위젯, 속성, Modifier, 디자인 역할, `Theme`, `DesignSystem`, 그리고 서로 다른
  스키마로 빌드된 Host와 렌더러가 함께 돌지 못하게 막는 스키마 해시. `codegen` 바이너리가 여기서
  Kotlin 쪽을 생성합니다.
- **`Runtime` 트레이트.** 트리를 만드는 쪽이 구현하는 인터페이스입니다. 그 주변의 타입도 있습니다:
  에셋, 커스텀 드로잉, 입력 이벤트, 알림, 메시지, 팔레트, 창 크기 클래스.
- **렌더러 다운로드.** 빌드 스크립트가 대상 플랫폼에 맞는 미리 빌드된 렌더러를 받아 검증하고
  링크합니다([시작하기](#-시작하기) 참고).

아직 **없는** 것은 자기 작성 API입니다. `#[composable]`, `Recomposer`, `launch`는 만드는 중이며([#23](https://github.com/DarkPyonix/compose-rust/issues/23), [#64](https://github.com/DarkPyonix/compose-rust/issues/64))
**2026-10-20의 1.0.0**을 목표로 합니다. 그때까지 이 크레이트는 화면을 직접 쓰는 도구가 아니라,
트리를 만드는 층이 올라설 토대입니다.

### 계획한 모양

> ⚠️ **계획이며, 아직 크레이트에 없습니다.** 아래 코드는 작성 API가 가질 모양을 보입니다.
> 0.0.x에서는 컴파일되지 않고, 이름도 확정되지 않았습니다.

```rust
#[composable]
fn counter() {
    let count = remember(|| mutable_state_of(0));
    Column(Modifier::new().fill_max_width(), || {
        Text(format!("{}", count.get()));
        Button(|| count.set(count.get() + 1), || Text("+1"));
    });
}

fn main() {
    compose_rust::launch(counter);
}
```

정해진 것은 이렇습니다. `#[composable]`이 그룹을 넣으므로 그룹이나 키를 손으로 쓰지 않으며,
분기, 반복문, 조기 `return`, `?`, 되감기를 지나도 그룹이 맞게 닫힙니다. 이름은 Compose를 따릅니다.
위젯 어휘는 스키마에 있는 그것 하나이고, 두 번 정의하지 않습니다.

> 🧩 **Dioxus와 `rsx!`가 좋다면** [dioxus-compose](https://github.com/DarkPyonix/dioxus-compose)를 쓰십시오. compose-rust 위에서 돌고, HTML/CSS `rsx!`와 Compose 위젯 `rsx!`를 모두 지원합니다.

### 설계했고 아직 develop에 없는 것

아래 항목은 설계와 합의가 끝났지만, `develop`의 크레이트에도 렌더러에도 들어 있지 않습니다.
작업이 있는 것은 열린 풀 리퀘스트나 브랜치에 있습니다.

| 항목 | 상태 | 이슈 | 진행 중인 작업 |
|---|---|---|---|
| 작성 API(`#[composable]`, `remember`, `launch`) | 부분 | [#23](https://github.com/DarkPyonix/compose-rust/issues/23), [#64](https://github.com/DarkPyonix/compose-rust/issues/64) | `feature/compose-api` 브랜치 |
| 웹에서 JavaScript 포워더 대신 Kotlin에서 Rust로 직결 호출 | 부분 | [#54](https://github.com/DarkPyonix/compose-rust/issues/54) | [#84](https://github.com/DarkPyonix/compose-rust/pull/84) |
| HTML/CSS 그리기 원소(`AbsoluteBox` 와 그 Modifier) | 부분 | [#104](https://github.com/DarkPyonix/compose-rust/issues/104) | [#74](https://github.com/DarkPyonix/compose-rust/pull/74) |
| 변환과 렌더러가 재생하는 애니메이션 | 부분 | [#105](https://github.com/DarkPyonix/compose-rust/issues/105) | [#83](https://github.com/DarkPyonix/compose-rust/pull/83) |
| HTML 텍스트 속성과 측정 호출 | 부분 | [#106](https://github.com/DarkPyonix/compose-rust/issues/106) | `feat/measure-call` 브랜치 |
| OS 글자 크기와 앱 확대 | 부분 | [#68](https://github.com/DarkPyonix/compose-rust/issues/68) | [#77](https://github.com/DarkPyonix/compose-rust/pull/77)(데스크톱의 OS 글자 크기만) |
| 동작 키(조밀한 키패드 버튼) | 부분 | [#57](https://github.com/DarkPyonix/compose-rust/issues/57) | [#76](https://github.com/DarkPyonix/compose-rust/pull/76) |
| 막대 제목을 플랫폼 캡션에 맡기기 | 계획 | [#79](https://github.com/DarkPyonix/compose-rust/issues/79) | 없음 |
| 디자인 시스템을 시스템별 라이브러리와 적응형 층으로 나누기 | 부분 | [#39](https://github.com/DarkPyonix/compose-rust/issues/39) | Compose 포크의 `chore/thisisthepy-coordinates` 브랜치 |
| macOS와 Linux에서 AWT 없는 Kotlin/Native 렌더러를 기본으로 | 부분 | [#22](https://github.com/DarkPyonix/compose-rust/issues/22) | `feature/native-default-renderer` 브랜치 |
| Windows Kotlin/Native 렌더러 | 부분 | [#24](https://github.com/DarkPyonix/compose-rust/issues/24) | `feature/windows-kotlin-native` 브랜치 |
| 작성 API로 다시 쓴 샘플 | 계획 | [#85](https://github.com/DarkPyonix/compose-rust/issues/85) | 없음 |
| 시스템 색 구성표가 바뀌었다는 이벤트 | 제안, 승인 대기 | [#97](https://github.com/DarkPyonix/compose-rust/issues/97) | 없음 |
| 노드마다 포커스를 얻은 이벤트와 키를 뗀 이벤트 | 제안, 승인 대기 | [#98](https://github.com/DarkPyonix/compose-rust/issues/98) | 없음 |

---

## 🖥 플랫폼

| 플랫폼 | 상태 | 렌더러 |
|---|---|---|
| 🍎 **macOS (arm64)** | **처음부터 끝까지 동작** | 0.0.1은 앱 옆에 놓이는 GraalVM 네이티브 이미지 라이브러리를 배포합니다. 실행 파일 안에 링크되는 Kotlin/Native 렌더러(자기 창, Metal로 그림)는 CI에서 돌고 있고, 이것을 대신하는 것이 계획입니다([#22](https://github.com/DarkPyonix/compose-rust/issues/22)). 기본적인 한글 IME 입력은 동작하고, IME 체크리스트 전체는 아직 끝나지 않았습니다 |
| 🐧 Linux (x64, arm64) | 빌드되고 시작됨 | 0.0.1은 GraalVM 네이티브 이미지 라이브러리를 배포합니다. Kotlin/Native 렌더러(자기 X11 창, GLX로 그림)는 두 아키텍처 모두 CI에서 헤드리스 시작 테스트를 통과하고, 이것을 대신하는 것이 계획입니다([#22](https://github.com/DarkPyonix/compose-rust/issues/22)) |
| 🪟 Windows | 빌드되고 시작됨 | 지금은 GraalVM 네이티브 이미지이고, 렌더러가 바뀔 때마다 스모크 테스트를 합니다. Windows도 실행 파일 하나가 되도록 Kotlin/Native로 옮기는 것이 계획입니다([#24](https://github.com/DarkPyonix/compose-rust/issues/24)) |
| 📱 iOS | 빌드되고 시작됨 | 같은 C 심볼을 내보내는 Kotlin/Native 정적 아카이브. XCFramework로 릴리스합니다 |
| 🤖 Android | **처음부터 끝까지 동작** | Kotlin Activity가 프로세스와 루프를 갖고, Rust는 cdylib이며, 양쪽 JNI 심은 스키마에서 생성됩니다. 크레이트가 렌더러의 Kotlin 소스를 싣고 있고, 빌드 스크립트가 그것을 Gradle 프로젝트에 풀어 놓습니다 |
| 🌐 Web (wasm) | **처음부터 끝까지 동작** | `WebAssembly.Memory` 하나를 Kotlin/Wasm 모듈이 갖고 Rust 모듈이 가져다 쓰므로, 배치는 쓰인 자리에서 읽힙니다. 렌더러에서 Host로 가는 호출은 생성된 JavaScript 포워더를 거치며, 약 12 ns로 측정되었습니다. 포워더 없는 직결 호출은 계획입니다([#54](https://github.com/DarkPyonix/compose-rust/issues/54)) |

### 실제 무게

notepad 샘플을 릴리스로 빌드하고 스트립한 것이며, Kotlin/Native 렌더러를 링크했습니다(0.0.1 다음에 기본이 되는 경로). 실행 파일 하나입니다: 옆에 놓이는 런타임도,
안에 든 가상 머신도 없고, 링크된 것은 시스템 라이브러리뿐입니다.

| 플랫폼 | 실행 파일 | 실제 점유 메모리 |
|---|---|---|
| macOS (arm64) | **28.76 MB** | **35.1 MB** |
| Linux (x86-64) | **37.93 MB** | 아직 재지 않음 |
| Windows | 아직 재지 않음 | 아직 재지 않음 |

비교하자면, 렌더러가 아직 자바 런타임을 싣고 있던 시절 macOS의 같은 종류 앱은 파일 넷, 93.1 MB였고
실제 점유는 56 MB였습니다. 브라우저에서는 렌더러와 Skia를 합쳐 gzip으로 6.95 MB입니다.

---

## 🏗 동작 방식

```
┌──────────────────────── Host (Rust) ────────────────────────┐
│  your code                                                  │
│  the layer that builds the tree (Runtime)                   │
│  compose-rust: schema, fixed-layout records, one batch      │
└──────────────────────────────┬──────────────────────────────┘
                               │
              synchronous, same-thread direct calls
              primitives, pointers and lengths only
                               │
┌──────────────────────────────┴──────────────────────────────┐
│  generated shims (C exports, JNI, wasm)                     │
│  protocol decoder ──► node table                            │
│  schema interpreter ──► Compose                             │
└──────────────────── Renderer (Kotlin, AOT) ─────────────────┘
```

**Rust가 UI를 기술하고, Compose가 해석합니다.** Rust는 Compose API를 직접 부르지 않습니다. 트리는
고정 레이아웃 레코드라는 값으로 건너가고, Kotlin 쪽의 범용 인터프리터가 그것을 실제 Compose 트리로
만듭니다. Cash App의 Redwood와 Jetpack Glance가 같은 패턴을 씁니다.

**경계는 동기이고, 한 스레드 위에 있습니다.** Host는 렌더러의 UI 스레드에서 돌고, 두 쪽은 서로를
직접 호출합니다. JSI가 React Native의 옛 브리지를 대체한 방식과 같습니다. 큐도 스레드 홉도 없으므로
이벤트 핸들러는 같은 호출 안에서 결과를 돌려줄 수 있습니다(예: 키 입력을 소비했는지). 무거운 일은
Host 워커 스레드에서 돌며, 워커는 상태를 갱신하고 프레임을 요청합니다. 애플리케이션 코드는 경계
함수를 부르지 않습니다.

**넘어가는 것은 primitive, 포인터, 길이뿐입니다.** Kotlin 타입이 Rust의 단일 정의에서 생성되므로
그 선 위에서 타입 안전이 돌아오고, 두 쪽이 어긋나면 스키마 해시가 빌드를 실패시킵니다.

**UI 로컬 상태는 Kotlin에 있습니다.** `TextField`는 비제어이고, IME 조합 중인 텍스트는 Rust를
왕복하지 않습니다. 스크롤 위치, 포커스, 애니메이션 상태도 렌더러의 것입니다.

---

## 🎨 디자인 시스템

Material 3, Apple HIG, Fluent, Liquid Glass를 비롯한 디자인 시스템 일곱 개를 애플리케이션마다
고릅니다. 위젯은 역할(색, 글꼴, 모양, 간격)을 내보내고 렌더러가 그것을 토큰으로 풀기 때문에,
다크 모드 전환은 노드마다 속성을 고치는 일이 아니라 테마 변경 한 번입니다.

디자인 시스템은 이 저장소를 떠나 thisisthepy의 Compose 포크로 옮겨 갔고, 그곳에서 `org.thisisthepy.compose.*`
아래의 평범한 Compose 라이브러리가 되어 갑니다. 시스템마다 컴포넌트 라이브러리 하나
(`org.thisisthepy.compose.material3`, `.cupertino`, `.fluent`, `.liquidglass` 등), 그 위에서 기본으로
플랫폼 자신의 시스템을 따르는 `org.thisisthepy.compose.adaptive`, 그리고 두 층이 함께 쓰는 계약인
`org.thisisthepy.compose.designsystem`입니다.

---

## 🚀 시작하기

### 크레이트 쓰기

```toml
[dependencies]
compose-rust = "0.0.1"
```

`cargo build`가 대상에 필요한 렌더러를 알아내고, 크레이트의 정확한 버전에 맞는 릴리스 아티팩트를
내려받아 함께 게시된 `.sha256`으로 확인한 뒤, `target/` 바깥의 캐시에 풀고 링크합니다. 설정할 환경
변수도, 실행할 스크립트도 없습니다. 캐시는 버전과 대상으로 구분되므로 `cargo clean` 뒤에도 남고
프로젝트끼리 공유됩니다.

| 변수 | 효과 |
|---|---|
| `COMPOSE_RUST_RENDERER_DIR` | 이 디렉터리의 렌더러를 씁니다. 가장 먼저 확인하고, 설정되어 있으면 아무것도 내려받지 않으므로 직접 빌드한 렌더러, 벤더링한 사본, 오프라인 빌드가 모두 이것으로 됩니다 |
| `COMPOSE_RUST_CACHE_DIR` | 캐시 위치를 `$HOME/.cache/compose-rust`(Windows는 `%LOCALAPPDATA%\compose-rust`)에서 옮깁니다 |

`default-features = false`로 빌드하면 렌더러 없이 빌드되며, 헤드리스나 문서용 빌드에 씁니다. 그렇게
빌드한 바이너리는 창 없이 조용히 끝나지 않고, 무엇이 빠졌는지 알린 뒤 0이 아닌 값으로 종료합니다.

### 이 저장소에서 작업하기

```bash
./scripts/setup-check.sh   # checks every tool the build needs and prints the fix for anything missing
./scripts/check.sh         # fmt, clippy, tests, quick benchmarks, Kotlin build and tests
```

워크스페이스는 Rust **1.85+**(edition 2024)를 대상으로 하며, `rustfmt`와 `clippy`가 필요합니다.
Kotlin은 따로 설치할 것이 없습니다. `renderer/kotlin`(Windows는 `kotlin.bat`)이 처음 쓸 때 고정된
툴체인을 내려받습니다.

**렌더러 빌드.** 렌더러는
[`thisisthepy/compose-multiplatform-core-extended`](https://github.com/thisisthepy/compose-multiplatform-core-extended)의
고정된 커밋을 씁니다. 게시된 빌드에 없는 것(Linux 타깃, 텍스트 선택 메뉴, 복사 키)을 더한 Compose
포크입니다. 그것을 한 번 빌드한 다음 렌더러를 빌드합니다.

```bash
# macOS
./renderer/scripts/build-compose.sh
cd renderer && ./desktop/scripts/build-macos.sh --release      # build/macos/<target>/

# Linux
./renderer/scripts/build-compose.sh --target linuxX64
cd renderer && ./desktop/scripts/build-linux.sh --release      # build/linux/<target>/
```

각각 Compose, Skia, 인터프리터가 들어간 정적 라이브러리 하나, `libcompose_rust_renderer.a`를
만듭니다. Windows는 아직 GraalVM 네이티브 이미지를 `renderer/desktop/scripts/build-native-windows.ps1`로
빌드합니다.

**JVM 개발 셸**은 렌더러 자체를 고칠 때 가장 빠른 루프이며, 핫 리로드와 `@Preview`가 됩니다. JVM은
여기서만 허용되고, 배포물에는 절대 들어가지 않습니다.

```bash
cd renderer && ./kotlin run -m desktop
```

---

## 🗂 저장소 구조

```
compose-rust/     the crate: boundary, protocol, schema, codegen, renderer download
renderer/         the Kotlin renderer: interpreter, generated shims, one module per platform
samples/          12 sample applications, being rewritten; they do not build until then (#85)
docs/             the user guide (docs/guide) and translations (docs/locales)
experiments/      measured experiments kept for their results
scripts/          setup check, quality gate, release and publishing scripts, script tests
.github/          CI workflows
```

Dioxus 어댑터와 기준선은 [dioxus-compose](https://github.com/DarkPyonix/dioxus-compose)로 옮겨 갔습니다.
`samples/` 아래 샘플은 compose-rust 작성 API로 다시 쓰는 중이며(#85), 그때까지는 빌드되지 않습니다. 디자인 시스템은 Compose 포크로 옮겨 갔으며,
[thisisthepy/compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended)의
`extended/design-systems/` 아래에 있습니다.

---

## 📚 문서

- **가이드:** [darkpyonix.dev/compose-rust](https://darkpyonix.dev/compose-rust/), 영어와 한국어.
- **저장소:** [github.com/DarkPyonix/compose-rust](https://github.com/DarkPyonix/compose-rust)

---

## 📄 라이선스

[Apache License 2.0](../../LICENSE).
