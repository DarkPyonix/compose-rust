# docs/guide

사용자 가이드입니다. 사이트 생성기 없이 손으로 쓴 정적 HTML/CSS이며, 빌드 단계가 없습니다.
이 디렉터리를 그대로 GitHub Pages로 배포합니다.

프로젝트 문서(README, PROJECT, INTENT, SPEC)와 달리 이 가이드는 **영어와 한국어 두 언어를
동등하게** 제공합니다. 영어가 기본 진입 언어입니다.

## 구조

```
docs/guide/
├─ index.html            # 진입점. 기본은 en/, 이전에 한국어를 본 독자는 ko/로 보냅니다
├─ assets/
│  ├─ style.css          # 사이트 전체의 유일한 스타일시트
│  └─ guide.js           # 유일한 자바스크립트 (테마, 사이드바, 코드 복사)
├─ en/                   # 영어 페이지
│  ├─ index.html             Overview
│  ├─ getting-started.html   Getting started
│  ├─ writing-ui.html        Writing UI
│  ├─ lists-and-streaming.html
│  ├─ architecture.html
│  └─ troubleshooting.html
└─ ko/                   # 한국어 페이지. 파일 이름은 en/과 1:1로 같습니다
```

**파일 이름은 두 언어에서 반드시 같아야 합니다.** 언어 전환 링크가 같은 이름의 파일을
가리키는 방식으로 동작하기 때문입니다.

## 규칙

- 빌드 도구, Node, 프레임워크, 외부 CDN을 쓰지 않습니다. 브라우저로 파일을 열면 그게 전부입니다.
- 자바스크립트는 `assets/guide.js` 하나뿐이고, 없어도 모든 페이지가 읽히고 이동할 수 있어야
  합니다. JS가 하는 일은 테마 토글, 좁은 화면의 사이드바 토글, 코드 복사 버튼입니다.
- 문법 강조는 손으로 붙인 `<span>` 클래스입니다(`k` 키워드, `ty` 타입, `s` 문자열, `n` 숫자,
  `c` 주석, `f` 함수, `at` 속성). 하이라이터 라이브러리를 추가하지 않습니다.
- **모든 코드 예제는 이 저장소의 실제 API에서 가져오거나 대조해서 확인한 것이어야 합니다.**
  확인할 수 없으면 예시(illustrative)임을 코드 블록 캡션에 명시하고, 계획 단계의 기능은
  `<span class="pill planned">` 배지로 표시합니다.
- 번역은 직역이 아니라 각 언어로 자연스럽게 씁니다. 내용과 구조는 같게 유지합니다.

## 페이지 추가하기

1. `en/`에서 가장 비슷한 페이지를 복사해 새 이름으로 만듭니다. 머리말·사이드바·푸터 구조를
   그대로 유지합니다.
2. `<head>`를 고칩니다.
   - `<title>`, `<meta name="description">`
   - `<link rel="alternate" hreflang="en" href="새이름.html">`
   - `<link rel="alternate" hreflang="ko" href="../ko/새이름.html">`
   - `<link rel="alternate" hreflang="x-default" href="새이름.html">`
3. 상단 언어 전환 링크를 새 파일 이름으로 맞춥니다. 현재 언어 쪽에 `aria-current="true"`를 둡니다.
4. 같은 이름으로 `ko/` 페이지를 만듭니다. `<html lang="ko">`로 바꾸고, `hreflang` 두 줄을
   서로 반대로(`en` → `../en/새이름.html`, `ko` → `새이름.html`) 씁니다.
5. **여섯 개 파일 모두**(en 5 + ko 5, 그리고 새 페이지 2개)의 사이드바 목록에 새 항목을
   추가합니다. 현재 페이지에는 `aria-current="page"`를 붙입니다.
6. 앞뒤 페이지의 `.pagenav` 링크를 갱신합니다.

## 로컬에서 확인하기

```bash
cd docs/guide
python3 -m http.server 8000
# http://localhost:8000/  → en/ 으로 이동합니다
```

확인할 것: 랜딩 페이지, 내용 페이지 하나, 언어 전환(같은 페이지에 머무르는지), 다크 테마
토글, 좁은 창에서의 사이드바.

## 테마 동작

- 기본값은 OS 설정(`prefers-color-scheme`)입니다. 별도 표시가 없습니다.
- 토글을 누르면 `<html data-theme="light|dark">`가 설정되고 `localStorage`의 `dxc-theme`에
  저장됩니다. 이후 방문에는 `<head>`의 짧은 인라인 스크립트가 이 값을 먼저 적용해서
  화면 깜빡임을 막습니다. 새 페이지를 만들 때 이 인라인 스크립트를 빠뜨리지 마세요.
- 읽던 언어는 `dxc-lang`에 저장되고, `docs/guide/index.html`이 그 값을 참고합니다.
