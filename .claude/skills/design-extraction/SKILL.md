---
name: design-extraction
description: 참고 사이트의 스크린샷을 계측해 디자인 토큰 후보·컴포넌트 인벤토리·아키타입별 레이아웃 맵을 만드는 절차. 색·타이포·간격·반경·그림자를 값과 신뢰도로 적고, 반복 UI를 표준 어휘로 묶고, 페이지의 섹션 순서·그리드·여백 리듬·반응형 붕괴를 layout.md로 기록한다. 같은 사이트의 두 번째 아키타입부터는 델타만 더한다. design-analyzer가 따른다.
---

# design-extraction

**측정값으로 적는다.** 「세련됨」 같은 인상은 토큰이 되지 않는다. 산출물은 호출 시 받은 작업 폴더(`artifacts/sites/<슬러그>/`) 아래에 둔다 — `analysis/`, `examples/<아키타입>/`.

## 1. 캡처

캡처는 **사용자가 올린 스크린샷**이다. 사이트를 직접 열지 않는다.

| 컷 | 폭 | 용도 |
| --- | --- | --- |
| 데스크톱 풀페이지 | 1440 | 토큰 · 레이아웃의 기준 |
| 모바일 풀페이지 | 560 | 반응형 붕괴 |
| 태블릿 | 960 | 있으면. 중간 브레이크포인트 |
| 상태 컷 | — | hover · focus · 선택 · 열린 드롭다운 · 모달. 있는 것만 상태로 기록한다 |

| 신뢰도 | 언제 | 표기 |
| --- | --- | --- |
| 측정 | 픽셀 단위로 셀 수 있는 것 — 간격 · 반경 · 컨테이너 폭 · 컬럼 수 | 값 |
| 추정 | 스크린샷에서 근사만 가능한 것 — hex(압축 · 안티앨리어싱) · weight · letter-spacing · 그림자 blur | 값 + `추정` |
| 미관측 | 컷이 없는 상태 · 애니메이션 · z-index · 포커스 링 | Known Gaps |

**추정을 측정처럼 적지 않는다.** 뒤 단계는 이 표기로 무엇을 믿을지 정한다.

## 2. 토큰 추출

| 그룹 | 무엇을 | 명명 |
| --- | --- | --- |
| 색 | 등장한 텍스트색 · 배경색을 hex로. 빈도순 | 가장 많은 텍스트색 `ink`, 가장 많은 배경 `canvas`, CTA 배경 `primary`(+ `on-primary`), 옅은 보더 `hairline`, 옅은 면 `surface-soft`. 큰 면적의 채도 높은 패널 `block-*`, 1회성 강조 `accent-*`, 상태 의미 `semantic-*`. **보이는 색 이름으로 짓지 않는다.** 같은 hex라도 역할이 다르면 다른 키 |
| 타이포 | (size · weight · line-height · letter-spacing) 조합 | 역할로 — `display-xl` `display-lg` `headline` `subhead` `card-title` `body-lg` `body` `body-sm` `link` `button` `eyebrow` `caption` 중 관측된 것. line-height는 px → 배수. 패밀리는 주 · 보조(mono)와 폴백 스택 |
| 반경 | border-radius 값을 작은 → 큰 순 | `xs` `sm` `md` `lg` `xl` · 알약형 `pill` · 원형 `full` |
| 간격 | padding · margin · gap을 8px 기반으로 근사 | `xxs`~`xxl` · 섹션 사이 큰 수직 간격 `section` |
| 그림자 | box-shadow를 단계로 | `elevation` 0 flat ~ 3 modal |
| 보더 두께 | 1px · 2px 등 | 본문 기록. 색은 `hairline`류가 담당 |
| 아이콘 | 크기 · stroke · 세트 추정 | 본문 기록. **아이콘 형태는 기록하지 않는다** — 브랜드 요소다 |

색 · 타이포 · 반경 · 간격은 프론트매터 토큰이 된다. 그 외는 `designmd-spec` 3장이 정한 본문 절로 간다 — 그림자는 Elevation & Depth, 보더 두께 · 아이콘 크기는 Shapes.

산출: `analysis/tokens.md`. 그룹별 표, 값마다 신뢰도와 출처 컷(`map-search/desktop` 등).

## 3. 컴포넌트 인벤토리

반복되는 UI를 컴포넌트로 묶고 토큰 참조로 정의한다. 각 항목은 `backgroundColor · textColor · typography · rounded · padding/size`를 `{group.token}`으로.

| 그룹 | 표준 어휘 |
| --- | --- |
| Actions | `button`(변형 전부) · `pulldown` |
| Forms | `field` · `input` · `textarea` · `select` · `checkbox` · `radio` · `switch` · `date` |
| Containment | `card` · `table` · `tabs` · `dialog` · `popover` |
| Feedback | `alert` · `toast` · `tooltip` · `badge` |
| Date | `calendar` |

| 규칙 | 내용 |
| --- | --- |
| 있는 것만 | 사이트에 없는 컴포넌트는 만들지 않는다 |
| 우리가 쓰는 것 우선 | `button` `pulldown` `field` `input` `select` `checkbox` `card` `tabs` `dialog` `badge` `toast` — 이 중 미관측인 것을 Known Gaps에 적는다 |
| 상태는 컷이 있는 것만 | `-hover` `-focus` `-active` `-disabled` `-checked`/`-selected` `-error` `-open`. 컷이 없으면 「present-but-uncaptured」 |
| 고유 컴포넌트 | nav · footer · 색 블록 · 퀵 메뉴 등은 자유 명명. 표준 역할에 해당하면 표준명 |
| 브랜드 요소 제외 | 로고 · 브랜드명 · 문구 · 사진 · 일러스트 · 아이콘 형태는 인벤토리에 넣지 않는다 |

산출: `analysis/tokens.md`의 components 절.

## 4. 델타 병합 — 두 번째 아키타입부터

한 페이지는 시스템의 단면이다. 같은 슬러그의 새 아키타입은 **기존 `tokens.md`를 먼저 읽고** 델타만 더한다.

| 관측 | 분류 | 기록 |
| --- | --- | --- |
| 기존에 없다 | 신규 | 추가 + `seen on: <아키타입>` |
| 기존과 같다 | 일치 | `seen on:`에 아키타입 추가. 여러 아키타입에서 반복 = canonical 신호 |
| 같은 역할, 다른 값 | 충돌 | `analysis/merge-notes.md`에 기존값 · 신규값 · 컷. **덮어쓰지 않는다** |
| 한 아키타입에서만 나온 값 | 1회 관측 | 표기만. 콘텐츠용 일회성 의심. 승격 여부는 author · reviewer |
| 팔레트 · 타이포가 전반적으로 다르다 | 발산 의심 | 표기하고 `design-extract`에 보고. 통합 여부는 사용자 |

## 5. 레이아웃 맵

정의서가 담지 못하는 **페이지 합성**을 아키타입마다 기록한다. `frontend-dev`가 화면을 만들 때 읽는 근거다.

`examples/<아키타입>/layout.md`:

```markdown
# <아키타입> layout — source: <슬러그>, <컷 파일명>
## 섹션 순서 (위 → 아래)
1. top-nav (sticky, h56) — 로고 자리 좌, 액션 우
2. filter-bar — pulldown N개, {spacing.md} 간격
3. split — 좌 목록 폭 <px> / 우 지도, 구분 {colors.hairline}
...
## 그리드 / 컨테이너
- max-width <px>, gutter 데스크톱 {spacing.xxl} → 모바일 {spacing.lg}
- 카드: 2-col → 1-col(<폭>)
## 여백 리듬
- 섹션 간 {spacing.section}, 카드 내부 {spacing.md}
## 반응형 붕괴
- <폭> 이하 목록 → 하단 시트, nav → 햄버거
```

`reference-*.png`는 같은 폴더에 둔다 — 커밋 대상이 아니다.

## 6. 산출 전 확인

| 확인 | 기준 |
| --- | --- |
| 모든 값에 신뢰도와 출처 컷이 있다 | 없는 값은 지어낸 것이다 |
| 색 역할이 의미 기반이다 | 보이는 색 이름 0건 |
| 컴포넌트가 토큰 참조로만 정의됐다 | raw hex · px는 토큰에 없는 1회성만 |
| 아키타입마다 `layout.md`와 참조 컷이 있다 | — |
| Known Gaps 후보가 적혔다 | 미관측 상태 · 미관측 표준 컴포넌트 |
| 브랜드 요소가 없다 | 로고 · 문구 · 사진 · 아이콘 형태 0건 |