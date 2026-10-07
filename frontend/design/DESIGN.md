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
  primary-line-soft: "#b8c9f7" # 델타 1007 · seen on: home/1007-04 · 추정 · 1회 관측 · 채택(2026-10-07 승인) — K-36
  on-primary: "#ffffff"
  # ── 바탕 ──
  background: "#ffffff"
  surface: "#ffffff"
  surface-muted: "#f4f5f6"    # seen on: home/01 · home/1007-01 · listing/02 · map-search/03
  surface-recessed: "#fafafa" # 델타 1007 · seen on: home/02 · home/1007-01 · 02 · 추정 · 2컷 같은 구역(추천 래퍼) · 채택 · 이름 유지(2026-10-07 승인) — K-31(M-05) · K-37
  surface-sunken: "#eeeeee"   # 델타 1007 · seen on: home/03 · home/1007-04 · 추정 · 같은 페이지 같은 구역 · 채택 · 이름 유지(2026-10-07 승인, 정본 K-08 미채택을 뒤집음) — K-39(M-11)
  inverse-surface: "#1e1e1e"  # seen on: favorites/01-03 · my-info/01 · listing/05 · home/1007-01 · 03
  on-inverse: "#ffffff"
  block-feature: "#444e6a"    # 델타 1007 · seen on: home/1007-02 · 03 · 04(한 띠) · 추정 · 1회 관측 · 채택(2026-10-07 승인) — K-36
  on-block-feature: "#ffffff" # 델타 1007 · seen on: home/1007-03 · 1회 관측 · 채택 — K-36
  on-block-feature-muted: "#c5cad6" # 델타 1007 · seen on: home/1007-03 · 추정 · 1회 관측 · 채택 — K-36
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
  display-mobile: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 24px, fontWeight: 700, lineHeight: 1.3 }     # 프로젝트 정의 · 모바일(~767) 전용 — 9절
  heading-1-mobile: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 22px, fontWeight: 700, lineHeight: 1.35 }  # 프로젝트 정의 · 모바일(~767) 전용 — 9절
  section-title: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 22px, fontWeight: 400, lineHeight: 1.75 }    # 델타 1007 · seen on: home/1007-01 · 03 · 04(3곳 반복) · 추정 · 메인 섹션 제목으로 채택(2026-10-07 승인) — K-29(M-03)
  heading-2: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 20px, fontWeight: 700, lineHeight: 1.4 }
  content-title: { fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif", fontSize: 20px, fontWeight: 400, lineHeight: 1.5 }     # 델타 1007 · seen on: home/1007-04 · 추정 · 1회 관측 · 채택(2026-10-07 승인) — K-36
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
  section-tight: 56px   # 델타 1007 · seen on: home/1007-01 · 02 · 03(같은 페이지 2곳) · 측정 · 채택 · 이름 유지(2026-10-07 승인) — K-37 · K-39(M-11)
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
  checkbox:
    backgroundColor: "{colors.surface}"
    borderColor: "{colors.border-strong}"
    rounded: "{rounded.sm}"
    size: 20px
  checkbox-checked:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.sm}"
    size: 20px
  checkbox-disabled:
    backgroundColor: "{colors.disabled-surface}"
    borderColor: "{colors.border}"
    rounded: "{rounded.sm}"
    size: 20px
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
  # 아래 card-category · card-feature · card-content 셋은 델타 1007(이슈 #483) 신규 · 우리 메인이 쓰는 것만 채택(2026-10-07 승인 — K-36).
  # seen on: home/1007 · 전부 1회 관측 · 색 · 반경 · 글자는 추정, 치수는 측정(±4) — 7절. card-placeholder(+primary)는 미채택 — 11절 K-36
  # 폭은 적지 않는다 — 관측 폭(580 · 233)은 참고 사이트 1180 그리드의 컬럼 폭이라 우리 화면에서는 그리드(4절 repeat(N, 1fr))가 정한다. 참고 폭은 7절에만
  card-category:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.heading-3}"   # M-04 결정(2026-10-07): 18/700 heading-3 채택. 옛 layout.md 의 heading-2 20/700 은 보정 순환값 — K-30
    rounded: "{rounded.lg}"
  card-feature:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.none}"
    height: 411px
  card-content:
    backgroundColor: "{colors.surface-sunken}"
    textColor: "{colors.primary-hover}"   # 의도적 이탈(2026-10-07 승인) — 관측 글자 primary 는 이 면 위 3.89:1 로 AA 미달, primary-hover 4.75:1. 상단 선 · 원 버튼 외곽선은 primary 그대로 — K-33
    borderColor: "{colors.primary}"
    typography: "{typography.content-title}"
    rounded: "{rounded.none}"
    height: 330px
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
  # 아래 dialog · toast · 로딩(spinner · skeleton)과 Forms의 checkbox 셋은 참고 사이트에 없는 프로젝트 정의다. 새 색 · 새 수치 없이 기존 토큰만
  # 조합했다(사용자 결정 2026-09-19, 이슈 #150). 수치는 spacing · rounded 척도의 값이다 — 11절 K-18 ~ K-21
  dialog:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    borderColor: "{colors.border}"
    rounded: "{rounded.lg}"
    padding: 24px
  toast:
    backgroundColor: "{colors.inverse-surface}"
    textColor: "{colors.on-inverse}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: 12px 16px
  toast-error:
    backgroundColor: "{colors.error}"
    textColor: "{colors.on-error}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: 12px 16px
  spinner:
    textColor: "{colors.text-tertiary}"
    size: 24px
  skeleton:
    backgroundColor: "{colors.surface-muted}"
    rounded: "{rounded.sm}"
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
  # 아래 둘은 델타 1007(이슈 #483) 신규 — seen on: home/1007 · 1회 관측 · 채택(2026-10-07 승인 — K-36). promo-panel은 8절 Don't의 명시 예외 — K-34
  # promo-panel 폭(관측 482 = 우 레일 폭)은 그리드가 정하므로 적지 않는다 — 7절. pagination-indicator는 미채택 — 11절 K-36
  promo-panel:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.lg}"
    height: 99px
  promo-panel-tile:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.caption}"
    rounded: "{rounded.md}"
    size: 84px 80px
omitted: []
---

## 1. Overview

> **델타 1007 — 2026-10-07 · 이슈 #483 · `home` 1007 컷 4장 · 사용자 결정 반영(2026-10-07, 「권고대로」).**
> 1007 컷의 신규 토큰 · 컴포넌트를 더하고, 같은 역할에 다른 값이 나온 열한 건
> (M-01 ~ M-11)을 사용자 결정대로 정리했다. **정본의 토큰 값은 바꾸지 않았다.** 바뀐 것은 정본의 **결정 문장 2곳**
> (K-08 「콘텐츠 카드 면 `#eeeeee` 미채택」 → `{colors.surface-sunken}` 채택 · 4절 「격자 밖 관측값」의 56 →
> `{spacing.section-tight}` 채택 — K-39(M-11))과, 신규 항목 중 **우리 메인이 쓰지 않는 넷을 프론트매터에서 뺀 것**이다(K-36).
> 결정 내역은 11절 「값 충돌 — 1007 델타」와 `analysis/merge-notes.md`(「결정」 열)에 있다.
> 신규 항목은 프론트매터 주석과 본문에 `델타 1007`로 표시했다.

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
>
> **델타 1007 — 이 가정에 의문이 생겼다(K-28, M-01).** 배율을 캡처 조건으로 아는 1007 컷(브라우저 100% ·
> OS 150% · 이미지 px × 1.147 = CSS px, 겹친 컷 네 쌍으로 배율 검증)에서 컨테이너는 **1180**이다. 기존 컷이
> 약 1.127배 확대돼 있었다면(1180 × 1.127 = 1330) 위 ×1.0 결론과 그에 기댄 값이 바뀐다.
> **결정(2026-10-07): 참고 사이트 컨테이너 기준은 1180이다** — 추가 캡처 없이 1007 측정값을 기준으로 삼는다.
> 위 ×1.0 판단은 기록으로 남긴다. 그에 기댄 기존 컴포넌트 치수(입력 48 · 주 버튼 60 · 폼 카드 540 등)는 이번 결정에서
> 바꾸지 않았다 — 우리 구현값으로 이미 쓰이고 있고, 1007 컷에 같은 컴포넌트가 없어 대조할 값이 없다(K-28).

---

## 2. Colors

> Source pages: `map-search` · `favorites` · `login` · `signup` · `my-info` · `home` · `listing` · **`home/1007`**(델타)
> (`listing`은 우리가 만들지 않는 화면이고 토큰 인벤토리 계측에만 썼다. **`home`은 메인 화면(`/`)의 정본이 됐다** —
> 처음에는 푸터 구역만 계측했으나 메인을 만들면서 본문 구역까지 보강했다(이슈 #117). `home/01` · `home/02`는
> 약 1.33~1.34배 확대 캡처라 보정값이며, 푸터 정본은 `home/03`이다. **`home/1007`**은 이슈 #483의 4컷으로,
> 뷰포트 1707 · 배율 ×1.147을 캡처 조건으로 알고 잰 값이다 — 기존 보정값과의 차이는 11절 K-28 ~ K-32.)

### Brand & Accent

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.primary}` | `#326cf9` | 주 버튼 · 선택된 탭 글자와 인디케이터 · 강조 수치 · 링크 | login/01 · listing/02 · map-search/02 · home/1007-01 · 02 · 04 (값은 기존 구현값 — K-03. 1007 관측 ≈ `#3c6ff5`도 계측 추정 `#3d6ff5`와 같다) |
| `{colors.primary-hover}` | `#2a5fe0` | 주 버튼 hover · `{components.badge-primary}` 글자 · **델타 1007** `{components.card-content}` 글자(옅은 면 위 파랑 글자 — 의도적 이탈 K-33) | 미관측 · 기존 구현값 |
| `{colors.primary-surface}` | `#eaf0ff` | 옅은 강조 면 — 활성 내비 pill · 선택 칩 · `{components.badge-primary}` 배경 | map-search/05 · listing/02 |
| `{colors.primary-line-soft}` | `#b8c9f7` | **델타 1007.** primary의 옅은 단계 **선** — `{components.card-content}` 라벨 밑 짧은 밑줄(1px · 폭 ≈ 25). `{colors.primary-surface}`(면)보다 진하다. 면으로 쓰지 않는다 | home/1007-04 · **추정 · 1회 관측** |
| `{colors.on-primary}` | `#ffffff` | `{colors.primary}` 위의 글자 | 전 컷 · home/1007-01 |

### Surface

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.background}` | `#ffffff` | 페이지 바탕 | 전 컷 |
| `{colors.surface}` | `#ffffff` | 카드 · 패널 · 표 본문 · 내비 | 전 컷 |
| `{colors.surface-muted}` | `#f4f5f6` | 섹션 사이 가로 띠 · 타일 면 · `{components.alert-info}` 배경 | home/01 · listing/02 · map-search/03 · home/1007-01(hero · 카테고리 회색 면 ≈ `#f4f4f4`) |
| `{colors.surface-recessed}` | `#fafafa` | **델타 1007.** 추천 래퍼 면(+ 1px `{colors.border}`). 같은 컷의 `{colors.surface-muted}` 면보다 **눈에 띄게 밝다** — 두 면이 한 컷에 함께 있어 비교했다. 이 면 위 글자는 `{colors.text-secondary}` 이상 — 아래 Text 인용 | home/02 · home/1007-01 · 02 · **추정 · 2컷 같은 구역.** 옛 `home/02` 래퍼 표본도 `#fafafa`(같은 컷 상단 회색 띠 `#f5f5f5`) — 기존 `layout.md`의 「래퍼 = `{colors.surface-muted}`」는 옛 컷에서도 관측과 다른 **표기 오류**였다(검수 표본). 채택(2026-10-07) — K-31(M-05) · 이름 유지 K-37 |
| `{colors.surface-sunken}` | `#eeeeee` | **델타 1007.** `{components.card-content}` 면 | home/03 · home/1007-04(≈ `#efefef`) · **추정.** 2회 관측이지만 같은 페이지 같은 구역이다. 정본 K-08의 「미채택」을 뒤집어 채택(2026-10-07) — K-39(M-11) |
| `{colors.inverse-surface}` | `#1e1e1e` | 선택 반전 면 · `{components.tooltip}` · `{components.badge-inverse}` | favorites/01-03 · my-info/01 · listing/05 · home/1007-01(선택 칩) · 03(슬라이드 첫 배지) |
| `{colors.on-inverse}` | `#ffffff` | 반전 면 위의 글자 | 〃 |
| `{colors.block-feature}` | `#444e6a` | **델타 1007.** 큰 면적 장식 띠(뷰포트 전폭) — 슬라이드 구역의 면. 푸른 기가 있는 짙은 회색이고 `{colors.footer-surface}`(무채색)와 **다른 색 · 다른 역할**이다 | home/1007-02 · 03 · 04(한 띠가 세 컷에 걸침) · **추정 · 1회 관측** |
| `{colors.on-block-feature}` | `#ffffff` | **델타 1007.** `{colors.block-feature}` 위의 섹션 제목(`{typography.section-title}`). hex는 `{colors.on-inverse}`와 같으나 역할이 달라 키를 나눴다. 대비 8.26 | home/1007-03 · **1회 관측** |
| `{colors.on-block-feature-muted}` | `#c5cad6` | **델타 1007.** `{colors.block-feature}` 위의 보조 링크(「더 보기」) · 셰브런. 대비 5.03 | home/1007-03 · **추정 · 1회 관측** |
| `{colors.footer-surface}` | `#3c3c3c` | 푸터 구역 2~4 배경 | home/03 · login/01 · favorites/01 · listing/06 |
| `{colors.footer-surface-raised}` | `#4a4a4a` | 푸터 소형 버튼 · 소셜 버튼 면 | home/03 |
| `{colors.on-footer}` | `#c9c9c9` | 푸터 링크 · 본문 | 〃 |
| `{colors.footer-divider}` | `#575757` | 푸터 가로 · 세로 구분선 | home/03 |
| `{colors.disabled-surface}` | `#d8d8d8` | 비활성 주 버튼 배경 | signup/01 (1회 관측) |

### Text

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.text}` | `#222222` | 제목 · 라벨 · 주 본문 | 전 컷 · home/1007 전 컷 |
| `{colors.text-secondary}` | `#666666` | 값 텍스트 · 표 본문 · 부가 설명 | map-search/02 · my-info/01 · listing/05 · home/1007-02 |
| `{colors.text-tertiary}` | `#767676` | 보조 설명 · placeholder · 셰브런. **흰 면(`{colors.surface}`) 위에서만** — `{colors.surface-muted}` · `{colors.surface-recessed}` 위 불가 | 역할은 관측(map-search/01 · favorites/01 · home/1007-01 ~ 03), 값은 기존 구현값 — K-04 |

> `{colors.text-tertiary}`는 `{colors.surface}` 위에서 **4.54**로 AA를 넘지만 `{colors.surface-muted}` 위에서는
> **4.16**, `{colors.surface-recessed}` 위에서는 **4.35**로 미달한다. `{colors.primary}` 글자도 `{colors.surface-recessed}`
> 위에서 **4.33**으로 미달한다. 회색 띠 · 추천 래퍼 · `{components.alert-info}` 안의 본문 텍스트에는 `{colors.text-secondary}`
> (`{colors.surface-recessed}` 위 5.50)를 쓴다. placeholder는 `{components.input-placeholder}`가 `{colors.surface}` 배경을 명시하므로 통과한다.
> 린터는 정의된 쌍만 보므로 이 조합을 잡지 못한다 — 8절 Don't에도 적었다.
| `{colors.text-disabled}` | `#999999` | 비활성 컨트롤 글자 | 미관측 · 기존 구현값 |

### Border

| 토큰 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{colors.border}` | `#e5e5e5` | 기본 1px 선 — 카드 · 입력 · 탭 · 행 구분 | 전 컷 · home/1007-01 ~ 03(칩 · 빈 카드 · 슬라이드 정보부 세로선. 래퍼 보더 ≈ `#ececec`도 JPG 오차 안) |
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
1007 컷의 top-nav · 카테고리 카드 배지(계측 `accent-new`)도 같은 이유로 미채택 그대로다.

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
| 주 패밀리 | 한글 지오메트릭 산세리프 1종. 20컷 전부 동일 (측정) · 1007 4컷도 같다 |
| 참고 사이트 패밀리 이름 | **확정 불가** — Pretendard 계열로 보이나 JPG에서 확정할 수 없다 (K-05) |
| 우리 스택 | `Pretendard Variable, Pretendard, -apple-system, BlinkMacSystemFont, system-ui, Apple SD Gothic Neo, Noto Sans KR, Malgun Gothic, sans-serif` — 오픈소스 Pretendard로 대체한다 |
| 숫자 · 라틴 | 본문과 같은 패밀리로 렌더된다. 별도 숫자 폰트 없음 |
| mono | 미관측 · 미사용 |

### Hierarchy

| 역할 | size / weight / line-height | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{typography.display}` | 40 / 700 / 1.3 | 페이지 제목(중앙 정렬) | favorites/01 · my-info/01 |
| `{typography.heading-1}` | 28 / 700 / 1.35 | 폼 카드 제목 · 상세 패널 가격 | login/01 · signup/01 · map-search/05 |
| `{typography.display-mobile}` | 24 / 700 / 1.3 | 모바일(`~767px`)의 페이지 제목 — `{typography.display}` 대신 | 프로젝트 정의 · 9절 |
| `{typography.heading-1-mobile}` | 22 / 700 / 1.35 | 모바일(`~767px`)의 폼 카드 제목 — `{typography.heading-1}` 대신 | 프로젝트 정의 · 9절 |
| `{typography.section-title}` | 22 / **400** / 1.75 | **델타 1007.** 메인(`/`) 섹션 제목 — 흰 면 · `{colors.block-feature}` 띠의 인사 문구 · 띠 제목 · 콘텐츠 제목. lh는 2줄 피치 39에서 | home/1007-01 · 03 · 04 · **추정 · 페이지 안 3곳 반복**. 메인 섹션 제목으로 채택(2026-10-07) — K-29(M-03) |
| `{typography.heading-2}` | 20 / 700 / 1.4 | 섹션 제목(메인 외) · 패널 헤더. 메인 섹션 제목은 `{typography.section-title}` | map-search/02-03 · listing/05 |
| `{typography.content-title}` | 20 / **400** / 1.5 | **델타 1007.** `{components.card-content}` 제목(2~3줄, 피치 30) | home/1007-04 · **추정 · 1회 관측** · 채택(2026-10-07) — K-36 |
| `{typography.heading-3}` | 18 / 700 / 1.4 | 목록 카드 가격 · 표 행 제목 · `{components.card-category}` 제목(M-04 결정) | map-search/01 · home/02 · home/1007-01 · 02 |
| `{typography.body-lg}` | 16 / 400 / 1.6 | 폼 안내문 · 빈 상태 · 탭 라벨 | login/01 · favorites/01 |
| `{typography.body}` | 15 / 400 / 1.6 | 라벨/값 행 · 표 본문 | map-search/02 · my-info/01 · home/1007-01(top-nav 항목) |
| `{typography.body-strong}` | 15 / 700 / 1.6 | `{components.field}` 라벨 · `{components.kv-row}` 라벨 | login/01 · map-search/02 · home/1007-02(추천 래퍼 제목) |
| `{typography.body-sm}` | 13 / 400 / 1.55 | 푸터 정보 · 푸터 사이트맵 링크(M-07 결정 — 줄 피치 28) · 목록 카드 설명 · 보조 줄 | home/03 · listing/05 · home/1007-01 ~ 04 |
| `{typography.label}` | 14 / 500 / 1.5 | 폼 보조 라벨 | 미관측 · 기존 구현값 유지 |
| `{typography.link}` | 14 / 400 / 1.5 | 폼 하단 보조 링크 · 푸터 링크(사이트맵 제외) | home/03 · login/01. 사이트맵 링크는 1007-04에서 ≈ 13으로 측정돼 `{typography.body-sm}`으로 옮겼다(2026-10-07 결정 — K-32(M-07)). 이 역할의 값은 바꾸지 않았다 |
| `{typography.button}` | 16 / 700 / 1 | 주 버튼 라벨 | login/01 · listing/02 |
| `{typography.button-sm}` | 13 / 400 / 1 | 소형 버튼 라벨 | map-search/02 · home/03 |
| `{typography.eyebrow}` | 13 / 700 / 1.4 | 푸터 사이트맵 컬럼 제목 · 칩 글자 | home/03 · home/1007-01 · 04 |
| `{typography.caption}` | 12 / 400 / 1.4 | 배지 · 아이콘 라벨 · 메인 매물 카드 1줄(유형 · 단지 — M-09 결정) | map-search/03 · listing/05 · home/1007-02 · 03 · 04 |

> **위 표의 size · weight · line-height는 전부 컷에서 역산한 추정이다 (K-07).** `analysis/tokens.md` 0.3 · 2.2가
> 「폰트 크기는 글자 높이 역산, weight도 추정」으로 못박은 값이다. 반복 관측된 역할(`{typography.body}` ·
> `{typography.caption}` · `{typography.button}`)이 상대적으로 신뢰도가 높고, 1~2컷에서만 나온 역할
> (`{typography.display}` · `{typography.eyebrow}`)은 낮다. 델타 1007의 두 역할도 같다 —
> `{typography.section-title}`만 페이지 안 3곳에서 반복됐고, **400 굵기는 JPG 획 두께 비교로 읽은 추정이다.**

`heading-1` `heading-2` `heading-3` `body` `body-strong` `label` `caption` 일곱은 기존 `typography.css`의
클래스 이름과 1:1이다. 나머지 일곱은 계측에서 나온 역할이라 추가했다 — 삭제한 역할은 없다.
델타 1007에서 `section-title` · `content-title` 둘을 더했다 — 기존 역할의 값은 바꾸지 않았다. 사진 위 제목
(관측 22/700)은 사진 카드를 쓰지 않아 역할로 두지 않았다 — K-36.

### Principles

- **크기는 단계가 아니라 역할로 고른다.** 같은 15px라도 라벨이면 `{typography.body-strong}`, 값이면 `{typography.body}`다. 같은 22px라도 메인 섹션 제목이면 `{typography.section-title}`(400), 모바일 폼 카드 제목이면 `{typography.heading-1-mobile}`(700)이다.
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
| `{spacing.2xs}` | 4px | 사진 그리드 갭 · 배지 내부 상하 · 콘텐츠 카드 그리드 갭(home/1007-04) |
| `{spacing.xs}` | 8px | 칩 사이 · 푸터 소형 버튼 갭 · `{components.promo-panel-tile}` 사이 |
| `{spacing.sm}` | 12px | 배지 좌우 패딩 · 라벨↔입력 · 칩 갭 · 칩 좌우 패딩 |
| `{spacing.md}` | 16px | `{components.pulldown}` 사이 · 타일 갭 · 입력 좌우 패딩 · 카테고리 카드 갭 |
| `{spacing.lg}` | 20px | 카드 그리드 갭 · 추천 래퍼 패딩 |
| `{spacing.xl}` | 24px | 패널 좌우 패딩 · 표 셀 상하 패딩 · 칩 줄↔래퍼 |
| `{spacing.2xl}` | 32px | 폼 묶음 사이 · top-nav 항목 간격 |
| `{spacing.3xl}` | 48px | 폼 카드 제목↔구분선 · 입력↔주 버튼 · 띠 제목↔카드 |
| `{spacing.section-tight}` | 56px | **델타 1007.** 흰 면 안의 내용 블록 끝 ↔ 면 전환(카테고리 카드 하단 ↔ 회색 면 끝 · 래퍼 하단 ↔ `{colors.block-feature}` 띠). **측정 · 같은 페이지 2곳** · 채택 · 이름 유지(2026-10-07) — K-37 |
| `{spacing.4xl}` | 64px | 페이지 섹션 사이 · 면 전환 직후 ↔ 다음 제목(home/1007, 2곳) |
| `{spacing.5xl}` | 80px | 폼 카드 내부 패딩 · 띠 하단 패딩 |

격자에 맞지 않는 관측값(44 · 76 · 184)은 토큰으로 올리지 않았다. 해당 화면의 `layout.md`가 갖는다.
56은 8의 배수이고 메인에서 같은 역할(면 전환 직전)로 2곳 반복돼 `{spacing.section-tight}`로 둔다(2026-10-07 결정 — K-39(M-11)).
`section-tight`는 다른 키의 크기 이름 체계에서 벗어난다 — `3xl`과 `4xl` 사이에 들어갈 크기 이름이 없어서이고, 이 이름으로 확정했다(K-37).

### Grid & Container

| 항목 | 값 | 출처 |
| --- | --- | --- |
| **참고 사이트 컨테이너 — 기준** | **1180px** (뷰포트 1707, `home/1007` 4컷 전 구역이 같은 좌우 끝) | **델타 1007 · 측정** · 기준으로 채택(2026-10-07 결정 — K-28(M-01)) |
| 참고 사이트 컨테이너 — 옛 컷 | 1330px (뷰포트 2518~2559의 6컷, ×1.0 배율 가정) | 계측 · 기록으로 남김. 뷰포트 반응인지 배율 가정 오류인지는 가리지 않았다(추가 캡처 없음 — K-28) |
| **우리 컨테이너 최대 폭** | **1200px** | 프로젝트 정의 (`input.md` 4장 · 2026-09-18 승인) — 이번 결정으로 바뀌지 않는다 |
| 4컬럼 그리드 | 컬럼 **285px** · gap **20px** (`{spacing.lg}`) | **환산값** |
| 유동 아키타입 | 지도 탐색만 컨테이너 없이 뷰포트 전폭 | 계측 |

> **참고 사이트의 1180 컨테이너 · 4열 컬럼 280 · gap 20((1180 − 60) ÷ 4)을 우리 1200 컨테이너에 맞춰 컬럼 285 · gap 20을
> 채택했다.** 4×285 + 3×20 = 1200. 1200 ÷ 1180 ≈ 1.017배라 280 → 284.7 ≈ 285, gap 20 → 20.3 ≈ 20으로 같은 값이 나온다.
> (정본은 옛 기준 1330 · 컬럼 318 · gap 20에서 같은 285 · 20을 얻었다 — 기준이 바뀌어도 채택값은 그대로다.)
>
> **285는 좌우 패딩 0을 전제한 값이다.** 실제 컨테이너는 좌우 패딩 24를 갖는다(9절 · `examples/home/layout.md`).
> 그 안에서 같은 4열을 잡으면 (1200 − 48 − 60) ÷ 4 = **273**이다. **코드는 px를 적지 않고
> `repeat(4, 1fr)` + `gap: {spacing.lg}`로 잡는다** — 컨테이너 폭이 바뀌면 컬럼이 따라간다. 285와 273은
> 같은 그리드를 기준점만 달리 읽은 것이고, 둘 중 하나가 틀린 것이 아니다.
>
> **델타 1007.** 1007 컷의 추천 그리드는 컨테이너 1180 안 래퍼 패딩 20을 빼고 **4열 · 컬럼 270 · gap 20**
> (4×270 + 3×20 = 1140)이다. gap `{spacing.lg}`와 `repeat(4, 1fr)` 방식은 그대로 맞는다. 환산의 분모는
> 1180으로 정했고(K-28) **우리 1200은 바뀌지 않는다.**

### Whitespace

리듬은 「폼 안은 `{spacing.3xl}`, 섹션 사이는 `{spacing.4xl}`, 카드 안쪽은 `{spacing.5xl}`」 세 박자다.
메인은 「면 전환 직후 `{spacing.4xl}`, 면 전환 직전 `{spacing.section-tight}`」 두 박자다(델타 1007).
섹션 순서 · 그리드 배치 · 구간별 여백은 `examples/<아키타입>/layout.md`가 갖는다. 정의서는 다시 쓰지 않는다.

---

## 5. Elevation & Depth

| 레벨 | 값 | 쓰임 | seen on |
| --- | --- | --- | --- |
| 0 | 그림자 없음. 1px `{colors.border}`로만 면을 나눈다 | 카드 · 폼 · 표 · 탭 — **기본값** | login/01 · favorites/01 · my-info/01 · listing/05 · home/1007-03 · 04(`{components.card-feature}` · `{components.card-content}` 그림자 없음) |
| 1 | `0 2px 10px rgba(0,0,0,0.06)` (추정) | 떠 있는 카드 · 검색 바 | home/01 (줌 보정). home/1007-01은 축소 JPG라 **판별 불가** — 일치도 충돌도 아님 |
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
| `{rounded.none}` | 0 | `{components.card-form}` · `{components.input}` · `{components.tabs-segmented}` · 표 · `{components.card-feature}` · `{components.card-content}` |
| `{rounded.sm}` | 4px | `{components.button-primary}` · 배지 · 태그 칩 · 썸네일 |
| `{rounded.md}` | 8px | `{components.select}` · `{components.popover}` · `{components.disclosure}` · 요약 카드 · `{components.promo-panel-tile}` · 메인 추천 래퍼(M-05 결정) · 메인 매물 썸네일(M-06 — 기존 유지) |
| `{rounded.lg}` | 12px | `{components.card}` · `{components.card-category}`(≈ 10, 8~12) · `{components.promo-panel}` · 큰 배너 |
| `{rounded.pill}` | 9999px | `{components.pulldown}` · `{components.tabs-pill}` · 원형 버튼(정사각에 적용하면 원) · 검색 바 |

폼 입력의 반경은 계측에서 0~2px로 읽혔고 `{rounded.none}`으로 확정했다 (추정 — K-07).
델타 1007의 반경은 전부 축소 JPG 경계 추정이다 — 매물 썸네일(8 vs ≈ 4)은 축소 컷으로 가를 수 없어 기존 8을 유지했다(2026-10-07 결정 — K-30(M-06)).

### Border Width

| 값 | 쓰임 |
| --- | --- |
| 1px | 기본. 카드 · 입력 · 탭 · 행 구분선 · 푸터 구분선 · `{components.card-feature}` 정보부 세로선 · `{components.card-content}` 라벨 밑줄(`{colors.primary-line-soft}`) |
| 2px | 강조. 표 헤더 상단(`{colors.border-strong}`) · 선택된 `{components.tabs-underline-selected}` 하단 인디케이터(`{colors.primary}`) · `{components.card-content}` 상단 선(2~3px, ±1) — home/1007-04 |

### Icon

| 크기 | 쓰임 |
| --- | --- |
| 16px | 본문 아이콘 · 셰브런 |
| 18px | **델타 1007.** 섹션 제목 끝 정보 아이콘 — home/1007-01 · **1회 관측** |
| 20px | 푸터 검색 아이콘 · hero 검색 바 아이콘(home/1007-01) |
| 24px | 액션 아이콘 · 입력 접미 아이콘 · 찜(home/1007-02) · `{components.promo-panel-tile}` 아이콘 |
| 28px | **델타 1007.** `{components.card-content}` 좌하단 원형 외곽선 버튼(1px `{colors.primary}`) — home/1007-04 · **1회 관측** |
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
| `{components.checkbox}` | `{colors.surface}` + 1px `{colors.border-strong}` · `{rounded.sm}` · 20(`{spacing.lg}`) | **프로젝트 정의** — K-20. 다중 선택 · 동의 |
| `{components.checkbox-checked}` | `{colors.primary}` 면 / `{colors.on-primary}` 체크 표시 | 선택됨 |
| `{components.checkbox-disabled}` | `{colors.disabled-surface}` + 1px `{colors.border}` | 비활성 |

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
| `{components.tabs-pill}` (+`-selected`) | h30 · `{rounded.pill}` | 목록 상단 전환 · 메인 해시태그 칩 줄(6개 · h32, 오차 안) | map-search/05 · home/1007-01 · 02 |
| `{components.popover}` | `{colors.surface}` + 1px `{colors.border}` · `{rounded.md}` | 지도 위 컨트롤 면 | map-search/01 · map-search/05 |
| `{components.disclosure}` | `{colors.surface}` + 1px `{colors.border-subtle}` · `{rounded.md}` · 트리거 패딩 12/16 | **프로젝트 정의** — 상세 패널의 건축물대장 · 등기 이력 접기 | project |

`{components.disclosure}`는 규격의 표준 어휘에 없다. 우리 상세 패널이 이미 쓰고 있어 프로젝트 정의
컴포넌트로 넣었고 값은 기존 구현(`Disclosure.module.css`)에서 옮겼다.

#### 델타 1007 — 카드 변형 셋 (`card-*`)

**전부 `seen on: home/1007` · 1회 관측이다.** 치수는 측정(±4 CSS px), 색 · 반경 · 글자는 추정이다.
`card`의 변형으로 이름을 붙였다(`card-form` · `card-summary`와 같은 방식). 사진 · 일러스트 · 문구 · 아이콘 형태는 적지 않고 **자리**만 적는다.
**우리 메인이 쓰는 셋만 채택했다(2026-10-07 결정 — K-36).** 같은 컷의 빈 칸 · 안내 카드는 미채택이라 여기 없다 — 11절 K-36.

> **「참고 폭」은 관측 기록일 뿐 컴포넌트의 값이 아니다.** 580 · 233은 참고 사이트 1180 컨테이너 안의 컬럼 폭이고
> (2열 · 5열), 우리 컨테이너는 1200이다. 폭은 4절의 `repeat(N, 1fr)` + gap 그리드가 정한다 — 그래서
> 프론트매터에는 높이만 두었다. `{components.card-form}` 540처럼 그리드와 무관한 고정 폭은 해당하지 않는다.

| 토큰 | 매핑 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{components.card-category}` | `{colors.surface}` / `{colors.text}` · 제목 `{typography.heading-3}` · 설명 2~3줄 `{typography.body-sm}` `{colors.text-tertiary}`(줄 피치 24) · `{rounded.lg}`(≈ 10) · 좌 패딩 `{spacing.lg}` · 높이 1행 175 / 2행 170(고정) · 우상단 배지 자리 · 우하단 ≈ 44 일러스트 자리 | 메인 hero 아래 카테고리 진입 카드(1행 2장 · 2행 3장 — 배치는 `layout.md`) | home/1007-01 · **1회 관측.** 제목 크기 18/700 `{typography.heading-3}` 채택(2026-10-07 결정 — K-30(M-04)). 옛 `layout.md`의 20/700은 보정 순환값 |
| `{components.card-feature}` | 높이 411(참고 폭 580 = 2열 컬럼) · `{rounded.none}` · 그림자 없음. 이미지부 높이 299 — 관측에서는 사진 자리지만 **우리 화면은 사진을 쓰지 않는다**(K-35). 이 자리에 무엇을 둘지는 화면 작업이 정한다. 좌상단 윗변에 붙은 배지는 `{components.badge-inverse}` h31 하나만 쓴다 — 관측의 둘째 배지(사진 위 반투명 면)와 사진 위 흰 글자 제목(관측 22/700)은 쓰지 않는다(K-35 · K-36). 정보부 580 × 111 `{colors.surface}` · 4열(피치 ≈ 145) · 열 사이 1px `{colors.border}` 세로선(위아래 ≈ 24 들임) · 라벨 `{typography.caption}` `{colors.text-tertiary}` / 값 `{typography.body-sm}` `{colors.text}`(피치 24) · 패딩 좌 ≈ 20 · 위아래 ≈ 24 | `{colors.block-feature}` 띠 안의 슬라이드 카드. 참고 폭 580 = (1180 − 20) ÷ 2라 「2장 보기 · 갭 20」으로 읽히나 둘째 칸은 미관측. 슬라이드 위치 표시는 쓰지 않는다(K-36) | home/1007-03 · **1회 관측** · 채택(2026-10-07) |
| `{components.card-content}` | `{colors.surface-sunken}` 면 · 상단 2~3px `{colors.primary}` 선 · `{rounded.none}` · 높이 330 고정(참고 폭 233 = 5열 컬럼) · 패딩 좌 ≈ 30 · 위 ≈ 40 · 아래 ≈ 30 · 라벨 ≈ 12 `{colors.primary-hover}` + 1px `{colors.primary-line-soft}` 밑줄(폭 ≈ 25) · 제목 `{typography.content-title}` `{colors.primary-hover}` · 좌하단 28 원형 외곽선 버튼(`{colors.primary}`) | 메인 추천 콘텐츠 5열(갭 ≈ `{spacing.2xs}`) | home/1007-04 (기존 `home/03`은 5열 · 면 · 상단 선만) · **1회 관측.** 채택(2026-10-07). 글자 `{colors.primary-hover}` on `{colors.surface-sunken}` = **4.75 ✓ — 의도적 이탈 K-33(승인)**(관측 글자 `{colors.primary}`는 3.89로 AA 미달). 선 · 외곽선은 글자가 아니라 `{colors.primary}` 그대로(비텍스트 3:1 기준 3.89 ✓) |

### Feedback

| 토큰 | 매핑 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{components.alert-info}` | `{colors.surface-muted}` / `{colors.text-secondary}` + 1px `{colors.border-subtle}` · `{rounded.md}` · 패딩 `{spacing.md}` | 조회 실패 · 빈 상태 안내 | **project** |
| `{components.alert-error}` | `{colors.error-surface}` / `{colors.error}` · `{rounded.md}` · 패딩 `{spacing.md}` | 인라인 오류 | **project** |
| `{components.tooltip}` | `{colors.inverse-surface}` / `{colors.on-inverse}` · `{typography.body-sm}` · 패딩 8/14 | 용어 설명 | map-search/02 · map-search/04 |
| `{components.dialog}` | `{colors.surface}` / `{colors.text}` + 1px `{colors.border}` · `{rounded.lg}` · 패딩 `{spacing.xl}` | 확인이 필요한 조작(관심 해제 등) | **project** — K-18 |
| `{components.toast}` | `{colors.inverse-surface}` / `{colors.on-inverse}` · `{typography.body}` · `{rounded.md}` · 패딩 `{spacing.sm}`/`{spacing.md}` | 조작 결과 · 알림 수신 피드백 | **project** — K-19 |
| `{components.toast-error}` | `{colors.error}` / `{colors.on-error}` · 나머지는 toast와 같다 | 뮤테이션 실패 | **project** — K-19 |
| `{components.spinner}` | `{colors.text-tertiary}` · 24(`{spacing.xl}`) | 짧은 대기(버튼 · 패널 안) | **project** — K-21 |
| `{components.skeleton}` | `{colors.surface-muted}` 면 · `{rounded.sm}` | 목록 · 카드 자리 표시 | **project** — K-21 |
| `{components.badge-neutral}` | `{colors.surface-muted}` / `{colors.text-secondary}` | 중립 표시 | map-search/01 |
| `{components.badge-primary}` | `{colors.primary-surface}` / `{colors.primary-hover}` | 강조 표시 | project |
| `{components.badge-inverse}` | `{colors.inverse-surface}` / `{colors.on-inverse}` | 상태 표시(마감 등) | listing/05 · home/1007-03(h31, 오차 안) |
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
`analysis/tokens.md` 8.12다. 델타 1007의 둘은 아래 별도 표에 있다(출처 `analysis/tokens.md` 13.1).

| 토큰 | 매핑 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{components.top-nav}` | `{colors.surface}` / `{colors.text}` · `{typography.body}` · 하단 1px `{colors.border}` · h80 | 전 페이지 상단 내비. 활성 항목은 `{colors.primary}` 글자 + `{colors.primary-surface}` pill. 로그인 상태에서 우측이 아바타(36px) + 닉네임으로 바뀐다 | signup/01 · favorites/01-02 · my-info/01 · listing/02 · home/1007-01(h78 · 좌 인셋 20 — 오차 안. 아바타 ≈ 30은 1회 관측. 하단 선은 아래 회색 면과 겹쳐 판별 불가) |
| `{components.site-footer}` | `{colors.footer-surface}` / `{colors.on-footer}` · `{typography.body-sm}` · 구분선 `{colors.footer-divider}` | **`map-search`를 제외한** 전 페이지 하단 — 그 아키타입은 뷰포트의 남은 높이를 전부 쓰는 유동 구조라 푸터를 붙이면 지도가 줄어든다(4절 Grid & Container의 예외와 같은 이유). 참고 사이트도 지도 화면 5컷 모두에 푸터가 없다. 구역 1은 흰 면, 구역 2~4가 어두운 면이다. 참고 사이트의 내부 버튼은 `{components.button-footer}` · `{components.button-footer-primary}` · `{components.button-social}`이다(home/03) — **우리 푸터는 내부 버튼을 쓰지 않는다**(이슈 487 — 외부 링크를 두지 않아 저장소 버튼이 빠졌고 소형 버튼 줄 · 소셜 버튼은 만들지 않았다). 세 키는 관측 기록으로 남는다. 구역 배치는 `examples/home/layout.md`가 갖는다 | home/03 · login/01 · signup/01 · favorites/01-03 · listing/06 · home/1007-04(구역 1 구조 — 6열 · 굵은 제목). **구역 1 사이트맵(2026-10-07 결정 — K-32(M-07)):** 컬럼 피치 190(첫 컬럼 인셋 20) · 링크 줄 피치 28 · 링크 `{typography.body-sm}`(≈ 13)는 1007 값. 링크 색은 `{colors.text-secondary}` 유지(관측 `#999` 근처 — 의도적 이탈). 앞 섹션 ↔ 사이트맵 `{spacing.4xl}` · 사이트맵 ↔ 어두운 면 ≈ 48은 기존 유지 |
| `{components.info-row}` | `{colors.surface}` / 라벨 `{typography.body-strong}` `{colors.text}` · 값 `{colors.text-secondary}` · 우측 끝 셰브런 16px · h76 · 1px `{colors.border}` | 설정·프로필의 「라벨 — 값 — >」 행. 누르면 편집으로 들어간다 | my-info/01 |
| `{components.kv-row}` | `{colors.surface}` / `{colors.text-secondary}` · `{typography.body}` · 행마다 하단 1px `{colors.border}` · h66 · 라벨 열 150px | 상세 패널 판정 근거 · 등기·건축물대장 값 표시. 라벨은 `{typography.body-strong}` `{colors.text}`, 강조 수치는 `{colors.primary}` | map-search/02 · map-search/03 |
| `{components.empty-state}` | `{colors.text}` · `{typography.body-lg}` · 줄 간격 29px | 관심 매물 · 알림의 빈 목록. 2줄 중앙 정렬 — 1줄은 `{colors.text}`, 2줄 보조 설명은 `{colors.text-secondary}`(계측 `ink-subtle`은 흰 면 위 2.68로 미채택 — K-04). 상단 여백 184px는 격자 밖이라 `examples/favorites/layout.md`가 갖는다 | favorites/01-03 |
| `{components.action-bar}` | `{colors.surface}` · 상단 1px `{colors.border}` · h92 · 패딩 `{spacing.md}` | 상세 패널 하단 고정 바. `{components.button-icon}` 2개 + `{components.button-primary}`(나머지 폭 전부), 갭 `{spacing.xs}`. 패널 스크롤과 무관하게 같은 위치 | map-search/02~05 |

- **`{components.info-row}`의 보더는 행 사이가 아니라 목록 마지막 행 아래에만 있다.** 계측(`my-info/01`)은
  행 사이 구분선이 없다. 프론트매터의 `borderColor` 하나만 보면 매 행에 선이 있는 것으로 읽히므로 여기
  적는다. 행마다 선이 있는 것은 `{components.kv-row}` 쪽이다.
- `type-rail` · `detail-panel` · `sub-nav` · `option-tile-grid` · `photo-grid`도 관측됐지만 **토큰이 아니라
  배치**라서 각 `layout.md`가 정본이다 (K-08).

#### 델타 1007 — 고유 컴포넌트 둘

**전부 `seen on: home/1007` · 1회 관측 · 채택(2026-10-07 결정 — K-36).** 치수는 측정, 색 · 글자는 추정이다.
같은 컷의 슬라이드 위치 표시는 미채택이라 여기 없다 — 11절 K-36.

| 토큰 | 매핑 | 쓰임 | seen on |
| --- | --- | --- | --- |
| `{components.promo-panel}` | `{colors.primary}` 면 / `{colors.on-primary}` · 높이 99(참고 폭 482 = 우 레일 폭 — 그리드가 정한다) · `{rounded.lg}`(≈ 12) · 좌 패딩 ≈ 20 · 좌측 제목(≈ 17/700 — 역할 없음, `{typography.heading-3}` 근사) + 부제 `{typography.body-sm}` · 우측 `{components.promo-panel-tile}` 2개(갭 `{spacing.xs}`, 우 인셋 ≈ 12) | 메인 우 레일의 상담 입구(바로가기 상자) **한 곳**. 장식 원호는 기록하지 않는다 | home/1007-01 · **1회 관측.** `{colors.primary}`를 큰 면적에 쓴다 — **8절 Don't의 명시 예외**(2026-10-07 결정 — K-34). 다른 자리에 같은 면을 쓰지 않는다 |
| `{components.promo-panel-tile}` | `{colors.surface}` / `{colors.text}` · 84 × 80 · `{rounded.md}` · 아이콘(24) 위 + 라벨(≈ 12/700 — `{typography.caption}` 근사, 굵기는 다르다) 아래 | `{components.promo-panel}` 안 흰 타일 버튼 | home/1007-01 · **1회 관측** |

> 칩 줄의 **선택 칩 ↔ 래퍼 연결 꼭지**(래퍼 윗변의 ≈ 12 삼각형)는 `{components.tabs-pill-selected}`의 1회 관측
> 장식으로 기록만 한다 — 컴포넌트로 세지 않았다. 추천 래퍼 · `{colors.block-feature}` 띠 · hero 검색 바는 **배치**라
> `examples/home/layout.md`가 정본이다.

### 상태 · 전환 · 레이어

| 항목 | 내용 |
| --- | --- |
| `-hover` | **전부 미관측** (K-11). `{components.button-primary-hover}`만 기존 구현값을 유지하고, 나머지는 면을 `{colors.surface-muted}`로 낮추는 규칙을 따른다. 1007 컷도 hover 없음(K-38) |
| `-focus` | 미관측. `{spacing.3xs}` 두께 `{colors.focus}` 실선 링 + 같은 값 offset. 키보드 포커스에서만(`:focus-visible`) |
| `-selected` | 관측됨. 두 방식뿐이다 — 반전(`{components.tabs-segmented-selected}`) 또는 `{colors.primary}` 인디케이터(`{components.tabs-underline-selected}`). 1007의 칩 줄도 반전(`{components.tabs-pill-selected}`)이다 |
| `-disabled` | 관측된 것은 주 버튼 하나. 8절 참조 |
| 전환 | 미관측. 색 전환 150ms ease, 회전 120ms ease를 넘지 않는다 (기존 구현값) |
| z-index | 미관측. 육안 순서는 콘텐츠 < 지도 컨트롤 < 상세 패널 < `{components.tooltip}` < `{components.toast}` < 모달. 값은 미확정 — 단 toast 는 **100**으로 정했다(K-13 · K-19) |

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
  **예외는 하나다** — 메인 우 레일의 상담 입구 `{components.promo-panel}` 한 곳(2026-10-07 결정 — K-34). 다른 화면 · 다른 자리로 넓히지 않는다.
- **등급 색을 배지 밖에서 쓰지 않는다.** 글자색 · 보더 색으로 돌려 쓰면 대비 보장이 깨진다.
- **`{colors.text-tertiary}`를 흰 면 밖에서 본문 텍스트로 쓰지 않는다.** `{colors.surface}` 위 4.54는 통과하지만
  `{colors.surface-muted}` 띠나 `{components.alert-info}` 위에서는 4.16, `{colors.surface-recessed}` 추천 래퍼 위에서는
  4.35로 미달한다. 그 자리에는 `{colors.text-secondary}`를 쓴다. 린터는 정의된 쌍만 보므로 이 조합을 잡아주지 않는다.
- **`{colors.primary}`를 `{colors.surface-recessed}` 위의 글자로 쓰지 않는다.** 4.33으로 미달한다(델타 1007). 같은 이유로
  `{colors.surface-sunken}` 위 파랑 글자는 `{colors.primary-hover}`다 — `{components.card-content}` · K-33.
- **`{colors.block-feature}` 위에 일반 글자색을 쓰지 않는다.** 그 띠 위의 글자는 `{colors.on-block-feature}` ·
  `{colors.on-block-feature-muted}` 둘뿐이다(델타 1007).
- **비활성 상태를 opacity로 만들지 않는다.** 배경색과 글자색을 바꾼다 (계측도 opacity 흔적이 없다).
- **그림자를 깊이 표현의 기본으로 쓰지 않는다.**
- **섹션 순서 · 그리드 · 여백 리듬을 이 문서에 다시 적지 않는다.** 정본은 `layout.md`다.

---

## 9. Responsive Behavior

**이 절은 계측값이 아니다.** 20컷 전부 뷰포트 2518~2559px의 데스크톱이고 모바일 · 태블릿 컷이 없다.
델타 1007의 4컷도 뷰포트 1707 한 폭의 데스크톱이다(K-38).
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
  델타 1007의 `{components.card-content}` 원형 버튼(28)도 같은 규칙을 따른다.

### Typography

모바일(`~767px`)에서 두 제목 역할만 작은 역할로 바꾼다. 나머지 역할은 구간과 무관하게 같다.

| 데스크톱 · 태블릿 | 모바일 | 제안한 곳 |
| --- | --- | --- |
| `{typography.display}` 40 | `{typography.display-mobile}` 24 | `examples/favorites/layout.md` · `examples/my-info/layout.md` 「페이지 제목 40 → 24」 |
| `{typography.heading-1}` 28 | `{typography.heading-1-mobile}` 22 | `examples/login/layout.md` 「카드 제목 28 → 22」 |

- **값은 레이아웃 맵의 반응형 제안(미관측)을 옮긴 프로젝트 정의다.** 모바일 컷이 없으므로 계측값이 아니다. weight · line-height는 원 역할과 같게 둔다.
- 역할을 새로 두는 이유: 컴포넌트 CSS가 `font-size`를 직접 적지 않는다는 규칙 때문에, 크기를 바꾸려면 바꿀 대상 역할이 정의서에 있어야 한다.
- 화면 적용(`typography.css` 이관과 미디어 쿼리)은 이슈 #130 범위 밖이었고 이슈 497 에서 구현했다 — K-27.
- 델타 1007의 `{typography.section-title}` · `{typography.content-title}`은 모바일 역할을 두지 않았다 — 모바일 컷이 없다(K-38).

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

**실측 error 0 · warning 46 · info 1**(`@google/design.md` 0.4.0, 사용자 결정 반영 뒤 · 2026-10-07).
남는 warning은 전부 K-25 · K-01 · K-02 · K-15로 설명되며,
**error는 0건이다** — 규격 밖 하위 토큰도 값은 전부 정의된 토큰을 가리키므로 실제로 깨진 참조는 없다.

| 규칙 | 실측 | 어디 |
| --- | --- | --- |
| `broken-ref` | **39** | 규격 밖 component 하위 토큰 (K-25). #150에서 dialog · checkbox · checkbox-disabled의 `borderColor` 3건, 델타 1007에서 `card-feature` · `card-content`의 `borderColor` 2건 추가(미채택 `card-placeholder` 둘의 2건은 빠진다) |
| `contrast-ratio` | **2** | `{components.button-primary-disabled}` · `{components.input-disabled}` (K-01 · K-02) |
| `orphaned-tokens` | **5** | `{colors.primary-line-soft}` · `{colors.surface-recessed}` · `{colors.block-feature}` · `{colors.on-block-feature}` · `{colors.on-block-feature-muted}` — 배치 · 장식 전용 색이라 유지, 경고 감수 (K-15). 뒤의 둘은 미채택 슬라이드 위치 표시가 빠지면서 참조가 없어진 것이다 |
| `token-summary` (info) | 1 | 색 36 · 타이포 18 · 반경 5 · 간격 12 · 컴포넌트 65 |
| `missing-primary` · `section-order` · `unknown-key` · `token-like-ignored` · `missing-sections` · `missing-typography` | **0** | — |

채택한 신규 쌍의 대비 — `promo-panel` 4.52 · `card-content` 4.75 · 띠 제목 `{colors.on-block-feature}` on `{colors.block-feature}` 8.26(산문 쌍).

| # | 항목 | 내용 |
| --- | --- | --- |
| K-25 | **규격 밖 component 하위 토큰 — `broken-ref` 39건** | 규격이 인정하는 하위 토큰은 `backgroundColor` · `textColor` · `typography` · `rounded` · `padding` · `size` · `height` · `width` **8개뿐**이고, 여기에 **보더와 간격이 없다.** 내역: `borderColor` **31** · `gap` **3**(`field` · `tabs-underline` · `empty-state`) · `shadow` **1**(`input-focus`) · `minWidth` **1**(`select`) · `labelWidth` **1**(`kv-row`). **유지하기로 결정했다 — 이슈 #110 작업 판단(2026-09-18), 검수 권장 수용. 사용자 승인 사항이 아니다.** 사유 — **이 시스템은 그림자 대신 1px 실선으로 면을 나누므로 보더 색은 부가 정보가 아니라 핵심 표현 수단이고, 따라서 산문이 아니라 기계가 읽는 자리에 남긴다.** 어느 컴포넌트가 `{colors.border}`를 쓰고 어느 것이 `{colors.border-subtle}`를 쓰는지가 `components`에서 사라지면 CSS로 옮길 때 정보를 잃는다. **값은 전부 `{colors.*}` 참조라 실제로 깨진 참조가 아니며 severity도 warning이다**(error 0건). 대가로 린터 경고 37건을 안고 간다. **델타 1007에서 같은 방침으로 `borderColor` 2건을 더했다** |
| K-01 | **비활성 버튼 대비 — `contrast-ratio` 1건** | 계측(`signup/01`)은 `{colors.disabled-surface}` + 흰 글자로 대비 ≈1.4:1이다. 우리는 글자를 `{colors.text-disabled}`(#999999)로 바꿔 **2.00:1**을 확보했다. 그래도 4.5:1 미만이라 경고가 난다. WCAG 2.2 SC 1.4.3은 **비활성 사용자 인터페이스 구성요소의 텍스트를 대비 요건에서 제외**하므로 위반은 아니다(SC 1.4.11도 같다). **검수에서 예외 적용을 승인했다.** **참고 사이트를 그대로 베끼지 않았다는 사실을 함께 기록한다** |
| K-02 | **`{components.input-disabled}` 대비 — `contrast-ratio` 1건** | `{colors.surface-muted}` + `{colors.text-disabled}` = **2.61:1**. 같은 비활성 예외이고 **검수에서 승인됐다.** 기존 구현값 유지 |
| K-15 | **컴포넌트가 참조하지 않는 색 토큰 — `orphaned-tokens` 5건(델타 1007)** | **린터가 보는 것**(검수가 린터 소스로 확인): `components`가 참조하는 경로와 같은 계열 이름(`on-` · `inverse-` 앞붙이, `-container` · `-variant` 등 뒷붙이를 뗀 이름)뿐이고 **본문 산문은 세지 않는다.** MD3 표준 계열(`primary` · `secondary` · `tertiary` · `error` · `surface` · `background` · `outline`)은 면제된다.<br>**정본 몫 — 경고 없음.** `{colors.background}`(`global.css`이 `background: var(--color-background)`로 실제 사용 중 — 지우면 전역 배경이 깨진다)는 린터의 MD3 표준 계열 면제(`background`)로 경고가 나지 않는다 — 산문 참조와 무관하다. `{colors.on-error}`도 `error` 계열 면제 대상이며, #150부터는 `{components.toast-error}`가 직접 참조한다. 경고가 없더라도 **삭제 후보가 아니라는 사실**을 남긴다.<br>**델타 1007 몫 — 유지, 경고 5건 감수.** 다섯 다 컴포넌트가 아니라 **배치 · 장식 전용 색**이라 참조할 컴포넌트 자리가 없다. 참조를 만들려고 컴포넌트를 새로 지어내지 않는다(K-25와 같은 판단 형식). (1) `{colors.surface-recessed}` — 추천 래퍼 면. 래퍼는 배치라 `examples/home/layout.md` 소관이다. 계열 이름이 `surface-recessed`라 `surface` 면제를 받지 못한다(이름 유지 결정 — K-37). (2) `{colors.primary-line-soft}` — `{components.card-content}` 라벨 밑 1px 밑줄. 규격 하위 토큰에 둘째 선 색 자리가 없다. (3) `{colors.block-feature}` — 슬라이드 구역의 전폭 띠 면. 띠는 배치다. (4) `{colors.on-block-feature}` — 띠 위 섹션 제목 글자. 제목은 타이포 역할이지 컴포넌트가 아니다. (3) · (4)는 미채택 슬라이드 위치 표시가 유일한 참조였다(K-36). (5) `{colors.on-block-feature-muted}` — 띠 위 「더 보기」 링크 · 셰브런. 해당 컴포넌트가 없다(띠는 배치) |

### 값 충돌 — 승인 대상

| # | 항목 | 내용 |
| --- | --- | --- |
| K-26 | **9절 터치 타겟 44 ↔ `{components.button-footer}` · `{components.button-footer-primary}`의 `height: 32px` 충돌** | 둘 다 버릴 수 없어 **입력 수단으로 갈랐다** — 터치 구간(`~767px`)은 44, 포인터 구간(`768px~`)은 컴포넌트 지정 높이. 규칙은 9절 Touch Targets에 있다. 근거는 44가 WCAG 2.2 SC 2.5.5(**AAA**)이고 AA 기준 SC 2.5.8은 **24×24**라는 것이다 — 32는 포인터 구간에서 AA를 넘는다. 이 충돌은 정의서 안에 원래 있었고 구현이 만든 것이 아니다 (이슈 #112 검토에서 드러남) |
| K-03 | `{colors.primary}` 값 | 계측 추정은 `#3d6ff5`이고 이 값은 `{colors.on-primary}`와 **4.38:1**로 AA에 못 미친다. hex는 JPG 압축 기반 「추정」이므로 기존 구현값 `#326cf9`(4.52)를 유지했다. 계측값을 쓰려면 사용자 승인이 필요하다. 1007 관측(≈ `#3c6ff5`)도 계측 추정과 같다 — 새 충돌이 아니다 |
| K-04 | `{colors.text-tertiary}` 값 | 계측 `#9e9e9e`는 흰 바탕 대비 약 2.6:1로 본문 텍스트에 쓸 수 없다. 기존 `#767676`(4.54)을 유지했다. placeholder 계측값 `#aaaaaa`도 같은 이유로 미채택 |
| K-10 | `{components.card}` 반경 | 기존 구현은 `{rounded.lg}`(12) + `{colors.border-subtle}`, 계측 `card-summary`는 `{rounded.md}`(8) + `{colors.border}`다. 덮어쓰지 않고 **두 항목으로 분리**했다. 하나로 합칠지는 승인 대상 |
| K-14 | 폼 입력↔주 버튼 간격 | `login/01`은 34, `signup/01`은 60으로 같은 역할에 값이 달랐다. **48(`{spacing.3xl}`)로 확정**했다(계측자 권장 수용). 충돌이 있었다는 사실을 남긴다 |

### 값 충돌 — 1007 델타 (이슈 #483 · 2026-10-07 사용자 결정)

**사용자가 권고대로 결정했다(진행표 5단계, 2026-10-07).** 정본의 토큰 값은 바뀌지 않았다. 정본의 **결정 문장 2곳**(K-08 「콘텐츠 카드 면
`#eeeeee` 미채택」 · 4절 「격자 밖 관측값」의 56)은 결정에 따라 바뀐다 — K-39(M-11). 값 · 근거의 본문은 `analysis/merge-notes.md`
(「권고 · 결정」 표)에 있고, 여기는 정의서에서 걸리는 자리와 결정만 적는다. 근거의 공통 전제 — **1007 컷은 브라우저 확대 100%에서
찍었고 배율(×1.147)을 겹친 컷 네 쌍으로 검증했다. 기존 `home/01` · `home/02` 값은 「컨테이너 = 1330」에 되맞춘 줌 보정 추정이다.**

| # | merge-notes | 정의서에서 걸리는 자리 | 결정 |
| --- | --- | --- | --- |
| K-28 | M-01 컨테이너 1330 vs 1180 · M-10 | 1절 「픽셀 배율 ×1.0」 · 4절 Grid & Container의 환산(컬럼 285) | **참고 사이트 컨테이너 기준 1180** — 추가 캡처 없이. 우리 1200 · `repeat(4, 1fr)` · gap `{spacing.lg}` · 컬럼 285는 그대로. **남는 것:** (a) 뷰포트 반응 / (b) 옛 컷 배율 가정 오류 중 어느 쪽인지는 가리지 않았다 — 옛 ×1.0 가정에 기댄 컴포넌트 치수(입력 48 · 주 버튼 60 · 폼 카드 540 등)는 우리 구현값으로 그대로 둔다 — 1007 컷에 같은 컴포넌트가 없어 대조할 값이 없다. 가르려면 넓은 뷰포트(≈ 2520) 배율 기록 컷 1장이 필요하다. M-10 오차 경계 4건은 조치 없음 |
| K-29 | M-03 섹션 제목 20/700 vs ≈ 22/400 | 3절 Hierarchy | **신규 채택** — 메인 섹션 제목은 `{typography.section-title}` 22/400. `{typography.heading-2}`는 메인 밖 섹션 제목 · 패널 헤더에 남긴다. 400 굵기는 JPG 추정으로 남는다 |
| K-30 | M-02 · M-04 · M-06 · M-08 · M-09 (`layout.md` 값) | `{components.card-category}` 제목 · 썸네일 반경 · 매물 카드 1줄 | **M-02 · M-04 · M-09 신규** — `{components.card-category}` 제목 `{typography.heading-3}` 18/700 · 매물 카드 1줄 `{typography.caption}` 12 · 나머지 `home/01` · `02` 보정값은 1007 측정값으로(`layout.md`). **M-06 · M-08 기존 유지** — 썸네일 `{rounded.md}` 8 · 검색 아이콘 ↔ 글자 `{spacing.md}` 16 |
| K-31 | M-05 추천 래퍼 면 · 반경 · 패딩 | `{colors.surface-recessed}` · 래퍼는 배치라 `layout.md` | **신규 채택** — 면 `{colors.surface-recessed}` + 1px `{colors.border}` · `{rounded.md}` · 패딩 `{spacing.lg}`. 옛 `layout.md`의 「래퍼 = `{colors.surface-muted}`」는 표기 오류로 정리된다. `{colors.surface-muted}` 자체의 값은 바꾸지 않는다(hero 면에서 일치 관측) |
| K-32 | M-07 사이트맵 | `{components.site-footer}` 구역 1 · `{typography.link}` | **나눠서** — 컬럼 피치 190 · 링크 줄 피치 28 · 링크 크기 13(`{typography.body-sm}`)은 **신규**. 링크 색은 **`{colors.text-secondary}` 유지 — 의도적 이탈**(관측 `#999` 근처, 검수 표본 `#a9a9a9`. `#999999`는 흰 면 위 2.85로 AA 미달 — K-04와 같은 이유). 앞뒤 여백(1007 86 / 61)은 **보류 — 기존 64 / ≈ 48 유지**(배율로 설명되지 않는 방향의 차이) |
| K-39 | M-11 정본 결정 문장 뒤집기 | 정본 11절 K-08 · 정본 4절 「격자에 맞지 않는 관측값(44 · 56 · 76 · 184)」 | **둘 다 채택** — (a) K-08의 「`#eeeeee` 미채택」을 뒤집어 `{colors.surface-sunken}` 채택(`{components.card-content}` 채택과 함께 — K-33). (b) 56을 격자 밖 목록에서 빼고 `{spacing.section-tight}`로 채택(K-37). 이 문서의 K-08 · 4절 문장은 결정대로 고쳤다 |

### 델타 1007 — 신규 항목의 문제

| # | 항목 | 내용 |
| --- | --- | --- |
| K-33 | **`{components.card-content}` 글자색 — 의도적 이탈** | 관측 `{colors.primary}` 글자 on `{colors.surface-sunken}`은 **3.89:1로 AA 미달**이다. `{typography.content-title}` 20/400은 큰 글자(24px 또는 18.66px 굵게)가 아니라 4.5:1 대상이고, 라벨(≈ 12)도 같은 면 위다. 그래서 관측값을 베끼지 않고 글자를 `{colors.primary-hover}`로 바꿨다 — **4.75:1 ✓**(컷 표본 면 `#f0f0f0` 위 4.79). K-03 · K-04와 같은 방식이고, 옅은 면 위 파랑 글자에 `{colors.primary-hover}`를 쓰는 선례는 `{components.badge-primary}`다. 면 · 상단 선 · 원 버튼 외곽선은 글자가 아니라 `{colors.primary}` 그대로다(비텍스트 3:1 기준 3.89 ✓). **2026-10-07 사용자 결정 — 이 이탈과 함께 `{components.card-content}`를 채택했다** |
| K-34 | **`{components.promo-panel}` ↔ 8절 Don't — 명시 예외** | 「`{colors.primary}`를 큰 면적의 배경으로 쓰지 않는다」와 관측 482 × 99(높이 99 · 폭은 우 레일) primary 면이 부딪쳤다. **2026-10-07 사용자 결정 — 채택하고, 8절 Don't에 예외로 적었다: 메인 우 레일의 상담 입구 한 곳.** 규칙 자체는 그대로이고 예외를 넓히지 않는다 |
| K-35 | **사진 위 반투명 배지 면 — 쓰지 않음(결정)** | 계측 `overlay-badge`(`#66738f` 근처 · 반투명)는 아래 사진에 따라 합성색이 흔들려 불투명 hex로 적으면 관측을 왜곡하고, 알파 값은 관측할 수 없다. **2026-10-07 사용자 결정 — 우리 화면은 사진이 없으므로 쓰지 않는다.** 키를 두지 않는다. `{components.card-feature}`의 이미지부(높이 299) 자리에 무엇을 둘지는 정해지지 않았다 — 화면 작업에서 정한다 |
| K-36 | **1회 관측 신규 항목 — 우리 메인이 쓰는 것만 채택(결정)** | **2026-10-07 사용자 결정.** **채택**: 색 `{colors.block-feature}` · `{colors.on-block-feature}` · `{colors.on-block-feature-muted}` · `{colors.primary-line-soft}` · `{colors.surface-recessed}`(2컷 같은 구역 — K-31) · `{colors.surface-sunken}`(정본 K-08 미채택을 뒤집음 — K-39), 타이포 `{typography.section-title}`(3곳 반복) · `{typography.content-title}`, 간격 `{spacing.section-tight}`(2곳), 컴포넌트 `{components.card-category}` · `{components.card-feature}` · `{components.card-content}` · `{components.promo-panel}` · `{components.promo-panel-tile}`. 반복 관측이 아닌 것은 1회 관측 표기를 그대로 둔다.<br>**관측됨 · 미채택** — 프론트매터에서 뺐다. 다시 쓰게 되면 아래 관측값에서 시작한다.<br>· `pagination-indicator` — 띠 위 슬라이드 현재 위치 숫자(13/700 · 띠 위 흰 글자 · 아래 2px 밑줄 폭 ≈ 16 · 뷰포트 중앙). **사유: 슬라이드를 만들지 않는다.** home/1007-03 · 1회 관측<br>· `overlay-title` — 사진 위 흰 글자 제목 22/700(line-height 미관측). **사유: 사진 카드를 쓰지 않는다(K-35).** home/1007-03 · 1회 관측<br>· `card-placeholder` — 그리드 빈 칸(흰 면 + 1px 보더 · 가운데 13 글자 2줄 · 반경 ≈ 2~4 · 높이 159) · `card-placeholder-primary` — 같은 모양에 보더 · 글자 primary + 채운 원 16. **사유: 지금 쓰는 자리가 없다 — 필요해질 때 재검토.** home/1007-02 · 1회 관측 |
| K-37 | **이름 — `{spacing.section-tight}` · `{colors.surface-recessed}` · `{colors.surface-sunken}` (결정)** | **2026-10-07 사용자 결정 — 세 이름 그대로.** `section-tight`는 크기 이름 체계 밖이다 — `3xl`(48)과 `4xl`(64) 사이에 들어갈 크기 이름이 없어서다. `surface-recessed` · `surface-sunken`은 둘 다 「바탕보다 낮다」로 읽혀 밝기 방향을 이름으로 알 수 없다 — 실제 밝기는 `{colors.surface}` `#fff` > `surface-recessed` `#fafafa` > `{colors.surface-muted}` `#f4f5f6` > `surface-sunken` `#eeeeee`이고, 이 순서는 이 행과 2절 Surface 표가 갖는다. 이름이 그대로라 K-15의 고아 판정도 그대로다 |

### 추정 · 미관측 (참고 사이트)

| # | 항목 |
| --- | --- |
| K-05 | 폰트 패밀리 이름 미확정. 「한글 지오메트릭 산세리프 1종」까지만 확인했고 폴백 스택은 미관측이다. 우리는 Pretendard로 대체한다 |
| K-06 | letter-spacing 전 역할 미관측 — 지정하지 않음 |
| K-07 | 반경 · 그림자 · 폰트 크기 · weight · hex는 전부 컷에서 역산한 **추정**이다. 입력 반경은 0~2px로 읽혀 `{rounded.none}`으로 확정했다 |
| K-08 | 1회 관측이라 채택하지 않은 색: 실거래가 최고/최저 배지, 검증 배지, 「새단장」 pill, `listing` 블록 색 3종. 필요해지면 추가한다. 콘텐츠 카드 면 `#eeeeee`는 처음에 1회 관측이라 이 목록에 있었으나, 델타 1007에서 같은 구역이 다시 관측돼
`{colors.surface-sunken}`으로 채택했다(2026-10-07 결정 — K-39(M-11)).<br>**컴포넌트 변형 중 미채택**: `card/grid-item`(home/02 · listing/04 · home/1007-02 — 면 없는 매물 카드) · `tabs/segment-toggle`(map-search/04) · `badge/solid-primary`(listing/05 — 정의서의 `{components.badge-primary}`는 `{colors.primary-surface}` 면이라 다른 것이다) · `badge/pill-accent`(home/1007-01 nav · 카테고리 카드 배지). 우리 화면에 역할이 없다.<br>`type-rail` · `detail-panel` · `option-tile-grid` · `photo-grid` · `sub-nav`는 관측됐으나 **토큰이 아니라 구조물**이라 각 `layout.md`가 정본이다 — 7절 「구조」 소절 참조 |
| K-11 | `-hover` · `-focus` · `-error` · 열린 `pulldown` · zebra · 정렬 컨트롤 · 다크 모드 · 애니메이션 전부 미관측 |
| K-12 | 모달 그림자(레벨 3) 미관측 |
| K-13 | z-index 계층 미관측 — 값 미확정. **예외: `toast` 100**(이슈 487 — 앱 셸 아래 콘텐츠 · 지도 전환 토글(1) 위, 모달 아래). 카카오 지도 오버레이는 SDK `setZIndex`로 0 ~ 10000을 쓰는데 지도 컨테이너의 쌓임 맥락 밖으로 나오는지는 미확인 — 지도 화면에서 토스트를 쓸 때 확인한다 |
| K-16 | 모바일 · 태블릿 컷이 하나도 없다. 9절은 전부 프로젝트 정의이고 붕괴 규칙은 각 `layout.md`의 제안(미관측)이다 |
| K-17 | 표 폭 1364와 컨테이너 1330의 불일치(계측 G-04), 푸터 구역 2 높이 편차 88~100(G-05)은 측정 한계로 남는다 |
| K-38 | **델타 1007에서도 못 본 것.** 축소 JPG(1488)라 그림자 · 1~3px 단위(콘텐츠 그리드 갭 · 상단 선 두께 · 작은 반경)를 가를 수 없다(G-28). 칩 · 카드 · 더 보기 · 슬라이드의 hover · 다른 선택 · 넘김 상태, 슬라이드 둘째 칸 미관측(G-29). 로그아웃 상태의 인사 문구 · 추천 래퍼 미관측 — 컷은 로그인 상태(G-30). 뷰포트 1707의 푸터 구역 2~4 미관측(G-31). 모바일 · 태블릿 컷 여전히 없음(G-32) |

### 프로젝트 정의 — 미확정 · 미구현

| # | 항목 |
| --- | --- |
| K-09 | **미분석 등급의 도메인 용어가 없다.** 지금은 `{colors.risk-unanalyzed}` · `{components.badge-risk-unanalyzed}`로 적었다. 도메인 용어 표에 용어가 추가되면 그 이름으로 바꾼다 |
| K-18 | `dialog` — **키 정의됨 · 미구현 · 미관측.** 기존 토큰 조합(사용자 결정 2026-09-19, #150). 뒤 막(scrim)은 기존 토큰에 반투명 값이 없어 정하지 않았다 — 쓰는 화면이 생길 때 정의서에 먼저 넣는다 |
| K-19 | `toast` · `toast-error` — **구현됨(이슈 487) · 미관측.** 기존 토큰 조합(#150). 앱 셸에 하나(`ToastProvider`)라 전 화면 공통으로 정했다 — **화면 하단 중앙 · 2.5초 · 한 번에 하나(새 알림이 교체) · z-index 100(K-13)**. 화면 하단에 떠 있는 컨트롤(모바일 지도 전환 토글)이 있으면 토스트를 그 높이만큼 위로 올린다 — 그 컨트롤에 `data-toast-avoid` 를 단다(이슈 489, 사용자 확인 필요). 상세 패널 하단 `action-bar` 도 같은 방식으로 피하고, 모바일에서 상세가 열려 있으면 전환 토글이 바 위로 올라서므로 토스트는 둘 다 넘는다(이슈 491) |
| K-20 | `checkbox` · `-checked` · `-disabled` — **키 정의됨 · 미구현 · 미관측.** 기존 토큰 조합(#150). 알림 구독 화면은 아직 네이티브 `<input>`을 직접 그린다 — 공용 컴포넌트로 옮길 때 이 키를 쓴다. 20px은 터치 타겟(44)이 아니라 상자 크기다 — 터치 구간(`~767px`)에서는 누르는 영역이 44 하한을 채워야 한다(9절). 채우는 방식은 구현 때 정한다 |
| K-21 | 로딩 표시 `spinner` · `skeleton` — **구현됨(이슈 489) · 정적.** 표준 어휘에 없는 프로젝트 정의, 기존 토큰 조합(#150). 애니메이션(회전 · 맥동)은 넣지 않았다 — 속도가 미정이라 정할 때 붙인다 |
| K-22 | `radio` · `switch` · `textarea` · `date` · `calendar` — 미관측 · 미사용. 필요해지면 정의서에 먼저 넣는다. 1007 컷에도 없다(`dialog` · `checkbox` · `toast` · `select` · `alert` 포함) |
| K-23 | 차트(실거래가 · 시세)는 차기 범위다. 데이터 시각화 색은 **미사용** |
| K-24 | `{colors.border-subtle}` · `{colors.focus}` · `{colors.error}` 계열 · `{typography.label}` · `{spacing.3xs}`는 참고 사이트에서 관측되지 않았다. **기존 구현값을 유지**했고 삭제하지 않았다 |
| K-27 | `{typography.display-mobile}` · `{typography.heading-1-mobile}` — **구현됨(이슈 497) — 9절 두 자리.** 페이지 제목(favorites · my-info)과 폼 카드 제목(login · signup · 비밀번호 찾기 · 재설정)만 모바일 크기 → 768 이상 원 역할 크기. 가격 등 다른 자리는 그대로 (9절 Typography, 이슈 #130) |
