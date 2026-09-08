---
name: designmd-spec
description: 디자인 토큰 정의서(DESIGN.md) 포맷의 정본. Google design.md 규격의 YAML 프론트매터 키, 토큰 상호참조 문법 {group.token}, 본문 절 구조, 공식 린터 규칙, 참고 사이트에 없는 프로젝트 고유 토큰을 넣는 자리를 정한다. designmd-author가 쓸 때, design-system-reviewer가 검수할 때 공통으로 따른다.
---

# designmd-spec

`DESIGN.md`는 한 사이트의 디자인 시스템을 **기계가 읽는 토큰(프론트매터)** + **사람이 읽는 의도(본문)** 로 한 파일에 적는다. 포맷은 Google의 `design.md` 규격(`@google/design.md`)이고, 규격 자체의 정본은 `npx @google/design.md spec`이 출력한다. **이 문서와 그 출력이 다르면 그 출력이 맞다.**

## 1. 프론트매터

규격의 키만 쓴다. 순서도 이대로. 전부 선택이고 `name`만 필수다.

| 키 | 내용 |
| --- | --- |
| `version` | 포맷 버전 라벨. 현재 `alpha` |
| `name` | 시스템 이름. 슬러그. **필수** |
| `description` | 시각적 성격 2~4문장 |
| `colors` | 평면 `키: "값"`. 값은 CSS 색(hex · `rgb()` · `oklch()` · 이름). YAML에서 `#`이 주석을 열므로 따옴표로 감싼다. 의미 기반 역할명 |
| `typography` | 역할별 객체. 필드는 `fontFamily` `fontSize`(px) `fontWeight` `lineHeight`(배수) `letterSpacing` `fontFeature` `fontVariation` — 관측된 것만 채운다 |
| `rounded` | 반경 스케일. 작은 → 큰 + `pill` · `full` |
| `spacing` | 8px 기반 간격 스케일 |
| `components` | 컴포넌트마다 `{group.token}` 참조로 정의 |
| `omitted` | 규격의 본문 절 중 의도적으로 생략한 것의 목록 |

```yaml
version: alpha
name: dabangapp
description: >
  ...
colors:
  primary: "#000000"
  on-primary: "#ffffff"
  ink: "#000000"
  canvas: "#ffffff"
  hairline: "#e6e6e6"
  surface-soft: "#f7f7f5"
typography:
  body: { fontFamily: <패밀리>, fontSize: 16px, fontWeight: 400, lineHeight: 1.5, letterSpacing: -0.2px }
rounded: { sm: 6px, md: 8px, lg: 16px, pill: 50px, full: 9999px }
spacing: { xxs: 4px, xs: 8px, sm: 12px, md: 16px, lg: 24px, xl: 32px, xxl: 48px, section: 96px }
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    padding: 10px 20px
```

위 값은 형식 예시다. 실제 값은 인벤토리에서 온다.

| 규칙 | 내용 |
| --- | --- |
| 규격 밖 최상위 키를 두지 않는다 | `motion` `zIndex` `opacity` `border` `focus` `icon` `primitives` `themes` `dataviz` 같은 그룹은 규격 키가 아니다. 린터 `token-like-ignored`(warning)가 잡는다. 관측됐으면 본문으로 — `border` `icon`은 Shapes, `motion` `zIndex` `opacity` `focus`는 Components 소절(상태 · 전환 · 레이어), `primitives`(색 램프)와 `themes`는 Colors 소절, `dataviz`는 차트가 차기 범위라 Known Gaps에 「미사용」으로만 |
| 색은 의미로 명명한다 | `primary` `ink` `canvas` `hairline` `surface-soft` `block-*` `accent-*` `semantic-*`. 같은 값이라도 역할이 다르면 다른 키 |
| 변형 · 상태는 별도 항목 | `-hover` `-focus` `-active`/`-pressed` `-disabled` `-checked`/`-selected` `-error` `-open` |
| `components` 값은 참조 우선 | `padding` · `size`처럼 토큰에 없는 1회성 수치만 직접 |

### 1.1 표준 컴포넌트 어휘

사이트에 **있으면** 이 이름으로, **없으면 만들지 않는다.**

| 그룹 | 표준 토큰명 |
| --- | --- |
| Actions | `button`(+변형) · `pulldown` |
| Forms | `field` · `input` · `textarea` · `select` · `checkbox` · `radio` · `switch` · `date` |
| Containment | `card` · `table` · `tabs` · `dialog` · `popover` |
| Feedback | `alert` · `toast` · `tooltip` · `badge` |
| Date | `calendar` |

### 1.2 프로젝트 정의 — 참고 사이트에 없는 토큰

우리 화면에는 참고 사이트에 없는 것이 있다. 위험 등급 색, 미분석 색, 등급 배지, 판정 근거 표, 알림 토스트.

| 규칙 | 내용 |
| --- | --- |
| 자리 | `colors`의 `risk-*` 키와 `components`의 해당 항목. 별도 최상위 키를 만들지 않는다. 참고 사이트에서 관측한 상태색은 `semantic-*`이므로 접두가 갈린다 — 섞이지 않는다 |
| 이름 | 도메인 용어 `riskGrade`의 값을 소문자로 — `risk-safe` `risk-caution` `risk-danger`. **미분석 등급의 용어는 도메인 용어 표에 없다 — 표에 추가된 뒤 그 이름을 쓴다** |
| 값 | **사용자가 준 값만.** 집필자가 정하지 않는다. 값이 없으면 **키를 넣지 않고** Known Gaps에 「프로젝트 정의 — 값 미확정」으로 적는다 |
| 표기 | 본문 Colors 절에 `> Project-defined:` 소절로 분리하고 `seen on: project`. 참고 사이트 관측값과 섞지 않는다 |
| 명암비 | 이 색 위의 텍스트도 4.5:1을 지킨다. 등급 배지의 `textColor`를 함께 정한다 |

## 2. 토큰 상호참조

`{group.token}` — `{colors.primary}` `{typography.button}` `{rounded.pill}` `{spacing.xxl}`.

| 규칙 | 내용 |
| --- | --- |
| 프론트매터 `components`와 본문 산문 모두 이 문법 | 본문에서 「검은 버튼」이 아니라 「`{colors.primary}` 버튼」 |
| 참조 대상은 프론트매터에 있어야 한다 | 없으면 `broken-ref` |

## 3. 본문 절

프론트매터 아래 `---` 다음. `##` 제목. **규격의 8절은 생략할 수 있지만, 있는 것은 이 순서다**(`section-order`가 본다). 규격에 없는 절은 린터가 건드리지 않으므로 **8절 뒤에** 둔다.

| # | 절 | 담는 것 |
| --- | --- | --- |
| 1 | Overview | 시스템의 성격, Key Characteristics |
| 2 | Colors | Brand & Accent / Surface / Text / Semantic 그룹. 첫 줄 `> Source pages:`. 프로젝트 정의 소절 |
| 3 | Typography | Font Family(주 · 보조 · 폴백 · 오픈소스 대체), Hierarchy 표, Principles |
| 4 | Layout | Spacing System, Grid & Container, Whitespace — **리듬 한 줄 요약만.** 상세는 `layout.md` |
| 5 | Elevation & Depth | 레벨 표, 그림자 값 |
| 6 | Shapes | Border Radius 표, 보더 두께, 아이콘 크기 |
| 7 | Components | 그룹별 토큰 매핑과 사용처. 상태 전환 · 포커스 링 · z-index가 관측됐으면 여기 소절로 |
| 8 | Do's and Don'ts | 시스템을 지키는 · 깨뜨리는 행동 |
| 9 | Responsive Behavior | 규격 밖. Breakpoints, Touch Targets(≥44px), Collapsing |
| 10 | Iteration Guide | 규격 밖. 화면을 만들 때 정할 순서 — 섹션별 surface 색부터 |
| 11 | Known Gaps | 규격 밖. 추정 · 미관측 · 1회 관측 · 프로젝트 정의 미확정 |

## 4. 린트

`npx @google/design.md lint DESIGN.md`. reviewer가 돌리고 결과를 리포트에 붙인다.

| 규칙 | 심각도 | 무엇 |
| --- | --- | --- |
| `broken-ref` | error | 정의되지 않은 토큰 참조 |
| `missing-primary` | warning | 색이 있는데 `primary`가 없다 |
| `contrast-ratio` | warning | 컴포넌트의 `backgroundColor`/`textColor` 쌍이 WCAG AA 4.5:1 미만 |
| `orphaned-tokens` | warning | 어느 컴포넌트도 참조하지 않는 색 토큰 |
| `token-summary` | info | 절별 토큰 개수 |
| `missing-sections` | info | 다른 토큰은 있는데 `spacing` · `rounded`가 없다 |
| `missing-typography` | warning | 색은 있는데 타이포가 없다 |
| `section-order` | warning | 규격의 절이 규격 순서와 다르다 |
| `unknown-key` | warning | 알려진 키의 오타로 보이는 최상위 키(`colours:`). 확장 키는 잡지 않는다 |
| `token-like-ignored` | warning | 토큰처럼 보이는 값을 가진 알 수 없는 최상위 키 — 확장 그룹이 여기 걸린다 |
| `omitted-rules` | info | `omitted` 목록의 정합 |

| 기준 | 값 |
| --- | --- |
| error | 0건이어야 한다 |
| warning | 0건을 목표로 한다. 남기면 리포트에 사유 |
| 린터가 안 보는 것 | 표준 컴포넌트 커버리지, 프로젝트 정의 값의 출처, `layout.md` 대조, 색 외 그룹의 고아 토큰 — `design-fidelity-check` |

## 5. 원칙

| 원칙 | 내용 |
| --- | --- |
| 토큰 우선, 리터럴 최소 | `components`와 본문은 참조로. raw 값은 토큰에 없는 1회성만 |
| 정직한 Known Gaps | 추정 · 미관측 · 1회 관측 · 미확정을 그대로 적는다 |
| `layout.md`와의 분업 | 정의서는 토큰 · 컴포넌트 · 규칙. 섹션 순서 · 그리드 · 여백 리듬은 `layout.md` |
| provenance | `> Source pages:`에 분석한 아키타입 전부. 토큰 · 컴포넌트에 `seen on:`. 반복 관측 = canonical, 1회 관측 = 콘텐츠 의심 |
| 충돌은 덮어쓰지 않는다 | 변형으로 분리하거나 `merge-notes.md`로 사용자 승인 |
| 브랜드 요소 없음 | 로고 · 브랜드명 · 문구 · 사진은 정의서에 없다 |