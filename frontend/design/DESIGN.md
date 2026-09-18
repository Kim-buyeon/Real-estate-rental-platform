---
version: alpha
name: dabangapp
description: >
  무채색 면과 1px 실선으로 구조를 만들고, 파랑 하나만 강조에 쓰는 정보 밀도 높은 시스템이다.
  그림자를 거의 쓰지 않고 보더와 옅은 회색 띠로 면을 나눈다. 반경은 작고(0~12px) 버튼·입력은
  높이가 커서(48~60px) 폼이 시원하다. 위험 등급 4색은 참고 사이트에 없는 프로젝트 정의이며,
  무채색 바탕 위에서 등급만 색으로 구분되도록 채도를 억제해 골랐다.
colors:
  # ── 주색 ──
  primary: "#326cf9"          # 기존 구현값 유지. 계측 추정 #3d6ff5는 흰 글자 대비 4.38 — Known Gaps K-03
  primary-hover: "#2a5fe0"    # 미관측 · 기존 구현값 유지
  primary-surface: "#eaf0ff"  # seen on: map-search/05 · listing/02
  on-primary: "#ffffff"
  # ── 바탕 ──
  background: "#ffffff"
  surface: "#ffffff"
  surface-muted: "#f4f5f6"    # seen on: home/01 · listing/02 · map-search/03
  inverse-surface: "#1e1e1e"  # seen on: favorites/01-03 · my-info/01 · listing/05
  on-inverse: "#ffffff"
  footer-surface: "#3c3c3c"   # seen on: home/03 · login/01 · favorites/01 · listing/06
  footer-surface-raised: "#4a4a4a"
  on-footer: "#c9c9c9"
  footer-divider: "#575757"
  disabled-surface: "#d8d8d8" # seen on: signup/01
  # ── 글자 ──
  text: "#222222"
  text-secondary: "#666666"
  text-tertiary: "#767676"    # 계측 #9e9e9e는 흰 바탕 대비 2.6 — 기존 구현값 유지. Known Gaps K-04
  text-disabled: "#999999"
  # ── 보더 ──
  border: "#e5e5e5"
  border-subtle: "#e5e8eb"    # 미관측 · 기존 구현값 유지
  border-strong: "#333333"    # seen on: listing/05
  focus: "#326cf9"            # 미관측 · 기존 구현값 유지
  # ── 상태 ──
  error: "#c92a2a"            # 미관측 · 기존 구현값 유지
  error-surface: "#fdecec"    # 미관측 · 기존 구현값 유지
  on-error: "#ffffff"         # 미관측 · 기존 구현값 유지
  # ── 위험 등급 · 프로젝트 정의 ──
  risk-safe: "#1b7f45"
  risk-caution: "#b35600"
  risk-danger: "#c92a2a"
  risk-unanalyzed: "#6b7280"
  on-risk: "#ffffff"
typography:
  display: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 40px, fontWeight: 700, lineHeight: 1.3 }
  heading-1: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 28px, fontWeight: 700, lineHeight: 1.35 }
  heading-2: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 20px, fontWeight: 700, lineHeight: 1.4 }
  heading-3: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 18px, fontWeight: 700, lineHeight: 1.4 }
  body-lg: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 16px, fontWeight: 400, lineHeight: 1.6 }
  body: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 15px, fontWeight: 400, lineHeight: 1.6 }
  body-strong: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 15px, fontWeight: 700, lineHeight: 1.6 }
  body-sm: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 13px, fontWeight: 400, lineHeight: 1.55 }
  label: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 14px, fontWeight: 500, lineHeight: 1.5 }
  link: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 14px, fontWeight: 400, lineHeight: 1.5 }
  button: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 16px, fontWeight: 700, lineHeight: 1 }
  button-sm: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 13px, fontWeight: 400, lineHeight: 1 }
  eyebrow: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 13px, fontWeight: 700, lineHeight: 1.4 }
  caption: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 12px, fontWeight: 400, lineHeight: 1.4 }
rounded: { none: 0px, sm: 4px, md: 8px, lg: 12px, pill: 9999px }
spacing:
  3xs: 2px      # 미관측 · 기존 구현값 유지
  2xs: 4px
  xs: 8px
  sm: 12px
  md: 16px
  lg: 20px
  xl: 24px
  2xl: 32px
  3xl: 48px
  4xl: 64px
  5xl: 80px
components:
  # ── Actions ──
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button}"
    rounded: "{rounded.sm}"
    height: 60px
    padding: 0 24px
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
    textColor: "{colors.on-primary}"
  button-primary-disabled:
    backgroundColor: "{colors.disabled-surface}"
    textColor: "{colors.text-disabled}"
    typography: "{typography.button}"
    rounded: "{rounded.sm}"
    height: 60px
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    height: 48px
  button-secondary-sm:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-secondary}"
    borderColor: "{colors.border}"
    typography: "{typography.button-sm}"
    rounded: "{rounded.sm}"
    height: 36px
  button-ghost:
    textColor: "{colors.text-secondary}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
  button-icon:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    borderColor: "{colors.border}"
    rounded: "{rounded.sm}"
    size: 57px 60px
  button-footer:
    backgroundColor: "{colors.footer-surface-raised}"
    textColor: "{colors.on-footer}"
    typography: "{typography.button-sm}"
    rounded: "{rounded.sm}"
    height: 32px
  button-footer-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button-sm}"
    rounded: "{rounded.sm}"
    height: 32px
  button-social:
    backgroundColor: "{colors.footer-surface-raised}"
    textColor: "{colors.on-footer}"
    rounded: "{rounded.pill}"
    size: 36px
  pulldown:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    height: 40px
    padding: 0 16px
  # ── Forms ──
  field:
    textColor: "{colors.text}"
    typography: "{typography.body-strong}"
    gap: 12px
  field-hint:
    textColor: "{colors.text-secondary}"
    typography: "{typography.caption}"
  field-error:
    textColor: "{colors.error}"
    typography: "{typography.caption}"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    height: 48px
    padding: 0 16px
  input-placeholder:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-tertiary}"
    typography: "{typography.body}"
  input-focus:
    borderColor: "{colors.focus}"
    shadow: "0 0 0 1px {colors.focus}"
  input-error:
    borderColor: "{colors.error}"
  input-disabled:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.text-disabled}"
  select:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: 8px 12px
    minWidth: 140px
  # ── Containment ──
  card:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.border-subtle}"
    rounded: "{rounded.lg}"
    padding: 20px
  card-form:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.border}"
    rounded: "{rounded.none}"
    padding: 80px
    width: 540px
  card-summary:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.border}"
    rounded: "{rounded.md}"
    padding: 24px
  card-list-item:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.border}"
    rounded: "{rounded.none}"
    padding: 24px
  table:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.border}"
  table-header:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-secondary}"
    borderColor: "{colors.border-strong}"
    typography: "{typography.body}"
    height: 62px
  table-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    padding: 22px 0
  tabs-segmented:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body-lg}"
    rounded: "{rounded.none}"
    height: 70px
  tabs-segmented-selected:
    backgroundColor: "{colors.inverse-surface}"
    textColor: "{colors.on-inverse}"
  tabs-underline:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-secondary}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    height: 58px
    gap: 36px
  tabs-underline-selected:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    borderColor: "{colors.primary}"
  tabs-pill:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.pill}"
    height: 30px
  tabs-pill-selected:
    backgroundColor: "{colors.inverse-surface}"
    textColor: "{colors.on-inverse}"
  popover:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    rounded: "{rounded.md}"
  disclosure:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border-subtle}"
    rounded: "{rounded.md}"
    padding: 12px 16px
  # ── Feedback ──
  alert-info:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.text-secondary}"
    borderColor: "{colors.border-subtle}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: 16px
  alert-error:
    backgroundColor: "{colors.error-surface}"
    textColor: "{colors.error}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: 16px
  tooltip:
    backgroundColor: "{colors.inverse-surface}"
    textColor: "{colors.on-inverse}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    padding: 8px 14px
  badge-neutral:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.text-secondary}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: 4px 12px
  badge-primary:
    backgroundColor: "{colors.primary-surface}"
    textColor: "{colors.primary-hover}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: 4px 12px
  badge-inverse:
    backgroundColor: "{colors.inverse-surface}"
    textColor: "{colors.on-inverse}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: 4px 12px
  badge-tag:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-secondary}"
    borderColor: "{colors.border}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: 0 12px
    height: 28px
  badge-risk-safe:
    backgroundColor: "{colors.risk-safe}"
    textColor: "{colors.on-risk}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: 4px 12px
    height: 30px
  badge-risk-caution:
    backgroundColor: "{colors.risk-caution}"
    textColor: "{colors.on-risk}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: 4px 12px
    height: 30px
  badge-risk-danger:
    backgroundColor: "{colors.risk-danger}"
    textColor: "{colors.on-risk}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: 4px 12px
    height: 30px
  badge-risk-unanalyzed:
    backgroundColor: "{colors.risk-unanalyzed}"
    textColor: "{colors.on-risk}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: 4px 12px
    height: 30px
  # ── 표준 어휘에 역할이 없는 관측 컴포넌트 ──
  top-nav:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    height: 80px
  site-footer:
    backgroundColor: "{colors.footer-surface}"
    textColor: "{colors.on-footer}"
    borderColor: "{colors.footer-divider}"
    typography: "{typography.body-sm}"
  info-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    height: 76px
  kv-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-secondary}"
    borderColor: "{colors.border}"
    typography: "{typography.body}"
    height: 66px
    labelWidth: 150px
  empty-state:
    textColor: "{colors.text}"
    typography: "{typography.body-lg}"
    gap: 29px
  action-bar:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.border}"
    height: 92px
    padding: 16px
omitted: []
---

## 1. Overview

무채색 팔레트 하나와 파랑 하나로 끝나는 시스템이다. 면을 나누는 수단은 그림자가 아니라 1px
`{colors.border}` 실선과 `{colors.surface-muted}` 가로 띠이고, 강조는 `{colors.primary}` 한 색이
독점한다. 반경은 0~12px로 작고, 폼 컨트롤은 높이 48~60px로 커서 밀도가 높은 목록·표와 대비된다.

### Key Characteristics

| 성격 | 내용 |
| --- | --- |
| 단일 강조색 | 강조 수치 · 주 버튼 · 선택된 `{components.tabs-underline-selected}`가 전부 `{colors.primary}` 하나를 쓴다 |
| 선으로 나누는 면 | 카드 · 표 · 탭 · 입력 모두 1px `{colors.border}`. 그림자는 지도 위에 얹히는 면에만 |
| 반전 선택 | 선택 상태를 색조가 아니라 `{colors.inverse-surface}` 반전으로 표시한다 (`{components.tabs-segmented-selected}`) |
| 큰 폼 컨트롤 | `{components.input}` 48px · `{components.button-primary}` 60px. 폼 카드 패딩 `{spacing.5xl}` |
| 낮은 채도의 등급색 | 프로젝트 정의 `{colors.risk-safe}` · `{colors.risk-caution}` · `{colors.risk-danger}`는 무채색 화면에서 등급만 도드라지게 채도를 억제했다 |

> **픽셀 배율은 ×1.0이다 — 이 문서의 px는 캡처 원본의 픽셀 그대로다.** 캡처 환경이 고DPI라면
> 원본을 2로 나눠야 하지만 그렇지 않다고 판단했다. 근거 둘. (1) 컨테이너가 1330px인데 ÷2를 적용하면
> CSS 뷰포트가 1259px이 되어 컨테이너가 뷰포트보다 넓어진다 — 성립하지 않는다. (2) ÷2에서는 입력
> 23px · 주 버튼 30px · 헤더 40px이 되는 반면 ×1.0에서는 48 · 60 · 80 · 540 · 380이 전부 8의 배수로
> 떨어진다. 뷰포트 2518~2559px의 초광폭 데스크톱에서 캡처한 것으로 본다. 계측 원자료는
> `analysis/tokens.md` 0장에 있으나 그 파일은 커밋 대상이 아니므로(`design-extract` 2장) 근거를
> 여기 옮겨 적는다. 이 판단이 뒤집히면 px 값을 일괄로 2로 나누면 되고, 비율 기반 값(컨테이너 대비
> 컬럼 폭 등)은 배율과 무관하게 유효하다.

---

## 2. Colors

> Source pages: `map-search` · `favorites` · `login` · `signup` · `my-info` · `home`(푸터 구역) · `listing`
> (`listing`은 우리가 만들지 않는 화면이고 토큰 인벤토리 계측에만 썼다. **`home`은 메인 화면(`/`)의 정본이 됐다** —
> 처음에는 푸터 구역만 계측했으나 메인을 만들면서 본문 구역까지 보강했다(이슈 #117). `home/01` · `home/02`는
> 약 1.33~1.34배 확대 캡처라 보정값이며, 푸터 정본은 `home/03`이다.)

### Brand & Accent

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.primary}` | `#326cf9` | 주 버튼 · 선택된 탭 글자와 인디케이터 · 강조 수치 · 링크 | login/01 · listing/02 · map-search/02 (값은 기존 구현값 — K-03) |
| `{colors.primary-hover}` | `#2a5fe0` | 주 버튼 hover · `{components.badge-primary}` 글자 | 미관측 · 기존 구현값 |
| `{colors.primary-surface}` | `#eaf0ff` | 옅은 강조 면 — 활성 내비 pill · 선택 칩 · `{components.badge-primary}` 배경 | map-search/05 · listing/02 |
| `{colors.on-primary}` | `#ffffff` | `{colors.primary}` 위의 글자 | 전 컷 |

### Surface

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.background}` | `#ffffff` | 페이지 바탕 | 전 컷 |
| `{colors.surface}` | `#ffffff` | 카드 · 패널 · 표 본문 · 내비 | 전 컷 |
| `{colors.surface-muted}` | `#f4f5f6` | 섹션 사이 가로 띠 · 타일 면 · `{components.alert-info}` 배경 | home/01 · listing/02 · map-search/03 |
| `{colors.inverse-surface}` | `#1e1e1e` | 선택 반전 면 · `{components.tooltip}` · `{components.badge-inverse}` | favorites/01-03 · my-info/01 · listing/05 |
| `{colors.on-inverse}` | `#ffffff` | 반전 면 위의 글자 | 〃 |
| `{colors.footer-surface}` | `#3c3c3c` | 푸터 구역 2~4 배경 | home/03 · login/01 · favorites/01 · listing/06 |
| `{colors.footer-surface-raised}` | `#4a4a4a` | 푸터 소형 버튼 · 소셜 버튼 면 | home/03 |
| `{colors.on-footer}` | `#c9c9c9` | 푸터 링크 · 본문 | 〃 |
| `{colors.footer-divider}` | `#575757` | 푸터 가로 · 세로 구분선 | home/03 |
| `{colors.disabled-surface}` | `#d8d8d8` | 비활성 주 버튼 배경 | signup/01 (1회 관측) |

### Text

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.text}` | `#222222` | 제목 · 라벨 · 주 본문 | 전 컷 |
| `{colors.text-secondary}` | `#666666` | 값 텍스트 · 표 본문 · 부가 설명 | map-search/02 · my-info/01 · listing/05 |
| `{colors.text-tertiary}` | `#767676` | 보조 설명 · placeholder · 셰브런. **흰 면 위에서만** | 역할은 관측(map-search/01 · favorites/01), 값은 기존 구현값 — K-04 |

> `{colors.text-tertiary}`는 `{colors.surface}` 위에서 **4.54**로 AA를 넘지만 `{colors.surface-muted}` 위에서는
> **4.16**으로 미달한다. 회색 띠 · `{components.alert-info}` 안의 본문 텍스트에는 `{colors.text-secondary}`를
> 쓴다. placeholder는 `{components.input-placeholder}`가 `{colors.surface}` 배경을 명시하므로 통과한다.
> 린터는 정의된 쌍만 보므로 이 조합을 잡지 못한다 — 8절 Don't에도 적었다.
| `{colors.text-disabled}` | `#999999` | 비활성 컨트롤 글자 | 미관측 · 기존 구현값 |

### Border

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.border}` | `#e5e5e5` | 기본 1px 선 — 카드 · 입력 · 탭 · 행 구분 | 전 컷 |
| `{colors.border-subtle}` | `#e5e8eb` | 더 옅은 선 — `{components.card}` · `{components.disclosure}` | 미관측 · 기존 구현값 |
| `{colors.border-strong}` | `#333333` | 표 헤더 상단 2px 선 | listing/05 (1회 관측) |
| `{colors.focus}` | `#326cf9` | 포커스 링 | 미관측 · 기존 구현값 |

### Semantic

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.error}` | `#c92a2a` | 검증 실패 글자 · `{components.input-error}` 보더 | 미관측 · 기존 구현값 |
| `{colors.error-surface}` | `#fdecec` | `{components.alert-error}` 배경 | 미관측 · 기존 구현값 |
| `{colors.on-error}` | `#ffffff` | 진한 오류 면 위의 글자 | 미관측 · 기존 구현값 |

참고 사이트에서 관측된 의미색(실거래가 최고/최저 배지, 검증 배지, 「새단장」 pill)은 전부 1회 관측이고
우리 화면에 대응 역할이 없어 채택하지 않았다 — Known Gaps K-08. `listing` 전용 블록 색 3종도 같다.

### > Project-defined:

참고 사이트에 없다. `input.md` 4장의 사용자 승인값만 옮겼다. seen on: project.

| 토큰 | 값 | 흰 글자 대비 | 쓰임 |
| --- | --- | --- | --- |
| `{colors.risk-safe}` | `#1b7f45` | 5.04 | 안전 등급 배지 · 지도 마커 |
| `{colors.risk-caution}` | `#b35600` | 4.94 | 주의 등급 |
| `{colors.risk-danger}` | `#c92a2a` | 5.46 | 위험 등급 |
| `{colors.risk-unanalyzed}` | `#6b7280` | 4.83 | 분석 이력이 없는 매물 |
| `{colors.on-risk}` | `#ffffff` | — | 위 네 색 위의 글자 |

네 색 모두 `{colors.on-risk}`와 4.5:1을 넘는다. 등급 색은 **반드시 `{components.badge-risk-safe}` 계열을
통해서만** 화면에 나온다 — 색만 따로 쓰지 않는다. `{colors.risk-unanalyzed}`의 키 이름은 도메인 용어 표에
미분석 등급 용어가 추가되면 그 이름을 따른다 (K-09).

---

## 3. Typography

### Font Family

| 항목 | 값 |
| --- | --- |
| 주 패밀리 | 한글 지오메트릭 산세리프 1종. 20컷 전부 동일 (측정) |
| 참고 사이트 패밀리 이름 | **확정 불가** — Pretendard 계열로 보이나 JPG에서 확정할 수 없다 (K-05) |
| 우리 스택 | `Pretendard Variable, Pretendard, -apple-system, BlinkMacSystemFont, system-ui, Apple SD Gothic Neo, Noto Sans KR, Malgun Gothic, sans-serif` — 오픈소스 Pretendard로 대체한다 |
| 숫자 · 라틴 | 본문과 같은 패밀리로 렌더된다. 별도 숫자 폰트 없음 |
| mono | 미관측 · 미사용 |

### Hierarchy

| 역할 | size / weight / line-height | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{typography.display}` | 40 / 700 / 1.3 | 페이지 제목(중앙 정렬) | favorites/01 · my-info/01 |
| `{typography.heading-1}` | 28 / 700 / 1.35 | 폼 카드 제목 · 상세 패널 가격 | login/01 · signup/01 · map-search/05 |
| `{typography.heading-2}` | 20 / 700 / 1.4 | 섹션 제목 · 패널 헤더 | map-search/02-03 · listing/05 |
| `{typography.heading-3}` | 18 / 700 / 1.4 | 목록 카드 가격 · 표 행 제목 | map-search/01 · home/02 |
| `{typography.body-lg}` | 16 / 400 / 1.6 | 폼 안내문 · 빈 상태 · 탭 라벨 | login/01 · favorites/01 |
| `{typography.body}` | 15 / 400 / 1.6 | 라벨/값 행 · 표 본문 | map-search/02 · my-info/01 |
| `{typography.body-strong}` | 15 / 700 / 1.6 | `{components.field}` 라벨 · `{components.kv-row}` 라벨 | login/01 · map-search/02 |
| `{typography.body-sm}` | 13 / 400 / 1.55 | 푸터 정보 · 목록 카드 설명 · 보조 줄 | home/03 · listing/05 |
| `{typography.label}` | 14 / 500 / 1.5 | 폼 보조 라벨 | 미관측 · 기존 구현값 유지 |
| `{typography.link}` | 14 / 400 / 1.5 | 푸터 링크 · 폼 하단 보조 링크 | home/03 · login/01 |
| `{typography.button}` | 16 / 700 / 1 | 주 버튼 라벨 | login/01 · listing/02 |
| `{typography.button-sm}` | 13 / 400 / 1 | 소형 버튼 라벨 | map-search/02 · home/03 |
| `{typography.eyebrow}` | 13 / 700 / 1.4 | 푸터 사이트맵 컬럼 제목 | home/03 |
| `{typography.caption}` | 12 / 400 / 1.4 | 배지 · 아이콘 라벨 | map-search/03 · listing/05 |

> **위 표의 size · weight · line-height는 전부 컷에서 역산한 추정이다 (K-07).** `analysis/tokens.md` 0.3 · 2.2가
> 「폰트 크기는 글자 높이 역산, weight도 추정」으로 못박은 값이다. 반복 관측된 역할(`{typography.body}` ·
> `{typography.caption}` · `{typography.button}`)이 상대적으로 신뢰도가 높고, 1~2컷에서만 나온 역할
> (`{typography.display}` · `{typography.eyebrow}`)은 낮다.

`heading-1` `heading-2` `heading-3` `body` `body-strong` `label` `caption` 일곱은 기존 `typography.css`의
클래스 이름과 1:1이다. 나머지 일곱은 계측에서 나온 역할이라 추가했다 — 삭제한 역할은 없다.

### Principles

- **크기는 14단계가 아니라 역할로 고른다.** 같은 15px라도 라벨이면 `{typography.body-strong}`, 값이면 `{typography.body}`다.
- 굵기는 400 · 500 · 700 셋뿐이다. 600은 쓰지 않는다 (계측에 없다).
- letter-spacing은 **전 역할 미관측**이라 지정하지 않는다 — 브라우저 기본값 (K-06).
- 한국어 본문은 `word-break: keep-all`.

---

## 4. Layout

### Spacing System

8px 격자 기반이다. 계측된 간격을 격자에 맞춘 값이며, 키 이름은 기존 `tokens.css`의 것을 유지한다.

| 토큰 | 값 | 쓰임 |
| --- | --- | --- |
| `{spacing.3xs}` | 2px | 포커스 링 두께 · 탭 인디케이터 (미관측 · 기존 구현값 유지) |
| `{spacing.2xs}` | 4px | 사진 그리드 갭 · 배지 내부 상하 |
| `{spacing.xs}` | 8px | 칩 사이 · 푸터 소형 버튼 갭 |
| `{spacing.sm}` | 12px | 배지 좌우 패딩 · 라벨↔입력 |
| `{spacing.md}` | 16px | `{components.pulldown}` 사이 · 타일 갭 · 입력 좌우 패딩 |
| `{spacing.lg}` | 20px | 카드 그리드 갭 |
| `{spacing.xl}` | 24px | 패널 좌우 패딩 · 표 셀 상하 패딩 |
| `{spacing.2xl}` | 32px | 폼 묶음 사이 |
| `{spacing.3xl}` | 48px | 폼 카드 제목↔구분선 · 입력↔주 버튼 |
| `{spacing.4xl}` | 64px | 페이지 섹션 사이 |
| `{spacing.5xl}` | 80px | 폼 카드 내부 패딩 |

격자에 맞지 않는 관측값(44 · 56 · 76 · 184)은 토큰으로 올리지 않았다. 해당 화면의 `layout.md`가 갖는다.

### Grid & Container

| 항목 | 값 | 출처 |
| --- | --- | --- |
| 참고 사이트 고정 컨테이너 | **1330px** (뷰포트 2518~2559의 6컷에서 불변) | 계측 |
| **우리 컨테이너 최대 폭** | **1200px** | 프로젝트 정의 (`input.md` 4장 · 2026-09-18 승인) |
| 4컬럼 그리드 | 컬럼 **285px** · gap **20px** (`{spacing.lg}`) | **환산값** |
| 유동 아키타입 | 지도 탐색만 컨테이너 없이 뷰포트 전폭 | 계측 |

> **참고 사이트의 1330 컨테이너 · 컬럼 318 · gap 20을 우리 1200 컨테이너에 비율로 환산해 컬럼 285 · gap 20을
> 채택했다.** 4×285 + 3×20 = 1200. 컬럼 폭 대비 gap 비율(약 7%)이 계측과 같다.
>
> **285는 좌우 패딩 0을 전제한 값이다.** 실제 컨테이너는 좌우 패딩 24를 갖는다(9절 · `examples/home/layout.md`).
> 그 안에서 같은 4열을 잡으면 (1200 − 48 − 60) ÷ 4 = **273**이다. **코드는 px를 적지 않고
> `repeat(4, 1fr)` + `gap: {spacing.lg}`로 잡는다** — 컨테이너 폭이 바뀌면 컬럼이 따라간다. 285와 273은
> 같은 그리드를 기준점만 달리 읽은 것이고, 둘 중 하나가 틀린 것이 아니다.

### Whitespace

리듬은 「폼 안은 `{spacing.3xl}`, 섹션 사이는 `{spacing.4xl}`, 카드 안쪽은 `{spacing.5xl}`」 세 박자다.
섹션 순서 · 그리드 배치 · 구간별 여백은 `examples/<아키타입>/layout.md`가 갖는다. 정의서는 다시 쓰지 않는다.

---

## 5. Elevation & Depth

| 레벨 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| 0 | 그림자 없음. 1px `{colors.border}`로만 면을 나눈다 | 카드 · 폼 · 표 · 탭 — **기본값** | login/01 · favorites/01 · my-info/01 · listing/05 |
| 1 | `0 2px 10px rgba(0,0,0,0.06)` (추정) | 떠 있는 카드 · 검색 바 | home/01 (줌 보정) |
| 2 | 좌측 경계에 옅은 그림자 | 지도 위에 얹히는 상세 패널 | map-search/02 · map-search/05 |
| 3 | **미관측** | 모달 — 컷 없음 (K-12) | — |

**이 시스템은 그림자를 거의 쓰지 않는다.** 깊이가 필요하면 먼저 1px `{colors.border}`와
`{colors.surface-muted}` 띠를 쓰고, 레벨 1 이상은 실제로 다른 면 위에 떠 있을 때만 쓴다.

---

## 6. Shapes

### Border Radius

> **키 이름은 기존 `tokens.css`를 따랐다.** 계측 스케일과 한 칸씩 어긋나므로 대조할 때 주의한다 —
> `analysis/tokens.md` 4장의 `radius.xs`(4) · `radius.sm`(8) · `radius.md`(12)가 각각 `{rounded.sm}` ·
> `{rounded.md}` · `{rounded.lg}`다. 값 대응은 1:1이고 바뀐 것은 이름뿐이다. `radius.full`(50%)은
> 별도 키를 만들지 않고 정사각에 `{rounded.pill}`을 적용해 대체한다.

| 토큰 | 값 | 쓰임 |
| --- | --- | --- |
| `{rounded.none}` | 0 | `{components.card-form}` · `{components.input}` · `{components.tabs-segmented}` · 표 |
| `{rounded.sm}` | 4px | `{components.button-primary}` · 배지 · 태그 칩 · 썸네일 |
| `{rounded.md}` | 8px | `{components.select}` · `{components.popover}` · `{components.disclosure}` · 요약 카드 |
| `{rounded.lg}` | 12px | `{components.card}` |
| `{rounded.pill}` | 9999px | `{components.pulldown}` · `{components.tabs-pill}` · 원형 버튼(정사각에 적용하면 원) |

폼 입력의 반경은 계측에서 0~2px로 읽혔고 `{rounded.none}`으로 확정했다 (추정 — K-07).

### Border Width

| 값 | 쓰임 |
| --- | --- |
| 1px | 기본. 카드 · 입력 · 탭 · 행 구분선 · 푸터 구분선 |
| 2px | 강조. 표 헤더 상단(`{colors.border-strong}`) · 선택된 `{components.tabs-underline-selected}` 하단 인디케이터(`{colors.primary}`) |

### Icon

| 크기 | 쓰임 |
| --- | --- |
| 16px | 본문 아이콘 · 셰브런 |
| 20px | 푸터 검색 아이콘 |
| 24px | 액션 아이콘 · 입력 접미 아이콘 |
| 32px | 타일 아이콘 (타일 72px) |
| 36px | 원형 소셜 · 아바타(내비) |

stroke는 약 1.5px 라인 아이콘이다(추정). **아이콘의 모양 · 세트는 정의서가 다루지 않는다 — 브랜드 요소다.**

---

## 7. Components

### Actions

| 토큰 | 매핑 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{components.button-primary}` | `{colors.primary}` / `{colors.on-primary}` · `{typography.button}` · `{rounded.sm}` · h60 | 폼 제출 · 상세 패널 주 동작. 폼에서는 100% 폭 | login/01 · listing/02 · map-search/02 |
| `{components.button-primary-hover}` | `{colors.primary-hover}` | hover | 미관측 · 기존 구현값 |
| `{components.button-primary-disabled}` | `{colors.disabled-surface}` / **`{colors.text-disabled}`** | 입력 미완료 시 제출 | signup/01 (글자색은 의도적 이탈 — 8절 · K-01) |
| `{components.button-secondary}` | `{colors.surface}` / `{colors.text}` + 1px `{colors.border}` · h48 | 보조 동작 · 더보기 | map-search/03 · map-search/05 |
| `{components.button-secondary-sm}` | `{colors.surface}` / `{colors.text-secondary}` · `{typography.button-sm}` · h36 | 목록 행 안의 동작 | map-search/02 |
| `{components.button-ghost}` | 배경 없음 / `{colors.text-secondary}` | 목록 · 패널의 약한 동작 | 미관측 · 기존 구현값 |
| `{components.button-icon}` | `{colors.surface}` / `{colors.primary}` + 1px `{colors.border}` | 상세 패널 하단 공유 · 찜 | map-search/02 |
| `{components.button-footer}` | `{colors.footer-surface-raised}` / `{colors.on-footer}` · h32 | 푸터 소형 버튼 | home/03 |
| `{components.button-footer-primary}` | `{colors.primary}` / `{colors.on-primary}` · h32 | 푸터 소형 버튼 중 강조 1개 | home/03 |
| `{components.button-social}` | `{colors.footer-surface-raised}` / `{colors.on-footer}` · `{rounded.pill}` · 36px | 푸터 원형 버튼 | home/03 |
| `{components.pulldown}` | `{colors.surface}` / `{colors.text}` + 1px `{colors.border}` · `{rounded.pill}` · h40 | 필터 줄 | map-search/01 · map-search/05 |

### Forms

| 토큰 | 매핑 | 쓰임 |
| --- | --- | --- |
| `{components.field}` | 라벨 `{typography.body-strong}` `{colors.text}` · 라벨↔입력 `{spacing.sm}` | 라벨 + 입력 묶음 |
| `{components.field-hint}` | `{colors.text-secondary}` · `{typography.caption}` | 보조 설명 (미관측 · 기존 구현값) |
| `{components.field-error}` | `{colors.error}` · `{typography.caption}` | 검증 메시지. 서버 `error.field`가 정본 (미관측 · 기존 구현값) |
| `{components.input}` | `{colors.surface}` + 1px `{colors.border}` · `{rounded.none}` · h48 · 좌우 `{spacing.md}` | 폼 입력 |
| `{components.input-placeholder}` | `{colors.text-tertiary}` | placeholder (계측 `#aaaaaa` 미채택 — K-04) |
| `{components.input-focus}` | `{colors.focus}` 보더 + 1px 링 | 포커스 (미관측 · 기존 구현값) |
| `{components.input-error}` | `{colors.error}` 보더 | 검증 실패 |
| `{components.input-disabled}` | `{colors.surface-muted}` / `{colors.text-disabled}` | 비활성 입력 |
| `{components.select}` | `{colors.surface}` / `{colors.text}` + 1px `{colors.border}` · `{rounded.md}` · 패딩 8/12 · 최소 폭 140 | **프로젝트 정의** — seen on: project |

참고 사이트의 필터는 native select가 아니라 커스텀 `{components.pulldown}`이다. 우리 `select`는
참고 사이트에 없고 기존 구현(`Select.module.css`)의 값을 그대로 옮겼다.

### Containment

| 토큰 | 매핑 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{components.card}` | `{colors.surface}` + 1px `{colors.border-subtle}` · `{rounded.lg}` · 패딩 `{spacing.lg}` | 일반 카드 | project (기존 구현 — 계측 `card-summary`와 충돌, K-10) |
| `{components.card-form}` | `{colors.surface}` + 1px `{colors.border}` · `{rounded.none}` · 패딩 `{spacing.5xl}` · 540px | 로그인 · 가입 · 프로필 카드 | login/01 · signup/01 · my-info/01 |
| `{components.card-summary}` | 1px `{colors.border}` · `{rounded.md}` · 패딩 `{spacing.xl}` | 수치 요약 카드 | map-search/04 (1회 관측) |
| `{components.card-list-item}` | 하단 1px `{colors.border}` · 패딩 `{spacing.xl}` | 매물 목록 행 | map-search/01 |
| `{components.table}` · `{components.table-header}` · `{components.table-row}` | 헤더 상단 2px `{colors.border-strong}` · 헤더 배경은 `{colors.surface}`(회색 아님) · 행마다 1px `{colors.border}` · 셀 상하 22px | **판정 근거 표** | listing/05 |
| `{components.tabs-segmented}` (+`-selected`) | 컨테이너 N등분 · h70 · 맞붙는 1px 스트립 · 선택은 `{colors.inverse-surface}` 반전 | 페이지 상단 탭 | favorites/01-03 · my-info/01 |
| `{components.tabs-underline}` (+`-selected`) | h58 · 간격 36 · 선택은 `{colors.primary}` 글자 + 2px 인디케이터 | 상세 패널 탭 | map-search/02-04 |
| `{components.tabs-pill}` (+`-selected`) | h30 · `{rounded.pill}` | 목록 상단 전환 | map-search/05 |
| `{components.popover}` | `{colors.surface}` + 1px `{colors.border}` · `{rounded.md}` | 지도 위 컨트롤 면 | map-search/01 · map-search/05 |
| `{components.disclosure}` | `{colors.surface}` + 1px `{colors.border-subtle}` · `{rounded.md}` · 트리거 패딩 12/16 | **프로젝트 정의** — 상세 패널의 건축물대장 · 등기 이력 접기 | project |

`{components.disclosure}`는 규격의 표준 어휘에 없다. 우리 상세 패널이 이미 쓰고 있어 프로젝트 정의
컴포넌트로 넣었고 값은 기존 구현(`Disclosure.module.css`)에서 옮겼다.

### Feedback

| 토큰 | 매핑 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{components.alert-info}` | `{colors.surface-muted}` / `{colors.text-secondary}` + 1px `{colors.border-subtle}` · `{rounded.md}` · 패딩 `{spacing.md}` | 조회 실패 · 빈 상태 안내 | **project** |
| `{components.alert-error}` | `{colors.error-surface}` / `{colors.error}` · `{rounded.md}` · 패딩 `{spacing.md}` | 인라인 오류 | **project** |
| `{components.tooltip}` | `{colors.inverse-surface}` / `{colors.on-inverse}` · `{typography.body-sm}` · 패딩 8/14 | 용어 설명 | map-search/02 · map-search/04 |
| `{components.badge-neutral}` | `{colors.surface-muted}` / `{colors.text-secondary}` | 중립 표시 | map-search/01 |
| `{components.badge-primary}` | `{colors.primary-surface}` / `{colors.primary-hover}` | 강조 표시 | project |
| `{components.badge-inverse}` | `{colors.inverse-surface}` / `{colors.on-inverse}` | 상태 표시(마감 등) | listing/05 |
| `{components.badge-tag}` | `{colors.surface}` + 1px `{colors.border}` / `{colors.text-secondary}` · h28 | 매물 특징 태그 | map-search/05 |

#### > Project-defined: 등급 배지

| 토큰 | 매핑 | 대비 |
| --- | --- | --- |
| `{components.badge-risk-safe}` | `{colors.risk-safe}` / `{colors.on-risk}` | 5.04 |
| `{components.badge-risk-caution}` | `{colors.risk-caution}` / `{colors.on-risk}` | 4.94 |
| `{components.badge-risk-danger}` | `{colors.risk-danger}` / `{colors.on-risk}` | 5.46 |
| `{components.badge-risk-unanalyzed}` | `{colors.risk-unanalyzed}` / `{colors.on-risk}` | 4.83 |

네 배지의 치수와 반경은 계측된 배지 변형의 값을 따른다 — `{typography.caption}` · `{rounded.sm}` ·
좌우 `{spacing.sm}` · 높이 30px (seen on: listing/05). **색은 project, 형태는 관측값이다.**
등급 색 5종은 이 네 컴포넌트를 통해서만 쓰인다.

### 구조

**규격의 표준 어휘에 역할이 없는 프로젝트 고유 컴포넌트다.** 표준 이름(`button` · `card` · `table` …)을
빌릴 수 없어 관측된 역할 이름을 그대로 썼다. 여섯 모두 우리가 만드는 화면의 구조물이고, 출처는
`analysis/tokens.md` 8.12다.

| 토큰 | 매핑 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{components.top-nav}` | `{colors.surface}` / `{colors.text}` · `{typography.body}` · 하단 1px `{colors.border}` · h80 | 전 페이지 상단 내비. 활성 항목은 `{colors.primary}` 글자 + `{colors.primary-surface}` pill. 로그인 상태에서 우측이 아바타(36px) + 닉네임으로 바뀐다 | signup/01 · favorites/01-02 · my-info/01 · listing/02 |
| `{components.site-footer}` | `{colors.footer-surface}` / `{colors.on-footer}` · `{typography.body-sm}` · 구분선 `{colors.footer-divider}` | **`map-search`를 제외한** 전 페이지 하단 — 그 아키타입은 뷰포트의 남은 높이를 전부 쓰는 유동 구조라 푸터를 붙이면 지도가 줄어든다(4절 Grid & Container의 예외와 같은 이유). 참고 사이트도 지도 화면 5컷 모두에 푸터가 없다. 구역 1은 흰 면, 구역 2~4가 어두운 면이다. 내부 버튼은 `{components.button-footer}` · `{components.button-footer-primary}` · `{components.button-social}`. 구역 배치는 `examples/home/layout.md`가 갖는다 | home/03 · login/01 · signup/01 · favorites/01-03 · listing/06 |
| `{components.info-row}` | `{colors.surface}` / 라벨 `{typography.body-strong}` `{colors.text}` · 값 `{colors.text-secondary}` · 우측 끝 셰브런 16px · h76 · 1px `{colors.border}` | 설정·프로필의 「라벨 — 값 — >」 행. 누르면 편집으로 들어간다 | my-info/01 |
| `{components.kv-row}` | `{colors.surface}` / `{colors.text-secondary}` · `{typography.body}` · 행마다 하단 1px `{colors.border}` · h66 · 라벨 열 150px | 상세 패널 판정 근거 · 등기·건축물대장 값 표시. 라벨은 `{typography.body-strong}` `{colors.text}`, 강조 수치는 `{colors.primary}` | map-search/02 · map-search/03 |
| `{components.empty-state}` | `{colors.text}` · `{typography.body-lg}` · 줄 간격 29px | 관심 매물 · 알림의 빈 목록. 2줄 중앙 정렬 — 1줄은 `{colors.text}`, 2줄 보조 설명은 `{colors.text-secondary}`(계측 `ink-subtle`은 흰 면 위 2.68로 미채택 — K-04). 상단 여백 184px는 격자 밖이라 `examples/favorites/layout.md`가 갖는다 | favorites/01-03 |
| `{components.action-bar}` | `{colors.surface}` · 상단 1px `{colors.border}` · h92 · 패딩 `{spacing.md}` | 상세 패널 하단 고정 바. `{components.button-icon}` 2개 + `{components.button-primary}`(나머지 폭 전부), 갭 `{spacing.xs}`. 패널 스크롤과 무관하게 같은 위치 | map-search/02~05 |

- **`{components.info-row}`의 보더는 행 사이가 아니라 목록 마지막 행 아래에만 있다.** 계측(`my-info/01`)은
  행 사이 구분선이 없다. 프론트매터의 `borderColor` 하나만 보면 매 행에 선이 있는 것으로 읽히므로 여기
  적는다. 행마다 선이 있는 것은 `{components.kv-row}` 쪽이다.
- `type-rail` · `detail-panel` · `sub-nav` · `option-tile-grid` · `photo-grid`도 관측됐지만 **토큰이 아니라
  배치**라서 각 `layout.md`가 정본이다 (K-08).

### 상태 · 전환 · 레이어

| 항목 | 내용 |
| --- | --- |
| `-hover` | **전부 미관측** (K-11). `{components.button-primary-hover}`만 기존 구현값을 유지하고, 나머지는 면을 `{colors.surface-muted}`로 낮추는 규칙을 따른다 |
| `-focus` | 미관측. `{spacing.3xs}` 두께 `{colors.focus}` 실선 링 + 같은 값 offset. 키보드 포커스에서만(`:focus-visible`) |
| `-selected` | 관측됨. 두 방식뿐이다 — 반전(`{components.tabs-segmented-selected}`) 또는 `{colors.primary}` 인디케이터(`{components.tabs-underline-selected}`) |
| `-disabled` | 관측된 것은 주 버튼 하나. 8절 참조 |
| 전환 | 미관측. 색 전환 150ms ease, 회전 120ms ease를 넘지 않는다 (기존 구현값) |
| z-index | 미관측. 육안 순서는 콘텐츠 < 지도 컨트롤 < 상세 패널 < `{components.tooltip}` < 모달. 값은 미확정 (K-13) |

---

## 8. Do's and Don'ts

### Do

- **면은 선으로 나눈다.** 1px `{colors.border}`와 `{colors.surface-muted}` 띠를 먼저 쓰고, 그림자는 실제로 떠 있는 것에만.
- **강조는 `{colors.primary}` 하나로 한다.** 선택 · 활성 · 강조 수치가 모두 이 색 하나다.
- **선택 상태는 반전 또는 인디케이터 둘 중 하나로만 표시한다.** 둘을 섞지 않는다.
- **등급은 `{components.badge-risk-safe}` 계열로만 보여준다.** 글자색은 항상 `{colors.on-risk}`다.
- **비활성 버튼의 글자는 `{colors.text-disabled}`를 쓴다.** 참고 사이트는 `{colors.disabled-surface}` 위에
  흰 글자(대비 ≈1.4:1)를 썼지만 **베끼지 않았다.** WCAG 2.2 1.4.3은 비활성 컴포넌트의 텍스트를 대비 요건에서
  제외하지만, 1.4:1은 비활성이라는 사실조차 읽히지 않는 수준이다. `{colors.text-disabled}`로 약 2:1을 확보한다.
  린터 `contrast-ratio`가 여기서 경고를 내면 그것은 **의도된 이탈**이다 (K-01).
- **간격은 토큰에서 고른다.** 44 · 76처럼 격자 밖 값이 필요하면 그 화면의 `layout.md`에 기록한다.

### Don't

- **hex를 컴포넌트 CSS에 적지 않는다.** 색은 토큰 변수로만 온다.
- **역할이 같은데 키를 새로 만들지 않는다.** `text` · `surface` 계열 키 이름은 고정이다.
- **`{colors.primary}`를 큰 면적의 배경으로 쓰지 않는다.** 이 시스템에서 파랑은 컨트롤과 수치에만 붙는다.
- **등급 색을 배지 밖에서 쓰지 않는다.** 글자색 · 보더 색으로 돌려 쓰면 대비 보장이 깨진다.
- **`{colors.text-tertiary}`를 흰 면 밖에서 본문 텍스트로 쓰지 않는다.** `{colors.surface}` 위 4.54는 통과하지만
  `{colors.surface-muted}` 띠나 `{components.alert-info}` 위에서는 4.16으로 미달한다. 그 자리에는
  `{colors.text-secondary}`를 쓴다. 린터는 정의된 쌍만 보므로 이 조합을 잡아주지 않는다.
- **비활성 상태를 opacity로 만들지 않는다.** 배경색과 글자색을 바꾼다 (계측도 opacity 흔적이 없다).
- **그림자를 깊이 표현의 기본으로 쓰지 않는다.**
- **섹션 순서 · 그리드 · 여백 리듬을 이 문서에 다시 적지 않는다.** 정본은 `layout.md`다.

---

## 9. Responsive Behavior

**이 절은 계측값이 아니다.** 20컷 전부 뷰포트 2518~2559px의 데스크톱이고 모바일 · 태블릿 컷이 없다.
아래는 2026-09-18에 승인된 **프로젝트 정의**다.

### Breakpoints

| 구간 | 값 | 표기 |
| --- | --- | --- |
| 모바일 | `~767px` | 프로젝트 정의 |
| 태블릿 | `768~1023px` | 프로젝트 정의 |
| 데스크톱 | `1024px~` | 프로젝트 정의 |
| 컨테이너 최대 폭 | `1200px` | 프로젝트 정의 |

### Touch Targets

- 누를 수 있는 것의 최소 치수는 **44px**다 (프로젝트 정의). `{components.input}` 48 · `{components.button-primary}` 60은
  그대로 충족한다. 모바일에서 높이를 줄일 때도 44 아래로 내리지 않는다.
- **44 하한은 터치 입력 구간(`~767px`)에 적용한다. 포인터 구간(`768px~`)에서는 컴포넌트가 지정한 높이를 따른다.**
  `{components.button-footer}` · `{components.button-footer-primary}`의 `height: 32px`가 44와 부딪치는데, 둘 다
  계측값이라 어느 쪽도 버릴 수 없다 — 입력 수단으로 가른다. 근거는 44가 WCAG 2.2 SC 2.5.5 Target Size (Enhanced)의
  **AAA** 기준이고 AA 기준인 SC 2.5.8 Target Size (Minimum)은 **24×24**라는 것이다. 즉 32는 포인터 구간에서 AA를
  넘고, 터치 구간에서는 우리가 AAA를 목표로 44까지 올린다. 충돌 자체는 K-26에 남긴다.

### Collapsing

붕괴 규칙은 **정의서가 쓰지 않는다.** 각 `layout.md`의 「반응형 붕괴 — 제안(미관측)」을 가리킨다.

| 아키타입 | 어디를 보는가 | 요지 |
| --- | --- | --- |
| `map-search` | `examples/map-search/layout.md` | 데스크톱 좁은 구간부터 상세 패널을 오버레이로, 모바일은 지도 전면 + 하단 시트 |
| `favorites` | `examples/favorites/layout.md` | 등분 탭 → 모바일 가로 스크롤 탭 |
| `my-info` | `examples/my-info/layout.md` | 6등분 탭 → 가로 스크롤, 카드 전폭 |
| `login` · `signup` | `examples/login/layout.md` · `examples/signup/layout.md` | 태블릿까지 붕괴 없음, 모바일에서 카드 전폭 · 패딩 축소 · 보더 제거 |
| 푸터 | `examples/home/layout.md` | 사이트맵 6열 → 3열 → 접기, 링크줄 2줄 |

---

## 10. Iteration Guide

화면을 만들 때 정하는 순서다.

1. **섹션마다 면 색을 먼저 정한다** — `{colors.background}` 위인가, `{colors.surface}` 카드 안인가, `{colors.surface-muted}` 띠인가.
2. **면을 나누는 방법을 고른다** — 1px `{colors.border}`가 기본이다. 그림자는 마지막 수단이다.
3. **타이포 역할을 붙인다** — 제목 `{typography.heading-1}` · 라벨 `{typography.body-strong}` · 값 `{typography.body}` · 보조 `{typography.body-sm}`.
4. **컨트롤을 어휘에서 꺼낸다** — 새 모양을 만들기 전에 7절에 그 역할이 있는지 본다.
5. **간격을 토큰에서 고른다** — 묶음 안은 `{spacing.sm}`~`{spacing.md}`, 묶음 사이는 `{spacing.2xl}`~`{spacing.3xl}`, 섹션 사이는 `{spacing.4xl}`.
6. **상태를 빠뜨리지 않는다** — hover · focus · disabled · 빈 상태 · 오류.
7. **등급 · 오류 색은 마지막에 붙인다** — 배지와 `{components.alert-error}`를 통해서만.

---

## 11. Known Gaps

### 의도적 이탈 — 린터 경고

**실측 총계: error 0 · warning 36 · info 1.** 아래는 예상이 아니라 `npx @google/design.md lint DESIGN.md`를
실행한 결과다(전문은 `reviews/designmd-review.md` 0장). warning 36건은 전부 K-25 · K-01 · K-02로 설명되며,
**error는 0건이다** — 규격 밖 하위 토큰도 값은 전부 정의된 토큰을 가리키므로 실제로 깨진 참조는 없다.

| 규칙 | 건수 | 어디 |
| --- | --- | --- |
| `broken-ref` | **34** | 규격 밖 component 하위 토큰 (K-25) |
| `contrast-ratio` | **2** | `{components.button-primary-disabled}` · `{components.input-disabled}` (K-01 · K-02) |
| `token-summary` (info) | 1 | 색 30 · 타이포 14 · 반경 5 · 간격 11 · 컴포넌트 52 |
| `orphaned-tokens` · `missing-primary` · `section-order` · `unknown-key` · `token-like-ignored` · `missing-sections` · `missing-typography` | **0** | — |

| # | 항목 | 내용 |
| --- | --- | --- |
| K-25 | **규격 밖 component 하위 토큰 — `broken-ref` 34건** | 규격이 인정하는 하위 토큰은 `backgroundColor` · `textColor` · `typography` · `rounded` · `padding` · `size` · `height` · `width` **8개뿐**이고, 여기에 **보더와 간격이 없다.** 내역: `borderColor` **28** · `gap` **3**(`field` · `tabs-underline` · `empty-state`) · `shadow` **1**(`input-focus`) · `minWidth` **1**(`select`) · `labelWidth` **1**(`kv-row`). **유지하기로 결정했다 — 이슈 #110 작업 판단(2026-09-18), 검수 권장 수용. 사용자 승인 사항이 아니다.** 사유 — **이 시스템은 그림자 대신 1px 실선으로 면을 나누므로 보더 색은 부가 정보가 아니라 핵심 표현 수단이고, 따라서 산문이 아니라 기계가 읽는 자리에 남긴다.** 어느 컴포넌트가 `{colors.border}`를 쓰고 어느 것이 `{colors.border-subtle}`를 쓰는지가 `components`에서 사라지면 CSS로 옮길 때 정보를 잃는다. **값은 전부 `{colors.*}` 참조라 실제로 깨진 참조가 아니며 severity도 warning이다**(error 0건). 대가로 린터 경고 34건을 안고 간다 |
| K-01 | **비활성 버튼 대비 — `contrast-ratio` 1건** | 계측(`signup/01`)은 `{colors.disabled-surface}` + 흰 글자로 대비 ≈1.4:1이다. 우리는 글자를 `{colors.text-disabled}`(#999999)로 바꿔 **2.00:1**을 확보했다. 그래도 4.5:1 미만이라 경고가 난다. WCAG 2.2 SC 1.4.3은 **비활성 사용자 인터페이스 구성요소의 텍스트를 대비 요건에서 제외**하므로 위반은 아니다(SC 1.4.11도 같다). **검수에서 예외 적용을 승인했다.** **참고 사이트를 그대로 베끼지 않았다는 사실을 함께 기록한다** |
| K-02 | **`{components.input-disabled}` 대비 — `contrast-ratio` 1건** | `{colors.surface-muted}` + `{colors.text-disabled}` = **2.61:1**. 같은 비활성 예외이고 **검수에서 승인됐다.** 기존 구현값 유지 |
| K-15 | 컴포넌트가 참조하지 않는 색 토큰 2종 | `{colors.background}`(`global.css`이 `background: var(--color-background)`로 실제 사용 중 — 지우면 전역 배경이 깨진다) · `{colors.on-error}`(진한 오류 면 미사용. `tokens.css`가 정의하고 있고 K-19의 토스트·모달에서 쓰인다). **`orphaned-tokens` 경고는 나지 않는다** — 린터가 본문 산문의 참조까지 세는데 둘 다 2절 Surface · Semantic 표와 10절에서 참조되기 때문이다. 경고가 없더라도 **삭제 후보가 아니라는 사실**을 남긴다 |

### 값 충돌 — 승인 대상

| # | 항목 | 내용 |
| --- | --- | --- |
| K-26 | **9절 터치 타겟 44 ↔ `{components.button-footer}` · `{components.button-footer-primary}`의 `height: 32px` 충돌** | 둘 다 버릴 수 없어 **입력 수단으로 갈랐다** — 터치 구간(`~767px`)은 44, 포인터 구간(`768px~`)은 컴포넌트 지정 높이. 규칙은 9절 Touch Targets에 있다. 근거는 44가 WCAG 2.2 SC 2.5.5(**AAA**)이고 AA 기준 SC 2.5.8은 **24×24**라는 것이다 — 32는 포인터 구간에서 AA를 넘는다. 이 충돌은 정의서 안에 원래 있었고 구현이 만든 것이 아니다 (이슈 #112 검토에서 드러남) |
| K-03 | `{colors.primary}` 값 | 계측 추정은 `#3d6ff5`이고 이 값은 `{colors.on-primary}`와 **4.38:1**로 AA에 못 미친다. hex는 JPG 압축 기반 「추정」이므로 기존 구현값 `#326cf9`(4.52)를 유지했다. 계측값을 쓰려면 사용자 승인이 필요하다 |
| K-04 | `{colors.text-tertiary}` 값 | 계측 `#9e9e9e`는 흰 바탕 대비 약 2.6:1로 본문 텍스트에 쓸 수 없다. 기존 `#767676`(4.54)을 유지했다. placeholder 계측값 `#aaaaaa`도 같은 이유로 미채택 |
| K-10 | `{components.card}` 반경 | 기존 구현은 `{rounded.lg}`(12) + `{colors.border-subtle}`, 계측 `card-summary`는 `{rounded.md}`(8) + `{colors.border}`다. 덮어쓰지 않고 **두 항목으로 분리**했다. 하나로 합칠지는 승인 대상 |
| K-14 | 폼 입력↔주 버튼 간격 | `login/01`은 34, `signup/01`은 60으로 같은 역할에 값이 달랐다. **48(`{spacing.3xl}`)로 확정**했다(계측자 권장 수용). 충돌이 있었다는 사실을 남긴다 |

### 추정 · 미관측 (참고 사이트)

| # | 항목 |
| --- | --- |
| K-05 | 폰트 패밀리 이름 미확정. 「한글 지오메트릭 산세리프 1종」까지만 확인했고 폴백 스택은 미관측이다. 우리는 Pretendard로 대체한다 |
| K-06 | letter-spacing 전 역할 미관측 — 지정하지 않음 |
| K-07 | 반경 · 그림자 · 폰트 크기 · weight · hex는 전부 컷에서 역산한 **추정**이다. 입력 반경은 0~2px로 읽혀 `{rounded.none}`으로 확정했다 |
| K-08 | 1회 관측이라 채택하지 않은 색: 실거래가 최고/최저 배지, 검증 배지, 「새단장」 pill, `listing` 블록 색 3종, 콘텐츠 카드 면(`#eeeeee`). 필요해지면 추가한다.<br>**컴포넌트 변형 중 미채택**: `card/grid-item`(home/02 · listing/04) · `tabs/segment-toggle`(map-search/04) · `badge/solid-primary`(listing/05 — 정의서의 `{components.badge-primary}`는 `{colors.primary-surface}` 면이라 다른 것이다). 우리 화면에 역할이 없다.<br>`type-rail` · `detail-panel` · `option-tile-grid` · `photo-grid` · `sub-nav`는 관측됐으나 **토큰이 아니라 구조물**이라 각 `layout.md`가 정본이다 — 7절 「구조」 소절 참조 |
| K-11 | `-hover` · `-focus` · `-error` · 열린 `pulldown` · zebra · 정렬 컨트롤 · 다크 모드 · 애니메이션 전부 미관측 |
| K-12 | 모달 그림자(레벨 3) 미관측 |
| K-13 | z-index 계층 미관측 — 값 미확정 |
| K-16 | 모바일 · 태블릿 컷이 하나도 없다. 9절은 전부 프로젝트 정의이고 붕괴 규칙은 각 `layout.md`의 제안(미관측)이다 |
| K-17 | 표 폭 1364와 컨테이너 1330의 불일치(계측 G-04), 푸터 구역 2 높이 편차 88~100(G-05)은 측정 한계로 남는다 |

### 프로젝트 정의 — 미확정 · 미구현

| # | 항목 |
| --- | --- |
| K-09 | **미분석 등급의 도메인 용어가 없다.** 지금은 `{colors.risk-unanalyzed}` · `{components.badge-risk-unanalyzed}`로 적었다. 도메인 용어 표에 용어가 추가되면 그 이름으로 바꾼다 |
| K-18 | `dialog` — **미구현 · 미관측.** 관심 해제 확인 등에 필요하다. 값이 없으므로 **키를 만들지 않았다** |
| K-19 | `toast` — **미구현 · 미관측.** 뮤테이션 실패 · 알림 수신 피드백에 필요하다. 키 없음 |
| K-20 | `checkbox` — **미구현 · 미관측.** 약관 동의 · 필터 다중 선택에 필요하다. `components/ui/`에 구현 파일이 없어 옮겨올 값이 없다. 키 없음 |
| K-21 | 로딩 표시(스피너 · 스켈레톤) — 표준 어휘에 없고 구현도 없다. **프로젝트 정의 — 값 미확정** |
| K-22 | `radio` · `switch` · `textarea` · `date` · `calendar` — 미관측 · 미사용. 필요해지면 정의서에 먼저 넣는다 |
| K-23 | 차트(실거래가 · 시세)는 차기 범위다. 데이터 시각화 색은 **미사용** |
| K-24 | `{colors.border-subtle}` · `{colors.focus}` · `{colors.error}` 계열 · `{typography.label}` · `{spacing.3xs}`는 참고 사이트에서 관측되지 않았다. **기존 구현값을 유지**했고 삭제하지 않았다 |
