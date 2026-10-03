# SPEC

이 문서는 dioxus-compose의 요구사항과 수용 기준을 정의합니다. 근거는 [INTENT.md](INTENT.md)에 있습니다.

- 상태 표기: `Draft`(합의 전), `Agreed`(구현 가능), `Done`(수용 기준 통과)
- 요구사항 ID는 한 번 부여하면 재사용하지 않습니다. 폐기할 때는 `Withdrawn`으로 남깁니다.

## 1. 용어

| 용어 | 정의 |
|---|---|
| Host | Rust 프로세스. 사용자 UI 코드(compose-rust의 composable 함수, 또는 그 위에 얹힌 Dioxus 컴포넌트)와 도메인 로직을 소유합니다 |
| Composition | compose-rust 런타임이 슬롯 테이블에 들고 있는 composable 호출의 기록. 상태가 바뀌면 그 상태를 읽은 스코프만 다시 실행됩니다(FR-39) |
| Renderer | AOT 컴파일된 Kotlin/Compose 네이티브 라이브러리. 스키마 인터프리터를 포함합니다 |
| Schema | Renderer가 해석할 수 있는 위젯 타입, 속성, Modifier, 이벤트의 닫힌 집합 |
| Mutation | 노드 트리 변경 명령 한 단위 |
| Event | Renderer에서 Host로 올라오는 사용자 입력 알림 |
| Node ID | Host가 발급하는 노드 식별자. 해당 세션 안에서 고유합니다 |
| Handler ID | Host가 발급하는 이벤트 핸들러 식별자 |

## 2. 아키텍처

```
┌──────────────── Host (Rust) ────────────────┐
│ 사용자 composable (compose-rust API)          │
│   또는 dioxus-compose: rsx!, hooks, VirtualDom │
│ compose-rust recomposition 런타임 (슬롯 테이블) │
│ 노드 트리 → Mutation 레코드 → 배치 버퍼          │
└───────────────────┬─────────────────────────┘
                    │  동기 직접 호출 (UI 스레드) + 배치 버퍼
┌───────────────────┴──── Renderer (Kotlin) ───┐
│ 코드젠 심 (@CEntryPoint / @CName / JNI / wasm) │
│ 프로토콜 디코더 → Node 테이블 (snapshot state)  │
│ 스키마 인터프리터 @Composable RenderNode       │
│ 렌더러 소유 창 + Compose 장면 (AWT 없음)       │
│   / Compose iOS(UIKit)                       │
└──────────────────────────────────────────────┘
```

## 3. 기능 요구사항

### FR-1 노드 트리 구성 (`Done`)
Host는 Mutation 시퀀스로 Renderer의 노드 트리를 생성, 수정, 삭제, 이동할 수 있어야 합니다.
- Host는 부모마다 그 아래에 선 것들을 **순서대로** 들고 있습니다. 그 목록에는 Renderer가 그린 노드, Dioxus 플레이스홀더, 그리고 템플릿의 동적 자식이 채워지기 전까지 서 있는 표식이 함께 들어갑니다. 인덱스에 세는 것은 그린 노드뿐입니다.
- 빈 분기(`if false`)와 빈 반복은 Dioxus 플레이스홀더를 남깁니다. 플레이스홀더는 Compose 트리의 노드가 아니라서 node id 0을 싣고, Host는 그 자리에 `Insert`를 보내지 않습니다. 대신 목록에서 자기 자리를 지키고, 그 분기가 채워질 때 보내는 `Insert`가 그 자리를 싣습니다. 플레이스홀더는 모두 node id가 같으므로 node id로 구분하면 서로의 자리를 덮어씁니다. **엘리먼트별로** 구분합니다.
- 자리는 기억해 두지 않고 매번 목록에서 읽습니다. 형제 하나가 사라지면 그 뒤가 모두 앞으로 당겨지므로, `Insert`를 보낼 때 적어 둔 인덱스는 다음 삭제까지만 참입니다. 열려 있을 때만 항목을 선언하는 `Menu`가 여기에 걸렸습니다. 항목 넷이 사라질 때 Dioxus가 마지막 항목을 플레이스홀더로 바꾸고, 플레이스홀더가 그 항목의 적어 둔 인덱스(넷)를 물려받아, 다시 열었을 때 항목들이 앵커 하나뿐인 목록의 끝 너머인 4번부터 들어갔습니다.
- `Insert`/`Move`가 싣는 인덱스는 **그 레코드를 적용하는 순간의** 자식 목록에서의 자리입니다. 완성된 트리에서의 자리가 아닙니다. Dioxus가 뒤쪽 동적 자식을 먼저 만들 때 둘이 갈라집니다. `Menu`의 항목들은 앵커보다 먼저 만들어지므로 항목이 0, 1번으로 들어가고 그 다음 앵커가 0번으로 들어가서 항목들을 뒤로 밉니다. Renderer는 배치를 순서대로 적용하고, 목록 끝을 넘는 인덱스는 끝으로 줄입니다.
- 자기 자신이나 자기 자손 아래로 넣는 `Insert`/`Move`는 부모 사슬에 뿌리가 없게 만듭니다. Renderer는 붙이기 전에 그런 `Insert`를 거부하고 `ProtocolError`로 알리며 가지고 있던 트리를 그대로 둡니다(NFR-7).
- 수용 기준: `Create`, `SetProp`, `SetModifier`, `Insert`, `Move`, `Remove`로 임의의 트리를 만들 수 있고, 적용 결과가 Renderer의 트리 덤프와 일치합니다.
- 수용 기준: 부모가 다른 빈 분기 둘이 동시에 채워질 때 각 분기의 내용이 자기 부모 아래에 붙고, Host가 보내는 부모 사슬은 순환하지 않습니다. 순환하는 `Insert`를 받은 Renderer는 프로세스를 중단하지 않고 `ProtocolError`를 보냅니다. **(2026-09-21 통과)**
- 수용 기준: 리스트 아이템 안의 `Menu`가 열려 있을 때만 항목을 선언해도, 항목들은 앵커 다음 자리부터 자기 메뉴 아래에 붙습니다. 메뉴를 열고 닫기를 되풀이하고 리스트의 윈도가 그 위를 오간 뒤에도 같습니다. **(2026-09-22 통과)**
- 수용 기준: 앵커보다 먼저 도착한 `Menu`의 항목들이 배치를 순서대로 적용한 Renderer의 트리에서 앵커 뒤에 섭니다. **(2026-09-22 통과)**

### FR-2 스키마 기반 렌더링 (`Done`)
Renderer는 스키마에 정의된 위젯 타입만 해석해서 해당 Compose 컴포저블로 렌더링합니다.
- 최소 스키마(M0): `Column`, `Row`, `Box`, `Text`, `TextField`, `Button`, `Spacer`, `LazyColumn`(FR-8)
- 디자인 확장: `ScrollColumn`. 값 모델은 FR-13, 테마는 FR-14를 따릅니다
- 수용 기준: 스키마에 없는 타입이나 속성을 받으면 크래시하지 않고 `ProtocolError` 이벤트를 보냅니다.

### FR-3 이벤트 전달 (`Done`)
사용자 입력은 `(node_id, handler_id, payload)` 형태로 Host 핸들러를 **동기로 직접 호출**합니다(PR-1). 핸들러는 반환값을 돌려줄 수 있습니다. 클로저는 경계를 넘지 않습니다.
- 수용 기준: Button 클릭이 등록된 Rust 핸들러를 정확히 한 번 호출합니다.

### FR-4 상태 갱신 반영 (`Done`)
Host 상태가 변경되면 변경분만 전송하고, Renderer는 해당 노드만 recomposition합니다.
- 수용 기준: Text 하나의 내용을 바꿀 때 전송되는 Mutation은 `SetProp` 1건입니다. 형제 노드는 recomposition되지 않습니다(recomposition 카운터로 확인).

### FR-5 비제어 TextField (`Agreed`)
- TextField의 편집 값과 조합 상태는 Renderer가 소유합니다.
- Renderer는 변경을 알림 이벤트(`TextChanged`, 디바운스 적용)와 확정 이벤트(`TextSubmitted`, `FocusLost`)로 보냅니다.
- Host가 값을 바꿀 때는 명시적 명령 `SetText(node_id, text, selection)`을 씁니다. Renderer는 IME 조합이 진행 중이면 조합이 끝날 때까지 적용을 미룹니다.
- 수용 기준: §6 IME 체크리스트를 통과합니다. **핵심 5개 항목은 2026-09-21 native-image 빌드에서 확인했습니다.** 나머지 4개(멀티라인 Enter 처리, 한글 혼합 붙여넣기, 일본어/중국어 후보창 위치, TextChanged 중 조합 유지)는 미확인이므로 `Done`이 아닙니다.

### FR-6 Dioxus 렌더러 (`Done`)
`dioxus-core` VirtualDom의 `Mutations`를 프로토콜 Mutation으로 변환하는 렌더러를 제공합니다.
- **2026-10-03부터 이 렌더러는 compose-rust 위에 얹히는 층입니다(INTENT D2).** compose-rust의 작성 API는 FR-39이고, Dioxus 층은 그것과 같은 노드 트리와 같은 와이어로 내려갑니다. 이 요구사항의 수용 기준은 그대로 유효합니다.
- 사용자 코드는 `rsx!`와 훅만으로 작성하고, 프로토콜을 직접 다루지 않습니다.
- 수용 기준: M0 화면을 `rsx!` 컴포넌트로 재작성했을 때 동일하게 동작합니다.

### FR-7 스키마 코드젠 (`Done`)
위젯, 속성, Modifier, 이벤트 페이로드는 Rust에서 단일 소스로 정의하고 Kotlin 타입과 코덱을 생성합니다.
- 양쪽 모두 exhaustive match가 적용됩니다(Rust `enum`은 Kotlin `sealed interface`로 생성).
- 핸드셰이크 때 스키마 해시를 비교해서 불일치하면 초기화를 실패시킵니다.
- 수용 기준: Rust 스키마에 속성을 추가하고 Kotlin 인터프리터를 갱신하지 않으면 **빌드가 실패**합니다.
- codegen은 **자기가 컴파일된 크레이트 디렉터리에만 씁니다.** cargo가 실행 시점에 알려 준 크레이트 디렉터리가 그것과 다르면 파일을 하나도 만들지 않고 두 경로를 찍으며 0이 아닌 코드로 끝납니다. 규격과 근거는 §5.4.
- 수용 기준: `CARGO_MANIFEST_DIR`을 다른 디렉터리로 두고 codegen 바이너리를 실행하면 그 디렉터리는 비어 있는 채로 남고, 종료 코드가 0이 아니며, 메시지에 컴파일된 경로와 실행된 경로가 모두 나옵니다.

근거(2026-09-22):
- `generated_protocol.rs`의 `fr7_generated_kotlin_matches_schema`가 체크인된 `Protocol.gen.kt`를 생성기 출력과 바이트 단위로 비교합니다. 스키마를 고치고 코드젠을 돌리지 않으면 여기서 빨개집니다. 프로토콜 벡터도 같은 방식으로 `fr7_generated_vectors_match_schema`가 붙잡습니다.
- `interpreter_exhaustiveness.rs`가 두 번째 고리를 봅니다. 생성기가 내놓는 `WidgetKind`, `PropertyKind`, `Modifier`, `Mutation`의 모든 변형이 인터프리터의 해당 `when`에 이름으로 나와 있어야 합니다. Kotlin의 exhaustive `when`이 원래 하던 일이지만 `else` 한 줄이면 사라지므로, `else`로 바꿔도 빨개지도록 Rust 테스트가 대신 확인합니다. iOS가 같은 파일을 심링크로 쓰는지도 같은 파일이 봅니다.
- 실제로 확인했습니다: `PropertyKind`에 변형을 하나 더하고 인터프리터를 그대로 두면 `fr7_every_property_in_the_schema_has_an_arm_in_the_interpreter`가 실패하고, `PropertyKind.Progress` 팔을 `else -> false`로 바꿔도 같은 테스트가 실패합니다.
- 체크아웃 경계: `codegen_tree.rs`가 codegen 바이너리를 다른 `CARGO_MANIFEST_DIR`로 실행해서 그 디렉터리가 비어 있는 채로 남는지, 종료 코드와 메시지가 두 경로를 말하는지 확인합니다.
- 스키마 해시: `boundary_hardening.rs`의 `nfr7_init_with_a_wrong_schema_hash_returns_a_status`가 불일치 핸드셰이크에서 `init`이 실패 상태를 돌려주는지 보고, iOS 쪽은 `ProtocolBufferTest.pr4_handshake_carries_the_schema_hash_the_host_checks`가 Host가 검사하는 그 해시를 핸드셰이크에 싣는지 봅니다.

### FR-8 LazyColumn 윈도잉 (`Done`)
- Host는 아이템 총 개수와 안정적인 key를 알립니다.
- Renderer는 보이는 범위를 `RangeRequested`로 요청하고, Host는 **요청받은 구간만 정확히** 생성합니다. Host가 구간을 넓히지 않습니다.
- **선읽기 버퍼는 Renderer가 소유합니다.** Renderer가 가시 범위에 버퍼를 더한 값을 `start`, `count`로 보냅니다. 스크롤 위치가 Renderer에 있으므로(D5) 얼마나 미리 읽을지 아는 쪽도 Renderer입니다. Host가 따로 버퍼를 더하면 Renderer는 받은 서브트리가 전체 목록의 몇 번째 자리에 놓이는지 알 수 없습니다. `start`가 곧 첫 아이템의 전역 인덱스라는 것이 이 규칙의 핵심입니다.
- 아이템 식별: Host가 아이템마다 `Box` 래퍼 노드를 만들고 `item_key`(문자열)를 실어 보냅니다. Renderer는 그 값을 Compose `LazyColumn`의 key로 씁니다.
- Renderer는 `item_count`개짜리 실제 Compose `LazyColumn`을 그립니다. 전역 인덱스 `i`는 `i - start`번째 자식으로 그리고, 구간 밖은 빈 자리로 둡니다. 그래서 스크롤 막대와 스크롤 거리가 전체 목록 기준으로 맞습니다.
- 와이어: `RangeRequested`는 이벤트 태그 7(24바이트, `start: u32`, `count: u32`)입니다. Host는 `item_count`, `item_key`, `on_range_requested` 속성으로 선언합니다.
- **목록은 뷰포트입니다.** Host가 높이를 정해주지 않았다면(`height`, `size`, `fill_max_height`, 그리고 세로로 쌓는 부모 아래의 `weight`) Renderer는 목록에 주어진 높이를 채웁니다. 자기 아이템 높이로 줄어든 목록은 다시 커질 수 없습니다. 요청하는 구간이 지금 높이로 결정되기 때문입니다. `Row` 아래의 `weight`는 너비의 몫이므로 높이를 정하지 않습니다(13.4).
- **크기가 0인 아이템은 목록의 끝이 아니라 빈 자리입니다.** 보이는 자리는 실제로 자리를 차지하는 것만 셉니다. 화면에 있는 윈도우가 아무것도 그리지 않으면 그 윈도우를 그대로 둡니다. 그러지 않으면 크기 0 아이템이 모두 보이는 것으로 보고되어 컬렉션 전체 크기의 구간을 요청하고, 두 윈도우가 프레임마다 서로를 대체하면서 아무것도 그려지지 않습니다.
- 수용 기준: 아이템 10,000개 목록에서 생성된 노드 수가 가시 범위와 버퍼에 비례합니다. **(통과: Host 측은 가시 20 + 버퍼 4 요청에 아이템 28개. Renderer 측은 2026-09-22 확인, `fr8_the_visible_range_is_requested_and_only_that_window_exists`와 `fr8_the_request_widens_the_visible_range_by_the_buffer`와 `fr8_the_window_is_drawn_at_the_requested_global_offset`이 창 바깥이 존재하지 않는 것까지 봅니다)**
- 수용 기준: 화면 높이를 채우는 `Row` 안의 `LazyColumn`은 `weight`만 받은 경우에도 Row가 주는 높이를 채웁니다. 아이템이 모두 크기 0인 목록은 한 윈도우에 정착하고 경계 호출을 되풀이하지 않습니다. **(2026-09-21 통과)**

### FR-9 스트리밍 텍스트 (`Done`)
긴 텍스트가 점진적으로 늘어나는 경우를 위해 Text 노드에 `AppendText` 명령을 둡니다(태그 8, 16바이트). 전체 문자열이 아니라 늘어난 꼬리만 보냅니다. Host는 추가분을 모아 프레임당 노드별 1건으로 flush하며, flush 지점은 `render_frame`입니다.
- 수용 기준: 초당 100회 추가되는 스트리밍 중에도 스크롤과 입력이 끊기지 않습니다. **(통과: Host 측은 36KB 텍스트에서 배치 64바이트 미만, 스트리밍 프레임 p99 125ns. Renderer 측은 2026-09-22 확인, `fr9_only_the_tail_is_sent_and_the_node_keeps_what_it_had`와 `fr9_append_text_grows_the_node_without_recomposing_anything_else`가 꼬리만 받아 붙이고 형제를 다시 그리지 않는 것을 봅니다)**

### FR-12 이벤트 소비(consume) (`Agreed`)
Dioxus 0.7의 이벤트 핸들러는 반환값이 없습니다. 그래서 핸들러가 **이벤트 객체에 소비 표시를 남기고**, 경계가 그 값을 읽어 `MutationBatch.result`로 돌려줍니다. 웹의 `preventDefault()`, Compose의 `PointerInputChange.consume()`과 같은 모델입니다.

```rust
rsx! {
    TextField {
        on_key_down: move |event| {
            if event.key() == Key::Enter && !event.shift_key() {
                submit();
                event.consume();   // Renderer의 onKeyEvent가 true를 반환합니다
            }
        }
    }
}
```

Renderer 측 규칙:
- `Modifier.onKeyEvent`에 연결합니다. `onPreviewKeyEvent`는 전역 단축키처럼 가로채야 하는 경우에만 씁니다.
- **IME 조합 중에는 키 이벤트를 Host로 보내지 않습니다.** 조합 중의 Enter는 제출이 아니라 조합 확정입니다. 이를 어기면 한글 입력 중 Enter에서 조합 중이던 글자가 사라진 채 제출됩니다(FR-5).
- 포인터 이벤트도 같은 방식으로 `PointerInputChange.consume()`에 대응시킵니다.

Host 측 규칙:
- 핸들러 실행 중에 Renderer를 동기로 호출하지 않습니다(재진입 금지). 상태만 바꾸고 프레임 요청으로 넘깁니다.
- 수용 기준: 멀티라인 TextField에서 Enter는 제출되고 줄바꿈이 생기지 않으며, Shift+Enter는 줄바꿈만 생기고 제출되지 않습니다. 한글 조합 중 Enter는 조합만 확정합니다.

### FR-10 Modifier 값 모델 (`Done`)
Modifier는 값 리스트로 직렬화합니다. 예: `[Padding(16), FillMaxWidth, Background(argb), Clickable(handler_id)]`. Renderer는 이를 `Modifier` 체인으로 재구성합니다.
- 수용 기준: 스키마의 모든 Modifier 변형이 인코딩 후 디코딩해도 같습니다(`fr10_every_modifier_variant_in_the_vector_round_trips`). **(통과)**
- 수용 기준: 값 리스트가 순서대로 Compose 체인으로 다시 서고, 순서가 결과에 반영됩니다(`fr10_modifier_list_rebuilds_the_compose_chain`). **(통과)**

기준을 2026-09-22에 적었습니다. 그 전까지 이 항목에는 수용 기준이 하나도 없었고, 기준 없는 요구사항은 검증할 것이 없어서 영원히 `Agreed`에 머무릅니다. 두 테스트는 그 전부터 있었습니다.

### FR-13 디자인 프리미티브 (`Done`)
위젯만으로는 디자인을 할 수 없습니다. 스키마에 **값 모델**이 필요합니다. 값은 고정 레이아웃 레코드(PR-4)를 넘어야 하므로, Modifier 한 변형이 쓸 수 있는 공간은 `(tag: u16, first: u64, second: u64)`뿐입니다. 아래 프리미티브는 모두 이 한도 안에 들어갑니다.

원칙: **역할(role)을 우선하고 리터럴은 탈출구로 둡니다.** 역할은 FR-14의 디자인 시스템이 해석하고, 리터럴은 그대로 그립니다.

#### 13.1 색: `Color`와 `ColorRole`, 그리고 `Paint`
- `Color`는 `u32` ARGB 한 개입니다(`#[repr(transparent)]`). 그라데이션과 이미지 브러시는 넣지 않습니다.
- `ColorRole`은 의미 슬롯입니다: `Primary`, `OnPrimary`, `Secondary`, `OnSecondary`, `Surface`, `OnSurface`, `SurfaceVariant`, `OnSurfaceVariant`, `Background`, `OnBackground`, `Outline`, `OutlineVariant`, `Error`, `OnError`, `SurfaceContainer`, 그리고 13.1-2의 강조색 계열 8개와 13.1-3의 코드 색 역할 22개.
- `Paint`는 둘 중 하나입니다: `Paint::Role(ColorRole)` 또는 `Paint::Literal(Color)`. `u64` 하나로 인코딩합니다(상위 32비트 = 종류, 하위 32비트 = 값).
- 색을 받는 자리는 전부 `Paint`를 씁니다. 색 표현이 스키마에 두 번 등장하지 않게 하기 위해서입니다.
- 수용 기준: `Modifier::Background(Paint::Role(ColorRole::Surface))`와 `Modifier::Background(Paint::Literal(Color::rgb(0x1B1B1F)))`가 같은 레코드 길이로 왕복하고, 디코드 결과가 입력과 같습니다.

#### 13.1-2 세 번째 강조색과 강조 컨테이너
`Tertiary`, `OnTertiary`, `PrimaryContainer`, `OnPrimaryContainer`, `SecondaryContainer`, `OnSecondaryContainer`, `TertiaryContainer`, `OnTertiaryContainer`를 추가합니다(태그 16~23).

**이유는 색이 크롬이 아니라 내용인 화면입니다.** 강조색 둘과 페이지 하나로는 버튼과 막대와 제목까지는 말할 수 있지만, 과목마다 색이 다른 타일 격자, 기분 선택기, 비용 패널 옆의 합계 패널은 말할 수 없습니다. 그런 화면에는 **서로 친척으로 읽히고, 읽기 표면은 아니며, 본문이 얹힐 만큼 조용한** 채움이 몇 개 필요한데, 어휘에 그것을 가리키는 말이 없었습니다. 유일한 방법은 리터럴이었고, 리터럴은 디자인 시스템이 영영 보지 못하는 색입니다.

- **컨테이너는 강조색의 낮은 불투명도가 아닙니다.** 불투명도는 뒤에 무엇이 있는지 이미 알 때만 의미가 있고, 역할은 뒤에 무엇이 있는지 아무도 모르는 시점에 답해야 합니다. 그래서 컨테이너는 각각 표의 한 색이고, 각각 자기 잉크를 가집니다.
- 컨테이너 3개는 **본문 기준(4.5:1)** 으로 자기 잉크와 대비를 지킵니다. 채움 위에 이름표만 올리는 것이 아니라 문단이 얹히는 것이 존재 이유이기 때문입니다. `Tertiary`/`OnTertiary`는 다른 강조색과 같은 3:1입니다.
- 컨테이너 3개는 각각 `Background`와 구분되어야 합니다. 보이지 않는 틴트는 없는 패널입니다.
- **세 컨테이너끼리는 구분을 요구하지 않습니다.** Material 3의 기준 배색에서 primary와 secondary 컨테이너는 한 팔레트의 이웃한 톤이라 10단계 남짓 떨어져 있고, 그것은 공개된 배색이지 실수가 아닙니다. 한눈에 갈라지는 채움 세 개가 필요한 호출자는 tertiary 쌍을 씁니다.
- 수용 기준: 6개 디자인 시스템 × 2개 명암 전부에서 컨테이너 3쌍이 4.5:1을 넘고, `Tertiary`가 3:1을 넘으며, 컨테이너 3개가 `Background`와 구분됩니다. **(2026-09-21 통과: `tokens.rs`의 `fr14_on_roles_stay_readable`, `fr14_surface_variant_is_visible_against_the_page_and_the_surface`)**

#### 13.1-3 코드 색 역할 (`Draft`)

대화 화면은 코드를 보여 줍니다. 코드 블록과 diff입니다. 지금 Host 쪽 강조기(tree-sitter 같은)가 토큰을 칠하려면 `Paint::Literal`밖에 없고, 그러면 같은 색이 일곱 시스템과 두 명암에 똑같이 박힙니다. 다크 모드에서 라이트용 주석 회색이 사라지는 일이 정확히 그것입니다.

- **코드 색도 역할입니다.** `ColorRole`에 아래를 덧붙입니다(태그 24부터, 추가 전용). 강조기는 토큰의 종류를 역할로 바꿔 스팬(FR-26)의 `Paint::Role`로 보냅니다. 스팬 레코드는 이미 `Paint`를 싣고 있으므로 **글자색을 위해서는 와이어 형식이 바뀌지 않습니다.** 바뀌는 것은 역할 열거형과 토큰 표의 길이뿐입니다. 역할은 Rust 스키마(`schema.rs`)의 `ColorRole`에 정의하고 Kotlin 쪽은 codegen이 생성합니다(FR-7).

| 태그 | 역할 | 무엇을 칠하는가 | tree-sitter 이름 예 |
|---|---|---|---|
| 24 | `SyntaxKeyword` | 예약어, 제어 흐름 | `keyword`, `keyword.*` |
| 25 | `SyntaxString` | 문자열과 문자 리터럴 | `string`, `character` |
| 26 | `SyntaxComment` | 주석, 문서 주석 | `comment`, `comment.documentation` |
| 27 | `SyntaxNumber` | 숫자 리터럴 | `number`, `float` |
| 28 | `SyntaxConstant` | 불, null, 열거 멤버, 상수 | `constant`, `constant.builtin`, `boolean` |
| 29 | `SyntaxType` | 타입 이름, 트레잇, 인터페이스 | `type`, `type.builtin` |
| 30 | `SyntaxFunction` | 함수와 메서드 이름 | `function`, `function.method`, `constructor` |
| 31 | `SyntaxVariable` | 변수, 매개변수 | `variable`, `variable.parameter` |
| 32 | `SyntaxProperty` | 필드, 속성, 객체 키 | `property`, `field` |
| 33 | `SyntaxOperator` | 연산자 | `operator` |
| 34 | `SyntaxPunctuation` | 괄호, 구분자 | `punctuation.*` |
| 35 | `SyntaxTag` | 마크업 태그 이름 | `tag` |
| 36 | `SyntaxAttribute` | 마크업 속성 이름 | `attribute` |
| 37 | `SyntaxEscape` | 문자열 안의 이스케이프, 정규식 | `string.escape`, `string.regexp` |
| 38 | `SyntaxMacro` | 매크로, 데코레이터, 애너테이션, 전처리기 | `function.macro`, `attribute`(언어별), `preproc` |
| 39 | `DiffAdded` | 더해진 줄의 표시와 글자 | `diff.plus` |
| 40 | `DiffRemoved` | 지워진 줄의 표시와 글자 | `diff.minus` |
| 41 | `DiffModified` | 바뀐 줄의 여백 표시 | `diff.delta` |
| 42 | `DiffAddedContainer` | 더해진 줄의 배경 | |
| 43 | `DiffRemovedContainer` | 지워진 줄의 배경 | |
| 44 | `DiffAddedEmphasis` | 더해진 줄 안에서 실제로 바뀐 글자의 배경 | |
| 45 | `DiffRemovedEmphasis` | 지워진 줄 안에서 실제로 바뀐 글자의 배경 | |

- 고른 기준은 tree-sitter 강조 이름, VS Code의 의미 토큰 종류, TextMate 범위가 공통으로 나누는 것입니다. 셋 다 이것보다 잘게 나누지만, 잘게 나눈 이름은 대부분 이 중 하나로 접히고(`keyword.return`은 `SyntaxKeyword`), 일곱 시스템 × 두 명암의 표를 사람이 참조와 대조할 수 있는 크기여야 합니다(INTENT D14). 어디에도 맞지 않는 토큰은 칠하지 않고 본문 잉크(`OnSurface`)로 둡니다.
- **강조기와 언어 지식은 Host 쪽에 있습니다.** 마크다운(FR-26)과 같은 규칙입니다. 이름에서 역할로 가는 표는 Host 쪽 편의 함수이고, Renderer는 언어를 모릅니다.
- **줄 배경은 노드의 배경입니다.** diff 한 줄은 `Modifier::Background(Paint::Role(DiffAddedContainer))`를 단 줄이고, 줄 안의 글자 배경(`*Emphasis`)은 스팬의 배경 `Paint`가 싣습니다(FR-26).
- **대비 기준**: 문법 역할 열다섯(24-38)은 코드 블록이 놓이는 `SurfaceContainer` 위에서 본문 기준 4.5:1을 지킵니다. 주석도 예외가 아닙니다. 흐리게 보이는 것은 색조와 명도로 하되 읽을 수 있어야 하고, 읽을 수 없는 주석은 없는 주석입니다. `DiffAdded`/`DiffRemoved`는 자기 컨테이너 위에서 4.5:1을, 두 컨테이너 위의 `OnSurface`도 4.5:1을 지킵니다. `*Emphasis`는 같은 줄의 컨테이너와 구분되어야 합니다. 더해진 줄과 지워진 줄은 색만으로 구분하지 않습니다(줄 머리의 `+`, `-`는 Host가 그대로 둡니다). 적록 색각 이상에서 두 배경은 같아 보일 수 있기 때문입니다.
- **값은 디자인 시스템마다 다릅니다.** 각 시스템의 표가 그 플랫폼의 대표 코드 편집기 배색에서 옵니다(INTENT D14, 참조 이미지). Cupertino와 Liquid Glass는 Xcode의 기본 라이트·다크, Fluent는 VS Code의 Light+/Dark+, GNOME은 GtkSourceView의 Adwaita와 Adwaita-dark, Breeze는 KSyntaxHighlighting의 Breeze Light/Dark입니다.
- **Material 3과 Deepin은 공개된 코드 배색이 없으므로 그 시스템의 기반 색 역할에서 유도합니다.** INTENT D14의 예외이고, 유도 규칙은 아래와 같습니다. 같은 시스템, 같은 명암의 표에서 읽습니다.
  - 문법 역할은 기반 역할 하나의 값을 그대로 가져옵니다.

    | 코드 역할 | 가져오는 기반 역할 |
    |---|---|
    | `SyntaxKeyword`, `SyntaxTag` | `Primary` |
    | `SyntaxType`, `SyntaxAttribute`, `SyntaxMacro` | `Secondary` |
    | `SyntaxString` | `Tertiary` |
    | `SyntaxFunction` | `OnPrimaryContainer` |
    | `SyntaxProperty` | `OnSecondaryContainer` |
    | `SyntaxNumber`, `SyntaxConstant` | `OnTertiaryContainer` |
    | `SyntaxEscape` | `Error` |
    | `SyntaxVariable` | `OnSurface` |
    | `SyntaxComment`, `SyntaxOperator`, `SyntaxPunctuation` | `OnSurfaceVariant` |

  - `DiffRemoved`는 `Error`입니다. `DiffAdded`는 `Error`의 HCT 톤과 채도를 그대로 두고 색상(hue)만 145도(초록)로 돌린 색입니다. 두 시스템 모두 성공을 뜻하는 기반 역할이 없고, 지워진 줄과 더해진 줄이 같은 무게로 읽혀야 하기 때문입니다. `DiffModified`는 `Tertiary`입니다.
  - 컨테이너는 짝이 되는 잉크(`DiffAdded`, `DiffRemoved`)를 `SurfaceContainer` 위에 라이트 12%, 다크 20% 불투명도로 겹친 결과의 불투명한 색이고, `*Emphasis`는 같은 방법으로 라이트 30%, 다크 40%입니다.
  - 이렇게 얻은 값이 위의 대비 기준을 지키지 못하면, 색상과 채도는 그대로 두고 HCT 톤만 기준을 넘는 첫 값까지 옮깁니다. 라이트에서는 어둡게, 다크에서는 밝게입니다. 다른 기반 역할에서 온 두 문법 역할이 같은 색이 되는 것은 허용하고, 같은 기반 역할에서 온 역할끼리는 원래 같습니다.
  - 유도는 빌드 시점에 Rust의 토큰 표를 만들 때 한 번 합니다. 값은 다른 다섯 시스템과 같이 표에 적힌 색이고, Renderer가 실행 중에 계산하지 않습니다.
- **앱이 덮어쓸 수 있습니다.** 14.10의 팔레트가 이 역할들에도 똑같이 적용됩니다. 앱이 자기 코드 배색을 가지려면 이 역할들을 팔레트에 넣으면 됩니다.
- 비용: `ColorRole`이 23개에서 45개로, 토큰 표가 시스템당 23행에서 45행으로 늡니다. 빌드 시점에 Renderer 바이너리로 들어가는 표이고 런타임에 경계를 넘지 않습니다(14.4). Rust의 `DesignTokenTable.colors`가 `[ColorToken; 45]`가 되며, `tokens.rs`와 `design-systems/`의 Kotlin 표를 비교하는 테스트가 새 행도 비교합니다.

수용 기준:
1. 일곱 시스템 × 두 명암에서 문법 역할 열다섯이 `SurfaceContainer` 위에서 4.5:1을 넘고, diff 잉크가 자기 컨테이너 위에서 4.5:1을 넘으며, `*Emphasis`가 같은 줄의 컨테이너와 구분됩니다. Rust 표 테스트로 확인합니다.
2. Rust 코드 한 토막을 강조해 보낸 `Text`가 라이트와 다크에서 서로 다른 색으로 그려지고, 와이어에는 리터럴 색이 하나도 없습니다(스팬의 `Paint` 종류가 전부 `Role`).
3. 같은 코드 블록이 최소 두 시스템에서 다른 키워드 색으로 그려집니다. 일곱 번 같은 색이면 실패입니다.
4. 역할을 추가해도 위젯 정의와 속성·Modifier 스키마가 바뀌지 않습니다(FR-14.1 회귀 검사).
5. Material 3와 Deepin의 코드 역할 값이 위의 유도 규칙으로 그 시스템의 기반 역할에서 다시 계산한 값과 같습니다. Rust 표 테스트로 확인합니다.

#### 13.2 타이포그래피
- `TypeRole`은 9단 사다리입니다: `Display`, `Headline`, `Title`, `Subtitle`, `Body`, `BodyStrong`, `Label`, `Caption`, `Mono`. 세 디자인 시스템의 타입 스케일이 모두 이 사다리에 대응합니다.
- `Text`의 속성으로 개별 재정의를 둡니다. 각각 독립된 `SetProp`이라서 FR-4의 변경분 전송이 유지됩니다.
  - `type_role`, `font_size`(sp), `font_weight`(100~900), `line_height`, `letter_spacing`, `color`(`Paint`), `text_align`(`Start|Center|End|Justify`), `max_lines`, `overflow`(`Clip|Ellipsis|Visible`)
- `type_role`만 지정한 Text는 디자인 시스템이 정한 크기·굵기·행간·자간을 그대로 씁니다. 재정의 속성이 있으면 그 축만 덮어씁니다.
- **서체는 그 기계의 UI 서체입니다.** 디자인 시스템이 정하지 않고 Host는 더더욱 정하지 않습니다. Renderer가 플랫폼의 UI 서체를 이름으로 찾아 쓰고, 없으면 툴킷 기본으로 떨어집니다. macOS는 SF, Windows는 Segoe UI, GNOME은 Adwaita Sans나 Cantarell, KDE는 Noto Sans입니다.
  - **디자인 시스템별로 가르지 않습니다.** macOS에서 Material 3를 고른 화면도 SF로 나옵니다. 창 하나가 그 기계의 다른 창들과 같은 글자로 읽히는 것이, 그 화면이 Roboto로 읽히는 것보다 중요합니다. 어느 서체를 쓸지는 디자인 시스템의 축이 아니라 기계의 성질입니다.
  - **번들하지 않습니다.** 설치된 것 중에서 고르므로 배포 용량이 늘지 않고 라이선스가 걸리지 않습니다.
  - 사다리(크기·굵기·행간·자간)는 그대로 디자인 시스템의 것입니다. 바뀌는 것은 그 사다리를 무엇으로 새기느냐뿐입니다.
  - 2026-09-24까지 일곱 시스템이 전부 Helvetica로 그려지고 있었습니다. `FontFamily.Default`가 macOS에서 그것으로 풀리기 때문이고, 그래서 화면이 네이티브 컨트롤이 아니라 그려 놓은 그림처럼 읽혔습니다. 글자 가장자리 전이 폭을 재면 우리 1.10픽셀, 네이티브 1.27픽셀로 흐린 것이 아니었고, 다른 서체였습니다.
- 수용 기준: `Text { type_role: Title }` 한 개는 `SetProp` 1건만 보냅니다. `font_size`만 바꾸면 추가 `SetProp` 1건만 전송됩니다. 그리고 어느 디자인 시스템으로 그리든 본문 서체가 그 기계의 UI 서체입니다.

#### 13.3 모양: `Shape`와 `Border`
- `Modifier::Shape { top_start, top_end, bottom_end, bottom_start }`, f32 4개를 `u64` 2개에 담습니다.
- `Modifier::ShapeRole(ShapeRole)`, `None|ExtraSmall|Small|Medium|Large|Full`. 실제 반지름은 디자인 시스템이 정합니다. Material 3의 12dp, HIG의 연속 곡률 느낌, Fluent의 4dp가 여기서 갈립니다.
- `Modifier::Border { width, paint }`, `first` 하위 32비트에 너비, `second`에 `Paint`.
- 테두리와 모양은 서로 독립입니다. Renderer는 `ShapeRole`/`Shape` 중 마지막에 적용된 것을 클립과 테두리 모두에 씁니다.

#### 13.4 간격과 배치
- `SpaceRole`: `None|Xs|Sm|Md|Lg|Xl|Xxl`. 밀도가 디자인 시스템마다 다른 부분이라 리터럴 dp보다 역할이 먼저입니다.
- `Modifier::PaddingRole(SpaceRole)`, 기존 `Modifier::Padding(f32)`는 유지합니다.
- `Modifier::PaddingEach { start, top, end, bottom }`, f32 4개.
- `Modifier::Weight(f32)`, `RowScope`/`ColumnScope`의 weight입니다. 부모 데이터이므로 자식을 쌓는 레이아웃만 적용할 수 있고, 자식을 세로로 쌓는 컨테이너(`Card`, `Surface`, `ScrollColumn`)도 `Column`과 같게 적용합니다. 몫이 되는 축은 부모가 쌓는 축입니다: `Column` 아래에서는 높이, `Row` 아래에서는 너비이며, `Box`처럼 쌓지 않는 부모 아래에서는 어느 축도 정하지 않습니다.
- 수용 기준: 높이가 정해진 `Surface` 안에서 `weight` 1과 3을 받은 자식 둘이 높이를 1:3으로 나눕니다. **(2026-09-21 통과)**
- `Column`/`Row` 속성: `arrangement`(`Start|Center|End|SpaceBetween|SpaceAround|SpaceEvenly`), `spacing`(f32 dp) 또는 `space_role`, `alignment`(교차축 정렬).
- `Box` 속성: `alignment`(9점 정렬).
- 수용 기준: 위 속성/Modifier가 전부 `(tag, u64, u64)` 안에 들어가고, 인코딩 후 디코딩 결과가 입력과 같습니다.

#### 13.5 고도(elevation)
- `Modifier::Elevation(f32 dp)` 하나만 둡니다. **그림자를 어떻게 그릴지는 디자인 시스템의 규칙입니다.** Material 3는 톤 상승 + 그림자, HIG는 넓고 옅은 그림자, Fluent는 층 그림자 + 가는 스트로크로 같은 값을 다르게 해석합니다.
- 그림자 색·오프셋·블러를 Host가 지정하는 경로는 두지 않습니다. 두면 디자인 시스템이 값만 받는 껍데기가 됩니다.

#### 13.6 스크롤 컨테이너
- `ScrollColumn`: 콘텐츠 전체를 구성하고 세로 스크롤만 붙입니다. 스크롤 위치는 Renderer가 소유합니다(D5).
- `LazyColumn`: FR-8의 윈도잉 프로토콜을 씁니다.
- `LazyRow`: FR-8의 윈도잉 프로토콜을 가로 축에 그대로 씁니다. 프로토콜은 축과 무관하므로 두 번째 윈도잉 규약을 만들지 않습니다.
- 지연되지 않는 가로 스크롤 컨테이너(`ScrollRow`)를 넣습니다. 한동안 "샘플 어디에도 필요가 없다"는 이유로 빼 두었는데, 그것은 샘플을 그렇게 쓰지 않도록 쓴 쪽의 이야기이지 어휘의 이야기가 아닙니다. `ScrollColumn`이 있고 `LazyRow`가 있는데 이것만 없는 것이 설명되지 않습니다. `ScrollColumn`의 축만 바꾼 것이고, 새 프로토콜은 없습니다.

#### 13.7 넣지 않은 것과 이유
| 제외 | 이유 |
|---|---|
| ~~그라데이션, 이미지 브러시~~ | **철회했습니다.** 값을 레코드에 싣지 못한다는 것은 맞지만, 그것이 기능을 뺄 이유는 아닙니다. FR-16이 이미 리소스를 id로 참조하는 길을 냈고, 브러시도 같은 길을 씁니다. FR-23 |
| ~~폰트 패밀리, 커스텀 폰트~~ | **철회했습니다.** 같은 이유입니다. 이름이 아니라 등록된 에셋의 id를 보내면 존재 검증이 런타임으로 밀리지 않습니다. FR-23 |
| 아이콘·이미지 위젯 | 에셋 전달 프로토콜이 따로 필요합니다. 별도 요구사항으로 분리합니다 |
| 애니메이션 스펙(duration, easing) | **Host가 값을 보내는 것은 여전히 넣지 않습니다.** 13.5와 같은 이유입니다. 없던 것은 모션을 말할 방법 자체였고, 그것은 다른 프리미티브와 같이 역할로 말합니다. FR-24 |
| ~~블러/머티리얼(HIG vibrancy)~~ | **철회했습니다.** "세 시스템 공통 축이 아니다"는 것은 역할을 어떻게 해석할지가 시스템마다 다르다는 뜻이지, 말할 수 없다는 뜻이 아닙니다. 다른 모든 프리미티브가 그렇습니다. FR-23.3 |
| 리플 설정 | 디자인 시스템의 규칙입니다(FR-14). 값을 Host가 주면 13.5와 같은 이유로 무너집니다 |
| Host가 보내는 토큰 테이블 | FR-14에서 해석 위치를 Renderer로 정했습니다. 테이블을 보내면 그 결정이 뒤집힙니다 |

#### 13.8 와이어 태그 배정
프리미티브를 구현하려면 고정 태그가 필요합니다. 태그는 한 번 배정하면 재사용하지 않고 뒤에만 덧붙입니다(PR-4).

역할 enum은 모두 1부터 시작합니다. 0은 "보내지 않음"을 뜻하므로 역할 값으로 쓰지 않습니다.

| enum | 값 |
|---|---|
| `ColorRole` | `Primary=1, OnPrimary=2, Secondary=3, OnSecondary=4, Surface=5, OnSurface=6, SurfaceVariant=7, OnSurfaceVariant=8, Background=9, OnBackground=10, Outline=11, OutlineVariant=12, Error=13, OnError=14` |
| `TypeRole` | `Display=1, Headline=2, Title=3, Subtitle=4, Body=5, BodyStrong=6, Label=7, Caption=8, Mono=9` |
| `ShapeRole` | `None=1, ExtraSmall=2, Small=3, Medium=4, Large=5, Full=6` |
| `SpaceRole` | `None=1, Xs=2, Sm=3, Md=4, Lg=5, Xl=6, Xxl=7` |
| `TextAlign` | `Start=1, Center=2, End=3, Justify=4` |
| `TextOverflow` | `Clip=1, Ellipsis=2, Visible=3` |
| `Arrangement` | `Start=1, Center=2, End=3, SpaceBetween=4, SpaceAround=5, SpaceEvenly=6` |
| `Alignment` | `TopStart=1, TopCenter=2, TopEnd=3, CenterStart=4, Center=5, CenterEnd=6, BottomStart=7, BottomCenter=8, BottomEnd=9` |
| `ButtonVariant` | `Filled=1, Tonal=2, Outlined=3, Text=4` |

`Paint`는 `u64` 하나입니다. 상위 32비트가 종류(`Role=1`, `Literal=2`)이고 하위 32비트가 값(`ColorRole` 태그 또는 ARGB)입니다.

위젯 태그: 1-9는 13.8 시점의 어휘이고, 10-25는 FR-15.2가 배정합니다.

Modifier 태그(기존 `Empty=0`~`Clickable=8` 뒤에 덧붙입니다):

| 태그 | Modifier | `first` | `second` |
|---|---|---|---|
| 9 | `PaddingRole` | `SpaceRole`(u32) | 없음 |
| 10 | `PaddingEach` | `start`\|`top` (f32 2개) | `end`\|`bottom` (f32 2개) |
| 11 | `Weight` | `value`(f32) | 없음 |
| 12 | `Shape` | `top_start`\|`top_end` | `bottom_end`\|`bottom_start` |
| 13 | `ShapeRole` | `ShapeRole`(u32) | 없음 |
| 14 | `Border` | `width`(f32) | `Paint`(u64) |
| 15 | `Elevation` | `dp`(f32) | 없음 |

`f32` 2개를 `u64` 하나에 담을 때는 하위 32비트가 첫 번째 값입니다.

**기존 `Modifier::Background`(태그 7)의 필드를 `argb: u32`에서 `paint: u64`로 바꿉니다.** 13.1의 "색을 받는 자리는 전부 `Paint`"를 지키려면 예외를 둘 수 없습니다. 레코드 길이는 그대로이고 스키마 해시만 바뀝니다. M0가 아직 배포되지 않았으므로 태그를 새로 따지 않고 자리를 바꿉니다.

Property 태그(기존 `OnRangeRequested=12` 뒤에 덧붙입니다): `TypeRole=13, FontSize=14, FontWeight=15, LineHeight=16, LetterSpacing=17, Color=18, TextAlign=19, MaxLines=20, Overflow=21, Arrangement=22, Spacing=23, SpaceRole=24, Alignment=25, Variant=26`.

#### 13.9 파괴적 동작과 구분선
목록과 폼을 실제로 그려 보면 13.1~13.8 어휘로는 두 가지를 말할 수 없습니다. 둘 다 새 위젯이나 새 태그 없이 해결합니다.

- **파괴적 동작**: 삭제 버튼은 세 시스템 모두에서 "평범한 버튼인데 라벨만 경고색"입니다. `ButtonVariant`를 하나 더 늘리는 대신, `Button`이 이미 있는 `Color`(태그 18) 속성을 받습니다. 노드가 색을 지정하면 그 값이 variant가 정한 라벨 색을 덮고, 지정하지 않으면 지금까지와 같습니다. 보내는 것은 여전히 `Paint::Role(ColorRole::Error)`이므로 실제 색조는 디자인 시스템이 정합니다.
  - 수용 기준: `fr13_button_label_colour_is_sent_as_a_role`.
- **구분선**: 묶인 목록은 행과 행 사이를 하이라인으로 나눕니다(HIG의 그룹 목록, Material의 Divider, Fluent의 층 스트로크). 애플리케이션 코드가 두께와 색을 들지 않는다는 것이 이 항목의 요점이고, 그 부분은 그대로입니다. 이름도 `Separator` 그대로입니다.

  **2026-09-21 정정**: 이 항목은 원래 "두께는 역할로 표현할 수 없는 상수이므로 라이브러리가 1dp를 들고, `Box` 하나로 그려서 위젯 태그를 늘리지 않는다"고 적혀 있었습니다. 15.2.4가 `Divider`를 태그 17로 넣으면서 그 근거가 둘 다 없어졌습니다. 태그는 이미 늘었고, 두께가 상수라는 전제도 틀렸습니다. Material의 divider와 Fluent의 층 스트로크와 HIG의 그룹 구분선은 두께도 색도 들여쓰기도 서로 다르며, 그것을 정하는 것이 디자인 시스템이 하는 일입니다(FR-14.1). 1dp를 라이브러리에 박아 두면 세 시스템 중 둘이 틀린 굵기로 그려집니다.

  따라서 `Separator`는 `Divider` 하나를 렌더링합니다. 같은 개념에 이름이 둘 있는 상태를 남기지 않되, 애플리케이션 코드가 이미 쓰고 있는 이름은 유지합니다.
  - 수용 기준: `fr13_separator_is_one_divider_so_the_design_system_sets_its_weight`.

#### 13.10 비활성 상태는 보여야 합니다

`Enabled`(태그 3)는 M0부터 있었지만 Renderer에서 하는 일이 클릭을 막는 것뿐이었습니다. 그래서 눌리지 않는 버튼이 눌리는 버튼과 픽셀 단위로 같게 그려졌고, 사용자는 눌러 보고 나서야 알 수 있었습니다. 선택 컨트롤 쪽은 `ControlsStyle.disabledAlpha`로 이미 흐려지고 있었으므로, 같은 개념이 위젯에 따라 있다가 없다가 하는 상태이기도 했습니다.

- **`Enabled = false`는 입력을 막고 동시에 흐려집니다.** 여섯 시스템 모두 비활성 컨트롤을 흐리게 그립니다. 얼마나 흐린지는 디자인 시스템의 값이고(FR-14.1), `ComponentRules`가 컨트롤에 대해 이미 답하고 있는 것과 같은 이름으로 버튼에 대해서도 답합니다. Host가 지정할 속성은 없습니다.
- **새 태그도 새 이벤트도 없습니다.** 이미 보내고 있는 속성을 Renderer가 마저 읽는 것입니다.
- 흐려지는 것은 컨테이너와 라벨 전체입니다. variant마다 비활성 색을 따로 두면 디자인 시스템 하나가 답해야 할 값이 네 개로 늘고, 일곱 번째 시스템이 들어올 때 그만큼 늘어납니다.
  - 수용 기준: `fr13_a_disabled_button_is_drawn_faded_in_every_design_system`.

### FR-14 디자인 시스템과 테마 모드 (`Agreed`)
디자인 시스템은 **토큰 집합 + 컴포넌트 스타일 규칙**의 한 쌍입니다. 속성을 모아 놓은 것이 아닙니다.

지원 대상은 두 단계로 나눕니다.

| 단계 | 디자인 시스템 |
|---|---|
| 1단계 | Material 3, Cupertino, WinUI/Fluent 2 |
| 2단계 | GNOME 50, KDE Breeze, Deepin |
| 3단계 | Liquid Glass |

2단계는 1단계가 동작한 뒤에 추가합니다. 14.1의 추상화가 성립하면 각각 `DesignSystem` 변형 1개와 Renderer 측 테이블 1개, 규칙 구현 1개로 끝나야 하며, 이것이 그 추상화의 실제 검증입니다.

2단계 세 시스템은 구현되어 실행 경로 위에 있습니다. 세 개 모두 `DesignSystem` 변형 1개(태그 4, 5, 6 추가, 기존 태그는 그대로), `tokens.rs`의 토큰 테이블 1개, Renderer의 `ComponentRules` 구현 1개로 끝났고 위젯·속성·Modifier·와이어 포맷은 움직이지 않았습니다. 값은 `design-systems/`의 Kotlin 구현에서 그대로 옮겨 왔습니다.

사본이 둘인데 아무것도 비교하지 않으면 조용히 갈라집니다. 실제로 두 행이 갈라졌습니다. GNOME의 어두운 보조 강조색 위 글자색은 한쪽이 흰색, 다른 쪽이 거의 검정이었고, Breeze의 어두운 패널 색은 한쪽이 뷰 색, 다른 쪽이 `SurfaceVariant` 회색이었습니다. 이제 Rust 쪽 테스트가 `tokens.rs`의 테이블과 `design-systems/`의 Kotlin 리터럴 테이블을 직접 비교합니다. 색, 반경, 간격이 하나라도 다르면 실패합니다. **2026-10-03(INTENT D19): 디자인 시스템은 `compose-multiplatform-core-extended`로 옮겨 갑니다.** 옮긴 뒤 이 비교의 상대는 이 저장소가 고정한 포크 커밋의 표이고, 비교 자체는 그대로 남습니다.

3단계는 Liquid Glass 하나이며, 같은 방식으로 끝났습니다. `DesignSystem` 변형 1개(태그 7), 토큰 테이블 1개, `ComponentRules` 구현 1개입니다. **Cupertino를 대체하지 않고 그 옆에 놓습니다**(INTENT D13). 두 언어는 서로 다른 화면을 만들며 둘 다 지금 쓰입니다.

#### 14.1 추상화
- 위젯은 **역할만 내보냅니다**(FR-13의 `ColorRole`, `TypeRole`, `ShapeRole`, `SpaceRole`, 그리고 `ButtonVariant` 같은 컴포넌트 변형).
- 디자인 시스템은 Renderer 안에 있는 **토큰 테이블 + 컴포넌트 규칙 구현** 한 쌍입니다.
- 따라서 **네 번째 디자인 시스템을 추가할 때 위젯 코드, 속성, Modifier, 와이어 포맷은 건드리지 않습니다.** Rust `DesignSystem` enum에 변형 1개, Kotlin에 테이블 1개와 규칙 구현 1개를 더하면 끝입니다.
- 수용 기준: `DesignSystem`에 변형을 하나 추가했을 때 `widgets.rs`의 위젯 정의와 Modifier/Property 스키마가 변경되지 않습니다.

#### 14.1-2 Apple 자리는 둘입니다: Cupertino와 Liquid Glass

**이름은 `Cupertino`입니다.** HIG(Human Interface Guidelines)는 지침 문서이지 디자인 시스템의 이름이 아닙니다. Apple은 자사 디자인 언어에 공개된 제품명을 붙이지 않으므로, 크로스플랫폼 툴킷에서 Apple 스타일 위젯 집합을 가리키는 관례적 이름인 `Cupertino`를 씁니다.

**Cupertino가 그리는 것은 평면 어휘입니다.** 회색 그룹 배경 위의 흰 삽입 목록, 앞쪽 텍스트에서 들여쓴 머리카락 구분선, 오른쪽 꺾쇠, 큰 제목, 얇은 경계선의 하단 탭 바, `#007AFF` 파랑. `docs/references/design-systems/cupertino/`의 두 그림이 사양입니다. 이것은 폐기된 룩이 아니라 크로스플랫폼 앱이 "iOS처럼 보이게" 할 때 실제로 고르는 어휘이며, Flutter의 `cupertino` 위젯 집합과 `compose-cupertino`가 그리는 것과 같습니다.

**Liquid Glass는 별개의 시스템이고, 태그 7입니다**(14.1-4, INTENT D13). macOS 26과 iOS 26부터 Apple이 쓰는 언어이며, Cupertino를 대체하는 대신 그 옆에 놓입니다. 표현해야 하는 것은 다음과 같습니다.

- **재질(material)**: 컨트롤과 표면이 뒤 배경을 비춥니다. 불투명 채움이 아니라 반투명 레이어와 흐림입니다.
- **가장자리 하이라이트**: 광원을 받은 유리처럼 테두리 상단이 밝고 하단이 어둡습니다. 단색 1px 테두리와는 다릅니다.
- **동심 곡률**: 안쪽 요소의 모서리 반경이 바깥 컨테이너와 동심을 이루도록 계산됩니다. 고정된 반경 값 하나로는 표현되지 않습니다.
- **깊이**: 그림자보다 레이어의 겹침과 굴절로 깊이를 나타냅니다.

- 수용 기준
  1. `DesignSystem::LiquidGlass`로 그린 화면이 재질, 가장자리 하이라이트, 동심 곡률에서 Material 3 및 Fluent와 눈으로 구분되고, 같은 화면을 `DesignSystem::Cupertino`로 그린 것과도 구분됩니다.
  2. 배경이 바뀌면 그 위의 컨트롤 색이 따라 바뀝니다. 고정 색을 칠하고 끝내지 않습니다.
  3. 흐림을 지원하지 않는 환경에서도 읽을 수 있는 대체 표현이 있습니다(대비를 지키는 불투명 재질).
  4. 접근성 설정의 투명도 감소를 존중합니다.

**Renderer 구현 제약 (조사 완료, 2026-09-21)**

Compose Multiplatform은 Liquid Glass를 그릴 수 없습니다. JetBrains가 명시합니다([ios-liquid-glass](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html)). 효과는 **시스템이** 네이티브 SwiftUI `TabView`, `NavigationStack`, 툴바 API를 통해 그리며, Compose 앱이 채택하려면 Compose 콘텐츠를 **네이티브 SwiftUI 셸로 감싸야** 합니다. iOS 26 + Xcode 26 전용이고, Compose 쪽 API는 없습니다. 이 문서는 iOS만 다루므로 데스크톱에 대해서는 아무 말도 하지 않습니다.

따라서 요구사항을 둘로 나눕니다. 경계는 **흐리게 할 대상이 우리가 그린 것이냐**입니다.

1. **우리가 그릴 수 있는 것 (1.0 범위).** 뒤에 있는 것이 우리 앱 콘텐츠인 경우입니다. 동심 곡률, 가장자리 하이라이트(상단 밝고 하단 어두움), 레이어 겹침으로 표현하는 깊이, 앱 콘텐츠 위의 반투명 컨트롤 표면, 그리고 우리가 그린 콘텐츠에 대한 Compose 흐림. 이것들은 흉내가 아니라 실제 구현이며, iOS 26 화면처럼 보이게 하는 요소의 대부분입니다.
2. **창 뒤의 시스템 배경을 비추는 재질.** macOS에서는 `NSVisualEffectView`가 필요하고 그것은 Compose 표면 바깥의 플랫폼 경로입니다. 우리가 `NSApplication`을 직접 만들므로(INTENT D9-macOS) 그 경로에 닿을 수 있고, 닿기로 했습니다. FR-29입니다. "이론적으로 접근 가능하나 1.0 범위 밖"이라고 적어 두었던 것은 할 수 없다는 판단이 아니라 하지 않겠다는 판단이었고, 그것을 요구사항으로 정할 근거가 없었습니다. iOS에서 진짜 Liquid Glass는 SwiftUI 셸의 내비게이션 크롬에만 적용되며, 그 크롬은 Rust가 작성하는 UI가 아닙니다.

수용 기준 2의 "배경"은 **컨트롤 뒤의 앱 콘텐츠**를 뜻합니다. 창 뒤의 바탕화면이 아닙니다.

**흉내만 낸 반투명으로 "구현했다"고 표시하지 않습니다.** 1번 항목은 실제로 그리는 것이므로 이 규칙에 걸리지 않지만, 2번을 했다고 주장해서는 안 됩니다.

#### 14.1-5 포커스를 잃은 창의 유리는 유리가 아닙니다 (`Agreed`)

macOS에서 창이 키를 잃으면 그 창의 재질은 납작해집니다. 비침이 사라지고 그림자가 옅어지거나 없어지며, 뒤에 있던 것이 더 이상 비쳐 오지 않습니다. 여러 창을 띄워 둔 화면에서 **어느 창이 지금 내 것인지**를 말하는 것이 이 차이이고, 시스템의 재질은 전부 이렇게 동작합니다. 포커스를 잃고도 계속 비치고 계속 떠 있는 창은 그 화면에서 유일하게 그러지 않는 창이 됩니다.

- **이미 있는 대체 경로를 그대로 씁니다.** 투명도를 줄여 달라는 설정과 블러를 쓸 수 없는 환경이 이미 불투명 대체로 내려가고 그리기 비용까지 함께 사라지는 길을 갖고 있습니다(14.1-2). 포커스를 잃은 창은 세 번째 조건이고, 답은 같습니다: 불투명 대체를 칠하고, 배경을 블러하지 않으며, 떠 있는 표면의 그림자를 그리지 않습니다.
- **그래서 비용도 같이 사라집니다.** 가려져 있거나 쓰지 않는 창이 매 프레임 블러 패스를 도는 것은 그 자체로 잘못입니다.
- **창이 있는 플랫폼의 이야기입니다.** iOS와 Android와 브라우저에는 키 창이라는 개념이 없고, 그곳에서는 항상 활성으로 답합니다.
- **Apple 계열만의 관습이 아닙니다.** Windows와 Linux의 창도 비활성일 때 캡션과 재질이 가라앉습니다. 규칙은 재질을 쓰는 모든 시스템에 적용되고, 각 시스템이 자기 불투명 대체로 내려갑니다.

수용 기준:

1. 유리를 쓴 창이 키를 잃으면 그 창의 유리 표면이 불투명 대체로 그려지고, 떠 있는 표면의 그림자가 사라집니다.
2. 키를 되찾으면 둘 다 돌아옵니다.
3. 비활성인 동안 블러 패스가 돌지 않습니다.
4. 창이 없는 플랫폼에서는 이 조건이 항상 활성이고, 그곳의 그림은 이 변경 전과 같습니다.

#### 14.1-3 Liquid Glass는 일곱 번째 디자인 시스템입니다

와이어 태그는 **`LiquidGlass = 7`**입니다. 참조 문서(`docs/references/design-systems/README.md`)가 Liquid Glass를 세 번째로 세지만 태그는 추가만 허용되므로(20.1) Deepin 다음에 붙습니다. 목록의 순서와 태그 번호는 서로 다른 것입니다.

**`adaptive`의 macOS와 iOS 자리는 Liquid Glass입니다**(INTENT D13). `adaptive`가 약속하는 것은 "앱이 고르지 않고 기계가 실제로 쓰는 것을 따른다"이고, macOS 26과 iOS 26의 기계가 쓰는 것은 Liquid Glass입니다. Cupertino는 사라지지 않고, 이름을 대고 고릅니다.

```rust
LaunchBuilder::new().with_theme(Theme::unified(DesignSystem::Cupertino)).launch(app);
```

샘플에서는 `DXC_DESIGN=liquidglass`(또는 `liquid-glass`)로 고릅니다(14.3).

#### 14.1-4 유리가 붙는 자리는 창 크기 클래스가 정합니다

macOS 26과 iOS 26은 같은 재질을 다른 범위에 씁니다. macOS는 크롬(사이드바, 툴바, 타이틀바 영역)만 유리이고 그 아래 문서 콘텐츠는 불투명합니다. iOS는 떠 있는 컨트롤 표면까지 유리입니다. **끝에서 끝까지 유리인 창은 두 쪽 모두에서 틀립니다.**

그래서 컴포넌트 규칙이 FR-20의 창 크기 클래스를 읽습니다.

| 크기 클래스 | 유리로 그리는 `ContainerRole` | 불투명으로 그리는 역할 |
|---|---|---|
| Compact | `TopAppBar`, `Menu`, `Dialog`, `Tooltip`, `Card`, `Surface` | 없음 |
| Medium, Expanded | `TopAppBar`, `Menu`, `Dialog`, `Tooltip` | `Card`, `Surface` |

크기 클래스는 Renderer가 이미 측정하는 값이므로(FR-20) 새 경계도 새 속성도 필요하지 않습니다. Host는 여전히 역할만 보냅니다.

**페이지 색은 Apple이 두 번 규정한 유일한 역할입니다.** iOS의 그룹 배경은 다크에서 순수한 검정이고 폰에서는 그것이 맞습니다. 화면이 거의 전부 콘텐츠이고 패널이 그 검정 위에 놓입니다. macOS 창은 검정이었던 적이 없습니다. 데스크톱 창을 검정으로 칠하면 `0x1c1c1e` 패널이 페이지에서 두 단계밖에 떨어지지 않아 패널이 패널로 읽히지 않고, 창이 문서가 아니라 동영상 플레이어처럼 보입니다.

그래서 다크 모드에서 `Background`만 크기 클래스에 따라 달라집니다. Compact는 검정, Medium과 Expanded는 시스템 그레이 중 가장 어두운 색입니다. 라이트 모드에는 이 선택이 필요 없습니다. 토큰 테이블은 역할당 값 하나만 담을 수 있으므로 Apple 자신의 두 값 중 하나를 고르는 일은 컴포넌트 규칙이 합니다(INTENT D13-2). **이것은 디자인 시스템이 임의로 색을 갈아끼우는 경로가 아닙니다.** 플랫폼 벤더가 같은 역할에 두 값을 규정한 경우에만 쓰며, 그 밖의 것은 전부 생성된 테이블이 답합니다.

**타이틀바 영역은 상단 바의 일부입니다.** macOS 26에서 타이틀바 영역과 툴바는 하나의 유리이며 그 사이에 경계가 없습니다. 창 맨 위에 페이지 색 띠를 남기고 그 아래부터 바를 그리면 유리가 아무리 정확해도 macOS 26으로 보이지 않습니다. 이것은 크롬이 어디까지인지의 문제이므로 FR-19.2에서 규정합니다.

- 수용 기준
  5. 폭 480dp 창에서 카드와 표면이 반투명하게 그려지고, 폭 1180dp 창에서 같은 노드가 불투명하게 그려집니다. 두 폭 모두에서 상단 바는 반투명입니다.
  6. 불투명 대체(14.1-2 수용 기준 3)는 본문 텍스트 기준 WCAG 2.2 AA(4.5:1)를 콘텐츠 색에 대해 만족하며, 그 계산이 단위 테스트로 검증됩니다.
  7. 투명도 감소가 켜지면 흐림 패스 자체가 사라집니다. 보이지 않는 흐림을 계속 그리지 않습니다.
  8. 다크 모드에서 폭 480dp 창의 페이지는 순수한 검정이고, 폭 1180dp 창의 페이지는 그보다 밝으며 그 위의 패널보다도 밝습니다. `Background` 외의 어떤 역할도, 그리고 라이트 모드의 어떤 역할도 폭에 따라 달라지지 않습니다.
  9. `Tonal` 버튼이 페이지 위, 패널 위, 바 위 어디에 놓여도 배경과 최소 6/255 떨어집니다. 저장된 회색 하나로는 이것을 약속할 수 없으므로, Apple의 fill 색처럼 반투명한 칠입니다. 라이트에서는 검정, 다크에서는 흰색이며, 이것이 같은 버튼이 세 배경 모두에서 보이는 이유입니다.

#### 14.2 컴포넌트 변형
컴포넌트 규칙이 붙는 자리는 변형(variant) 속성입니다. 값은 디자인 시스템 중립 이름입니다.
- `Button.variant`: `Filled | Tonal | Outlined | Text`
  - Material 3: Filled/Tonal/Outlined/Text 버튼, 큰 곡률, 리플.
  - Cupertino: Filled은 강조 버튼(연속 곡률, 그림자 없음), Tonal은 회색 배경, Text는 내용 색만 쓰는 plain 버튼. 리플 대신 하이라이트.
  - Liquid Glass: 모든 크기에서 캡슐입니다. Filled은 강조색 채움, Tonal은 반투명한 칠(라이트에서 검정, 다크에서 흰색)이라 페이지 위에서도 바 위에서도 배경과 떨어져 보이고, Outlined는 유리 테두리, Text는 내용 색만 씁니다.
  - Fluent: Accent/Standard/Standard+stroke/Subtle, 4dp 곡률, 위쪽 밝은 테두리.
- 같은 rsx 코드가 시스템에 따라 다른 모양으로 그려지는 것이 정상 동작입니다.

#### 14.3 두 가지 테마 모드
애플리케이션이 **명시적으로** 고릅니다.

```rust
// 모든 플랫폼에서 같은 디자인 시스템
LaunchBuilder::new().with_theme(Theme::unified(DesignSystem::Material3)).launch(app);
// 호스트 플랫폼을 따라감. 대응이 없는 플랫폼에서 쓸 시스템을 반드시 함께 지정합니다
LaunchBuilder::new().with_theme(Theme::adaptive(DesignSystem::Material3)).launch(app);
```

- **기본값은 `Theme::adaptive(DesignSystem::Material3)`입니다.** `with_theme`을 부르지 않으면 호스트 플랫폼을 따라갑니다.
- 이 값은 원래 `unified(Material3)`였습니다. 기본값이 플랫폼마다 다르게 보이는 것이 예측 가능성을 해친다고 봤기 때문입니다. 실제로 써 보니 판단이 틀렸습니다. macOS에서 아무 설정 없이 실행하면 Material 3 화면이 나오는데, 네이티브 데스크톱 UI를 표방하는 툴킷의 첫인상으로 맞지 않고, 무엇보다 **플랫폼 적응이 동작하는지 확인할 방법이 기본 경로에 없었습니다.** 깨져 있어도 알 수 없는 기본값은 예측 가능성이 아닙니다.
- 플랫폼과 무관하게 같은 화면을 원하면 `Theme::unified(...)`를 명시합니다. 한 줄이고, 그렇게 쓰는 쪽이 의도를 드러냅니다.
- `Theme::adaptive`는 fallback 인자가 **필수**입니다. 그래서 adaptive에 "대응이 애매한 플랫폼"이 남지 않습니다. 기본값의 fallback은 Material 3입니다.

| 플랫폼 | `adaptive`가 고르는 시스템 |
|---|---|
| Android | Material 3 |
| macOS, iOS | Liquid Glass |
| Windows | WinUI/Fluent 2 |
| Linux (GNOME) | GNOME 50 |
| Linux (KDE) | KDE Breeze |
| Linux (그 외, 판별 불가) | Deepin |
| Web | WinUI/Fluent 2 (설정으로 Material 3로 교체 가능) |

- **Linux 데스크톱 환경 판별**: `XDG_CURRENT_DESKTOP`을 먼저 보고, 비어 있으면 `DESKTOP_SESSION`을 봅니다. 값에 `GNOME`이 포함되면 GNOME 50, `KDE`면 Breeze, 그 외와 판별 실패는 Deepin입니다. 판별 결과는 시작 시 한 번만 읽습니다.
- **Web에 플랫폼 룩은 없습니다.** 브라우저는 자기 디자인 언어를 갖지 않으므로 `adaptive`에서도 선택은 임의입니다. 기본을 Fluent 2로 두되, 앱이 설정으로 Material 3를 고를 수 있습니다. 문서에서는 Web에 대해 `unified`를 명시하는 것을 권장합니다.
- 위 표가 적용됩니다. `fallback` 인자는 자기 디자인 언어가 없는 플랫폼(판별 불가 포함)이 쓰므로 여전히 필수입니다.
- **GNOME 50 주의**: 버전을 명시한 것은 GNOME의 디자인 언어가 릴리스마다 바뀌기 때문입니다. 참조한 문서와 버전은 토큰 테이블의 `reference` 문자열에 남아 있습니다.
- **Deepin 주의**: 토큰값과 스타일 규칙만 참조합니다. 아이콘 세트와 전용 폰트는 별도 라이선스가 걸리므로 가져다 쓰지 않습니다.
- **Cupertino는 표에 없습니다.** `adaptive`가 고르지 않습니다(14.1-3). macOS와 iOS 자리는 Liquid Glass이고, Cupertino는 `unified`로 이름을 대고 고릅니다. 두 언어 모두 그 플랫폼의 것이며, 이름을 대지 않았을 때 나오는 쪽이 그 기계가 지금 쓰는 언어여야 합니다.
- **`DXC_DESIGN`으로 샘플의 시스템을 고릅니다.** 값은 `material3`, `cupertino`, `fluent`, `gnome`, `breeze`, `deepin`, `liquidglass`(`liquid-glass`도 같음)이고, 그 밖의 값과 설정하지 않음은 `adaptive`입니다. `DXC_SCHEME`은 같은 이유로 `light`와 `dark`를 받습니다. 한 대의 기계에서 일곱 시스템을 전부 눈으로 확인할 방법이 기본 경로에 없으면 여섯은 보이지 않은 채 남습니다.
- 명암(`ColorScheme`)은 `Light | Dark | FollowSystem`이고 기본은 `FollowSystem`입니다. 시스템 설정 변화는 Renderer가 먼저 알고 스스로 반영합니다. Host는 관여하지 않습니다(D5).
- **`Theme::unified`은 명암을 고정하지 않습니다.** `unified`가 말하는 축은 "어느 디자인 시스템인가" 하나이고, 명암은 별개의 축입니다. 디자인이 라이트나 다크 한쪽으로 정해져 있는 앱은 `.with_color_scheme(...)`으로 그렇게 말합니다. 한 줄 더 쓰는 쪽을 고른 이유는 셋입니다.
  1. `unified`가 명암까지 고정하면, 디자인 시스템 하나만 원했던 앱이 독자의 다크 모드 설정까지 같이 잃습니다. 이름이 약속한 적 없는 일이고, 접근성 후퇴입니다.
  2. `adaptive`와 `unified`의 차이는 `adaptive` 플래그 하나여야 합니다. 두 생성자를 나란히 읽는 사람은 그 하나만 다르다고 읽고, 실제로 두 번째 축에서도 달라지면 틀리게 읽습니다.
  3. `unified`가 고정할 수 있는 옳은 값이 없습니다. 라이트로 읽히도록 만든 디자인은 `Light`라고 말하면 되고, 독자의 설정을 따르려는 앱은 아무 말도 하지 않으면 됩니다. 고정해 버리면 두 번째가 사라지고, 그것을 되돌리는 `.follow_system()`을 더하는 것은 `.with_color_scheme(Light)` 한 줄보다 큰 API입니다.
- **참조 디자인이 한쪽으로 정해진 샘플은 그 한쪽을 명시합니다.** 통합 샘플 일곱 개가 여기 걸립니다. 참조 그림이 라이트 iOS 디자인인데 기계가 다크로 설정되어 있으면 일곱 개 전부 검게 떠서, 참조와 비교할 수 있는 화면이 한 장도 나오지 않습니다. 어느 쪽인지는 참조 그림이 정하며, `docs/references/design-systems/README.md`의 "Unified Examples" 각 항목에 적혀 있습니다.
- **`demo_theme_for(theme)`은 샘플이 자기 테마를 가진 채로 `DXC_DESIGN`/`DXC_SCHEME`을 받는 경로입니다.** 변수가 이름을 댄 축만 덮어쓰므로, Liquid Glass를 보자고 해도 그 디자인이 그려진 명암은 그대로 남습니다.

#### 14.4 해석 위치: Renderer
**토큰 해석과 컴포넌트 규칙은 Renderer가 수행합니다.** Host는 역할과 선택만 보냅니다.

근거:
- **PR-3/NFR-9(프레임 예산).** Host는 Renderer UI 스레드에서 돌기 때문에 Host의 작업이 그대로 프레임 예산(§5.1, 상호작용당 ≤ 0.5ms)에서 빠집니다. Host가 토큰을 푼다면 다크모드 전환이나 플랫폼 테마 변경이 트리 전체에 대한 `SetProp` 재전송(O(노드 수))이 됩니다. Renderer가 풀면 같은 변경이 `SetTheme` 1건이고, 나머지는 Compose의 `CompositionLocal` 무효화로 끝납니다.
- **PR-1(동기 경계).** 시스템 명암 전환과 플랫폼 식별은 Renderer 쪽 정보입니다. Host가 해석하려면 Renderer→Host 질의가 필요한데, 경계는 동기 단방향 호출 모델이라 질의를 추가하면 PR-2의 표면이 늘어납니다.
- **D5.** 테마는 UI 로컬 상태입니다. 스크롤 위치·포커스와 같은 부류입니다.
- 비용: Host 쪽 단위 테스트는 "어떤 역할을 보냈는가"까지만 검증할 수 있고, 실제 색·치수는 Renderer 테스트에서 검증합니다. 이 분리를 받아들입니다.

**토큰 테이블의 저작 위치는 Rust이고, 실행 위치는 Renderer입니다.** `ColorRole` 23개(13.1-3이 들어오면 45개) × 2개 명암, 9단 `TypeRole`, `ShapeRole`/`SpaceRole` 치수 같은 값 표는 Rust 스키마에 데이터로 두고, FR-7 코드젠이 `Protocol.gen.kt`에 Kotlin `object`로 내보냅니다. 근거:
- D6(단일 소스는 Rust)를 토큰에도 그대로 적용합니다. Kotlin에 손으로 적으면 세 시스템 × 7개 표가 검증되지 않은 채 남습니다.
- 값 표는 Rust 테스트로 검증할 수 있습니다(대비비, 사다리 단조성, 표가 비어 있지 않은지). 14.4가 포기한 것은 "화면에 그려진 결과"이지 "표의 내용"이 아닙니다.
- 경계는 그대로입니다. 표는 **빌드 시점에** Renderer 바이너리로 들어가고, 런타임에 경계를 넘지 않습니다. 13.7의 "Host가 보내는 토큰 테이블"은 여전히 금지입니다.
- Renderer 구현자가 채우는 것은 값이 아니라 **적용 규칙**(14.6의 5·6·7번과 `CompositionLocal` 배선)입니다.

#### 14.5 와이어 추가분
- Mutation `SetTheme { design_system: u16, fallback: u16, color_scheme: u16, adaptive: u16 }`: **명령 태그 9**, 레코드 길이 12바이트(`tag`, `len`, 뒤이어 u16 4개). 루트(`node_id` 없음)에 적용합니다. `adaptive`는 0 또는 1입니다. Host는 초기 배치의 첫 레코드로 1회 보내고, 앱이 테마를 바꿀 때만 다시 보냅니다.
- `DesignSystem` 태그: `Material3 = 1`, `Cupertino = 2`, `Fluent = 3`, `Gnome = 4`, `Breeze = 5`, `Deepin = 6`, `LiquidGlass = 7`. 태그 값은 바뀌지 않고 추가만 합니다. `Cupertino`의 이전 이름은 `AppleHig`였습니다.
- `ColorScheme` 태그: `Light = 1`, `Dark = 2`, `FollowSystem = 3`.
- 수용 기준: `Theme::unified(...)`로 띄운 앱의 첫 배치 첫 레코드가 `SetTheme`이고 `adaptive = false`입니다. `Theme::adaptive(...)`이면 `adaptive = true`이며 `fallback`이 인자로 준 시스템입니다.
- 수용 기준: `Theme::unified(X)`의 `color_scheme`은 `FollowSystem`입니다. 통합 샘플 일곱 개는 각자 `.with_color_scheme(...)`으로 참조 그림의 명암을 명시하며, 그 값이 `SetTheme` 레코드에 실립니다.

#### 14.6 Renderer 구현자가 채워야 할 표
디자인 시스템마다 아래 8개가 필요합니다. 채워지면 위젯 코드는 건드리지 않습니다.

1~4번은 14.4에 따라 Rust 스키마에서 코드젠으로 생성되어 `Protocol.gen.kt`의 `DesignTokens`에 이미 들어 있습니다. Renderer 구현자는 **5~8번과, 1~4번을 Compose에 배선하는 일**을 맡습니다.
1. `ColorRole` 23개(13.1-3이 들어오면 45개) × {Light, Dark} 색값
2. `TypeRole` 9개 → 크기/굵기/행간/자간/폰트
3. `ShapeRole` 6개 → 곡률(HIG는 연속 곡률)
4. `SpaceRole` 7개 → dp
5. `Modifier::Elevation(dp)` → 그림자/톤/스트로크 렌더링 규칙
6. `ButtonVariant` 4개 → 배경·전경·테두리·눌림 표현
7. 모션: 상태 전환 duration과 easing
8. 입력 필드의 틀(14.7)

#### 14.7 입력 필드의 틀 (`Done`)

`TextField`는 지금까지 아무 틀 없이 그려졌습니다. 배경도, 테두리도, 안쪽 여백도, 포커스 표시도 없는 맨 편집 영역 하나입니다. 그래서 여섯 시스템의 입력 필드가 전부 똑같이 보였고, 틀을 원하는 화면은 `Background`와 `Border`와 `PaddingRole`을 직접 붙여 왔습니다. 둘 다 14.1에 어긋납니다.

필드의 틀은 여섯 시스템이 가장 눈에 띄게 갈라지는 자리입니다. Material 3는 채운 상자에 밑줄을 긋고 포커스에서 밑줄이 두꺼워집니다. Cupertino는 둥근 사각형에 옅은 채움이고 테두리를 거의 쓰지 않습니다. Fluent 2는 사각형에 가까운 상자에 아래쪽 강조선을 두고 그 선만 포커스에서 굵어집니다. Adwaita는 6px 둥근 채움에 포커스에서 강조색 테두리가 생깁니다. Breeze는 한 겹 하이라이트 테두리로 포커스를 표시합니다. Deepin은 큰 반경의 채움에 테두리 없이 포커스에서만 선이 나타납니다. 그것을 Host가 칠한다는 것은 디자인 시스템이 정할 것을 애플리케이션이 정하고 있다는 뜻입니다.

- **위젯은 역할만 내보냅니다**(14.1). 애플리케이션은 필드가 있다고 말할 뿐이고, 틀은 디자인 시스템이 그립니다. 모든 위젯이 공통으로 갖는 Modifier(배경·테두리·반경·여백)는 `TextField`에도 그대로 남지만 그것은 어느 위젯에나 있는 탈출구이지 필드의 틀을 그리는 수단이 아닙니다. `Button`이 공통 Modifier를 가지면서도 자기 컨테이너를 스스로 그리는 것과 같습니다. 포커스 표시를 지정하는 속성은 두지 않습니다.
- **Renderer 쪽 확장은 `ComponentRules`에 `field()` 하나를 더하는 것으로 끝납니다.** 일곱 번째 디자인 시스템은 여전히 구현 하나입니다.
- **포커스는 Renderer의 상태입니다**(D5). 포커스가 들고 나면서 틀이 바뀌는 것은 경계를 넘지 않고, Host는 그 전환을 알지 못합니다.
- 규칙이 답하는 값: 채움색, 포커스 시 채움색, 테두리색과 두께, 포커스 시 테두리색과 두께, 밑줄만 그리는지, 모서리, 안쪽 여백, 커서색, 최소 높이.
- **한 줄짜리 필드와 여러 줄짜리 필드는 따로 묻습니다.** 알약 모양은 한 줄의 모양입니다. 같은 반경을 글이 여러 줄 들어찬 편집 영역에 그대로 쓰면 모서리끼리 만나 타원이 되고, 그 안의 글은 잘려 나갑니다. 그래서 규칙은 필드가 한 줄인지 여러 줄인지를 인자로 받고, 큰 반경을 쓰는 시스템만 답을 달리합니다. 애플리케이션은 여전히 `multiline`만 말하고 모서리는 말하지 않습니다.
- 수용 기준
  1. Modifier를 하나도 붙이지 않은 `TextField`가 여섯 시스템에서 서로 다른 틀로 그려지고, 어느 것도 틀 없는 맨 편집 영역이 아닙니다.
  2. 필드에 포커스가 들어가면 그 시스템이 정한 대로 틀이 바뀝니다.
  3. 같은 `TextField` 선언이 디자인 시스템을 바꾸면 다른 모양이 됩니다.
  4. 여러 줄 필드의 모서리 반경은 그 필드의 높이 절반보다 작습니다. 어느 시스템에서도 편집 영역이 타원으로 그려지지 않습니다.

#### 14.8 값의 근거는 참조 이미지입니다 (수동 검사)

번호가 14.7이었습니다. 입력 필드 항목이 이미 14.7이므로 14.8로 옮깁니다. 내용은 그대로입니다.

`docs/references/design-systems/README.md`가 일곱 시스템의 참조 이미지를 모아 둡니다. **토큰 값과 컴포넌트 규칙은 그 그림에서 읽어 옵니다**(INTENT D14). 기억으로 쓴 값은 값이 아니라 추측입니다. 예외는 하나입니다. 공개된 코드 배색이 없는 Material 3과 Deepin의 코드 색 역할은 그 시스템의 기반 색 역할에서 13.1-3의 규칙으로 유도하고, 대조는 그 기반 역할에 대해 합니다.

자동화된 검사가 물을 수 있는 것은 "두 시스템이 서로 다른가"와 "대비가 충분한가"까지입니다. "이 시스템이 자기 자신처럼 보이는가"는 물을 수 없습니다. 그래서 다음은 **수동 검사**입니다.

1. `DXC_SCREENSHOT_DIR=<디렉터리> ./kotlin test -p jvm`으로 일곱 시스템 × 두 명암의 쇼케이스를 받습니다.
2. 각 이미지를 해당 시스템의 참조 이미지와 나란히 놓고, 버튼·카드·입력 필드·목록 행·상단 바·대화상자·메뉴·내비게이션·시트·메시지·선택 컨트롤을 하나씩 대조합니다.
3. 값을 바꾸는 변경은 **어느 그림의 무엇을 보고 바꿨는지** 커밋 본문에 적습니다.

- 수용 기준: 일곱 시스템 각각에 대해 참조 이미지와의 대조 결과가 기록되어 있고, 다른 점은 고쳐졌거나 왜 남겨 두는지가 적혀 있습니다.

##### 14.8-1 1차 대조 기록 (코드 기준, 화면 미확인)

아래는 참조 이미지와 **코드**를 대조한 결과입니다. 스크린샷 대조는 아직 하지 않았으므로 이 표는 14.8 수용 기준의 절반이고, 나머지 절반은 렌더러 빌드로 찍은 이미지와의 대조입니다.

| 시스템 | 참조가 보여주는 것 | 고친 것 | 남겨 둔 것 |
|---|---|---|---|
| Material 3 Expressive | 캡슐 버튼, 28 반경 카드, 캡슐 내비 표시, 굵고 둥근 탭 표시, 고채도 보라 | 모양 사다리를 Expressive로(Small 12, Medium 16, Large 28), 메뉴 Small, 탭 표시 4dp 캡슐 | 팔레트. 참조의 보라는 baseline 토널 팔레트와 같은 계열입니다. 이어진 버튼 그룹과 물결 진행 표시는 위젯 어휘에 없습니다 |
| Cupertino | 회색 그룹 배경 위 흰 삽입 목록, 앞쪽에서 들여쓴 머리카락 구분선, 오른쪽 꺾쇠, 굵은 큰 제목, 얇은 경계선 하단 탭 바, `#007AFF` | 큰 제목을 굵게(700) | 나머지 값. 팔레트와 컨트롤은 이미 참조와 맞습니다 |
| Liquid Glass | 반투명 유리 크롬, 모든 크기에서 캡슐, 동심 곡률, 캡슐 입력 필드, 초록 스위치, 떠 있는 상단 캡슐, 큰 창 반경, 신호등이 크롬 위에 직접 | 시스템 전체를 새로 만듦(태그 7). 재질·대비 대체·동심 모양은 이전 브랜치에서 가져옴 | 창 뒤 바탕화면 샘플링. Compose가 할 수 없습니다(14.1-2) |
| Fluent 2 | 작은 반경(버튼 4, 카드 8), 위쪽 밝은 테두리, 왼쪽 가장자리 강조 막대, 46×32 캡션 버튼, 닫기 호버 `#C42B1C` | 캡션 규칙 추가 | 토큰. 참조와 이미 맞습니다 |
| GNOME 50 | 가운데 제목, 오른쪽 회색 원형 창 버튼, 박스 목록 12 반경, 창 15 반경, 버튼 6 | 모양 사다리를 libadwaita의 이름 있는 값으로(6/12/15), 캡션 규칙 추가 | 팔레트와 컨트롤. libadwaita 이름 색과 GTK 스위치 치수를 이미 씁니다 |
| Breeze | 거의 각진 모서리, 가운데 제목, 오른쪽 작은 창 버튼, 연한 파랑 선택 행, `#3DAEE9` | 캡션 규칙 추가 | 팔레트와 모양. Breeze 이름 색을 이미 씁니다. 다크 창 색은 참조에서 정확한 단계를 읽을 수 없어 그대로 둡니다 |
| Deepin | 중립 회색(따뜻하지 않음), 흰 창에 회색 사이드바, 큰 창 반경, 강조 파랑 선택 행, 오른쪽 맨 글리프 창 버튼 | 팔레트 전체를 중립으로, 페이지를 흰색으로 패널을 회색 우물로, Medium 반경 10으로, 캡션 규칙 추가 | 두 번째 강조색(amber). Deepin 자체 읽기 화면의 두 번째 계열에서 읽었습니다 |

**상단 바의 제목 정렬(2026-09-22 구현).** GNOME과 Breeze와 Deepin은 창 제목을 가운데에 두고 나머지는 앞쪽에 둡니다. `CaptionStyle.titleAlignment`가 그 값을 담고 있었지만 아무도 읽지 않았습니다. `TopAppBar`의 자식이 Host가 보낸 임의의 트리여서 어느 것이 "제목"인지 렌더러가 집어낼 수 없었기 때문입니다.

`TopAppBar`가 `title`을 속성으로 받습니다. 자식이 아니라 속성인 이유가 그것입니다. 새 위젯도 새 이벤트도 없고, 이미 있는 `PropertyKind::Text`를 바가 마저 받는 것뿐입니다.

- **가운데 정렬은 바의 다른 자식들 사이가 아니라 창을 기준으로 합니다.** 옆에 버튼이 붙을 때마다 제목이 밀린다면 그것은 창 제목을 가운데 둔 것이 아닙니다.
- 수용 기준: GNOME에서 제목의 중심이 바의 중심과 일치합니다(`fr15_2_a_bar_centres_its_title_where_the_design_system_asks`). **(통과)**
- 수용 기준: Material 3에서 제목이 바의 다른 자식보다 앞에 섭니다(`fr15_2_a_bar_leads_with_its_title_where_the_design_system_asks`). **(통과)**

#### 14.9 iOS 26의 네이티브 Liquid Glass 셸 (`Agreed`)

14.1-2는 우리가 그릴 수 있는 것과 그릴 수 없는 것을 갈라 놓고, 그릴 수 없는 쪽(시스템이 탭 바와 내비게이션 바에 입히는 진짜 Liquid Glass)을 1.0 범위 밖에 두었습니다. 이 항목이 그 경계를 iOS에 한해 옮깁니다. **그 크롬을 우리가 그리지 않고 시스템의 것을 세우면 진짜 유리를 받습니다.** 결정과 그 근거는 INTENT D15입니다.

**Host는 아무것도 새로 말하지 않습니다.** 경계에 위젯 태그도, 속성 태그도, 이벤트 태그도 늘지 않습니다. Host가 보내는 것은 FR-21이 정한 `Navigation` 하나 그대로이고, 그것을 무엇으로 그릴지는 이미 Renderer의 판단입니다(FR-21.2). 이 항목은 그 판단지에 선택지 하나를 더합니다.

##### 14.9.1 무엇이 네이티브가 되는가

디자인 시스템이 `Navigation`을 **막대**로 그리기로 답했고(FR-21.2의 기본 대응에서 `Compact`), 플랫폼이 아래 게이트를 통과하며, 해석된 디자인 시스템이 Apple의 것(`Cupertino` 또는 `LiquidGlass`)일 때에만 해당합니다.

**디자인 시스템 조건은 잊어도 되는 것이 아닙니다.** 셸이 세우는 크롬은 Apple이 그리는 Apple의 것입니다. Material 3나 Fluent를 달라고 한 화면 밑에 그것을 놓는 것은 아무도 하지 않은 질문에 답하는 일이고, 하필 답을 반박하기 가장 어려운 플랫폼에서 하는 일입니다. 이 플랫폼에서 아무 말도 하지 않은 애플리케이션은 어차피 Apple의 것을 받습니다(FR-14.3의 adaptive 기본값).

- 목적지 하나가 `UITabBarItem` 하나입니다. 라벨은 `Text`(태그 1), 그림은 `Icon`(태그 60)의 `IconRole`을 SF Symbol 이름으로 옮긴 것입니다.
- 탭 하나가 `UINavigationController`이고, 그 안에는 비어 있고 투명한 화면이 하나 있습니다. 목적지의 라벨이 아래쪽 탭 바에서 탭의 이름이 되고 위쪽 바에서 제목이 됩니다. 시스템이 유리를 입히는 자리가 그 두 바이기 때문입니다.
- 선택된 탭의 화면은 **여전히 Compose가 그립니다.** 바뀌는 것은 두 바뿐이고, `Navigation`의 내용 자식들은 지금과 같은 인터프리터를 지나 같은 트리로 그려집니다.
- 컨트롤러 세 겹의 모양은 취향이 아닙니다. `UITabBarController`는 `addChildViewController`를 "탭을 하나 덧붙여라"로 해석하므로 콘텐츠를 그 자식으로 둘 수 없습니다. 첫 `setViewControllers`가 그것을 다시 떼어 냅니다. 그래서 콘텐츠와 탭 바 컨트롤러는 나란히 **평범한 컨테이너 컨트롤러의 자식**이고, 콘텐츠의 **뷰**만 탭 바 컨트롤러의 뷰 안 맨 아래에 들어갑니다. 유리가 굴절할 것이 그 아래에 있어야 하고, 콘텐츠 영역의 터치가 Compose에 닿아야 하기 때문입니다.
- **아래쪽 막대는 여백으로 밀어내지 않고, 위쪽 제목 바는 밀어냅니다.** 아래는 콘텐츠가 그 밑으로 지나가는 것이 유리의 전제이고, 위는 그 밑에 남은 콘텐츠를 아무도 읽을 수 없습니다. 두 높이는 가정하지 않고 UIKit에서 잽니다. 기기마다 다르고, 홈 인디케이터와 상태 표시줄이 더해집니다.
- iOS 26이 탭 바를 띄워 두고 스크롤에 맞춰 접는 동작(`tabBarMinimizeBehavior`)은 그 속성이 존재할 때에만 켭니다.
- **제목 바는 지금 장식입니다.** 내비게이션 컨트롤러의 컨테이너 뷰는 탭 전체를 덮으므로 터치를 받게 두면 그 아래 콘텐츠로 가야 할 탭을 전부 삼킵니다. 그래서 뷰 전체를 터치 불가로 둡니다. 제목 바에 누를 것이 생기는 순간 이 처리는 바의 사각형에만 `hitTest`로 답하는 뷰로 바뀌어야 하며, 그 자리가 코드에 적혀 있습니다.
- **창의 막대는 트리의 루트인 `Navigation`에게만 줍니다.** 플랫폼의 크롬은 창의 것이고 하나뿐입니다. 화면 일부에 중첩된 `Navigation`이 그것을 가져가면 원래 임자에게서 빼앗는 것이고, 둘이면 번갈아 가집니다. 중첩된 것은 이쪽에서 그린 막대를 그대로 씁니다.

##### 14.9.2 이음매는 공유 인터프리터에 있고, 그것을 채우는 것은 iOS뿐입니다

인터프리터 소스는 데스크톱과 iOS가 같은 파일입니다. 그래서 UIKit을 아는 코드가 그 안에 있을 수 없습니다. 인터프리터에는 **설치되지 않았으면 아무 일도 일어나지 않는 이음매** 하나만 둡니다.

- `Navigation`은 막대를 그리기 직전에 그 이음매에게 목적지 목록과 현재 선택을 건네고 "네가 그렸느냐"고 묻습니다.
- 아무도 설치하지 않았으면 답은 "아니오"이고, 지금과 완전히 같은 Compose 막대가 그려집니다. 데스크톱, Android, 웹, 그리고 iOS 26 미만이 전부 이 경로입니다.
- 답이 "예"이면 `Navigation`은 내용만 그립니다. 막대는 시스템의 것이 이미 화면에 있습니다.

##### 14.9.3 게이트

실행 중인 OS가 iOS 26 이상일 때에만 네이티브 셸을 세웁니다. 판정은 `NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion`이고, 이유는 INTENT D15에 적었습니다.

미만에서는 지금 있는 것을 그대로 씁니다. Cupertino 토큰과 14.1-2의 1번 항목(동심 곡률, 가장자리 하이라이트, 반투명 표면)이 그리는 근사입니다. **그 근사는 없어지지 않습니다.** macOS의 구현이자 iOS 26 미만의 구현으로 남습니다.

##### 14.9.4 수용 기준

1. 이음매에 아무것도 설치되지 않은 상태에서 `Navigation`이 그리는 화면이 이 변경 전과 같습니다. 목적지 수, 선택 표시, 내용 배치가 모두 그대로입니다.
2. 이음매가 "내가 그렸다"고 답하면 Compose 막대가 그려지지 않고, 내용은 그대로 그려집니다.
3. 게이트가 iOS 26 미만에서 거짓, 26 이상에서 참입니다.
3-1. Material 3를 지정한 화면은 iOS 26에서도 이쪽에서 그린 막대를 씁니다. `LiquidGlass`를 지정한 화면은 `Cupertino`와 같이 셸을 받습니다.
4. 네이티브 셸이 서면 창의 루트 뷰 컨트롤러가 `UITabBarController`이고, 그 탭 수가 `NavigationItem` 자식 수와 같으며, 각 탭의 제목이 그 목적지의 `Text`입니다.
5. 시스템 탭 바에서 탭을 고르면 그 목적지의 `OnClick` 핸들러로 `Clicked`가 **정확히 한 번** 갑니다. FR-21.7의 3번과 같은 규약이고, 경계에 새 이벤트가 없습니다.
6. Host가 `SelectedIndex`(태그 42)를 바꾸면 시스템 탭 바의 선택이 따라 움직입니다.
7. `tabBarMinimizeBehavior`를 켜는 코드가 그 속성이 없는 시스템에서 실행되어도 죽지 않습니다.
8. iOS 26 시뮬레이터 스크린샷에서 탭 바가 그 아래 콘텐츠를 비춥니다. **손으로 확인합니다.** 시스템이 그린 유리인지 우리가 그린 근사인지는 픽셀을 세어 가릴 수 있는 것이 아니고, 이 프로젝트에 그 판정을 자동화할 기준 이미지가 없습니다.
9. iOS 18 시뮬레이터에서 같은 화면이 Compose 막대로 뜨고, 어떤 UIKit 셸도 세워지지 않습니다. **손으로 확인합니다.**

##### 14.9.5 지금까지 확인된 것 (2026-09-22)

1번부터 7번까지 통과했습니다. 1번, 2번, 3-1번, 5번, 6번은 `PlatformNavigationShellTest`가 JVM에서, 3번, 4번, 7번은 `LiquidGlassAvailabilityTest`와 `IosNavigationShellTest`가 시뮬레이터에서 확인합니다. 후자는 UIKit이 실제로 만든 것에 대한 단언입니다. 이 기계의 기본 런타임은 iOS 26으로 보고되었고 게이트는 참이었으며, 잰 값은 아래쪽 83pt, 위쪽 54pt였습니다.

**8번과 9번은 아직입니다.** 둘 다 스크린샷이고, 이 작업은 렌더러를 빌드하지 않았습니다. 스크린샷은 CI의 iOS 잡이 찍습니다. 기본 스모크 트리에는 `Navigation`이 없어서 그 화면에는 이 작업의 크롬이 한 조각도 나오지 않으므로, `--navigation`으로 목적지 두 개를 선언한 두 번째 스크린샷을 같이 올립니다.

**자동화할 수 없는 것을 분명히 해 둡니다.** 시스템이 그 막대를 Liquid Glass로 칠했는지는 이 코드가 끝난 뒤 시스템이 내리는 그리기 결정이고, 픽셀을 세어 가릴 수 있는 것이 아닙니다. `tabBarMinimizeBehavior`가 실제로 막대를 접는 것도 스크롤 뷰가 있는 화면에서만 볼 수 있고, 지금 탭 안의 화면은 비어 있습니다.

**알려진 한계 둘.** 잰 높이는 `present`가 불릴 때 갱신되므로 회전만으로는 다시 재지 않습니다. 그리고 창이 넓은 상태로 시작하면(아이패드) 폭이 측정되기 전 첫 컴포지션이 좁은 창으로 잡혀 막대를 한 프레임 세웠다가 내립니다. 둘 다 스스로 복구되지만 기록해 둡니다.

#### 14.10 애플리케이션 색 (`Draft`)

지금 `Theme`은 일곱 디자인 시스템 중 하나와 명암 하나, 그리고 역할별 폰트(FR-23.2)를 고릅니다. **앱이 자기 색을 정할 길이 없습니다.** 색을 칠하는 유일한 방법은 노드마다 `Paint::Literal`을 쓰는 것이고, 리터럴은 디자인 시스템이 영영 보지 못하는 색이라 버튼의 눌림 표현, 비활성 흐림, 유리의 틴트, 다크 모드 전환이 전부 그 색을 모릅니다. 요청한 앱(Ember)은 자기 브랜드 색이 있고, 그 색은 런처와 대화 화면의 모든 버튼과 선택 표시에 나와야 합니다.

코드 색 역할(13.1-3)을 앱이 덮어쓰는 경로도 이 항목입니다.

##### 14.10.1 바꾸는 것은 색 역할의 값이고, 나머지는 디자인 시스템의 것입니다

```rust
const EMBER: Palette = Palette::new()
    .with(ColorRole::Primary, Color::rgb(0xE8590C), Color::rgb(0xFF8A4C))
    .with(ColorRole::OnPrimary, Color::rgb(0xFFFFFF), Color::rgb(0x2B0E00))
    .with(ColorRole::Background, Color::rgb(0xFFFBF8), Color::rgb(0x141110));

LaunchBuilder::new()
    .with_theme(Theme::adaptive(DesignSystem::Material3).with_palette(&EMBER))
    .launch(app);
```

- **앱이 덮어쓰는 단위는 `ColorRole` 하나와 명암 하나입니다.** 값은 `Color`(ARGB 리터럴)입니다. 역할을 다른 역할로 돌리는 것(`Primary`를 `Tertiary`로)이나 브러시(FR-23.1)는 받지 않습니다. Renderer의 규칙은 역할을 하나의 색으로 풀어서 그 위에서 계산합니다(눌림 상태 층, 비활성 흐림, 유리 틴트). 그라데이션은 그 계산의 입력이 될 수 없습니다.
- **모양, 타이포그래피, 간격, 고도, 모션, 컴포넌트 규칙은 그대로 디자인 시스템의 것입니다.** 같은 브랜드 색을 Material 3로 그리면 큰 곡률과 리플이고, Liquid Glass로 그리면 캡슐과 유리입니다. 이것이 FR-14.1이 지키는 경계이며, 앱 색은 그 경계를 넘지 않습니다. 13.7의 "Host가 보내는 토큰 테이블" 금지는 그대로입니다. 보내는 것은 색 역할의 값 일부이지, 치수나 규칙의 표가 아닙니다.
- **명암별로 따로 줍니다.** 한 색을 라이트와 다크에 같이 쓰면 거의 늘 한쪽에서 틀립니다. 밝은 페이지에 맞춘 진한 주황은 어두운 페이지에서 가라앉습니다.
- **두 명암의 값이 한 번에 갑니다.** `FollowSystem`에서 시스템이 다크로 바뀌면 Renderer가 이미 든 표에서 고릅니다. Host로 묻지 않습니다(14.4의 근거 그대로. 명암 전환이 `SetTheme` 재전송이 되면 안 됩니다).
- `Theme::adaptive`에서는 Renderer가 고른 시스템이 무엇이든 같은 팔레트가 얹힙니다. 브랜드 색은 플랫폼마다 달라지는 것이 아니기 때문입니다.
- **역할로만 덮어씁니다.** 노드 하나의 색을 바꾸는 길은 지금처럼 `Paint`이고, 이것은 앱 전체의 역할을 바꾸는 길입니다. 둘 다 있어야 하고, 둘이 같은 일을 하지 않습니다.

##### 14.10.2 앱이 정하지 않은 역할

- **앱이 정하지 않은 역할은 디자인 시스템의 값입니다.** 역할마다, 명암마다 따로 그렇습니다. 라이트의 `Primary`만 준 앱은 다크의 `Primary`가 디자인 시스템의 것이고, `Background`를 주지 않은 앱의 페이지는 그 시스템의 페이지입니다.
- **잉크 짝을 주지 않은 채움은 Renderer가 잉크를 고릅니다.** `Primary`만 주고 `OnPrimary`를 주지 않으면, 디자인 시스템의 `OnPrimary`가 새 `Primary` 위에서 그 짝에 요구되는 대비(13.1-2: 강조색 3:1, 컨테이너 4.5:1)를 지키면 그것을 쓰고, 지키지 못하면 흰색과 검정 중 대비가 더 높은 쪽을 씁니다. 브랜드 주황 위에 시스템의 흰 글자가 읽히면 흰 글자가 맞고, 노란 브랜드 위에서는 검정이 맞습니다. 앱이 잉크까지 정하면 그 값이 이깁니다.
  - 반대 방향(잉크만 주고 채움은 주지 않음)에는 이 규칙을 적용하지 않습니다. 앱이 잉크만 정했다면 그 잉크가 의도이고, 고칠 대상은 앱의 표입니다.
- **대비는 Rust에서 검사합니다.** `Palette::check(design_system)`이 주어진 디자인 시스템의 표 위에 팔레트를 얹은 결과에서 13.1-2, 13.1-3과 14장의 대비 기준을 어기는 쌍을 돌려줍니다. 앱은 이것을 자기 테스트에서 일곱 시스템 모두에 대해 부릅니다. 디버그 빌드는 시작할 때 어기는 쌍을 한 번 로그로 남깁니다. 실행을 막지는 않습니다. 대비가 조금 모자란 화면이 아무것도 안 뜨는 화면보다 낫고, 판단은 앱의 몫입니다.
- **고대비 모드에서는 앱 색을 쓰지 않습니다.** Windows의 대비 테마나 그에 해당하는 플랫폼 설정이 켜져 있으면 Renderer는 팔레트를 무시하고 디자인 시스템의 고대비 값(없으면 기본 값)으로 그립니다. 사용자가 접근성을 위해 고른 색을 브랜드가 덮어쓰면 안 됩니다(NFR-8).
- **시스템 강조색과의 관계**: 사용자가 OS에서 고른 강조색을 따르는 디자인 시스템(Fluent, Breeze, GNOME 같은)에서 앱이 `Primary`를 정하면 앱의 값이 이깁니다. 정하지 않으면 지금처럼 시스템의 답입니다. 앱이 브랜드 색을 정했다는 것이 "시스템 강조색을 따르지 않겠다"는 말이기 때문입니다.

##### 14.10.3 와이어: 테마 레코드

- **`SetTheme`(명령 태그 9)에 팔레트 참조 하나를 더합니다.** 지금 레코드는 머리 4바이트, u16 넷, 폰트 슬롯 아홉(`TYPE_ROLE_COUNT`, FR-23.2)으로 48바이트입니다. 그 뒤에 `palette: (offset: u32, len: u32)` 8바이트를 붙여 **56바이트**가 됩니다. 팔레트가 없으면 `len = 0`입니다.
- 팔레트 본체는 PR-4의 문자열·블롭 규약대로 같은 arena에 놓이는 고정 길이 항목의 나열입니다. 항목 하나는 8바이트 `(role: u16, scheme: u16, argb: u32)`이고, `scheme`은 `Light=1` 또는 `Dark=2`입니다. `FollowSystem`(3)은 항목에 쓸 수 없습니다. 그것은 고르는 방법이지 명암이 아니기 때문입니다.
- 고정 배열(역할 수 × 2)이 아니라 항목 나열인 이유는 둘입니다. "정하지 않음"을 값 하나(예: 0)로 표시하면 그 값이 색으로 쓰일 수 없게 되는데, 투명 검정도 색이고 앱이 실제로 쓸 수 있습니다. 그리고 `ColorRole`이 늘 때마다(13.1-3이 바로 그런 경우입니다) 레코드 길이가 바뀌지 않습니다.
- 크기: 역할 45개(13.1-3 이후) × 명암 2 = 최대 90항목, 720바이트입니다. 테마가 바뀔 때 한 번이고, 프레임마다 가지 않습니다.
- 모르는 역할 태그, 범위 밖의 `scheme`, 같은 (역할, 명암)의 중복, 8로 나누어지지 않는 `len`은 `ProtocolError`입니다. 프로세스는 죽지 않고, 테마는 잘못된 항목만 빼고 적용됩니다. 테마 하나가 통째로 버려지면 앱이 아무 색도 없는 화면으로 뜨는데, 잘못은 항목 하나에 있습니다.
- 레코드와 항목은 Rust 스키마에 정의하고, 코드젠(FR-7)이 Kotlin 쪽 디코더를 생성합니다. 스키마 해시가 움직이므로 양쪽이 어긋나면 핸드셰이크에서 잡힙니다.
- `Theme`은 지금 `Copy`이고 `const`로 만들 수 있습니다. 팔레트는 `&'static Palette`로 실어 그 성질을 유지합니다. 런타임에 만든 팔레트가 필요하면 `Theme`을 다시 보내는 앱 API(`use_theme`의 설정 경로)가 소유권을 가집니다.

##### 14.10.4 와이어 비용 정리

- `SetTheme` 레코드 48바이트에서 56바이트로. 명령 태그는 그대로 9입니다.
- 팔레트 블롭, 최대 720바이트, 테마가 바뀔 때만.
- `ColorRole` 태그 24-45 추가(13.1-3). 위젯 태그, 속성 태그, 이벤트 태그, 경계 진입점은 늘지 않습니다.

##### 14.10.5 수용 기준

1. `Primary`의 라이트·다크 값을 준 앱의 `Filled` 버튼이 일곱 디자인 시스템 모두에서 그 색으로 채워지고, 버튼의 모양과 눌림 표현은 시스템마다 다릅니다.
2. 같은 앱을 `FollowSystem`으로 띄우고 시스템 명암을 바꾸면, Host로 가는 이벤트와 Host가 보내는 `SetTheme` 없이 다크 값으로 바뀝니다.
3. 앱이 정하지 않은 역할(예: `Outline`)은 그 디자인 시스템의 값과 같습니다. 라이트 값만 준 역할의 다크 값은 디자인 시스템의 다크 값입니다.
4. `Primary`만 주고 `OnPrimary`를 주지 않은 경우, Renderer가 고른 잉크가 3:1 이상입니다. 밝은 노랑과 진한 남색 두 경우에서 서로 다른 잉크(검정과 흰색)가 나옵니다.
5. `Palette::check`가 대비를 어기는 쌍을 돌려주고, 어기지 않는 팔레트에는 빈 목록을 돌려줍니다.
6. 고대비 모드가 켜진 Windows에서 팔레트를 준 앱이 팔레트 없이 띄운 앱과 같은 색으로 그려집니다. 수동 확인입니다.
7. 56바이트 `SetTheme`과 팔레트 블롭이 PR-4 프로토콜 벡터에 들어가 양쪽에서 같은 바이트로 왕복합니다. 팔레트가 없는 테마는 `len = 0`입니다.
8. 모르는 역할 태그, `scheme = 3`, 중복 항목이 각각 `ProtocolError`가 되고, 나머지 항목은 적용되며, 프로세스가 죽지 않습니다.

코드 색 역할에 대한 기준(일곱 시스템의 대비, 리터럴 없는 강조, 시스템마다 다른 키워드 색, 스키마 회귀)은 13.1-3에 있습니다.

##### 14.10.6 열린 질문

- **팔레트를 디자인 시스템별로 줄 것인가.** 이 항목은 `adaptive`에서 한 팔레트가 어느 시스템에나 얹히게 했습니다. 브랜드가 "macOS에서는 유리 위에서 더 밝은 주황"처럼 시스템별 값을 원하면 `(design_system, role, scheme)` 항목이 필요합니다. 지금은 넣지 않았고, 요청한 앱의 브랜드 지침이 그것을 요구하는지 확인이 필요합니다.
- **씨앗 색에서 팔레트 만들기.** Material 3는 색 하나에서 배색 전체를 만드는 알고리즘(dynamic color)이 있습니다. 다른 여섯 시스템에는 없으므로 이 항목은 값을 직접 주게 했습니다. Material 3 전용 편의 함수(`Palette::from_seed`)를 Host 쪽에 둘지는 정하지 않았습니다.

#### 14.11 디자인 시스템 라이브러리의 두 층 (`Agreed`)

INTENT D19의 2026-10-03 결정입니다. 디자인 시스템은 `compose-multiplatform-core-extended`에서 Compose 라이브러리로 나가고, 두 층으로 나뉩니다.

> "컴포넌트 라이브러리로 하되" (2026-10-03)

> "2층으로 가고, 개들은 adaptive 디자인 시스템 패키지 명을 쓰면 될거같네." (2026-10-03)

##### 14.11.1 1층: 디자인 시스템마다 하나의 컴포넌트 라이브러리

- 패키지는 `org.thisisthepy.compose.material3`, `org.thisisthepy.compose.cupertino`, `org.thisisthepy.compose.fluent`, `org.thisisthepy.compose.gnome`, `org.thisisthepy.compose.breeze`, `org.thisisthepy.compose.deepin`, `org.thisisthepy.compose.liquidglass` 일곱 개입니다.
- **일곱 개 모두 material3의 모양을 가집니다.** `XxxTheme` 컴포저블(`CupertinoTheme`, `FluentTheme`, ...), `ColorScheme`, `Typography`, `Shapes`, 그리고 컴포넌트입니다. 공개 API의 이름, 매개변수, 기본값을 두는 방식은 `androidx.compose.material3`를 본뜹니다. 그 시스템에 대응물이 없는 material3 컴포넌트는 만들지 않아도 되지만, 있는 것은 material3와 같은 이름과 같은 모양의 시그니처를 씁니다.
- **`org.thisisthepy.compose.material3`는 `androidx.compose.material3` 위의 어댑터입니다.** 컴포넌트를 다시 그리지 않고 위임합니다. 어댑터가 하는 일은 공통 계약(14.11.3)의 역할과 토큰을 material3의 `ColorScheme`, `Typography`, `Shapes`로 옮기는 것입니다.
- 1층 라이브러리는 서로에게도, 2층에도 의존하지 않습니다. 의존하는 것은 공통 계약(14.11.3)과 Compose뿐입니다.

##### 14.11.2 2층: `org.thisisthepy.compose.adaptive`

- 디자인 시스템에 매이지 않는 중립 컴포넌트(`Button`, `TextField`, `DatePicker`, ...)를 둡니다. 각 컴포넌트는 **현재 테마의 1층 구현에 위임합니다.** 스스로 그리지 않습니다.
- 진입점은 `MaterialTheme`의 관례를 따릅니다.

```kotlin
@Composable
fun AdaptiveTheme(
    designSystem: DesignSystem = DesignSystem.platformDefault(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
)
```

- `designSystem`의 기본값은 실행 중인 플랫폼의 디자인 시스템입니다. 어느 플랫폼이 어느 시스템인지는 14.3의 adaptive 기본값과 같은 대응입니다.
- **compose-rust의 렌더러는 기본으로 이 층을 씁니다.** 14.3의 `Theme::adaptive`는 `AdaptiveTheme`의 기본값으로, `Theme::unified(ds)`는 `designSystem = ds`로 내려갑니다. 렌더러가 1층을 직접 부르는 길을 따로 두지 않습니다.
- 2층은 1층 일곱 개 전부와 공통 계약에 의존합니다.

##### 14.11.3 공통 계약: `org.thisisthepy.compose.designsystem`

- 역할 enum(`ColorRole`, `TypeRole`, `ShapeRole`, `SpaceRole`과 컴포넌트 변형), `DesignSystem` 인터페이스, 토큰을 둡니다. 두 층이 모두 이것에 의존합니다.
- **adaptive에 합치지 않습니다.** adaptive는 1층 전부에 의존하고 1층은 adaptive에 의존하면 안 됩니다. 계약이 adaptive 안에 있으면 1층이 계약을 쓰려고 adaptive에 의존하게 되어 순환이 생깁니다. 별도 모듈로 두면 의존은 `designsystem ← 1층 ← adaptive` 한 방향입니다.

##### 14.11.4 이름이 비슷한 다른 것

JetBrains의 `androidx.compose.material3.adaptive`는 적응형 레이아웃(창 크기 클래스) 라이브러리이며 `org.thisisthepy.compose.adaptive`와 무관합니다. 네임스페이스가 달라 부딪히지 않습니다.

##### 14.11.5 수용 기준

1. **"adaptive 컴포넌트 × 일곱 디자인 시스템" 표에 빈 칸이 하나라도 있으면 실패하는 테스트가 있습니다.** adaptive의 모든 컴포넌트가 일곱 시스템 각각에서 1층 구현으로 해석되어야 합니다. 1층에 대응물이 없는 칸도 비워 두지 않고, 그 시스템의 컴포넌트로 채웁니다.
2. **"material3 컴포넌트 목록 × 각 1층 라이브러리" 표를 진행 척도로 기록합니다.** 이 표는 통과와 실패를 가르지 않습니다. 1층 라이브러리 각각이 material3의 모양을 얼마나 덮었는지를 보여 주는 숫자이고, 바뀔 때마다 갱신합니다.
3. 1층 일곱 모듈 어디에도 `org.thisisthepy.compose.adaptive`에 대한 의존이 없고, 1층 모듈 사이의 의존도 없습니다. 빌드 설정을 읽는 검사로 확인합니다.
4. `AdaptiveTheme`을 인자 없이 부르면 실행 중인 플랫폼의 디자인 시스템이 선택되고, `darkTheme`의 기본값은 시스템의 명암을 따릅니다.
5. `org.thisisthepy.compose.material3`의 컴포넌트가 `androidx.compose.material3`의 같은 컴포넌트로 그려집니다(어댑터이며 다시 그린 것이 아님).
6. compose-rust 렌더러가 `Theme::adaptive`와 `Theme::unified`를 2층의 `AdaptiveTheme`으로 내립니다.

##### 14.11.6 플랫폼에 대응물이 없는 컴포넌트

> 소유자 결정(2026-10-03, 질문에 대한 답 "둘 다"): 1층은 대응물이 없어도 그 시스템의 스타일로 만들되 비표준으로 표시하고, 2층은 그 플랫폼이 실제로 쓰는 대체 방식으로 그립니다.

- **1층.** 플랫폼에 대응물이 없는 material3 컴포넌트도 그 디자인 시스템의 스타일로 만들어 둡니다(예: Cupertino 스타일 FAB, GNOME 스타일 Time Picker). 이런 컴포넌트는 KDoc에 "비표준"이라고 적고, 필요한 곳에는 opt-in 어노테이션(`@NonNativeComponent`)을 붙입니다. 대응표의 그 칸에는 대응물이 없다는 근거가 되는 플랫폼 가이드 링크를 함께 적습니다.
- **2층(adaptive).** 기본값은 그 플랫폼이 쓰는 대체 방식으로 그리는 것입니다. 예를 들어 Cupertino에서 FAB는 내비게이션 바 오른쪽의 + 버튼으로 그립니다. 어떤 컴포넌트가 어떤 대체 방식으로 가는지를 대응표에 적습니다.
- **대응표 테스트.** 1층 표는 모든 칸이 채워져야 하고, 비표준 표시가 붙은 칸을 따로 셉니다. 2층 표는 칸마다 플랫폼 대체 방식이 지정되어 있어야 하며, 지정되지 않은 칸이 있으면 실패합니다.
- **비표준 목록.** 시스템마다 다른 후보는 FAB, Navigation Rail, Snackbar, Chip, Time Picker, Tooltip(iOS), Carousel입니다. 각 칸은 그 시스템의 공식 가이드(Apple Human Interface Guidelines, Fluent 2, GNOME HIG, KDE HIG, Deepin 디자인 문서)의 링크로 확인한 뒤 확정합니다.

**1.0.0의 범위**: 1번 표의 행은 compose-rust가 먼저 쓰는 컴포넌트(FR-15의 위젯 어휘)입니다. 그 행들이 일곱 칸 모두 채워진 것이 1.0.0이고, material3 목록의 나머지(2번 표)는 그 뒤에 채웁니다.

### FR-15 위젯 어휘 (`Agreed`)

M0의 위젯 9개는 데모를 굴리는 데 필요했던 만큼이지 설계된 범위가 아니었습니다. 1.0의 목표 어휘를 여기에 고정합니다.

#### 15.1 기준: 세 시스템 공통 축

위젯은 **Material 3, Cupertino, Fluent 2 모두에 대응물이 있는 것만** 코어 스키마에 넣습니다. 세 곳에 다 있어야 FR-14의 역할 기반 설계가 성립합니다. 위젯이 역할만 내보내고 그리는 방법은 디자인 시스템이 정하려면, 애초에 세 시스템이 그 개념을 공유해야 합니다.

`androidx.compose.material3`를 1:1로 미러링하지 않는 이유가 이것입니다. `AssistChip`, `NavigationRail`, `ExtendedFloatingActionButton`은 Material 고유 어휘이고 Cupertino에 대응물이 없습니다. 코어에 넣으면 Cupertino와 Fluent는 Material을 흉내 내는 스킨이 되고, FR-14.2의 "네 번째 디자인 시스템을 추가할 때 위젯 코드를 건드리지 않는다"가 무너집니다. 그런 위젯은 FR-11 확장 패키지가 맡습니다.

#### 15.2 코어 어휘

기존(태그 1-9): `Column=1, Row=2, Box=3, Text=4, TextField=5, Button=6, Spacer=7, LazyColumn=8, ScrollColumn=9`

추가(태그 10-25):

| 태그 | 위젯 | 세 시스템 대응 |
|---|---|---|
| 10 | `Image` | 공통. FR-16 에셋 핸들을 받습니다 |
| 11 | `Icon` | 공통. FR-16 에셋 핸들과 `ColorRole` 틴트 |
| 12 | `Checkbox` | Material Checkbox / UIKit 체크 / Fluent CheckBox |
| 13 | `RadioButton` | 공통 |
| 14 | `Switch` | Material Switch / UISwitch / Fluent Toggle |
| 15 | `Slider` | 공통 |
| 16 | `ProgressIndicator` | 공통. `determinate: bool`, `circular: bool` |
| 17 | `Divider` | 공통. `vertical: bool` |
| 18 | `Card` | Material Card / 그룹 박스 / Fluent Card |
| 19 | `Surface` | 배경과 고도를 갖는 컨테이너. FR-13.5 Elevation을 씁니다 |
| 20 | `Dialog` | 공통. 모달. 위치와 애니메이션은 디자인 시스템 규칙 |
| 21 | `Menu` | 공통. 앵커 노드에 붙는 팝업 |
| 22 | `Tabs` | Material TabRow / UISegmentedControl / Fluent Pivot |
| 23 | `TopAppBar` | Material TopAppBar / UINavigationBar / Fluent CommandBar |
| 24 | `LazyRow` | FR-8 윈도잉의 가로 축 |
| 25 | `Tooltip` | 공통. 데스크톱 전용 동작이 아니라 접근성 설명으로도 쓰입니다 |
| 26 | `Canvas` | FR-17의 커스텀 드로잉 |
| 27 | `DatePicker` | Material DatePicker / `UIDatePicker` / Fluent CalendarDatePicker |
| 28 | `TimePicker` | Material TimePicker / `UIDatePicker`(시간 모드) / Fluent TimePicker |
| 29 | `Dropdown` | 목록에서 하나 고르기. Material ExposedDropdownMenu / Cupertino Picker / Fluent ComboBox |

#### 15.2.1 선택기(picker)에 대한 주의

날짜와 시간 선택기는 세 시스템에서 **겉모습만 다른 것이 아니라 상호작용 자체가 다릅니다.** Material은 달력 격자와 다이얼, Cupertino는 휠, Fluent는 달력 플라이아웃입니다. FR-14의 역할 기반 설계가 특히 중요한 자리입니다. 위젯은 **값과 범위와 변경 이벤트만** 내보내고, 어떤 방식으로 고르게 할지는 전적으로 디자인 시스템이 정합니다. Host가 "휠로 고르게 하라"고 지시할 수 있는 속성을 두면 안 됩니다.

- 값은 에폭 기준 정수로 주고받습니다. 날짜는 `days: i64`(1970-01-01 기준), 시간은 `minutes: u32`(자정 기준). 고정 레이아웃을 유지하고 타임존 해석이 경계를 넘지 않습니다.
- **타임존과 로케일은 Renderer가 소유합니다(D5).** 표시 형식, 주 시작 요일, 12/24시간제는 플랫폼 설정을 따릅니다. Host가 형식 문자열을 보내는 경로는 두지 않습니다. 보내면 플랫폼을 따라간다는 말이 거짓이 됩니다.
- 범위 제한은 `min`과 `max`로 같은 단위로 보냅니다.

#### 15.2.10 칩, 떠 있는 동작 버튼, 가로 스크롤

15.3이 앞의 둘을 "Material 고유 어휘"라며 뺐습니다. 21.1이 `NavigationBar`와 `BottomSheet`에 대해 같은 실수를 한 번 바로잡았고, 이 둘도 같습니다. 기준은 이름이 아니라 개념입니다.

- **`Chip`**: 고르거나 거르는 작은 토큰입니다. Material은 칩, Cupertino는 둥근 필터 알약, Fluent는 태그 버튼, GNOME은 pill 버튼입니다. 여섯 시스템 모두에 있습니다. 선택 상태(`selected`)와 클릭만 내보내고, 모양은 디자인 시스템이 정합니다.
- **`FloatingAction`**: 화면의 기본 동작 하나를 눈에 띄게 두는 것입니다. Material은 떠 있는 원형 버튼, Cupertino는 바 위의 큰 동작 버튼이나 오른쪽 위의 더하기, Fluent는 강조 버튼입니다. **떠 있는 것은 Material의 답이지 개념이 아닙니다.** 어디에 어떻게 놓을지는 디자인 시스템이 정하고, Host는 아이콘과 라벨과 클릭만 줍니다.
- **`ScrollRow`**: 13.6이 미뤄 둔 것입니다. `ScrollColumn`의 축만 바꾼 것이고 새 프로토콜이 없습니다.

수용 기준: 셋 각각이 여섯 디자인 시스템에서 렌더링되고 `DesignShowcase`에 나타나며, `FloatingAction`이 최소 두 시스템에서 **서로 다른 자리에** 놓입니다. 같은 자리에 같은 모양으로 여섯 번 그려지면 실패입니다.

여기까지가 15.2의 어휘 32개이고, FR-21이 탐색과 시트로 35개까지 넓힙니다.

#### 15.2.11 배지 (`Draft`)

다른 것에 붙어서 그것의 상태를 짧게 알리는 작은 표시입니다. 개수(읽지 않은 3개), 점(새것이 있음), 짧은 글자(새로움) 셋 중 하나를 싣습니다. Material은 `Badge`/`BadgedBox`, iOS와 macOS는 탭 바와 독 아이콘의 빨간 개수 표시, Fluent는 `CounterBadge`와 `PresenceBadge`, GNOME은 사이드바 행의 개수 알약입니다. 일곱 시스템 모두에 대응물이 있습니다.

- **`Badge` 위젯 하나, 쓰임 둘.** 자식이 하나 있으면 그 자식에 붙습니다(아이콘, 아바타, 탐색 항목). 자식이 없으면 혼자 서는 표시가 됩니다(목록 행 끝의 개수). 이름은 Compose의 `Badge`이고, 자식에 붙는 경우는 `BadgedBox`에 해당합니다.
- 속성: `value`(개수, 선택), `text`(짧은 글자, 선택), 둘 다 없으면 점입니다. 색은 `ColorRole`로 줍니다(기본은 `Error`). 리터럴 색을 받는 경로는 두지 않습니다.
- **붙는 자리와 모양, 큰 수의 표기는 디자인 시스템이 정합니다.** 오른쪽 위에 겹칠지, 행 끝에 둘지, 99를 넘으면 `99+`로 줄일지는 시스템의 규칙입니다. Host가 위치나 상한을 지정하는 속성을 두지 않습니다.
- 상태 표시(실행 중 스피너, 승인 대기 경고 아이콘)는 배지가 아닙니다. 그것은 `ProgressIndicator`와 `Icon`이고, 배지는 그 옆에 개수나 점을 붙일 때 씁니다.
- 접근성: 배지의 내용은 붙은 자식의 접근성 설명에 덧붙습니다("받은 편지함, 읽지 않음 3개"). 배지만 따로 읽히지 않습니다.
- 위젯 태그 40. 속성 태그는 15.2.2의 블록 규칙에 따라 새 블록을 엽니다. 위젯과 속성은 Rust 스키마(`schema.rs`)에 정의하고 Kotlin 쪽은 codegen이 생성합니다(FR-7).

수용 기준:
1. 배지가 일곱 디자인 시스템에서 그려지고 `DesignShowcase`에 나옵니다. 자식에 붙은 것과 혼자 선 것, 개수, 점, 짧은 글자를 모두 보여 줍니다.
2. 같은 개수(예: 120)가 최소 두 시스템에서 서로 다르게 표기되거나 서로 다른 자리에 붙습니다. 일곱 번 같은 모양이면 실패입니다.
3. 자식에 붙은 배지의 내용이 그 자식의 접근성 설명에 들어갑니다.
4. 값만 바뀌면 그 노드의 속성 하나만 바뀌고 자식은 다시 그려지지 않습니다.

#### 15.2.12 분할 창 (`Draft`)

옆 칸과 본문 둘을 나란히 놓고, 그 사이의 경계선을 사용자가 끌어 옆 칸의 폭을 바꾸는 것입니다. 요청한 앱(Ember, AI IDE 클라이언트)은 데스크톱에서 세션 목록과 대화를 두 열로 놓고, 폰에서는 목록을 먼저 보이고 하나를 고르면 대화로 넘어가는 한 열 쌓기로 씁니다.

일곱 시스템 모두에 대응물이 있습니다. Apple은 `NSSplitView`/`NSSplitViewController`(사이드바는 끌어서 폭을 바꾸고 끝까지 밀면 접힙니다)와 iPad의 `UISplitViewController`, Fluent는 `SplitView`와 `TwoPaneView`, GNOME은 `AdwNavigationSplitView`와 `AdwOverlaySplitView`(좁아지면 쌓기나 덮기로 바뀝니다), KDE는 Kirigami의 열 보기와 `QSplitter`, Material 3는 adaptive의 list-detail 창 배치(`ListDetailPaneScaffold`)와 그 끌기 손잡이(`PaneExpansionDragHandle`)입니다. 이름은 Compose에 안정 API로 같은 이름이 없으므로 개념 이름 `SplitPane`을 씁니다.

**`Navigation`(FR-21)과는 다른 것입니다.** `Navigation`은 목적지 집합 중 하나를 고르는 개념이고 서랍의 폭은 디자인 시스템이 정합니다. 분할 창은 내용 두 덩어리를 나란히 놓는 배치이고, 옆 칸에 무엇이 들어가는지(세션 목록, 파일 트리)는 Host가 정합니다. 둘은 겹쳐 쓸 수 있습니다. `Navigation`의 본문 안에 분할 창이 들어가는 것이 정상적인 모양입니다.

##### 15.2.12.1 모양

- **자식은 정확히 둘입니다.** 첫째가 옆 칸, 둘째가 본문입니다. 옆 칸은 읽는 방향의 시작 쪽에 놓이고, 오른쪽에서 왼쪽으로 읽는 로케일에서는 거울처럼 뒤집힙니다. 자식 수가 둘이 아니면 `ProtocolError`이고 프로세스는 죽지 않으며, 그 노드는 자식들을 세로로 쌓아 그립니다. `Navigation`이 자식을 인덱스로 나누지 않은 이유(목적지 수가 조건에 따라 달라진다)가 여기에는 없습니다. 칸은 늘 둘이고, 옆 칸을 감추는 것은 자식을 빼는 것이 아니라 접는 것입니다(15.2.12.3).
- **경계선의 생김새와 잡히는 폭은 디자인 시스템이 정합니다.** 머리카락 선인지, 손잡이가 있는지, 보이는 선보다 잡히는 띠가 얼마나 넓은지, 마우스를 올렸을 때 커서가 무엇으로 바뀌는지는 `ComponentRules`의 답입니다(FR-14.1). Host가 두께나 색을 정하는 속성을 두지 않습니다. 13.9의 구분선과 같은 이유입니다.
- 옆 칸의 기본 폭도 디자인 시스템이 정합니다. Host가 `Value`를 보내지 않으면 그 시스템의 사이드바 폭으로 엽니다.

##### 15.2.12.2 끄는 동안의 폭은 Renderer의 상태이고, 끝난 폭은 Host가 압니다

- **끄는 동안 경계를 넘는 것은 없습니다(D5).** 포인터를 따라가는 경계선 위치는 스크롤 위치, 슬라이더 엄지(15.2.4)와 같은 부류입니다. 끄는 매 프레임 Rust를 왕복하면 그만큼 VirtualDom을 돌고, 그것은 NFR-9 위반입니다.
- **끌기가 끝나면 Host가 최종 폭을 한 번 받습니다.** 기존 `OnValueChange`(속성 6) 핸들러로 기존 `ValueChanged`(이벤트 16)를 보내고, 값은 옆 칸의 폭(dp)입니다. 새 이벤트 태그는 없습니다.
  - Host가 알아야 하는 이유는 **다음 실행에서 같은 폭으로 여는 것**입니다. macOS의 사이드바는 폭을 기억하고(`NSSplitView`의 autosave), 기억하지 않는 사이드바는 매번 다시 끌어야 하는 사이드바입니다. 그런데 기억하려면 저장소가 필요하고, 저장소(설정 파일, 그 입출력)는 도메인 상태이며 Host 워커의 일입니다(PR-3). Renderer에는 앱 단위 저장 경로가 없고, 이 기능을 위해 만들 이유도 없습니다.
  - 끝에 한 번이므로 비용은 끌기 한 번에 이벤트 한 건입니다. 슬라이더가 이미 같은 규약입니다.
- **`Value`(속성 51)가 옆 칸 폭의 시작값이고, 바깥에서 들어온 변경을 전달하는 통로입니다.** 15.2.4의 `Checked`/`Value`와 같은 규약입니다. 앱은 받은 폭을 자기 신호에 다시 써 두어야 하고, 그렇게 하지 않으면 Host가 같은 값을 다시 보낼 때 diff가 아무것도 보내지 않습니다. 이것은 슬라이더와 토글에 이미 있는 규칙이며, 새 규칙이 아닙니다.
- 창 크기가 바뀌어 옆 칸이 줄어드는 것(15.2.12.3)은 사용자가 끈 것이 아니므로 보고하지 않습니다. 창이 다시 넓어지면 사용자가 마지막으로 정한 폭으로 돌아갑니다. 창을 잠깐 좁혔다고 저장된 폭이 줄어들면 안 되기 때문입니다.

##### 15.2.12.3 최소, 최대, 접힘

- **`Min`(52)과 `Max`(53)가 옆 칸 폭의 범위(dp)입니다.** 보내지 않으면 디자인 시스템의 값을 씁니다. 경계선은 이 범위 밖으로 끌리지 않습니다.
- **본문에도 최소 폭이 있고, 그것은 디자인 시스템이 정합니다.** 창이 좁아지면 먼저 옆 칸이 `Min`까지 줄고, 그래도 본문이 자기 최소보다 좁아지면 표현이 바뀝니다(15.2.12.4). 본문이 읽을 수 없을 만큼 좁아진 두 열은 어느 시스템에서도 정답이 아닙니다.
- **접을 수 있는지는 앱이 정합니다.** 새 속성 `Collapsible`(bool, 기본 거짓)입니다. 참이면 `Min`보다 디자인 시스템이 정한 거리 이상 더 끌었을 때 옆 칸이 접힙니다. `NSSplitView`의 `canCollapseSubview`와 같은 동작입니다. 어떤 옆 칸은 감출 수 있고(세션 목록) 어떤 것은 감추면 화면이 성립하지 않으므로(설정 화면의 항목 목록), 이것은 디자인 시스템이 아니라 앱의 의미입니다.
- **접힘도 같은 이벤트입니다.** 접히면 `ValueChanged`가 `0.0`으로 한 번 갑니다. Host가 `Value = 0`을 보내면 접힌 채로 엽니다. 펼치면(경계선을 다시 끌어내거나, 키보드로, 또는 Host가 0이 아닌 `Value`를 보내서) 펼친 폭이 다시 한 번 갑니다. 접힘을 따로 나르는 속성과 이벤트를 두면 "폭이 몇인가"와 "보이는가"라는, 한 숫자로 말할 수 있는 것에 이름이 둘 생깁니다. 접기 전 폭은 Renderer가 기억해 두었다가 펼칠 때 씁니다.
- 앱이 "사이드바 토글" 버튼을 두려면 `Value`를 0과 마지막 폭 사이에서 바꿔 보내면 됩니다. 아이콘은 기존 `IconRole::Sidebar`(19)입니다.

##### 15.2.12.4 좁은 자리에서는 쌓습니다

- **표현은 Renderer가 고릅니다.** 21.2가 탐색에 대해 Host 분기를 거부한 세 이유가 그대로 적용됩니다. Renderer가 이미 폭을 알고, 분기하면 칸의 노드가 지워졌다 다시 만들어지며, 선언 하나가 폰과 데스크톱에서 그 플랫폼답게 나오는 것이 이 프로젝트의 주장입니다.
- 표현은 셋입니다. `SideBySide`(두 열), `Overlay`(본문이 자리를 다 쓰고 옆 칸은 그 위로 덮여 나옴), `Stacked`(한 번에 한 칸). `ComponentRules`의 `splitPane(WindowSizeClass)`가 답하고, 기본 대응은 `Compact→Stacked`, `Medium→SideBySide`(옆 칸 `Min`과 본문 최소의 합이 들어가지 않으면 `Overlay`), `Expanded→SideBySide`입니다. GNOME이 `Medium`에서 `Overlay`로 답하는 것처럼 시스템이 달리 답해도 위젯 코드는 바뀌지 않습니다.
- **크기 클래스는 창이 아니라 분할 창 자신의 폭으로 셉니다.** 경계는 FR-20의 600dp/840dp 그대로입니다. 분할 창은 `Navigation`의 본문처럼 창보다 좁은 자리에 놓일 수 있고, 그때 창의 클래스로 고르면 넓은 창 안의 좁은 칸에 두 열을 억지로 넣게 됩니다. 이 측정은 Renderer 안에서 끝나며 이벤트를 보내지 않습니다. `LazyGrid`의 `min_column_width`(FR-25)와 같은 종류의 판단입니다.
- **쌓였을 때 어느 칸이 보이는지는 `SelectedIndex`(42)입니다.** 0이 옆 칸, 1이 본문입니다. 폰에서 세션을 고르는 것은 도메인 사건이므로(어느 세션을 열지 Host가 압니다) Host가 1을 보냅니다. Renderer는 칸 전환 애니메이션을 스스로 돌립니다(D5). 두 열이나 덮기 표현에서 이 속성은 읽지 않습니다.
- **본문에서 돌아오는 것은 Renderer가 받습니다.** 시스템 뒤로 가기(Android의 뒤로, iOS의 가장자리 쓸기)와 디자인 시스템이 그리는 뒤로 버튼입니다. 돌아오면 Renderer가 곧바로 옆 칸을 보이고, `OnDismiss`(41) 핸들러로 `Clicked`를 한 번 보냅니다. Host는 그것을 받아 선택을 비우고 `SelectedIndex`를 0으로 맞춥니다. `Sheet`(21.3)와 같은 규약이고 새 이벤트 태그가 없습니다.
- 표현이 바뀌는 동안 두 칸의 노드는 새로 만들어지지 않습니다. 21.7의 2번과 같은 성질입니다.
- 창을 좁혔다가 다시 넓히면 옆 칸은 사용자가 정한 폭으로 돌아옵니다.

##### 15.2.12.5 키보드와 접근성

- **경계선은 포커스를 받습니다.** 탭 순서는 옆 칸의 내용, 경계선, 본문의 내용 순입니다. 마우스가 없는 사용자에게 끌 수 없는 경계선은 고정된 경계선이고, 그러면 폭을 바꾼다는 기능이 그 사용자에게는 없습니다.
- 포커스를 받은 경계선에서 왼쪽·오른쪽 화살표는 디자인 시스템이 정한 한 걸음만큼 폭을 바꾸고(읽는 방향을 따라 뒤집힙니다), Home과 End는 `Min`과 `Max`로 보냅니다. `Collapsible`이면 Enter가 접고 펼칩니다. 이 키들은 Renderer가 소비하고 Host의 `OnKeyDown`으로 가지 않습니다(FR-12). 경계선에 포커스가 있을 때의 화살표는 경계선의 것이기 때문입니다.
- **키보드로 바꾼 폭도 끝난 뒤에 한 번만 보고합니다.** 키를 누르고 있어 반복되는 동안에는 보내지 않고, 키를 뗄 때 한 번 보냅니다. 끄는 것과 같은 규칙입니다.
- 플랫폼에 사이드바를 여닫는 표준 단축키가 있으면 Renderer가 그것을 받습니다(macOS는 Control Command S, 메뉴 항목 "사이드바 보기/가리기"). `Collapsible`일 때만입니다.
- 접근성 의미: 경계선은 분할자(splitter) 역할과 현재 값, 범위를 가진 조절 가능한 요소로 드러납니다. 화면 낭독기가 "사이드바, 280"처럼 읽고, 늘리기·줄이기 동작으로 바꿀 수 있습니다. 이름은 `Text`(속성 1)로 줄 수 있고, 주지 않으면 Renderer가 플랫폼 로케일의 "사이드바"를 씁니다.

##### 15.2.12.6 와이어 비용

- 위젯 태그 42 `SplitPane`.
- 새 속성은 하나입니다. `Collapsible`(bool), 속성 태그 90. 15.2.2의 규칙대로 `SplitPane`의 블록 90-99를 엽니다.
- 나머지는 기존 태그를 씁니다. `Value`(51), `Min`(52), `Max`(53), `OnValueChange`(6), `SelectedIndex`(42), `OnDismiss`(41), `Text`(1).
- 새 이벤트 태그, 새 명령 태그, 새 경계 진입점이 없습니다.
- 위젯과 속성은 Rust 스키마(`schema.rs`)에 정의하고 Kotlin 쪽은 codegen이 생성합니다(FR-7). 스키마 해시가 움직입니다.
- Renderer 쪽은 `ComponentRules`에 `splitPane(WindowSizeClass)` 하나를 더하고, 21.6처럼 기본 구현을 둡니다. 일곱 시스템 모두 재정의하지 않아도 컴파일되고 자기 색으로 그려집니다.

##### 15.2.12.7 수용 기준

1. 1100dp 창에서 분할 창이 두 열로 그려지고, 경계선을 끄는 동안 Host로 가는 이벤트가 없으며, 놓으면 `ValueChanged`가 **정확히 한 번** 옆 칸 폭(dp)을 싣고 갑니다.
2. `Value = 320`으로 연 분할 창의 옆 칸이 320dp입니다. `Min = 200`, `Max = 400`이면 경계선이 그 범위 밖으로 끌리지 않습니다.
3. `Collapsible`인 분할 창에서 `Min`보다 더 끌면 옆 칸이 접히고 `ValueChanged`가 `0.0`으로 한 번 갑니다. `Value = 0`으로 열면 접힌 채로 열립니다. 펼치면 접기 전 폭으로 돌아오고 그 폭이 한 번 보고됩니다. `Collapsible`이 거짓이면 접히지 않습니다.
4. 같은 선언이 폭 500dp에서 한 칸씩 쌓이고(`Stacked`), 1100dp에서 두 열로 그려집니다. 넓혔다 좁히는 동안 두 칸의 `Create` 레코드가 나오지 않습니다.
5. 넓은 창 안의 500dp 자리에 놓인 분할 창이 쌓기로 그려집니다. 크기 클래스를 창이 아니라 자기 폭으로 세는 것을 확인합니다.
6. 쌓기에서 `SelectedIndex = 1`이면 본문이 보이고, 시스템 뒤로 가기나 뒤로 버튼으로 돌아오면 옆 칸이 보이며 `OnDismiss`로 `Clicked`가 정확히 한 번 갑니다.
7. 창을 좁혀 옆 칸이 줄어드는 동안 이벤트가 가지 않고, 다시 넓히면 사용자가 정한 폭으로 돌아옵니다.
8. 키보드만으로 경계선에 포커스를 옮기고 화살표로 폭을 바꿀 수 있으며, 키를 뗄 때 `ValueChanged`가 한 번 갑니다. Home과 End가 `Min`과 `Max`로 보냅니다.
9. 화면 낭독기가 경계선을 이름과 현재 폭을 가진 조절 요소로 읽습니다(VoiceOver, 내레이터, Orca). 이 항목은 수동 확인이며 §7과 같은 방식으로 native-image 빌드에서 기록합니다.
10. 일곱 디자인 시스템에서 렌더링되고 `DesignShowcase`에 나옵니다. 경계선의 생김새가 최소 두 시스템에서 다르고(머리카락 선과 손잡이), 최소 한 시스템이 `Medium`에서 다른 표현을 답합니다.
11. 자식이 둘이 아닌 분할 창은 `ProtocolError`를 보내고 프로세스가 죽지 않습니다.
12. `Collapsible`이 PR-4 프로토콜 벡터에 들어가 Rust와 Kotlin 양쪽에서 같은 바이트로 왕복합니다.

##### 15.2.12.8 열린 질문

- **`Navigation` 서랍의 폭.** macOS의 사이드바는 끌어서 폭을 바꿀 수 있는데, FR-21의 `Navigation` 서랍은 폭을 디자인 시스템이 정하고 끌 수 없습니다. 분할 창과 같은 규칙(끝난 폭을 Host에 보고)을 서랍에도 줄지는 FR-21의 변경이라 이 항목에 넣지 않았습니다. 요청한 앱의 런처가 `Navigation`과 `SplitPane` 중 무엇으로 세션 목록을 그릴지에 따라 필요 여부가 갈립니다.

#### 15.2.2 속성 태그 블록

FR-13.8이 코어 속성 태그를 26까지 썼고, FR-11 확장 예제가 27을 가져갔습니다. 15.2의 새 위젯들은 서로 다른 시점에 구현되므로, 태그가 겹치지 않도록 블록을 미리 나눠 둡니다. 블록 안에서는 앞에서부터 채우고, 남는 자리는 비워 둡니다.

| 위젯 태그 | 속성 태그 블록 |
|---|---|
| 10-17 (`Image` ~ `Divider`) | 28-39 |
| 18-25 (`Card` ~ `Tooltip`) | 40-49 |
| 26-29 (`Canvas` ~ `Dropdown`) | 50-59 |
| 30-32 (`Navigation` ~ `Sheet`) | 60-69 (FR-21) |
| 40 (`Badge`) | 80-89 (15.2.11) |
| 42 (`SplitPane`) | 90-99 (15.2.12) |
| 43 (`CodeEditor`) | 100-109 (FR-38) |

위젯 태그 40부터는 **새 속성이 필요한 위젯 하나마다 열 개짜리 블록 하나**를 엽니다. 블록은 그 시점에 어디서든 쓰인 마지막 속성 태그 뒤의 첫 십 단위에서 시작하고, 예약된 태그는 쓰인 것으로 셉니다(67-74는 제스처, 75는 버튼의 종류, 76은 `Section`). 그래서 70-79는 새 블록으로 열지 않고, 배지가 80을 열었습니다. 두 작업이 어느 순서로 들어오든 겹치지 않게 하려는 것입니다. 열 개로 모자라는 위젯은 그다음 빈 블록을 하나 더 엽니다. 속성이 없는 위젯(`SelectionContainer`, 41)은 블록을 열지 않습니다. 위젯 태그 37-39는 따로 들어오는 `ScrollRow`, `Chip`, `FloatingAction`에 예약되어 있고, 그 위젯들이 새 속성을 필요로 하면 같은 규칙으로 그 시점의 다음 빈 블록을 엽니다.

#### 15.2.3 컨테이너, 오버레이, 탭(태그 18-25)

**새 속성은 셋뿐입니다**: `Open=40`(bool), `OnDismiss=41`(핸들러 id), `SelectedIndex=42`(u32).

**새 이벤트 태그는 만들지 않습니다.** 해제와 탭 선택은 값을 싣지 않는 알림이므로 기존 `Clicked`(이벤트 태그 1)를 각자의 핸들러 id로 보냅니다. 어떤 탭이 선택됐는지는 노드 id가 말해 줍니다.

- **D5**: Dialog의 열림, Menu의 펼침, Tabs의 선택, 스크롤 위치는 전부 Renderer의 상태입니다. Host 속성은 초기값과 바깥에서 들어온 변경을 주고, Renderer는 바뀐 사실만 이벤트로 알립니다. 여는 애니메이션이나 탭 전환이 프레임마다 Rust를 왕복하지 않습니다.
- **자식 규약**: `Menu`는 자식 0이 앵커이고 나머지가 팝업 항목입니다. `Tooltip`은 자식이 앵커이고 설명은 `text` 속성입니다. `Tabs`의 자식이 곧 탭이며, 탭 `i`를 고르면 자식 `i`의 `OnClick` 핸들러로 `Clicked`를 보냅니다. `Dialog`, `Card`, `Surface`, `TopAppBar`의 자식은 그대로 내용입니다.
- **역할만 내보냅니다**(FR-14.1): 카드의 배경과 모서리, 대화상자의 스크림과 위치, 메뉴 팝업의 그림자, 탭 인디케이터의 모양, 툴팁의 지연 시간은 전부 디자인 시스템의 규칙입니다. Host가 지정할 속성을 두지 않습니다. Renderer 쪽 확장은 `ComponentRules`에 `container(ContainerRole)`와 `tabs()` 둘을 더하는 것으로 끝나고, 네 번째 디자인 시스템은 여전히 구현 하나입니다.
- **고도**: `Surface`와 `Card`는 `Modifier::Elevation`(FR-13.5)을 그대로 받습니다. 값이 없으면 디자인 시스템이 정한 기본 고도를 씁니다.
- **`LazyRow`**: FR-8의 `item_count`, `item_key`, `on_range_requested`를 그대로 씁니다. 가로 축이라는 것 말고 다른 점이 없어야 하고, 선읽기 버퍼도 같은 이유로 Renderer가 소유합니다.

#### 15.2.4 선택 컨트롤과 표시기(태그 12-17)

`Checkbox=12`, `RadioButton=13`, `Switch=14`, `Slider=15`, `ProgressIndicator=16`, `Divider=17`입니다. 여섯 모두 다른 위젯과 같은 Modifier 11종을 그대로 받습니다.

**새 속성 태그는 다섯입니다.** 15.2.2의 28-39 블록에서 `Image`와 `Icon`이 쓰는 28-31 뒤를 이어 32부터 채웁니다.

| 태그 | 속성 | 값 | 쓰는 위젯 |
|---|---|---|---|
| 32 | `Checked` | bool | `Checkbox`, `RadioButton`, `Switch` |
| 33 | `Steps` | i64 | `Slider`의 양 끝 사이 불연속 지점 개수. 0이면 연속 |
| 34 | `Determinate` | bool | `ProgressIndicator` |
| 35 | `Circular` | bool | `ProgressIndicator` |
| 36 | `Vertical` | bool | `Divider` |

**값과 범위는 새 태그를 따지 않습니다.** `Slider`의 위치와 `ProgressIndicator`의 진행도는 15.2.5가 이미 배정한 `Value`(태그 51)를 쓰고, `Slider`의 양 끝은 `Min`(52)과 `Max`(53)를 씁니다. 같은 개념을 스키마에 두 번 넣지 않는다는 13.1의 규칙이 블록 배정보다 우선합니다. 태그는 하나인데 값의 타입은 위젯이 정합니다. 픽커는 에폭 정수를, 슬라이더는 f32를 담습니다.

`RadioButton`의 선택 여부도 `Checked`를 씁니다. 와이어에서는 "켜져 있는가"라는 bool 하나입니다. `selected`라는 이름은 Compose 관례이므로 rsx 속성 이름으로만 남습니다(PR-7).

변경 핸들러는 기존 `OnValueChange`(태그 6)를 그대로 씁니다.

- **네 컨트롤 모두 15.2.5의 `ValueChanged`(이벤트 태그 16)를 씁니다.** 토글 셋은 새 상태를 0.0 또는 1.0으로 싣고, `Slider`는 값 자체를 싣습니다. bool을 위해 이벤트를 하나 더 만들면 같은 개념("이 컨트롤의 값이 이렇게 되었다")에 이름이 둘 생기고, 컨트롤이 늘 때마다 또 나눠야 합니다.
- **드래그 중과 누르는 중의 상태는 Renderer가 소유합니다(D5).** 손가락을 따라가는 슬라이더 위치와 토글의 전환 애니메이션이 프레임마다 경계를 넘지 않습니다. `Checked`와 `Value`는 그 상태의 시작값이고 바깥에서 들어온 변경을 전달하는 통로입니다.
- **역할만 내보냅니다**(FR-14.1). 체크 표시의 모양과 크기, 스위치가 리플을 내는지 눌린 동안 흐려지는지, 트랙과 엄지의 치수, 부정형 표시기가 도는 속도, 구분선의 두께와 색은 전부 디자인 시스템의 규칙입니다. Host가 지정할 속성을 두지 않습니다. Renderer 쪽 확장은 `ComponentRules`에 `controls()` 하나를 더하는 것으로 끝나고, 일곱 번째 디자인 시스템은 여전히 구현 하나입니다.
- `ProgressIndicator`는 `determinate`가 거짓이면 `Value`를 읽지 않습니다. `circular`는 모양을 고르는 것이지 치수를 정하는 것이 아닙니다.
- `Divider`는 `vertical`이 정하는 축 말고는 아무것도 싣지 않습니다.

**Material 3는 `androidx.compose.material3`가 그립니다.** 나머지 시스템은 우리 드로잉입니다.

그 라이브러리는 이미 Renderer의 의존성입니다(FR-11의 확장 예제가 씁니다). Material 3 컨트롤을 손으로 다시 그리면 리플, 상태 레이어, 접근성 시맨틱, 최소 터치 영역, 모션을 전부 따라 만들어야 하고, 그렇게 만든 것은 자신이 따른다고 주장하는 명세와 반드시 어긋나기 시작합니다. 스펙을 그대로 구현한 것이 이미 있는데 흉내를 유지보수할 이유가 없습니다. Cupertino와 Fluent에는 그런 라이브러리가 없으므로 우리가 그립니다.

이것이 15.1의 기준과 충돌하지 않습니다. 15.1은 **코어 어휘에 무엇을 넣을지**의 기준입니다. 여섯 컨트롤은 세 시스템 모두에 대응물이 있으므로 그 시험을 이미 통과했고, 통과한 위젯을 어느 시스템에서 무엇으로 그리는지는 그 시스템의 규칙입니다. Cupertino가 Material을 흉내 내는 스킨이 되는 경우가 15.1이 막으려는 것이고, Material이 Material로 그려지는 것은 그 반대입니다.

**그래서 `ComponentRules`가 답하는 것이 둘입니다.**

- `controls()`는 **치수와 색**을 답합니다(`ControlsStyle`). 우리 드로잉이 읽습니다.
- `controlWidgets`는 **누가 그리는지**를 답합니다. 기본값이 `controls()`에서 그리는 구현이므로, 일곱 번째 디자인 시스템은 여전히 `controls()` 하나만 구현하면 되고 이 속성은 건드리지 않습니다. Material 3만 이 기본값을 덮는 유일한 arm입니다.

두 갈래로 나눈 이유는 기본값이 없으면 새 시스템마다 컨트롤 여섯 개의 컴포저블을 처음부터 쓰게 되기 때문입니다. 그것은 "디자인 시스템 하나 = 구현 하나"라는 FR-14.2의 계약을 깨뜨립니다.

**FR-11의 `LinearProgressIndicator`(태그 100)와는 다른 위젯입니다.** 그쪽은 확장 메커니즘이 동작한다는 것을 보이는 예제입니다. 가로 막대만 있고, 결정형만 있고, 어느 디자인 시스템이 켜져 있든 Material 3로 그려지며, 코어가 아니라 확장 태그 블록에 있습니다. 여기의 `ProgressIndicator`(태그 16)는 코어 어휘이고, 결정형과 부정형을 모두 그리며, `circular`로 막대와 링을 고르고, 생김새는 활성 디자인 시스템이 정합니다. 둘 다 남깁니다. 예제를 지우면 FR-11이 보여주는 것이 없어지고, 예제를 코어로 승격하면 확장 태그 블록을 실제로 쓰는 코드가 저장소에서 사라집니다.

#### 15.2.5 값 변경 이벤트 (`ValueChanged`, 태그 16)

값을 가진 위젯이 하나의 이벤트를 공유합니다. 픽커 3종(DatePicker, TimePicker, Dropdown), 그리고 15.2.4의 `Checkbox`, `RadioButton`, `Switch`, `Slider`가 씁니다.

```
tag 16, 24바이트: node_id: u32, handler_id: u64, value: f64
```

**`f64` 하나로 통일합니다.** 픽커는 정수(에폭 일수, 자정 기준 분, 선택 위치)를 보내고 Slider는 연속값을 보내는데, `f64`는 2^53까지의 정수를 오차 없이 표현하므로 양쪽이 한 타입에 들어갑니다. 이벤트 종류를 둘로 나누면 같은 개념에 이름이 둘 생기고, 위젯이 늘 때마다 또 나눠야 합니다.

- **사용자 코드는 `f64`를 보지 않습니다.** `DatePicker`의 `on_change`는 `EventHandler<i64>`이고, `Slider`는 `EventHandler<f32>`입니다. 변환은 컴포넌트가 합니다. 와이어 표현이 하나라고 해서 API까지 하나여야 하는 것은 아닙니다.
- 토글도 같은 이벤트를 씁니다. 꺼짐이 0.0, 켜짐이 1.0입니다. `Checkbox`의 `on_change`는 `EventHandler<bool>`이고, 변환은 컴포넌트가 합니다.
- 속성 쪽도 같습니다. `PropertyKind::Value`는 하나이고, 값의 해석은 위젯이 정합니다.

#### 15.3 넣지 않는 것

| 제외 | 이유 |
|---|---|
| ~~`Chip`, `FAB`~~ | **철회했습니다.** 15.1이 묻는 것은 이름이 아니라 개념이고(21.1이 같은 실수를 한 번 바로잡았습니다), 둘 다 여섯 시스템에 대응물이 있습니다. 칩은 고르거나 거르는 작은 토큰이고, 떠 있는 기본 동작 버튼은 iOS에서 바 위의 큰 동작 버튼입니다. FR-15.2 어휘로 편입합니다 |
| ~~`NavigationBar`, `NavigationRail`, `BottomSheet`~~ | **철회했습니다.** 위젯 이름이 아니라 개념을 기준으로 다시 따져 보니 여섯 시스템 모두에 있습니다. FR-21이 코어 어휘로 편입합니다 |
| `Grid` | 별도 요구사항으로 분리했고, 그 요구사항이 FR-25입니다. 1.0 범위 안입니다 |
| `RichText`, 마크다운 | 별도 요구사항으로 분리했고, 그 요구사항이 FR-26입니다. 1.0 범위 안입니다 |
| 파일 드래그 앤 드롭 | 별도 요구사항으로 분리했고, 그 요구사항이 FR-27입니다. 포인터 드래그 자체는 FR-18입니다 |

#### 15.4 수용 기준

- 32개 위젯 각각이 세 디자인 시스템에서 렌더링되고, `DesignShowcase`에 나타납니다.
- `DatePicker`가 세 시스템에서 **서로 다른 고르기 방식**으로 나타납니다. 같은 모양을 세 번 그린 것이면 실패입니다.
- 위젯을 추가해도 `DesignSystem` enum과 규칙 테이블 외에는 바뀌지 않습니다(FR-14.2 회귀 검사).
- 각 위젯에 프로토콜 왕복 테스트가 있습니다(PR-4 벡터).

### FR-16 에셋 전달 (`Agreed`)

`Image`와 `Icon`은 리소스가 경계를 넘어야 합니다. 13.7에서 "별도 요구사항으로 분리"한 그 프로토콜입니다.

#### 16.1 제약

PR-4의 고정 레이아웃 레코드에는 이미지 바이트가 들어가지 않습니다. 그리고 PR-1의 동기 경계는 배치를 호출 스택 안에서 소비하므로, 배치가 가리키는 포인터는 호출이 끝나면 무효입니다. 이미지는 프레임보다 오래 살아야 합니다.

#### 16.2 설계: 에셋 핸들

1. Host가 `RegisterAsset` 명령으로 에셋을 등록합니다. 레코드는 `(asset_id: u32, kind: u16, offset: u32, length: u32)`이고, 바이트는 배치의 문자열 영역과 같은 방식으로 레코드 뒤에 놓입니다(PR-4 그대로).
2. Renderer는 **그 호출 안에서 바이트를 복사해** 자기 캐시에 `asset_id`로 보관합니다. 이미지 디코딩은 Renderer가 합니다. 복사는 등록 시점 1회뿐이고 프레임마다 일어나지 않으므로 NFR-9의 프레임 예산 밖입니다.
3. `Image`와 `Icon` 위젯은 `asset_id`만 속성으로 받습니다. 고정 레이아웃이 유지됩니다.
4. `ReleaseAsset(asset_id)`로 캐시에서 내립니다. Host가 소유권을 갖습니다.
5. 등록되지 않은 `asset_id`는 `ProtocolError`입니다(NFR-7). 프로세스를 중단시키지 않습니다.

- `kind`: `Png=1, Jpeg=2, Svg=3, VectorIcon=4`. Renderer가 해석할 수 없는 종류는 `ProtocolError`입니다.
- **시스템 아이콘 이름을 보내는 경로는 두지 않습니다.** 이름을 보내면 존재 검증이 런타임으로 밀리고, 폰트 패밀리를 뺀 이유(13.7)와 같은 문제가 생깁니다. 디자인 시스템별 기본 아이콘 세트는 Renderer 번들이 소유하고, `VectorIcon` 종류가 그 세트의 **역할**(`Back`, `Close`, `Search` 등 닫힌 enum)을 가리킵니다. 그래야 Cupertino에서는 SF Symbols 모양, Material에서는 Material Symbols 모양이 나옵니다.

#### 16.3 수용 기준

- PNG 1장을 등록하고 `Image`로 그린 뒤 해제하면, 해제 후 같은 `asset_id` 사용이 `ProtocolError`가 됩니다.
- 등록은 프레임당 0회인 정상 경로에서 스틸 프레임 할당이 0입니다(NFR-9).
- 같은 `VectorIcon` 역할이 세 디자인 시스템에서 각 시스템의 아이콘으로 그려집니다.

#### 16.4 애플리케이션이 등록하는 경로

16.2는 배치에 무엇이 실리는지를 정하지만, 그 레코드를 만들 수 있는 것은 `Host`뿐이었습니다. `Host`는 경계 객체이고 사용자 코드는 경계 함수를 부르지 않으므로(PR-3), `Image`는 와이어에도 위젯 목록에도 있는데 애플리케이션이 쓸 방법이 없었습니다. 통합 샘플 일곱 개의 그림이 전부 단색 사각형인 이유가 이것이고, 그 사실이 샘플 주석에 "그릴 수 없다"고 여러 번 적혀 있었습니다. 위젯이 있는데 부를 수 없으면 요구사항이 절반만 구현된 것입니다.

메시지(FR-22)와 같은 모양으로 해결합니다. 새 경계 진입점은 없습니다.

```rust
static HERO: &[u8] = include_bytes!("../assets/hero.svg");
rsx! { Image { asset_id: asset(AssetKind::Svg, HERO), height: 240.0 } }
```

1. `asset(kind, bytes)`는 등록을 스레드 로컬 큐에 넣고 id를 돌려줍니다. Host는 그 호출이 만드는 배치에 `RegisterAsset`을 실어 보냅니다. 메시지와 같은 경로이므로 경계 모델(PR-1)은 그대로입니다.
2. `bytes`는 `&'static [u8]`입니다. `include_bytes!`로 실행 파일에 들어간 바이트가 보통의 경우이고, `'static`이면 등록이 어느 프레임에 실리든 포인터가 유효합니다.
3. **같은 바이트를 다시 부르면 같은 id가 나오고 두 번째 등록은 큐에 들어가지 않습니다.** 컴포넌트 본문에서 매 렌더 부르는 것이 자연스러운 쓰기이므로, 중복 제거가 없으면 프레임마다 같은 그림이 다시 등록되고 NFR-9의 스틸 프레임 할당 0이 깨집니다.
   - **주소를 먼저 보고, 빗나가면 내용을 봅니다.** 주소 비교가 거의 항상 답이고 비교 한 번이면 끝납니다. 내용 비교가 필요한 이유는 같은 파일이 두 주소로 도착할 수 있기 때문입니다. 참조를 담은 `const`는 쓰는 자리마다 인라인되고 자리마다 별도 할당을 받을 수 있어서, `const` 배열로 쓴 카탈로그는 한 파일에 대해 두 개의 포인터를 내놓습니다. 실제로 팟캐스트 샘플이 커버 하나를 두 개의 id로 두 번 등록했습니다. 내용 비교는 주소가 빗나갔을 때만 돌고, 그것은 새 그림 하나당 한 번이며 정상 프레임에서는 일어나지 않습니다.
4. id는 1부터 Host가 나눠 줍니다. 애플리케이션이 숫자를 고르지 않으므로 두 화면이 같은 id를 쓰는 일이 생기지 않습니다.
5. 새 `Host`가 스레드를 넘겨받으면 큐와 id가 비워집니다. 새 Renderer의 캐시는 비어 있으므로, 이전 Host가 나눠 준 id를 그대로 쓰면 등록되지 않은 id가 됩니다.
6. 레코드 순서는 문제가 되지 않습니다. Renderer는 배치 전체를 적용한 뒤에 그리므로, 같은 배치 안에서 `RegisterAsset`이 그것을 쓰는 노드보다 뒤에 있어도 그릴 때는 이미 캐시에 있습니다.

수용 기준:
- `asset(...)`을 부른 컴포넌트의 첫 배치에 `RegisterAsset`이 한 건 있고, 같은 바이트를 여러 번 불러도 한 건입니다. 같은 내용이 서로 다른 두 주소로 도착해도 한 건입니다.
- 같은 화면의 두 번째 프레임에는 `RegisterAsset`이 없습니다.
- 두 개의 다른 바이트는 서로 다른 id를 받습니다.
- 새 `Host`에서 같은 화면을 다시 만들면 `RegisterAsset`이 다시 나갑니다.

#### 16.6 Lottie 애니메이션 (`미결`, 결정 전)

**결정되지 않았습니다.** 여기 적는 것은 필요해졌을 때 다시 조사하지 않기 위한 재료이고,
"넣지 않는다"는 결론이 아닙니다. 판단 없이 제외해 두었다가 전부 철회한 일이 2026-09-23에
있었으므로, 안 하기로 정한 것과 아직 정하지 않은 것을 구분해서 적습니다.

**직접 구현하지 않습니다.** Lottie는 After Effects의 기능을 따라가는 큰 포맷이고, 15.2가
Material 3 컨트롤에 대해 적어 둔 것이 그대로 적용됩니다. 스펙을 구현한 것이 이미 있는데
흉내를 유지보수할 이유가 없고, 그렇게 만든 것은 따른다고 주장하는 명세와 반드시 어긋나기
시작합니다.

**skottie도 쓰지 않습니다.** Skia의 Lottie 재생기이고 skiko 안에 들어 있어서 데스크톱과
iOS와 웹에서는 바로 닿지만, **Android는 skiko를 쓰지 않습니다.** 우리 Android 렌더러는
androidx Compose이고 거기에는 skottie가 없습니다. 플랫폼 셋은 이렇게, 하나는 저렇게 그리는
구현은 같은 선언이 플랫폼마다 다른 코드로 그려지는 것이고, 그것이 어긋나기 시작하면 찾기
어려운 자리에서 어긋납니다.

**Compose Multiplatform용 Lottie 라이브러리를 씁니다.** Compottie가 2.0부터 순수 Kotlin
렌더러이고(기존 재생기를 감싸는 것이 아니라 자체 구현입니다), 브리지가 없으므로 Compose가
도는 곳이면 전부 같은 코드로 그립니다. 우리 Android 경로를 포함합니다. 2026-09-23 기준 그
렌더러는 실험 단계라고 저자가 밝히고 있으므로, 채택 시점에 그 상태를 다시 확인해야 합니다.

**설계는 이미 정해진 것들의 조합입니다.**

- **Lottie는 위젯이 아니라 에셋입니다.** `AssetKind`에 값을 하나 더하고 JSON(또는 dotLottie)
  바이트를 등록해 id로 참조합니다. `Png`, `Svg`와 같은 부류이고 새 전달 수단이 필요 없습니다.
  태그는 추가 전용이므로 나중에 더하는 데 제약이 없습니다.
- **재생 상태는 Renderer가 소유합니다(D5).** Host가 말하는 것은 "이 에셋을, 반복 여부와 속도와
  함께, 재생하거나 멈춘다"까지입니다. 현재 프레임이 경계를 넘나들면 프레임마다 경계 호출이
  생기고 PR-1과 NFR-9이 같이 무너집니다. 끝났다는 것만 이벤트로 올라옵니다. Dialog의 열림과
  Tabs의 선택이 이미 같은 규칙입니다.
- **Android에서는 좌표로 선언합니다.** 남의 라이브러리를 Maven 좌표로 말하는 것은 PR-5가 이미
  Compose에 대해 하는 일입니다.

**정해야 하는 것은 비용입니다.**

처음 이 항목을 적을 때 skottie 아카이브 8.6MB(`libskottie.a` 4.7MB + `libsksg.a` 3.9MB,
2026-09-23 실측)를 비용으로 적었는데, **그것은 틀린 계산이었습니다.** 순수 Kotlin 라이브러리를
쓰면 그 아카이브는 필요하지 않습니다. 실제 비용은 그 라이브러리의 Kotlin 코드가 이미지에서
차지하는 크기이고, native-image의 도달 분석이 적용되므로 훨씬 작습니다. 다만 **아직 재지
않았습니다.**

렌더러는 애플리케이션마다 빌드되는 것이 아니라 미리 만들어 배포하는 아티팩트 하나이므로,
코어에 넣으면 Lottie를 쓰지 않는 애플리케이션도 그만큼을 냅니다. FR-11의 확장 패키지로 빼도
같습니다. 확장도 결국 같은 렌더러 바이너리에 들어갑니다. 그 크기를 재고 나서 판단합니다.

**지금 상태**: 아무도 요청한 적이 없고, 라이브러리를 넣었을 때 이미지가 얼마나 커지는지 재지
않았습니다. 요청이 오면 그 숫자부터 잽니다.

#### 16.5 버튼도 아이콘 역할을 답니다 (`Agreed`)

FR-22의 참조 화면 네 개가 전부 같은 자리에서 막혔습니다. Windows 계산기의 햄버거와 기록,
iOS 메모의 뒤로·실행 취소·공유·더 보기·완료, Gemini의 새 대화와 접기, 할 일 목록의 필터.
**참조의 막대는 전부 아이콘 버튼입니다.** 그런데 지금 애플리케이션 코드는 아이콘을 놓을 수
없습니다. `Icon` 위젯은 Host가 등록한 `asset_id`만 받고, `IconRole`은 `NavigationItem`을
통해서만 트리에 닿습니다. 그래서 샘플들은 막대에 글자 버튼을 늘어놓았고, 좁은 창에서는 그
글자들이 서로를 밀어냅니다.

- **새 속성도 새 위젯도 필요 없습니다.** `PropertyKind::Icon`(태그 60)은 이미 있고 `IconRole`
  태그를 싣습니다. `Button`이 그 속성을 달 수 있게 하는 것이 전부이고, 경계도 스키마 해시도
  움직이지 않습니다.
- **아이콘은 뜻이지 그림이 아닙니다**(16.2). `Button`이 싣는 것은 `IconRole`이고, 어느 시스템의
  어떤 글리프가 그려지는지는 Renderer가 정합니다.
- **글자는 버튼의 이름으로 남습니다.** 아이콘과 글자가 같이 있으면 둘 다 그려지고, 글자가 비면
  아이콘만 그려집니다. 글자가 빈 버튼의 이름은 아이콘 역할이 되므로, 보조 기술에 이름 없는
  버튼이 남지 않습니다.
- 수용 기준
  1. `icon`만 단 `Button`이 그 디자인 시스템의 글리프 하나로 그려지고, 같은 선언이 시스템을
     바꾸면 다른 글리프가 됩니다.
  2. `icon`과 `text`를 함께 단 `Button`은 둘 다 그립니다.
  3. 글자가 빈 아이콘 버튼도 접근성 이름을 갖습니다.

### FR-17 커스텀 드로잉 (`Agreed`)

FR-15의 위젯 25개는 사각형, 모서리, 테두리, 그림자까지입니다. 기존 위젯을 아무리 조합해도 **새 픽셀은 나오지 않습니다.** 차트, 스파크라인, 서명 패드, 커스텀 로딩 표시, 아바타 링이 전부 여기 걸립니다.

Renderer가 AOT 컴파일된 바이너리이므로 Host가 그리기 코드를 보낼 수는 없습니다(D7). 대신 **닫힌 드로잉 명령 집합**을 보냅니다.

#### 17.1 Canvas 위젯

위젯 태그 `Canvas = 26`(FR-15.2에도 등재). 자식 노드 대신 드로잉 명령 목록을 갖습니다. 크기는 Modifier가 정하고, 좌표는 위젯 좌상단 기준 dp입니다.

#### 17.2 드로잉 명령

명령도 고정 레이아웃 레코드입니다(PR-4). 한 명령은 `(tag: u16, length: u16, paint: u64, a: f32, b: f32, c: f32, d: f32, e: f32, f: f32)`입니다.

| 태그 | 명령 | a-f |
|---|---|---|
| 1 | `Line` | x1, y1, x2, y2, stroke_width |
| 2 | `Rect` | x, y, w, h, stroke_width (0이면 채움) |
| 3 | `RoundRect` | x, y, w, h, radius, stroke_width |
| 4 | `Circle` | cx, cy, radius, stroke_width |
| 5 | `Arc` | cx, cy, radius, start_deg, sweep_deg, stroke_width |
| 6 | `PolylineRef` | points 에셋 id(FR-16), stroke_width |
| 7 | `TextAt` | 문자열 (offset, length), x, y, `TypeRole` |

- `paint`는 FR-13.1의 `Paint`를 그대로 씁니다. 따라서 **`ColorRole`을 보낼 수 있고, 그러면 디자인 시스템이 색을 정합니다.** Canvas라고 해서 FR-14 바깥으로 나가지 않습니다.
- 점이 많은 경로는 레코드에 넣지 않고 FR-16 에셋으로 등록한 뒤 id로 참조합니다(`PolylineRef`). 그래서 명령 레코드가 고정 길이를 유지합니다.
- 그라데이션, 셰이더, 이미지 브러시, 임의 베지어 경로는 넣지 않습니다. 13.7의 제외 사유가 그대로 적용됩니다.
- 명령 목록이 바뀌지 않은 프레임은 명령을 다시 보내지 않습니다. 목록은 노드 속성이고, 변경 없으면 Mutation이 없습니다.

#### 17.2.1 명령 목록을 나르는 속성

- 속성 태그는 15.2.2의 26-29 블록에서 `Commands=50` 하나입니다. Canvas가 갖는 유일한 속성입니다.
- 명령 레코드는 36바이트 고정입니다(`tag`와 `length`가 각 2바이트, `paint`가 8바이트, `a`-`f`가 각 4바이트). `length`는 항상 36이고, 길이를 읽는 쪽이 모르는 태그를 건너뛸 수 있게 자리를 지킵니다.
- `a`-`f`는 32비트 낱말 6개입니다. 기하 값은 `f32`로, 에셋 id와 문자열 오프셋과 역할 태그는 `u32`로 읽습니다.
- 속성 값 종류에 `Bytes`를 더합니다. 레이아웃은 문자열과 같은 `(offset: u32, length: u32)`이고, 바이트도 같은 방식으로 레코드 영역 뒤에 놓입니다(PR-4 그대로). UTF-8 검사만 하지 않습니다.
- 값은 명령 배열 다음에 `TextAt`의 문자열들이 이어붙은 덩어리 하나입니다. `TextAt`의 `(offset, length)`는 **그 덩어리의 시작 기준** 바이트 오프셋이라, 명령 목록은 배치 안에서 자기 자신만으로 해석됩니다.

#### 17.3 수용 기준

- 꺾은선 차트 하나를 Rust `rsx!`만으로 그립니다. Kotlin 변경이 없습니다.
- `ColorRole`로 그린 선이 세 디자인 시스템에서 각 시스템의 색으로 나옵니다.
- 명령 목록이 그대로인 프레임에서 Host 힙 할당이 0입니다(NFR-9).

### FR-18 포인터 제스처 (`Agreed`, 1.1 범위)

**2026-09-22: 1.0 범위 밖으로 정했습니다.** 설계는 아래에 다 적혀 있고 태그까지 배정되어 있으므로, 나중에 채워도 이미 나간 것은 움직이지 않습니다. 클릭과 키보드로 동작하는 UI는 지금도 만들 수 있고 샘플 열한 개가 그 증거이므로, 이것이 없는 것은 1.0이 깨져 있는 것이 아니라 1.0에 없는 기능입니다.

**이 요구사항은 코드에 하나도 들어와 있지 않습니다.** 아래 표의 여덟 이벤트 중 스키마에 있는 것은 없고, 렌더러에 제스처 인식기도 없습니다. 자리는 비워 두었습니다. `ValueChanged`가 태그 16을 쓰는 것이 그 흔적이고, 그래서 나중에 채워도 이미 배정된 태그는 움직이지 않습니다.

요구사항마다 이름을 딴 테스트를 세어 보다 드러났습니다. `fr18_`로 시작하는 테스트가 한 건도 없고, 다른 이름으로 존재하지도 않습니다. 문서가 코드보다 앞서 있던 것이고, 그렇게 보이지 않게 두는 것이 더 나쁩니다.

입력이 `Clicked`와 `KeyDown`뿐이었습니다. 제스처 인식은 Compose 쪽에서 일어나므로 위젯 조합으로 만들어낼 수 없고, 없으면 Host가 우회할 방법도 없습니다.

#### 18.1 이벤트

이벤트 태그 8부터 이어 붙입니다(1-7은 배정 완료). **8-15는 제스처가 예약합니다.** 값 변경 이벤트는 16번입니다(15.2.5).

**2026-09-21 정정**: 픽커 구현이 SPEC에 없는 `ValueChanged`를 태그 8에 넣어 이 예약을 침범한 채 develop에 들어갔습니다. 코드와 SPEC이 어긋나면 코드를 고친다는 규칙에 따라, 그 이벤트를 16번으로 옮기고 아래 15.2.5로 명세합니다.

| 태그 | 이벤트 | 페이로드 |
|---|---|---|
| 8 | `PointerEnter` | 없음 |
| 9 | `PointerExit` | 없음 |
| 10 | `LongPress` | x, y (f32) |
| 11 | `ContextMenu` | x, y (f32). 우클릭, 그리고 터치의 관례적 대응 동작 |
| 12 | `DragStart` | x, y (f32) |
| 13 | `DragMove` | x, y, dx, dy (f32) |
| 14 | `DragEnd` | x, y (f32) |
| 15 | `Scroll` | dx, dy (f32). 휠과 트랙패드 |

- 좌표는 이벤트를 받은 노드 기준 dp입니다. 화면 좌표를 보내지 않습니다. Host는 창 위치를 모르고, 알 필요도 없습니다.
- 구독하지 않은 제스처는 **인식기를 붙이지 않습니다.** `on_drag_move`를 선언한 노드에만 드래그 인식이 걸립니다. 모든 노드에 거는 것은 NFR-9 위반입니다.
- `DragMove`는 프레임당 최대 1건으로 합칩니다. 포인터 이벤트는 프레임보다 자주 오므로 합치지 않으면 프레임당 여러 번 VirtualDom을 돌게 됩니다.
- FR-12의 `consume`이 그대로 적용됩니다. 부모로 전파할지는 핸들러가 정합니다.
- 플랫폼 차이는 Renderer가 흡수합니다. 터치에는 호버가 없으므로 iOS와 Android에서 `PointerEnter`는 발생하지 않습니다. Host 코드는 그대로 두고, 호버로만 보이는 UI를 두지 않는 것은 애플리케이션의 책임입니다.

#### 18.1.1 핸들러 속성 태그

제스처는 어느 위젯에나 붙으므로 15.2.2의 위젯별 블록에 들어가지 않습니다. 그 블록들 뒤에 이어 붙입니다.

`OnPointerEnter=67, OnPointerExit=68, OnLongPress=69, OnContextMenu=70, OnDragStart=71, OnDragMove=72, OnDragEnd=73, OnScroll=74`

**처음에는 60번부터였습니다.** 그 사이에 60에서 66이 실제로 쓰였습니다. `Icon=60`, `Slot=61`, `Columns=62`, `MinColumnWidth=63`, `Spans=64`, `OnFilesEntered=65`, `OnFilesDropped=66`이고, 모두 구현되어 프로토콜에 들어가 있습니다. 예약은 구현된 태그를 비켜야 하므로 블록 전체가 그 뒤인 67번으로 옮겨집니다. 제스처는 아직 구현되지 않았으므로 이 이동으로 움직이는 바이트는 없습니다. 태그는 추가 전용이고, 그 규칙이 지켜야 하는 것은 이미 전송된 값이지 아직 전송된 적 없는 예약이 아닙니다.

값은 기존 핸들러 속성과 같은 핸들러 id이고, 값이 없으면(`None`) 구독이 사라집니다. Renderer는 핸들러 id가 있는 축에만 인식기를 붙입니다.

#### 18.2 수용 기준

- 호버로 버튼을 띄우는 코드가 macOS에서 동작하고, iOS에서 이벤트가 오지 않아도 앱이 깨지지 않습니다.
- 드래그로 항목을 옮기는 목록에서 `DragMove`가 프레임당 1건입니다.
- 제스처를 선언하지 않은 노드에는 인식기가 붙지 않습니다(인터프리터 단위 테스트).

### FR-19 창 크롬 (`Agreed`)

데스크톱 창이 지금 플랫폼 기본 타이틀바를 그대로 씁니다. macOS는 오래전에 타이틀바가 앱 내용 안으로 들어가는 형태로 바뀌었고, GNOME은 클라이언트 사이드 데코레이션(CSD)이 기본이며, Windows 11도 캡션을 앱이 그리는 쪽이 일반적입니다. 지금 모습은 어느 플랫폼에서도 최신이 아닙니다.

**기본값을 현대적 크롬으로 바꿉니다.** 앱이 아무것도 요청하지 않아도 그렇게 보여야 합니다. 네이티브 데스크톱 UI를 표방하면서 10년 전 창을 띄우는 것은 FR-14.3에서 기본 테마를 adaptive로 바꾼 것과 같은 이유로 맞지 않습니다.

#### 19.1 플랫폼별 방식

세 플랫폼이 서로 다른 방식을 요구합니다. 공통 추상화를 억지로 만들지 않고, 플랫폼이 제공하는 것을 그대로 씁니다.

| 플랫폼 | 방식 | 창 버튼 |
|---|---|---|
| macOS | 타이틀바를 투명하게 하고 콘텐츠를 그 아래까지 확장 | **시스템이 그립니다** (신호등 버튼 유지) |
| Windows | 프레임은 유지하고 캡션 띠만 클라이언트 영역으로 되찾음 | 우리가 그립니다 |
| Linux | 장식 없는 창 + 앱이 캡션을 그림 (CSD) | 우리가 그립니다 |

**Windows에서 장식 없는 창을 쓰지 않습니다.** Windows의 프레임은 타이틀바만이 아닙니다. 드롭 섀도, 크기 조절 테두리, Snap Layouts, 복원 애니메이션이 전부 같은 프레임에서 나오고, 창 안에서 그릴 수 있는 것은 그중 하나도 없습니다. 처음 Windows에서 실행한 사람이 "테두리가 조잡하다"고 말한 것이 이것입니다.

**VS Code와 Windows Terminal이 하는 방식을 씁니다.** 프레임은 그대로 두고 캡션 띠만 가져옵니다. 창은 `WM_NCCALCSIZE`에서 자기 클라이언트 영역을 보고하는데, 기본 처리기가 프레임을 계산하게 둔 다음 위쪽 모서리만 원래 자리로 되돌리면, 좌우와 아래는 시스템의 것으로 남고 캡션이 있던 띠만 우리가 그릴 영역이 됩니다. 스타일을 건드리지 않으므로 섀도와 테두리와 Snap도 건드리지 않습니다.

두 가지가 딸려옵니다. **최대화된 창**은 테두리 두께만큼 모니터보다 크게 배치되므로 위쪽 모서리를 그대로 되돌리면 캡션이 화면 밖으로 나가고, 그만큼 안으로 들여야 합니다. **위쪽 크기 조절 띠**는 사라진 비클라이언트 영역에 있었으므로 `WM_NCHITTEST`가 대신 답하지 않으면 위에서만 크기를 못 바꾸는 창이 됩니다.

캡션 띠의 기하는 Kotlin과 네이티브 양쪽에 있습니다. Kotlin이 그리고 네이티브가 히트 테스트를 답하며, 둘이 어긋나면 캡션처럼 보이는데 끌리지 않는 줄이나 눌리지 않는 버튼이 생깁니다. 어느 쪽도 크래시가 아니고 CI에 나타나지 않으므로 `scripts/tests/windows-caption-metrics.test.sh`가 두 숫자를 맞춥니다.

**macOS에서 신호등 버튼을 직접 그리지 않습니다.** 위치, 모양, 호버 동작, 전체화면 전환, 접근성 레이블이 전부 시스템 소유입니다. 흉내 내면 반드시 어긋나고, 그건 네이티브를 표방하는 프로젝트에서 가장 눈에 띄는 실패입니다. AWT가 제공하는 클라이언트 속성(`apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar`, `apple.awt.windowTitleVisible`)으로 충분하며 JNI가 필요 없습니다.

#### 19.2 콘텐츠 안전 영역

콘텐츠가 타이틀바 아래까지 확장되면 **그 영역과 겹치는 문제**가 생깁니다. macOS 신호등 버튼 자리에 앱의 버튼을 그리면 둘 다 못 쓰게 됩니다.

- Renderer가 `WindowInsets` 값을 알고 있으며, 루트 노드에 적용합니다. Host는 이 값을 모릅니다.
- `TopAppBar`(FR-15.2 태그 23)를 루트 자식으로 두면, Renderer가 그것을 캡션 영역으로 인식해 시스템 버튼을 피해 배치하고 드래그 영역으로 만듭니다. `TopAppBar`가 없으면 콘텐츠 전체에 인셋을 적용합니다.
- **바가 캡션을 받으면 그 표면이 캡션 띠를 덮습니다.** 인셋을 바 위쪽에 두면 창 맨 위에 페이지 색 띠가 남고, 그것은 "콘텐츠가 타이틀바 아래로 확장된 창"이 아니라 "아무도 요청하지 않은 타이틀바가 있는 창"으로 읽힙니다. macOS 26이 타이틀바 영역과 툴바를 하나의 유리로 그리는 것과 같은 이유입니다(FR-14.1-4). 그래서 바는 창 맨 위에서 시작하고 최소한 캡션 띠만큼 높습니다.
- **바의 내용은 창 버튼과 같은 줄에 놓입니다.** 버튼 아래로 내려 두 줄을 만들지 않습니다. macOS 26은 툴바를 신호등 버튼과 같은 선에 두고, 아래로 미는 배치는 같은 내용을 담은 바를 두 배 높게 만들 뿐입니다. 버튼이 차지하는 폭만 앞쪽 패딩이 됩니다.
- 바를 찾을 때는 트리의 앞쪽 가장자리만 따라갑니다. `Column`과 `Box`처럼 화면에 보이지 않는 래퍼는 통과하고(앱은 `Column { TopAppBar { } ... }`라고 씁니다), 그 밖의 경로로 닿는 바는 창의 맨 위가 아니므로 캡션을 받지 않습니다.
- **드래그 영역**: 캡션 영역의 빈 공간을 잡고 창을 움직일 수 있어야 합니다. 상호작용 위젯(Button, TextField 등) 위에서는 드래그가 일어나지 않습니다.
- **캡션 띠의 색은 루트가 칠한 색입니다.** 창 전체를 테마의 배경색으로 칠하고 그 안에서 인셋을 주면, 자기 팔레트를 가진 앱은 본문과 맨 위 띠의 색이 어긋납니다. 페이지가 회색인데 띠만 흰색으로 남는 식입니다. 루트 노드가 `Background`를 직접 지정했다면 창의 바탕도 그 색이고, 지정하지 않았을 때만 테마의 배경색입니다. `Container`에서 "애플리케이션이 칠한 색이 이긴다"로 정한 것과 같은 규칙이며(FR-14), 이 규칙이 적용되는 범위가 노드에서 창까지 넓어지는 것뿐입니다.
- **전면 시각 요소도 캡션을 받습니다.** 트리의 앞쪽 가장자리가 `Image`이면 바와 같은 자격으로 캡션 띠를 가져갑니다. 받는 것은 바와 그림뿐입니다. 자기 배경을 칠했다는 이유만으로 주면, 페이지 전체를 담은 `Navigation` 같은 껍데기가 캡션을 가져가고 그 첫 줄의 글자가 창 버튼 아래로 들어갑니다. 껍데기의 색이 해야 할 일은 창을 채우는 것이고, 그건 바로 위 항목이 답합니다. 창 맨 위를 사진이나 색면이 채우는 화면에서 그 위에 인셋만큼의 빈 띠를 남기면, 바가 캡션을 받지 못했을 때와 똑같이 "아무도 요청하지 않은 타이틀바"로 읽힙니다. 창 버튼은 그 위에 그대로 그려지므로 가려지지 않습니다.

#### 19.3 Host가 정할 수 있는 것

창 자체는 Renderer 소유이지만, 몇 가지는 애플리케이션이 정해야 합니다.

```rust
LaunchBuilder::new()
    .with_window(Window::new().title("Chat").size(900.0, 640.0))
    .launch(app);
```

- `title`, `icon`, `size`, `min_size`, `resizable`
- **`icon`은 FR-16의 에셋 id입니다.** 파일 경로도 이름도 아닙니다. 애플리케이션이 PNG를
  등록하고 그 id를 실으면, Renderer가 디코드된 것을 창에 붙입니다. 0은 "말하지 않음"이고,
  그러면 툴킷의 기본 아이콘이 나옵니다. 2026-09-23까지 그 기본값이 그대로 나오고 있었고,
  Windows 작업 관리자는 프로세스 아래에 창을 한 줄씩 보여 주므로 샘플이 전부 자바 커피잔을
  달고 있었습니다.
- **아이콘은 그 애플리케이션이 여는 모든 창에 붙습니다.** 렌더러가 세우는 첫 창만이 아닙니다. 부모 안에 다 들어가지 않는 팝업이나 대화 상자는 자기 플랫폼 창이 되고, 그 창은 첫 창을 세운 코드를 지나가지 않습니다. 또 아이콘은 에셋이라 첫 창이 이미 뜬 뒤에 도착하므로, 그 사이에 열린 창은 아무것도 없이 태어납니다. 그래서 그림은 창이 아니라 렌더러가 들고 있다가 그림이 바뀔 때 이미 열린 창 전부에 적용하고, 그 뒤에 열리는 창에도 적용합니다. 2026-09-24까지 서브 창이 자바 컵을 달고 있었던 것이 이것입니다.
- **샘플 열 개가 전부 아이콘을 지정합니다.** 2026-09-24까지 `minimal` 하나만 지정했고, 나머지는 메인 창부터 자바 컵이었습니다. 그림은 `scripts/make-sample-icons.py`가 그리며, 색은 참조 문서가 각 샘플에 대해 적어 둔 것(강조색, 페이지 색)에서 옵니다(INTENT D14). `frames`는 창을 여는 경로가 없는 라이브러리 샘플이라 해당하지 않습니다.
- 아이콘을 창 설정에 두는 이유는 그것이 창의 성질이기 때문입니다. 노드가 아니므로 위젯
  속성일 수 없고, 디자인 시스템이 정할 것도 아닙니다. 어떤 애플리케이션인지는 애플리케이션만
  압니다.
- `chrome`: `Chrome::Modern`(기본) 또는 `Chrome::System`(플랫폼 기본 타이틀바)
- `Chrome::System`은 없애지 않습니다. 시스템 타이틀바가 필요한 도구형 앱이 있고, 무엇보다 **Modern이 깨졌을 때 돌아갈 곳**이 있어야 합니다.
- 창 버튼의 색, 위치, 모양을 Host가 지정하는 경로는 두지 않습니다. 그것은 디자인 시스템과 플랫폼의 몫입니다(FR-14).

**어떻게 건너가는가: 경계 함수는 바뀌지 않습니다.** `compose_rust_renderer_run`에 인자를 붙이는 길은 PR-2에 걸리고, C 심과 iOS의 `@CName`과 스모크 하네스와 Rust 선언을 모두 따라가야 합니다. 그럴 필요가 없습니다.

- **창 설정은 `SetTheme`과 같은 모양의 레코드입니다.** 노드에 대한 것이 아니라 창 전체에 대한 것이고, rebuild마다 한 번 쓰이며 프레임마다 쓰이지 않습니다. 테마가 이미 그 자리를 쓰고 있으므로 새로운 개념이 아닙니다.
- **렌더러는 창을 만들기 전에 그것을 읽습니다.** `compose_rust_host_init`이 초기 배치를 돌려주므로, Renderer는 `Window`를 세우기 전에 init을 부르고 그 배치에서 창 설정을 꺼낸 다음, 그 값으로 창을 만듭니다. 배치는 창이 생긴 뒤 콘텐츠가 조립될 때 그대로 적용됩니다. init은 UI 스레드에서 도는 동기 호출이므로(PR-1) 창을 만드는 스레드와 같은 스레드입니다.
- **그래서 경계 표면은 그대로입니다.** 진입점이 늘지 않고 시그니처도 바뀌지 않으므로 PR-2의 변경이 아니라 FR-7의 스키마 변경입니다. 스키마 해시는 움직이고, 그것이 양쪽이 어긋나면 빌드 타임에 잡히는 이유입니다.
- **창이 이미 있는 플랫폼은 이 레코드를 무시합니다.** iOS와 Android와 브라우저에서 창은 우리 것이 아닙니다. 읽을 값이 없는 것이 아니라 적용할 곳이 없는 것이므로, 무시가 오류보다 옳습니다.

#### 19.4 수용 기준

1. 아무 설정 없이 실행한 창이 macOS에서 신호등 버튼을 유지하면서 콘텐츠가 타이틀바 영역까지 올라옵니다. 타이틀 문자열은 표시되지 않습니다.
2. Windows와 Linux에서 시스템 타이틀바가 없고, 최소화·최대화·닫기가 동작하며, 캡션 빈 영역 드래그로 창이 움직이고, 가장자리로 크기 조절이 됩니다.
3. `TopAppBar`가 있는 앱에서 그 내용이 macOS 신호등 버튼과 겹치지 않습니다.
4. `TopAppBar`가 없는 앱의 콘텐츠가 캡션 영역에 가려지지 않습니다.
5. `Chrome::System`으로 실행하면 플랫폼 기본 타이틀바가 그대로 나옵니다.
7. 루트가 자기 배경색을 지정한 창에서 캡션 띠가 그 색이고, 지정하지 않은 창에서 테마의 배경색입니다.
8. 트리가 `Image`로 열리는 창에서 그 그림이 창 맨 위부터 그려지고, 창 버튼이 그 위에 남습니다.
6. 창 버튼이 접근성 트리에 노출됩니다. macOS는 시스템이 제공하므로 자동이고, Windows와 Linux는 우리가 그리므로 역할과 레이블을 직접 붙여야 합니다.

#### 19.7 타이틀바 모드 (`Agreed`)

19.3의 `Chrome`은 **누가 캡션을 그리는가**를 고릅니다. 이것은 그 안에서 **그 캡션이 어떤 창의 것인가**를 고릅니다. 두 값이고 `Window`에 실립니다.

```rust
Window::new().with_title_bar(TitleBar::Normal)
```

- `TitleBar::Normal`이 **기본값**입니다. 지금 그 플랫폼의 애플리케이션이 실제로 그러한 창입니다.
- `TitleBar::Simple`은 보통 창입니다. 도구형 애플리케이션과, `Normal`이 어색해지는 자리를 위한 것이며, `Chrome::System`이 `Chrome::Modern`에 대해 그러하듯 **돌아갈 곳**입니다.

**플랫폼마다 무엇이 달라지는지는 다릅니다.** 모드는 두 개의 답 중 하나를 고르는 것이고, 그 답이 무엇인지는 디자인 시스템이 정합니다(FR-14.1). Host는 어느 쪽인지만 말하고 숫자를 말하지 않습니다.

| 플랫폼 | `Normal` | `Simple` |
|---|---|---|
| macOS | 창 버튼이 모서리에서 **더 들어와** 앉고, 창의 **모서리 반경이 더 큽니다**. macOS 26의 애플리케이션이 그러합니다 | 버튼이 보통 창의 자리에, 보통 창의 반경으로 |
| Windows | 캡션의 **높이**가 다릅니다. 버튼은 플랫폼이 두는 자리에 그대로 | 플랫폼 기본 높이 |
| Linux | 같은 규칙. 캡션의 높이만 다릅니다 | 플랫폼 기본 높이 |

- **버튼이 움직이는 것은 macOS뿐입니다.** Windows와 Linux의 캡션 버튼은 그 플랫폼의 오른쪽 끝에 붙는 것이 관습이고, 그것을 안쪽으로 들이는 애플리케이션은 없습니다. 창의 모서리 반경도 그 두 플랫폼에서는 창 관리자와 컴포지터의 것이지 애플리케이션의 것이 아닙니다. 그래서 두 곳에서 모드가 고르는 것은 높이 하나입니다.
- **`Chrome::System`에서는 아무 효과가 없습니다.** 플랫폼이 그리는 타이틀바의 높이도 버튼의 자리도 우리 것이 아닙니다. 값은 무시되고 오류가 아닙니다.
- **창이 없는 플랫폼도 무시합니다.** iOS와 Android와 브라우저에는 적용할 창이 없습니다. 19.3의 창 설정 전체가 이미 그렇습니다.
- **와이어**: `SetWindow` 레코드에 `title_bar`(u16) 한 칸과, 레코드를 4의 배수로 유지하는 패딩 한 칸이 붙습니다(28바이트에서 32바이트). 추가 전용이고 스키마 해시가 움직입니다.

수용 기준:

1. macOS에서 `Normal`로 연 창의 창 버튼 중심이 모서리에서 `Simple`로 연 창보다 더 안쪽에 있고, 두 창의 모서리 반경이 다릅니다.
2. Windows와 Linux에서 두 모드의 캡션 높이가 다르고, 두 모드 모두 버튼은 그 플랫폼이 두는 끝에 있습니다.
3. `Chrome::System`으로 연 창은 두 모드에서 같습니다.
4. 아무것도 말하지 않은 애플리케이션은 `Normal`을 얻습니다.

#### 19.5 창 버튼의 모양은 디자인 시스템이 정합니다

19.3이 "창 버튼의 색·위치·모양은 디자인 시스템과 플랫폼의 몫"이라고 정한 것의 구현 자리는 `ComponentRules.caption()`입니다. 답하는 값: 버튼이 붙는 끝, 버튼의 너비와 높이, 모양, 각 버튼의 기본 채움과 호버 채움, 글리프 색과 두께, 쉬는 상태에서 글리프를 그리는지, 간격과 가장자리 여백, 제목 정렬.

버튼의 **순서는 붙는 끝을 따라갑니다**. 앞쪽 끝은 닫기·최소화·확대, 뒤쪽 끝은 최소화·최대화·닫기입니다. 같은 줄을 반대쪽으로 옮기기만 하면 읽는 사람이 최소화를 찾는 자리에 닫기가 놓입니다.

글리프는 아이콘 세트가 아니라 직접 그립니다. 창을 닫는 버튼은 글꼴이 없어서 글리프가 비는 것을 감당할 수 없는 자리입니다.

- **2026-09-22: 가장자리 크기 조절이 들어왔습니다.** 장식 없는 창의 여덟 가장자리에 포인터 굵기의 보이지 않는 띠를 두고, 끄는 만큼 창의 경계를 다시 계산합니다. 앞쪽 가장자리를 끌면 크기와 위치가 함께 움직여서 반대쪽 가장자리가 제자리에 남고, 최소 크기에 닿으면 크기와 함께 위치도 멈춥니다. 크기만 고정하면 창이 줄어들기를 멈춘 뒤에도 계속 미끄러지기 때문입니다.
- **다만 이 화면은 macOS에서 확인할 수 없습니다.** 굵은 띠가 붙는 것은 장식 없는 창뿐이고 macOS는 진짜 타이틀바를 유지하므로, 이 기능이 실제로 손에 닿는지는 Windows와 Linux에서만 확인됩니다. 그래서 잘못될 수 있는 쪽, 즉 경계를 다시 계산하는 부분을 순수 함수로 떼어 테스트로 고정했습니다. 포인터 입력이 그 함수에 제대로 닿는지는 아직 사람이 확인하지 않았습니다.
- **2026-09-22: 19.3의 `with_window`가 들어왔습니다.** `SetWindow`는 `SetTheme` 옆의 레코드이고, Renderer는 창을 세우기 전에 Host를 시작해 첫 배치에서 그것을 읽은 다음 그 값으로 창을 만듭니다. 경계 함수는 하나도 바뀌지 않았고 `Chrome` enum과 `Window` 타입과 디코더는 전부 코드젠이 만들었습니다(FR-7). 19.4의 5번이 이제 도달 가능합니다.

#### 19.6 Windows 캡션 구현 기록

- **2026-09-24: 프레임을 유지한 채 캡션을 되찾는 방식이 들어왔습니다.** 그 전까지 Windows는 시스템 타이틀바를 그대로 쓰고 있었고, 코드에는 "이렇게 하는 것이 옳지만 확인할 수 없는 플랫폼의 네이티브 작업이라 미룬다"는 주석이 붙어 있었습니다. 미룬 판단을 코드와 문서에 적어 둔 것이 잘못이었습니다.
- 네이티브 진입점이 창 절차를 바꿔 끼웁니다. 창은 AWT가 자기 스레드에서 나중에 만들므로, Kotlin과 이 파일 사이에 손으로 쓴 다리를 놓지 않기 위해 진입점이 스레드 하나를 띄워 `SunAwtFrame` 클래스의 우리 창이 나타나기를 기다린 뒤 한 번 바꿔 끼웁니다. 실패하면 평범한 시스템 타이틀바가 남습니다. 더 나쁜 모습이고 동작하는 창이므로 시작을 거부할 일이 아닙니다.
- 캡션 띠의 버튼 자리는 클라이언트 영역으로 남기고 나머지는 `HTCAPTION`을 돌려줍니다. 그래서 끌기, 두 번 눌러 최대화, 오른쪽 눌러 시스템 메뉴가 전부 시스템 것으로 돌아옵니다.
- **Snap Layouts의 최대화 버튼 호버 플라이아웃은 아직 없습니다.** 그것은 `HTMAXBUTTON`을 최대화 버튼의 사각형에서만 돌려줘야 하는데, 그 사각형은 디자인 시스템이 정하고 Kotlin이 압니다. 네이티브가 그것을 알려면 Kotlin에서 네이티브로 값을 보내는 경로가 필요하고 지금은 없습니다. 끌어서 스냅하는 것과 키보드 스냅은 프레임이 그대로이므로 동작합니다.

#### 19.8 데스크톱 창 코드는 Compose의 포크가 가집니다 (`Agreed`)

INTENT D19가 Compose 자체를 고치는 일을 `thisisthepy/compose-multiplatform-core-extended`(브랜치 `extended`)로 옮겼고, 데스크톱 창은 그 일에 속합니다. Rust와 무관하고, Python에서 Compose를 쓰는 다른 프로젝트도 같은 창을 필요로 합니다. **아래 창 코드의 주인은 포크이고, 이 저장소에서 포크로 옮기는 중입니다.**

- Windows 캡션 되찾기(19.1, 19.6)와 캡션의 색 (`renderer_entry.c`, `WindowChrome.kt`)
- Windows DPI 인식 선언
- 크기를 바꾸는 동안 그리기, 끄는 도중에도 내용이 창 크기를 따라 그려지는 것 (`WindowResize.kt`, `ResizeSync.kt`, `WindowFrames.kt`, `win32_resize.h`, NFR-9)
- 창 아이콘(19.3, `WindowIcon.kt`)
- 툴킷 없는 창: AWT 없이 렌더러가 여는 Win32, X11, AppKit 창(`win32_window.c`, `x11_window.c`, `appkit_window.m`, NFR-14)
- Kotlin/Native 데스크톱 창: 렌더러의 `macos/`, `linux/` 모듈과 D18의 `windows/` 모듈의 창 부분
- 이슈 #26이 함께 꼽는 것: GraalVM native-image에서 AWT를 걷어내는 경로, macOS 창 재질(FR-29)

지금 이 코드는 `renderer/desktop/c/`와 렌더러의 Kotlin 소스, 플랫폼 모듈에 있습니다. 옮긴 뒤 이 저장소에는 포크가 내놓는 창을 쓰는 코드만 남고, 창에 관한 변경은 포크의 커밋으로 들어갑니다. 옮기는 동안 창이 빌드되지 않거나 나빠지는 기간이 생기면 안 되므로, 이 저장소의 사본은 포크가 아래 기준을 통과할 때까지 남깁니다.

수용 기준 (이슈 #26):

1. 지금 이 저장소가 창에 대해 돌리는 검사(19.4, 19.7의 수용 기준, 그리고 `scripts/tests/`의 창 검사: 캡션 기하, Win32와 X11 창 배치, 크기 조절 중 그리기)가 포크의 샘플(`compose-multiplatform-extended`의 hello 앱)에 대해 같은 결과로 통과합니다. 캡션의 색과 크기 조절 중 그리기가 그 샘플에 지금 없는 것이 이 이전이 끝나지 않았다는 표시입니다.
2. 그 확인을 화면 배율 100%와 200%에서 각각 하고, 각 배율의 스크린숏을 기록합니다.
3. 옮긴 뒤 compose-rust의 샘플이 포크의 창으로 같은 검사를 통과하고, 이 저장소의 창 사본이 지워집니다.

### FR-20 반응형 레이아웃 기반 (창 크기 클래스) (`Done`)

UI는 Rust가 저작하지만 측정은 Kotlin이 합니다. 그래서 지금은 Host 컴포넌트가 "폰에서는 한 열, 태블릿에서는 두 열, 데스크톱에서는 사이드바"라고 말할 방법이 전혀 없습니다. 브레이크포인트도, 크기 클래스도, 폭을 알아낼 경로도 없습니다. 폰/태블릿/데스크톱 적응은 그 위에 얹는 것이고, 이 요구사항은 그 바닥을 만듭니다.

#### 20.1 크기 클래스

Material 3 window size class를 그대로 따릅니다. 경계는 폭 기준 600dp와 840dp, 높이 기준 480dp와 900dp입니다.

| 폭 클래스 | 폭 | 대표 기기 |
|---|---|---|
| `Compact` | < 600dp | 세로 방향 폰 |
| `Medium` | 600dp 이상 840dp 미만 | 가로 폰, 세로 태블릿, 작은 창 |
| `Expanded` | 840dp 이상 | 태블릿 가로, 데스크톱 창 |

| 높이 클래스 | 높이 | 대표 상황 |
|---|---|---|
| `Compact` | < 480dp | 가로로 누운 폰 |
| `Medium` | 480dp 이상 900dp 미만 | 세로 방향 폰, 가로로 누운 태블릿, 보통 크기의 창 |
| `Expanded` | 900dp 이상 | 세로 방향 태블릿, 키가 큰 데스크톱 창 |

독자적인 경계를 만들지 않는 이유는 이 숫자들이 이미 Compose, Android, 그리고 이 프로젝트가 기본으로 삼는 디자인 시스템의 가이드라인과 같은 값이기 때문입니다. 다른 값을 쓰면 Renderer 쪽 Compose 컴포넌트의 적응 동작과 Host 쪽 분기가 서로 다른 지점에서 바뀌고, 그 어긋남은 화면에서 바로 보입니다. 높이 기준 클래스(480dp/900dp)도 같은 표에서 나온 값이므로 같이 도입합니다. 한동안 "쓰는 곳이 없다"는 이유로 빼 두었는데, 가로로 누운 전화기에서 세로로 긴 레이아웃을 접는 것이 바로 그 쓰임이고, 폭만으로는 그 상황과 작은 창을 구별할 수 없습니다. 이벤트는 폭 클래스와 같은 레코드에 실어 보냅니다.

폭 클래스는 **폭만**, 높이 클래스는 **높이만** 보고 정합니다. 한 축이 다른 축의 결정에 끼어드는 일은 없고, 둘은 한 레코드에 같이 실려 올라갑니다. 레이아웃이 읽는 것은 보통 둘 중 하나뿐이며, 둘 다 읽어야 하는 자리는 가로로 누운 폰처럼 폭만으로는 작은 창과 구별되지 않는 경우입니다.

#### 20.2 범위: 창 단위만

크기 클래스는 **창 하나에 하나**입니다. 루트 콘텐츠가 측정된 크기가 곧 그 창의 크기입니다.

컨테이너 단위(임의의 노드가 자기 폭을 아는 것)는 FR-28입니다. 비용이 걱정이라 미뤄 두었는데, 그 비용은 **구독한 노드에만** 붙습니다. 아무도 구독하지 않으면 지금과 한 바이트도 다르지 않고, 그 성질을 수용 기준으로 못박습니다. 이 이벤트의 `node_id` 자리를 그대로 써서 특정 노드의 크기를 싣고, 루트는 계속 `node_id = 0`입니다.

#### 20.3 와이어 형태

Renderer에서 Host로 올라가는 **이벤트 레코드 한 종류**를 추가합니다. 두 번째 전달 수단을 만들지 않습니다.

| 태그 | 이벤트 | 페이로드 |
|---|---|---|
| 17 | `WindowSizeChanged` | width_dp (f32), height_dp (f32), size_class (u32), height_class (u32) |

- 태그 8-15는 FR-18.1이 제스처로 예약했고 16은 `ValueChanged`이므로, 이어지는 첫 자리인 17을 씁니다.
- 레코드는 고정 32바이트입니다. 공통 머리 16바이트(tag, record_len, node_id, handler_id) 뒤에 width_dp, height_dp, size_class, height_class가 각각 4바이트입니다. 가변 부분도, 문자열 참조도 없습니다.
- 창의 크기를 알리는 이벤트는 `node_id = 0`(루트), `handler_id = 0`입니다. 이 이벤트는 핸들러가 소유하지 않습니다. Host는 핸들러 조회 없이 처리하고, 배치를 돌려줍니다. 같은 레코드가 특정 노드의 크기도 나르며, 그쪽은 FR-28이 정합니다.
- `size_class`와 `height_class`는 둘 다 `Compact=0, Medium=1, Expanded=2`인 닫힌 열거형이고 스키마 해시에 들어갑니다. 모르는 값은 `ProtocolError`입니다. 두 축이 같은 세 이름을 쓰는 것은 같은 표에서 나왔기 때문이며, 경계가 되는 숫자만 다릅니다.
- dp 단위입니다. 픽셀도, 화면 좌표도 보내지 않습니다. 밀도 변환은 Renderer가 합니다(FR-18의 좌표 규칙과 같습니다).

#### 20.4 보내는 시점

Renderer는 **클래스가 실제로 바뀔 때만** 보냅니다.

- 루트 콘텐츠가 측정되는 곳에서 폭과 높이를 읽고, 직전에 보낸 두 클래스와 비교합니다. 둘 다 같으면 아무것도 하지 않습니다. 한쪽만 바뀌어도 한 번 보내고, 둘 다 바뀌어도 한 번입니다. 두 클래스가 한 레코드에 있으므로 나눠 보낼 이유가 없습니다.
- 창을 잡고 끄는 동안 레이아웃 패스는 초당 수십 번 일어납니다. 패스마다 이벤트를 보내면 그만큼 VirtualDom을 돌게 되고, 이는 NFR-9 위반입니다.
- **양쪽의 시작 상태는 두 축 모두 `Compact`입니다.** Host는 아무것도 받기 전에 `Compact`/`Compact`를 들고 있고, Renderer도 마지막으로 보낸 두 클래스를 그렇게 두고 시작합니다. 그래서 600dp보다 좁고 480dp보다 낮게 열린 창은 첫 측정에서도 아무것도 보내지 않습니다. 이미 서로 같은 값을 알고 있는데 그것을 한 번 더 보내는 것은 정의상 불필요한 경계 호출이고, 그 호출은 VirtualDom을 한 번 돌립니다.
- 창이 그보다 넓거나 높게 열리면 첫 측정에서 한 번 보냅니다. Host가 실제 크기를 알게 되는 시점은 그때입니다. 세로 방향 폰처럼 좁지만 높은 창도 여기에 해당하며, 그 한 번은 Host가 모르는 것을 알리는 호출이지 이미 아는 것을 되풀이하는 호출이 아닙니다.
- 새 Host는 `Compact` 기본값에서 시작합니다. 한 프로세스에서 Host를 다시 만들어도 두 시작 상태가 어긋나지 않습니다.
- 클래스가 같고 dp만 바뀐 경우는 보내지 않습니다. 따라서 Host가 보는 dp 값은 **마지막 클래스 전환 시점의 값**입니다. 픽셀 단위로 따라오는 dp가 필요한 레이아웃은 Compose 쪽에서 하는 일이고, 그것을 Host로 올리면 프레임마다 VirtualDom을 도는 것과 같습니다.

#### 20.5 비용

- **추가 지연 프레임 없음**: 이벤트는 기존 동기 dispatch 경로를 그대로 타고, 그 호출이 돌려준 배치가 같은 프레임에 적용됩니다. 큐도, 프레임 지연도 없습니다.
- **정상 상태 할당 없음**: Host 쪽 구독 목록은 컴포넌트가 훅을 처음 부를 때만 자라고, 이벤트 처리 경로는 할당하지 않습니다. Renderer 쪽 비교는 마지막 클래스 하나를 들고 있는 것이 전부이며, 레이아웃 패스마다 객체를 만들지 않습니다.

#### 20.6 Host API

```rust
let window = use_window_size();
if window.class == WindowSizeClass::Expanded { /* 사이드바 */ }
```

- `use_window_size()`는 Dioxus 훅 관례를 따릅니다(PR-7). 현재 클래스와 dp 값을 담은 `WindowSize`를 돌려주고, 클래스가 바뀌면 그 훅을 부른 컴포넌트만 다시 렌더링합니다.
- 구독은 훅 상태의 수명을 따릅니다. 컴포넌트가 사라지면 구독도 사라집니다.
- dp만 바뀌고 클래스가 그대로면 아무 컴포넌트도 다시 렌더링되지 않습니다(20.4에 따라 이벤트 자체가 오지 않습니다).

#### 20.7 수용 기준

1. 599dp에서 601dp로 창을 넓히면 `WindowSizeChanged` 이벤트가 **정확히 한 번** 발생합니다.
2. `Compact` 크기로 열린 창은 첫 측정에서 이벤트를 보내지 않습니다.
3. 300dp에서 599dp까지 같은 클래스 안에서 크기를 바꾸는 동안 이벤트가 **한 번도** 발생하지 않습니다.
4. 28바이트 고정 레코드가 Rust와 Kotlin 양쪽에서 같은 바이트로 인코딩되고 디코딩됩니다(프로토콜 벡터).
5. `use_window_size()`를 쓴 컴포넌트가 클래스 전환에서 다시 렌더링되고, 그렇지 않은 형제는 다시 렌더링되지 않습니다.
6. 알 수 없는 `size_class` 값은 `ProtocolError`가 되고 프로세스가 죽지 않습니다.

### FR-21 탐색, 시트, 일시 메시지 (`Agreed`)

FR-15.2의 코어 어휘 29개로는 애플리케이션이 세 가지를 말할 수 없습니다. 화면을 목적지로 나누는 것(탐색), 화면 위에 임시로 무언가를 덮는 것(시트), 그리고 "했습니다"라고 알리는 것(일시 메시지)입니다. 셋 다 없어서는 안 되는 부류이고, 셋 다 FR-15.3에서 한 번 제외됐거나 아예 논의되지 않았습니다.

특히 탐색은 FR-20이 만든 창 크기 클래스가 존재하는 이유 그 자체입니다. FR-20은 Host에게 `use_window_size()`를 줬지만, 그 값으로 만들 수 있는 것을 하나도 주지 않았습니다.

#### 21.1 FR-15.3의 제외를 되짚습니다

FR-15.3은 `NavigationBar`를 "모바일 고유. Android와 iOS에만 있고 데스크톱 세 시스템에 공통 개념이 없습니다"라며, `BottomSheet`를 "Material 고유 어휘"라며 제외했습니다. 두 판단 모두 **위젯 이름을 기준으로 삼았기 때문에** 틀렸습니다.

공통인지 따져야 할 대상은 `NavigationBar`라는 컴포넌트가 아니라 **"목적지 집합 중 하나가 선택되어 있다"는 개념**입니다. 그 개념은 여섯 시스템 모두에 있습니다.

| 시스템 | 좁은 창 | 중간 창 | 넓은 창 |
|---|---|---|---|
| Material 3 | `NavigationBar` | `NavigationRail` | `PermanentNavigationDrawer` |
| Apple | 탭 바(iOS) | 탭 바 또는 사이드바 | 사이드바(`NSSplitViewController`) |
| Fluent 2 | `NavigationView` minimal | `NavigationView` compact | `NavigationView` expanded |
| GNOME | `AdwViewSwitcherBar` | `AdwNavigationSplitView` 접힘 | `AdwNavigationSplitView` 펼침 |
| KDE Breeze | Kirigami 하단 액션 | 좁은 사이드바 | `KPageView` 사이드바 |
| Deepin | 하단 탭 | 사이드바 | 사이드바 |

Fluent의 `NavigationView`가 결정적입니다. 한 컴포넌트가 폭에 따라 세 가지 표시 모드를 스스로 고릅니다. "데스크톱에 공통 개념이 없다"가 아니라, 데스크톱 시스템들은 **이미 그것을 폭에 따라 달라지는 하나의 개념으로 취급하고 있었습니다.**

시트도 같습니다. Material의 bottom sheet, Apple의 `UISheetPresentationController`, Fluent의 `Sheet`/`TeachingTip`, GNOME의 `AdwBottomSheet`, Kirigami의 `OverlayDrawer`. 화면 가장자리에서 밀려 들어오는 임시 표면은 여섯 시스템 모두의 어휘입니다.

따라서 FR-15.3의 해당 두 줄을 철회하고, 여기서 코어 어휘로 편입합니다. 나머지 제외 항목(`Chip`, `FAB`, `Grid`, `RichText`)은 그대로 둡니다.

#### 21.2 탐색은 위젯 셋이 아니라 개념 하나입니다

**Host는 `Navigation` 하나만 선언하고, 막대/레일/서랍 중 무엇으로 그릴지는 Renderer가 고릅니다.**

대안은 Host가 고르는 것, 즉 `use_window_size()`를 읽어 `NavigationBar`, `NavigationRail`, `Drawer` 세 위젯 중 하나를 `rsx!`에서 분기하는 방식입니다. 채택하지 않는 이유는 셋입니다.

1. **Renderer가 이미 폭을 압니다.** Host가 고르려면 폭이 경계를 한 번 올라갔다가 그 결과로 만들어진 트리가 다시 내려와야 합니다. 클래스가 바뀔 때마다 VirtualDom 전체가 돌고, 목적지 노드들이 전부 지워졌다가 다시 만들어집니다. Renderer가 고르면 같은 노드가 배치만 바꿉니다.
2. **경계가 좁아야 합니다(PR-2).** 세 위젯은 위젯 태그 셋, 속성 셋, Renderer 분기 셋입니다. 하나면 전부 하나입니다.
3. **이것이 이 프로젝트의 주장 그 자체입니다.** "선언 하나가 폰에서도 데스크톱에서도 그 플랫폼답게 나온다." 그 주장을 가장 잘 보여줄 자리에서 Host에게 분기를 시키면, 주장은 남지만 근거가 사라집니다.

네 표현 사이의 대응은 **디자인 시스템의 규칙**이지 고정값이 아닙니다(FR-14.1). Renderer의 `ComponentRules`가 크기 클래스를 받아 표현을 답합니다. 기본 대응은 `Compact→막대`, `Medium→레일`, `Expanded→서랍`이고, 어떤 시스템이 다르게 답해도 위젯 코드는 바뀌지 않습니다.

#### 21.2.1 치워둠 (`Agreed`)

**네 번째 표현은 목적지들이 화면에 없는 상태입니다.** 막대도 레일도 서랍도 아니고, 목적지를 부르는 버튼 하나만 남습니다. 버튼을 누르면 서랍이 페이지 위로 덮여 나오고, 목적지를 고르거나 바깥을 누르면 다시 치워집니다.

**Apple 계열에는 선택이 아니라 필수입니다.** 메모, 메일, Finder, 그리고 참조로 삼은 대화 애플리케이션까지, macOS의 사이드바는 전부 치워둘 수 있고 그 버튼은 창 좌상단에 있습니다. 치워둘 수 없는 사이드바는 그 플랫폼의 사이드바가 아닙니다. 레일이 이 자리를 대신할 수 없다는 것은 FR-21.2가 이미 말한 것과 같은 이유입니다. 아이콘에서 글자를 떼어낸 세로줄은 다른 언어의 답입니다.

- 와이어에 새로 실리는 것이 없습니다. `NavigationPresentation`은 Renderer 안의 열거형이고 Host는 여전히 `Navigation` 하나만 선언합니다.
- **치워졌는지 펼쳐졌는지는 Renderer의 상태입니다(D5).** 21.2의 마지막 항목이 이미 정한 규칙이 그대로 적용됩니다. Host 속성이 없고, 사용자가 사이드바를 여닫는 동안 경계를 넘는 것은 아무것도 없습니다.
- 버튼이 놓이는 자리와 모양은 디자인 시스템이 답합니다. 창 캡션을 쓰는 플랫폼에서는 창 버튼과 같은 줄입니다(FR-19.2).
- 서랍이 덮여 나올 때 페이지는 그대로 있습니다. 목적지를 고르는 것은 페이지를 바꾸는 일이고, 그 사이에 페이지가 사라졌다 나타나면 무엇이 바뀌었는지 볼 수 없습니다.

수용 기준:

1. Cupertino와 Liquid Glass에서 목적지를 가진 화면을 좁혀 가면, 서랍이 사라지고 그 자리에 목적지를 부르는 버튼 하나가 남습니다.
2. 그 버튼을 누르면 목적지들이 페이지 위로 나오고, 하나를 고르면 다시 치워지며, 고른 목적지가 선택된 상태로 남습니다.
3. 치워진 동안 페이지는 창 전체를 씁니다. 목적지들이 있던 폭이 빈 띠로 남지 않습니다.
4. 나머지 다섯 디자인 시스템의 대응은 바뀌지 않습니다.

**목적지는 데이터여야 합니다.** `Tabs`처럼 자식을 그대로 그리게 하면 안 됩니다. `Column { Icon, Text }`를 자식으로 받으면 Host가 이미 "아이콘 위에 라벨"이라는 배치를 정해 버린 것이고, 레일이 라벨을 작게 줄이거나 서랍이 아이콘 옆에 라벨을 놓는 결정을 Renderer가 할 수 없게 됩니다. 그래서 목적지는 라벨과 아이콘을 **속성으로** 싣는 전용 위젯 `NavigationItem`입니다.

- **자식 규약**: `Navigation`의 자식 중 `NavigationItem`인 것이 목적지이고, 나머지 자식이 그 화면의 내용입니다. 인덱스로 나누지 않는 이유는, 목적지 수가 조건에 따라 달라지는 순간 Host가 두 목록의 순서를 손으로 맞춰야 하기 때문입니다.
- **선택은 Renderer의 상태입니다(D5).** `SelectedIndex`(태그 42)가 초깃값과 바깥에서 들어온 변경을 주고, 목적지를 고르면 Renderer가 선택을 옮긴 뒤 그 목적지 자신의 `OnClick` 핸들러로 `Clicked`를 보냅니다. `Tabs`와 같은 규약이고 새 이벤트 태그가 없습니다.
- **서랍이 열려 있는지, 레일이 접혀 있는지는 Renderer의 상태입니다.** Host 속성이 없습니다. 창이 넓으면 서랍은 항상 보이고, 그 사실은 Host가 알 필요가 없습니다.

#### 21.3 시트

`Sheet`는 `Dialog`와 같은 오버레이이고, 같은 두 속성을 씁니다: `Open`(태그 40)이 Renderer의 열림 상태를 seed하고, `OnDismiss`(태그 41)가 사용자가 닫으려 했다는 사실을 한 번 알립니다. 자식이 내용입니다.

- **끌린 거리, 스냅 위치, 애니메이션은 경계를 넘지 않습니다(D5).** 사용자가 시트를 절반쯤 끌어내리는 동안 Rust는 아무것도 듣지 않습니다. 끝까지 내려가서 닫히면 그때 `OnDismiss` 한 번입니다.
- **어느 가장자리에서 들어오는지는 Renderer가 정합니다.** 좁은 창에서는 아래에서, 넓은 창에서는 옆에서 들어오는 것이 여섯 시스템의 공통 관행입니다. 탐색과 같은 이유로 이 선택도 `ComponentRules`가 크기 클래스를 받아 답합니다. Host에 `bottom: bool` 같은 속성을 두지 않습니다.

#### 21.4 일시 메시지는 노드가 아닙니다

Snackbar를 위젯 태그로 만들면 Host가 **자기 소멸 타이머를 모델링해야 합니다.** "지금부터 4초 동안 열림"이라는 상태를 Rust가 들고, 4초 뒤에 워커가 신호를 보내 프레임을 돌려서 노드를 지워야 합니다. 4초짜리 애니메이션을 위해 VirtualDom이 도는 셈이고, D5가 스크롤 위치와 IME 조합을 Renderer에 둔 것과 정확히 같은 이유로 틀렸습니다.

메시지는 **수명을 가진 사건**이지 트리의 일부가 아닙니다. 그래서 새 **Mutation 태그 12 `ShowMessage`** 하나를 더합니다. Host는 "이 말을 전하라"고 한 번 말하고 잊습니다.

```
tag 12, 32바이트: handler_id: u64, text: (offset, len), action: (offset, len), duration: u16, reserved: u16
```

- `handler_id`는 동작 라벨을 눌렀을 때 보낼 핸들러 id입니다. 동작이 없으면 0이고, 그때 `action`은 빈 문자열입니다. 누르면 기존 `Clicked`(이벤트 태그 1)를 `node_id = 0`으로 보냅니다. 메시지에는 노드가 없기 때문입니다.
- `duration`은 `Short=1`, `Long=2`인 닫힌 열거형입니다. 실제 밀리초는 디자인 시스템이 정합니다. 반드시 확인을 받아야 하는 메시지는 일시 메시지가 아니라 `Dialog`이므로 "무기한"은 두지 않습니다.
- **연달아 온 메시지는 줄을 섭니다.** 한 번에 하나만 보이고, 보이는 것이 수명을 다하면 다음 것이 나타납니다. 나중 것이 앞의 것을 지우면 사용자가 방금 한 일에 대한 응답을 못 보게 되고, 둘을 겹쳐 쌓으면 화면이 가려집니다. 줄은 유한하며(8개) 넘치면 **가장 오래 기다린 것부터 버립니다.** 밀려 버려지는 쪽이 이미 사용자가 잊은 조작에 대한 응답이기 때문입니다.
- 큐도, 스레드 홉도 아닙니다. `ShowMessage`는 다른 모든 레코드와 같은 배치를 타고 같은 호출 안에서 적용됩니다(PR-1).

#### 21.5 와이어 추가분

위젯 태그(FR-15.2의 29 뒤에 덧붙입니다):

| 태그 | 위젯 | 여섯 시스템 대응 |
|---|---|---|
| 30 | `Navigation` | 21.1의 표. 표현은 Renderer가 고릅니다 |
| 31 | `NavigationItem` | 목적지 하나. 라벨과 아이콘을 속성으로 싣습니다 |
| 32 | `Sheet` | 가장자리에서 들어오는 임시 표면 |

속성 태그: 위젯 태그 30-32의 블록은 **60-69**입니다(FR-15.2.2의 다음 블록).

| 태그 | 속성 | 값 |
|---|---|---|
| 60 | `Icon` | `IconRole` 태그(u32). 0은 "보내지 않음" |

나머지는 전부 기존 태그를 씁니다. `Navigation`은 `SelectedIndex`(42), `NavigationItem`은 `Text`(1), `Enabled`(3), `OnClick`(5), `Sheet`는 `Open`(40)과 `OnDismiss`(41)입니다.

`IconRole`에 탐색이 필요로 하는 의미 셋을 덧붙입니다: `Home=9`, `List=10`, `Inbox=11`. 기존 8개와 같은 규칙으로, Host는 의미만 보내고 그림은 Renderer가 시스템별로 그립니다.

#### 21.2.2 목적지 옆에 있어야 하는 것들 (`Agreed`)

참조의 사이드바는 목적지 목록만이 아닙니다. 머리에 애플리케이션의 마크와 이름이 있고, 목적지들이 이름 붙은 묶음으로 나뉘어 있으며, 발치에 계정 줄이 있습니다. 21.2가 정한 것은 목적지들뿐이라 나머지 셋은 말할 방법이 없었고, 샘플은 그래서 사이드바에 목적지 두 개만 있는 화면을 그리고 있었습니다.

**새 위젯은 만들지 않습니다.** 셋 다 이미 있는 어휘로 말합니다.

- **머리와 발치는 `ScaffoldSlot`입니다.** `Navigation`의 자식 중 `Slot`(태그 61)이 `TopBar`인 것은 목적지들 **위**에, `BottomBar`인 것은 **아래**에 놓입니다. 슬롯은 "내 부모 안에서 내가 어디에 놓이는지"를 말하는 것이고, 그 뜻은 Scaffold 안에서나 Navigation 안에서나 같습니다. 새 위젯 태그도 새 속성 태그도 필요 없습니다.
- **묶음은 `NavigationItem`의 속성 하나입니다.** `Section`(추가 전용 태그 **76**)은 문자열이고. 67에서 74는 18.1.1이 제스처로, 75는 FR-30이 `ButtonKind`로 이미 예약했으므로 그 뒤의 첫 자리입니다, 같은 문자열을 든 이웃한 목적지들이 한 묶음이 되며 그 문자열이 묶음의 머리글이 됩니다. 속성을 보내지 않은 목적지는 어느 묶음에도 속하지 않고 지금처럼 그려집니다.
- **무엇으로 그려지는지는 디자인 시스템이 답합니다**(FR-14.1). 머리글의 크기와 색, 접히는지 여부, 슬롯이 스크롤을 따라 움직이는지는 `ComponentRules`의 답이지 Host의 속성이 아닙니다.

**막대에서는 슬롯도 묶음도 그려지지 않습니다.** 하단 막대는 목적지 다섯 개가 나란히 선 줄이고, 거기에 머리글이나 워드마크가 들어갈 자리는 없습니다. 좁은 창에서 사라지는 것은 목적지가 아니라 그 둘입니다.

수용 기준:

1. 서랍으로 그려진 `Navigation`에서 `TopBar` 슬롯의 내용이 첫 목적지 위에, `BottomBar` 슬롯의 내용이 마지막 목적지 아래에 나타납니다.
2. 같은 `Section`을 든 이웃한 목적지들 위에 그 문자열이 머리글로 한 번 나타나고, 문자열이 바뀌면 새 머리글이 납니다.
3. 막대로 그려진 같은 선언에는 슬롯도 머리글도 나타나지 않고, 목적지들만 나란히 섭니다.
4. 속성을 보내지 않은 목적지의 그림은 이 변경 전과 같습니다.

#### 21.5.1 참조 화면이 요구하는 의미들 (`Agreed`)

클론 코딩한 샘플들을 참조와 나란히 놓자 어휘에 없어서 그릴 수 없는 것이 여덟 개 나왔습니다. `Menu=12`, `History=13` 뒤에 이어 붙입니다.

| 태그 | 의미 | 어디에 쓰이는가 |
|---|---|---|
| 14 | `Send` | 컴포저가 지금 쓴 것을 보냅니다. 위를 가리키는 화살표입니다 |
| 15 | `Mic` | 말로 넣습니다 |
| 16 | `Image` | 그림 |
| 17 | `Video` | 영상 |
| 18 | `Library` | 모아 둔 것 |
| 19 | `Sidebar` | 옆의 목적지들을 치우거나 꺼냅니다(21.2.1) |
| 20 | `Compose` | 새로 씁니다. 펜입니다 |
| 21 | `Collapse` | 접힌 것을 펴거나 접습니다. 아래를 가리키는 꺾쇠입니다 |

- **여덟 개 다 뜻이고 그림이 아닙니다**(16.2). `Send`는 "보낸다"이지 "위 화살표"가 아니며, 어느 시스템이 종이비행기로 그려도 규칙은 그대로입니다.
- **없는 것을 가까운 것으로 대신 쓰지 않습니다.** `Send` 자리에 `Forward`를 놓으면 화살표가 옆을 가리키고, 그것은 다음으로 넘어간다는 뜻이지 보낸다는 뜻이 아닙니다. 어휘에 없는 의미는 대신 쓰는 것이 아니라 더하는 것입니다.
- 기존 태그는 움직이지 않습니다. 추가 전용입니다.

#### 21.6 디자인 시스템은 하나씩 늘어나야 합니다

세 부류 모두 `ComponentRules`에 답을 하나씩 더합니다: `navigation(WindowSizeClass)`, `sheet(WindowSizeClass)`, `message()`.

**셋 다 기본 구현을 가집니다.** 기본값은 그 시스템의 토큰 표(색, 모서리, 간격)에서 유도되므로, 아무것도 재정의하지 않은 시스템도 남의 색이 아니라 자기 색으로 나옵니다. 재정의하지 않으면 안 되는 것은 없고, 시스템은 의견이 있는 것만 답합니다. 일곱 번째 디자인 시스템이 들어올 때 이 세 멤버 때문에 컴파일이 깨지지 않는 것이 요점입니다(FR-14.2).

#### 21.7 수용 기준

1. 같은 `Navigation` 선언이 폭 500dp에서 하단 막대로, 700dp에서 레일로, 1100dp에서 서랍으로 그려집니다. 세 폭의 스크린샷으로 확인합니다.
2. 창을 넓혀 표현이 바뀌는 동안 `Navigation`의 자식 노드가 새로 만들어지지 않습니다(`Create` 레코드가 나오지 않습니다).
3. 목적지를 고르면 그 목적지의 `OnClick` 핸들러로 `Clicked` 이벤트가 **정확히 한 번** 갑니다. 선택 표시는 Host 배치 없이 바뀝니다.
4. `Sheet`를 끌어 닫으면 `OnDismiss`가 한 번 가고, 끄는 동안에는 아무 이벤트도 가지 않습니다.
5. `ShowMessage` 두 개가 한 배치에 오면 첫 번째만 보이고, 그 수명이 끝난 뒤에 두 번째가 보입니다.
6. 메시지의 동작 라벨을 누르면 `node_id = 0`으로 `Clicked`가 갑니다.
7. 32바이트 `ShowMessage` 레코드가 Rust와 Kotlin 양쪽에서 같은 바이트로 인코딩되고 디코딩됩니다(PR-4 벡터).
8. 세 위젯이 여섯 디자인 시스템 전부에서 렌더링되고, 새 디자인 시스템은 `ComponentRules` 구현 하나로 끝납니다.

#### 21.8 지금까지 확인된 것

1번부터 7번까지 통과했습니다. 1번은 `fr21_one_declaration_is_a_bar_a_rail_and_a_drawer`가 500dp/700dp/1100dp에서 막대/레일/서랍의 치수를 재고, 같은 폭에서 찍은 스크린샷 아홉 장(디자인 시스템 3 × 폭 3)을 눈으로 확인했습니다. 2번은 `fr21_navigation_is_one_declaration_and_a_resize_creates_nothing`, 3번은 Host와 Renderer 양쪽, 4번은 `fr21_dragging_a_sheet_shut_reports_one_dismissal_and_nothing_during_the_drag`, 5번은 `fr21_two_messages_in_one_batch_are_shown_one_at_a_time_in_order`, 6번은 양쪽, 7번은 갱신된 `mutations.bin` 벡터와 `fr21_the_message_record_in_the_vector_decodes_to_the_same_values`입니다.

**8번은 아직입니다.** Renderer의 `ComponentRules` 구현은 현재 셋(Material 3, Cupertino, Fluent)이고, GNOME/Breeze/Deepin은 다른 작업에서 들어오는 중입니다. 세 멤버 전부 토큰 표에서 유도한 기본 구현을 가지므로 그 셋이 합류할 때 컴파일이 깨지지 않고 각자의 색으로 나오지만, "여섯 시스템 전부"는 그 작업이 합쳐진 뒤에 확인해야 합니다. 그때까지 이 요구사항은 `Done`이 아닙니다.


### FR-22 샘플 애플리케이션의 참조 구현 (`Agreed`)

#### 22.6 데스크톱 샘플 배포 번들 (`Agreed`)

샘플 릴리스는 각 데스크톱 대상에 설치하거나 바로 실행할 수 있는 플랫폼 형식을 제공합니다. macOS arm64는 실행 가능한 `.app`과 전달용 `.dmg`, Windows x64는 압축을 푼 자리에서 실행되는 `.zip`과 `.msix`, Linux x64와 arm64는 `.AppImage`를 만듭니다. 이름, 번들 식별자, 설명은 각 샘플의 `Dioxus.toml`에서 읽고 아이콘과 플랫폼 메타데이터에 사용합니다.

**2026-10-03(INTENT D17, NFR-15): 번들이 담는 것은 실행 파일 하나입니다.** 렌더러와 Skia와 ICU 데이터가 실행 파일 안에 있으므로, 아래 문단의 라이브러리 배치는 GraalVM native-image 경로의 기록이고 1.0.0의 번들에는 해당하지 않습니다.

렌더러 라이브러리와 동반 파일은 각 번들 안에 그대로 들어갑니다. macOS는 렌더러의 `lib` 디렉터리 전체를 `Contents/Frameworks/lib`에 두며 실행 파일의 참조를 상대 경로로 바꿉니다. Windows는 DLL을 실행 파일 옆에 두고 렌더러의 `lib` 디렉터리를 보존합니다. Linux는 실행 파일 옆 `lib` 디렉터리를 두고 `DT_NEEDED`와 rpath를 상대 경로로 바꿉니다. 라이브러리 파일 자체를 압축하지 않습니다.

수용 기준:

1. 네 데스크톱 릴리스 행은 각 샘플에 해당 플랫폼 형식의 아티팩트를 만들고 체크섬을 기록합니다.
2. 각 번들의 실행 파일은 빌드 머신의 렌더러 절대 경로를 참조하지 않으며, 번들 안에 렌더러와 동반 파일이 있습니다.
3. 번들의 이름, 식별자, 설명은 샘플 설정과 일치하고 아이콘이 포함됩니다.
4. MSIX는 서명되지 않은 패키지로 생성됩니다. 설치에는 개발자 모드 또는 신뢰된 인증서로 다시 서명하는 절차가 필요함을 릴리스에 알립니다. 실제 설치 확인은 Windows 환경에서 수동으로 합니다.

**2026-09-22: 샘플이 일곱 플랫폼 전부에서 돕니다.** 데스크톱 넷(macOS arm64, Linux x64, Linux arm64, Windows x64)과 Android, iOS 시뮬레이터, 브라우저입니다. statistics 샘플을 iOS 시뮬레이터와 Android 에뮬레이터와 Chromium에서 각각 띄워 같은 세이지와 주황과 파우더 블루가 나오는 것을 확인했습니다. 통합 샘플이 플랫폼과 무관하게 같은 디자인을 그린다는 것이 이것으로 처음 실증됐습니다.

**2026-09-23: 그러나 일곱 플랫폼 중 여섯이 사용자가 겪을 경로로 만들어진 것이 아니었습니다.** Android 하나를 지적받고 나머지를 전부 확인한 결과입니다. 샘플은 "사용자가 쓰는 방식으로 쓴 것"이어야 하는데, 릴리스 아티팩트는 저장소 안에만 있는 스크립트로 만들었습니다. 그래서 배포한 것이 사용자가 만들 것과 다르고, 사용자 경로가 깨져 있어도 아무도 모릅니다. 실제로 셋이 깨져 있었습니다.

| 플랫폼 | 릴리스를 만든 방법 | `dx`로 했을 때 | 상태 |
|---|---|---|---|
| Android | 우리 Amper 모듈에 cdylib을 넣어 빌드 | `dx build --platform android`로 APK가 나오고 에뮬레이터에서 같은 화면을 그림 | 2026-09-23 고침 |
| 데스크톱 | `cargo build` 뒤 `scripts/bundle-renderer.sh`로 렌더러를 옆에 넣고 install name 수정 | `.app`이 나오고 이 기계에서는 뜨지만, 렌더러 dylib을 빌드 트리의 절대 경로로 참조함 | 만들어지기는 하나 남에게 줄 수 없음 |
| iOS | `build-sample-ios.sh`가 `xcrun clang`으로 직접 링크하고 Info.plist를 직접 씀 | `dx build --platform ios` 링크 실패. `compose_rust_renderer_run`과 `..._request_frame`이 undefined | 사용자 경로 없음 |
| 웹 | 우리 Amper 빌드와 `build-sample-pages.sh` | `dx build --platform web`이 **성공을 보고하고** 뜨지 않는 페이지를 냄. Rust 모듈이 `compose_rust_renderer`를 import하는데 그것을 주는 모듈이 산출물에 없음 | 사용자 경로 없음, 게다가 조용히 실패 |

세 가지가 남습니다. 데스크톱은 번들이 자기 완결적이어야 합니다(`bundle-renderer.sh`가 하는 일이 크레이트 쪽으로 들어가야 하고, 저장소 스크립트로 남아 있으면 크레이트만 쓰는 사람에게는 없는 것입니다). iOS는 `dx`가 만드는 바이너리에 렌더러 아카이브가 링크되어야 합니다. 웹은 `dx`의 산출물에 Kotlin/Wasm 모듈과 그 둘을 싣는 페이지가 없습니다.

이를 위해 샘플은 라이브러리가 되고 데스크톱 바이너리는 그 한 줄이 됐습니다. Android의 Activity와 브라우저 페이지는 프로세스와 프레임 루프를 자기가 소유하므로 우리 `main`을 부를 자리가 없고, iOS는 렌더러가 정적 아카이브라 애플리케이션 자체가 라이브러리입니다.

macOS `.app`을 만들 때 `Info.plist`에 `NSHighResolutionCapable`을 `true`로 넣어야 합니다. 번들 없는 실행 파일은 AppKit이 기본으로 고해상도로 그리지만, 번들이 이 키를 말하지 않으면 1x로 그린 뒤 확대해서 둥근 모서리의 계단이 눈에 보입니다. 번들을 만들기 시작하는 순간 생기는 회귀이므로 번들러의 조건으로 둡니다.

iOS는 시뮬레이터용만 냅니다. 기기용 번들은 Apple이 발급한 인증서와 그 기기를 지목하는 프로비저닝 프로파일로 서명해야 하고 자체 서명은 거부됩니다.

샘플 네 개는 어휘가 실제로 쓸 만한지 확인하는 장치입니다. 지금까지 확인한 것은 "위젯이 동작하는가"였고, "이 어휘로 사람들이 실제로 쓰는 화면을 말할 수 있는가"는 확인하지 않았습니다. 그래서 샘플마다 참조 디자인을 못 박고, 그 화면이 나오는지를 기준으로 삼습니다. 참조 이미지는 `docs/references/design-systems/README.md`의 "Sample Apps"에 있습니다.

두 축이 동시에 움직입니다.

- **디자인은 플랫폼을 따릅니다.** 샘플은 디자인 시스템을 고르지 않습니다(FR-14의 `Theme::adaptive`). 같은 선언이 macOS에서 Liquid Glass로, Windows에서 Fluent로, Linux에서 GNOME/Breeze/Deepin으로 나옵니다. 계산기의 참조가 셋(Windows, macOS, Deepin)인 것은 그래서 모순이 아닙니다. **한 선언의 세 가지 결과**를 찍은 사진입니다.
- **레이아웃은 창 크기 클래스를 따릅니다.** 샘플은 `use_window_size()`(FR-20)만 읽습니다. 목적지 집합이 막대인지 레일인지 서랍인지, 시트가 어느 가장자리에서 오는지는 Renderer가 정합니다(FR-21).

#### 22.1 샘플별 참조와 형태

| 샘플 | 참조 | Compact | Medium | Expanded |
|---|---|---|---|---|
| `todo` | jordansinger/todo-macos-swiftui-sample, Dribbble ToDo Scheduler | 한 열, 목적지는 하단 막대, 작성은 시트 | 한 열, 목적지는 레일 | 측정폭으로 좁힌 한 열, 목적지는 서랍 |
| `notepad` | iOS 26 메모, Fluent 2 Loop | 문서 하나, 문서 목록은 시트 | 문서 하나, 목록은 시트 | 목록과 문서가 나란히 |
| `calculator` | Windows 11 계산기, macOS 계산기, Deepin 계산기 | 한 열 키패드, 기록은 시트 | 함수열이 그리드에서 빠진 넓은 키패드 | 키패드 옆에 기록 |
| `chat` | Google Gemini 데스크톱/세로 | 한 열, 목적지는 하단 막대 | 목적지는 레일 | 목적지는 서랍(사이드바) |

세 참조가 모두 공유하는 것이 화면의 뼈대입니다.

- **계산기**: 읽는 자리(식 한 줄과 결과 한 줄, 둘 다 오른쪽 정렬)가 위, 메모리 줄이 그 아래, 키패드가 창 아래쪽에 붙습니다. 키는 창이 커져도 손가락보다 크게 자라지 않습니다. 세 참조가 전부 그 모양이고, 다른 것은 디자인 시스템이 정하는 색과 모서리뿐입니다.
- **메모장**: 문서에 이름이 있고 그 이름이 문서 맨 위에 제목으로 섭니다. 문서는 여러 개이며 목록에서 고릅니다. 서식은 시트로 열립니다.
- **채팅**: 목적지 집합이 사이드바가 되고, 대화가 비어 있으면 가운데에 이 화면이 무엇인지 적힌 자리가 서며, 작성란은 페이지 아래에 떠 있는 둥근 막대입니다.
- **할 일**: 화면 제목이 두 줄(무엇을 보고 있는지와 그 상태)이고, 작성은 좁은 창에서 시트로 물러납니다.

#### 22.2 값은 역할로만 말합니다

샘플 코드에는 16진수 색도, 근거 없는 dp 상수도 없습니다. 색은 `ColorRole`, 글자는 `TypeRole`, 모서리는 `ShapeRole`, 간격은 `SpaceRole`입니다. 참조가 역할 어휘로 말할 수 없는 것을 요구하면 샘플에서 우회하지 않고, 역할을 스키마와 일곱 디자인 시스템 전부에 추가합니다(FR-14.1).

계산기 연산자 키는 기존의 네 `ButtonVariant`로 말할 수 없습니다. `Filled`는 모든 시스템에서 강조색 바탕이라 Windows 참조와 다르고, `Tonal`은 모든 시스템에서 중립 바탕이라 macOS 참조와 다릅니다. 따라서 `Operator` 변형을 추가합니다. 이것은 샘플이 색을 고르는 우회로가 아니라 디자인 시스템이 계산기 연산자를 해석하는 역할입니다.

| 디자인 시스템 | `Operator` 표현 |
|---|---|
| Cupertino, Liquid Glass | 강조색 바탕과 그 위의 글자색 |
| Fluent 2 | 숫자 키와 같은 중립 바탕과 본문 글자색 |
| Deepin | 숫자 키와 같은 중립 바탕과 강조색 글자색 |
| Material 3, GNOME, Breeze | 중립 바탕과 강조색 글자색 |

등호는 일곱 시스템 모두 기존 `Filled`를 사용합니다. 그래서 Fluent에서는 등호만 강조색으로 채워지고, macOS에서는 연산자와 등호가 모두 강조색으로 채워지며, Deepin에서는 등호만 채워지고 연산자는 파란 글리프로 남습니다. 변형의 와이어 태그는 기존 네 값 뒤의 `Operator = 5`이고, 이전 태그는 바꾸지 않습니다.

Gemini 참조의 배경과 검색도 샘플이 색과 크기를 정해서는 만들 수 없습니다. `NavigationStyle`은 페이지 배경 그라데이션의 시작과 끝 역할을 선택할 수 있고, 값이 없으면 기존 단색 배경을 그대로 씁니다. Liquid Glass는 `Background`에서 `PrimaryContainer`로 이어지는 그라데이션을 선택하고, 반투명 탐색 컨테이너가 그 위에 놓입니다. 따라서 넓은 창에서는 색조가 든 반투명 사이드바 뒤로 페이지가 비치고, 좁은 창에서는 같은 그라데이션이 한 열 페이지의 바탕이 됩니다.

`IconRole::Search`인 목적지는 검색이라는 의미를 이미 싣고 있습니다. Host는 검색 항목 하나와 클릭했을 때 열 검색 시트만 선언하고, 그 항목은 모든 디자인 시스템과 모든 크기에서 보통 목적지로 남습니다.

이 문단은 원래 Liquid Glass의 Expanded 탐색이 그 목적지를 `ShapeRole::Full` 검색 막대로 그린다고 규정했습니다. 근거는 그 플랫폼의 사이드바가 검색을 그렇게 둔다는 기억이었고, 확인해 보니 사실이 아닙니다(INTENT D14). 메모 앱의 사이드바에는 검색 필드가 없고, 검색은 본문 열의 툴바에 있습니다. 클론 코딩의 참조도 검색을 평범한 행으로 둡니다. 목적지 목록 한가운데에 혼자 칠해진 막대를 세우면 선택 표시와 구분되지 않는 두 번째 상태가 생기고, 실제로 그렇게 보였습니다.

#### 22.3 수용 기준

1. 계산기 화면이 식 줄, 결과 줄, 메모리 줄, 키패드 순서로 나오고, 메모리 키가 실제로 값을 저장하고 되불러옵니다.
2. 계산기의 키패드가 창 아래쪽에 붙고, 읽는 자리가 남는 높이를 가져갑니다. 참조 셋의 비율(읽는 자리 하나에 키패드 둘에서 셋)을 따릅니다.
3. 메모장이 문서를 여러 개 들고, 문서 이름이 페이지 맨 위 제목으로 섭니다. Expanded에서 목록이 문서 옆에 서고, 그보다 좁으면 시트로 물러납니다. 서식 제어는 별도 시트에서 문서의 글자 역할을 바꿉니다.
4. 채팅의 목적지 집합이 하나의 `Navigation` 선언이고, 대화가 비어 있을 때 가운데에 안내가 섭니다.
5. 채팅의 작성란이 `ShapeRole::Full`의 떠 있는 막대이며, 보내기는 그 안에 있습니다.
6. 할 일의 제목이 두 줄이고, Compact에서 작성이 시트로 물러납니다.
7. 네 샘플이 일곱 디자인 시스템 × 두 색 구성 × 세 폭 전부에서 기록되고 그려집니다(`scripts/sample-shots.sh`).
8. 적응형 샘플 네 개(22.1)의 소스 어디에도 16진수 색 리터럴이 없습니다. `scripts/tests/samples-speak-in-roles.test.sh`가 그 넷을 검사합니다. 통합 샘플은 22.5를 따릅니다.
9. 계산기의 `Operator` 키가 Cupertino, Fluent 2, Deepin에서 22.2 표의 서로 다른 표현으로 나옵니다. Fluent 2에서 강조색 바탕은 등호 하나뿐입니다.
10. Liquid Glass 채팅은 역할에서 온 그라데이션 위에 반투명 탐색 컨테이너를 그립니다. `Search` 목적지는 다른 목적지와 같은 행으로 그려지며, 어느 크기에서도 자기만의 막대나 테두리를 갖지 않습니다.
11. 채팅의 검색 목적지를 누르면 검색 필드가 있는 시트가 열리고, 입력한 문자열로 대화 목적지가 걸러집니다.
12. 통합 샘플은 참조 그림의 강조색과 면 색을 자기 `palette` 모듈에서 리터럴로 이름 짓고, 그 값을 고정하는 테스트를 가집니다.
13. 통합 샘플의 하단 막대는 아이콘만 있고, 선택된 목적지는 그 참조의 강조색으로 그려집니다.
14. 통합 샘플에는 `PrimaryContainer`, `SecondaryContainer`, `TertiaryContainer`로 칠한 카드나 패널이 없습니다.

#### 22.5 통합 샘플은 참조 그림의 색을 자기 이름으로 말합니다

22.2는 적응형 샘플 네 개의 규칙입니다. 그 넷은 플랫폼의 디자인 시스템을 따르므로, 색을 역할로 말해야 일곱 시스템 어디에서나 자기 색으로 나옵니다.

통합 샘플 일곱 개는 반대입니다(FR-14.3). 참조 그림 한 장이 그 샘플의 디자인이고, 그림은 자기 강조색과 자기 면 색을 이미 가지고 있습니다. 그것을 역할로 말하면 활성 디자인 시스템의 팔레트가 대신 들어옵니다. 일곱 개가 전부 그렇게 되어 있었습니다. 강조색은 전부 테마의 파랑이었고, 카드와 패널은 `PrimaryContainer`와 `SecondaryContainer`에서 와서 연보라와 하늘색과 분홍으로 떴습니다. 참조에는 그 색이 한 군데도 없습니다.

- **그림이 리터럴 색을 보여 주는 자리는 샘플이 그 색을 자기 코드에서 이름 짓습니다.** FR-13.1의 `Paint::Literal`이 그 탈출구이고, 통합 샘플이 그 탈출구가 필요한 자리입니다.
- **한 샘플이 공유하는 색은 그 샘플의 `palette` 모듈 하나에 모읍니다.** 트리 곳곳에 흩어진 16진수는 고칠 때 한 군데가 남습니다.
- **그 값은 테스트가 고정합니다.** 리터럴인지와 ARGB 값 둘 다를 단언하므로, 누군가 역할로 되돌리면 테스트가 먼저 말합니다.
- **하단 막대도 참조가 정합니다.** 참조의 막대는 아이콘만 있고 라벨도 선택 알약도 위쪽 구분선도 없습니다. `Navigation`은 활성 디자인 시스템의 막대를 그리므로(FR-21), 그 막대를 요구하는 참조는 `Navigation`으로 말할 수 없습니다. 통합 샘플은 자기 막대를 아이콘과 누를 수 있는 것으로 직접 그립니다.
- **적응형 샘플 네 개는 22.2 그대로입니다.** 리터럴은 통합 샘플의 예외이지 새 기본값이 아닙니다.

#### 22.4 지금까지 확인된 것

1번부터 6번, 8번부터 11번까지 자동 검사로 확인했습니다. Rust 전체 검사는 `DXC_SKIP_KOTLIN=1 ./scripts/check.sh`로 통과했고, Renderer JVM 빌드와 157개 테스트도 통과했습니다. Renderer의 대상을 제한하지 않은 Kotlin 빌드도 Web, iOS, Android, JVM을 모두 컴파일했습니다.

12번부터 14번은 샘플마다 따로 확인합니다. 샘플의 팔레트 테스트와 막대 테스트가 그 샘플에 대해 통과하면 그 샘플이 확인된 것입니다.

**7번의 화면 비교는 아직입니다.** 이 작업에서는 네이티브 Renderer를 만들거나 화면을 띄우지 않습니다. 중앙에서 한 번 만든 Renderer로 참조와 같은 폭의 화면을 찍어 비교하기 전까지 이 요구사항은 `Done`이 아닙니다.

### FR-11 스키마 확장 (서드파티 위젯) (`Agreed`)
스키마에 없는 Compose 컴포넌트는 **E1 확장 스키마 패키지**로 추가합니다. 확장은 런타임 플러그인이 아니라 Host와 Renderer의 소스 빌드에 함께 들어가는 한 쌍입니다. Rust 쪽 선언이 위젯 태그, 속성 태그, 타입이 붙은 Dioxus 컴포넌트를 소유하고, Kotlin 쪽 구현이 그 태그와 속성을 실제 `@Composable` 호출로 해석합니다.

| 안 | 방식 | 장점 | 단점 |
|---|---|---|---|
| E1 확장 스키마 패키지 | 위젯 정의(Rust)와 인터프리터 구현(Kotlin)을 한 쌍으로 묶은 확장 단위. 빌드 시 코드젠으로 스키마에 병합 | 타입 안전(FR-7 그대로), 오버헤드 없음 | Host와 Renderer 재빌드 필요 |
| E2 Kotlin 어노테이션 기반 역생성 | `@DioxusWidget` 붙인 `@Composable` 함수에서 KSP로 Rust 컴포넌트와 스키마 생성 | Compose 라이브러리 래핑 비용이 최소 | 단일 소스가 Kotlin으로 뒤집힘(D6와 충돌 검토 필요), 파라미터 타입 매핑 한계 |
| E3 동적 Slot 위젯 | `Custom { kind: "id", props: bytes }` 범용 노드, Renderer 측 레지스트리 | 스키마 변경 없이 추가 | 타입 안전이 런타임 검사로 격하 (C3 위반 소지) |

현재 `dioxus-compose/src/codegen.rs`는 Rust의 정적 스키마 표를 순회해 `Protocol.gen.kt`를 만드는 단방향 생성기입니다. 이 구조에서는 E1이 D6와 FR-7을 그대로 보존합니다. 새 태그가 표와 스키마 해시에 들어가고, 기존 `Create`와 `SetProp`의 고정 레이아웃을 그대로 사용하며, 표에 없는 태그는 계속 `ProtocolError`입니다.

E2를 기본 경로로 삼으면 KSP가 Rust 열거형, Dioxus element, 컴포넌트 API까지 역생성해야 합니다. Kotlin 타입 중 Rust와 와이어 타입으로 손실 없이 옮길 수 없는 타입도 별도 규칙이 필요합니다. 이는 현재 생성 방향을 뒤집고 Rust 단일 소스를 깨므로 채택하지 않습니다. 나중에 많은 Compose API를 감쌀 때에도 E2는 Kotlin을 런타임에 등록하는 장치가 아니라, 검토해서 커밋할 E1 Rust 선언 초안을 만드는 오프라인 도구로만 허용합니다. E3는 임의 바이트와 문자열 식별자가 고정 스키마를 우회하므로 채택하지 않습니다.

#### 11.1 저작 단위와 빌드 비용
확장 작성자는 다음 두 소스 파일을 책임집니다.

1. Host의 Rust 확장 파일 하나: 추가 전용 범위의 위젯 태그와 속성 태그, Dioxus element, 타입이 붙은 `#[component]`를 선언합니다. 속성은 기존 `PropertyValue`의 `String`, `bool`, `i64`, `f32` 중 하나로 내립니다.
2. Renderer의 Kotlin 확장 파일 하나: 생성된 `WidgetKind`와 `PropertyKind`를 처리하고 실제 Compose 라이브러리의 `@Composable`을 호출합니다. 외부 라이브러리라면 Renderer 빌드 의존성도 추가합니다.

`cargo run -p dioxus-compose --bin codegen`은 별도 저작 파일을 요구하지 않고 이 Rust 선언을 정규 스키마에 병합해 `Protocol.gen.kt`를 다시 만듭니다. 생성 파일은 커밋합니다. 태그를 추가하거나 속성 계약을 바꿀 때마다 Host 라이브러리와 모든 플랫폼 Renderer를 함께 다시 빌드하고 배포해야 합니다. 앱의 UI 코드만 바뀌고 확장 스키마가 그대로면 Renderer를 다시 빌드할 필요가 없습니다. 이미 배포된 Renderer에는 새 컴포넌트를 로드하거나 등록할 방법이 없습니다.

#### 11.2 태그와 호환성
- 기본 스키마 태그는 그대로 유지합니다. 확장 위젯과 속성은 추가 전용 태그를 쓰며 기존 태그를 재사용하거나 재정렬하지 않습니다.
- 확장 메타데이터는 `WIDGET_SCHEMA`, `PROPERTY_SCHEMA`, `SCHEMA_HASH`에 포함됩니다. Host와 Renderer 중 한쪽만 갱신하면 핸드셰이크가 화면을 만들기 전에 실패합니다.
- 확장도 `Create` 12바이트와 `SetProp` 24바이트 레코드만 사용합니다. 별도 payload, 동적 map, 직렬화 포맷을 만들지 않습니다.
- Renderer는 생성된 열거형의 모든 확장 위젯을 명시적으로 해석해야 합니다. 생성 코드는 Compose 구현을 추론하지 않습니다.

#### 11.3 수용 기준
- M0에 없는 실제 Compose 컴포넌트 하나를 Rust의 타입이 붙은 컴포넌트로 선언하고, RSX에서 사용하면 추가 위젯 태그와 속성 태그를 가진 고정 레이아웃 Mutation이 나옵니다.
- 코드젠 결과에 같은 `WidgetKind`, `PropertyKind`, 디코더 분기가 생기며 생성 파일 일치 테스트가 통과합니다.
- 확장을 포함한 스키마 해시는 확장이 없던 스키마 해시와 다르고, 잘못된 핸드셰이크는 초기화에 실패합니다.
- 선언되지 않은 위젯 태그와 속성 태그는 Rust와 Kotlin 디코더에서 `ProtocolError`로 남습니다.
- Renderer 구현에는 생성된 종류별 분기와 Compose 호출이 필요하다는 사실을 문서와 빌드가 숨기지 않습니다.

### FR-23 브러시, 폰트, 재질 (`Draft`)

13.7이 셋을 뺐고, 셋 다 같은 이유였습니다. 값이 `(u64, u64)` 모디파이어 슬롯에 들어가지 않는다는 것입니다. 그것은 맞지만 기능을 빼는 근거는 아닙니다. FR-16이 이미 리소스를 등록하고 id로 참조하는 길을 냈고, 셋 다 그 길을 씁니다. 레코드는 고정 길이로 남고 PR-4는 그대로입니다.

#### 23.1 그라데이션과 이미지 브러시
- `Paint`에 세 번째 종류를 더합니다. 지금은 `Role`과 `Literal` 둘이고, `Asset(id)`가 셋째입니다. 위 워드가 종류를 말하는 구조는 그대로입니다.
- 브러시는 FR-16의 에셋으로 등록합니다. 선형·방사형 그라데이션은 정지점 목록이고, 이미지 브러시는 이미 등록된 그림의 id와 타일 방식입니다.
- 쓰이는 자리는 `Paint`가 쓰이는 모든 자리입니다. `background`, `border_color`, `color`, 그리고 FR-17의 드로잉 명령입니다.
- 수용 기준: 정지점 세 개짜리 선형 그라데이션을 배경으로 받은 `Surface`가 여섯 디자인 시스템 모두에서 그라데이션으로 그려집니다. 등록되지 않은 id를 받으면 프로세스를 중단하지 않고 `ProtocolError`를 보냅니다. 브러시를 쓰는 프레임이 정상 상태에서 Host 쪽 할당을 하지 않습니다.

#### 23.2 폰트
- `TypeRole`은 그대로입니다. 더하는 것은 애플리케이션이 자기 폰트를 **등록**하고, 역할이 그것으로 해석되게 하는 길입니다. 이름을 보내지 않으므로 존재 검증이 런타임으로 밀리지 않습니다.
- 등록은 FR-16의 에셋이고, 종류가 하나 늘어납니다(`AssetKind::Font`).
- 역할을 가진 텍스트의 글꼴은 테마 단위입니다. `Theme`에 역할별 폰트 에셋 id를 선택적으로 실어 보냅니다. 역할을 가진 텍스트에는 노드마다 폰트를 지정하는 길을 내지 않습니다. 그것은 13.5가 막는 것과 같은 종류입니다. **역할이 없는 텍스트(`TypeRole::None`, HTML 경로)는 예외로, 노드와 스팬마다 글꼴을 받습니다(FR-40, INTENT D20, 2026-10-03 소유자 결정).**
- 수용 기준: 등록한 폰트로 `Display` 역할을 해석하게 한 애플리케이션의 제목이 그 폰트로 그려지고, 나머지 역할은 시스템 폰트로 남습니다. 없는 id는 `ProtocolError`이고 텍스트는 시스템 폰트로 그려집니다.

#### 23.3 재질 (흐림)
- `Modifier::Material(MaterialRole)`입니다. 값이 아니라 역할이고, 다른 모든 프리미티브와 같은 규칙입니다. 역할은 `Thin`, `Regular`, `Thick`, `Chrome` 넷입니다.
- 해석은 디자인 시스템의 몫입니다. Cupertino와 Liquid Glass는 흐림이고, Material 3는 고도와 톤 얹기이며, GNOME은 불투명 표면입니다. 같은 선언이 시스템마다 자기 모습으로 나오는 것이 FR-14의 전부입니다.
- 14.1이 정한 것을 뒤집지 않습니다. 흉내 낸 반투명을 "Liquid Glass를 구현했다"고 말하지 않는다는 규칙은 그대로이고, 이것은 그 주장이 아니라 흐림을 말할 어휘입니다.
- 수용 기준: 같은 `Material(Regular)` 선언이 여섯 시스템에서 서로 다른 픽셀을 냅니다. 흐림을 지원하지 않는 환경에서 대비를 지키는 불투명 대체가 나옵니다(14.1의 3번 그대로).

### FR-24 모션 (`Draft`)

13.7은 "애니메이션 스펙"을 뺐고 그 이유는 옳았습니다. Host가 duration과 easing을 보내면 디자인 시스템이 모션을 결정한다는 FR-14가 무너집니다. 빠져 있던 것은 값이 아니라 **말할 방법**이었습니다. 색과 간격이 역할로 말해지듯 모션도 역할로 말합니다.

- `MotionRole`: `Instant`, `Quick`, `Standard`, `Slow`, `Emphasized` 다섯입니다.
- 쓰이는 자리는 상태가 바뀌는 곳입니다. 보임과 숨김, 선택 표시의 이동, 크기 변화입니다. `Modifier::Motion(role)`이 그 노드의 변화에 어떤 곡선과 길이를 쓸지 정합니다.
- 값은 전부 디자인 시스템 표에 있습니다. Material 3의 emphasized 곡선과 Cupertino의 스프링은 같은 역할에 대한 서로 다른 답입니다.
- 접근성: 시스템이 모션 감소를 요청하면 Renderer가 모든 역할을 `Instant`로 해석합니다. Host는 관여하지 않습니다(D5).
- 수용 기준: `Motion(Standard)`을 단 노드의 보임 전환이 여섯 시스템에서 서로 다른 길이로 끝납니다. 모션 감소가 켜진 상태에서는 여섯 모두 한 프레임 안에 끝납니다. 정상 상태 할당이 없습니다.

### FR-25 Grid (`Draft`)

지연 그리드입니다. FR-8이 한 축을 창으로 보는 규약을 정했고, 그리드는 그 규약을 행 단위로 씁니다. 두 번째 윈도잉 프로토콜을 만들지 않습니다.

- `LazyGrid`: 열 수를 고정으로 받거나(`columns`), 최소 폭으로 받습니다(`min_column_width`). 후자는 Renderer가 자기 폭으로 열 수를 정하므로 크기 클래스와 같은 종류의 판단이고, Host는 관여하지 않습니다.
- 창 프로토콜은 FR-8 그대로입니다. 요청 단위가 아이템이 아니라 **행**이라는 것만 다릅니다. 행 하나는 열 수만큼의 아이템이고, 마지막 행은 덜 찰 수 있습니다.
- 수용 기준: 아이템 1000개짜리 그리드가 보이는 행만 만들고, 스크롤이 창을 옮기며, 창 밖 아이템의 노드는 존재하지 않습니다. 창 크기가 바뀌어 열 수가 달라지면 행 경계가 다시 계산되고 아이템 순서는 유지됩니다. 크기 0 아이템으로 채운 그리드가 경계 호출을 되풀이하지 않습니다(FR-8과 같은 회귀).

### FR-26 서식 있는 텍스트 (`Draft`)

`Text`의 스팬 모델입니다. 위젯을 더하는 것이 아니라 `Text`가 구간마다 다른 역할을 가질 수 있게 하는 것입니다.

- `Text`에 선택적인 스팬 목록을 싣습니다. 스팬 하나는 `(시작, 길이, TypeRole?, Paint?, 굵게, 기울임, 밑줄, 취소선, 링크 핸들러 id?)`입니다. 고정 길이 레코드이고 FR-16의 바이트와 같은 방식으로 레코드 영역 뒤에 놓입니다.
- 마크다운은 **Host 쪽 편의 함수**입니다. Rust에서 마크다운을 스팬으로 바꿔 보내고, 경계에는 스팬만 건넙니다. 렌더러가 마크다운을 아는 일은 없습니다.
- 링크: 스팬이 핸들러 id를 들고 있으면 그 구간이 눌렸을 때 `Clicked`를 보냅니다. 새 이벤트 태그를 만들지 않습니다.
- **스팬은 배경 `Paint`도 싣습니다** (2026-10-03 추가, `Draft`). 구간 하나의 글자 뒤만 칠하는 것으로, diff 안에서 실제로 바뀐 단어를 강조하는 것(13.1-3의 `DiffAddedEmphasis`, `DiffRemovedEmphasis`)이 그 쓰임입니다. 줄 전체의 배경은 지금처럼 노드의 `Modifier::Background`이고, 이것은 줄 안의 일부입니다.
  - 스팬 레코드는 28바이트에서 **36바이트**가 됩니다. 글자색 `Paint` 뒤에 `background: Paint`(u64) 하나를 더하고, 0은 "배경 없음"입니다. 글자색과 배경을 한 `Paint` 자리에 플래그로 번갈아 싣는 방법은 쓰지 않습니다. 한 구간이 둘을 같이 가질 수 없게 되고, 바뀐 단어는 대개 글자색(문법 역할)과 배경(diff 강조)을 같이 가집니다.
  - 받는 값은 다른 `Paint` 자리와 같습니다. 역할이 먼저이고 리터럴은 탈출구입니다(13.1). 속성 태그는 그대로 `Spans`(64)이고, 레코드 길이가 바뀌므로 스키마 해시가 움직입니다. 레코드는 Rust 쪽(`spans.rs`)에 정의하고 Kotlin 디코더는 codegen이 생성합니다(FR-7).
  - 배경은 구간의 글자 상자를 칠하며, 줄이 바뀌는 구간은 줄마다 따로 칠합니다. 모양과 안쪽 여백은 두지 않습니다. 그것은 디자인 시스템이 정할 것이 생기면 그때 역할로 말할 일입니다.
- 수용 기준: 한 `Text` 안에서 굵은 구간과 링크 구간이 서로 다르게 그려지고, 링크 구간만 클릭을 보고합니다. 스팬 범위가 문자열 밖이거나 겹치면 프로세스를 중단하지 않고 `ProtocolError`를 보냅니다. 스팬이 없는 `Text`의 레코드 크기와 경로는 지금과 같습니다.
- 수용 기준(배경): 배경 `Paint::Role(DiffAddedEmphasis)`를 단 스팬 하나가 그 구간 뒤만 칠하고 나머지 글자 뒤는 칠하지 않습니다. 글자색과 배경을 같이 가진 스팬이 둘 다 그려집니다. 36바이트 스팬 레코드가 PR-4 프로토콜 벡터에 들어가 Rust와 Kotlin 양쪽에서 같은 바이트로 왕복합니다. 배경이 0인 스팬은 배경 없이 그려집니다.

### FR-27 파일 드래그 앤 드롭 (`Draft`)

창에 파일을 떨어뜨리는 것입니다. 포인터 드래그 자체는 FR-18이고 이것과 별개입니다.

- 이벤트 둘입니다. 파일이 노드 위에 들어왔다는 것과, 떨어졌다는 것입니다. 떨어진 이벤트는 경로 목록을 싣습니다.
- 경로는 문자열이므로 PR-4의 문자열 규약을 그대로 씁니다. 새 전달 수단을 만들지 않습니다.
- 플랫폼: 데스크톱 셋입니다. iOS와 Android에는 창에 파일을 떨어뜨리는 개념이 없으므로 이벤트가 발생하지 않고, Host 코드는 그대로 둡니다(FR-18의 호버와 같은 규칙).
- 수용 기준: 데스크톱에서 파일 두 개를 떨어뜨리면 경로 두 개가 한 이벤트로 도착합니다. 받을 준비가 없는 노드 위에서는 이벤트가 발생하지 않습니다. 경로에 UTF-8이 아닌 바이트가 있으면 그 파일을 버리고 나머지를 전달하며, 프로세스를 중단하지 않습니다.

### FR-28 노드 크기 관찰 (`Draft`)

FR-20은 창의 크기를 답합니다. 이것은 특정 노드의 크기를 답합니다. 컨테이너 질의(container query)에 해당하는 것으로, 창이 아니라 자기가 놓인 자리에 맞춰 모습을 바꾸는 컴포넌트가 이것을 씁니다.

- **구독한 노드만 비용을 냅니다.** `Modifier::ObserveSize`가 붙은 노드에만 측정 구독이 붙습니다. 아무도 구독하지 않은 트리는 지금과 한 바이트도 다르지 않아야 하고, 그것이 이 요구사항의 첫 번째 수용 기준입니다.
- 이벤트는 FR-20이 만든 것을 그대로 씁니다. `node_id`가 0이면 창이고, 아니면 그 노드입니다. 두 번째 전달 수단을 만들지 않습니다.
- 크기 클래스도 같은 규칙으로 계산해 실어 보냅니다. 노드에게도 "좁다"와 "넓다"는 창에서와 같은 뜻이고, 폭 클래스와 높이 클래스가 FR-20.3의 한 레코드에 같이 실립니다.
- `Modifier::ObserveSize { handler_id }`가 모디파이어 목록의 태그 16으로 들어갑니다. Host가 구독 id를 발급해 이 모디파이어에 싣고, Renderer는 그 노드가 클래스를 넘을 때 그 노드의 `node_id`와 그 id를 `handler_id`로 실어 보냅니다. 구독 하나가 곧 핸들러 하나이므로, 노드가 트리에서 사라질 때 그 노드의 핸들러가 사라지는 기존 규칙이 구독도 함께 없앱니다. 구독을 따로 지우는 절차는 없습니다.
- 보내는 시점의 규칙은 FR-20.4와 같습니다. 노드마다 마지막으로 보낸 폭 클래스와 높이 클래스를 하나씩 들고 비교하며, 시작값은 창과 같이 `Compact`입니다. 그래서 `Compact`로 태어난 노드는 첫 측정에서 아무것도 보내지 않습니다.
- 구독하지 않은 노드는 모디파이어 목록에 이 항목이 없으므로, 측정 콜백도 마지막 클래스를 담을 자리도 생기지 않습니다.
- 수용 기준: 구독이 하나도 없는 트리의 프레임당 경계 호출 수가 FR-20만 있을 때와 같습니다. 구독한 노드의 폭이 클래스 경계를 넘으면 이벤트가 한 번 오고, 같은 클래스 안에서 바뀌는 동안에는 오지 않습니다. 노드가 트리에서 사라지면 구독도 사라집니다.

### FR-29 창 뒤를 비추는 재질 (`Draft`)

14.1-2가 "우리가 그릴 수 없는 것"으로 분류했던 것입니다. macOS에서 창 뒤의 시스템 배경을 비추는 재질이고, `NSVisualEffectView`가 그립니다.

- 우리가 `NSApplication`을 직접 만들므로(INTENT D9-macOS) 그 뷰를 창의 콘텐츠 뷰 아래에 둘 수 있습니다. Compose 표면은 그 위에 투명하게 올라갑니다.
- 켜지는 조건은 FR-23.3의 `Material(Chrome)`이 창의 배경에 쓰였을 때입니다. 새 속성을 만들지 않습니다.
- Windows와 Linux는 각자의 답이 있을 때까지 FR-23.3의 흐림으로 남습니다. 창 뒤를 비추지 못하는 것과 흐리지 못하는 것은 다른 이야기이고, 뒤쪽은 이미 할 수 있습니다.
- 14.1의 규칙은 그대로입니다. 이것이 켜졌을 때에만 "창 뒤를 비춘다"고 말할 수 있고, 켜지지 않은 환경에서 그렇게 주장하지 않습니다.
- 수용 기준: macOS에서 이 재질을 쓴 창이 뒤에 있는 창을 실제로 비춥니다(스크린샷을 데스크톱 배경만 바꿔 두 번 찍어 픽셀이 달라지는 것으로 확인합니다). 재질을 쓰지 않은 창은 지금과 같은 경로로 뜨고 `NSVisualEffectView`가 만들어지지 않습니다. 이 기능이 없는 플랫폼에서 같은 선언이 흐림으로 그려집니다.

### FR-30 플랫폼 고유 동작 표면과 제목 배치 (`Agreed`)

적응형 애플리케이션은 디자인 시스템의 이름을 묻지 않고 자기가 놓는 것이 무엇인지 말합니다.
렌더러의 `ComponentRules`가 그 뜻을 현재 디자인 시스템의 모양과 배치로 바꿉니다.

#### 30.1 조밀한 동작 키

계산기처럼 조밀한 격자 안에서 반복되는 버튼은 보통 버튼과 별개의 **동작 키**입니다. 같은
격자 선언을 macOS는 원으로, Windows 11은 작은 반경의 서로 붙은 직사각형으로, Deepin은
더 큰 반경과 간격을 가진 직사각형으로 그립니다. `ShapeRole::Full`은 Windows에서도 캡슐을
만드므로 이 차이를 말할 수 없고, 애플리케이션이 디자인 시스템을 검사해 모서리를 고르면
FR-14의 계층을 거꾸로 세우게 됩니다.

- `Button`은 선택적인 `kind`를 받고 값은 `ButtonKind::Standard`와 `ButtonKind::ActionKey`입니다.
  기본값은 `Standard`이며 기존 버튼의 모양을 바꾸지 않습니다. `ActionKey`는 색이나 중요도를
  말하지 않고, 조밀한 동작 격자에 놓인 키라는 정체만 말합니다. 채움과 강조는 기존
  `ButtonVariant`가 계속 답합니다.
- 와이어 열거형 태그는 `Standard=1, ActionKey=2`이고, 속성은 18.1.1이 제스처로 예약한
  블록(67에서 74) 뒤의 추가 전용 태그 `ButtonKind=75`를 씁니다. 기존 태그는 움직이지
  않습니다.
- `ComponentRules`의 일곱 구현이 모두 동작 키의 모양을 직접 답합니다. Host는 반경이나
  `ShapeRole`을 보내지 않습니다. Cupertino와 Liquid Glass는 원, Fluent 2는 Windows 11의
  작은 반경, Deepin은 그보다 큰 반경을 답하며 Material 3 Expressive, GNOME 50, Breeze도
  각자의 참조 어휘로 답합니다.

수용 기준:

1. 같은 크기의 `ButtonKind::ActionKey` 선언을 Cupertino와 Fluent 2로 그리면 Cupertino의
   모서리 반경은 짧은 변의 절반이고 Fluent 2의 반경은 그보다 작습니다.
2. 같은 선언을 Fluent 2와 Deepin으로 그리면 Deepin의 모서리 반경이 더 큽니다.
3. 일곱 디자인 시스템 모두 `ActionKey`에 명시적인 답을 가지며, `Standard` 버튼의 기존
   모양은 그대로입니다.
4. 계산기 키패드는 디자인 시스템 이름이나 모서리 값을 검사하지 않고 모든 키를
   `ActionKey`로 선언합니다.

#### 30.2 창 제목과 막대 제목

창 캡션을 플랫폼이 이미 그리는 디자인 언어에서는 같은 제목을 `TopAppBar`가 다시 그리지
않습니다. 제목의 문자열은 애플리케이션이 계속 한 번 선언하지만, 그 문자열을 막대에 놓을지
시스템 캡션에 맡길지는 `CaptionStyle`의 `titlePlacement`가 답합니다. 값은 `Bar`와
`SystemCaption`입니다.

- Cupertino와 Liquid Glass는 캡션 높이가 있는 창에서 `SystemCaption`을 답하고 막대 안에는
  제목을 그리지 않습니다. 캡션이 없는 모바일 화면에서는 막대가 제목을 그립니다.
- 나머지 다섯 시스템은 `Bar`를 답하고, 기존 `titleAlignment`에 따라 앞쪽 또는 가운데에
  제목을 놓습니다.
- 자식 콘텐츠와 막대의 캡션 띠 점유는 바뀌지 않습니다. 달라지는 것은 제목 한 줄의 배치뿐입니다.

수용 기준:

1. 캡션 높이가 같은 `TopAppBar(title)`를 Cupertino와 Fluent 2로 그리면 Cupertino 막대에는
   제목 노드가 없고 Fluent 2 막대에는 하나 있습니다.
2. 캡션 높이가 0인 Cupertino 화면에서는 제목이 막대에 나타납니다.
3. GNOME과 Material 3의 기존 가운데 및 앞쪽 제목 정렬 측정이 그대로 통과합니다.

### FR-31 Scaffold (`Draft`)

화면의 뼈대를 앱이 조립하지 않고 이름으로 맡깁니다. Material 3의 `Scaffold`와 같은 모양입니다. 앱은 슬롯을 채우고, 그 슬롯이 이 플랫폼에서 무엇이 되는지와 이 창 너비에서 어떻게 접히는지는 Renderer가 정합니다.

지금은 그 뼈대가 없어서 샘플이 자기 손으로 짭니다. 계산기는 어느 플랫폼에서든 Windows 11 계산기의 구조(햄버거, 제목, 히스토리 버튼, 메모리 줄)를 그리고, macOS 참조에는 그중 아무것도 없습니다. 디자인 시스템은 색과 모양만 바꾸므로 여기에 닿지 못합니다. FR-21.2가 `Navigation` 하나를 막대·레일·서랍으로 갈라 놓은 것과 같은 일을 창 크롬 전체에 대해 하는 것이 이 항목입니다.

#### 31.1 모양

```rust
rsx! {
    Scaffold {
        top_bar: rsx! { TopAppBar { title: "Standard" } },
        bottom_bar: rsx! { Navigation { /* ... */ } },
        floating_action: rsx! { Button { /* ... */ } },
        // 남은 자식이 본문입니다.
        Column { /* ... */ }
    }
}
```

- **슬롯은 `Element` 속성입니다.** `Scaffold`는 Rust 컴포넌트이고, 각 슬롯을 슬롯 이름이 붙은 자식으로 펼쳐 내보냅니다. 앱이 쓰는 문장은 Material의 것과 같고, 와이어에는 어느 자식이 어느 슬롯인지가 명시됩니다. 자식의 순서로 추측하지 않습니다.
- **본문은 인셋을 받습니다.** Material이 `content`에 `PaddingValues`를 넘기는 자리이고, 여기서는 Renderer가 창 캡션, 시스템 바, 그리고 바가 차지한 자리를 합쳐 본문에 적용합니다. 앱은 그 숫자를 보지 않습니다.
- **비어 있는 슬롯은 없는 것입니다.** 바를 주지 않은 화면은 바가 없는 화면이며, 빈 띠가 남지 않습니다.

#### 31.2 Renderer가 정하는 것

같은 선언에서 갈라지는 것들입니다. 이 목록이 곧 Scaffold가 존재하는 이유입니다.

- `top_bar`가 창의 캡션이 되는지, 시스템 타이틀바 아래의 앱 바가 되는지, 아니면 macOS처럼 신호등과 한 줄을 이루는지(FR-19.2).
- 제목이 보이는지, 어디에 정렬되는지, 큰 제목으로 확대되는지.
- `bottom_bar`가 하단 막대인지 레일인지 서랍인지(FR-21.2가 이미 정한 규칙을 그대로 씁니다).
- `floating_action`이 떠 있는 버튼인지, 툴바 안의 동작인지, 메뉴로 접히는지.
- 크기 클래스마다 이 셋이 어떻게 바뀌는지(FR-20).

#### 31.3 수용 기준

1. 슬롯만 채운 같은 `rsx!` 트리가 Material 3, Liquid Glass, Fluent에서 각 플랫폼의 관습대로 다른 뼈대로 그려집니다.
2. 같은 트리가 Compact, Medium, Expanded에서 FR-21.2가 정한 대로 다른 뼈대로 그려집니다.
3. 본문이 받는 인셋에 캡션과 시스템 바와 바의 높이가 모두 반영되어, 어느 조합에서도 본문이 가려지지 않습니다.
4. 슬롯을 비워 둔 화면에 빈 띠가 남지 않습니다.
5. 샘플 네 개(`calculator`, `todo`, `notepad`, `chat`)가 자기 손으로 짜던 뼈대를 버리고 이것을 씁니다.

**아직 답하지 않은 것이 하나 있습니다.** 계산기의 참조 셋은 뼈대만이 아니라 키 구성도 다릅니다(Apple은 `AC`와 메모리 줄 없음, Windows는 `C`와 메모리 줄). Scaffold는 뼈대까지 답하고 본문의 내용에는 답하지 않으므로, 그 차이를 앱이 디자인 시스템을 읽어 갈라 쓸 수 있게 할지는 별개의 결정으로 남습니다.

### FR-32 Host가 해석된 디자인 시스템을 읽습니다 (`Draft`)

`Theme::adaptive`는 어느 디자인 시스템이 쓰일지를 Renderer가 정하게 합니다(FR-14.3). 그 답을 Host는 알 수 없습니다. 색과 모양은 역할이 해결하므로 대부분의 화면은 알 필요가 없지만, **구조가 다른 경우가 있습니다.**

계산기가 그 경우입니다. 참조 셋이 스타일이 아니라 내용에서 갈립니다. Apple 계산기에는 메모리 줄이 없고 지우기 키가 `AC`이며 첫 줄이 `⌫ AC % ÷`입니다. Windows 계산기에는 `MC MR M+ M− MS M▾` 줄이 있고 지우기 키가 `C`이며 첫 줄이 `C ⌫ % ÷`입니다. 역할로는 여기까지 갈 수 없습니다. 2026-09-24에 창을 참조 비율(380×620)로 좁히자 메모리 줄이 두 줄로 깨졌는데, 그 줄은 Apple 화면에 있어서는 안 되는 줄입니다.

**`WindowSizeChanged`와 같은 모양으로 전달합니다.** Renderer가 테마를 해석한 뒤 한 번, 그리고 해석 결과가 바뀔 때만 보냅니다. 프레임마다 보내지 않습니다.

```rust
let design = use_design_system();
if design.is_apple() {
    // AC, 메모리 줄 없음
} else {
    // C, 메모리 줄
}
```

- 값은 해석된 `DesignSystem` 하나입니다. 명암은 이미 역할이 해결하므로 싣지 않습니다.
- **이것은 앱이 디자인 시스템을 고르는 경로가 아닙니다.** 고르는 것은 `Theme::unified`이고, 이것은 정해진 답을 읽는 것입니다. 읽고 나서 색을 직접 칠하는 것은 FR-14가 이미 금지합니다.
- **쓸 자리는 좁습니다.** 벤더가 같은 일을 서로 다른 화면으로 만들어 두었고 역할로 옮길 수 없을 때만입니다. 색, 모양, 간격, 타입은 전부 역할이 답하므로 이 문을 쓸 이유가 없습니다.

#### 32.1 수용 기준

1. `Theme::adaptive`로 실행한 화면에서 `use_design_system()`이 Renderer가 실제로 고른 시스템을 돌려줍니다.
2. `Theme::unified(X)`로 실행하면 `X`를 돌려줍니다.
3. 값이 바뀌지 않는 동안 이벤트가 추가로 오지 않습니다.
4. 계산기가 Apple 계열에서 메모리 줄 없이 `AC`로, Fluent에서 메모리 줄과 `C`로 그려집니다.

### FR-33 텍스트 선택 (`Draft`)

읽는 글을 끌어서 고르고 복사하는 것입니다. 입력란 안의 선택은 FR-5의 `TextField`가 이미 합니다. 이것은 입력란이 아닌 `Text`, 특히 여러 개의 `Text`로 이루어진 한 덩어리의 글(대화 메시지 하나: 문단, 목록, 코드 블록)을 한 번에 고르는 일입니다.

- **선택 영역은 컨테이너 하나입니다.** `SelectionContainer` 안에 있는 `Text`들은 하나의 선택 영역이 되어, 끌면 여러 `Text`에 걸쳐 고를 수 있습니다. 밖에 있는 `Text`는 고를 수 없습니다. 기본값이 "고를 수 없음"인 이유는, 버튼 라벨과 탐색 항목까지 끌어서 골라지는 화면은 앱이 아니라 문서처럼 보이기 때문입니다. Compose의 `SelectionContainer`와 같은 이름, 같은 뜻입니다.
- **선택 상태는 Renderer가 소유합니다(D5).** 스크롤 위치, 포커스와 같은 부류입니다. 고르는 동안 Host로 이벤트를 보내지 않고, Host가 선택 범위를 읽거나 정하는 경로도 두지 않습니다.
- **복사는 Renderer가 플랫폼 클립보드에 직접 합니다.** 단축키(macOS의 Command C, 다른 플랫폼의 Control C)와 우클릭 메뉴(시스템 메뉴)로 합니다. 복사한 글자가 경계를 넘지 않습니다.
- 여러 `Text`에 걸친 선택을 복사하면, 블록 사이에 줄바꿈을 넣어 이어 붙입니다. 가로 스크롤(`ScrollRow`) 안의 코드 블록도 선택 영역에 들어갑니다.
- 선택 영역 안에 있는 버튼, 체크박스 같은 컨트롤은 선택되지 않고 지금처럼 눌립니다.
- 모바일은 길게 눌러 고르고 시스템의 복사 메뉴를 씁니다. 데스크톱과 같은 위젯, 같은 선언입니다.
- 위젯 태그 41. 속성은 없습니다. 위젯은 Rust 스키마(`schema.rs`)에 정의하고 Kotlin 쪽은 codegen이 생성합니다(FR-7).

수용 기준:
1. `SelectionContainer` 안의 `Text` 세 개(문단, 목록 항목, `ScrollRow` 안의 코드)를 한 번 끌어서 고르고 복사하면, 클립보드에 세 글이 줄바꿈으로 이어져 들어갑니다.
2. 밖에 있는 `Text`는 끌어도 골라지지 않습니다.
3. 고르는 동안과 복사할 때 Host로 가는 이벤트가 없습니다.
4. 선택 영역 안의 `Text` 하나의 내용이 바뀌는 재구성(스트리밍으로 마지막 메시지가 길어지는 경우)에서, 바뀌지 않은 다른 `Text`의 선택은 유지됩니다.
5. 선택 영역 안의 버튼은 눌리고, 선택에 들어가지 않습니다.
6. 데스크톱 셋에서 단축키와 우클릭 메뉴 양쪽으로 복사됩니다. 모바일의 길게 누르기는 수동 확인입니다(§6과 같은 방식으로 기록).

### FR-36 OS 알림 (`Draft`)

창 밖에서 사용자를 부르는 것입니다. 요청한 앱(Ember)에서는 "세션이 끝났습니다"와 "승인을 기다리고 있습니다" 둘이고, 둘 다 사용자가 다른 창에 가 있을 때 의미가 있습니다. 그 플랫폼의 알림으로 나와야 합니다. macOS 알림 센터(`UNUserNotificationCenter`), Windows 토스트(앱 알림), Linux 데스크톱 알림(`org.freedesktop.Notifications`), iOS(`UNUserNotificationCenter`), Android(`NotificationManager`), 그리고 브라우저(Web Notifications API)입니다.

**FR-21.4의 일시 메시지와 다른 것입니다.** 일시 메시지는 창 안에서, 사용자가 방금 한 조작에 대해 몇 초 동안 말합니다. 알림은 창 밖에서, 사용자가 하지 않은 일(작업이 끝남, 무엇이 기다림)에 대해 사용자가 치울 때까지 남습니다. 수명도 주인도 다르므로 하나로 합치지 않습니다.

#### 36.1 누가 보내는가

- **Host가 명령 레코드로 보냅니다. 위젯이 아닙니다.** 알림은 트리의 어디에도 자리가 없고, 그것을 낸 컴포넌트가 사라진 뒤에도 남습니다. 노드로 만들면 Host가 "알림이 떠 있음"이라는 노드를 들고 있어야 하는데, 그 노드가 언제 사라져야 하는지는 사용자가 알림 센터에서 무엇을 했는지에 달려 있고 Host는 그것을 모릅니다. 21.4가 일시 메시지를 노드로 만들지 않은 이유와 같습니다.
- **Host는 플랫폼 API를 부르지 않습니다. 알림을 띄우는 것은 Renderer입니다.** 플랫폼은 Renderer가 소유합니다(D4, D5). Host가 `UNUserNotificationCenter`나 D-Bus를 직접 부르면, 플랫폼마다 Rust 쪽 바인딩이 생기고(손으로 쓴 FFI, C3에 걸립니다), 알림을 누른 사건이 Renderer를 거치지 않고 다른 스레드에서 Host로 들어오는 두 번째 입구가 생깁니다(PR-1, PR-3). 권한 요청 대화상자도 창을 가진 쪽이 띄워야 합니다.
- **워커에서도 보낼 수 있습니다.** 세션이 끝났다는 것은 워커가 먼저 아는 사실입니다(PR-3). Host API는 `Send`인 손잡이(`NotificationSender`)를 주고, 그 손잡이로 보낸 알림은 Host 안의 대기열에 들어간 뒤 Host가 내부에서 프레임을 요청해 다음 `render_frame` 배치에 실립니다. 사용자 코드는 경계 함수를 부르지 않으며(PR-3), 이 대기열은 Host 안의 것이지 경계 위의 큐가 아닙니다(PR-1). UI 스레드의 컴포넌트와 이벤트 핸들러에서는 `Message`처럼 바로 보냅니다.

```rust
Notification::new("세션이 끝났습니다")
    .body("refactor-parser: 테스트 214개 통과")
    .key(format!("session/{id}"))
    .channel("세션")
    .presentation(Presentation::WhenInactive)
    .post();
```

#### 36.2 알림 하나가 싣는 것

- `title`, `body`(문자열). 앱의 이름과 아이콘은 싣지 않습니다. 플랫폼이 앱의 번들 정보에서 붙이고, 그것은 창의 아이콘과 같은 출처입니다(FR-19.3, FR-22.6).
- **`key`(문자열)는 앱이 정하는 이름입니다.** 같은 `key`로 다시 보내면 앞의 알림을 바꿉니다. 세션 하나의 상태가 바뀔 때마다 알림이 쌓이지 않고 하나가 갱신됩니다. 누른 사건도 이 `key`로 돌아옵니다(36.3). 빈 `key`는 Renderer가 알림마다 새 이름을 붙이는 것이고, 그 알림은 바꾸거나 거둘 수 없습니다.
- **`WithdrawNotification(key)`로 거둡니다.** 승인 요청을 사용자가 창 안에서 이미 처리했다면 알림 센터에 남은 "승인을 기다리고 있습니다"는 거짓말이 됩니다. 거두는 길이 없으면 앱은 그 거짓말을 지울 수 없습니다.
- **동작 버튼은 둘까지입니다**(`action_1`, `action_2`, 라벨 문자열, 비우면 없음). 플랫폼 다섯이 모두 알림의 동작 버튼을 지원하지만 그 수가 다르고(Windows는 다섯, Android는 셋, macOS는 펼쳐야 보임), 둘이면 어디서나 같은 모양으로 들어갑니다. 버튼을 누른 사건은 그 번호를 싣고 돌아옵니다.
- **`importance`는 `Normal=1`, `Urgent=2`인 닫힌 열거형입니다.** 실제 효과(소리, Android 채널의 중요도, 방해 금지 중 표시 여부)는 Renderer가 플랫폼별로 정합니다. `MessageDuration`(21.4)과 같은 이유로, Host가 소리 파일이나 우선순위 숫자를 보내는 길을 두지 않습니다. 요청한 앱에서는 "승인 대기"가 `Urgent`, "세션 끝남"이 `Normal`입니다.
- **`channel`(문자열)은 사용자가 알림을 종류별로 끌 수 있게 하는 묶음입니다.** Android 8 이상은 알림마다 채널이 필수이고, 채널의 이름은 사용자가 설정에서 보는 글자이므로 앱의 언어로 앱이 정해야 합니다. Renderer는 처음 보는 채널 이름에 대해 채널을 만듭니다. 비우면 앱 이름의 기본 채널입니다. 채널 개념이 없는 플랫폼은 이 값을 무시합니다(Linux에는 `category` 힌트로 넘깁니다).
- **`presentation`은 `Always=1`, `WhenInactive=2`입니다.** `WhenInactive`는 앱의 창이 활성(포커스를 가진 상태)이면 Renderer가 알림을 띄우지 않고 버리는 것입니다. 창이 활성인지는 Renderer의 상태이고(D5), Host가 이것을 판단하려면 포커스 이벤트를 매번 받아야 합니다. "사용자가 보고 있으면 부르지 않는다"는 판단을 그 상태를 가진 쪽에 둡니다.

#### 36.3 사용자가 누르면

- **누른 사건은 Host 앞으로 가는 이벤트입니다.** 새 이벤트 `NotificationActivated`(이벤트 태그 24)이고, `node_id = 0`, `handler_id = 0`이며, 페이로드는 `key`(문자열)와 `action: u32`(본문을 눌렀으면 0, 동작 버튼이면 1 또는 2)입니다. `DesignSystemResolved`(21)처럼 노드가 아니라 Host 자신에게 오는 사건이라 핸들러 조회를 거치지 않습니다.
- **핸들러 id가 아니라 `key`인 이유**: 알림은 몇 시간, 몇 날 동안 알림 센터에 남습니다. 그 사이 알림을 낸 컴포넌트는 사라지고, 콜백은 그 스코프와 함께 사라집니다. 21.4의 일시 메시지는 줄이 유한해서(8개) 콜백을 16개까지만 들고 있으면 되었지만, 알림에는 그런 상한이 없습니다. 그래서 알림이 들고 다니는 것은 살아 있는 콜백이 아니라 앱이 언제든 해석할 수 있는 이름입니다.
- Host API는 앱 단위의 훅 하나입니다. `use_notification_activated(move |activation| ...)`가 `key`와 `action`을 받고, 앱은 `session/{id}`를 보고 그 세션을 엽니다. 훅을 부른 컴포넌트가 없으면 사건은 버려집니다.
- **본문을 누르면 Renderer가 앱의 창을 앞으로 가져옵니다.** 창을 앞으로 가져오는 것은 플랫폼의 일이고, 그다음 무엇을 보일지는 Host의 일입니다. 동작 버튼을 누르면 창을 가져오지 않습니다. 동작 버튼이 있는 이유가 창을 열지 않고 처리하는 것이기 때문입니다.
- **알림을 눌러 앱이 새로 시작되는 경우**(iOS와 Android에서 프로세스가 없을 때): Renderer는 그 사건을 들고 있다가 `compose_rust_host_init`이 끝난 뒤 첫 이벤트로 보냅니다. Host가 존재하기 전에 Host로 갈 수는 없으므로, 새 진입점이 아니라 순서로 해결합니다.
- **데스크톱에서 프로세스가 끝나면 Renderer는 자기가 띄운 알림을 거둡니다.** macOS 알림 센터에 남은 알림을 누르면 앱이 다시 시작되는데, 그 알림이 가리키는 세션은 끝난 프로세스의 것이고 새 프로세스는 그것을 모릅니다. 사용자가 앱을 닫은 뒤에 남는 알림은 대부분 누를 수 없는 약속입니다.

#### 36.4 권한

- **권한은 처음 보낼 때 묻습니다.** 앱을 처음 실행하자마자 묻는 것은 사용자가 아직 무엇에 대한 알림인지 모를 때 묻는 것이고, 거절당하기 가장 쉬운 시점입니다. Apple의 지침도 맥락 안에서 물으라고 합니다. 권한 상태가 "아직 묻지 않음"일 때 첫 `PostNotification`이 오면 Renderer가 플랫폼의 권한 요청을 띄우고, 허락되면 그 알림을 띄웁니다.
- **앱이 먼저 물을 수도 있습니다.** 명령 `RequestNotificationPermission`(본문 없음)입니다. 설정 화면에 "세션이 끝나면 알림 받기" 스위치를 두고, 사용자가 켤 때 묻는 경우입니다. 브라우저는 사용자 조작 안에서만 권한을 물을 수 있게 하는데, 이 명령은 그 조작을 처리한 `dispatch_event`가 돌려준 배치에 실려 같은 호출 스택 안에서 적용되므로(PR-1) 그 조건을 그대로 만족합니다. 비동기 경계였다면 이 성질이 없었습니다.
- **권한 상태는 Host가 읽을 수 있습니다.** 이벤트 `NotificationPermissionChanged`(이벤트 태그 25), 페이로드 `state: u32`이고 값은 `NotDetermined=1`, `Granted=2`, `Denied=3`, `Unsupported=4`인 닫힌 열거형입니다. 모르는 값은 `ProtocolError`입니다. Renderer는 시작 후 한 번, 그리고 값이 바뀔 때만 보냅니다(사용자가 OS 설정에서 바꾼 것을 창이 다시 활성이 될 때 확인합니다). `WindowSizeChanged`(20.4)와 같은 규칙이고, 양쪽의 시작값은 `NotDetermined`입니다. Host API는 `use_notification_permission()`입니다.
- 거절된 상태에서 온 `PostNotification`은 Renderer가 버립니다. 오류가 아니므로 `ProtocolError`가 아닙니다. 다시 묻지도 않습니다. 거절한 사용자를 다시 조르는 앱은 그 플랫폼이 금지하거나(iOS는 두 번째 요청을 아예 띄우지 않습니다) 사용자가 싫어하는 앱입니다.
- **Renderer가 알림 대신 일시 메시지를 띄우지 않습니다.** 알림이 필요한 순간은 사용자가 창을 보지 않는 순간이고, 그때 창 안에 뜬 메시지는 아무도 보지 않습니다. 대신 무엇을 보여 줄지는 권한 상태를 읽은 앱이 정합니다.

#### 36.5 플랫폼별 조건과 지원되지 않는 경우

| 플랫폼 | 경로 | 띄우기 위한 조건 |
|---|---|---|
| macOS | `UNUserNotificationCenter` | 앱 번들과 번들 식별자. 서명된 배포 번들(FR-22.6)에서 동작합니다 |
| Windows | 앱 알림(토스트) | AppUserModelID와 그것을 단 시작 메뉴 바로 가기. Renderer가 시작할 때 프로세스의 AppUserModelID를 정하고, 배포 번들이 바로 가기를 만듭니다 |
| Linux | `org.freedesktop.Notifications`(D-Bus) | 그 이름을 가진 알림 데몬이 세션 버스에 있을 것. 동작 버튼은 데몬이 `actions` 기능을 알릴 때만 |
| iOS | `UNUserNotificationCenter` | 권한 |
| Android | `NotificationManager` | Android 13 이상은 `POST_NOTIFICATIONS` 런타임 권한, 8 이상은 채널. 매니페스트의 권한 선언은 앱 빌드가 넣습니다(PR-5.1) |
| Web | Notifications API | 보안 컨텍스트(HTTPS)와 권한. 페이지가 열려 있는 동안만 |

- **조건을 채우지 못하는 환경에서는 권한 상태가 `Unsupported`입니다.** 번들이 없는 JVM 개발 셸의 macOS, AppUserModelID가 없는 Windows 실행, 알림 데몬이 없는 Linux 세션이 여기 해당합니다. 그때 `PostNotification`과 `WithdrawNotification`은 아무 일도 하지 않고, 프로세스가 죽지 않으며, `ProtocolError`도 아닙니다. 플랫폼에 그 기능이 없는 것은 프로토콜 오류가 아니기 때문입니다. FR-27이 모바일의 파일 드롭에 대해, FR-19.3이 창이 이미 있는 플랫폼의 창 설정에 대해 정한 것과 같은 규칙입니다. Host 코드는 플랫폼마다 갈라지지 않습니다.
- 개발 셸에서 `Unsupported`가 되는 것은 숨기지 않습니다. 디버그 빌드는 첫 `PostNotification`에서 그 이유("이 실행에는 번들 식별자가 없어 알림 센터가 알림을 받지 않습니다")를 한 번 로그로 남깁니다.
- **창이 그려지지 않는 동안에도 알림은 나가야 합니다.** 창이 최소화되어 있거나 가려져 있거나 앱이 백그라운드에 있으면 Compose의 프레임 클록이 멈출 수 있고, 그대로 두면 알림이 가장 필요한 순간(사용자가 창을 보지 않을 때)에 배치가 나가지 않습니다. 그래서 PR-3(2026-10-03 개정)에 따라 Renderer는 프레임 클록이 멈춘 동안 온 `request_frame`에 대해 UI 스레드에서 `render_frame`을 직접 한 번 부릅니다. 경계 함수는 그대로이고 바뀌는 것은 Renderer의 예약 방법뿐입니다. Android에서 멈춘(`onStop`) 동안 Host가 프레임 요청을 미뤄 두는 규칙(PR-5)도 알림 대기열이 비어 있지 않을 때는 미루지 않습니다.
- **이 항목은 실행 중인 프로세스가 띄우는 지역 알림입니다.** iOS가 백그라운드 앱을 일시 정지시키거나 Android가 프로세스를 정리하면 Host가 돌지 않으므로 알림을 낼 쪽이 없습니다. 그 경우를 덮는 것은 서버가 보내는 원격 푸시(APNs, FCM)이고, 서버와 기기 토큰이 필요한 다른 기능입니다(36.8).

#### 36.6 와이어

경계 진입점은 하나도 늘지 않습니다. 보내는 것은 기존 배치의 레코드이고, 돌아오는 것은 기존 `dispatch_event`의 이벤트입니다. PR-2의 변경이 아니라 FR-7의 스키마 변경이고, 스키마 해시가 움직입니다. 명령과 이벤트, 세 열거형은 Rust 스키마(`schema.rs`)에 정의하고 Kotlin 쪽 코덱은 codegen이 생성합니다.

명령(Mutation) 레코드, `SetWindow=13` 뒤에 덧붙입니다:

| 태그 | 명령 | 레코드 |
|---|---|---|
| 14 | `PostNotification` | 56바이트: 머리 4, `key`, `title`, `body`, `channel`, `action_1`, `action_2`(각각 `(offset: u32, len: u32)`, 48), `importance: u16`, `presentation: u16` |
| 15 | `WithdrawNotification` | 12바이트: 머리 4, `key`(8) |
| 16 | `RequestNotificationPermission` | 4바이트: 머리만 |

이벤트 레코드, `FilesDropped=23` 뒤에 덧붙입니다:

| 태그 | 이벤트 | 페이로드 |
|---|---|---|
| 24 | `NotificationActivated` | 공통 머리 16바이트(`node_id = 0`, `handler_id = 0`) 뒤에 `action: u32`, `key: (offset, len)` |
| 25 | `NotificationPermissionChanged` | 공통 머리 뒤에 `state: u32` |

- 문자열은 전부 PR-4 규약입니다. 새 전달 수단이 없습니다.
- `importance`, `presentation`, `state`는 닫힌 열거형이고 스키마 해시에 들어갑니다. 모르는 값은 `ProtocolError`입니다.
- 두 이벤트의 페이로드(`u32` 하나와 문자열 하나, 그리고 `u32` 하나)는 지금 `EventPayloadType`에 없는 모양이라 둘을 더합니다.
- Renderer 쪽은 플랫폼별 알림 어댑터 하나씩입니다. 공유 인터프리터는 명령을 해석해 어댑터에 넘기고, 어댑터가 없는 플랫폼은 `Unsupported`를 답하는 기본 구현을 씁니다. 손으로 쓴 경계 접착 코드는 없습니다. 어댑터가 부르는 것은 Renderer가 이미 소유한 플랫폼 API이고(macOS와 iOS는 Kotlin/Native의 플랫폼 라이브러리, Windows와 Linux는 데스크톱 렌더러가 이미 쓰는 경로), Host와 Renderer 사이의 경계가 아닙니다.

#### 36.7 수용 기준

1. macOS, Windows, Linux(GNOME) 배포 빌드에서 `PostNotification`이 그 플랫폼의 알림으로 나타나고, 제목과 본문이 보낸 글자와 같습니다. 수동 확인이며 native-image 빌드에서 기록합니다.
2. 본문을 누르면 `NotificationActivated`가 보낸 `key`와 `action = 0`을 싣고 **정확히 한 번** 오고, 앱의 창이 앞으로 옵니다. 동작 버튼을 누르면 그 번호가 오고 창은 앞으로 오지 않습니다.
3. 같은 `key`로 두 번 보내면 알림 센터에 하나만 남고 두 번째 내용이 보입니다. `WithdrawNotification`을 보내면 사라집니다.
4. 권한이 `NotDetermined`일 때 첫 `PostNotification`이 플랫폼의 권한 요청을 띄우고, 허락하면 그 알림이 나타나며 `NotificationPermissionChanged(Granted)`가 한 번 옵니다. 거절하면 알림이 나타나지 않고 `Denied`가 한 번 오며, 그 뒤의 `PostNotification`은 권한을 다시 묻지 않습니다.
5. 창이 활성인 동안 `WhenInactive` 알림은 나타나지 않고 `Always` 알림은 나타납니다.
6. 워커 스레드에서 `NotificationSender`로 보낸 알림이, 창이 최소화된 상태에서 1초 안에 나타납니다. 그동안 사용자 코드가 경계 함수를 부르지 않습니다.
7. 번들 식별자가 없는 실행(JVM 개발 셸)에서 권한 상태가 `Unsupported`이고, `PostNotification`이 프로세스를 죽이지 않으며 `ProtocolError`를 내지 않습니다.
8. iOS와 Android에서 프로세스가 없을 때 알림을 눌러 앱이 시작되면, `NotificationActivated`가 `compose_rust_host_init` 다음의 첫 이벤트로 옵니다. 수동 확인입니다.
9. 데스크톱 앱을 끝내면 그 앱이 띄운 알림이 알림 센터에서 사라집니다.
10. 세 명령과 두 이벤트가 PR-4 프로토콜 벡터에 들어가 양쪽에서 같은 바이트로 왕복합니다. 모르는 `importance`와 `state` 값이 `ProtocolError`가 되고 프로세스가 죽지 않습니다.
11. 경계 진입점 목록(PR-2)이 이 변경 전과 같습니다.
12. Android 13 이상에서 `POST_NOTIFICATIONS` 권한 요청이 나타나고, 서로 다른 `channel` 두 개가 설정 화면에 두 채널로 보입니다. 수동 확인입니다.

#### 36.8 열린 질문

1. **원격 푸시.** 백그라운드에서 정지된 iOS 앱과 정리된 Android 프로세스는 지역 알림을 낼 수 없습니다. 요청한 앱의 세션이 원격 기계에서 돈다면, 폰에서 "세션이 끝났습니다"를 받으려면 APNs/FCM 원격 푸시가 필요합니다. 그것은 서버, 기기 토큰 등록, 그리고 토큰을 Host에 알리는 이벤트가 필요한 별도 요구사항입니다. 요청한 앱이 폰에서 이것을 필요로 하는지 확인이 필요하고, 필요하다면 별도 SPEC 항목으로 씁니다.
2. **알림에서 바로 승인하기.** 동작 버튼 둘이 있으면 앱은 "승인/거절"을 알림 위에 둘 수 있습니다. 사용자가 요청 내용을 보지 않고 승인하게 되는 길이기도 합니다. 이것은 요청한 앱의 제품 판단이고 프레임워크는 버튼을 막지 않지만, 그쪽 결정이 필요합니다.
3. **데스크톱에서 종료 시 알림 거두기(36.3).** 정했지만 macOS 관례(종료 뒤에도 알림이 남고 누르면 앱이 뜸)와 다릅니다. 반대로 정하면 "앱이 새로 시작되며 받은 `key`를 Host가 해석할 수 있어야 한다"는 요건이 데스크톱에도 생깁니다.
4. **Windows의 바로 가기.** 압축을 푼 실행 파일 하나(INTENT D17)는 시작 메뉴 바로 가기가 없어 토스트를 띄울 수 없습니다. Renderer가 첫 실행에 바로 가기를 만드는 것은 사용자의 시작 메뉴를 앱이 건드리는 일이라 설치 프로그램(FR-22.6의 배포 번들)의 일로 두었습니다. 설치 없이 도는 단일 실행 파일에서는 `Unsupported`가 됩니다. 이 결과를 받아들일지 확인이 필요합니다.

### FR-38 코드 편집기 `CodeEditor` (`Draft`)

여러 줄의 코드를 보여 주고 고치는 위젯입니다. 줄 번호, 구문 색, 그리고 Host가 범위로 붙이는 장식(밑줄, 줄 위 인레이, 호버 앵커, 고스트 텍스트)을 그립니다. 요청한 앱(Ember)은 이 위에 진단 밑줄, CodeLens, Hover, 인라인 완성을 얹습니다. 웹뷰가 없으므로(C1) 브라우저의 편집기를 빌려 올 수 없고, 편집기는 Compose 위에 따로 있어야 합니다.

#### 38.1 텍스트는 Renderer가 소유하고, Host는 확정된 변경만 받습니다

- 편집 중인 버퍼는 Renderer의 것입니다. `TextField`가 비제어인 것(D5, FR-5)과 같은 이유입니다. 글자 하나마다 Host를 왕복하면 입력 지연이 프레임 하나 이상 늘어납니다(NFR-9). 커서, 선택, 스크롤 위치, 실행 취소 기록도 Renderer의 것입니다.
- **IME 조합 중인 글자는 Host로 가지 않습니다(D5).** 조합이 확정된 뒤에만 변경으로 나갑니다.
- 입력은 Compose의 플랫폼 텍스트 입력을 거칩니다(C5). 편집기가 키 이벤트를 직접 받아 글자를 넣는 경로는 두지 않습니다.
- 문서에는 버전이 있습니다. Host가 처음 준 내용이 버전 0이고, 확정된 변경 하나마다 1씩 늘어납니다.
- 처음 내용은 `Text`(속성 1)입니다. Host가 보낸 `Text`가 Renderer의 현재 버퍼와 글자 단위로 같으면 아무것도 하지 않습니다. 다른 컨트롤처럼 앱이 받은 변경을 자기 신호에 다시 써도 커서와 실행 취소 기록이 남습니다. 다르면 문서 전체를 바꾸고 버전 0부터 다시 시작합니다(파일 열기). 같은 내용으로 다시 열려면 노드의 key를 바꿔 새 노드로 엽니다. 새 노드는 새 문서입니다.

#### 38.2 증분 변경 이벤트

- 확정된 변경마다 이벤트 하나: `(version, start_line, start_column, end_line, end_column, text)`. 바뀌기 전 문서 기준의 범위를 새 글자로 바꿨다는 뜻이고, LSP의 `TextDocumentContentChangeEvent`와 같은 모양입니다. 언어 서버나 확장 호스트 쪽 문서 동기화를 이 이벤트만으로 할 수 있어야 합니다.
- 열(column)은 UTF-16 코드 단위입니다. LSP와 VS Code가 쓰는 단위이고, 다른 단위를 쓰면 앱의 연결 계층에서 모든 범위를 다시 세야 합니다. 줄과 열은 0부터 셉니다.
- 붙여넣기, 실행 취소처럼 여러 범위를 한 번에 바꾸는 동작은 같은 프레임 안에 변경 이벤트 여럿으로 나갑니다. 각자 버전이 하나씩 늘어납니다.
- 이벤트는 기존 이벤트 경로(`dispatch_event`)로 가고, 핸들러는 기존 `OnValueChange`(속성 6)입니다. 새 경계 진입점은 없습니다(PR-2).
- `text`는 다른 이벤트의 문자열과 같이 PR-4의 문자열 규약으로 갑니다. 큰 붙여넣기(수 MB)도 같은 경로입니다.

#### 38.3 Host가 문서를 바꾸는 경로

- Host는 편집 명령 `EditCode(request_id, base_version, range, text)`로 바꿉니다(서식 맞춤, 완성 수락, 원격 파일 다시 읽기).
- Renderer는 `base_version`이 현재 버전보다 오래되었으면 그 사이의 변경을 거쳐 범위를 옮긴 뒤 적용합니다. 옮긴 범위가 사용자가 그 사이에 고친 곳과 겹치면 적용하지 않고 거절 이벤트(`CodeEditRejected`, 핸들러 `OnEditRejected`)를 보냅니다. Host가 사용자의 입력을 덮어쓰지 않습니다.
- Host가 적용한 편집도 변경 이벤트로 다시 나갑니다. 그래서 Host와 그 너머(언어 서버, 확장 호스트)가 보는 버전이 하나로 이어집니다.
- 문서 전체 교체(파일 열기)는 버전 0부터 다시 시작합니다(38.1).

#### 38.4 장식

- 장식은 `(version, range, kind, ...)` 목록이고, Host가 `Decorations` 속성으로 통째로 보냅니다. 종류는 넷입니다.
  - **밑줄**: 심각도(오류, 경고, 정보, 힌트)마다 `ColorRole`. 리터럴 색은 받지 않습니다. 역할을 보내지 않으면 디자인 시스템이 심각도에 맞는 역할을 고릅니다. 밑줄의 모양(물결인지, 굵기)은 디자인 시스템이 정합니다.
  - **줄 위 인레이**: 한 줄 위에 끼우는 짧은 글(CodeLens). 누르면 장식 활성 이벤트(`DecorationActivated`, 핸들러 `OnDecorationClick`)가 앱이 그 장식에 붙인 id를 싣고 갑니다.
  - **호버 앵커**: 그 범위 위에 포인터가 머물면 Host로 호버 이벤트(`CodeHovered`, 앱이 그 장식에 붙인 id)가 갑니다. 앵커가 없는 자리에 머물면 장식 없이 위치만 싣고 갑니다. 포인터가 떠나면 같은 이벤트가 떠남으로 한 번 갑니다. 머무는 시간은 디자인 시스템이 정하고(`Tooltip`의 지연과 같은 부류), 머무는 동안 다시 보내지 않습니다. 팝업 내용은 Host가 기존 `Tooltip`/오버레이 위젯으로 그립니다.
  - **고스트 텍스트**: 커서 위치에 흐리게 보이는 제안(인라인 완성). Tab으로 수락하면 Renderer가 일반 편집으로 넣어 변경 이벤트를 먼저 보내고, 이어서 같은 프레임 안에 그 장식의 id를 실은 `DecorationActivated`를 보냅니다. 순서는 늘 이렇습니다. Esc나 다른 입력이면 사라집니다.
- **장식은 편집을 따라갑니다.** Host가 버전 n 기준으로 보낸 장식은, 그 뒤 사용자가 고칠 때마다 Renderer가 범위를 옮깁니다. 지워진 범위의 장식은 사라집니다. 새 목록이 올 때까지 Host는 아무것도 하지 않아도 됩니다.
- 구문 색은 장식이 아니라 색 역할이 붙은 스팬입니다(`SyntaxSpans`). 역할은 코드 색 역할(FR-13.1-3)의 22개이고, 강조기는 FR-13.1-3처럼 Host 쪽에 있습니다. 장식과 같이 편집을 따라갑니다.

#### 38.5 기본기

- 줄 번호, 현재 줄 표시, 탭 폭(`TabWidth` 속성), 자동 들여쓰기는 직전 줄의 들여쓰기 유지까지.
- 저장: 플랫폼 저장 단축키(macOS Command S, 그 밖 Control S)가 Host로 저장 이벤트(`CodeSaveRequested`, 현재 버전, 핸들러 `OnSave`)를 보냅니다. 파일에 쓰는 일은 Host의 몫입니다(PR-3).
- 선택과 복사, 잘라내기, 붙여넣기, 실행 취소와 다시 실행은 Renderer가 합니다.
- **큰 파일**: 십만 줄 파일이 열리고, 스크롤과 입력이 프레임 예산(NFR-9) 안에 있어야 합니다. 보이지 않는 줄은 그리지 않습니다.
- 여러 커서와 찾기는 이 항목에 넣지 않고 따로 엽니다(요청한 앱이 후순위로 정했습니다).
- **역할만 내보냅니다**(FR-14.1). 줄 번호 영역, 현재 줄 표시, 밑줄 굵기, 고스트 텍스트의 흐림은 디자인 시스템의 답입니다. 글자는 `Mono` 타입 역할입니다. Renderer 쪽 확장은 `ComponentRules`에 `codeEditor()` 하나를 더하고, 21.6처럼 기본 구현을 둡니다.

#### 38.6 와이어

경계 진입점은 늘지 않습니다. 위젯, 속성, 명령, 이벤트, 장식과 스팬 레코드는 Rust 스키마에 정의하고 Kotlin 쪽 코덱은 codegen이 생성합니다(FR-7). 스키마 해시가 움직입니다.

- 위젯 태그 43 `CodeEditor`.
- 속성 태그는 15.2.2의 규칙대로 블록 100-109를 엽니다.

| 태그 | 속성 | 값 |
|---|---|---|
| 100 | `Decorations` | 장식 레코드의 블롭(아래) |
| 101 | `SyntaxSpans` | 구문 스팬 레코드의 블롭(아래) |
| 102 | `TabWidth` | i64, 칸 수. 보내지 않으면 디자인 시스템의 값 |
| 103 | `OnEditRejected` | 핸들러 id |
| 104 | `OnHover` | 핸들러 id |
| 105 | `OnSave` | 핸들러 id |
| 106 | `OnDecorationClick` | 핸들러 id |

  나머지는 기존 태그입니다. 처음 내용은 `Text`(1), 변경 이벤트의 핸들러는 `OnValueChange`(6)입니다.
- 장식 레코드, 44바이트: `version: u32`, `kind: u16`(`Underline=1`, `CodeLens=2`, `HoverAnchor=3`, `GhostText=4`), `severity: u16`(`Error=1`, `Warning=2`, `Information=3`, `Hint=4`, 밑줄이 아니면 0), `color: u16`(`ColorRole`, 0이면 디자인 시스템이 고름), 예약 `u16`, `start_line`, `start_column`, `end_line`, `end_column`(각 `u32`), `id: u64`(앱이 고른 장식 id. 0이면 누를 수 없고 활성, 호버 이벤트에 실리지 않음), `text: (offset: u32, len: u32)`(CodeLens와 고스트 텍스트의 글자, 블롭 끝에 놓임. `Canvas`의 글자 명령과 같은 방식).
- 구문 스팬 레코드, 28바이트: `version: u32`, `start_line`, `start_column`, `end_line`, `end_column`(각 `u32`), `paint: Paint`(u64).
- 명령(Mutation), FR-36의 16 뒤에 덧붙입니다:

| 태그 | 명령 | 레코드 |
|---|---|---|
| 17 | `EditCode` | 40바이트: 머리 4, `node_id: u32`, `request_id: u32`(앱이 고르며 거절 이벤트가 그대로 돌려줌), `base_version: u32`, `start_line`, `start_column`, `end_line`, `end_column`(각 `u32`), `text: (offset, len)` |

- 이벤트, FR-36의 25 뒤에 덧붙입니다. 공통 머리 16바이트(`node_id`, `handler_id`) 뒤에 페이로드가 오고, 바이트 수는 머리를 포함한 레코드 길이입니다.

| 태그 | 이벤트 | 페이로드 |
|---|---|---|
| 26 | `CodeChanged` | 44바이트: `version: u32`(이 변경 뒤의 버전), `start_line`, `start_column`, `end_line`, `end_column`(바뀌기 전 문서 기준), `text: (offset, len)` |
| 27 | `CodeEditRejected` | 44바이트: `request_id: u32`(거절된 `EditCode`가 실어 보낸 값), `base_version: u32`, `current_version: u32`, 보낸 그대로의 `start_line`, `start_column`, `end_line`, `end_column` |
| 28 | `CodeHovered` | 36바이트: `decoration: u64`(앱이 장식에 붙인 id, 앵커가 없으면 0), `line: u32`, `column: u32`, `phase: u32`(`Rest=1`, `Leave=2`) |
| 29 | `CodeSaveRequested` | 24바이트: `version: u32`, 예약 `u32` |
| 30 | `DecorationActivated` | 24바이트: `decoration: u64`(앱이 장식에 붙인 id) |

- CodeLens 클릭과 고스트 텍스트 수락은 `DecorationActivated`(이벤트 태그 30)입니다. 장식 블롭 안의 id에는 Host 쪽 리스너가 붙어 있지 않으므로, 편집기 노드의 리스너 하나(`OnDecorationClick`)로 받고 페이로드의 id로 어느 장식인지 가립니다.
- 장식의 id는 앱이 고르는 `u64`이며 0은 "장식 없음"이라 쓰지 않습니다.
- 다섯 이벤트의 페이로드는 지금 `EventPayloadType`에 없는 모양이라 다섯을 더합니다. `kind`, `severity`, `phase`는 닫힌 열거형이고 모르는 값은 `ProtocolError`입니다. 범위가 문서 밖이거나 시작이 끝보다 뒤인 장식, 스팬, 편집은 그 항목만 버리고 `ProtocolError`를 보내며, 프로세스는 죽지 않습니다.

#### 38.7 수용 기준

1. 한글 조합 중에는 변경 이벤트가 나가지 않고, 확정되면 한 번 나갑니다(native-image 수동 확인, §6 방식으로 기록).
2. 임의의 편집 열(입력, 붙여넣기, 실행 취소, 여러 줄 삭제)을 거친 뒤, 버전 0의 내용에 변경 이벤트를 차례로 적용한 결과가 Renderer의 버퍼와 같습니다. UTF-16 서로게이트 쌍(이모지)이 들어간 줄을 포함합니다.
3. 오래된 `base_version`의 Host 편집은 범위가 옮겨져 맞는 자리에 들어가고, 사용자가 그 사이에 고친 곳과 겹치면 거절 이벤트가 나갑니다.
4. 버전 n의 밑줄은, 그 앞에 줄을 끼워 넣으면 아래로 따라가고, 그 범위를 지우면 사라집니다. Host로 가는 이벤트 없이 그렇게 됩니다.
5. CodeLens 클릭, 호버, 고스트 텍스트 수락이 각각 이벤트 하나로 Host에 갑니다. 수락은 변경 이벤트를 하나 더 만듭니다.
6. 저장 단축키가 현재 버전을 담은 저장 이벤트를 보냅니다.
7. 십만 줄 파일에서 스크롤과 입력의 프레임 시간이 NFR-9 예산 안이고, 수치를 이 항목에 기록합니다.
8. 일곱 디자인 시스템에서 그려지고 `DesignShowcase`에 나옵니다.

#### 38.8 일정과 측정 약속 (2026-10-03)

**큰 파일과 Compose 텍스트 입력은 2026-10-10까지 재서 정합니다.** Compose의 `BasicTextField`(`TextFieldState`)는 텍스트 전체를 한 번에 배치합니다. 십만 줄을 그대로 넣으면 기준 7을 넘을 가능성이 큽니다. 줄마다 따로 입력란을 두면 줄을 넘는 선택과 IME가 깨집니다. 길은 셋이고 측정으로 고릅니다.

- (가) `BasicTextField` 하나에 보이는 줄 범위만 넣고, 스크롤 때 그 범위를 바꿈
- (나) 글자 그리기는 직접 하고, 입력만 Compose의 플랫폼 입력 세션에 붙임. 안정 API로 되는지 확인이 필요하고(NFR-6), 안 되면 어댑터 파일 하나로 격리하고 버전을 고정합니다
- (다) 십만 줄 기준을 낮춤. 이것은 SPEC 변경이고 소유자의 결정입니다. 측정이 (가)와 (나)를 모두 막는다고 보여도 기준은 스스로 내려가지 않습니다

어느 길이든 입력은 Compose의 플랫폼 텍스트 입력을 거칩니다(C5). 측정 수치는 이 절에 기록합니다.

일정:

- 2026-10-15 중간 산출물: 파일 하나 열기, 편집, 변경 이벤트, 저장 이벤트(기준 2, 6). 요청한 앱이 원격 파일시스템과 문서 동기화를 붙입니다.
- 2026-10-18: 장식 넷과 큰 파일(기준 3~5, 7, 8).
- 측정 결과가 (나)이면 10-15 산출물이 하루나 이틀 늦어질 수 있습니다.

비용: 위젯 태그 하나(43), 속성 일곱(100-106), 명령 하나(17), 이벤트 다섯(26-30), `EventPayloadType` 다섯. 디자인 시스템 규칙 일곱 벌에 편집기 모양(줄 번호 영역, 현재 줄, 밑줄 굵기). 측정 결과가 (나)이면 Renderer 쪽 텍스트 그리기 코드가 새로 생깁니다.

### FR-39 compose-rust 작성 API와 recomposition 런타임 (`Agreed`, 1.0.0 범위)

**2026-10-03 소유자 결정(INTENT D2).** compose-rust는 Dioxus 없이 쓸 수 있는 자기 작성 API를 가집니다. 모양은 Compose이고, 그 아래는 슬롯 테이블 위의 recomposition 런타임입니다. dioxus-compose는 이 위에 얹힙니다. compose-rust 1.0.0(2026-10-20)의 범위입니다.

#### 39.1 모양

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

위 코드는 모양을 보이기 위한 것이고 이름을 확정하지 않습니다. 확정되는 것은 다음입니다.

- **composable은 함수입니다.** `#[composable]` 속성이 본문에 그룹을 넣고, 사용자는 그룹도 키도 손으로 쓰지 않습니다. 분기, `match`, 반복문, 조기 `return`, `continue`, `break`, `?`, 되감기를 지나도 그룹이 맞게 닫힙니다(INTENT D2가 기록한 클로저 방식의 실패를 되풀이하지 않습니다).
- **이름은 Compose를 따릅니다.** Compose에 같은 개념이 있으면 그 이름을 Rust 관례(함수와 메서드는 snake_case, 위젯은 FR-15의 Compose 이름)로 옮깁니다. PR-7의 "Rust 공개 API는 Dioxus 관례" 행은 dioxus-compose 층에 대한 것이 됩니다.
- **위젯 어휘는 하나입니다.** FR-15의 위젯과 FR-13의 프리미티브, FR-10의 Modifier가 같은 스키마(FR-7)에서 나옵니다. compose-rust API를 위해 위젯이나 속성을 따로 정의하지 않습니다.

#### 39.2 런타임

- **diff가 없습니다.** 무엇이 바뀌었는지는 트리를 비교해서가 아니라, 바뀐 상태를 읽은 스코프를 다시 실행해서 압니다. 다시 실행된 스코프 안에서 인자가 이전과 같은 composable 호출은 건너뜁니다.
- **위치 기반 기억.** `remember`는 호출 자리(그룹 경로와 그 안의 순서)에 묶입니다. 반복문 안에서 항목의 정체성이 순서가 아니라 값에 묶여야 할 때 쓰는 키(Compose의 `key`)를 둡니다. 키가 있는 항목이 자리를 바꾸면 런타임은 노드를 지우고 다시 만들지 않고 이동을 내보냅니다.
- **상태.** 상태 객체를 읽은 스코프가 기록되고, 쓰면 그 스코프들이 무효가 됩니다. 한 프레임 안의 여러 쓰기는 한 번의 recomposition이 됩니다. 워커 스레드의 쓰기도 같은 상태 객체로 하며, Host가 내부적으로 프레임을 요청합니다. 사용자 코드는 경계 함수를 부르지 않습니다(PR-3).
- **effect.** composition이 적용된 뒤 실행되는 효과(Compose의 `SideEffect`), 키가 바뀌면 다시 시작하고 composition을 떠나면 취소되는 효과(`LaunchedEffect`), 떠날 때 정리하는 효과(`DisposableEffect`)를 둡니다. 이벤트 핸들러 안의 상태 쓰기는 recomposition을 부르고, recomposition 도중의 상태 쓰기는 다음 프레임으로 미뤄집니다.
- **UI 로컬 상태는 여전히 Kotlin에 있습니다(D5).** `TextField`는 비제어이고(FR-5), 스크롤, 포커스, 애니메이션 진행 상태는 Renderer가 가집니다. recomposition 런타임이 생겼다고 그것들이 Rust로 옮겨 오지 않습니다.

#### 39.3 경계

런타임이 내보내는 것은 지금과 같은 Mutation 레코드(PR-4)이고, 같은 배치 버퍼로 같은 경계 함수(PR-2)를 건너 같은 렌더러가 그립니다. 경계 진입점, 와이어 포맷, 스키마 해시, 렌더러 바이너리는 작성 모델 때문에 바뀌지 않습니다. 윈도잉(FR-8)과 스트리밍(FR-9)은 같은 레코드로 말합니다.

#### 39.4 수용 기준

1. **Dioxus 없이.** compose-rust만 의존하는 애플리케이션의 의존성 그래프(`cargo tree`)에 `dioxus-*` 크레이트가 없고, 그 애플리케이션이 데스크톱 창을 띄워 그리고 이벤트를 받습니다. compose-rust 크레이트 자체의 의존성에도 `dioxus-*`가 없습니다.
2. **변경되지 않은 그룹을 건너뜀.** 상태 하나를 바꾸면 그 상태를 읽은 스코프만 다시 실행되고, 형제와 부모의 본문은 실행되지 않습니다(실행 카운터로 확인). 인자가 같은 composable 호출은 본문이 실행되지 않습니다. Text 하나의 내용을 바꾸는 상호작용이 내보내는 Mutation은 `SetProp` 1건입니다(FR-4와 같은 기준).
3. **위치 기반 기억.** 분기가 사라지거나 다시 나타날 때, 반복이 줄거나 늘 때, `match` 갈래가 바뀔 때 각 호출 자리가 자기 값을 유지하고 남의 값을 받지 않습니다. 같은 컴포넌트를 평평한 오프셋으로 돌린 대조군이 실패하는 것을 테스트가 함께 보입니다. 조기 `return`, `continue`, `break`, `?`, 되감기 뒤에도 그룹이 맞게 닫힙니다. 키가 있는 항목의 재배치가 이동으로 나가고 항목의 `remember` 값이 따라갑니다.
4. **상태.** UI 스레드의 쓰기와 워커 스레드의 쓰기가 모두 다음 프레임에 반영되고, 한 프레임 안의 여러 쓰기가 recomposition 한 번이 됩니다. 사용자 코드에 경계 함수 호출이 없습니다.
5. **effect.** 적용 후 효과는 적용된 composition마다 한 번 실행되고, 키 있는 효과는 키가 바뀔 때 취소 후 다시 시작하며, composition을 떠난 스코프의 효과는 취소되고 정리 함수가 한 번 실행됩니다.
6. **같은 와이어, 같은 렌더러.** 경계 진입점과 스키마 해시가 바뀌지 않고, 체크인된 프로토콜 벡터가 그대로 통과하며, 같은 렌더러 아티팩트가 compose-rust 애플리케이션과 dioxus-compose 애플리케이션을 모두 그립니다.
7. **`rsx!`와 같은 표현력.** 오늘 `rsx!`로 말할 수 있는 것을 compose-rust API로 전부 말할 수 있습니다. FR-15의 위젯 전부와 그 속성, FR-10의 Modifier, FR-13의 프리미티브, 이벤트와 그 소비(FR-3, FR-12), 비제어 `TextField`와 `SetText`(FR-5), 윈도잉(FR-8), 스트리밍(FR-9), 디자인 시스템과 테마(FR-14), 에셋(FR-16), 커스텀 드로잉(FR-17), 창 설정(FR-19.3), 창 크기 클래스(FR-20), 탐색과 시트와 일시 메시지(FR-21), 워커 스레드의 갱신(PR-3)입니다. 저장소의 샘플을 compose-rust API로 옮긴 것과 지금의 `rsx!` 판이 같은 상호작용 뒤에 Renderer 쪽에서 같은 노드 트리를 만드는 것으로 확인합니다.
8. **성능: Dioxus 경로보다 10배.** 소유자가 정한 목표이고, 측정이 숫자에 맞춰 바뀌지 않도록 아래 규칙을 함께 정합니다.

   > "나는 성능 목표 10배로 잡으면 하는데 니가 말한 측정 조건을 사기치는 경우가 발생할 수 있다는 우려에 동의해. 니가 잘 지시해봐 그러면" (2026-10-03)

   1. **시나리오는 구현 전에 고정합니다.** 벤치 시나리오를 구현보다 먼저 커밋하고, 그 뒤에 바꾸려면 소유자의 승인이 필요합니다. 두 묶음입니다. (a) 바뀐 동적 슬롯 수 1, 5, 17, 33, 65, 129의 스윕. (b) 샘플에 기록해 둔 실제 상호작용의 재생: 계산기 입력, todo 추가와 삭제, chat 스트리밍, 긴 목록의 스크롤과 윈도잉, 탭 전환. 벤치를 위해 만든 화면이나 한쪽에 유리하게 만든 화면은 넣지 않습니다.
   2. **같은 결과일 때만 셉니다.** 측정을 실행할 때마다 두 경로가 Renderer 쪽에 같은 노드 트리를 만드는지 확인하고, 다르면 그 측정은 무효입니다. 내보낸 Mutation 수와 바이트 수를 함께 기록하며, compose-rust 경로가 Dioxus 경로보다 많으면 안 됩니다.
   3. **일을 옮겨서 얻은 숫자는 세지 않습니다.** Host 시간(핸들러, recomposition, 배치 인코딩)과 함께 Renderer 적용 시간과 프레임 전체 시간을 잽니다. Host에서 줄어든 만큼 Renderer나 다른 스레드로 일이 넘어갔다면 그만큼 실패입니다. 워커 스레드, 지연 처리, 캐시 예열로 측정 구간 밖에 일을 빼는 것도 금지합니다.
   4. **기준선을 건드리지 않습니다.** Dioxus 경로는 측정을 시작한 시점의 커밋으로 고정하고, 비교할 때마다 같은 날, 같은 기계, 같은 빌드 프로필(release, 같은 플래그)로 나란히 잽니다. 기준선 쪽 코드가 바뀌면 그 측정 묶음 전체를 다시 잽니다.
   5. **벤치 전용 경로가 없습니다.** 측정은 공개 API로만 합니다. 런타임에 `cfg(bench)`, 벤치를 감지하는 분기, 측정 전용 플래그가 없어야 하고, 이것을 확인하는 검사가 CI에 있습니다.
   6. **판정.** 목표는 (a)와 (b)의 모든 시나리오에 걸쳐 p50 배수의 기하평균 10배 이상, p99 배수의 기하평균 5배 이상입니다. 최소선으로, 어느 시나리오도 Dioxus 경로보다 느리면 안 됩니다. §5.1의 절대 기준(일반 상호작용 0.5ms, 스트리밍 프레임 1ms, 인코딩 할당 0회, 반복해도 늘지 않는 메모리)은 그대로입니다. 시나리오별 배수를 전부 표로 남기고, 평균만 보고하지 않습니다.
   7. **실행 조건을 기록합니다.** 기계, 부하 평균, 반복 횟수, 워밍업 횟수, 시나리오마다 p50, p99, 최대값과 원시 데이터의 경로를 `benches/baseline.json`에 남깁니다. 부하 평균이 높을 때 잰 값은 그 사실과 함께 기록하고, 판정에는 한가한 기계에서 잰 값을 씁니다.
   8. **결과는 그대로 보고합니다.** 10배에 못 미치면 실측 배수를 그대로 보고합니다. 목표를 맞추려고 시나리오, 지표, 판정 방법을 바꾸는 것은 금지입니다. 이 기준을 `Done`으로 표시하는 것은 측정한 쪽이 아니라, 다른 세션이 같은 벤치를 다시 돌려 같은 결과를 확인한 뒤입니다.

### FR-40 HTML 텍스트 속성 (`Agreed`)

CSS가 크기와 글꼴을 정하는 텍스트(HTML 경로)를 그리고 재는 데 필요한 속성입니다. 측정 호출(PR-2.1)의 값이 그려진 값과 같으려면, 그리기도 같은 칸을 같은 뜻으로 받아야 합니다. 노드 단위 글꼴은 역할이 없는 텍스트에만 엽니다(INTENT D20, 2026-10-03 소유자 결정).

`Text`의 기본 모양과 FR-26 스팬 레코드에 다음을 더합니다. 측정 호출(PR-2.1)의 `MeasureText` 요청은 같은 칸을 같은 뜻으로 싣습니다. **측정과 그리기는 같은 해석 함수를 씁니다.**

1. `TypeRole::None`(태그 0). 타입 스케일을 쓰지 않고, 크기, 굵기, 행간, 자간, 글꼴을 명시적 값에서만 가져옵니다. 명시적 값이 없는 칸은 Renderer의 기본값(14dp, 400, normal)입니다.
2. `font: FontRef`. `TypeRole::None`일 때만 씁니다. 다음 셋 중 하나이고, 앞에서부터 처음 해석되는 것을 씁니다(CSS 글꼴 목록).
   - `Asset(id)`: FR-16 에셋. FR-23.2의 `AssetKind::Font`를 그대로 씁니다. 앱이 바이트를 제공하고(`@font-face src`), 크레이트가 받아오지 않습니다.
   - `System(name)`: 시스템 패밀리 이름. 이름 문자열은 레코드 뒤 페이로드에 놓입니다.
   - `Generic(SystemUi | SansSerif | Serif | Monospace)`
   - 목록은 최대 8개입니다. 아무것도 해석되지 않으면 `Generic(SansSerif)`입니다. 없는 에셋 id는 `ProtocolError`이고, 목록의 다음 것으로 넘어갑니다.
3. `word_break: Normal | KeepAll | BreakAll`, `overflow_wrap: Normal | Anywhere | BreakWord`. CSS와 같은 뜻입니다.
   - 한국어 `Normal`은 음절 사이에서 끊을 수 있고, `KeepAll`은 공백에서만 끊습니다.
   - Compose의 기본 줄바꿈은 플랫폼마다 다르므로, Renderer가 이 값에 맞춰 줄바꿈 전략을 정합니다.
4. `tab_size: u8`(기본 8). 탭은 다음 탭 위치까지 차지합니다. 비례 글꼴에서는 Host가 탭을 공백으로 펼칠 수 없습니다.
5. `absolute_size: bool`. 참이면 시스템 글꼴 배율을 적용하지 않습니다(CSS px). 측정과 그리기에 똑같이 적용됩니다.
6. 공백 규칙: Renderer는 받은 문자열의 공백을 자르거나 접지 않습니다. 접기는 Host의 일입니다.

수용 기준:
1. 같은 HTML 텍스트를 측정한 값과 그린 값이 비트 단위로 같습니다(PR-2.1 기준 1). 아래 각 경우를 모두 확인합니다.
   - 글꼴 종류 셋(에셋, 시스템, 일반)
   - 한국어 `Normal`과 `KeepAll`
   - 탭이 든 `pre`
   - `absolute_size`를 켠 텍스트와 끈 텍스트
2. 등록한 아이콘 글꼴의 사설 영역 글리프가 그 글꼴로 그려지고 크기가 0이 아닙니다(codicon 한 글자).
3. 시스템 글꼴 배율을 200%로 바꿨을 때, `absolute_size` 텍스트의 측정값과 그린 크기는 그대로이고, 역할을 가진 텍스트는 커집니다.
4. 역할을 가진 텍스트에 `font`를 보내면 무시되고 테마 글꼴로 그려집니다. 역할 원칙이 HTML 경로 밖으로 새지 않는다는 확인입니다.
5. 다섯 플랫폼과 Web에서 기준 1의 라틴 경우가 통과합니다.

- HTML 화면의 접근성 배율(시스템 글꼴 배율을 `absolute_size` 텍스트에 어떻게 반영할지)은 아직 정하지 않았습니다. 검토 문서를 만들어 소유자가 정합니다. `absolute_size` 칸 자체는 어느 결정에서도 필요합니다.
### FR-41 Transform과 렌더러가 재생하는 애니메이션 (`Agreed`)

요청한 쪽: dioxus-compose FR-34.1(CSS transform 전부, M14 10-18)과 FR-34.2(CSS transition과 animation, 혼합안). 소유자 결정(2026-10-03): `opacity`, `color`, `background-color`, `transform`은 렌더러가 Compose 애니메이션으로 재생하고, 레이아웃 속성은 나중에(M15) Host가 매 프레임 시각을 넘겨 계산합니다. 이 초안은 그중 렌더러 쪽 둘을 compose-rust 스키마에 적습니다.

전제: HTML/CSS 원소 초안(dioxus-compose `.scratch/spec-drafts/html-css-primitives.md`)의 `AbsoluteBox`(위젯 44)와 수정자 19~25(`Offset`, `RequiredSize`, `BorderEach`, `CornerEach`, `Shadow`, `Clip`, `Alpha`)가 먼저 들어간다고 봅니다. 

소유자 결정(2026-10-03):
- **2D 먼저, 3D는 다음 단계.** M14에서는 2D 아핀만 구현합니다. 3D는 FR-41.3에 다음 단계 항목으로 남깁니다. "지원하지 않음"이 아닙니다.
- **앱 작성 API에도 엽니다.** 같은 레코드를 FR-39의 compose-rust API에서 쓸 수 있습니다(FR-41.4).
- **이벤트 레코드의 프레임 시각은 검토 중입니다.** 결론이 나기 전까지 기본값은 "싣지 않음"이고, 이 항목은 `Ready` 이벤트와 Host의 추정으로 시작 시각을 압니다.

### FR-41.1 `Transform` 수정자 (`Agreed`)

노드와 그 안의 내용 전부를 2D 아핀 변환으로 그립니다. 레이아웃에는 영향을 주지 않고, 적중 판정은 변환된 모양을 따릅니다. CSS `transform`과 `transform-origin`의 계산된 값이 그대로 들어가는 자리입니다.

#### 값

- `Modifier::Transform { a, b, c, d, e, f, origin_x, origin_y }`, 모두 `f32`. 수정자 태그 26.
- 행렬은 CSS `matrix(a, b, c, d, e, f)`와 같은 뜻입니다. 열 벡터 기준으로 `x' = a·x + c·y + e`, `y' = b·x + d·y + f`입니다. `a`~`d`는 단위가 없고, `e`, `f`는 dp입니다(CSS px와 같은 단위로, `Offset`과 같은 변환을 Host가 합니다).
- **원점은 노드 크기에 대한 비율입니다.** `origin_x`, `origin_y`는 노드가 측정된 크기(CSS의 border box)에 대한 비율이고, `(0.5, 0.5)`가 가운데입니다. 고른 이유:
  - Compose의 `TransformOrigin`이 비율이므로 렌더러가 변환 없이 씁니다.
  - 노드의 크기가 렌더러에서 정해지는 경우(Host가 크기를 모르는 `weight`, `FillMaxWidth` 노드)에도 "가운데를 축으로 돈다"가 맞습니다. 크기가 바뀌어도 레코드를 다시 보낼 필요가 없습니다. CSS의 기본값(`50% 50%`)과 키워드 원점이 이 경우입니다.
  - 길이로 쓴 원점(`transform-origin: 10px 20px`)은 Host가 원점을 행렬에 접어 넣고(`T(o)·M·T(-o)`) 원점을 `(0, 0)`으로 보냅니다. 이렇게 하면 크기가 0인 상자에서도 정확합니다. 비율로는 크기가 0인 상자의 길이 원점을 나타낼 수 없기 때문입니다.
- 항등 변환은 `(1, 0, 0, 1, 0, 0)`이고 원점은 무엇이든 됩니다. 변환이 없는 노드는 이 수정자를 갖지 않는 것이 보통이지만, 애니메이션의 자리 표시(FR-41.2)로 항등 변환을 둘 수 있습니다.
- `translate`는 이 수정자로 보냅니다. `Offset`에 더하지 않습니다. 레이아웃 자리와 변환을 섞으면 적중 판정, 원점, 렌더러 재생 애니메이션이 모두 틀어집니다. 지금 dioxus-compose-html이 이동을 상자 위치에 더하는 것은 이 수정자가 들어오면 바뀌어야 합니다.

#### 와이어: 넓은 `SetModifier`

- 지금 `SetModifier` 레코드는 28바이트이고 수정자 값 칸은 `(first: u64, second: u64)` 16바이트입니다(FR-13). 이 수정자는 `f32` 여덟 개, 32바이트라 들어가지 않습니다.
- **수정자 태그가 레코드 길이를 정하게 합니다.** `SetModifier`의 길이는 `28 + 8k`이고, `k`는 수정자 태그마다 스키마에 고정됩니다. 지금 있는 수정자는 모두 `k = 0`이라 바이트가 하나도 바뀌지 않습니다. `Transform`은 `k = 2`, 44바이트입니다.

```
SetModifier, Transform (44바이트)
offset  0  tag: u16 = 3          offset  2  len: u16 = 44
offset  4  node_id: u32
offset  8  index: u16            offset 10  modifier: u16 = 26
offset 12  first:  (a: f32, b: f32)
offset 20  second: (c: f32, d: f32)
offset 28  third:  (e: f32, f: f32)
offset 36  fourth: (origin_x: f32, origin_y: f32)
```

- 두 `f32`가 `u64` 하나에 들어가는 방식은 지금의 `pack_floats`와 같습니다(첫 값이 하위 32비트).
- 태그가 요구하는 길이와 레코드의 `len`이 다르면 `ProtocolError`입니다(NFR-7).
- 코드젠의 필드 슬롯(`FieldSlot`)에 `ThirdLow`, `ThirdHigh`, `FourthLow`, `FourthHigh`가 늘어납니다. Kotlin 디코더와 Rust 인코더는 지금처럼 생성됩니다(FR-7).
- 버린 대안 둘:
  - 두 수정자로 나누기(선형 부분 4개, 이동과 원점 4개). 둘 다 16바이트에 들어가지만, 회전과 이동이 함께 바뀌면 레코드가 둘 나가서 dioxus-compose FR-34.1 수용 기준 4("변환 값만 바뀐 갱신에서 변환 수정자 하나만")를 어깁니다.
  - 값을 arena의 별도 영역에 두고 `(offset, len)`으로 가리키기(문자열과 같은 방식). 레코드 길이는 그대로지만, 고정 크기 값에 간접 참조와 범위 검사를 하나 더 붙일 이유가 없습니다.
- 같은 문제가 HTML/CSS 원소 초안에도 있습니다. `Shadow`(23)는 `f32` 넷과 `Paint` 하나로 24바이트, `BorderEach`(21)는 두께 넷과 `Paint` 넷으로 48바이트라 16바이트 칸에 들어가지 않습니다. 같은 규칙(`k`가 각각 1, 4)을 거기에도 쓰자고 제안합니다.

#### Compose 대응

- **Compose의 안정 API에는 임의 행렬을 받는 레이어가 없습니다.** `graphicsLayer`가 받는 것은 이동, 확대, 회전(Z, X, Y), 원점이고, 기울이기(skew)는 없습니다. 그리기 단계의 `withTransform { transform(matrix) }`는 임의 행렬을 그릴 수 있지만 적중 판정과 자식의 좌표가 따라오지 않으므로 쓰지 않습니다.
- **그래서 `graphicsLayer` 두 겹으로 나눕니다.** 2x2 선형 부분 `A`를 특이값 분해하면 `A = R(θ1)·S(σ1, σ2)·R(θ2)`입니다. 바깥 레이어는 `translationX = e`, `translationY = f`, `rotationZ = θ1`, `scaleX = σ1`, `scaleY = σ2`이고, 안쪽 레이어는 `rotationZ = θ2`입니다. 두 레이어 모두 `transformOrigin = TransformOrigin(origin_x, origin_y)`입니다. 두 레이어가 같은 크기의 같은 노드에 겹쳐 있으므로 합은 `T(e, f)·T(o)·A·T(-o)`이고, CSS의 `T(o)·M·T(-o)`와 같습니다.
  - 행렬식이 음수(반사, `scaleX(-1)`)이면 `σ2`가 음수입니다.
  - 기울이기가 없고 확대가 균일하면(`b = -c`, `a = d` 꼴) 한 겹으로 충분하고, 렌더러는 그때 한 겹만 씁니다. 결과는 같습니다.
  - Compose 레이어의 연산 순서(확대, 회전, 원점, 이동)에 기대는 코드는 이 분해 하나뿐이고, 한 파일에 둡니다. 순서가 다르면 아래 수용 기준 3이 바로 깨집니다.
- **행렬식이 0이면 그리지 않습니다.** CSS는 역행렬이 없는 변환을 받은 상자와 그 내용을 그리지 않습니다. 렌더러는 그 노드의 내용을 그리지 않고, 적중 판정에서도 빠집니다.
- 값에 NaN이나 무한대가 있으면 `ProtocolError`입니다.
- **레이아웃은 바뀌지 않습니다.** 측정, 배치, 형제의 자리, `ObserveSize`가 알리는 크기, `LazyColumn` 창 계산은 변환 전 상자 기준입니다. CSS와 같습니다.
- **적중 판정은 Compose가 합니다.** Compose의 포인터 입력은 노드를 찾을 때 포인터 위치를 각 `graphicsLayer`의 역행렬로 옮긴 뒤 노드의 사각형 안인지 봅니다. 그래서 체인에서 `Transform` 뒤(안쪽)에 있는 `Clickable`과 포인터 수정자는 변환된 모양으로 판정됩니다. **`Transform`보다 앞(바깥)에 둔 `Clickable`은 변환 전 사각형으로 판정되므로** Host는 `Clickable`을 `Transform` 뒤에 둡니다.
  - dioxus-compose가 클릭을 루트에서 받아 Host에서 blitz 좌표로 적중 판정하는 길을 쓴다면, 그 판정이 역행렬을 적용해야 합니다. 스키마가 약속하는 것은 Compose의 적중 판정입니다.
- **렌더러만의 상태.** 이 수정자 자체는 Host가 보낸 값 하나이고 렌더러가 따로 들고 있는 상태는 없습니다. 분해 결과(θ1, σ1, σ2, θ2)는 레코드 값에서 유도한 캐시로, `remember`의 키가 레코드 값입니다. 값이 바뀌면 레이어 속성만 갱신되고, 그 노드와 자식은 다시 측정되지 않습니다.

#### 다른 수정자와의 순서

수정자 목록은 순서대로 Compose 체인이 됩니다(FR-10). 렌더러는 순서를 바꾸지 않습니다. CSS와 같은 결과를 내는 순서는 이렇고, Host가 이 순서로 보냅니다.

```
Offset, RequiredSize, Transform, Alpha, Shadow, Background, Border/BorderEach, Clip, Clickable, 그 밖의 것
```

- `Offset`, `RequiredSize`가 `Transform`보다 바깥입니다. 상자를 먼저 제자리에 놓고 그 상자의 좌표계에서 변환해야 원점이 상자 기준이 됩니다. 순서가 반대이면 원점이 이동 전 자리를 기준으로 잡힙니다.
- `Transform`과 `Alpha`는 순서와 무관하게 같은 그림이 됩니다(불투명도와 기하 변환은 교환됩니다). 렌더러는 붙어 있는 둘을 레이어 하나로 합쳐도 되고, 합치지 않아도 됩니다. `Alpha`는 HTML 초안대로 묶음 전체에 한 번 적용됩니다(`graphicsLayer`의 오프스크린 합성).
- `Clip`은 `Transform` 안쪽입니다. CSS의 `overflow: hidden`이 만드는 자르기 영역은 상자와 함께 돌아갑니다. 조상의 `Clip`은 조상의 좌표계에서 변환된 자식을 자릅니다. 둘 다 체인 순서가 그대로 만들어 줍니다.
- `Shadow`와 `Background`, `Border`도 `Transform` 안쪽입니다. CSS에서 그림자와 테두리는 상자와 함께 변환됩니다. `graphicsLayer`는 기본으로 자르지 않으므로(`clip = false`) 상자 밖으로 번지는 그림자도 그대로 그려집니다.
- 한 목록에 `Transform`이 둘 이상이면 바깥 것부터 차례로 곱해집니다. 오류는 아니지만 Host는 하나로 합쳐 보냅니다.
- 쌓임 순서(`z-index`, 변환이 만드는 쌓임 맥락)는 HTML 초안대로 Host가 `AbsoluteBox`의 자식 순서로 풉니다. 이 수정자는 순서를 바꾸지 않습니다.

수용 기준:
1. `Transform` 수정자가 44바이트 `SetModifier`로 Rust와 Kotlin에서 같은 바이트로 인코딩되고 디코딩됩니다(체크인된 프로토콜 벡터). 기존 수정자 0~25의 벡터는 바이트 하나도 바뀌지 않습니다(fr41_transform_round_trips, fr41_existing_modifier_vectors_are_unchanged).
2. 태그가 요구하는 길이와 다른 `SetModifier`, 값에 NaN이나 무한대가 든 `Transform`은 `ProtocolError` 이벤트가 되고 프로세스가 중단되지 않습니다.
3. `rotate(15deg)`, `scale(1.5)`, `skewX(10deg)`, `scaleX(-1)`, 그리고 셋을 곱한 행렬 각각을 원점 `(0, 0)`과 `(0.5, 0.5)`로 단 100x40dp 상자에서, 네 꼭짓점의 `LayoutCoordinates.localToRoot` 결과가 행렬을 직접 적용한 값과 0.01dp 안에서 같습니다. 데스크톱 렌더러 테스트입니다.
4. 변환된 노드의 측정 크기, 형제의 자리, `ObserveSize`가 알리는 크기가 변환이 없을 때와 같습니다.
5. 100x40dp 상자를 가운데 기준으로 45도 돌리고 `Clickable`을 `Transform` 뒤에 둔 노드에서, 마우스로 원래 사각형의 꼭짓점 근처(로컬 `(3, 3)`)를 누르면 `Clicked`가 오지 않고, 가운데를 누르면 오며, 원래 사각형 밖이지만 돌아간 모양 안인 점을 누르면 옵니다.
6. 돌아간 상자 안의 `Text`와 자식 상자가 함께 돌아가 그려집니다(스크린숏 테스트).
7. `[Transform, Clip]`에서 자르기 영역이 함께 돌아가고, `[Transform, Alpha]`와 `[Alpha, Transform]`의 그림이 픽셀 단위로 같습니다.
8. 행렬식이 0인 변환(`scale(0)`)을 받은 노드는 아무것도 그리지 않고, 그 자리를 눌러도 `Clicked`가 오지 않습니다.
9. 변환 값만 바뀐 갱신은 그 노드의 `SetModifier` 하나만 싣고, 렌더러에서 그 노드와 자식이 다시 측정되지 않습니다(측정 횟수 계측).

---

### FR-41.2 렌더러가 재생하는 애니메이션 (`Agreed`)

Host가 애니메이션 하나를 한 번 기술하면, 렌더러가 자기 프레임 클록으로 끝까지 재생합니다. 재생하는 동안 Host로 가는 호출은 없습니다(D5: 애니메이션 상태는 렌더러). 대상은 레이아웃에 영향이 없는 넷, `Alpha`, `Color`, `Background`, `Transform`입니다.

#### FR-24와의 관계

13.7과 FR-24는 "Host가 길이와 곡선을 보내면 디자인 시스템이 모션을 정한다는 원칙이 무너진다"는 이유로 애니메이션 스펙을 스키마에서 뺐고, 모션을 역할(`MotionRole`)로 말하게 했습니다. 이 항목은 그 원칙을 뒤집지 않습니다. 색에서 `Paint::Role`이 기본이고 `Paint::Literal`이 탈출구이듯, **모션에서도 역할이 기본이고 이 레코드는 탈출구입니다.** CSS로 쓴 화면은 길이와 곡선을 화면의 저자가 이미 정했고, 디자인 시스템이 그것을 바꾸면 Chromium과 같은 화면이라는 FR-34의 약속이 깨집니다. 같은 노드의 같은 속성에 이 레코드가 효력을 가진 동안에는 `Motion` 역할의 보간보다 이 레코드가 우선합니다.

#### 수정자가 아니라 명령 레코드

애니메이션은 수정자(태그 27)가 아니라 새 Mutation 두 개, `StartAnimation`(Mutation 태그 18)과 `ControlAnimation`(19)입니다. 이유:

- **값이 아니라 사건입니다.** 수정자는 선언된 값이고 Host의 diff는 같은 값을 다시 보내지 않습니다. CSS에서는 같은 transition이 같은 값으로 다시 시작될 수 있습니다(올렸다 내렸다 다시 올린 hover). 값으로 두면 두 번째 시작이 diff에서 사라집니다. 명령은 보낸 만큼 시작합니다.
- **열쇠가 자리 번호가 아닙니다.** 수정자는 목록의 자리(`index`)로 식별되므로, 앞에 수정자 하나가 끼면 뒤의 수정자가 다시 나갑니다. 애니메이션이 그것을 "새로 시작"으로 읽으면 진행이 처음으로 돌아갑니다. 명령의 열쇠는 `(node, property, slot)`이라 목록이 바뀌어도 그대로입니다.
- **기준값과 애니메이션은 다른 것입니다.** CSS에서 애니메이션은 속성의 바탕값(underlying value)을 덮어쓰는 것이고, 끝나면(fill이 없으면) 바탕값이 다시 보입니다. 바탕값은 지금처럼 수정자와 속성이 들고, 애니메이션은 그 위에 얹힙니다. 둘을 같은 칸에 넣으면 이 구분을 잃습니다.
- **길이가 가변입니다.** 키프레임 수와 변환 함수 수에 따라 레코드가 104바이트에서 수백 바이트입니다. 고정 칸인 수정자 값에 넣으면 FR-13의 값 모델이 무너집니다.
- 대가: Mutation 태그 둘, 이벤트 태그 하나, 렌더러의 애니메이션 표 하나. 경계 진입점(PR-2)은 늘지 않습니다. 모두 지금 배치를 타고 같은 호출 안에서 적용됩니다(PR-1). 큐가 아닙니다.

#### 대상과 바탕값

| `property` | 값 | 바탕값을 들고 있는 자리 | 그리는 단계 |
|---|---|---|---|
| `Alpha = 1` | `f32` | 그 노드의 첫 `Alpha` 수정자(25) | `graphicsLayer { alpha }` |
| `Color = 2` | `Paint` | 그 노드의 `color` 속성(`Text` 등 `color` 속성이 있는 위젯) | 그리기 단계에서 읽는 색(`BasicText`의 `ColorProducer`) |
| `Background = 3` | `Paint` | 그 노드의 첫 `Background` 수정자(7) | `drawBehind`에서 읽는 색 |
| `Transform = 4` | 변환 함수 목록 | 그 노드의 첫 `Transform` 수정자(26) | `graphicsLayer` 두 겹(FR-41.1) |

- **애니메이션은 바탕값을 가진 자리에서 그려집니다.** 수정자는 순서가 뜻을 가지므로(FR-41.1의 순서), 애니메이션된 `Transform`이 체인의 어디에 끼는지가 정해져 있어야 합니다. 그래서 `Alpha`, `Background`, `Transform` 애니메이션은 같은 종류의 수정자가 이미 그 노드에 있어야 하고, 렌더러는 그 첫 수정자의 자리에서 값을 바꿔 그립니다. CSS에 바탕값이 없으면 Host가 항등값(`Alpha(1)`, 투명 `Background`, 항등 `Transform`)을 둡니다.
  - 확인 시점은 배치 전체를 적용한 뒤입니다. 같은 배치 안에서 `StartAnimation`이 바탕 수정자보다 먼저 와도 됩니다.
  - 배치를 다 적용한 뒤에도 그 자리가 없거나, `Color` 대상인데 그 위젯에 `color` 속성이 없으면 `ProtocolError`입니다.
  - 나중의 `SetModifier`가 그 자리를 없애면(다른 종류로 바꾸거나 `Empty`로) 그 노드의 그 속성 애니메이션은 모두 조용히 취소됩니다. Host가 그렇게 보냈으므로 Host는 이미 압니다.
- **값을 그리기 단계에서만 바꿉니다.** 렌더러는 매 프레임 상태 하나를 쓰고, 그 상태는 `graphicsLayer {}` 블록이나 그리기 람다 안에서만 읽힙니다. 그래서 재생 중에 그 노드는 다시 구성(recomposition)되지도, 다시 측정되지도 않습니다. `Color`도 글자를 다시 배치하지 않습니다.

#### `StartAnimation` (Mutation 태그 18)

```
머리 52바이트
offset  0  tag: u16 = 17            offset  2  len: u16
offset  4  node_id: u32
offset  8  animation_id: u32        Host가 붙이는 이름. 이벤트가 그대로 돌려줍니다
offset 12  property: u8             Alpha=1, Color=2, Background=3, Transform=4
offset 13  slot: u8                 같은 노드·속성 안의 우선순위. 클수록 위
offset 14  direction: u8            Normal=1, Reverse=2, Alternate=3, AlternateReverse=4
offset 15  fill: u8                 None=1, Forwards=2, Backwards=3, Both=4
offset 16  play_state: u8           Running=1, Paused=2
offset 17  interpolation: u8        색만: SrgbPremultiplied=1, Oklab=2. 그 밖은 0
offset 18  keyframe_count: u16      2 이상
offset 20  start_time_nanos: u64    렌더러 프레임 클록 기준. 0이면 "이 배치가 처음 그려지는 프레임"
offset 28  delay_ms: f32            음수 허용(CSS와 같이 중간부터 시작)
offset 32  duration_ms: f32         0 이상
offset 36  iterations: f32          0 이상, +∞ 허용(infinite)
offset 40  origin_x: f32            Transform만. 노드 크기에 대한 비율(FR-41.1과 같음). 그 밖은 0
offset 44  origin_y: f32
offset 48  events: u8               받고 싶은 이벤트의 비트(아래). 0이면 아무 이벤트도 오지 않습니다
offset 49  reserved: u8 × 3
뒤이어 키프레임 keyframe_count개
```

키프레임 하나:

```
offset  0  offset: f32              0..1, 앞 키프레임 이상. 첫 키프레임은 0, 마지막은 1
offset  4  timing: u8               Linear=1, CubicBezier=2, Steps=3. 다음 키프레임까지의 구간에 씁니다
offset  5  jump: u8                 Steps만: JumpStart=1, JumpEnd=2, JumpNone=3, JumpBoth=4
offset  6  flags: u16               비트 0 FromPresented(첫 키프레임만, 아래)
offset  8  p1..p4: f32 × 4          CubicBezier: x1, y1, x2, y2. Steps: p1에 단계 수 n(u32 그대로)
offset 24  값                        property에 따라
```

| `property` | 키프레임의 값 | 키프레임 크기 |
|---|---|---|
| `Alpha` | `f32` | 28바이트 |
| `Color`, `Background` | `Paint`(`u64`, 13.1과 같은 인코딩) | 32바이트 |
| `Transform` | `count: u16`, `reserved: u16`, 변환 함수 `count`개(각 28바이트) | `28 + 28·count`바이트 |

변환 함수 하나는 `kind: u32`와 `f32` 여섯 칸(28바이트)입니다. 쓰지 않는 칸은 0입니다.

| `kind` | 함수 | 칸 |
|---|---|---|
| 1 | `Translate` | x, y (dp) |
| 2 | `Rotate` | 각도(도). 360도로 접지 않습니다. 0에서 720까지는 두 바퀴입니다 |
| 3 | `Scale` | sx, sy |
| 4 | `Skew` | x축 각도, y축 각도(도) |
| 5 | `Matrix` | a, b, c, d, e, f |

- **변환은 행렬이 아니라 함수 목록으로 보냅니다.** 행렬로는 `rotate(0)`에서 `rotate(360deg)`로 가는 애니메이션을 나타낼 수 없습니다. 두 끝이 같은 행렬이기 때문입니다. `rotate(0) translateX(100px)`에서 `rotate(360deg) translateX(100px)`로 가는 공전도 행렬 보간과 다르게 움직입니다. CSS가 함수 단위로 보간하므로 같은 것을 보냅니다.
- **목록 맞추기는 Host가 합니다.** 한 애니메이션의 모든 키프레임은 같은 길이, 같은 순서, 같은 종류의 함수 목록이어야 합니다. CSS의 규칙대로 짧은 쪽을 항등 함수로 채우거나, 종류가 맞지 않으면 키프레임마다 `Matrix` 하나로 접는 일은 Host(stylo)가 이미 하는 일이고, 렌더러는 그 결과를 받습니다. 모양이 다르면 `ProtocolError`입니다.
- 렌더러의 보간은 같은 자리의 함수끼리 칸마다 선형입니다. `Matrix`끼리는 CSS Transforms 1의 2D 행렬 분해(이동, 확대, 각도, 나머지 행렬)로 분해해 보간하고 다시 합칩니다. 보간한 목록을 곱한 행렬이 FR-41.1의 두 겹 레이어로 그려지므로 적중 판정도 그 순간의 모양을 따릅니다.
- 백분율 이동(`translateX(50%)`)은 Host가 계산된 상자 크기로 dp로 바꿔 보냅니다. 재생 중에 상자 크기가 바뀌면 Host가 같은 `animation_id`, 같은 `start_time_nanos`로 다시 보내 진행을 이어 갑니다.
- 색 보간: `SrgbPremultiplied`는 CSS의 기존 색 문법(`rgb()`, `#hex`, 이름 색)이 쓰는 방식이고, `Oklab`은 CSS Color 4의 새 문법 기본값입니다. Compose의 `lerp(Color, Color)`는 Oklab이라 앞의 것에 그대로 쓸 수 없습니다. 보간 뒤 채널은 0..1로 자릅니다. `Paint::Role`은 매 프레임 그때의 테마로 풀어서 보간하므로, 재생 중에 테마가 바뀌면 다음 프레임부터 새 테마의 색을 향합니다.
- `Alpha`는 보간 뒤 0..1로 자릅니다(베지어가 범위를 넘을 수 있습니다).
- CSS `linear()` 시간 함수는 Host가 그 꺾임점마다 키프레임을 넣어 `Linear` 구간들로 바꿔 보냅니다. 함수 목록과 색과 `Alpha`에서는 결과가 같습니다. `Matrix`를 포함한 목록에서는 분해 보간이 구간마다 다시 시작되므로 아주 작게 다를 수 있고, 그 차이는 수용 기준 4의 허용치로 잽니다.

**시간 모델.** Web Animations Level 1의 시간 모델을 그대로 따릅니다. 앞(before), 활성(active), 뒤(after) 단계, 활성 길이(`duration × iterations`, 0 × ∞ = 0), 반복 진행과 방향, `steps()`의 before flag까지 같습니다. 렌더러는 `(레코드, 시각) → 값`인 순수 함수 하나로 이것을 구현하고, Compose의 `AnimationSpec`(`tween`, `repeatable`)에 맡기지 않습니다. `repeatable`은 소수 반복 횟수(2.5회), fill 모드, 음수 지연, 점프 항을 나타낼 수 없기 때문입니다. 시계는 Compose의 프레임 클록(`withFrameNanos`)이고, 베지어는 Compose의 `CubicBezierEasing`을 씁니다. "Compose 애니메이션으로 재생"은 이 뜻입니다. 프레임 클록과 레이어 상태는 Compose의 것이고, CSS의 시간 규칙은 그 위의 함수 하나입니다.

**열쇠와 겹침: `(node_id, property, slot)`.**
- 같은 열쇠로 `StartAnimation`이 다시 오면 앞의 것을 버리고 새것으로 바꿉니다. 이벤트는 오지 않습니다. 바꾼 것은 Host이므로 `transitioncancel`은 Host가 스스로 냅니다.
- `slot`은 한 속성에 여러 애니메이션이 동시에 걸리는 CSS의 경우를 위한 것입니다. 효력이 있는(활성이거나 fill로 값을 붙들고 있는) 것 중 `slot`이 가장 큰 것의 값이 보이고, 효력이 있는 것이 없으면 바탕값이 보입니다. CSS에서 animation이 transition을 덮고(transition은 slot 0, animation은 1부터), `animation-name` 목록의 뒤쪽이 앞쪽을 덮는 것(`animation-composition: replace`)이 이것으로 그대로 됩니다. Host가 끝나는 시점마다 다시 보낼 필요가 없습니다.
- 열쇠를 노드와 속성 둘로만 두면 이 경우마다 Host가 끝 이벤트를 받아 다음 것을 다시 보내야 하고, 그 사이 한 프레임 동안 바탕값이 비칩니다. 그래서 1바이트를 더했습니다.

**FromPresented.** 첫 키프레임의 `flags` 비트 0이 켜져 있으면 그 키프레임의 값은 무시하고, 새 애니메이션이 시작하는 프레임에 그 노드·속성에서 **보이고 있던 값**(다른 slot의 애니메이션 값이든 바탕값이든)을 씁니다. CSS transition이 진행 중에 목표가 바뀌면 지금 보이는 값에서 새 transition이 시작하는데, 지금 보이는 값을 아는 것은 렌더러뿐이기 때문입니다. 이것으로 되돌림에 튐이 없습니다.
- CSS의 "되돌림 단축"(진행 중인 transition을 출발값으로 되돌릴 때 길이를 진행한 만큼 줄이는 규칙)은 Host가 길이를 줄여 보냅니다. Host는 자기가 보낸 레코드와 시작 시각(아래 `Ready` 이벤트, 또는 자기가 정한 시각)을 알고, 되돌리는 시각을 마지막 `render_frame`의 `frame_time_nanos`에서 자기 단조 시계로 경과한 만큼 더해 추정합니다. 추정 오차는 한 프레임 이하이고, 튐은 FromPresented가 막으므로 오차는 길이에만 나타납니다.

**시작 시각.**
- `start_time_nanos`는 렌더러 프레임 클록의 값입니다. `render_frame`의 `frame_time_nanos`와 같은 단조 시계입니다(PR-3 개정의 그 시계).
- 0이면 렌더러가 이 배치를 처음 그리는 프레임의 시각으로 정합니다. CSS의 transition과 animation이 시작하는 방식(스타일이 바뀐 다음 프레임)과 같습니다. Host가 그 값을 알아야 하면 `Ready` 이벤트를 요청합니다.
- 0이 아니면 그 시각에 시작한 것으로 보고 그만큼 진행된 자리부터 그립니다. 끝난 애니메이션을 다시 보내 이어 가거나(위의 백분율 이동), 여러 노드를 같은 박자로 맞출 때 씁니다.

**재생 상태와 시각 정지.** CSS `animation-play-state: paused`로 처음부터 멈춘 애니메이션은 `play_state = Paused`로 보냅니다. 그 경우 지연 전(앞 단계)에서 멈춰 있습니다.

#### `ControlAnimation` (Mutation 태그 19)

```
24바이트
offset  0  tag: u16 = 18            offset  2  len: u16 = 24
offset  4  node_id: u32
offset  8  animation_id: u32
offset 12  property: u8             offset 13  slot: u8
offset 14  op: u8                   Pause=1, Resume=2, Cancel=3
offset 15  reserved: u8
offset 16  at_time_nanos: u64       0이면 이 배치가 처음 그려지는 프레임
```

- `Pause`는 그 시각의 진행에서 멈춥니다. `Resume`은 멈춘 만큼 시작 시각을 뒤로 밀고 이어 갑니다. Host는 같은 계산으로 보이는 값을 언제든 다시 셀 수 있습니다.
- `Cancel`은 표에서 지웁니다. 바탕값(또는 다른 slot)이 다음 프레임부터 보입니다. 이벤트는 오지 않습니다.
- 열쇠가 표에 없거나 `animation_id`가 지금 것과 다르면 아무 일도 하지 않습니다. fill 없이 이미 끝나 지워진 애니메이션을 Host가 뒤늦게 멈추거나 취소하는 것은 정상 경로라 오류가 아닙니다.
- 노드가 `Remove`되면 그 노드의 애니메이션이 모두 지워지고 이벤트는 오지 않습니다. `Move`는 영향이 없습니다(열쇠가 노드 id입니다).

#### 바탕값의 나중 `SetModifier`

- 같은 속성의 바탕값을 바꾸는 `SetModifier`(또는 `Color`의 `SetProp`)는 **애니메이션을 취소하지 않습니다.** 바탕값만 바꿉니다. 애니메이션이 효력을 가진 동안 화면은 그대로이고, 효력이 끝나면(fill이 없어 끝나거나 취소되면) 새 바탕값이 보입니다. CSS의 cascade와 같습니다.
- `fill: Forwards`나 `Both`로 끝 값을 붙들고 있는 애니메이션은 바탕값이 바뀌어도 계속 끝 값을 보입니다. CSS와 같고, 풀려면 Host가 `Cancel`합니다.
- CSS transition의 보통 흐름은 이렇습니다. 속성이 바뀌면 Host는 새 바탕값의 `SetModifier`와 `StartAnimation`(첫 키프레임 FromPresented 또는 앞 값, 마지막 키프레임 새 값, fill 없음, slot 0)을 같은 배치에 보냅니다. 재생이 끝나면 애니메이션이 사라지고 같은 값인 바탕값이 이어서 보이므로 경계에서 튐이 없습니다.
- `Transform`의 바탕 수정자가 애니메이션 도중 바뀌어도 애니메이션의 원점은 레코드의 원점입니다.

#### Host가 보는 것: `AnimationEvent` (이벤트 태그 31)

`events` 비트로 요청한 것만 옵니다. 0이면 재생이 끝날 때까지 Host로 가는 호출이 하나도 없습니다.

| 비트 | 종류 | 언제 | CSS 이벤트 |
|---|---|---|---|
| 1 | `Ready = 1` | 시작 시각이 정해진 프레임(`start_time_nanos = 0`일 때) | (Host가 시각을 알기 위한 것) |
| 2 | `Active = 2` | 앞 단계에서 활성 단계로 들어간 프레임(지연이 끝남. 음수 지연이면 첫 프레임) | `transitionstart`, `animationstart` |
| 4 | `Iteration = 3` | 반복 번호가 바뀐 프레임. 한 프레임에 여러 번 넘어가도 한 번 | `animationiteration` |
| 8 | `End = 4` | 활성 단계가 끝난 프레임 | `transitionend`, `animationend` |

```
40바이트
offset  0  tag: u16 = 26            offset  2  len: u16 = 40
offset  4  node_id: u32             offset  8  handler_id: u64 = 0
offset 16  animation_id: u32
offset 20  kind: u8                 offset 21  property: u8
offset 22  slot: u8                 offset 23  reserved: u8
offset 24  iteration: u32           그 시점의 반복 번호
offset 28  elapsed_ms: f32          CSS 이벤트의 elapsedTime과 같은 값(지연 제외, 활성 시간 기준)
offset 32  time_nanos: u64          그 사건이 일어난 프레임 시각(Ready면 정해진 시작 시각)
```

- 사건은 프레임마다 값을 계산한 뒤, 그리기 전에 `dispatch_event`로 하나씩 보냅니다(PR-1). 돌아온 배치는 같은 프레임 안에서 적용되므로, `animationend` 핸들러가 다음 애니메이션을 시작해도 한 프레임이 비지 않습니다.
- `transitionrun`과 `transitioncancel`, `animationcancel`은 Host가 스스로 압니다(자기가 보내거나 지운 것). 그래서 렌더러에서 오지 않습니다.
- 창이 멈춰 프레임 클록이 서 있다 돌아오면 첫 프레임에 그 사이의 사건이 합쳐서 옵니다(`Iteration` 한 번, `End` 한 번).

#### 프레임과 비용

- 렌더러는 표에 활성이거나 앞 단계인 애니메이션이 하나라도 있으면 프레임을 계속 요청하고, 모두 끝나거나 fill로 멈춘 값만 남으면 더 요청하지 않습니다. 멈춘 화면은 프레임을 만들지 않습니다.
- 값은 그 순간 구성되어 있는 노드에만 씁니다. `LazyColumn` 창 밖으로 나간 노드의 애니메이션은 표에 남고, 다시 들어오면 그 시각의 값으로 나타납니다. 시간 기반이라 따로 맞출 것이 없습니다. 사건은 구성 여부와 무관하게 표에서 계산합니다.
- 프레임당 렌더러 작업: 애니메이션마다 순수 함수 한 번과 상태 쓰기 하나, `Transform`이면 특이값 분해 한 번. 정상 상태에서 힙 할당은 없습니다(레코드는 받을 때 미리 할당한 구조에 복사합니다).
- 프레임당 Host 작업: 0. 요청한 이벤트가 있을 때만 그 사건 수만큼 `dispatch_event`.
- 렌더러가 들고 있는 메모리: 애니메이션 하나당 받은 레코드 크기 정도(대략 100~500바이트).

#### 모션 감소: `ReducedMotionChanged` (이벤트 태그 32)

dioxus-compose FR-34.2 수용 기준 5(`prefers-reduced-motion`)는 Host가 미디어 쿼리를 계산하므로, Host가 시스템 설정을 알아야 합니다. 지금 스키마에는 그 값이 Host로 가는 길이 없습니다(FR-24는 렌더러 안에서만 씁니다). 이것 없이는 그 기준을 지킬 수 없으므로 여기에 같이 넣습니다.

- 이벤트 `ReducedMotionChanged`, `node_id = 0`, `handler_id = 0`, 페이로드 `state: u32`(`Off = 1`, `On = 2`, `Unknown = 3`), 20바이트. 모르는 값은 `ProtocolError`입니다.
- `NotificationPermissionChanged`(FR-36)와 같은 규칙입니다. 렌더러가 시작 후 한 번, 그리고 값이 바뀔 때만 보냅니다. Host API는 `use_reduced_motion()`입니다.
- 읽는 곳: macOS `NSWorkspace.accessibilityDisplayShouldReduceMotion`, iOS `UIAccessibility.isReduceMotionEnabled`, Windows `SPI_GETCLIENTAREAANIMATION`, GNOME `org.gnome.desktop.interface enable-animations`, KDE `AnimationDurationFactor`, Android `ANIMATOR_DURATION_SCALE`, Web `matchMedia('(prefers-reduced-motion: reduce)')`. FR-24가 이미 읽는 값이면 같은 값을 씁니다.
- **렌더러는 `StartAnimation`에 모션 감소를 적용하지 않습니다.** 무엇을 줄일지는 CSS(Host)가 정합니다. FR-24의 `Motion` 역할은 지금처럼 렌더러가 `Instant`로 해석합니다.

수용 기준:
1. `StartAnimation`(네 속성 각각, `Transform`은 다섯 함수 종류 각각과 함수 셋짜리 목록), `ControlAnimation`, `AnimationEvent`, `ReducedMotionChanged`가 Rust와 Kotlin에서 같은 바이트로 인코딩되고 디코딩됩니다(체크인된 프로토콜 벡터, fr41_start_animation_round_trips 등).
2. 다음은 모두 `ProtocolError` 이벤트가 되고 프로세스가 중단되지 않습니다: 모르는 열거값, 키프레임 2개 미만, 오프셋이 0에서 시작하지 않거나 1로 끝나지 않거나 줄어드는 것, 음수 길이, NaN 또는 음수 반복 횟수, 키프레임마다 다른 변환 함수 목록, `len`과 맞지 않는 키프레임 수, 배치를 다 적용한 뒤에도 바탕 수정자가 없는 대상, `color` 속성이 없는 위젯의 `Color`.
3. 시간 모델 함수의 표 테스트: `linear`, `ease`(0.25, 0.1, 0.25, 1)의 0.5 지점, `steps(4)`의 네 점프 항 각각의 경계 값(before flag 포함), 반복 2.5회와 네 방향, 음수 지연, 네 fill 모드의 앞·뒤 단계 값, 무한 반복, 길이 0. 기대값은 Web Animations Level 1의 식으로 계산해 테스트에 적습니다.
4. 렌더러 테스트(테스트 시계, `mainClock.autoAdvance = false`): 바탕 `Alpha(0.2)`에 0에서 1로 가는 1000ms `linear`, 지연 200ms, fill 없음을 걸면 100ms에 0.2, 700ms에 0.5, 1300ms에 0.2입니다. fill `Backwards`면 100ms에 0, `Forwards`면 1300ms에 1입니다. 같은 방식으로 `Background`(sRGB 사전곱 중간색), `Color`, `Transform`(`rotate(0)`에서 `rotate(720deg)`, 500ms에 360도가 아니라 정확히 한 바퀴 돈 모양이고, 250ms에 180도)를 확인합니다. 값은 노드의 그리기 결과와 `boundsInRoot`로 읽습니다. `Matrix` 보간과 `linear()` 분할은 꼭짓점 기준 0.01dp 안입니다.
5. 1초짜리 `Alpha`와 `Transform` 애니메이션이 60프레임 도는 동안, 이벤트를 요청하지 않았으면 Host의 `render_frame`과 `dispatch_event`가 한 번도 불리지 않습니다. `Active`와 `End`를 요청했으면 정확히 두 번 불립니다(가짜 Host의 호출 횟수).
6. 무한 반복 `Alternate` 애니메이션에서 `Iteration`이 반복 경계마다 한 번 오고, 프레임 하나가 여러 경계를 넘어도 한 번 옵니다. 반복 3회짜리는 `End`가 정확히 한 번 오고 `elapsed_ms`가 활성 길이와 같습니다. 요청하지 않은 종류는 오지 않습니다.
7. 재생 중에 같은 속성의 바탕값 `SetModifier`가 와도 보이는 값이 바뀌지 않고, fill 없이 끝난 뒤에 새 바탕값이 보입니다. fill `Forwards`면 바탕값이 바뀌어도 끝 값이 남고, `Cancel` 뒤에 새 바탕값이 보입니다.
8. 같은 열쇠로 FromPresented를 켠 `StartAnimation`을 재생 중간에 보내면, 새 애니메이션의 첫 프레임 값이 직전 프레임 값에서 한 프레임 분의 변화 이상 튀지 않습니다.
9. slot 0의 transition과 slot 1의 animation이 겹치면 slot 1이 보이고, slot 1이 fill 없이 끝난 다음 프레임부터 slot 0이 보입니다.
10. `Pause`한 애니메이션은 테스트 시계를 500ms 진행해도 값이 그대로이고, `Resume` 뒤에는 멈춘 시간만큼 늦게 끝납니다. `Remove`한 노드의 애니메이션은 표에서 사라지고 이벤트가 오지 않습니다. 없는 열쇠에 대한 `ControlAnimation`은 아무 일도 하지 않습니다.
11. `Alpha`, `Background`, `Transform`, `Color` 애니메이션이 도는 동안 그 노드의 recomposition 횟수와 측정 횟수가 0입니다(계측 테스트).
12. `Transform` 애니메이션이 도는 동안의 적중 판정이 그 프레임에 보이는 모양을 따릅니다(FR-41.1 수용 기준 5를 애니메이션 중간 시각에서 반복).
13. **CSS transition 하나가 배치 하나입니다.** 속성 하나의 transition을 시작하는 갱신은 `StartAnimation` 하나가 든 배치 하나로 나가고, 재생이 끝날 때까지 그 애니메이션 때문에 나가는 배치는 0입니다. 프레임마다 내는 배치가 없습니다(가짜 Renderer가 받은 배치 수와 레코드 수로 확인).
14. 재생 중에 테마의 명암을 바꾸면 `Paint::Role` 키프레임의 색이 다음 프레임부터 새 테마 값을 향합니다.
15. 애니메이션 100개가 도는 정상 상태에서 렌더러의 프레임당 힙 할당이 0바이트입니다(`BoundaryCostTest`와 같은 방식으로 실측). 모두 끝난 뒤에는 프레임이 더 요청되지 않습니다.
16. 노드 1000개가 동시에 `Transform` 애니메이션을 도는 화면의 프레임 시간이 NFR-9 예산 안이고, 수치를 이 항목에 기록합니다.
17. `ReducedMotionChanged`가 시작 후 한 번 오고, 시스템 설정을 바꾸면 한 번 더 옵니다. 모션 감소가 켜져 있어도 `StartAnimation`은 레코드대로 재생되고, `Motion` 역할은 한 프레임 안에 끝납니다. 시스템 설정 변경은 데스크톱 세 플랫폼에서 수동으로 확인하고 그렇게 적습니다.

---

### FR-41.3 3D 변환 (`Draft`, 다음 단계)

`rotateX`, `rotateY`, `perspective`, `matrix3d`처럼 2D 아핀 레코드로 나타낼 수 없는 변환입니다. 다음 단계에서 구현합니다. 지금 정해 둔 것은 다음과 같습니다.
- 값은 4x4 행렬 수정자(64바이트, 넓은 `SetModifier` 규칙으로 `k = 6`)입니다.
- `graphicsLayer`는 `rotationX`/`rotationY`와 `cameraDistance`까지 안정 API로 그립니다. 임의의 4x4 원근 행렬은 안정 API 밖이므로, 그 부분은 어댑터 한 파일에 가두고 버전을 고정합니다(NFR-6).
- 수용 기준은 이 항목을 구현할 때 적습니다. 그전까지 Host는 3D 변환을 2D로 근사해 보내지 않습니다. dioxus-compose는 그 변환을 적용하지 않고 개발자 경고를 냅니다. 틀린 근사보다 경고가 낫기 때문입니다.

### FR-41.4 compose-rust 작성 API (`Agreed`)

FR-39의 compose-rust API가 같은 레코드를 씁니다. Compose의 이름을 따릅니다(PR-7).
- `Modifier.graphics_layer { ... }`: 회전, 크기, 이동, 원점, 투명도를 받아 FR-41.1의 `Transform`과 `Alpha`로 내려갑니다.
- `animate_float_as_state`, `animate_color_as_state`, `animate_offset_as_state` 같은 `animate_*_as_state`: 목표값이 바뀌면 `StartAnimation` 하나를 내고, 재생은 렌더러가 합니다. 반환값은 목표값입니다. 재생 중의 값은 Host가 보지 않습니다(D5).
- 모션 역할(FR-24)이 기본이고, 길이와 곡선을 직접 주는 것은 탈출구라는 관계는 같습니다. `animate_*_as_state`는 길이와 곡선을 주지 않으면 `MotionRole::Standard`로 해석합니다.
- 수용 기준:
  1. compose-rust API로 쓴 `animate_float_as_state` 투명도 전환이, 같은 값을 직접 보낸 `StartAnimation`과 같은 레코드를 냅니다(fr41_compose_api_emits_the_same_records).
  2. 재생하는 동안 Host의 recomposition이 0번입니다(fr41_compose_api_animation_does_not_recompose).

#### 비용

- **와이어.** 수정자 태그 하나(26)와 넓은 `SetModifier` 규칙(Transform 44바이트, 기존 수정자는 28바이트 그대로). Mutation 태그 둘(18, 19), 이벤트 태그 둘(31, 32). 스키마 해시가 바뀝니다. 경계 진입점(PR-2)은 늘지 않습니다.
- **레코드 크기.** 2키프레임 기준으로 `Alpha` 108바이트, `Color`/`Background` 116바이트, 함수 하나짜리 `Transform` 164바이트(머리 52 + 키프레임 둘). 애니메이션이 시작할 때 한 번 나갑니다. 매 프레임 `SetModifier`를 보내는 길(가)이었다면 `Alpha` 하나에 60프레임 동안 28바이트 × 60 = 1680바이트(`Transform`이면 44바이트 × 60)와 Host 스타일 계산 60번이었습니다.
- **코드젠.** 지금 코드젠은 고정 레이아웃 레코드만 생성합니다. 키프레임 배열과 그 안의 변환 함수 배열(반복 그룹)을 생성하는 일이 새로 생깁니다. 경계 코드는 손으로 쓰지 않는다는 제약 때문에 이 비용은 피할 수 없습니다. 넓은 수정자 슬롯도 코드젠에 들어갑니다.
- **렌더러.** 두 겹 레이어 분해(한 파일), 애니메이션 표, Web Animations 시간 모델 함수, `steps()`, 색 공간 둘의 보간, 변환 함수 보간과 2D 행렬 분해, 이벤트 보내기, 모션 감소 설정 읽기(플랫폼 일곱). `Background`와 `Text` 색을 그리기 단계에서 읽도록 바꾸는 일.
- **Host.** compose-rust 쪽은 레코드를 쓰는 함수(`start_animation`, `control_animation`)와 `use_reduced_motion()`뿐입니다. CSS를 레코드로 옮기는 일(목록 맞추기, 원점 접기, 되돌림 단축, `linear()` 분할)은 dioxus-compose의 몫입니다.
- **디자인 시스템.** 늘지 않습니다.

#### 열린 질문 (기술, 설계자들이 정합니다)

1. **글 안의 스팬 색.** `<span>`의 `color` transition은 `Text` 안의 스팬(FR-26) 색이라 `(node, property, slot)`으로 가리킬 수 없습니다. 열쇠에 스팬 번호를 더하는 안(`Color` 대상만, 4바이트)이 있습니다. 블록 요소의 `color`는 Host가 자손 `Text` 노드마다 하나씩 보내면 되지만, 노드가 많으면 레코드가 그만큼 늡니다.
2. **`animation-composition: add`/`accumulate`.** slot은 `replace`만 나타냅니다. 필요하면 머리의 예약 바이트에 합성 방식을 넣을 수 있습니다.
3. **변환이 스크롤 영역에 주는 영향.** CSS는 변환된 상자의 경계를 스크롤 가능한 넘침 영역에 넣지만, Compose의 스크롤은 레이아웃 크기만 봅니다. Host(blitz)가 넘침 영역을 알므로 스크롤 내용의 크기를 그만큼 잡아 보내는 안이 있습니다. dioxus-compose 쪽 결정입니다.
4. **터치 대상 확장.** Compose는 48dp보다 작은 노드의 터치 적중 영역을 넓힙니다(`minimumTouchTargetSize`). CSS에는 없는 동작이라 터치 입력에서는 Chromium과 판정이 다를 수 있습니다. 수용 기준 5는 마우스로 잽니다.
5. **번호.** Mutation 18, 19, 이벤트 31, 32는 FR-38(CodeEditor)이 17과 26~30을 가져간 뒤의 다음 빈 번호입니다(2026-10-03 다시 매김). 수정자 26은 HTML/CSS 원소 초안의 19~25 다음 번호입니다. 다른 초안이 먼저 가져가면 설계자가 다시 매깁니다.

### FR-42 HTML/CSS 화면을 그리는 원소 (`Agreed`)

요청한 쪽: dioxus-compose FR-34(M12, 10-15). Host(`blitz-dom`)가 계산한 상자와 텍스트를 Compose로 그리려면, 계산된 결과를 그대로 놓을 수 있는 원소가 필요합니다. M10 측정(dioxus-compose PR #28)에서 상자 배치는 Chromium과 정확히 같았고 텍스트 폭은 허용치를 넘었습니다. 그래서 **상자는 Host가 계산한 자리에 놓고, 텍스트의 크기는 Compose가 잽니다**(PR-2.1). 소유자 승인 2026-10-03.

번호: `AbsoluteBox` 위젯 44, 수정자 `Offset` 19, `RequiredSize` 20, `BorderEach` 21, `CornerEach` 22, `Shadow` 23, `Clip` 24, `Alpha` 25. CSS 변환은 FR-41이 다룹니다.

#### 42.1 원칙: 새 위젯보다 새 수정자

dioxus-compose의 결정(HTML 경로의 원소 조건) 가운데 하나가 "기존 원소로 표현할 수 있는 것은 새로 만들지 않는다"입니다. CSS 상자는 대부분 지금 있는 `Box`, `Text`, `Image`, `ScrollColumn`/`ScrollRow`에 수정자를 붙인 것입니다. 이 초안이 더하는 것은 수정자가 대부분이고, 위젯은 하나입니다.

#### 42.2 새 위젯: `AbsoluteBox` (태그 44)

- 자식들을 각자의 `Offset` 자리에 놓는 컨테이너입니다. Compose의 `Box`에 자식마다 `Modifier.offset`을 준 것과 같고, 자식끼리 서로의 자리에 영향을 주지 않습니다.
- `blitz-dom`이 이미 모든 상자의 자리를 계산했으므로, Compose가 다시 배치 규칙을 적용하면 안 됩니다. `Column`/`Row`/`Box`는 각자의 배치 규칙이 있어서 이 용도에 맞지 않습니다.
- 자식의 그리는 순서는 자식 순서입니다(뒤의 자식이 위). CSS의 `z-index`와 쌓임 맥락은 Host가 자식 순서로 풀어서 보냅니다.
- 이름은 Compose에 같은 개념의 안정 API 이름이 없어서 개념 이름을 씁니다. 다른 이름(`Layout`, `Canvas`의 변형)은 이미 다른 뜻이 있습니다.

#### 42.3 새 수정자

| 수정자 | 값 | Compose 대응 | CSS |
|---|---|---|---|
| `Offset` | x, y (dp, 부모 기준) | `Modifier.offset` | 계산된 상자 위치 |
| `RequiredSize` | w, h (dp) | `Modifier.requiredSize` | 계산된 상자 크기. 부모 제약을 무시합니다 |
| `BorderEach` | 변마다 두께와 `Paint` | 테두리 네 개를 그리는 그리기 수정자 | `border-top` 등이 서로 다를 때 |
| `CornerEach` | 모서리마다 반지름 (dp) | `RoundedCornerShape(a, b, c, d)` | `border-radius` 네 값 |
| `Shadow` | x, y, blur, spread (dp), `Paint` | 그림자 그리기 수정자 | `box-shadow` 하나. 여럿이면 수정자를 여러 개 |
| `Clip` | 켜짐/꺼짐 | `Modifier.clip(shape)` | `overflow: hidden`. 같은 노드에 `CornerEach`가 있으면 그 둥근 모양으로 자르고, 없으면 사각형으로 자릅니다. CSS도 `border-radius`가 있는 상자의 `overflow: hidden`은 둥근 모양으로 자릅니다 |
| `Alpha` | 0..1 | `Modifier.alpha`(`graphicsLayer`) | `opacity`. 자식까지 한 묶음으로 합성한 뒤 한 번 적용합니다. 자식마다 따로 적용하지 않습니다. CSS `opacity`도 같은 방식입니다 |

- 기존 `Border`, `Shape`, `Elevation`은 그대로 둡니다. 네 변이 같으면 Host가 기존 `Border`를 씁니다.
- **와이어 길이.** `Shadow`(값 24바이트)와 `BorderEach`(48바이트)는 지금의 16바이트 수정자 칸에 들어가지 않습니다. FR-41.1이 정한 가변 길이 `SetModifier` 규칙(`28 + 8k`바이트, `k`는 수정자 태그마다 스키마에 고정)을 따라 `Shadow`는 `k = 1`, `BorderEach`는 `k = 4`입니다. 나머지 다섯은 `k = 0`이라 지금 레코드와 같습니다.
- 색은 `Paint`입니다. CSS 색은 리터럴이므로 `Paint::Literal`이 쓰입니다. 테마 변수(`--vscode-*`)를 색 역할로 옮기는 것은 dioxus-compose의 변환기 몫이고, 옮길 수 있는 것만 `Paint::Role`이 됩니다.
- `overflow: scroll`/`auto`는 새 수정자가 아니라 기존 `ScrollColumn`/`ScrollRow`(둘 다면 둘을 겹침)로 놓습니다. 스크롤 위치는 렌더러가 소유합니다(D5).
- 이미지(`<img>`, `background-image`)는 기존 `Image`와 브러시 에셋(FR-23)입니다.

#### 42.4 텍스트

- 텍스트 조각은 기존 `Text`(FR-26 스팬 포함)이고, `Offset`과 너비를 받습니다. 줄바꿈과 글자 위치는 Compose가 정합니다.
- 레이아웃 도중 텍스트 크기는 PR-2.1 측정 호출로 렌더러에 묻습니다(소유자 승인 2026-10-03). HTML 텍스트의 글자 모양 속성(글꼴 이름, `word-break`, `tab-size` 등)은 FR-40입니다. 그래서 측정과 그리기가 같은 글꼴 해석을 씁니다.

#### 42.5 갱신 비용

- 상자 하나의 배경색이 바뀌면 그 노드의 `Background` 수정자 한 건만 나갑니다(dioxus-compose FR-34 기준 4).
- 창 크기가 바뀌어 많은 상자의 자리가 바뀌면, 바뀐 상자마다 `Offset`/`RequiredSize` 한 건씩입니다. 수천 개의 상자가 한 번에 움직이는 경우의 프레임 시간은 NFR-9 예산으로 측정해 기록합니다.

#### 수용 기준

1. `AbsoluteBox` 안의 자식이 `Offset` 자리에 정확히 놓이고(오차 0dp), 서로의 크기에 영향을 주지 않습니다.
2. 새 수정자 일곱 가지가 각자 Compose 대응대로 그려지고, 데스크톱 렌더러에서 스크린숏 테스트로 확인됩니다.
3. 네 변이 같은 테두리와 하나의 반지름은 기존 수정자로 나가고, 새 수정자는 값이 서로 다를 때만 나갑니다.
4. 수정자 하나만 바뀐 갱신에서 그 노드의 그 수정자 말고는 아무것도 나가지 않습니다.
5. 이 원소들로 dioxus-compose FR-34 수용 기준 1의 세 구역 중 상자(텍스트 제외)가 그 상자 허용치를 지킵니다.

#### 비용

위젯 태그 하나(44), 수정자 일곱. 렌더러의 그리기 코드(테두리 네 변, 그림자). 디자인 시스템 규칙은 늘지 않습니다. CSS가 모양을 정하므로 디자인 시스템이 개입하지 않는 것이 이 원소들의 성격입니다.

열린 질문: `AbsoluteBox`라는 이름. 더 나은 개념 이름이 있으면 바꿉니다.

## 4. 경계 프로토콜

### PR-1 호출 모델: 동기·동일 스레드 직접 호출 (`Done`)

**검증 방식에 관하여(2026-09-22):** 이 항목에는 `pr1_`로 시작하는 테스트가 없습니다. 없어도 되는 종류입니다. 여기 적힌 것은 기능이 아니라 구조에 관한 금지이고("큐를 두지 않는다"), 구조는 그것을 어겼을 때 다른 데서 깨지는 방식으로 지켜집니다. 동기 반환값은 `dispatch_event`의 반환을 확인하는 테스트들이, 호출 비용은 `boundary_call_cost.rs`가 지키고 있습니다.
옛 React Native 브리지처럼 비동기 큐를 두면 병목이 생깁니다. 비동기 큐는 동기 반환값을 받을 수 없고, 스레드 홉 때문에 최대 1프레임 지연이 생깁니다. 그래서 JSI처럼 **같은 스레드에서 서로를 직접 호출**합니다.

- VirtualDom은 **Renderer의 UI 스레드에서** 돕니다. 이 스레드는 Host의 전용 스레드가 아닙니다.
- 사용자 입력이 들어오면 Renderer가 Host 핸들러를 직접 호출합니다. Host는 그 자리에서 핸들러를 실행하고 diff를 계산한 뒤, 결과 Mutation 배치와 반환값을 돌려줍니다.
- 동기 반환값을 지원합니다. 예: `onKeyEvent`의 "처리됨" 여부. Enter는 제출, Shift+Enter는 줄바꿈으로 나누는 처리가 여기에 해당합니다. 표현 방식은 FR-12를 따릅니다.
- 경계에 비동기 큐를 두지 않습니다. 스레드 간 통신은 PR-3의 wake 신호 하나뿐입니다.
- 측정 호출(PR-2.1)은 Renderer가 Host를 부른 호출 안에서 Host가 Renderer를 다시 부르는 **같은 스레드의 재진입**입니다(2026-10-03 소유자 승인). 이 호출 때문에 큐나 스레드가 생기지 않습니다.
- 수용 기준: 배치 버퍼가 큐가 아니라 한 호출의 인자입니다. 호출이 돌려준 배치는 그 호출 스택 안에서 소비되고, 다음 호출에는 남아 있지 않습니다(`pr4_the_batch_buffer_is_an_argument_and_not_a_queue`, `pr4_nothing_is_left_for_a_third_call`). **(통과)**
- 수용 기준: 핸들러의 동기 반환값이 그 호출의 반환으로 돌아옵니다(`fr12_key_consumption_is_returned_and_does_not_leak`). **(통과)**
- 수용 기준: Host의 초기화가 Renderer의 UI 스레드에서 돕니다(`pr3_init_runs_on_a_different_thread_than_launch`). **(통과)**

기준을 2026-09-22에 적었습니다. 이 항목은 기능이 아니라 구조에 관한 금지("큐를 두지 않는다")여서, 그것을 지키는 테스트들은 다른 요구사항의 이름을 달고 있습니다. 여기에 인용해 두는 편이, 테스트 수를 세다가 0을 보고 미구현으로 읽는 것보다 낫습니다.

### PR-2 경계 표면 (`Done`)
경계는 primitive, 포인터, 길이만 씁니다(GraalVM `@CEntryPoint` 제약). 함수는 호출 방향에 중립적인 **논리 연산**으로 정의하고, 플랫폼별 심은 코드젠(FR-7)이 생성합니다. 사람이 JNI나 cinterop 코드를 직접 쓰지 않습니다.

C 심볼은 Rust 쪽 관례(snake_case, 크레이트 이름 접두사)를 따릅니다. Kotlin 쪽 선언은 코드젠이 Compose 관례에 맞춰 생성합니다. 명명 규칙 전체는 PR-7에 있습니다.

Renderer → Host (Rust가 export):
```c
// 핸드셰이크: 스키마 해시, 버전, LoopMode를 교환하고 초기 트리 배치를 받습니다
int32_t compose_rust_host_init(const uint8_t* handshake, uint32_t len, MutationBatch* out);
// 이벤트 1건 처리: 핸들러를 실행한 뒤 diff를 계산하고, 결과 배치와 반환값을 돌려줍니다
int32_t compose_rust_host_dispatch_event(const uint8_t* event, uint32_t len, MutationBatch* out);
// 프레임 요청 이후 호출: 워커에서 온 상태 변경을 반영해 diff를 계산합니다
int32_t compose_rust_host_render_frame(uint64_t frame_time_nanos, MutationBatch* out);
void    compose_rust_host_release_batch(MutationBatch* batch);
void    compose_rust_host_shutdown(void);
```

Host → Renderer (Kotlin이 export):
```c
int32_t compose_rust_renderer_run(void);            // LoopMode::Renderer일 때만. 블로킹
void    compose_rust_renderer_request_frame(void);  // 스레드 안전. 다음 프레임에 render_frame 예약
// 측정: 요청 레코드 count개를 읽고 결과 레코드 count개를 채웁니다. UI 스레드에서만, 동기(PR-2.1)
int32_t compose_rust_renderer_measure(const uint8_t* requests, uint32_t len,
                                      uint32_t count, MeasureResult* results);
```

- `LoopMode`
  - `LoopMode::Renderer`: Desktop, iOS. Rust `main`에서 `dioxus_compose::launch(app)`가 `compose_rust_renderer_run`을 호출합니다.
  - `LoopMode::Platform`: Android, Web. 플랫폼이 루프를 소유하고, Renderer가 먼저 `compose_rust_host_init`을 호출합니다.
  - 사용자 코드는 `fn app() -> Element`뿐이라 두 모드에서 동일합니다.
- GraalVM에서는 진입점마다 `IsolateThread*`가 붙습니다. 코드젠이 이를 숨깁니다. isolate는 프로세스당 1개입니다.
- `dispatch_event`와 `render_frame`이 반환한 배치는 Renderer가 **같은 호출 스택 안에서** 적용하고 즉시 `release_batch`합니다. 배치를 쌓아 두는 큐는 없습니다.
- 배치 하나는 단일 스냅샷 트랜잭션(`Snapshot.withMutableSnapshot`)으로 적용합니다. 중간 상태가 화면에 그려져서는 안 됩니다.

**검증 (2026-09-21, macOS arm64 + iOS 시뮬레이터)**

데스크톱용 C 스모크 호스트(`desktop/c/smoke_host.c`)를 **한 글자도 고치지 않고** iOS 정적 아카이브에 링크해 실행했습니다. 같은 `main`, 같은 다섯 개 `compose_rust_host_*` 함수가 GraalVM native-image 공유 라이브러리와 Kotlin/Native 아카이브 양쪽에 그대로 붙습니다. 경계 표면이 두 런타임에서 하나라는 근거입니다.

```
compose_rust_host_init: 180 bytes, 8 records
compose_rust_host_dispatch_event: click 1
```

- 핸드셰이크와 초기 배치(180바이트, 8레코드)가 양쪽에서 바이트 단위로 동일합니다.
- 시뮬레이터에서 버튼을 탭하면 `dispatch_event`가 Rust 핸들러까지 도달합니다. `simctl`에 탭 명령이 없어 이 확인은 자동화되지 않습니다. `ios-smoke-test.sh --await-click`으로 사람이 실행합니다. CI는 이 플래그 없이 기동과 렌더링까지만 증명합니다.
- iOS에는 isolate가 없어 `@CName`이 공개 심볼을 Kotlin 함수에 직접 붙입니다. isolate 심이 하던 나머지 역할은 Kotlin/Native 런타임과 `NSThread.isMainThread` 검사가 대신합니다.
- Web은 검증되었습니다(PR-6의 검증 절). 같은 다섯 개 논리 연산이 브라우저에서도 그대로 서고, 초기 배치와 클릭 왕복이 공유 메모리 위에서 돕니다. **Android는 2026-09-22 API 36 에뮬레이터에서 확인했습니다.** 같은 다섯 연산이 생성된 JNI 심을 통해 서고, 화면이 그려지며, 워커의 프레임 요청이 경계를 넘어옵니다. 호출당 비용도 그 자리에서 쟀습니다(PR-5의 수용 기준 1).

### PR-2.1 측정 호출 (`Agreed`)

**무엇을 하나.** Host가 자기 레이아웃을 계산하는 도중에, 텍스트 한 덩이나 이미 보낸 위젯 하나가 주어진 제약 안에서 차지할 크기를 Renderer에게 묻습니다. Renderer는 **그릴 때와 같은 글꼴 해석, 같은 Density와 글꼴 배율, 같은 디자인 시스템 타입 스케일**로 재서 그 자리에서 돌려줍니다. 측정값과 그려진 크기가 같다는 것이 이 호출의 존재 이유입니다.

**한 번에 여러 개.** 호출 하나가 요청 N개를 싣습니다. 레이아웃 한 번에 텍스트 잎이 수백 개 나오고(사이드바 하나에 약 200개), Taffy는 같은 잎에 min-content, max-content, 정해진 폭을 차례로 묻습니다. 잎마다 경계를 건너면 그 비용이 쌓입니다. Host는 레이아웃 한 번에 이 호출을 여러 번 불러도 됩니다.

**요청 버퍼.** 배치 버퍼(PR-4)와 같은 형식입니다. 앞에 고정 길이 요청 레코드 `count`개가 있고, 그 뒤 페이로드 영역에 UTF-8 텍스트와 스팬 레코드가 놓입니다. 레코드는 페이로드를 `(offset, len)`으로 가리킵니다. serde 형식은 쓰지 않고, 레코드 레이아웃은 Rust 스키마에서 codegen이 만듭니다(FR-7).

요청 레코드는 종류 태그로 시작합니다:

- `MeasureText = 1`
  - `text: (offset, len)`. UTF-8이고, 공백 접기와 `text-transform`은 Host가 이미 적용한 최종 문자열입니다.
  - `spans: (offset, count)`. FR-26의 스팬 레코드를 그대로 씁니다. 크기에 영향을 주지 않는 칸(색, 링크 핸들러)은 무시합니다.
  - 기본 글자 모양:
    - `font: FontRef` (FR-40, `TypeRole::None`일 때만)
    - `type_role: TypeRole`. 노드 단위 글꼴, `TypeRole::None`, 그리고 아래 HTML 텍스트 칸(`tab_size`, `word_break`, `overflow_wrap`, `absolute_size`)이 그리기에도 같은 뜻으로 서는 것은 FR-40의 요구사항입니다. 측정과 그리기는 그 칸들을 같은 해석 함수로 풉니다.
    - `font_size: f32` (0이면 역할의 값)
    - `font_weight: u16` (0이면 역할의 값)
    - `italic: u8`
    - `letter_spacing: f32` (NaN이면 역할의 값)
    - `line_height: f32` (NaN이면 normal)
    - `max_lines: u32` (0이면 제한 없음)
    - `wrap: u8` (0은 줄을 바꾸지 않음. CSS `nowrap`, `pre`)
    - `tab_size: u8` (CSS `tab-size`, 기본 8). 탭은 다음 탭 위치까지 차지합니다.
    - `word_break: Normal | KeepAll | BreakAll`, `overflow_wrap: Normal | Anywhere | BreakWord`
    - `absolute_size: u8`. 1이면 시스템 글꼴 배율을 적용하지 않습니다(CSS px). 측정과 그리기에 똑같이 적용됩니다.
  - **Renderer는 받은 문자열의 공백을 자르거나 접지 않습니다.** 공백 접기(`normal`, `pre-line`)는 Host의 일입니다. `pre`는 그대로 보낸 문자열과 `wrap = 0`, `pre-wrap`은 그대로 보낸 문자열과 `wrap = 1`입니다.
  - 제약 `constraint: MinContent = 1 | MaxContent = 2 | AtMost = 3`과 `width: f32`(`AtMost`일 때만 씀). 결과는 다음과 같습니다.
    - `MinContent`: 가장 긴 끊을 수 없는 조각의 폭. Compose `ParagraphIntrinsics.minIntrinsicWidth`.
    - `MaxContent`: 줄을 바꾸지 않은 폭. `maxIntrinsicWidth`.
    - `AtMost(w)`: 폭 `w` 안에서 줄을 바꿨을 때의 크기.
- `MeasureNode = 2`
  - `node: NodeId`. Host가 이미 보냈고 Renderer가 이미 적용한 노드입니다.
  - 제약 `min_width, max_width, min_height, max_height: f32`. 제한 없음은 `+inf`입니다.
  - 그 노드의 하위 트리를 Compose 레이아웃이 주어진 제약에서 잴 때의 크기를 돌려줍니다.

**결과 레코드** (`MeasureResult`, 32바이트):
- `width, height: f32`
- `first_baseline, last_baseline: f32`. 기준선이 없으면 NaN입니다.
- `last_line_width: f32`. 마지막 줄의 폭으로, 그 뒤에 이어지는 인라인 내용을 놓는 데 씁니다. 노드면 NaN입니다.
- `line_count: u32`. 노드면 0입니다.
- `flags: u32`. 비트 0 `truncated`는 `max_lines` 때문에 글자가 잘렸다는 뜻입니다.
- `status: u32`

`status` 값:
- `Ok = 0`
- `UnknownNode = 1`: 아직 적용되지 않았거나 없는 노드
- `Malformed = 2`: 레코드가 버퍼 밖을 가리킴

단위는 레이아웃 속성과 같은 dp입니다.

**반환값.** 0이면 성공입니다. 레코드 하나가 잘못된 것은 그 레코드의 `status`로 알리고 나머지는 잽니다. 버퍼 전체를 읽을 수 없으면 음수를 돌려줍니다. 이때 Host는 `ProtocolError`를 내고(NFR-7) 그 레이아웃을 크기 0으로 계속합니다. 프로세스는 멈추지 않습니다.

**스레드와 재진입(PR-1, PR-3).**
- UI 스레드에서만 부릅니다. 보통은 Renderer가 Host를 부른 호출(`render_frame`, `dispatch_event`, `init`) 안에서 Host가 다시 Renderer를 부르는 재진입이고, 같은 스레드, 같은 호출 스택입니다. 큐도 스레드 홉도 없습니다.
- 다른 스레드에서 부르면 아무것도 재지 않고 음수를 돌려줍니다.
- 텍스트 측정은 composition 밖에서 돕니다. 현재 `Density`, `FontFamily.Resolver`, 디자인 시스템 타입 스케일로 만든 `TextMeasurer`를 씁니다.
- **같은 호출에서 아직 적용되지 않은 노드는 잴 수 없습니다.** 지금 계산 중인 배치는 Host가 돌려준 뒤에야 적용되기 때문입니다. 그런 노드는 `UnknownNode`가 됩니다. Host는 새로 만든 위젯을 한 프레임 동안 CSS 크기로 놓고 다음 프레임에 잴 수 있습니다.

**캐시.** Host는 `(텍스트, 모양, 제약)`마다 한 프레임 동안 결과를 캐시합니다. Renderer도 캐시할 수 있지만, 결과가 캐시에 의존해서는 안 됩니다.

**Rust 쪽 API.** 사용자 코드는 경계 함수를 직접 부르지 않습니다(PR-3). 어댑터와 compose-rust 런타임은 `compose_rust::measure`의 안전한 API로 부릅니다. 이 API는 Host의 프레임 작업 안에서만 측정기를 내주고, 그 밖에서는 `Err`를 돌려줍니다.

**플랫폼.** desktop(K/N과 native-image), iOS, Android, Web 모두 같은 논리 연산입니다. 심은 codegen이 만듭니다.
- Web(PR-6): 요청과 결과 버퍼가 공유 `WebAssembly.Memory` 안에 있고, Kotlin이 그 자리에서 읽고 씁니다.
- Android: 생성된 JNI 심이 UI 스레드에 이미 붙어 있는 `JNIEnv`로 부르고, 버퍼는 direct `ByteBuffer`로 감쌉니다. 복사하지 않습니다.
- 스키마 해시가 바뀝니다.

**테스트용 Renderer.** stand-in renderer와 mock renderer도 이 함수를 갖습니다. 결정적인 가짜 메트릭(글자당 고정 폭)을 돌려주므로, Rust 테스트가 실제 렌더러 없이 돌 수 있습니다.

### 수용 기준

1. **측정값과 그려진 크기가 같습니다.** 같은 텍스트와 모양을 `AtMost(w)`로 잰 결과와, Renderer가 폭 `w`의 `Text` 노드로 그린 결과의 너비, 높이, 첫 기준선, 줄 수가 비트 단위로 같습니다. 대상은 다음 네 가지입니다.
   - 라틴
   - 한국어(단어 경계 줄바꿈)
   - 이모지
   - 스팬이 섞인 문단

   마지막 줄 폭과 `truncated`도 같아야 합니다. 같은 기준을 FR-40의 칸에도 적용합니다. 한국어 `word_break`의 `Normal`과 `KeepAll`, 탭이 든 `pre` 문자열, `absolute_size` 문자열입니다.

   (pr2_measured_text_matches_the_drawn_text, Kotlin 측 테스트)
2. `MinContent`와 `MaxContent`가 Compose `ParagraphIntrinsics`의 `minIntrinsicWidth`, `maxIntrinsicWidth`와 같습니다(pr2_intrinsic_widths_match_compose).
3. 이미 적용된 노드를 잰 크기가, 같은 제약에서 Compose 레이아웃이 그 노드에 준 크기와 같습니다. 대상은 `Button`, `TextField`, `Column` 안의 `Text` 둘입니다(pr2_measured_node_matches_its_layout).
4. 아직 적용되지 않은 노드는 `UnknownNode`를 받고, 같은 호출의 나머지 요청은 정상으로 잽니다(pr2_unknown_node_is_reported_per_record).
5. 재진입:
   - `render_frame` 안에서 Host가 측정을 부르면 같은 스레드, 같은 호출 스택에서 답을 받습니다(pr1_measure_is_answered_inside_the_host_call).
   - 다른 스레드에서 부르면 음수를 받고, 프로세스는 계속 돕니다(pr3_measure_off_the_ui_thread_is_refused).
6. 잘못된 버퍼는 `ProtocolError`가 되고 프로세스를 멈추지 않습니다(nfr7_malformed_measure_buffer_is_a_protocol_error).
7. 비용(NFR-9):
   - 사이드바 하나 분량(텍스트 잎 200개, 잎마다 min, max, 정해진 폭 세 번, 캐시가 빈 상태)을 재는 벤치마크를 둡니다.
   - 같은 600건을 Kotlin에서 `TextMeasurer`로 직접 잰 시간 대비, 경계를 건넌 측정의 추가 비용이 10% 이하입니다.
   - 그 절대 시간을 플랫폼별로 기록합니다.
   - (`benches/measure_sidebar`, 수치는 이 항목 아래에 기록)
8. 다섯 플랫폼(macOS, Linux, Windows, iOS, Android)과 Web에서 기준 1의 라틴 경우가 통과합니다. CI 스모크로 확인합니다.

### PR-3 스레드 규칙 (`Done`)
- VirtualDom, 사용자 컴포넌트, 모든 `compose_rust_host_*` 호출은 Renderer UI 스레드에서만 실행합니다. 그래서 락이 필요 없습니다.
- **UI 스레드에서 도메인 작업을 금지합니다.** 네트워크, 파일 I/O, 프로세스 관리 같은 작업은 Host 워커 스레드(tokio 등)에서 돌립니다. 워커는 Dioxus signal로 상태를 갱신하고, Host가 내부에서 `request_frame`을 호출합니다. 사용자 코드는 경계 함수를 직접 부르지 않습니다.
- `request_frame`은 여러 번 불러도 다음 프레임에 `render_frame` 1회로 합쳐집니다. Compose frame clock(`withFrameNanos`)이 돌고 있으면 그 안에서 실행됩니다.
- **프레임 클록이 멈춰 있으면 Renderer가 `render_frame`을 직접 한 번 부릅니다** (2026-10-03 개정, `Draft`). 창이 최소화되거나 가려지거나 앱이 백그라운드로 가면 Compose의 프레임 클록이 멈출 수 있고, 그때 온 요청을 다음 그리기까지 미루면 워커가 낸 결과(FR-36의 알림이 대표적입니다)가 사용자가 창을 보지 않는 동안 나가지 않습니다. 그래서 그 동안 온 `request_frame`에 대해 Renderer는 UI 스레드에 작업 하나를 올리고, 그 안에서 `render_frame`을 한 번 부른 뒤 돌아온 배치를 같은 호출 스택 안에서 적용합니다. 여러 요청이 한 번으로 합쳐지는 것은 같고, `frame_time_nanos`는 프레임 클록과 같은 단조 시계의 현재 값입니다.
  - 바뀌는 것은 Renderer가 호출을 예약하는 방법뿐입니다. 경계 함수, 스레드 간 wake 신호 하나(PR-1), VirtualDom이 UI 스레드에서만 돈다는 규칙은 그대로이고, 새 진입점도 큐도 없습니다.
  - 수용 기준: 창이 최소화되어 프레임 클록이 멈춘 동안 워커가 낸 요청이 1초 안에 `render_frame` 한 번으로 처리되고, 그 배치가 적용됩니다. 클록이 다시 돌면 요청은 이전처럼 클록 안에서 처리됩니다. **(미구현)**
- macOS에서 `compose_rust_renderer_run`은 프로세스 메인 스레드에서 호출해야 합니다(AppKit 요구사항).
- Android: Host 워커 스레드는 `request_frame`을 부르기 위해 JavaVM에 **1회 영구 attach**합니다. 호출마다 attach하는 것은 금지합니다. `@FastNative`/`@CriticalNative`는 짧은 호출에만 허용합니다.
- 프레임 예산은 NFR-9를 따릅니다.
- 수용 기준: 여러 번의 `request_frame`이 다음 프레임의 `render_frame` 한 번으로 합쳐집니다(`pr3_frame_request_applies_exactly_one_frame_batch`). **(통과)**
- 수용 기준: 워커의 요청이 Compose의 프레임 클록 안에서 처리됩니다(`pr3_a_frame_request_reaches_the_frame_clock`). **(통과)**
- 수용 기준: 프레임 사이에 놓친 요청이 다음 요청을 삼키지 않습니다(`pr3_a_missed_frame_request_does_not_silence_the_next`). **(통과)**
- 수용 기준: Host의 초기화가 `launch`를 부른 스레드가 아니라 Renderer의 UI 스레드에서 돕니다(`pr3_init_runs_on_a_different_thread_than_launch`). **(통과)**
- Android의 워커 attach는 2026-09-22 에뮬레이터에서 간접 확인했습니다. 스트리밍이 초당 100회 신호를 보내는 동안 화면이 계속 갱신되었고, 호출마다 attach했다면 그 비용이 드러났을 것입니다. 직접 계측은 하지 않았습니다.

기준을 2026-09-22에 적었습니다. 그 전까지 이 항목에는 수용 기준이 없었고, 네 테스트는 그보다 먼저 있었습니다.

### PR-4 배치 버퍼와 인코딩 (`Done`)
원칙: **같은 프로세스 안이므로 직렬화, 복사, 경계 호출 횟수를 최소화합니다.** 버퍼는 큐가 아니라 **한 번의 호출에서 Mutation 여러 개를 넘기는 인자**입니다.

- `#[repr(C)] struct MutationBatch { ptr: *const u8, len: u32, result: i64 }`
  - Host가 소유하는 arena를 가리키며, 프레임마다 재사용합니다.
  - `result`에는 핸들러의 동기 반환값이 들어갑니다.
- **인코딩**: 고정 레이아웃 바이너리로, 제자리에서 읽습니다(zero-copy). 디코드 단계를 두지 않습니다.
  - 레코드: `tag: u16`, `len: u16`, 뒤이어 고정 필드(`node_id: u32` 등)가 오며, 리틀 엔디언이고 4바이트 정렬입니다.
  - 문자열: 같은 arena에 두고 `(offset: u32, len: u32)`로 참조합니다. UTF-8입니다. Renderer는 Compose에 넘기는 시점에만 `String`으로 변환합니다.
  - postcard, bincode, FlatBuffers는 쓰지 않습니다. 레코드 레이아웃과 접근자는 FR-7 코드젠이 생성합니다.
- **이벤트 태그** (Renderer→Host): 입력 이벤트(FR-3), `TextChanged`/`TextSubmitted`/`FocusLost`(FR-5), `RangeRequested`(FR-8), `Lifecycle(Start|Stop)`, `Resync`, `SaveState`/`RestoreState`(선택), `ProtocolError`
- **플랫폼별 메모리 접근**
  - Desktop(GraalVM): `Pointer`로 직접 읽습니다.
  - iOS(Kotlin/Native): `CPointer`로 읽습니다.
  - Android: `NewDirectByteBuffer`로 arena를 감싸서 읽습니다.
  - Web: 공유 linear memory(PR-6)
- 수용 기준: 텍스트 하나를 바꾸는 이벤트 처리에서 경계 호출 2회(`dispatch_event`, `release_batch`), 힙 할당은 Compose `String` 생성 1회 이하

수용 기준 점검(2026-09-22, 디코더 수정 후 재측정):
- **경계 호출 2회: 충족.** `boundary_call_cost.rs`가 C export를 그대로 불러 확인합니다. `dispatch_event`가 돌아온 시점에 diff와 핸들러 반환값이 이미 out 인자에 들어 있고(`pr4_a_text_change_costs_two_boundary_calls`), 바로 뒤에 부른 `render_frame`은 레코드를 하나도 싣지 않으며(`pr4_nothing_is_left_for_a_third_call`), 연속한 키 입력 세 번이 같은 arena에 쓰입니다(`pr4_the_batch_buffer_is_an_argument_and_not_a_queue`). 세 번째 호출이 필요해지면 셋 중 하나가 실패합니다. Host 인코딩 쪽 정상 상태 할당 0회는 `nfr9_boundary_encoding_does_not_allocate`가 지킵니다.
- **Renderer의 힙 할당: 충족.** `BoundaryCostTest`로 JVM 개발 셸에서 실측했습니다. 텍스트 한 번 바뀔 때 글자당 **1.00바이트**, 16글자짜리 레코드 하나에 **96바이트**입니다(고치기 전: 글자당 2.99바이트, 326바이트). arena 크기와도 무관합니다(작은 arena 104바이트, 64KB arena 104바이트).
  - 비용의 내역까지 측정합니다(`pr4_a_text_change_allocates_the_string_and_nothing_else_made_of_it`). 19글자 변경 하나가 104바이트인데, 그중 **40바이트**는 문자열이 없는 같은 모양의 레코드(`SetProp(Bool)`)도 똑같이 내는 `Mutation`과 `PropertyValue` 값이고, 남는 **64바이트**는 같은 텍스트로 `String`을 하나 만드는 비용과 바이트까지 일치합니다(같은 테스트에서 직접 만들어 재어 비교합니다). 즉 텍스트에서 비롯되는 할당은 Compose에 넘기는 `String` 하나뿐이고, 남는 40바이트는 텍스트의 사본이 아니라 인터프리터가 받는 레코드 값입니다.
- 어떻게 고쳤는지: 검증과 생성을 나눴습니다. 둘을 한 번에 하는 방법은 둘 다 쓸 수 없기 때문입니다. `String(bytes, UTF-8)`은 잘못된 UTF-8을 조용히 U+FFFD로 치환하는데 프로토콜은 그것을 `ProtocolError`로 보고해야 하고(`nfr7_non_utf8_string_payload_is_a_protocol_error`), `CharsetDecoder`는 보고는 하지만 텍스트를 `char`로(글자당 2바이트) 한 번 조립한 뒤 `String`으로 다시 옮기며 문자열마다 디코더 하나와 버퍼 뷰 둘을 새로 만듭니다. 그래서 생성된 디코더는 이제 `requireUtf8`로 arena를 제자리에서 훑고(할당 0), 통과한 바이트만 디코더가 들고 키우는 버퍼를 거쳐 `String`을 한 번 만듭니다.
  - 남는 위험은 손으로 쓴 검증기가 플랫폼과 다른 판단을 하는 것입니다. `ProtocolStringTest`가 malformed 18가지(과잉 인코딩, surrogate 반쪽, U+10FFFF 초과, 잘린 시퀀스)를 플랫폼 디코더와 나란히 세워 두어 두 판단이 갈라지면 그 자리에서 드러납니다.
  - `BYTES_PER_CHARACTER_CEILING`은 3.2에서 **1.05**로 내렸습니다. 이제 네 번째 복사가 아니라 두 번째 복사를 막습니다.
- 디코드 시간도 같이 재었습니다(NFR-9). JVM 개발 셸, 20만 회 디코드 9번 중 최선값입니다. 키 입력 한 번 크기(19글자) **45ns**(고치기 전 67ns), 4016글자 **638ns**(669ns), ASCII가 없는 텍스트(한글 30글자) **227ns**(118ns).
  - 긴 ASCII가 한때 1156ns까지 올라갔습니다. 플랫폼 디코더에는 벡터화된 ASCII 경로가 있는데 `ByteBuffer.get` 루프에는 없기 때문입니다. 그래서 검증기는 텍스트가 ASCII인 동안 `Long` 하나씩 읽습니다(여덟 바이트에 상위 비트가 하나도 없으면 여덟 글자가 더 볼 것이 없는 글자입니다). ASCII 바이트를 본 뒤에만 들어가므로 ASCII가 없는 텍스트는 시도 비용을 내지 않습니다.
  - ASCII가 없는 텍스트는 여전히 예전보다 느립니다. 검증한 바이트를 `String`이 다시 디코드하기 때문입니다. 문자열 하나에 110ns이고, 그 대가로 프레임마다 모든 문자열의 사본 두 벌이 사라집니다. GC 정지가 프레임을 깨는 쪽이라 이 교환을 택했습니다.
- 두 기준이 모두 실측으로 충족되어 상태는 `Done`입니다. 할당 수치는 JVM 개발 셸에서 재었고(native image가 컴파일되는 바이트코드와 같은 것), iOS는 같은 생성 파일을 `java.nio` 심으로 컴파일해 같은 디코더를 씁니다.

### PR-5 Android (`Agreed`)
- 호스트 관계: Kotlin Activity가 프로세스와 루프를 소유합니다(`LoopMode::Platform`). Rust는 cdylib입니다. VirtualDom은 PR-3에 따라 UI 스레드에서 돕니다.
- JNI 심: PR-2의 논리 연산에서 jni-rs 기반 심과 Kotlin `external fun` 선언을 코드젠으로 생성합니다. UniFFI(JNA 경유)는 호출당 오버헤드가 커서 쓰지 않습니다.
- 생명주기:
  - Surface 파괴와 config change: Compose만 재구성됩니다. VirtualDom도 노드 테이블도 프로세스에 남으므로, 다시 만들어진 Activity는 같은 테이블을 그대로 그립니다. 경계 호출도 상태 손실도 없고, `Resync`는 이 자리에서 보내지 않습니다.
  - `Resync`는 노드 테이블을 지킬 수 없는 Renderer를 위한 것입니다. Host는 이미 보낸 트리의 사본을 들고 있지 않아서 애플리케이션을 처음부터 다시 만들어 답하며, 그래서 돌아오는 배치의 노드 id는 1부터 다시 시작하고 컴포넌트 상태는 사라집니다. 받는 쪽은 그 배치가 오기 전에 기존 트리와 에셋과 메시지를 먼저 버려야 합니다. 지킬 수 있는 테이블은 지키는 쪽이 언제나 낫습니다.
  - `onStop`/`onStart`: `LifecycleStop`과 `LifecycleStart`를 보냅니다. Host는 그 사이에 도착한 워커의 프레임 요청을 기억만 해 두었다가 시작할 때 한 번 내보내고, 그동안 타이머와 애니메이션은 억제됩니다. `Resumed`/`Paused`/`Destroyed`로 나누지 않습니다. 그리는 일이 실제로 멈추는 경계는 `onStop`이고, `Destroyed`는 프로세스가 사라지는 자리라 Host가 들을 수 없습니다.
    - 예외는 알림입니다(FR-36, 2026-10-03, `Draft`). 멈춘 동안 Host의 알림 대기열에 알림이 들어오면 Host는 그 요청을 미루지 않고 내보내고, Renderer는 PR-3의 개정대로 멈춘 클록 밖에서 `render_frame`을 한 번 부릅니다. 알림은 사용자가 화면을 보지 않을 때 나가야 하는 것이기 때문입니다. 타이머와 애니메이션의 억제는 그대로입니다.
  - 프로세스 kill: 메모리 상태는 복원하지 않습니다. 필요하면 `SaveState`로 작은 blob을 `onSaveInstanceState`에 저장합니다.
- 에셋: Android는 자기 그래픽 스택으로 그리므로 SVG 파서가 없습니다. `Svg` 종류의 등록은 FR-16이 정한 대로 읽을 수 없다고 보고하고, 나머지 배치는 그대로 적용됩니다. `VectorIcon`은 영향이 없습니다. 모양을 그리는 것은 디자인 시스템이기 때문입니다.
- android-activity, NativeActivity, GameActivity 진입점은 쓰지 않습니다. ComposeView와 공존한 사례가 없고 IME 충돌 위험이 있습니다. JavaVM은 `JNI_OnLoad`에서 얻습니다.

#### 5.1 Kotlin이 앱 빌드에 들어가는 방법 (INTENT D11)

데스크톱과 iOS는 Kotlin을 AOT로 굳혀 바이너리 하나로 내보내지만, Android는 ART라 그럴 수 없습니다. Kotlin이 사용자 앱의 Gradle 빌드에 참여해야 하고, 그 경로를 dx가 정해 둡니다.

**크레이트가 Kotlin 소스를 품고, 빌드 스크립트가 Gradle 프로젝트의 `src/main/kotlin`에 풀어놓습니다.**

- dx의 Gradle 템플릿은 `gradle_dependencies` 항목을 `implementation("...")`로 감싸므로 **Maven 좌표만** 받습니다. 크레이트에 AAR을 넣어도 Gradle에 알릴 방법이 없어서, 컴파일된 아티팩트가 아니라 소스를 배포합니다.
- 같은 템플릿이 `sourceSets { main { java.srcDirs("src/main/kotlin", ...) } }`를 선언하므로, 거기 놓인 `.kt`는 사용자 앱과 함께 컴파일됩니다. wry가 Kotlin을 생성해 넣는 것과 같은 경로입니다.
- **Compose와 androidx는 좌표로 선언합니다.** 남의 라이브러리이고 mavenCentral에 이미 있으므로 우리가 배포하지 않습니다.
- **MainActivity는 생성합니다.** dx 템플릿은 패키지를 `dev.dioxus.main`으로 고정하고 앱 id는 `BuildConfig` 별칭에만 씁니다. 정적 파일로 주면 그 치환을 못 받으므로, 빌드 스크립트가 dx가 알려 주는 패키지와 라이브러리 이름으로 만들어냅니다. dx가 생성해 둔 것(wry의 Activity를 상속합니다)을 덮어쓰고, `ComponentActivity`를 상속해 `setContent`로 렌더러를 띄웁니다.
- 수용 기준(M6)에 추가합니다: **4. 사용자가 `Dioxus.toml`에 우리 좌표를 적지 않고도 APK가 빌드됩니다.** Kotlin 소스가 자동으로 들어가고 Maven 의존성이 없어야 통과입니다.
- 수용 기준(M6):
  1. 일반 JNI와 `@FastNative`의 호출당 비용을 실측합니다. 공개 수치(약 115ns, 약 35ns)와 비교해 기록합니다.
  2. M0 화면을 같은 Rust 소스로 띄우고, 초당 100회 추가되는 스트리밍 중 프레임 끊김이 없음을 Macrobenchmark `FrameTimingMetric`으로 확인합니다.
  3. 화면 회전, 다크모드 전환, 홈→복귀, `am kill` 후 복귀에서 크래시가 없습니다.
- 구현 상태(2026-09-22): 경계 심 생성, 생명주기와 `Resync`, Activity 호스팅, cdylib 빌드가 들어왔습니다. 심은 `aarch64-linux-android`로 컴파일되고, cdylib이 내보내는 JNI 심벌은 컴파일된 Kotlin 클래스가 native로 선언한 이름과 정확히 일치합니다.
- **2026-09-23: Android 모듈에서 애플리케이션을 지웠습니다.** `MainActivity.kt`, `AndroidManifest.xml`, 런처 리소스, `jniLibs`, `android/scripts/build-host.sh`, `android_demo` 예제입니다. 모듈은 `lib`가 되어 "Android 툴체인에서 Kotlin이 컴파일된다"만 말합니다. 애플리케이션을 하나 들고 있던 것이 **샘플 APK를 사용자가 밟지 않는 경로로 만들게 한 원인**이었습니다. 두 경로가 있으면 우리는 항상 우리 쪽을 쓰게 되고, 사용자 경로는 깨져도 아무도 모릅니다.
  - `scripts/build-sample-apks.sh`는 이제 `dx build --platform android`를 돕니다. minimal 샘플로 다시 확인했습니다. Activity도 manifest도 없는 상태에서 APK가 나오고 에뮬레이터에서 같은 화면을 그립니다.
  - 크레이트가 실어 나르는 Kotlin에는 Activity도 manifest도 없어야 합니다. `pr5_no_application_of_ours_travels_with_the_renderer`가 그것을 봅니다.
- **수용 기준 3은 2026-09-22 API 36 에뮬레이터에서 통과했습니다.** 화면 회전, 다크모드 전환, 홈에서 복귀는 모두 크래시 없이 **같은 프로세스가 유지**되었고(위 생명주기 항목이 규정한 대로 Compose만 재구성되고 노드 테이블은 남습니다), 백그라운드로 보낸 뒤 `am kill`한 다음 다시 띄운 것도 새 프로세스로 정상 동작했습니다. `FATAL EXCEPTION`은 한 건도 없습니다. 다크모드는 실제로 팔레트가 바뀌는 것까지 화면으로 확인했고, 그 동안 Rust 워커의 스트리밍이 끊기지 않았으므로 워커의 프레임 요청이 JNI 경계를 계속 넘어온다는 것도 같이 확인됩니다.
- **이 검증에서 결함이 하나 나왔습니다.** Rust cdylib이 `compose_rust_renderer_run`을 선언하고 있어서 `dlopen`이 실패하고 `onCreate`에서 매번 죽었습니다. 빌드 스크립트가 타깃을 데스크톱과 그 외로만 갈라서 Android를 iOS와 같이 취급했기 때문입니다. iOS는 Xcode가 그 심벌을 실제로 링크하지만 Android의 렌더러는 ART 안의 Kotlin이라 그런 네이티브 심벌이 없습니다. Android는 `JNI_OnLoad`에서 진입점을 설치하므로 브라우저와 같은 갈래입니다. 빌드 스크립트는 테스트로 컴파일되지 않아 이 규칙이 어디에서도 검증되지 않고 있었고, 지금은 테스트가 닿는 모듈로 나와 있습니다.
- **수용 기준 1은 2026-09-22 API 36 에뮬레이터에서 쟀습니다.** 빈 shim을 워밍업 20만 번 뒤 200만 번씩 불러서 전환 비용만 뽑은 값입니다.

  | | 실측 | 공개 수치 |
  |---|---|---|
  | 일반 JNI | 101.08 ns | 약 115 ns |
  | 상태 전환을 건너뛴 호출 | 4.48 ns | 약 35 ns |

  **에뮬레이터 수치이므로 절대값은 실기와 다릅니다.** 일반 JNI는 공개 수치에 가깝지만 `@FastNative` 쪽이 8배 빠르게 나왔고, 이는 호스트 CPU에서 도는 환경이라 상태 전환이 실기보다 싸게 끝난 것으로 봅니다. 실기 측정은 따로 남습니다.

  이 수치가 프레임 예산에서 뜻하는 바: 120Hz의 프레임당 8.33ms에 대해 경계 호출은 프레임당 몇 번뿐이고(`RenderFrame` 한 번, 입력이 있으면 `DispatchEvent` 한 번, `ReleaseBatch` 한 번), 다섯 번으로 잡아도 505ns로 예산의 0.006%입니다. 호출당 비용이 아니라 호출 횟수가 문제가 될 자리인데, 배치를 복사하지 않고 오프셋과 길이만 건네는 것과 워커의 프레임 요청이 카운터 하나로 합쳐지는 것이 그 횟수를 눌러 둡니다.

  `@FastNative`가 붙는 것은 `ReleaseBatch`뿐입니다. 이 어노테이션은 스레드를 runnable로 둔 채 호출하므로 그동안 GC가 그 스레드를 멈출 수 없고, `RenderFrame`과 `DispatchEvent`는 안에서 VirtualDom이 도는 호출이라 GC를 붙잡아 두는 대가가 아끼는 100ns보다 비쌉니다.

  **2026-09-23: 측정 하네스는 지웠습니다.** 빈 shim 두 개(`nativeNoop`, `nativeNoopFast`)와 그것을 부르던 `JniCallCost`, 그리고 그것을 띄우던 Activity입니다. 위 수치는 남지만 다시 재려면 하네스를 새로 만들어야 합니다. 벤치마크를 `bridge/`에 둔 탓에 **모든 사용자 APK에 200만 회 루프와 빈 JNI 진입점 두 개가 실려 가고 있었고**, 그 상태로 두는 것이 실기 재측정 편의보다 나쁘다고 판단했습니다. 실기 측정이 필요해지면 그때 애플리케이션 쪽에 하네스를 만듭니다.
- **수용 기준 2는 이 자리에서 잴 수 없습니다(2026-09-22).** 스트리밍 자체는 돕니다. 화면에서 점이 계속 늘어나는 것과 다크모드 전환 내내 멈추지 않는 것을 확인했으므로, 워커의 프레임 요청이 JNI 경계를 계속 넘어온다는 것까지는 압니다. 확인되지 않은 것은 그 프레임이 제때 그려지는지입니다.

  `dumpsys gfxinfo`는 렌더링된 프레임을 0건으로 보고합니다. 창 없는 에뮬레이터에서는 합성이 일어나지 않기 때문입니다. 창을 띄운 에뮬레이터라면 숫자는 나오겠지만, 그것은 호스트 맥의 컴포지터를 잰 값이지 기기의 값이 아닙니다. 끊김이 없다는 주장을 그 숫자로 세우면 측정하지 않은 것을 측정했다고 적는 셈입니다. 이 항목은 기기가 필요합니다.
- **5.1의 수용 기준 4는 2026-09-23 충족했습니다.** `dx build --platform android`로 minimal 샘플의 APK가 나왔고, 에뮬레이터에서 데스크톱과 같은 화면을 그렸습니다. 샘플의 `Dioxus.toml`에는 이 크레이트에 관한 항목이 하나도 없습니다. 의존성에 `dioxus-compose`를 적은 것이 전부입니다.
  - **dx가 알려 주는 것을 읽습니다.** dx는 Android 빌드마다 `WRY_ANDROID_KOTLIN_FILES_OUT_DIR`, `WRY_ANDROID_PACKAGE`, `WRY_ANDROID_LIBRARY`를 내보냅니다. 이름은 wry의 것입니다. dx가 wry의 webview를 띄울 Activity를 생성하라고 주는 값이기 때문입니다. 우리는 Compose로 그리고 webview가 없지만, 세 값이 가리키는 것은 어느 쪽이든 같습니다. 이 빌드가 속한 Gradle 프로젝트, 그 안의 패키지, 그리고 애플리케이션 자신의 cdylib 이름입니다. 이것을 읽는 것이 사용자가 아무것도 적지 않아도 되는 이유입니다.
  - **dx는 한 패키지의 디렉터리를 알려 주고 우리는 열두 패키지를 풉니다.** 그래서 알려 준 디렉터리에서 패키지 성분 수만큼 올라간 곳이 소스 루트입니다. `kotlin`이라는 이름을 찾는 대신 성분을 세는 것은 소스 루트 이름이 다른 프로젝트에서도 맞기 위해서입니다.
  - **Compose는 빌드 스크립트가 생성된 Gradle 파일에 넣습니다.** dx의 `gradle_plugins` 항목은 파일에 닿기 전에 이스케이프되므로 버전이 붙은 플러그인을 적을 방법이 없고, Kotlin 2.0에서 `@Composable`을 컴파일하려면 컴파일러 플러그인이 반드시 필요합니다. 그래서 모듈의 `build.gradle.kts`에는 플러그인과 androidx 좌표를, 루트에는 그 플러그인의 classpath를 더합니다. 버전은 템플릿이 이미 고정해 둔 Kotlin 버전을 읽어서 씁니다. 둘은 같이 릴리스되고 어긋나면 구성 단계에서 거부됩니다. 넣을 것이 없으면 아무것도 쓰지 않으므로 매 빌드가 Gradle을 다시 구성하게 만들지 않습니다. **이것은 dx가 고치는 편이 옳은 자리입니다.** 생성된 파일을 우리가 손보는 것이므로, `gradle_plugins`가 버전을 받게 되면 이 보정은 없어져야 합니다.
  - **생성된 파일은 매 빌드 다시 쓰이므로 `rerun-if-changed`로 걸어 둡니다.** 걸지 않으면 두 번째 빌드에서 빌드 스크립트가 캐시되어 보정이 사라지고, Compose를 하나도 못 찾는 에러가 수십 개 납니다. 실제로 그렇게 한 번 났습니다.
  - **cdylib 이름은 애플리케이션의 것입니다.** 런타임이 `android_demo`를 상수로 들고 있어서 dx가 만든 APK가 `libandroid_demo.so`를 찾다가 죽었습니다. 지금은 생성된 Activity가 `ComposeRustRuntime.load(name)`으로 이름을 넘깁니다.
  - **크레이트의 `MainActivity.kt`와 `AndroidManifest.xml`은 따라가지 않습니다.** 둘 다 이 저장소 자신의 Android 애플리케이션 것이지 렌더러의 것이 아닙니다. 따라가면 남의 프로젝트에 Activity가 둘이 되고 매니페스트가 두 번 선언됩니다.
  - **`ByteBuffer.get(index, array, offset, length)`는 Java 13의 것입니다.** 우리 Amper 모듈은 더 높은 compileSdk로 빌드해서 통과했지만, dx 템플릿의 compileSdk 34에서는 후보가 없다고 거부됩니다. 생성 코드가 버퍼 자신의 position을 거쳐 읽고 되돌려 놓도록 바꿨습니다. `duplicate()`는 배치마다 문자열 수만큼 객체를 만들므로 쓰지 않습니다.
  - **로컬 도구 사슬 메모.** AGP 8.7의 `jlink` 변환은 JDK 25에서 실패합니다. `JAVA_HOME`이 21을 가리켜야 빌드가 끝납니다. 이것은 우리 쪽 문제가 아니라 AGP와 JDK의 조합입니다.
  - `scripts/tests/android-kotlin-travels.test.sh`가 담긴 사본이 렌더러와 같은지와 패키지 목록에 들어 있는지를 봅니다. 사본이 뒤처지면 애플리케이션이 Host보다 낡은 인터프리터를 컴파일하고 핸드셰이크가 거부합니다.
  - **`sample-v0.1.1`의 APK는 이 경로로 만든 것이 아닙니다.** 우리 Amper 모듈(`renderer/android`)에 샘플의 cdylib을 넣어 빌드한 것이고, 그것이 증명하는 것은 Android에서 렌더러와 Host가 동작한다는 것이지 사용자가 겪을 경로가 동작한다는 것은 아닙니다. 다음 샘플 릴리스의 APK는 dx로 만듭니다.

### PR-6 Web 경계 (`Agreed`, 재측정 중)

**상태를 `Done`에서 내립니다(2026-10-03).** 소유자 지시는 직결이었습니다: "Q7 JS 브릿지 갔다오는건 성능이 느려서 안된다. 직결하도록 해"(2026-09-19). 2026-09-20 실험에서 Wasm 테이블을 거치는 `call_indirect` 트램펄린이 Safari 기준 5.6ns(JS 경유 13.2ns)로 측정됐지만 커밋되지 않았습니다. 그 뒤 "직결은 불가능하다"는 결론이 승인 없이 이 항목과 INTENT에 들어갔습니다. 재측정(#54)으로 직결이 재현되면 이 항목과 INTENT D3/D8을 직결로 되돌리고, 아래의 JS 포워더 서술은 그때 고칩니다. 그때까지 이 항목은 `Done`이 아닙니다.
Rust(wasm32)와 Kotlin/Wasm 모듈을 연결합니다. `LoopMode::Platform`입니다. 2026-09-20 실측으로 확정했습니다(`experiments/web-interop/`).

- **메모리: Kotlin이 소유합니다.** Kotlin/Wasm 모듈은 항상 자기 메모리를 정의해 export하며, 외부 메모리를 import하는 경로가 없습니다. 따라서 Rust가 `--import-memory`로 그 메모리를 가져다 씁니다. PR-4의 arena는 양쪽이 제자리에서 읽습니다. 복사는 없습니다.
- **함수 호출: 경계 함수마다 고정된 형태의 JS forwarder를 코드젠으로 생성합니다.** 호출당 약 12ns입니다.
- **두 가지를 동시에 가질 수 없습니다.** `WebAssembly.instantiate`는 import를 먼저 요구합니다. Kotlin은 Rust export 없이 인스턴스화할 수 없고, Rust는 Kotlin 메모리 없이 인스턴스화할 수 없으며, Kotlin 메모리는 인스턴스화 전에 존재하지 않습니다. wasm import를 wasm export에 직접 묶으면(1.45ns) 메모리를 공유할 수 없고, 메모리를 공유하면 한쪽 호출 경로에 JS forwarder가 들어옵니다. 단일 모듈 링크(WasmGC와 linear memory 혼합 불가), 제3의 메모리 소유 모듈, component model(브라우저 미지원) 모두 이 순환을 풀지 못합니다.
- **메모리 공유를 택합니다.** 프레임 예산을 지배하는 것은 PR-4의 복사 회피이지 호출 오버헤드가 아닙니다. 프레임당 경계 호출 3회 기준 약 36ns이며, PR-5가 Android에서 이미 수용한 JNI 호출 비용(약 115ns)보다 한 자릿수 작습니다. D8이 거부한 React Native 브리지와는 성격이 다릅니다. 직렬화도, 비동기 큐도, 스레드 홉도, 데이터 복사도 없습니다.
- 구현 시 주의: Kotlin 메모리는 0페이지로 시작하므로 Rust 인스턴스화 전에 JS가 `memory.grow()`를 해야 합니다. Rust의 데이터 세그먼트와 Kotlin `kotlin.wasm.unsafe` 할당자가 같은 주소 공간을 쓰므로 `--global-base`로 영역을 분리합니다.
- 실측(Safari 26.5, Apple silicon): 같은 모듈 호출 0.30ns, wasm 직접 바인딩 1.45ns, JS forwarder 12.05ns, Kotlin에서 메모리 읽기 0.977ns/byte.
- **방향에 따라 비용이 다릅니다.** forwarder를 거치는 것은 Renderer에서 Host로 가는 호출뿐입니다. 반대 방향, 곧 Host가 프레임을 요청하는 `request_frame`은 Rust의 wasm import를 Kotlin이 `@WasmExport`로 내놓은 함수에 직접 묶으므로 JS가 없습니다. Rust가 나중에 인스턴스화되고 그 시점에 Kotlin export는 이미 존재하기 때문입니다.
- **주소 영역을 상수로 못박습니다.** 0부터 `WEB_RUST_REGION_BASE`(4MiB) 미만은 Kotlin `kotlin.wasm.unsafe` 할당자의 것이고, 그 위는 Rust의 데이터와 스택과 힙입니다. Rust는 `--global-base`로 그 자리에 놓입니다. Renderer는 할당자가 준 주소가 경계 아래인지 시작할 때 확인하고, 아니면 경계 호출을 시작하지 않습니다. 두 할당자가 같은 주소를 쓰면 화면이 조용히 틀리는 것으로 끝나므로, 겹침은 자라기 전에 잡아야 합니다.
- 경계 함수 목록은 PR-2 그대로입니다. 여기에 Rust wasm 모듈은 `compose_rust_host_web_start`를 하나 더 export합니다. 경계 연산이 아니라, 라이브러리 로더가 없는 환경에서 Android의 `JNI_OnLoad`가 하던 일(루트 컴포넌트 등록과 RendererApi 설치)을 놓을 자리입니다.
- **`web_start`는 Host가 Renderer에게 빌려주는 블록의 주소를 돌려줍니다.** 앞 16바이트가 `MutationBatch` out 레코드이고, 그 뒤 4KiB가 이벤트 버퍼입니다. Host가 소유하는 이유는 Renderer가 붙잡아 둘 수 없기 때문입니다. `kotlin.wasm.unsafe`의 할당자는 `withScopedMemoryAllocator` 블록 안에서만 살아 있어서, 프레임을 넘겨 쓸 주소를 얻는 방법이 없습니다. 호출마다 스코프를 열면 정상 상태 할당 0회(NFR-9)를 잃습니다. 그래서 두 버퍼는 Rust 영역에 정적으로 놓이고, Renderer는 주소만 기억합니다. 0이 돌아오면 Host가 없는 것이고, Renderer는 경계 호출을 시작하지 않습니다.
- **forwarder는 Kotlin `@JsFun` 선언입니다.** 경계 함수마다 하나씩, 인자를 그대로 넘기는 고정 형태의 JS 화살표 함수를 코드젠이 생성합니다(`(a, b, c) => host.symbol(a, b, c)`). 실측한 12.05ns가 바로 이 모양입니다. 별도의 `@WasmImport` 모듈을 두는 길은 같은 순환에 걸립니다. Kotlin의 import는 인스턴스화 시점에 채워져야 하는데 그때 Rust는 아직 없습니다.
- **인스턴스화 순서**를 페이지가 정합니다. 코드젠이 만든 로더 모듈이 `web.mjs`보다 먼저 평가되어 Rust 모듈을 `compileStreaming`으로 컴파일해 둡니다. 그다음 Kotlin 모듈이 인스턴스화되면서 메모리가 생기고, Kotlin `main`이 로더를 한 번 불러 그 메모리 위에 Rust를 동기로 인스턴스화합니다(`new WebAssembly.Instance`). 이 시점에 Kotlin export가 이미 있으므로 `compose_rust_renderer_request_frame`은 wasm export 객체를 그대로 넘겨 직접 바인딩합니다. Renderer 쪽에 새 진입점은 없습니다.
- 수용 기준(M7):
  1. Kotlin이 정의해 export한 메모리 하나를 Rust가 import하고, 한쪽이 쓴 arena를 다른 쪽이 제자리에서 읽습니다. 복사한 바이트가 없습니다.
  2. 이벤트 하나가 경계 호출 2회로 끝납니다(PR-4와 같은 기준).
  3. forwarder의 호출당 비용을 실측해 기록합니다.
  4. M0 화면이 데스크톱과 같은 Rust 소스로 브라우저에 뜨고, 클릭이 Rust에 도달하며, Rust의 상태 변경이 화면에 반영됩니다.
  5. 생성된 Kotlin 선언, 생성된 forwarder, Rust의 wasm glue가 모두 같은 스키마에서 나옵니다. 손으로 쓴 glue는 없습니다(FR-7).

**검증 (2026-09-22, Chrome for Testing 149 / V8, Apple silicon)**

`renderer/web/test/WebBoundaryTest.kt`가 Kotlin/Wasm 테스트 러너가 이미 띄우는
브라우저 안에서 경계를 직접 돕니다. 대상은 실물입니다. 생성된 forwarder, 생성된 wasm 심, 생성된
인스턴스화, 그리고 양쪽이 제자리에서 읽는 `WebAssembly.Memory` 하나입니다. Host는 데스크톱과 같은
Rust 소스(`examples/web_demo.rs`)를 `--import-memory --global-base=4194304 --initial-memory=8388608`로
링크한 것입니다.

```
pr6 forwarder cost: 12.15 ns/call across the boundary, 0.44 ns/call in this module
```

- **수용 기준 1 충족.** 초기 배치가 트리를 만들고 오류 없이 디코드됩니다. 한쪽이 쓴 arena를 다른 쪽이
  제자리에서 읽은 것이고, 복사한 바이트는 없습니다. 링크된 모듈이 메모리를 정의하지 않고 import하는
  것은 `scripts/tests/web-host-imports.test.sh`가 확인합니다.
- **수용 기준 2 충족.** PR-4의 기준은 Host 쪽 `boundary_call_cost.rs`가 지키고, 브라우저에서는
  텍스트 변경과 클릭이 Rust 핸들러에 도달해 바뀐 상태가 배치로 돌아오는 것을 확인했습니다
  (`pr6_a_click_reaches_the_host_and_its_state_change_comes_back`).
- **수용 기준 3 충족.** 경계 호출 **12.15 / 12.29 / 12.75 ns**(3회 측정, 각 200만 회 호출 7세트의
  최선값), 같은 루프를 모듈 안에서 돌린 값 **0.39~0.44 ns**. Safari 26.5의 12.05ns와 같은 자리이므로
  두 번째 엔진에서도 수치가 유지됩니다. 프레임당 경계 호출 3회는 약 37ns이고, 16.7ms 프레임에서
  0.0002%입니다.
- **수용 기준 5 충족.** 생성된 Kotlin 선언, 생성된 forwarder와 인스턴스화, Rust의 wasm 심이 모두
  `BOUNDARY_SCHEMA`와 그 옆의 메모리 상수에서 나옵니다. `dioxus-compose/tests/web_boundary.rs`가
  체크인된 세 파일이 오늘 생성되는 것과 같은지, forwarder가 인자를 넘기는 것 외에 아무것도 하지
  않는지, 양쪽 인자 개수가 맞는지를 지킵니다.
- **수용 기준 4 충족.** `web/scripts/screenshot.sh`가 페이지를 띄워 사진을 찍습니다. 처음 뜬 화면에
  데스크톱과 같은 트리(`dioxus-compose chat`, `Write a message` 자리표시자, Material 3 `Send` 버튼)가
  그려지고, 필드에 타이핑한 뒤 버튼을 누르면 Rust 핸들러가 signal에 넣은 문장이 필드 위에 새 `Text`로
  나타납니다. 필드의 글자가 남는 것은 D5대로 `TextField`가 uncontrolled이기 때문이며 데스크톱과 같습니다.
  스크립트는 Kotlin 테스트 하네스가 이미 내려받은 Playwright와 브라우저, 툴체인이 들고 있는 Node를
  빌려 쓰므로 디스플레이도 네이티브 빌드도 필요하지 않습니다.
- 남은 확인: SpiderMonkey에서 재측정. V8은 이 측정으로 닫혔습니다.
- CI는 여전히 wasm 테스트를 돌리지 않습니다. 러너가 잘린 skiko 모듈을 받아 브라우저 하네스가 뜨지
  않기 때문이고, 경계 테스트 자체는 이제 의미가 있으므로 그 문제가 풀리면 바로 켤 수 있습니다.

### PR-7 명명 규칙 (`Done`)

**검증 방식에 관하여(2026-09-22):** 대부분은 판단의 문제입니다. 어떤 이름이 Compose나 Dioxus가 썼을 이름인지는 스크립트가 답할 수 없고 검토가 답합니다. 다만 한 조항은 정확하고, 그것은 이상하게 읽히는 데 그치지 않고 남의 빌드를 깨뜨리는 조항입니다. **C로 내보내는 심볼은 모두 `compose_rust_` 접두사를 답니다.** C 심볼은 프로세스 전역이라, 두 라이브러리가 같은 맨이름을 내보내면 둘 다 쓰는 사람에게는 링크 오류입니다.

- 수용 기준: C 익스포트 13개가 전부 접두사를 답니다(`scripts/tests/naming-conventions.test.sh`). **(통과)**
각 언어 생태계의 관례를 따릅니다. 한쪽 관례를 다른 쪽에 억지로 맞추지 않습니다.

| 영역 | 관례 | 예 |
|---|---|---|
| C ABI 심볼 | snake_case, `compose_rust_{host,renderer}_` 접두사, 동사구 | `compose_rust_host_dispatch_event` |
| Rust 공개 API | Dioxus 관례를 따릅니다: `launch`, `LaunchBuilder`, `use_*` 훅, PascalCase 컴포넌트 | `dioxus_compose::launch(app)`, `use_text_field()` |
| Rust 타입 | PascalCase, `#[repr(C)]` 경계 타입은 역할 이름 | `MutationBatch`, `LoopMode`, `HostEvent` |
| rsx 위젯 | Compose 이름을 그대로 씁니다 | `Column`, `Row`, `LazyColumn`, `TextField` |
| rsx 속성과 Modifier | snake_case, Compose 이름을 옮긴 것 | `fill_max_width`, `padding`, `on_click`, `on_value_change` |
| Kotlin 선언 | camelCase 함수, PascalCase 타입, 컴포저블은 PascalCase 명사 | `ComposeRustContent(host)`, `rememberComposeRustHost()`, `HostBridge.dispatchEvent()` |
| Kotlin 생성 코드 | `generated` 패키지, 파일 이름 접미사 `.gen.kt` | `dev.darkpyonix.composerust.protocol` |
| 이벤트/Mutation 태그 | Rust `enum` 변형은 PascalCase, Kotlin `sealed interface` 하위 타입은 같은 이름 | `SetText`, `TextSubmitted` |

- Compose에 같은 개념이 있으면 그 이름을 씁니다(`Modifier`, `Recomposition`, `requestFrame`). 새 이름을 만들지 않습니다.
- Dioxus에 같은 개념이 있으면 Rust 쪽은 Dioxus 이름을 씁니다(`VirtualDom`, `Mutations`, `ElementId`).
- 두 이름이 충돌하면 Rust 쪽은 Dioxus 이름을, Kotlin 쪽은 Compose 이름을 쓰고, 대응 관계를 코드젠 스키마에 기록합니다.
- **2026-10-03(INTENT D2): 위의 Dioxus 관례는 dioxus-compose 층에 적용됩니다.** compose-rust 자신의 작성 API(FR-39)는 Compose의 이름을 Rust 관례로 옮겨 씁니다(`remember`, composable 함수, 상태 객체, effect). 두 층이 같은 개념을 가리킬 때 compose-rust 쪽 이름은 Compose를, dioxus-compose 쪽 이름은 Dioxus를 따릅니다.

### PR-8 macOS 런타임 요건 (`Done`)

**2026-10-03(INTENT D4, NFR-14): 이 항목은 GraalVM native-image(AWT) 경로의 요건입니다.** 배포되는 macOS 렌더러는 이제 Kotlin/Native 정적 라이브러리이고 `java.home`도 `lib/`도 없습니다. 메인 스레드 요건(`RUN_NOT_MAIN_THREAD`)은 AppKit의 요건이므로 두 경로 모두에 적용됩니다.

- 빌드 도구는 Liberica NIK 25 Full입니다(INTENT D9-macOS).
- 배포 레이아웃은 `<root>/lib/` 하나이며 `java.home`은 그 부모입니다. 렌더러는 자기 라이브러리 경로를 dladdr로 얻어 `java.home`, `skiko.library.path`, `skiko.data.path`를 설정합니다.
- `lib/`에 함께 두는 파일: 렌더러 라이브러리, Skia(`libskiko-macos-<arch>.dylib`), `libjawt.dylib` 포워더, `libawt_lwawt.dylib` 자리 채우기.
- `compose_rust_renderer_run`은 프로세스 메인 스레드에서 호출해야 합니다. 아니면 `RUN_NOT_MAIN_THREAD`를 반환합니다.
- 빌드 스크립트는 `lib/static/darwin-*/libawt_lwawt.a`가 없는 설치를 이미지 빌드 시작 전에 거부합니다. 순정 GraalVM과 비 Full NIK을 걸러내기 위한 것입니다.
- 수용 기준
  1. C 호스트가 라이브러리를 링크해 `run`을 호출하면 창이 뜨고, 창을 닫으면 `run`이 0을 반환하며 프로세스가 정상 종료됩니다. **(2026-09-20 통과)**
     근거: `desktop/c/smoke_host.c`가 `run`의 반환값을 프로세스 종료 코드로 옮기고, `desktop/scripts/smoke-test.sh`가 그 호스트를 링크해 실행합니다. `native-renderer.yml`의 macOS 잡이 native-image 빌드 뒤에 이 스크립트를 돌리므로, 창이 뜨지 않거나 `run`이 0이 아닌 값을 돌려주면 잡이 실패합니다.
  2. 잘못된 툴체인(`GRAALVM_HOME` 미설정/없는 경로/`native-image` 없음/정적 AWT 아카이브 없음)에서 빌드가 즉시 실패하고 조치 방법을 출력합니다. **(2026-09-20 통과)**
     근거: `scripts/tests/renderer-toolchain.test.sh`가 네 경우를 전부 임시 디렉터리로 재현해 `env.sh`가 1로 끝나고 설치 방법을 출력하는지 확인하고, 올바른 모양의 설치에서는 통과하는지도 함께 확인합니다. `build-native.sh`와 `smoke-test.sh`의 첫 실행문이 `env.sh`를 source하는지도 같은 파일이 봅니다. native-image 툴체인 없이 밀리초 단위로 돌기 때문에 PR마다 도는 `ci.yml`의 `scripts/tests` 루프에 들어 있습니다.

## 5. 비기능 요구사항

| ID | 요구사항 | 수용 기준 | 상태 |
|---|---|---|---|
| NFR-1 | JVM 불필요 | 배포물에 JRE가 없고, `java`가 없는 머신에서 실행됨 | Agreed |
| NFR-2 | 웹뷰 불필요 | WKWebView, WebView2, WebKitGTK에 링크하지 않음 | Agreed |
| NFR-3 | 데스크톱 무게 | 빈 창 physical footprint < 56MB, 배포 용량 < 100MB. 측정 기준과 근거는 §5.2 | Agreed |
| NFR-4 | 플랫폼 | macOS, Windows, Linux 데스크톱, iOS, Android, Web(wasm). Android와 Web의 경계는 PR-5, PR-6 참조. **2026-09-22 충족**: 같은 샘플을 macOS와 iOS 시뮬레이터와 Android 에뮬레이터와 Chromium에서 띄워 같은 화면이 나오는 것을 확인했고, Linux와 Windows는 CI가 빌드하고 스모크 테스트를 돌립니다 | Done |
| NFR-5 | 개발 경험 | Renderer는 JVM 개발 셸에서 hot reload와 `@Preview`로 작업 가능. native-image 빌드는 개발 루프에 필요 없음. 새 머신의 준비 상태를 `scripts/setup-check.sh` 한 번으로 확인 가능 | Agreed |
| NFR-6 | 안정 API만 사용 | `@InternalComposeUiApi`, `@ExperimentalComposeUiApi` 의존을 금지하거나, 쓰더라도 어댑터 한 파일에 격리하고 버전 핀을 둠. **2026-09-22 충족**: 실험 API를 쓰는 파일은 `web/src/main.kt` 하나이고 `@OptIn`이 그 자리에 붙어 있습니다 | Done |
| NFR-7 | 크래시 격리 | 프로토콜 오류로 프로세스가 종료되지 않고 `ProtocolError` 이벤트를 보냄 | Agreed |
| NFR-8 | 데스크톱 접근성 | 배포 빌드(2026-10-03부터 AWT 없는 렌더러, NFR-14)의 접근성 트리가 JVM 개발 셸과 같은 구조로 노출될 것. smoke test의 종료 코드 0은 근거가 되지 않습니다(접근성을 질의하지 않으므로). **2026-09-21 트리 노출 충족**, VoiceOver 수동 확인은 미완료(§7) | Agreed |
| NFR-9 | 네이티브 수준 프레임 성능 | §5.1 기준 충족 | Agreed |
| NFR-10 | 렌더러 탐색 경로 | `COMPOSE_RUST_RENDERER_DIR` → 워크스페이스 빌드 결과물 → 버전·타깃별 캐시 → 릴리스 다운로드 순서로 찾음. 규격과 수용 기준은 §5.3. **2026-09-21 충족** | Done |
| NFR-11 | 배포 | 크레이트는 crates.io, 렌더러는 플랫폼별 체크섬 릴리스 아티팩트. 설치는 `Cargo.toml` 한 줄이 전부이고 빌드 스크립트가 아티팩트를 가져옵니다. 규격과 수용 기준은 §5.3 (INTENT D10). **2026-09-21 macOS에서 충족**, Windows와 Linux는 실행 확인 미완료 | Agreed |
| NFR-12 | 워크트리 빌드 격리 | 워크트리마다 자기 `target/`에 빌드하고, 다른 워크트리의 빌드 디렉터리를 가리키는 설정이 없음. 한 트리에서 컴파일된 codegen 바이너리가 다른 트리에 쓸 수 없음. 규격과 수용 기준은 §5.4 (INTENT D16). **2026-09-22 충족** | Done |
| NFR-13 | Windows 단일 실행 파일 | Windows 렌더러를 Kotlin/Native로 빌드해 앱 실행 파일 하나에 링크함. JVM이 없고, 앱을 빌드하는 사람에게 MinGW를 요구하지 않음. 규격과 수용 기준은 §5.6 (INTENT D18, 2026-10-03 소유자 결정) | Agreed |
| NFR-14 | AWT 없는 데스크톱 렌더러가 기본 | 배포되는 데스크톱 렌더러가 AWT도 JVM도 쓰지 않음. macOS와 Linux는 Kotlin/Native 정적 라이브러리, Windows는 NFR-13. 앱이 아무것도 고르지 않아도 이 경로가 나옴. 규격과 수용 기준은 §5.5 (INTENT D4, 2026-10-03 소유자 결정) | Agreed |
| NFR-15 | 데스크톱 애플리케이션은 실행 파일 하나 | compose-rust 애플리케이션이 macOS, Windows, Linux에서 실행 파일 하나이고, 시스템 라이브러리 외에 아무것도 불러오지 않음. 렌더러, Skia, ICU 데이터가 안에 있음. 1.0.0 요구사항. 규격과 수용 기준은 §5.7 (INTENT D17, 2026-10-03 소유자 결정) | Agreed |

### 5.1 프레임 예산 (NFR-9)

목표는 **같은 화면을 Kotlin/Compose로 직접 작성한 것과 체감 차이가 없는 수준**입니다. 기준은 120Hz 디스플레이(프레임당 8.33ms)입니다.

| 항목 | 기준 (p99, 릴리스 빌드) |
|---|---|
| 기준선 대비 오버헤드 | 같은 화면을 순수 Compose로 만든 기준선 대비 프레임 시간 증가 ≤ 10% |
| Host 처리 (핸들러 + diff + 배치 인코딩) | 일반 상호작용 ≤ 0.5ms, 스트리밍 프레임 ≤ 1ms |
| 경계 호출 1회 비용 | Desktop/iOS ≤ 100ns, Android ≤ 200ns(`@FastNative` 적용 시) |
| 배치 적용 (Renderer 디코드 + 스냅샷 적용) | Mutation 100건당 ≤ 0.3ms |
| 입력 → 화면 반영 | 기준선과 같은 프레임 수. 추가 프레임 지연 0 |
| 정상 상태 할당 | 경계 인코딩(arena 재사용) 0회. Host 전체 경로는 프레임당 200회 이하이고, 같은 상호작용을 반복해도 증가하지 않을 것. Renderer: 변경된 문자열의 `String` 생성 외 할당 0회 |
| 프레임 드랍 | 초당 100회 추가되는 스트리밍 + 스크롤 중 드랍 0 (아이템 1만 개 목록) |

- 모든 수치는 실측으로 확인하고, 측정 환경(기기, OS, 빌드 설정)과 함께 기록합니다. 측정 결과는 `dioxus-compose/benches/baseline.json`에 있습니다.
- 할당은 횟수 자체보다 **증가하지 않는지**가 기준입니다. Dioxus는 diff와 이벤트 처리 과정에서 내부적으로 할당하며(2026-09-20 측정: 클릭당 99회), 이를 0으로 만들려면 Dioxus를 포크해야 합니다. 이 문장은 Dioxus 경로에 대한 것이고, 2026-10-03부터 compose-rust의 자기 런타임(INTENT D2, FR-39)은 Dioxus의 내부 할당에 묶이지 않습니다. 같은 표의 기준이 두 경로 모두에 적용됩니다. Rust에는 GC가 없어 이 할당이 프레임 멈춤으로 이어지지 않습니다. 반복 상호작용에서 할당 수가 늘어나면 누수나 캐시 미작동으로 보고 조사합니다.
- 벤치마크 하네스는 M0에서 함께 만들고, CI에서 회귀를 감시합니다. 기준 초과는 빌드 실패로 처리합니다.
- 개발 빌드에서는 Host 처리가 1ms를 넘는 프레임을 경고로 남깁니다.
- native-image의 GC pause도 프레임 드랍 요인으로 측정합니다. 기준을 넘으면 GC 설정(Serial/Epsilon, 힙 크기) 조정을 SPEC에 기록합니다.

### 5.2 메모리 (NFR-3)

**측정 기준은 macOS의 physical footprint입니다.** RSS는 이미지에서 매핑된 깨끗한 페이지까지 세기 때문에 실제 점유량을 과장합니다. 같은 프로세스가 RSS 127MB, footprint 54MB로 두 배 넘게 차이납니다. Activity Monitor의 "메모리" 열이 footprint입니다. 측정은 `desktop/scripts/measure-memory.sh`로 재현합니다.

**현재값 (2026-09-20, macOS 26.5.1, Apple M1, 스모크 테스트 창)**

| 영역 | dirty | 정체 |
|---|---|---|
| `MALLOC_SMALL` | 14.0MB | **Skia의 네이티브 할당**. Java 힙이 아닙니다 |
| 그래픽(Metal, 창 서페이스) | 10.0MB | 창 크기에 비례합니다 |
| `IOSurface` | 7.5~9.4MB | 창 서페이스 |
| `__DATA` dirty | 6.0MB | 이미지 힙의 쓰기 페이지 |
| `IOAccelerator` | 4.9MB | 이 중 4.7MB는 회수 가능 |
| 매핑된 파일 | 4.3MB | |
| `untagged (VM_ALLOCATE)` | 2.5MB | **실제 SubstrateVM 힙** |
| 기타 | 약 3.5MB | malloc 메타데이터, 페이지 테이블, 스택 |
| **합계** | **54~56MB** | |

**목표를 45MB에서 56MB로 고쳤습니다.** 45MB는 측정 전에 네이티브 AppKit 앱(20~25MB)을 보고 잡은 숫자였고, 실측으로 근거가 무너졌습니다.

**측정으로 확인된 사실**

1. **SubstrateVM 힙 상한은 footprint를 바꾸지 않습니다.** 기본값(RAM의 80%), 64MB, 24MB에서 `MALLOC_SMALL`이 모두 정확히 14MB입니다. Serial GC의 적응 정책이 이미 실제 사용량에 맞춰 힙을 잡습니다. 5.2가 이전에 이것을 최대 레버로 지목한 것은 틀린 전제였습니다.
2. **14MB는 Skia의 네이티브 할당입니다.** `-R:` 계열 플래그가 닿지 않습니다. 실제 Java 힙은 2.5MB입니다.
3. **로케일과 도달 가능 코드는 footprint에 영향이 없습니다.** 이미지 코드와 읽기 전용 힙은 `__TEXT`(18MB)와 깨끗한 `__DATA`(8.7MB)에 들어가고, footprint는 dirty 페이지만 셉니다. 이 레버들은 디스크 용량(65MB)을 줄이지 resident를 줄이지 않습니다.
4. **`skiko.buffering=DOUBLE`은 JVM에서는 1.9MB를 줄이지만 네이티브 이미지에서는 동작하지 않습니다.** Skiko의 속성 보관 객체가 이미지 빌드 시점에 초기화돼서, 시작 시점에 설정한 값이 너무 늦게 도착합니다. IOSurface가 9408KB로 동일한 것을 측정으로 확인했습니다.

**Windows 실측 (2026-09-23, private working set, `examples/memory_probe`)**

지표가 다릅니다. macOS는 physical footprint, Windows는 작업 관리자가 "메모리" 열에 보여 주는
private working set입니다. 직접 빼서 비교할 수 있는 값이 아니지만, **비교가 유리한 쪽이
Windows입니다**: 네이티브 이미지의 코드는 DLL에서 매핑되는 파일 기반 페이지라 private에 잡히지
않으므로, 아래 숫자는 거의 전부 더티 메모리입니다.

| 빈 창 | Windows private ws | macOS footprint |
|---|---|---|
| 400x300 | 136.5MB | 38.5MB |
| 800x600 | 138.7MB | 52.4MB |
| 1600x1200 | 143.5MB | 90.2MB |
| 픽셀 16배에 대한 증가분 | **7MB** | **51.7MB** |

**Windows는 창 크기와 거의 무관합니다.** macOS에서는 서피스가 지배하고 픽셀 수에 선형인데(2배
디스플레이라 더 가파릅니다), Windows는 서피스가 거의 보이지 않고 **고정분이 약 135MB**입니다.
macOS의 고정분은 약 33MB이므로, **100MB가 Windows에만 있고 창을 아무리 줄여도 남습니다.**
50MB 목표에 서피스 최적화는 Windows에서 의미가 없습니다.

내용이 무는 값은 오히려 Windows가 작습니다(버튼 30개에 8.7MB, macOS는 15.6MB). 서피스와 같은
이유로 보입니다.

**어디서 오는지는 아직 확정하지 않았습니다.** 렌더러가 101.6MB(Windows) 대 70.5MB(macOS)이고
그 차이는 Windows에만 있는 `-H:Preserve=module=java.desktop` 하나입니다. 다만 30MB의 크기
차이가 100MB의 더티 메모리를 그대로 설명하지는 않습니다. 후보가 둘입니다.

1. **보존된 모듈의 메타데이터**가 이미지 힙의 가변 객체로 들어갑니다. 가변이면 런타임 더티입니다.
2. **Windows 로더의 재배치.** DLL이 선호 주소에 앉지 못하면 재배치가 적용되고, 재배치가 있는
   페이지는 private이 됩니다. 네이티브 이미지의 힙은 절대 포인터가 많아 재배치 대상이 넓습니다.

둘 다 이미지를 작게 만들라는 같은 방향을 가리킵니다. **2026-09-23에 되돌린 Windows NIK 전환이
파일 개수가 아니라 이 숫자의 레버입니다**(INTENT의 데스크톱 항목 참조). 확정하려면 `Preserve`
없이 빌드한 이미지로 같은 측정을 해야 합니다.

**남은 여지**: 그래픽이 약 22.5MB로 전체의 40%를 넘고 창 크기에 비례합니다. Skia와 그래픽을 합친 약 36.5MB가 Compose/Skia 프로세스의 바닥이며, 시스템 텍스트 스택을 공유하는 AppKit 앱은 지지 않는 비용입니다.

**더 줄이려면 IME 등록 메타데이터를 건드려야 합니다.** `ImeReachabilityFeature`가 패키지 단위로 등록하면서 약 7.8MB의 이미지 힙을 만듭니다. 이것을 좁히는 것이 남은 유일한 큰 레버지만, 실패하면 입력기가 텍스트 필드를 건드리는 순간 프로세스가 죽습니다(§6). **사람이 네이티브 빌드에서 한글을 직접 입력해 확인할 수 있을 때만 시도합니다.**

### 5.3 렌더러 획득 (NFR-10, NFR-11)

**요구사항: `Cargo.toml`에 `dioxus-compose`를 추가하는 것이 설치의 전부입니다.** 설정할 환경 변수, 손으로 내려받을 파일, 실행할 스크립트가 없습니다. `cargo build`가 렌더러를 확보하고 링크합니다.

#### 5.3.1 아티팩트 규격

| 항목 | 값 |
|---|---|
| 파일 이름 | `compose-rust-renderer-v{crate_version}-{target}.tar.gz` |
| 체크섬 파일 | 같은 이름에 `.sha256`. 내용은 `shasum -a 256` 출력(`<64자리 16진수>  <파일 이름>`) |
| 주소 | `https://github.com/DarkPyonix/dioxus-compose/releases/download/v{crate_version}/{파일 이름}` |
| `{target}` | `macos-aarch64`, `windows-x64`, `linux-x64`, `linux-arm64` |
| 내용 | 아티팩트 루트에 플랫폼별 렌더러 디렉터리. Windows는 `bin/`, 나머지는 `lib/` |

`{target}`은 Cargo의 타깃 트리플에서 만들되, 아키텍처 표기가 릴리스와 다릅니다. Cargo의 `x86_64`는 `x64`, Linux의 `aarch64`는 `arm64`입니다. macOS만 두 표기가 같습니다.

#### 5.3.2 탐색 순서

1. `COMPOSE_RUST_RENDERER_DIR`. 설정돼 있으면 언제나 이깁니다. 없으면 실패하고, 다음 단계로 넘어가지 않습니다. 직접 빌드한 렌더러를 가리켜 둔 빌드가 조용히 다운로드로 바뀌면 안 됩니다.
2. 워크스페이스 빌드 결과물. 이 저장소의 체크아웃에만 있습니다.
3. 버전·타깃별 캐시. 있으면 네트워크를 쓰지 않습니다.
4. 릴리스에서 내려받아 캐시에 풉니다.

#### 5.3.3 캐시

- 위치는 `target/` 밖입니다. Unix는 `$HOME/.cache/compose-rust/renderer/v{version}/{target}`, Windows는 `%LOCALAPPDATA%\compose-rust\renderer\v{version}\{target}`입니다. `COMPOSE_RUST_CACHE_DIR`로 뿌리를 옮길 수 있습니다(선택이며, 설치에 필요하지 않습니다).
- 키는 버전과 타깃입니다. `cargo clean`을 견디고 프로젝트 사이에서 공유됩니다.
- 내려받은 `.tar.gz`와 `.sha256`은 캐시 뿌리의 `downloads/`에 그대로 둡니다. 이 디렉터리가 오프라인 안내가 가리키는 자리이기도 합니다. 여기에 두 파일을 놓으면 다음 빌드가 네트워크 없이 검증하고 풉니다. 다운로드 경로와 손으로 놓는 경로가 같은 코드라서, 안내한 방법이 실제로 동작하는지 테스트가 확인할 수 있습니다.
- 푸는 매 번 체크섬을 검증합니다. 맞지 않으면 풀지 않고, 받은 값과 기대값을 모두 출력합니다.
- 푸는 작업은 임시 디렉터리에서 하고 마지막에 이름만 바꿉니다. 중간에 끊긴 빌드가 반쯤 풀린 디렉터리를 남기면 다음 빌드가 그것을 캐시 적중으로 읽습니다.

#### 5.3.4 링크된 바이너리가 실제로 실행될 것

크레이트를 의존성으로 추가한 애플리케이션이 링크에 성공하고 실행에 실패하면 설치가 끝난 것이 아닙니다. Cargo는 의존성 빌드 스크립트의 링크 탐색 경로와 링크 라이브러리는 최종 바이너리까지 넘기지만 **링크 인자는 넘기지 않습니다.** rpath가 링크 인자이므로 소비자 바이너리에는 rpath가 없습니다. 그래서 다음 두 가지를 크레이트 안에서 해결합니다(INTENT D12).

- **라이브러리는 자기가 놓인 절대 경로를 자기 이름으로 답니다.** 빌드 스크립트가 렌더러를 확보한 직후, 그 라이브러리가 지금 있는 절대 경로를 라이브러리 자신에게 새깁니다. 소비자 바이너리는 그 절대 경로를 기록하고 로더는 rpath 없이 찾습니다. `COMPOSE_RUST_RENDERER_DIR`, 워크스페이스 빌드 결과물, 캐시, 다운로드 네 경로 모두에 적용하며, 이미 그 이름이면 아무것도 하지 않습니다.
- **`compose_rust_host_*` 심벌의 보존.** 렌더러는 적재된 뒤 이름으로 이 심벌들을 찾습니다. 애플리케이션 코드는 그 이름을 한 번도 쓰지 않으므로 링커가 죽은 코드로 판단해 지웁니다. 지워진 바이너리는 실행 첫 순간에 심벌을 못 찾고 죽습니다. 주소를 담은 `#[used]` static이 죽은 코드 제거의 뿌리 역할을 합니다.

**이 크레이트의 빌드 스크립트는 rpath를 내보내지 않습니다.** 내보내면 이 저장소의 테스트 바이너리와 예제만 rpath로 살아나고, 이름 새기기가 고장나도 소비자 쪽에서만 드러납니다. rpath가 없으면 우리 바이너리가 소비자 바이너리와 같은 방식으로 적재되므로 같은 고장을 같은 자리에서 봅니다. 같은 이유로 `samples/`에는 `build.rs`가 없습니다.

##### 플랫폼별 수단

| 플랫폼 | 파일 안의 이름 | 빌드 스크립트가 하는 일 |
|---|---|---|
| macOS | `LC_ID_DYLIB`. 아티팩트는 `@rpath/libcompose_rust_renderer.dylib`로 나옵니다 | `install_name_tool -id <절대 경로>`. Xcode 명령줄 도구에 들어 있고 Rust 링커가 어차피 필요로 합니다 |
| Linux | `DT_SONAME`. 아티팩트에는 없습니다 | SONAME이 없으면 링커가 연 경로를 그대로 `DT_NEEDED`에 적으므로 할 일이 없습니다. 있으면 푸는 시점에 `.dynamic`에서 그 항목을 들어냅니다. patchelf는 쓰지 않습니다 |
| Windows | 없습니다. DLL은 로더의 탐색 경로로만 찾습니다 | 렌더러 디렉터리를 이름으로 말합니다. 소비자가 그 디렉터리를 `PATH`에 넣거나 안의 파일을 실행 파일 옆에 두어야 합니다 |

Linux 공유 객체에는 `$ORIGIN` 런패스가 붙어 있어 심이 옆에 있는 이미지를 찾습니다. macOS 렌더러는 자기 위치를 `dladdr`로 알아내 옆의 Skia와 AWT 라이브러리를 찾습니다. 그래서 위의 절대 경로는 렌더러가 실제로 풀린 자리여야 하고, 그 아래 배치가 `<루트>/lib/`로 유지되어야 합니다. 라이브러리 파일 하나만 다른 곳에 복사해 두고 그 경로를 이름으로 박으면 링크와 적재는 되지만 첫 프레임에서 Skia를 찾지 못합니다.

**Windows의 자동 해결은 아직 남아 있습니다.** 없애려면 빌드 스크립트가 `bin/`을 실행 파일 옆으로 복사해야 하고, 그러면 프로파일마다 수십 MB가 복사됩니다. **Windows 기계에서 확인할 수 있을 때 결정합니다.** 확인 없이 복사부터 넣으면 검증되지 않은 수십 MB짜리 동작이 모든 빌드에 들어갑니다. 그때까지는 빌드 스크립트가 할 일을 이름으로 말하는 것이 해결입니다.

##### 절대 경로가 지나가는 세 단계

절대 경로는 빌드한 기계의 사실이지 출하물의 사실이 아닙니다. 세 단계의 계약이 각각 다릅니다.

| 단계 | 누가 | 라이브러리 이름 | 지켜야 할 것 |
|---|---|---|---|
| 1. 우리가 배포 | 릴리스 아티팩트를 만드는 쪽 | 상대 이름. macOS는 `@rpath/libcompose_rust_renderer.dylib`, Linux는 SONAME 없음 | 아티팩트는 어디에 풀어도 되어야 합니다. 절대 경로를 박아 배포하면 그 경로가 있는 기계에서만 동작합니다 |
| 2. 소비자의 빌드 | `dioxus-compose`의 빌드 스크립트 | 그 기계에서의 절대 경로 | 이름을 바꾸는 대상은 소비자의 캐시나 작업 트리 안에 있는 사본입니다. 원본 아티팩트(`.tar.gz`)는 건드리지 않으므로 1단계는 그대로 유지됩니다 |
| 3. 소비자가 앱을 출하 | 애플리케이션의 번들러 | 다시 상대 이름. macOS는 `@executable_path/../Frameworks/lib/...`, Linux는 `$ORIGIN/lib` 런패스와 짧은 `DT_NEEDED` | 2단계가 실행 파일에 남긴 절대 경로를 반드시 지워야 합니다. 지우지 않으면 개발자의 홈 디렉터리 경로가 출하된 바이너리에 남고, 다른 기계에서는 적재에 실패합니다 |

3단계에서 렌더러를 어디에 복사하는지는 자유가 아닙니다. 렌더러는 자기가 적재된 디렉터리에서 Skia와 AWT 동반 파일을 찾고, AWT는 그 디렉터리의 부모에 `lib`을 붙인 자리에서 자기 파일을 읽습니다. macOS에서 확인한 결과, 동작하는 배치는 두 가지입니다. 렌더러 파일을 실행 파일 옆에 두는 것과, 렌더러의 `lib` 디렉터리를 통째로 옮기는 것(깊이는 상관없습니다)입니다. 그 둘 중 어느 쪽도 아닌 자리에 파일만 펼쳐 놓으면 안 됩니다. 파일을 `Contents/Frameworks`에 펼친 앱은 시작해서 렌더러까지 적재한 뒤 `Contents/lib/libjawt.dylib`을 찾다가 죽습니다. `Contents/Frameworks/lib`은 동작합니다.

3단계는 애플리케이션이 정하는 일이며 이 프로젝트의 범위 밖입니다. 범위 안의 책임은 3단계가 가능하도록 남겨 두는 것입니다. 2단계는 되돌릴 수 없는 것을 굽지 않습니다. 실행 파일이 기록한 절대 경로는 `otool -L`(macOS)과 `readelf -d`(Linux)로 그대로 읽히고, macOS는 `install_name_tool -change <절대 경로> <상대 이름>`으로, Linux는 `patchelf --replace-needed`와 `--set-rpath '$ORIGIN/lib'`로 바꿀 수 있습니다. 번들러는 앱 개발자가 한 번 준비하는 환경이므로 patchelf를 요구해도 됩니다. 이 저장소의 `scripts/bundle-renderer.sh`가 그 작업을 하고, 샘플 릴리스 워크플로가 샘플을 묶을 때 실제로 그것을 씁니다.


#### 5.3.5 렌더러가 링크되었는지는 기능 플래그가 아니라 빌드 결과가 정한다

`native-renderer`가 켜져 있다고 렌더러가 링크된 것은 아닙니다. docs.rs는 기능을 켠 채 아무것도 링크하지 않습니다. 빌드 스크립트는 렌더러를 실제로 링크했을 때만 `renderer_linked` cfg를 내보내고, Host는 그 cfg로 외부 심벌을 부를지 시끄럽게 실패할지 정합니다. 기능 플래그로 정하면 docs.rs가 만든 바이너리가 있지도 않은 심벌을 부르게 됩니다.

데스크톱이 아닌 타깃은 Cargo가 렌더러를 링크하지 않지만 심벌은 실행 시점에 존재합니다(iOS는 Xcode가 XCFramework를, 웹은 Kotlin/Wasm 모듈이 임포트를 해결합니다). 그쪽도 `renderer_linked`입니다.

#### 5.3.6 실패

| 상황 | 요구되는 동작 |
|---|---|
| 네트워크 없음 | 어떤 파일을 어디에 두면 되는지 이름과 경로로 말합니다. 파일을 그 자리에 두면 다음 빌드가 그것을 씁니다 |
| 해당 타깃의 아티팩트 없음 | 게시된 타깃 목록을 보여줍니다. 404를 그대로 보여주지 않습니다 |
| 체크섬 불일치 | 풀지 않고 실패합니다. 지울 경로를 알려줍니다 |
| `DOCS_RS` 설정됨 | 내려받지 않고, 렌더러를 링크하지 않고, 빌드는 성공합니다 |
| 렌더러 없이 만들어진 바이너리의 실행 | 조용히 성공하지 않습니다. 무엇이 없는지 표준 오류로 말하고 0이 아닌 상태로 끝냅니다 |

#### 5.3.7 수용 기준

1. 캐시가 이미 있으면 네트워크 접근 없이 빌드가 성공합니다.
2. 체크섬이 맞지 않는 아티팩트는 풀리지 않고 빌드가 실패합니다.
3. 게시되지 않은 타깃은 게시된 목록을 이름으로 말합니다.
4. `COMPOSE_RUST_RENDERER_DIR`은 캐시와 다운로드보다 우선합니다.
5. 렌더러도 mock도 없이 앱을 시작하면 성공을 반환하지 않습니다.
6. 빌드 스크립트가 출력하는 어떤 메시지도 저장소에 없는 파일을 실행하라고 안내하지 않습니다.
7. 비어 있는 캐시에서 실제 다운로드가 동작하고, 걸린 시간과 캐시 크기를 기록합니다.
8. 크레이트에 의존하기만 한 애플리케이션이 빌드되고, 실행되고, 창을 엽니다.
9. **`build.rs`가 없는 소비자 크레이트**가 빌드되고, rpath를 하나도 갖지 않고, 실행됩니다. 이것이 이 절의 회귀 기준입니다. `build.rs`를 가진 샘플로는 확인되지 않습니다.
10. 워크스페이스에서 직접 빌드한 렌더러도 같은 대접을 받습니다. 캐시에서 온 렌더러와 개발용 렌더러의 적재 방식이 다르면 안 됩니다.
11. 번들러를 거친 실행 파일에는 빌드한 기계의 절대 경로가 남아 있지 않습니다.

**2026-09-21 1에서 7까지 충족.** 1에서 6은 `dioxus-compose/tests/renderer_resolution.rs`가 매 실행 확인합니다(5는 렌더러가 정말 없는 빌드에서만 컴파일되므로 `scripts/check.sh`가 `--no-default-features`로 한 번 더 돌립니다). 7은 Apple Silicon Mac에서 캐시를 비우고 측정했습니다: **6.3초, 캐시 119MB**(`.tar.gz` 32MB + 푼 것 86MB). 8은 `cargo build -p sample-calculator`로 확인했습니다. 바이너리가 캐시 절대 경로를 기록했고 창이 열렸습니다.

**2026-09-21 9에서 11까지 macOS에서 충족.** 9는 `scripts/tests/consumer-crate.test.sh`가 확인합니다. `dioxus-compose/tests/fixtures/consumer/`에 `build.rs`가 없는 크레이트가 들어 있고, 스크립트가 그것을 빌드해 rpath 개수가 0인지와 실행이 0으로 끝나는지를 봅니다. cargo 테스트가 아니라 셸 스크립트인 이유는 두 가지입니다. 하나는 cargo 안에서 cargo를 부르는 일이라 같은 `target/`을 쓰면 잠금에서 멈추고, 별도 `target/`을 쓰면 의존성 전체를 한 번 더 빌드하기 때문입니다. 다른 하나는 이 확인이 링크된 실행 파일의 적재 명령을 읽는 일이라 확인 대상이 호스트 플랫폼의 도구(`otool`, `readelf`)이기 때문입니다. CI의 `shell` 잡이 `scripts/tests/*.test.sh`를 모두 돌립니다.

10은 `renderer_resolution.rs`가 워크스페이스 경로와 변수 경로 각각에 대해, 진짜 Mach-O 라이브러리를 만들어 이름이 절대 경로로 바뀌는지 확인합니다. 이미 제 자리 이름을 달고 있는 라이브러리는 쓰기 권한을 뗀 채로 통과해야 하므로, 다시 쓰지 않는다는 것도 같은 방식으로 확인됩니다. 11은 같은 스크립트의 후반부가 확인합니다. 만들어진 실행 파일과 렌더러를 임시 디렉터리로 복사해 `scripts/bundle-renderer.sh`로 상대 이름으로 바꾸고, 기록된 경로에 절대 경로가 남지 않았는지 읽은 뒤, 빌드와 아무 상관 없는 디렉터리에서 실행합니다.

이 절에 손으로 확인한 것은 다음과 같습니다. `build.rs` 없는 소비자 크레이트가 `LC_RPATH` 0개로 빌드되어 실행됐고, 샘플 네 개가 모두 같은 모양으로 빌드됐으며 `sample-calculator`가 창을 열었습니다. 번들한 샘플을 `/tmp` 아래 세 가지 배치(실행 파일 옆, `<루트>/lib`, `Contents/Frameworks/lib`)로 실행해 창이 뜨는 것을 확인했고, `Contents/Frameworks`에 펼친 배치가 실패하는 것도 확인했습니다.

Windows와 Linux는 이 저장소에 실행할 기계가 없어 자동 테스트와 코드 검토까지만 확인했습니다. Linux 쪽 판단은 게시된 `.so`의 ELF 헤더를 읽어 SONAME이 없고 런패스가 `$ORIGIN`임을 확인한 것이 근거이고, SONAME을 지우는 편집 자체는 손으로 만든 ELF 픽스처로 `renderer_resolution.rs`가 확인합니다. `scripts/tests/consumer-crate.test.sh`는 두 플랫폼에서 같은 검사를 하도록 쓰여 있으므로, Linux 확인의 다음 단계는 CI의 `shell` 잡이 그것을 돌리는 것입니다.

### 5.4 워크트리 빌드 격리 (NFR-12)

**요구사항: 한 체크아웃에서 실행한 빌드는 다른 체크아웃의 파일을 읽지도 쓰지도 않습니다.**

Cargo는 path 패키지의 유닛 해시에 패키지 경로를 넣지 않습니다. 그래서 워크트리 여럿이 빌드 디렉터리 하나를 같이 쓰면, 내용이 같은 두 워크트리가 캐시 항목 하나가 되고, 컴파일 시점에 박히는 절대 경로(`env!("CARGO_MANIFEST_DIR")`, `include_str!`, 빌드 스크립트의 `OUT_DIR` 산출물, 5.3.4의 절대 이름)가 전부 먼저 빌드한 쪽 것이 됩니다. 결정과 확인된 사고 두 건은 INTENT D16에 있습니다.

#### 5.4.1 설정

1. **빌드 디렉터리는 Cargo의 기본값입니다.** 워크스페이스 루트 아래의 `target/`이고, 워크트리마다 따로입니다.
2. **`target-dir`을 적은 기계 로컬 설정을 두지 않습니다.** `.cargo/config.toml`에도, `CARGO_TARGET_DIR`에도 두지 않습니다. 절대 경로를 적어 두는 설정은 체크아웃을 옮기거나 워크트리를 만들 때마다 어긋날 자리를 하나 남깁니다.
3. **예외는 스스로 만들고 스스로 지우는 임시 디렉터리뿐입니다.** `scripts/tests/consumer-crate.test.sh`가 임시 디렉터리를 `CARGO_TARGET_DIR`로 쓰는 것이 그 경우입니다. 바깥 cargo와 같은 `target/`을 쓰면 잠금에서 멈추기 때문입니다.
4. `scripts/setup-worktrees.sh`가 이 상태를 만들고 확인하며, 워크트리별 빌드 디렉터리 크기와 남은 공간을 함께 보고합니다. 디스크가 D16이 받아들인 비용이므로, 349GB 사건이 사후가 아니라 사전에 보여야 합니다.

#### 5.4.2 소스 트리에 쓰는 도구

설정은 어긋날 수 있으므로 소스 트리에 쓰는 도구는 스스로 확인합니다.

- 자기가 컴파일된 크레이트 디렉터리(`env!("CARGO_MANIFEST_DIR")`)와 cargo가 실행 시점에 환경으로 알려 준 크레이트 디렉터리(`CARGO_MANIFEST_DIR`)를 비교합니다.
- 두 값이 같으면 그 디렉터리에 씁니다.
- 실행 시점 값이 없으면(cargo를 거치지 않고 바이너리를 직접 실행한 경우) 컴파일된 디렉터리를 씁니다. 의도한 트리를 알 방법이 달리 없고, 그것이 그 바이너리가 아는 유일한 트리입니다.
- 두 값이 다르면 **아무것도 쓰지 않고** 두 경로와 고치는 법을 찍으며 실패합니다.

#### 5.4.3 수용 기준

1. 6줄짜리 크레이트를 두 디렉터리에 같은 내용으로 두고 각자 빌드 디렉터리를 주면, 각 디렉터리의 `cargo run`이 자기 디렉터리를 출력합니다. **같은 빌드 디렉터리를 주면 두 번째가 첫 번째에서 컴파일된 바이너리를 실행한다는 것은 단언하지 않고 함께 보고만 합니다.** cargo가 나중에 이것을 고치더라도 검사가 빨개지면 안 되고, 고쳐지든 아니든 이 저장소가 빌드 디렉터리를 나눈다는 규칙은 그대로이기 때문입니다.
2. `CARGO_MANIFEST_DIR`을 빈 임시 디렉터리로 두고 codegen 바이너리를 실행하면 종료 코드가 0이 아니고, 그 디렉터리는 비어 있으며, 메시지에 두 경로가 모두 나옵니다.
3. 이 저장소의 어떤 워크트리에도 `target-dir` 설정이 없고, 환경에 `CARGO_TARGET_DIR`도 없습니다.
4. `scripts/setup-worktrees.sh`와 에이전트 런처(`scripts/launch-agent.sh`)가 같은 규칙을 따릅니다. 런처는 빌드 디렉터리를 스스로 적지 않고 `scripts/setup-worktrees.sh`에 맡깁니다.

1과 3과 4를 `scripts/tests/worktree-target.test.sh`가, 2를 `dioxus-compose/tests/codegen_tree.rs`가 확인합니다. `scripts/setup-check.sh`도 이 체크아웃 하나에 대해 3을 봅니다.

**2026-09-22 측정.** 자기 `target/`에 처음부터 빌드하는 데(`cargo build --workspace --tests`, 레지스트리 캐시는 더운 상태) 23초가 걸렸고 `target/`은 1.0GB가 됐습니다. `scripts/check.sh`를 한 번 돌리면 벤치마크까지 포함해 2.0GB가 됩니다. 워크트리 여덟 개가 같이 쓰던 빌드 디렉터리 하나는 그때 16GB였습니다. 워크트리마다 나누는 쪽이 이 기계에서는 디스크도 덜 씁니다. 공유 디렉터리는 워크트리 여덟 개분의 핑거프린트를 한꺼번에 들고 있으면서 아무도 치우지 않기 때문입니다.

### 5.5 AWT 없는 데스크톱 렌더러 (NFR-14)

**2026-10-03 소유자 결정(INTENT D4).** 배포되는 데스크톱 렌더러는 AWT를 쓰지 않습니다. 렌더러가 자기 창을 직접 열고 Compose 장면을 그 창에 붙여 그립니다. macOS와 Linux는 Kotlin/Native 정적 라이브러리이고, Windows는 Kotlin/Native `mingwX64`로 같은 길을 갑니다(§5.6, NFR-13). GraalVM native-image 위의 AWT 경로는 Windows 렌더러가 NFR-13을 통과할 때까지 Windows가 지금 내는 것으로만 남습니다.

| 플랫폼 | 창 | 렌더러 | 입력기 경로 |
|---|---|---|---|
| macOS | AppKit `NSWindow`를 렌더러가 직접 엶 | Kotlin/Native 정적 라이브러리 | `NSTextInputClient`에서 Compose의 플랫폼 텍스트 입력으로 |
| Linux | X11 창을 렌더러가 직접 엶 | Kotlin/Native 정적 라이브러리 | XIM, ibus, fcitx에서 Compose의 플랫폼 텍스트 입력으로 |
| Windows | Win32 창을 렌더러가 직접 엶 | Kotlin/Native `mingwX64` 정적 라이브러리(NFR-13) | IMM32/TSF에서 Compose의 플랫폼 텍스트 입력으로 |

창 코드의 자리는 Compose의 포크입니다(INTENT D19, FR-19.8).

수용 기준:

1. 아무 설정 없이 빌드한 데스크톱 샘플이 macOS와 Linux에서 Kotlin/Native 렌더러를 링크합니다. 링크된 실행 파일에 AWT와 JVM이 없습니다(`libawt`, `libjvm`, `JNI_OnLoad_*` 심볼이 없고, 실행 중 `java.home`을 찾지 않음). 빌드 산출물을 검사하는 테스트로 확인합니다. Windows는 NFR-13의 기준이 같은 것을 확인합니다.
2. 앱을 빌드하는 기계에 JDK도 GraalVM도 필요 없습니다. 렌더러는 D10대로 아티팩트로 옵니다.
3. 스모크 호스트가 세 플랫폼에서 창을 띄워 그리고, 창을 닫으면 `run`이 0을 돌려줍니다(CI).
4. FR-19의 수용 기준(19.4, 19.7)이 이 경로에서 통과합니다.
5. §6 IME 체크리스트 아홉 항목이 세 플랫폼의 이 빌드에서 통과합니다(사람이 확인). Compose의 플랫폼 텍스트 입력을 우회하는 경로가 코드에 없습니다.
6. §7 접근성: 접근성 트리가 노출되고, macOS VoiceOver, Windows Narrator, Linux Orca(AT-SPI)가 Text와 Button 라벨을 읽습니다(사람이 확인).
7. §5.1 프레임 예산과 NFR-3 무게 기준을 이 빌드에서 잽니다.

### 5.6 Windows 단일 실행 파일 (NFR-13)

**2026-10-03 소유자 결정(INTENT D18). 실험이 아니라 정식 통합입니다.** Windows 렌더러는 Kotlin/Native(`mingwX64`)로 빌드한 정적 라이브러리(`renderer/windows/`, `staticlib-windows/`)이고, Rust 앱의 MSVC 실행 파일에 링크됩니다. Skia와 skiko의 C++ 부분은 MSVC로 빌드해 같은 실행 파일에 들어갑니다. Compose와 skiko의 `mingwX64` 타깃은 Compose 포크의 커밋입니다(INTENT D19). 경계와 그 이유는 INTENT D18에 있습니다.

**MinGW는 렌더러 아티팩트를 만드는 빌드 안에만 있습니다.** 앱을 빌드하는 사람의 툴체인은 Rust의 기본 Windows 타깃(`x86_64-pc-windows-msvc`)과 MSVC 링커이고, MinGW 툴체인, MinGW DLL, `windows-gnu` 타깃 어느 것도 요구하지 않습니다. 렌더러 아티팩트에 들어가는 MinGW 오브젝트는 MSVC 링커가 뜻을 바꾸지 않고 읽도록 고쳐진 것입니다(생성자 섹션, 되감기 정보).

수용 기준:

1. 샘플을 빌드한 결과물이 실행 파일 하나이고, 불러오는 DLL이 Windows 시스템 DLL뿐입니다. 빌드 산출물의 import 표를 검사하는 테스트로 확인합니다.
2. `icudtl.dat` 없이 글자가 그려집니다.
3. 함수 여러 개를 지나는 Kotlin 예외가 잡히고, 잡히지 않은 예외는 보고된 뒤 종료됩니다(테스트).
4. MinGW 오브젝트를 고치는 단계를 빼면 2와 3이 실패하는 것을 대조 테스트가 확인합니다.
5. 스모크 호스트가 창을 띄우고 0으로 끝납니다(Windows CI).
6. §6 IME 체크리스트 핵심 5개를 통과합니다(Windows에서 손으로 확인).
7. 접근성 트리가 노출됩니다(NFR-8과 같은 기준, Windows).
8. 앱을 빌드하는 기계에 MinGW 툴체인이 없어도 `cargo build`가 성공하고, 앱의 Rust 타깃은 `x86_64-pc-windows-msvc`입니다.

### 5.7 데스크톱 애플리케이션은 실행 파일 하나 (NFR-15)

**2026-10-03 소유자 결정(INTENT D17). compose-rust 1.0.0(2026-10-20)의 요구사항입니다.** compose-rust로 만든 데스크톱 애플리케이션은 macOS, Windows, Linux 모두에서 실행 파일 하나입니다.

이것은 INTENT D2와 D4에서 따라 나옵니다. 애플리케이션은 `cargo build`가 내는 Rust 실행 파일이고(D2), AWT 없는 렌더러는 Kotlin/Native 정적 라이브러리라 그 실행 파일에 링크됩니다(D4, Windows는 D18). Skia와 skiko의 C++ 부분, ICU 데이터도 같은 실행 파일 안에 들어갑니다.

| 플랫폼 | 실행 파일 안에 있는 것 | 실행 파일이 불러오는 것 |
|---|---|---|
| macOS | 앱, compose-rust, 렌더러, Compose, Skia, skiko C++ | 시스템 프레임워크와 `/usr/lib`의 시스템 라이브러리 |
| Windows | 위와 같고 ICU 데이터 포함(§5.6) | Windows 시스템 DLL |
| Linux | 위와 같고 ICU 데이터 포함 | 배포판의 시스템 라이브러리(libc, X11, GL, fontconfig, FreeType 계열) |

수용 기준:

1. 세 플랫폼에서 샘플을 릴리스로 빌드한 결과물이 실행 파일 하나입니다. 그 실행 파일을 빈 디렉터리에 홀로 복사해 실행하면 창이 뜨고 샘플을 그립니다.
2. 실행 파일이 불러오는 라이브러리가 위 표의 시스템 라이브러리뿐입니다. macOS는 `otool -L`, Linux는 `DT_NEEDED`, Windows는 import 표를 검사하는 테스트로 확인합니다. 렌더러, Skia(`libskiko-*`, `skiko-windows-*.dll`), JVM, AWT 라이브러리 이름이 나오면 실패입니다.
3. `icudtl.dat`이 실행 파일 옆에도 시스템 경로에도 없는 상태에서 한국어 단어 경계와 글자 그리기가 맞습니다.
4. 실행 중 렌더러가 경로로 라이브러리를 찾는 일이 없습니다(`dlopen`, `LoadLibrary`, `System.load`로 우리 라이브러리를 여는 코드가 없음).
5. 앱을 빌드하는 기계에 필요한 것은 Rust 툴체인과 그 플랫폼의 기본 링커뿐이고, 렌더러 아티팩트는 D10대로 빌드 스크립트가 받아 옵니다. 앱의 `Cargo.toml`에 compose-rust 한 줄 외에 빌드 설정(`.cargo/config.toml`, `RUSTFLAGS` 포함)을 요구하지 않습니다. Windows의 C 런타임은 2026-10-03 소유자 결정("(b)안으로 가자")대로 크레이트의 빌드 스크립트가 정적 C 런타임을 링크하고 부딪히는 기본 라이브러리를 막아서 맞춥니다(INTENT D18).
   - `.cargo/config.toml`도 `RUSTFLAGS`도 없는 새 앱이 windows-latest에서 빌드됩니다.
   - 그 실행 파일의 `dumpbin /dependents` 결과에 Windows 시스템 DLL만 나오고 `vcruntime140.dll`, `msvcp140.dll`이 없습니다.
   - 그 실행 파일이 빈 폴더에서 실행되어 창을 띄웁니다.
   - 동적 C 런타임으로 미리 빌드된 C/C++ 라이브러리와 섞여 링커가 `RuntimeLibrary` 불일치(LNK2038)를 내면, 빌드 스크립트가 어느 라이브러리가 동적 런타임인지와 무엇을 하면 되는지를 짚는 메시지를 냅니다. 테스트로 확인합니다.
   - 같은 샘플을 동적 런타임과 정적 런타임으로 각각 링크한 실행 파일 크기를 재서 여기에 적습니다(측정 전).
6. FR-22.6의 번들(`.app`, `.dmg`, `.zip`, `.msix`, `.AppImage`)은 이 실행 파일 하나와 아이콘, 메타데이터만 담습니다.

## 6. IME 수용 체크리스트 (FR-5, M1)

native-image 빌드에서 macOS와 Windows 각각 수동으로 확인합니다.

**2026-10-03(INTENT D4, NFR-14): 확인 대상은 배포되는 빌드, 즉 AWT 없는 렌더러입니다.** 아래의 macOS 결과는 그 전의 native-image(AWT) 빌드에서 얻은 것이므로, macOS, Linux, Windows의 AWT 없는 빌드에서 아홉 항목을 다시 확인합니다.

**macOS arm64 결과 (2026-09-21, Liberica NIK 25, native-image 빌드)**: 5개 항목을 사람이 직접 확인했습니다. 조합 과정 표시, 조합 중 자모 단위 백스페이스, 조합 중 화살표 이동 시 확정 후 이동, 문장 중간 삽입, 한글 폰트 폴백입니다. **M1의 관문이 이 항목이었고, 통과했습니다.**

확인 절차: `COMPOSE_RUST_SMOKE_IME=1 desktop/scripts/smoke-test.sh`로 텍스트 필드가 있는 스모크 창을 띄우고 한국어 입력기로 입력합니다. 이 플래그가 없으면 창에 라벨과 버튼만 있어서 타이핑할 곳이 없습니다.

남은 4개는 미확인이며, 자동화할 수 없으므로 사람이 실행해야 합니다.

여기서 발견한 실패 양상을 남겨 둡니다. 등록되지 않은 입력 경로는 빌드도 렌더링도 멀쩡히 통과한 뒤, 입력기가 텍스트 필드를 건드리는 순간 Objective-C 예외로 프로세스를 abort시킵니다. Java 스택 트레이스 없이 창이 그냥 사라지므로, 이 증상이 보이면 실행 로그에서 `JNI Lookup Exception`과 그 앞의 `NoSuchMethodError`를 먼저 찾으십시오. 근본 대응은 `ImeReachabilityFeature`가 패키지 단위로 등록하는 것입니다(INTENT D9-macOS).

- [x] "안녕하세요" 입력 시 조합 과정이 정상 표시됨
- [x] 조합 중 백스페이스로 자모 단위 삭제
- [x] 조합 중 화살표로 커서 이동 시 조합 확정 후 이동
- [x] 문장 중간에 커서를 두고 한글 삽입
- [ ] 멀티라인 필드에서 조합 중 Enter 처리 (조합 확정과 줄바꿈/제출 구분)
- [ ] 한글이 섞인 긴 텍스트 붙여넣기
- [ ] 일본어/중국어 IME 후보창이 커서 위치에 뜸
- [ ] Host가 `TextChanged`를 받는 동안 조합이 리셋되지 않음
- [x] 한글 폰트 폴백 (두부 문자 없음)

native-image(AWT) 빌드에서 실패하면 INTENT D4의 이전 결정에 따라 native-image 설정(ServiceLoader, JNI/리플렉션 config, 로케일/문자셋)부터 점검합니다. AWT 없는 빌드에서는 그 플랫폼의 창이 입력기 사건을 Compose의 플랫폼 텍스트 입력에 넘기는 자리부터 봅니다.

**입력 경로 등록은 반드시 빌드 시점 Feature(`ImeReachabilityFeature`)로 합니다.** 메타데이터에 `allDeclaredMethods`를 쓰면 안 됩니다. JDK가 선언만 하고 라이브러리에 넣지 않은 네이티브 메서드(`CInputMethod.nativeHandleEvent`)까지 링크 대상이 되어, Java 스택 트레이스 없이 dyld 단계에서 라이브러리 로드가 실패합니다. 반대로 등록이 부족하면 렌더링까지 정상 동작한 뒤 입력기가 텍스트 필드를 건드리는 순간 프로세스가 abort합니다.

## 7. 접근성 (NFR-8)

**2026-09-21: 네이티브 이미지가 라벨된 접근성 트리를 노출하고, 트리를 읽어도 죽지 않습니다.**

| | JVM 개발 셸 | native-image (수정 전) | native-image (수정 후) |
|---|---|---|---|
| 트리 요소 수 | 14 | 1 | 12 |
| 라벨된 컨트롤 | 있음 | 없음 | `AXStaticText`, `AXButton` |
| 질의 후 프로세스 | 정상 | 중단(exit 134) | 정상 |

### 원인: 네이티브 링크에서 클래스가 제거됨

Objective-C 쪽은 Java의 역할(role)을 **클래스 이름 문자열**로 바꿔 `NSClassFromString`으로 찾습니다. 그래서 `GroupAccessibility`, `ButtonAccessibility`, `StaticTextAccessibility`, `IgnoreAccessibility`를 심볼로 참조하는 코드가 이미지 안에 없습니다. `-force_load`가 오브젝트를 가져와도 링커가 참조 없는 클래스로 보고 제거합니다. 수정 전 빌드에는 아카이브의 `*Accessibility` 클래스 38개 중 12개만 남아 있었고, 사라진 것이 정확히 역할이 가리키는 클래스들이었습니다.

`NSClassFromString`이 nil을 반환하고, `[nil alloc]`이 nil이 되고, 그 nil을 AppKit이 자식 배열에 넣으려다 예외를 던집니다.

**정적 분석이 이것을 볼 수 없습니다.** 문제가 Java 도달 가능성이 아니라 네이티브 링크에 있기 때문입니다. `--exact-reachability-metadata`가 아무 누락도 보고하지 않으면서 증상이 그대로였던 이유입니다.

기각된 가설 둘을 기록해 둡니다. `GetFieldID(AccessibleRole, "key")` 실패는 원인이 아닙니다. 해당 네이티브가 **호출조차 되지 않았습니다**. Java 예외도 아닙니다. Java 예외는 `NSGenericException`으로 나타나며 실제 예외는 `NSInvalidArgumentException`이었고, 이는 nil 자식만이 만듭니다.

### 수정

`build-native.sh`가 아카이브에서 `*Accessibility` 클래스 목록을 읽어 전부 링크 루트(`-Wl,-u`)로 지정합니다. JDK가 역할을 추가해도 목록이 낡지 않습니다. 이미지 크기는 50KB 늘었습니다.

`desktop/scripts/tests/accessibility-link.test.sh`가 아카이브의 클래스와 빌드된 라이브러리의 클래스를 비교해 누락이 있으면 실패합니다. 빌드도 스모크 테스트도 둘 다 통과하므로, 이 검사가 없으면 부재를 알 방법이 없습니다. CI의 macOS 빌드 뒤에 붙였습니다.

### 남은 수용 기준

- [x] native-image의 접근성 트리가 JVM과 같은 구조로 노출됨
- [x] 트리를 질의해도 프로세스가 중단되지 않음
- [ ] macOS VoiceOver가 Text와 Button 라벨을 읽음: **사람이 확인해야 합니다**
- [ ] Tab 키 포커스 순회
- [ ] Windows Narrator: Windows 빌드 검증 후

VoiceOver 수동 절차는 `experiments/accessibility/README.md`에 있습니다. 오늘까지는 VoiceOver를 켜는 것만으로 프로세스가 죽어서 2단계를 넘어갈 수 없었고, 이제 끝까지 진행할 수 있습니다.

## 8. 열린 질문 추적

[PROJECT.md](../PROJECT.md#열린-질문)의 열린 질문 표를 참고하세요. 결정이 나면 해당 SPEC 항목을 `Agreed`로 올리고 INTENT에 결정을 기록합니다.
