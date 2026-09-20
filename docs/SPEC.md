# SPEC

이 문서는 dioxus-compose의 요구사항과 수용 기준을 정의합니다. 근거는 [INTENT.md](INTENT.md)에 있습니다.

- 상태 표기: `Draft`(합의 전), `Agreed`(구현 가능), `Done`(수용 기준 통과)
- 요구사항 ID는 한 번 부여하면 재사용하지 않습니다. 폐기할 때는 `Withdrawn`으로 남깁니다.

## 1. 용어

| 용어 | 정의 |
|---|---|
| Host | Rust 프로세스. 사용자 UI 코드(Dioxus 컴포넌트)와 도메인 로직을 소유합니다 |
| Renderer | AOT 컴파일된 Kotlin/Compose 네이티브 라이브러리. 스키마 인터프리터를 포함합니다 |
| Schema | Renderer가 해석할 수 있는 위젯 타입, 속성, Modifier, 이벤트의 닫힌 집합 |
| Mutation | 노드 트리 변경 명령 한 단위 |
| Event | Renderer에서 Host로 올라오는 사용자 입력 알림 |
| Node ID | Host가 발급하는 노드 식별자. 해당 세션 안에서 고유합니다 |
| Handler ID | Host가 발급하는 이벤트 핸들러 식별자 |

## 2. 아키텍처

```
┌──────────────── Host (Rust) ────────────────┐
│ 사용자 컴포넌트 (rsx!, hooks)                  │
│ dioxus-core VirtualDom                       │
│ dioxus-compose renderer: Mutations → bytes   │
└───────────────────┬─────────────────────────┘
                    │  동기 직접 호출 (UI 스레드) + 배치 버퍼
┌───────────────────┴──── Renderer (Kotlin) ───┐
│ 코드젠 심 (@CEntryPoint / @CName / JNI / wasm) │
│ 프로토콜 디코더 → Node 테이블 (snapshot state)  │
│ 스키마 인터프리터 @Composable RenderNode       │
│ Compose Desktop(AWT) / Compose iOS(UIKit)    │
└──────────────────────────────────────────────┘
```

## 3. 기능 요구사항

### FR-1 노드 트리 구성 (`Agreed`)
Host는 Mutation 시퀀스로 Renderer의 노드 트리를 생성, 수정, 삭제, 이동할 수 있어야 합니다.
- 수용 기준: `Create`, `SetProp`, `SetModifier`, `Insert`, `Move`, `Remove`로 임의의 트리를 만들 수 있고, 적용 결과가 Renderer의 트리 덤프와 일치합니다.

### FR-2 스키마 기반 렌더링 (`Agreed`)
Renderer는 스키마에 정의된 위젯 타입만 해석해서 해당 Compose 컴포저블로 렌더링합니다.
- 최소 스키마(M0): `Column`, `Row`, `Box`, `Text`, `TextField`, `Button`, `Spacer`, `LazyColumn`(FR-8)
- 디자인 확장: `ScrollColumn`. 값 모델은 FR-13, 테마는 FR-14를 따릅니다
- 수용 기준: 스키마에 없는 타입이나 속성을 받으면 크래시하지 않고 `ProtocolError` 이벤트를 보냅니다.

### FR-3 이벤트 전달 (`Agreed`)
사용자 입력은 `(node_id, handler_id, payload)` 형태로 Host 핸들러를 **동기로 직접 호출**합니다(PR-1). 핸들러는 반환값을 돌려줄 수 있습니다. 클로저는 경계를 넘지 않습니다.
- 수용 기준: Button 클릭이 등록된 Rust 핸들러를 정확히 한 번 호출합니다.

### FR-4 상태 갱신 반영 (`Agreed`)
Host 상태가 변경되면 변경분만 전송하고, Renderer는 해당 노드만 recomposition합니다.
- 수용 기준: Text 하나의 내용을 바꿀 때 전송되는 Mutation은 `SetProp` 1건입니다. 형제 노드는 recomposition되지 않습니다(recomposition 카운터로 확인).

### FR-5 비제어 TextField (`Agreed`)
- TextField의 편집 값과 조합 상태는 Renderer가 소유합니다.
- Renderer는 변경을 알림 이벤트(`TextChanged`, 디바운스 적용)와 확정 이벤트(`TextSubmitted`, `FocusLost`)로 보냅니다.
- Host가 값을 바꿀 때는 명시적 명령 `SetText(node_id, text, selection)`을 씁니다. Renderer는 IME 조합이 진행 중이면 조합이 끝날 때까지 적용을 미룹니다.
- 수용 기준: §6 IME 체크리스트를 통과합니다. **핵심 5개 항목은 2026-09-21 native-image 빌드에서 확인했습니다.** 나머지 4개(멀티라인 Enter 처리, 한글 혼합 붙여넣기, 일본어/중국어 후보창 위치, TextChanged 중 조합 유지)는 미확인이므로 `Done`이 아닙니다.

### FR-6 Dioxus 렌더러 (`Agreed`)
`dioxus-core` VirtualDom의 `Mutations`를 프로토콜 Mutation으로 변환하는 렌더러를 제공합니다.
- 사용자 코드는 `rsx!`와 훅만으로 작성하고, 프로토콜을 직접 다루지 않습니다.
- 수용 기준: M0 화면을 `rsx!` 컴포넌트로 재작성했을 때 동일하게 동작합니다.

### FR-7 스키마 코드젠 (`Draft`)
위젯, 속성, Modifier, 이벤트 페이로드는 Rust에서 단일 소스로 정의하고 Kotlin 타입과 코덱을 생성합니다.
- 양쪽 모두 exhaustive match가 적용됩니다(Rust `enum`은 Kotlin `sealed interface`로 생성).
- 핸드셰이크 때 스키마 해시를 비교해서 불일치하면 초기화를 실패시킵니다.
- 수용 기준: Rust 스키마에 속성을 추가하고 Kotlin 인터프리터를 갱신하지 않으면 **빌드가 실패**합니다.

### FR-8 LazyColumn 윈도잉 (`Agreed`)
- Host는 아이템 총 개수와 안정적인 key를 알립니다.
- Renderer는 보이는 범위를 `RangeRequested`로 요청하고, Host는 **요청받은 구간만 정확히** 생성합니다. Host가 구간을 넓히지 않습니다.
- **선읽기 버퍼는 Renderer가 소유합니다.** Renderer가 가시 범위에 버퍼를 더한 값을 `start`, `count`로 보냅니다. 스크롤 위치가 Renderer에 있으므로(D5) 얼마나 미리 읽을지 아는 쪽도 Renderer입니다. Host가 따로 버퍼를 더하면 Renderer는 받은 서브트리가 전체 목록의 몇 번째 자리에 놓이는지 알 수 없습니다. `start`가 곧 첫 아이템의 전역 인덱스라는 것이 이 규칙의 핵심입니다.
- 아이템 식별: Host가 아이템마다 `Box` 래퍼 노드를 만들고 `item_key`(문자열)를 실어 보냅니다. Renderer는 그 값을 Compose `LazyColumn`의 key로 씁니다.
- Renderer는 `item_count`개짜리 실제 Compose `LazyColumn`을 그립니다. 전역 인덱스 `i`는 `i - start`번째 자식으로 그리고, 구간 밖은 빈 자리로 둡니다. 그래서 스크롤 막대와 스크롤 거리가 전체 목록 기준으로 맞습니다.
- 와이어: `RangeRequested`는 이벤트 태그 7(24바이트, `start: u32`, `count: u32`)입니다. Host는 `item_count`, `item_key`, `on_range_requested` 속성으로 선언합니다.
- 수용 기준: 아이템 10,000개 목록에서 생성된 노드 수가 가시 범위와 버퍼에 비례합니다. **(Host 측 통과: 가시 20 + 버퍼 4 요청에 아이템 28개)**

### FR-9 스트리밍 텍스트 (`Agreed`)
긴 텍스트가 점진적으로 늘어나는 경우를 위해 Text 노드에 `AppendText` 명령을 둡니다(태그 8, 16바이트). 전체 문자열이 아니라 늘어난 꼬리만 보냅니다. Host는 추가분을 모아 프레임당 노드별 1건으로 flush하며, flush 지점은 `render_frame`입니다.
- 수용 기준: 초당 100회 추가되는 스트리밍 중에도 스크롤과 입력이 끊기지 않습니다. **(Host 측 통과: 36KB 텍스트에서 배치 64바이트 미만, 스트리밍 프레임 p99 125ns)**

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

### FR-10 Modifier 값 모델 (`Agreed`)
Modifier는 값 리스트로 직렬화합니다. 예: `[Padding(16), FillMaxWidth, Background(argb), Clickable(handler_id)]`. Renderer는 이를 `Modifier` 체인으로 재구성합니다.

### FR-13 디자인 프리미티브 (`Agreed`)
위젯만으로는 디자인을 할 수 없습니다. 스키마에 **값 모델**이 필요합니다. 값은 고정 레이아웃 레코드(PR-4)를 넘어야 하므로, Modifier 한 변형이 쓸 수 있는 공간은 `(tag: u16, first: u64, second: u64)`뿐입니다. 아래 프리미티브는 모두 이 한도 안에 들어갑니다.

원칙: **역할(role)을 우선하고 리터럴은 탈출구로 둡니다.** 역할은 FR-14의 디자인 시스템이 해석하고, 리터럴은 그대로 그립니다.

#### 13.1 색: `Color`와 `ColorRole`, 그리고 `Paint`
- `Color`는 `u32` ARGB 한 개입니다(`#[repr(transparent)]`). 그라데이션과 이미지 브러시는 넣지 않습니다.
- `ColorRole`은 의미 슬롯입니다: `Primary`, `OnPrimary`, `Secondary`, `OnSecondary`, `Surface`, `OnSurface`, `SurfaceVariant`, `OnSurfaceVariant`, `Background`, `OnBackground`, `Outline`, `OutlineVariant`, `Error`, `OnError`.
- `Paint`는 둘 중 하나입니다: `Paint::Role(ColorRole)` 또는 `Paint::Literal(Color)`. `u64` 하나로 인코딩합니다(상위 32비트 = 종류, 하위 32비트 = 값).
- 색을 받는 자리는 전부 `Paint`를 씁니다. 색 표현이 스키마에 두 번 등장하지 않게 하기 위해서입니다.
- 수용 기준: `Modifier::Background(Paint::Role(ColorRole::Surface))`와 `Modifier::Background(Paint::Literal(Color::rgb(0x1B1B1F)))`가 같은 레코드 길이로 왕복하고, 디코드 결과가 입력과 같습니다.

#### 13.2 타이포그래피
- `TypeRole`은 9단 사다리입니다: `Display`, `Headline`, `Title`, `Subtitle`, `Body`, `BodyStrong`, `Label`, `Caption`, `Mono`. 세 디자인 시스템의 타입 스케일이 모두 이 사다리에 대응합니다.
- `Text`의 속성으로 개별 재정의를 둡니다. 각각 독립된 `SetProp`이라서 FR-4의 변경분 전송이 유지됩니다.
  - `type_role`, `font_size`(sp), `font_weight`(100~900), `line_height`, `letter_spacing`, `color`(`Paint`), `text_align`(`Start|Center|End|Justify`), `max_lines`, `overflow`(`Clip|Ellipsis|Visible`)
- `type_role`만 지정한 Text는 디자인 시스템이 정한 크기·굵기·행간·자간을 그대로 씁니다. 재정의 속성이 있으면 그 축만 덮어씁니다.
- 수용 기준: `Text { type_role: Title }` 한 개는 `SetProp` 1건만 보냅니다. `font_size`만 바꾸면 추가 `SetProp` 1건만 전송됩니다.

#### 13.3 모양: `Shape`와 `Border`
- `Modifier::Shape { top_start, top_end, bottom_end, bottom_start }`, f32 4개를 `u64` 2개에 담습니다.
- `Modifier::ShapeRole(ShapeRole)`, `None|ExtraSmall|Small|Medium|Large|Full`. 실제 반지름은 디자인 시스템이 정합니다. Material 3의 12dp, HIG의 연속 곡률 느낌, Fluent의 4dp가 여기서 갈립니다.
- `Modifier::Border { width, paint }`, `first` 하위 32비트에 너비, `second`에 `Paint`.
- 테두리와 모양은 서로 독립입니다. Renderer는 `ShapeRole`/`Shape` 중 마지막에 적용된 것을 클립과 테두리 모두에 씁니다.

#### 13.4 간격과 배치
- `SpaceRole`: `None|Xs|Sm|Md|Lg|Xl|Xxl`. 밀도가 디자인 시스템마다 다른 부분이라 리터럴 dp보다 역할이 먼저입니다.
- `Modifier::PaddingRole(SpaceRole)`, 기존 `Modifier::Padding(f32)`는 유지합니다.
- `Modifier::PaddingEach { start, top, end, bottom }`, f32 4개.
- `Modifier::Weight(f32)`, `RowScope`/`ColumnScope`의 weight입니다.
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
- 지연되지 않는 가로 스크롤 컨테이너(`ScrollRow`)는 여전히 넣지 않습니다. 세 샘플 어디에도 필요가 없고, 넣으면 검증되지 않은 위젯이 하나 늘어납니다.

#### 13.7 넣지 않은 것과 이유
| 제외 | 이유 |
|---|---|
| 그라데이션, 이미지 브러시 | `(u64, u64)`에 들어가지 않고, 리소스가 경계를 넘어야 합니다(PR-4 위반) |
| 폰트 패밀리, 커스텀 폰트 | 폰트 리소스는 Renderer 번들에 있습니다. Host가 이름을 보내면 존재 검증이 런타임으로 밀립니다 |
| 아이콘·이미지 위젯 | 에셋 전달 프로토콜이 따로 필요합니다. 별도 요구사항으로 분리합니다 |
| 애니메이션 스펙(duration, easing) | 모션은 디자인 시스템의 규칙입니다(FR-14). Host가 값을 주면 13.5와 같은 이유로 무너집니다 |
| 블러/머티리얼(HIG vibrancy), 리플 설정 | 플랫폼 전용 효과라 세 시스템 공통 축이 아닙니다 |
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

### FR-14 디자인 시스템과 테마 모드 (`Agreed`)
디자인 시스템은 **토큰 집합 + 컴포넌트 스타일 규칙**의 한 쌍입니다. 속성을 모아 놓은 것이 아닙니다.

지원 대상은 두 단계로 나눕니다.

| 단계 | 디자인 시스템 |
|---|---|
| 1단계 | Material 3, Cupertino, WinUI/Fluent 2 |
| 2단계 | GNOME 50, KDE Breeze, Deepin |

2단계는 1단계가 동작한 뒤에 추가합니다. 14.1의 추상화가 성립하면 각각 `DesignSystem` 변형 1개와 Renderer 측 테이블 1개, 규칙 구현 1개로 끝나야 하며, 이것이 그 추상화의 실제 검증입니다.

#### 14.1 추상화
- 위젯은 **역할만 내보냅니다**(FR-13의 `ColorRole`, `TypeRole`, `ShapeRole`, `SpaceRole`, 그리고 `ButtonVariant` 같은 컴포넌트 변형).
- 디자인 시스템은 Renderer 안에 있는 **토큰 테이블 + 컴포넌트 규칙 구현** 한 쌍입니다.
- 따라서 **네 번째 디자인 시스템을 추가할 때 위젯 코드, 속성, Modifier, 와이어 포맷은 건드리지 않습니다.** Rust `DesignSystem` enum에 변형 1개, Kotlin에 테이블 1개와 규칙 구현 1개를 더하면 끝입니다.
- 수용 기준: `DesignSystem`에 변형을 하나 추가했을 때 `widgets.rs`의 위젯 정의와 Modifier/Property 스키마가 변경되지 않습니다.

#### 14.1-2 Apple 디자인 시스템의 이름과 현재 언어

**이름은 `Cupertino`입니다.** HIG(Human Interface Guidelines)는 지침 문서이지 디자인 시스템의 이름이 아닙니다. Apple은 자사 디자인 언어에 공개된 제품명을 붙이지 않으므로, 크로스플랫폼 툴킷에서 Apple 스타일 위젯 집합을 가리키는 관례적 이름인 `Cupertino`를 씁니다.

**현재 언어는 Liquid Glass입니다.** macOS 26과 iOS 26부터 Apple의 디자인 언어가 바뀌었고, 이 프로젝트가 대상으로 하는 macOS가 그 버전입니다. 평면 채움과 단색 배경을 전제한 이전 스타일로는 플랫폼을 따라간다고 할 수 없습니다. Cupertino 토큰과 컴포넌트 규칙은 다음을 표현해야 합니다.

- **재질(material)**: 컨트롤과 표면이 뒤 배경을 비춥니다. 불투명 채움이 아니라 반투명 레이어와 흐림입니다.
- **가장자리 하이라이트**: 광원을 받은 유리처럼 테두리 상단이 밝고 하단이 어둡습니다. 단색 1px 테두리와는 다릅니다.
- **동심 곡률**: 안쪽 요소의 모서리 반경이 바깥 컨테이너와 동심을 이루도록 계산됩니다. 고정된 반경 값 하나로는 표현되지 않습니다.
- **깊이**: 그림자보다 레이어의 겹침과 굴절로 깊이를 나타냅니다.

- 수용 기준
  1. `DesignSystem::Cupertino`로 그린 화면이 재질, 가장자리 하이라이트, 동심 곡률에서 Material 3 및 Fluent와 눈으로 구분됩니다.
  2. 배경이 바뀌면 그 위의 컨트롤 색이 따라 바뀝니다. 고정 색을 칠하고 끝내지 않습니다.
  3. 흐림을 지원하지 않는 환경에서도 읽을 수 있는 대체 표현이 있습니다(대비를 지키는 불투명 재질).
  4. 접근성 설정의 투명도 감소를 존중합니다.

**Renderer 구현 제약 (조사 완료, 2026-09-21)**

Compose Multiplatform은 Liquid Glass를 그릴 수 없습니다. JetBrains가 명시합니다([ios-liquid-glass](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html)). 효과는 **시스템이** 네이티브 SwiftUI `TabView`, `NavigationStack`, 툴바 API를 통해 그리며, Compose 앱이 채택하려면 Compose 콘텐츠를 **네이티브 SwiftUI 셸로 감싸야** 합니다. iOS 26 + Xcode 26 전용이고, Compose 쪽 API는 없습니다. 이 문서는 iOS만 다루므로 데스크톱에 대해서는 아무 말도 하지 않습니다.

따라서 요구사항을 둘로 나눕니다. 경계는 **흐리게 할 대상이 우리가 그린 것이냐**입니다.

1. **우리가 그릴 수 있는 것 (1.0 범위).** 뒤에 있는 것이 우리 앱 콘텐츠인 경우입니다. 동심 곡률, 가장자리 하이라이트(상단 밝고 하단 어두움), 레이어 겹침으로 표현하는 깊이, 앱 콘텐츠 위의 반투명 컨트롤 표면, 그리고 우리가 그린 콘텐츠에 대한 Compose 흐림. 이것들은 흉내가 아니라 실제 구현이며, iOS 26 화면처럼 보이게 하는 요소의 대부분입니다.
2. **우리가 그릴 수 없는 것.** 창 뒤의 **시스템 배경**을 비추는 재질입니다. macOS에서는 `NSVisualEffectView`가 필요하고 그것은 Compose 표면 바깥의 플랫폼 경로입니다. 우리가 `NSApplication`을 직접 만들므로(INTENT D9-macOS) 이론적으로 접근 가능성은 있으나, 1.0 범위 밖입니다. iOS에서 진짜 Liquid Glass는 SwiftUI 셸의 내비게이션 크롬에만 적용되며, 그 크롬은 Rust가 작성하는 UI가 아닙니다.

수용 기준 2의 "배경"은 **컨트롤 뒤의 앱 콘텐츠**를 뜻합니다. 창 뒤의 바탕화면이 아닙니다.

**흉내만 낸 반투명으로 "구현했다"고 표시하지 않습니다.** 1번 항목은 실제로 그리는 것이므로 이 규칙에 걸리지 않지만, 2번을 했다고 주장해서는 안 됩니다.

#### 14.2 컴포넌트 변형
컴포넌트 규칙이 붙는 자리는 변형(variant) 속성입니다. 값은 디자인 시스템 중립 이름입니다.
- `Button.variant`: `Filled | Tonal | Outlined | Text`
  - Material 3: Filled/Tonal/Outlined/Text 버튼, 큰 곡률, 리플.
  - Cupertino: Filled은 강조 버튼(연속 곡률, 그림자 없음), Tonal은 회색 배경, Text는 내용 색만 쓰는 plain 버튼. 리플 대신 하이라이트.
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
| macOS, iOS | Cupertino |
| Windows | WinUI/Fluent 2 |
| Linux (GNOME) | GNOME 50 |
| Linux (KDE) | KDE Breeze |
| Linux (그 외, 판별 불가) | Deepin |
| Web | WinUI/Fluent 2 (설정으로 Material 3로 교체 가능) |

- **Linux 데스크톱 환경 판별**: `XDG_CURRENT_DESKTOP`을 먼저 보고, 비어 있으면 `DESKTOP_SESSION`을 봅니다. 값에 `GNOME`이 포함되면 GNOME 50, `KDE`면 Breeze, 그 외와 판별 실패는 Deepin입니다. 판별 결과는 시작 시 한 번만 읽습니다.
- **Web에 플랫폼 룩은 없습니다.** 브라우저는 자기 디자인 언어를 갖지 않으므로 `adaptive`에서도 선택은 임의입니다. 기본을 Fluent 2로 두되, 앱이 설정으로 Material 3를 고를 수 있습니다. 문서에서는 Web에 대해 `unified`를 명시하는 것을 권장합니다.
- 2단계 시스템이 구현되기 전까지 Linux는 `fallback` 인자를 씁니다. 구현 완료 시 위 표가 적용됩니다.
- **GNOME 50 주의**: 버전을 명시한 것은 GNOME의 디자인 언어가 릴리스마다 바뀌기 때문입니다. 구현 전에 해당 릴리스의 HIG를 직접 확인하고, 참조한 문서와 버전을 토큰 테이블 주석에 남깁니다.
- **Deepin 주의**: 토큰값과 스타일 규칙만 참조합니다. 아이콘 세트와 전용 폰트는 별도 라이선스가 걸리므로 가져다 쓰지 않습니다.
- 명암(`ColorScheme`)은 `Light | Dark | FollowSystem`이고 기본은 `FollowSystem`입니다. 시스템 설정 변화는 Renderer가 먼저 알고 스스로 반영합니다. Host는 관여하지 않습니다(D5).

#### 14.4 해석 위치: Renderer
**토큰 해석과 컴포넌트 규칙은 Renderer가 수행합니다.** Host는 역할과 선택만 보냅니다.

근거:
- **PR-3/NFR-9(프레임 예산).** Host는 Renderer UI 스레드에서 돌기 때문에 Host의 작업이 그대로 프레임 예산(§5.1, 상호작용당 ≤ 0.5ms)에서 빠집니다. Host가 토큰을 푼다면 다크모드 전환이나 플랫폼 테마 변경이 트리 전체에 대한 `SetProp` 재전송(O(노드 수))이 됩니다. Renderer가 풀면 같은 변경이 `SetTheme` 1건이고, 나머지는 Compose의 `CompositionLocal` 무효화로 끝납니다.
- **PR-1(동기 경계).** 시스템 명암 전환과 플랫폼 식별은 Renderer 쪽 정보입니다. Host가 해석하려면 Renderer→Host 질의가 필요한데, 경계는 동기 단방향 호출 모델이라 질의를 추가하면 PR-2의 표면이 늘어납니다.
- **D5.** 테마는 UI 로컬 상태입니다. 스크롤 위치·포커스와 같은 부류입니다.
- 비용: Host 쪽 단위 테스트는 "어떤 역할을 보냈는가"까지만 검증할 수 있고, 실제 색·치수는 Renderer 테스트에서 검증합니다. 이 분리를 받아들입니다.

**토큰 테이블의 저작 위치는 Rust이고, 실행 위치는 Renderer입니다.** 14개 `ColorRole` × 2개 명암, 9단 `TypeRole`, `ShapeRole`/`SpaceRole` 치수 같은 값 표는 Rust 스키마에 데이터로 두고, FR-7 코드젠이 `Protocol.gen.kt`에 Kotlin `object`로 내보냅니다. 근거:
- D6(단일 소스는 Rust)를 토큰에도 그대로 적용합니다. Kotlin에 손으로 적으면 세 시스템 × 7개 표가 검증되지 않은 채 남습니다.
- 값 표는 Rust 테스트로 검증할 수 있습니다(대비비, 사다리 단조성, 표가 비어 있지 않은지). 14.4가 포기한 것은 "화면에 그려진 결과"이지 "표의 내용"이 아닙니다.
- 경계는 그대로입니다. 표는 **빌드 시점에** Renderer 바이너리로 들어가고, 런타임에 경계를 넘지 않습니다. 13.7의 "Host가 보내는 토큰 테이블"은 여전히 금지입니다.
- Renderer 구현자가 채우는 것은 값이 아니라 **적용 규칙**(14.6의 5·6·7번과 `CompositionLocal` 배선)입니다.

#### 14.5 와이어 추가분
- Mutation `SetTheme { design_system: u16, fallback: u16, color_scheme: u16, adaptive: u16 }`: **명령 태그 9**, 레코드 길이 12바이트(`tag`, `len`, 뒤이어 u16 4개). 루트(`node_id` 없음)에 적용합니다. `adaptive`는 0 또는 1입니다. Host는 초기 배치의 첫 레코드로 1회 보내고, 앱이 테마를 바꿀 때만 다시 보냅니다.
- `DesignSystem` 태그: `Material3 = 1`, `Cupertino = 2`, `Fluent = 3`. 태그 값은 바뀌지 않습니다. 이전 이름은 `AppleHig`였습니다.
- `ColorScheme` 태그: `Light = 1`, `Dark = 2`, `FollowSystem = 3`.
- 수용 기준: `Theme::unified(...)`로 띄운 앱의 첫 배치 첫 레코드가 `SetTheme`이고 `adaptive = false`입니다. `Theme::adaptive(...)`이면 `adaptive = true`이며 `fallback`이 인자로 준 시스템입니다.

#### 14.6 Renderer 구현자가 채워야 할 표
디자인 시스템마다 아래 7개가 필요합니다. 채워지면 위젯 코드는 건드리지 않습니다.

1~4번은 14.4에 따라 Rust 스키마에서 코드젠으로 생성되어 `Protocol.gen.kt`의 `DesignTokens`에 이미 들어 있습니다. Renderer 구현자는 **5~7번과, 1~4번을 Compose에 배선하는 일**을 맡습니다.
1. `ColorRole` 14개 × {Light, Dark} 색값
2. `TypeRole` 9개 → 크기/굵기/행간/자간/폰트
3. `ShapeRole` 6개 → 곡률(HIG는 연속 곡률)
4. `SpaceRole` 7개 → dp
5. `Modifier::Elevation(dp)` → 그림자/톤/스트로크 렌더링 규칙
6. `ButtonVariant` 4개 → 배경·전경·테두리·눌림 표현
7. 모션: 상태 전환 duration과 easing

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

여기까지가 1.0의 코어 어휘 29개입니다.

#### 15.2.2 속성 태그 블록

FR-13.8이 코어 속성 태그를 26까지 썼고, FR-11 확장 예제가 27을 가져갔습니다. 15.2의 새 위젯들은 서로 다른 시점에 구현되므로, 태그가 겹치지 않도록 블록을 미리 나눠 둡니다. 블록 안에서는 앞에서부터 채우고, 남는 자리는 비워 둡니다.

| 위젯 태그 | 속성 태그 블록 |
|---|---|
| 10-17 (`Image` ~ `Divider`) | 28-39 |
| 18-25 (`Card` ~ `Tooltip`) | 40-49 |
| 26-29 (`Canvas` ~ `Dropdown`) | 50-59 |

#### 15.2.3 컨테이너, 오버레이, 탭(태그 18-25)

**새 속성은 셋뿐입니다**: `Open=40`(bool), `OnDismiss=41`(핸들러 id), `SelectedIndex=42`(u32).

**새 이벤트 태그는 만들지 않습니다.** 해제와 탭 선택은 값을 싣지 않는 알림이므로 기존 `Clicked`(이벤트 태그 1)를 각자의 핸들러 id로 보냅니다. 어떤 탭이 선택됐는지는 노드 id가 말해 줍니다.

- **D5**: Dialog의 열림, Menu의 펼침, Tabs의 선택, 스크롤 위치는 전부 Renderer의 상태입니다. Host 속성은 초기값과 바깥에서 들어온 변경을 주고, Renderer는 바뀐 사실만 이벤트로 알립니다. 여는 애니메이션이나 탭 전환이 프레임마다 Rust를 왕복하지 않습니다.
- **자식 규약**: `Menu`는 자식 0이 앵커이고 나머지가 팝업 항목입니다. `Tooltip`은 자식이 앵커이고 설명은 `text` 속성입니다. `Tabs`의 자식이 곧 탭이며, 탭 `i`를 고르면 자식 `i`의 `OnClick` 핸들러로 `Clicked`를 보냅니다. `Dialog`, `Card`, `Surface`, `TopAppBar`의 자식은 그대로 내용입니다.
- **역할만 내보냅니다**(FR-14.1): 카드의 배경과 모서리, 대화상자의 스크림과 위치, 메뉴 팝업의 그림자, 탭 인디케이터의 모양, 툴팁의 지연 시간은 전부 디자인 시스템의 규칙입니다. Host가 지정할 속성을 두지 않습니다. Renderer 쪽 확장은 `ComponentRules`에 `container(ContainerRole)`와 `tabs()` 둘을 더하는 것으로 끝나고, 네 번째 디자인 시스템은 여전히 구현 하나입니다.
- **고도**: `Surface`와 `Card`는 `Modifier::Elevation`(FR-13.5)을 그대로 받습니다. 값이 없으면 디자인 시스템이 정한 기본 고도를 씁니다.
- **`LazyRow`**: FR-8의 `item_count`, `item_key`, `on_range_requested`를 그대로 씁니다. 가로 축이라는 것 말고 다른 점이 없어야 하고, 선읽기 버퍼도 같은 이유로 Renderer가 소유합니다.

#### 15.2.4 선택 컨트롤과 표시기(태그 12-17)

`Checkbox`, `RadioButton`, `Switch`, `Slider`, `ProgressIndicator`, `Divider`입니다. 15.2.2의 속성 태그 블록 28-39를 둘로 나눕니다. **28-31은 `Image`와 `Icon`(태그 10-11)이 쓰고, 32-39는 이 여섯 위젯이 씁니다.** 두 묶음이 서로 다른 시점에 구현되므로 블록을 다시 쪼개 두지 않으면 같은 태그를 두 번 배정하게 됩니다.

**새 속성은 여덟입니다.**

| 태그 | 속성 | 값 | 쓰는 위젯 |
|---|---|---|---|
| 32 | `Checked` | bool | `Checkbox`, `RadioButton`, `Switch` |
| 33 | `Value` | f32 | `Slider`의 위치, `ProgressIndicator`의 진행도 |
| 34 | `MinValue` | f32 | `Slider` |
| 35 | `MaxValue` | f32 | `Slider` |
| 36 | `Steps` | i64 | `Slider`의 양 끝 사이 불연속 지점 개수. 0이면 연속 |
| 37 | `Determinate` | bool | `ProgressIndicator` |
| 38 | `Circular` | bool | `ProgressIndicator` |
| 39 | `Vertical` | bool | `Divider` |

`RadioButton`의 선택 여부도 `Checked`를 씁니다. 와이어에서는 "켜져 있는가"라는 bool 하나이고, 13.1이 색에 요구한 것과 같은 이유로 같은 개념을 스키마에 두 번 넣지 않습니다. `selected`라는 이름은 Compose 관례이므로 rsx 속성 이름으로만 남습니다(PR-7).

변경 핸들러는 기존 `OnValueChange`(태그 6)와 `OnClick`(태그 5)을 그대로 씁니다.

**새 이벤트 태그는 하나입니다.**

| 태그 | 이벤트 | 페이로드 |
|---|---|---|
| 16 | `ValueChanged` | `value: f32` |

1-7은 배정이 끝났고 8-15는 FR-18이 가져갔으므로 16부터 이어 붙입니다. 레코드 길이는 20바이트입니다.

- `Checkbox`, `RadioButton`, `Switch`는 **제어(controlled) 위젯입니다.** Renderer는 `Checked`가 말하는 그대로 그리고, 사용자가 누르면 `Clicked`만 보냅니다. 값을 뒤집는 쪽은 Host입니다. 15.2.3의 탭 선택과 같은 이유로 새 페이로드가 필요 없고, Renderer가 자체 상태를 들고 있다가 Host와 어긋나는 일도 없습니다.
- `Slider`만 `ValueChanged`를 씁니다. 드래그 중의 위치는 연속값이라 Host가 유추할 수 없기 때문입니다. **드래그 중의 임시 위치는 Renderer가 소유합니다(D5).** 손가락을 따라가는 움직임이 프레임마다 경계를 넘지 않고, Renderer는 바뀐 값만 알립니다.
- **역할만 내보냅니다**(FR-14.1). 체크 표시의 모양과 크기, 스위치가 리플을 내는지 눌린 동안 흐려지는지, 트랙과 엄지의 치수, 부정형 표시기가 도는 속도, 구분선의 두께와 색은 전부 디자인 시스템의 규칙입니다. Host가 지정할 속성을 두지 않습니다. Renderer 쪽 확장은 `ComponentRules`에 `controls()` 하나를 더하는 것으로 끝나고, 네 번째 디자인 시스템은 여전히 구현 하나입니다.
- `ProgressIndicator`는 `determinate`가 거짓이면 `Value`를 읽지 않습니다. `circular`는 모양을 고르는 것이지 치수를 정하는 것이 아닙니다.
- `Divider`는 `vertical`이 정하는 축 말고는 아무것도 싣지 않습니다.

#### 15.3 넣지 않는 것

| 제외 | 이유 |
|---|---|
| `Chip`, `FAB`, `NavigationRail`, `BottomSheet` | Material 고유 어휘. Cupertino 대응물 없음. FR-11 확장으로 |
| `NavigationBar` | 모바일 고유. Android와 iOS에만 있고 데스크톱 세 시스템에 공통 개념이 없습니다 |
| `Grid` | 지연 그리드는 FR-8과 다른 윈도잉 프로토콜이 필요합니다. 별도 요구사항으로 분리 |
| `RichText`, 마크다운 | 위젯이 아니라 `Text`의 스팬 모델 문제입니다. 별도 요구사항으로 분리합니다 |
| 드래그 앤 드롭(파일), 리사이즈 핸들 | 플랫폼 파일 프로토콜이 따로 필요합니다. 1.0 이후. 포인터 드래그 자체는 FR-18에 있습니다 |

#### 15.4 수용 기준

- 29개 위젯 각각이 세 디자인 시스템에서 렌더링되고, `DesignShowcase`에 나타납니다.
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

#### 17.3 수용 기준

- 꺾은선 차트 하나를 Rust `rsx!`만으로 그립니다. Kotlin 변경이 없습니다.
- `ColorRole`로 그린 선이 세 디자인 시스템에서 각 시스템의 색으로 나옵니다.
- 명령 목록이 그대로인 프레임에서 Host 힙 할당이 0입니다(NFR-9).

### FR-18 포인터 제스처 (`Agreed`)

입력이 `Clicked`와 `KeyDown`뿐이었습니다. 제스처 인식은 Compose 쪽에서 일어나므로 위젯 조합으로 만들어낼 수 없고, 없으면 Host가 우회할 방법도 없습니다.

#### 18.1 이벤트

이벤트 태그 8부터 이어 붙입니다(1-7은 배정 완료).

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

#### 18.2 수용 기준

- 호버로 버튼을 띄우는 코드가 macOS에서 동작하고, iOS에서 이벤트가 오지 않아도 앱이 깨지지 않습니다.
- 드래그로 항목을 옮기는 목록에서 `DragMove`가 프레임당 1건입니다.
- 제스처를 선언하지 않은 노드에는 인식기가 붙지 않습니다(인터프리터 단위 테스트).

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

## 4. 경계 프로토콜

### PR-1 호출 모델: 동기·동일 스레드 직접 호출 (`Agreed`)
옛 React Native 브리지처럼 비동기 큐를 두면 병목이 생깁니다. 비동기 큐는 동기 반환값을 받을 수 없고, 스레드 홉 때문에 최대 1프레임 지연이 생깁니다. 그래서 JSI처럼 **같은 스레드에서 서로를 직접 호출**합니다.

- VirtualDom은 **Renderer의 UI 스레드에서** 돕니다. 이 스레드는 Host의 전용 스레드가 아닙니다.
- 사용자 입력이 들어오면 Renderer가 Host 핸들러를 직접 호출합니다. Host는 그 자리에서 핸들러를 실행하고 diff를 계산한 뒤, 결과 Mutation 배치와 반환값을 돌려줍니다.
- 동기 반환값을 지원합니다. 예: `onKeyEvent`의 "처리됨" 여부. Enter는 제출, Shift+Enter는 줄바꿈으로 나누는 처리가 여기에 해당합니다. 표현 방식은 FR-12를 따릅니다.
- 경계에 비동기 큐를 두지 않습니다. 스레드 간 통신은 PR-3의 wake 신호 하나뿐입니다.

### PR-2 경계 표면 (`Agreed`)
경계는 primitive, 포인터, 길이만 씁니다(GraalVM `@CEntryPoint` 제약). 함수는 호출 방향에 중립적인 **논리 연산**으로 정의하고, 플랫폼별 심은 코드젠(FR-7)이 생성합니다. 사람이 JNI나 cinterop 코드를 직접 쓰지 않습니다.

C 심볼은 Rust 쪽 관례(snake_case, 크레이트 이름 접두사)를 따릅니다. Kotlin 쪽 선언은 코드젠이 Compose 관례에 맞춰 생성합니다. 명명 규칙 전체는 PR-7에 있습니다.

Renderer → Host (Rust가 export):
```c
// 핸드셰이크: 스키마 해시, 버전, LoopMode를 교환하고 초기 트리 배치를 받습니다
int32_t dioxus_compose_host_init(const uint8_t* handshake, uint32_t len, MutationBatch* out);
// 이벤트 1건 처리: 핸들러를 실행한 뒤 diff를 계산하고, 결과 배치와 반환값을 돌려줍니다
int32_t dioxus_compose_host_dispatch_event(const uint8_t* event, uint32_t len, MutationBatch* out);
// 프레임 요청 이후 호출: 워커에서 온 상태 변경을 반영해 diff를 계산합니다
int32_t dioxus_compose_host_render_frame(uint64_t frame_time_nanos, MutationBatch* out);
void    dioxus_compose_host_release_batch(MutationBatch* batch);
void    dioxus_compose_host_shutdown(void);
```

Host → Renderer (Kotlin이 export):
```c
int32_t dioxus_compose_renderer_run(void);            // LoopMode::Renderer일 때만. 블로킹
void    dioxus_compose_renderer_request_frame(void);  // 스레드 안전. 다음 프레임에 render_frame 예약
```

- `LoopMode`
  - `LoopMode::Renderer`: Desktop, iOS. Rust `main`에서 `dioxus_compose::launch(app)`가 `dioxus_compose_renderer_run`을 호출합니다.
  - `LoopMode::Platform`: Android, Web. 플랫폼이 루프를 소유하고, Renderer가 먼저 `dioxus_compose_host_init`을 호출합니다.
  - 사용자 코드는 `fn app() -> Element`뿐이라 두 모드에서 동일합니다.
- GraalVM에서는 진입점마다 `IsolateThread*`가 붙습니다. 코드젠이 이를 숨깁니다. isolate는 프로세스당 1개입니다.
- `dispatch_event`와 `render_frame`이 반환한 배치는 Renderer가 **같은 호출 스택 안에서** 적용하고 즉시 `release_batch`합니다. 배치를 쌓아 두는 큐는 없습니다.
- 배치 하나는 단일 스냅샷 트랜잭션(`Snapshot.withMutableSnapshot`)으로 적용합니다. 중간 상태가 화면에 그려져서는 안 됩니다.

**검증 (2026-09-21, macOS arm64 + iOS 시뮬레이터)**

데스크톱용 C 스모크 호스트(`desktop/c/smoke_host.c`)를 **한 글자도 고치지 않고** iOS 정적 아카이브에 링크해 실행했습니다. 같은 `main`, 같은 다섯 개 `dioxus_compose_host_*` 함수가 GraalVM native-image 공유 라이브러리와 Kotlin/Native 아카이브 양쪽에 그대로 붙습니다. 경계 표면이 두 런타임에서 하나라는 근거입니다.

```
dioxus_compose_host_init: 180 bytes, 8 records
dioxus_compose_host_dispatch_event: click 1
```

- 핸드셰이크와 초기 배치(180바이트, 8레코드)가 양쪽에서 바이트 단위로 동일합니다.
- 시뮬레이터에서 버튼을 탭하면 `dispatch_event`가 Rust 핸들러까지 도달합니다. `simctl`에 탭 명령이 없어 이 확인은 자동화되지 않습니다. `ios-smoke-test.sh --await-click`으로 사람이 실행합니다. CI는 이 플래그 없이 기동과 렌더링까지만 증명합니다.
- iOS에는 isolate가 없어 `@CName`이 공개 심볼을 Kotlin 함수에 직접 붙입니다. isolate 심이 하던 나머지 역할은 Kotlin/Native 런타임과 `NSThread.isMainThread` 검사가 대신합니다.
- Android와 Web은 아직 검증되지 않았습니다. 네 타깃이 모두 확인되면 `Done`으로 올립니다.

### PR-3 스레드 규칙 (`Agreed`)
- VirtualDom, 사용자 컴포넌트, 모든 `dioxus_compose_host_*` 호출은 Renderer UI 스레드에서만 실행합니다. 그래서 락이 필요 없습니다.
- **UI 스레드에서 도메인 작업을 금지합니다.** 네트워크, 파일 I/O, 프로세스 관리 같은 작업은 Host 워커 스레드(tokio 등)에서 돌립니다. 워커는 Dioxus signal로 상태를 갱신하고, Host가 내부에서 `request_frame`을 호출합니다. 사용자 코드는 경계 함수를 직접 부르지 않습니다.
- `request_frame`은 여러 번 불러도 다음 프레임에 `render_frame` 1회로 합쳐집니다. Compose frame clock(`withFrameNanos`) 안에서 실행됩니다.
- macOS에서 `dioxus_compose_renderer_run`은 프로세스 메인 스레드에서 호출해야 합니다(AppKit 요구사항).
- Android: Host 워커 스레드는 `request_frame`을 부르기 위해 JavaVM에 **1회 영구 attach**합니다. 호출마다 attach하는 것은 금지합니다. `@FastNative`/`@CriticalNative`는 짧은 호출에만 허용합니다.
- 프레임 예산은 NFR-9를 따릅니다.

### PR-4 배치 버퍼와 인코딩 (`Draft`)
원칙: **같은 프로세스 안이므로 직렬화, 복사, 경계 호출 횟수를 최소화합니다.** 버퍼는 큐가 아니라 **한 번의 호출에서 Mutation 여러 개를 넘기는 인자**입니다.

- `#[repr(C)] struct MutationBatch { ptr: *const u8, len: u32, result: i64 }`
  - Host가 소유하는 arena를 가리키며, 프레임마다 재사용합니다.
  - `result`에는 핸들러의 동기 반환값이 들어갑니다.
- **인코딩**: 고정 레이아웃 바이너리로, 제자리에서 읽습니다(zero-copy). 디코드 단계를 두지 않습니다.
  - 레코드: `tag: u16`, `len: u16`, 뒤이어 고정 필드(`node_id: u32` 등)가 오며, 리틀 엔디언이고 4바이트 정렬입니다.
  - 문자열: 같은 arena에 두고 `(offset: u32, len: u32)`로 참조합니다. UTF-8입니다. Renderer는 Compose에 넘기는 시점에만 `String`으로 변환합니다.
  - postcard, bincode, FlatBuffers는 쓰지 않습니다. 레코드 레이아웃과 접근자는 FR-7 코드젠이 생성합니다.
- **이벤트 태그** (Renderer→Host): 입력 이벤트(FR-3), `TextChanged`/`TextSubmitted`/`FocusLost`(FR-5), `RangeRequested`(FR-8), `Lifecycle(Resumed|Paused|Destroyed)`, `Resync`, `SaveState`/`RestoreState`(선택), `ProtocolError`
- **플랫폼별 메모리 접근**
  - Desktop(GraalVM): `Pointer`로 직접 읽습니다.
  - iOS(Kotlin/Native): `CPointer`로 읽습니다.
  - Android: `NewDirectByteBuffer`로 arena를 감싸서 읽습니다.
  - Web: 공유 linear memory(PR-6)
- 수용 기준: 텍스트 하나를 바꾸는 이벤트 처리에서 경계 호출 2회(`dispatch_event`, `release_batch`), 힙 할당은 Compose `String` 생성 1회 이하

### PR-5 Android (`Draft`)
- 호스트 관계: Kotlin Activity가 프로세스와 루프를 소유합니다(`LoopMode::Platform`). Rust는 cdylib입니다. VirtualDom은 PR-3에 따라 UI 스레드에서 돕니다.
- JNI 심: PR-2의 논리 연산에서 jni-rs 기반 심과 Kotlin `external fun` 선언을 코드젠으로 생성합니다. UniFFI(JNA 경유)는 호출당 오버헤드가 커서 쓰지 않습니다.
- 생명주기:
  - Surface 파괴와 config change: Compose만 재구성됩니다. VirtualDom은 프로세스 전역에 유지됩니다. Renderer는 재구성 후 `Resync`를 보내고, Host는 전체 트리 배치를 돌려줍니다.
  - `onStop`/`onStart`: `Lifecycle` 이벤트를 보냅니다. Host는 타이머와 애니메이션을 억제합니다.
  - 프로세스 kill: 메모리 상태는 복원하지 않습니다. 필요하면 `SaveState`로 작은 blob을 `onSaveInstanceState`에 저장합니다.
- android-activity, NativeActivity, GameActivity 진입점은 쓰지 않습니다. ComposeView와 공존한 사례가 없고 IME 충돌 위험이 있습니다. JavaVM은 `JNI_OnLoad`에서 얻습니다.
- 수용 기준(M6):
  1. 일반 JNI와 `@FastNative`의 호출당 비용을 실측합니다. 공개 수치(약 115ns, 약 35ns)와 비교해 기록합니다.
  2. M0 화면을 같은 Rust 소스로 띄우고, 초당 100회 추가되는 스트리밍 중 프레임 끊김이 없음을 Macrobenchmark `FrameTimingMetric`으로 확인합니다.
  3. 화면 회전, 다크모드 전환, 홈→복귀, `am kill` 후 복귀에서 크래시가 없습니다.

### PR-6 Web 경계 (`Agreed`)
Rust(wasm32)와 Kotlin/Wasm 모듈을 연결합니다. `LoopMode::Platform`입니다. 2026-09-20 실측으로 확정했습니다(`experiments/web-interop/`).

- **메모리: Kotlin이 소유합니다.** Kotlin/Wasm 모듈은 항상 자기 메모리를 정의해 export하며, 외부 메모리를 import하는 경로가 없습니다. 따라서 Rust가 `--import-memory`로 그 메모리를 가져다 씁니다. PR-4의 arena는 양쪽이 제자리에서 읽습니다. 복사는 없습니다.
- **함수 호출: 경계 함수마다 고정된 형태의 JS forwarder를 코드젠으로 생성합니다.** 호출당 약 12ns입니다.
- **두 가지를 동시에 가질 수 없습니다.** `WebAssembly.instantiate`는 import를 먼저 요구합니다. Kotlin은 Rust export 없이 인스턴스화할 수 없고, Rust는 Kotlin 메모리 없이 인스턴스화할 수 없으며, Kotlin 메모리는 인스턴스화 전에 존재하지 않습니다. wasm import를 wasm export에 직접 묶으면(1.45ns) 메모리를 공유할 수 없고, 메모리를 공유하면 한쪽 호출 경로에 JS forwarder가 들어옵니다. 단일 모듈 링크(WasmGC와 linear memory 혼합 불가), 제3의 메모리 소유 모듈, component model(브라우저 미지원) 모두 이 순환을 풀지 못합니다.
- **메모리 공유를 택합니다.** 프레임 예산을 지배하는 것은 PR-4의 복사 회피이지 호출 오버헤드가 아닙니다. 프레임당 경계 호출 3회 기준 약 36ns이며, PR-5가 Android에서 이미 수용한 JNI 호출 비용(약 115ns)보다 한 자릿수 작습니다. D8이 거부한 React Native 브리지와는 성격이 다릅니다. 직렬화도, 비동기 큐도, 스레드 홉도, 데이터 복사도 없습니다.
- 구현 시 주의: Kotlin 메모리는 0페이지로 시작하므로 Rust 인스턴스화 전에 JS가 `memory.grow()`를 해야 합니다. Rust의 데이터 세그먼트와 Kotlin `kotlin.wasm.unsafe` 할당자가 같은 주소 공간을 쓰므로 `--global-base`로 영역을 분리합니다.
- 실측(Safari 26.5, Apple silicon): 같은 모듈 호출 0.30ns, wasm 직접 바인딩 1.45ns, JS forwarder 12.05ns, Kotlin에서 메모리 읽기 0.977ns/byte.
- 남은 확인: V8과 SpiderMonkey에서 같은 수치가 나오는지 재측정해야 합니다.

### PR-7 명명 규칙 (`Agreed`)
각 언어 생태계의 관례를 따릅니다. 한쪽 관례를 다른 쪽에 억지로 맞추지 않습니다.

| 영역 | 관례 | 예 |
|---|---|---|
| C ABI 심볼 | snake_case, `dioxus_compose_{host,renderer}_` 접두사, 동사구 | `dioxus_compose_host_dispatch_event` |
| Rust 공개 API | Dioxus 관례를 따릅니다: `launch`, `LaunchBuilder`, `use_*` 훅, PascalCase 컴포넌트 | `dioxus_compose::launch(app)`, `use_text_field()` |
| Rust 타입 | PascalCase, `#[repr(C)]` 경계 타입은 역할 이름 | `MutationBatch`, `LoopMode`, `HostEvent` |
| rsx 위젯 | Compose 이름을 그대로 씁니다 | `Column`, `Row`, `LazyColumn`, `TextField` |
| rsx 속성과 Modifier | snake_case, Compose 이름을 옮긴 것 | `fill_max_width`, `padding`, `on_click`, `on_value_change` |
| Kotlin 선언 | camelCase 함수, PascalCase 타입, 컴포저블은 PascalCase 명사 | `DioxusContent(host)`, `rememberDioxusHost()`, `HostBridge.dispatchEvent()` |
| Kotlin 생성 코드 | `generated` 패키지, 파일 이름 접미사 `.gen.kt` | `dioxus.compose.protocol` |
| 이벤트/Mutation 태그 | Rust `enum` 변형은 PascalCase, Kotlin `sealed interface` 하위 타입은 같은 이름 | `SetText`, `TextSubmitted` |

- Compose에 같은 개념이 있으면 그 이름을 씁니다(`Modifier`, `Recomposition`, `requestFrame`). 새 이름을 만들지 않습니다.
- Dioxus에 같은 개념이 있으면 Rust 쪽은 Dioxus 이름을 씁니다(`VirtualDom`, `Mutations`, `ElementId`).
- 두 이름이 충돌하면 Rust 쪽은 Dioxus 이름을, Kotlin 쪽은 Compose 이름을 쓰고, 대응 관계를 코드젠 스키마에 기록합니다.

### PR-8 macOS 런타임 요건 (`Draft`)
- 빌드 도구는 Liberica NIK 25 Full입니다(INTENT D9-macOS).
- 배포 레이아웃은 `<root>/lib/` 하나이며 `java.home`은 그 부모입니다. 렌더러는 자기 라이브러리 경로를 dladdr로 얻어 `java.home`, `skiko.library.path`, `skiko.data.path`를 설정합니다.
- `lib/`에 함께 두는 파일: 렌더러 라이브러리, Skia(`libskiko-macos-<arch>.dylib`), `libjawt.dylib` 포워더, `libawt_lwawt.dylib` 자리 채우기.
- `dioxus_compose_renderer_run`은 프로세스 메인 스레드에서 호출해야 합니다. 아니면 `RUN_NOT_MAIN_THREAD`를 반환합니다.
- 빌드 스크립트는 `lib/static/darwin-*/libawt_lwawt.a`가 없는 설치를 이미지 빌드 시작 전에 거부합니다. 순정 GraalVM과 비 Full NIK을 걸러내기 위한 것입니다.
- 수용 기준
  1. C 호스트가 라이브러리를 링크해 `run`을 호출하면 창이 뜨고, 창을 닫으면 `run`이 0을 반환하며 프로세스가 정상 종료됩니다. **(2026-09-20 통과)**
  2. 잘못된 툴체인(`GRAALVM_HOME` 미설정/없는 경로/`native-image` 없음/정적 AWT 아카이브 없음)에서 빌드가 즉시 실패하고 조치 방법을 출력합니다. **(2026-09-20 통과)**

## 5. 비기능 요구사항

| ID | 요구사항 | 수용 기준 | 상태 |
|---|---|---|---|
| NFR-1 | JVM 불필요 | 배포물에 JRE가 없고, `java`가 없는 머신에서 실행됨 | Agreed |
| NFR-2 | 웹뷰 불필요 | WKWebView, WebView2, WebKitGTK에 링크하지 않음 | Agreed |
| NFR-3 | 데스크톱 무게 | 빈 창 physical footprint < 56MB, 배포 용량 < 100MB. 측정 기준과 근거는 §5.2 | Agreed |
| NFR-4 | 플랫폼 | macOS, Windows, Linux 데스크톱, iOS, Android, Web(wasm). Android와 Web의 경계는 PR-5, PR-6 참조 | Agreed |
| NFR-5 | 개발 경험 | Renderer는 JVM 개발 셸에서 hot reload와 `@Preview`로 작업 가능. native-image 빌드는 개발 루프에 필요 없음. 새 머신의 준비 상태를 `scripts/setup-check.sh` 한 번으로 확인 가능 | Agreed |
| NFR-6 | 안정 API만 사용 | `@InternalComposeUiApi`, `@ExperimentalComposeUiApi` 의존을 금지하거나, 쓰더라도 어댑터 한 파일에 격리하고 버전 핀을 둠 | Agreed |
| NFR-7 | 크래시 격리 | 프로토콜 오류로 프로세스가 종료되지 않고 `ProtocolError` 이벤트를 보냄 | Agreed |
| NFR-8 | 데스크톱 접근성 | native-image 빌드의 접근성 트리가 JVM 개발 셸과 같은 구조로 노출될 것. smoke test의 종료 코드 0은 근거가 되지 않습니다(접근성을 질의하지 않으므로). **2026-09-21 트리 노출 충족**, VoiceOver 수동 확인은 미완료(§7) | Agreed |
| NFR-9 | 네이티브 수준 프레임 성능 | §5.1 기준 충족 | Agreed |
| NFR-10 | 렌더러 탐색 경로 | `DIOXUS_COMPOSE_RENDERER_DIR` → 워크스페이스 빌드 결과물 순서로 찾음 | Agreed |
| NFR-11 | 배포 | 크레이트는 crates.io, 렌더러는 플랫폼별 체크섬 릴리스 아티팩트. 아티팩트 규격과 소비자 측 해석, 버전 계약은 §5.3 (INTENT D10) | Draft |

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
- 할당은 횟수 자체보다 **증가하지 않는지**가 기준입니다. Dioxus는 diff와 이벤트 처리 과정에서 내부적으로 할당하며(2026-09-20 측정: 클릭당 99회), 이를 0으로 만들려면 Dioxus를 포크해야 해서 D2와 충돌합니다. Rust에는 GC가 없어 이 할당이 프레임 멈춤으로 이어지지 않습니다. 반복 상호작용에서 할당 수가 늘어나면 누수나 캐시 미작동으로 보고 조사합니다.
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

**남은 여지**: 그래픽이 약 22.5MB로 전체의 40%를 넘고 창 크기에 비례합니다. Skia와 그래픽을 합친 약 36.5MB가 Compose/Skia 프로세스의 바닥이며, 시스템 텍스트 스택을 공유하는 AppKit 앱은 지지 않는 비용입니다.

**더 줄이려면 IME 등록 메타데이터를 건드려야 합니다.** `ImeReachabilityFeature`가 패키지 단위로 등록하면서 약 7.8MB의 이미지 힙을 만듭니다. 이것을 좁히는 것이 남은 유일한 큰 레버지만, 실패하면 입력기가 텍스트 필드를 건드리는 순간 프로세스가 죽습니다(§6). **사람이 네이티브 빌드에서 한글을 직접 입력해 확인할 수 있을 때만 시도합니다.**

## 6. IME 수용 체크리스트 (FR-5, M1)

native-image 빌드에서 macOS와 Windows 각각 수동으로 확인합니다.

**macOS arm64 결과 (2026-09-21, Liberica NIK 25, native-image 빌드)**: 5개 항목을 사람이 직접 확인했습니다. 조합 과정 표시, 조합 중 자모 단위 백스페이스, 조합 중 화살표 이동 시 확정 후 이동, 문장 중간 삽입, 한글 폰트 폴백입니다. **M1의 관문이 이 항목이었고, 통과했습니다.**

확인 절차: `DIOXUS_COMPOSE_SMOKE_IME=1 desktop/scripts/smoke-test.sh`로 텍스트 필드가 있는 스모크 창을 띄우고 한국어 입력기로 입력합니다. 이 플래그가 없으면 창에 라벨과 버튼만 있어서 타이핑할 곳이 없습니다.

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

실패하면 INTENT D4에 따라 native-image 설정(ServiceLoader, JNI/리플렉션 config, 로케일/문자셋)부터 점검합니다.

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
