# map-search layout — source: dabangapp

> 컷: `reference-desktop-01-map-list.jpg` · `02-detail-price.jpg` · `03-detail-info-options.jpg` · `04-detail-trade-chart.jpg` · `05-detail-head-agency.jpg`
> 대응 화면: `/map` 지도 탐색 + 목록 탭 + 상세 패널
> 단위는 CSS px. 배율 근거는 `analysis/tokens.md` 0장 (**배율 ×1.0 확정**).
> 토큰 이름은 `DESIGN.md` 프론트매터 기준이다 (계측 단계 이름은 `analysis/tokens.md`).

## 섹션 순서 (위 → 아래)

1. **top-nav** (높이 80, 배경 `{colors.surface}`, 하단 1px `{colors.border}`)
   - 좌: 로고 자리 (좌여백 24) → 검색 `input` (폭 343 · 높이 48 · `{rounded.md}` · 우측 돋보기 아이콘)
   - 우: 내비 4 → 액션 버튼 2
   - 검색 input 좌측 여백은 로고 자리에서 40
2. **filter-bar** (높이 72, 배경 `{colors.surface}`, 하단 1px `{colors.border}`)
   - `pulldown` 5~6개 + 초기화 아이콘 버튼 1
   - pulldown 높이 40 · `{rounded.pill}` · 간격 `{spacing.md}`
   - 좌측 시작 x 는 type-rail 폭(67) + 48
3. **split (전폭, 컨테이너 없음)** — 아래 세 영역이 가로로 붙는다

| 영역 | 폭 | 성격 |
| --- | --- | --- |
| type-rail | 67 (고정) | 세로 아이콘 레일. 항목 6, 피치 91. 선택 항목 `{colors.primary-surface}` + `{rounded.md}` |
| list-panel | 447 (고정) | 매물 목록. 자체 스크롤 |
| detail-panel | 445 (고정, 열렸을 때만) | 상세. 지도 위에 얹힘 |
| map | 나머지 전부 (유동) | 지도 |

## 그리드 / 컨테이너

- **컨테이너 없음.** 이 아키타입만 뷰포트 전폭을 쓴다. 다른 아키타입의 고정 1330 컨테이너가 적용되지 않는다 (측정: 컷 5장 모두 좌우 여백 0).
- 세로도 전폭: split 영역이 `100vh - 80 - 72` 를 채우고 **각 패널이 개별 스크롤**한다. 페이지 전체 스크롤 없음 (푸터가 이 아키타입에는 없다 — 5컷 모두 푸터 미등장).

### list-panel 내부

- 상단에 `tabs/pill` (「매물 / 단지」) — 높이 30, 좌여백 24, 상여백 24
- 목록 행: 높이 192, 하단 1px `{colors.border}`
  - 썸네일 156×156 `{rounded.sm}` (좌여백 24) → 텍스트 열 (갭 24)
  - 텍스트 4줄: 가격 `{typography.heading-3}` → 유형 `{typography.body}` → 스펙 `{typography.body-sm}` `{colors.text-secondary}` → 설명 `{typography.body-sm}` `{colors.text-tertiary}` (1줄 말줄임)
  - `badge/plus` 는 마지막 줄 아래 8
  - 찜 아이콘은 썸네일 우상단 오버레이 (여백 8)
- 중개사 목록 모드(`05`)에서는 행이 아바타 46 + 이름/부가 2줄 + 우측 「N개의 방 >」 로 바뀌고, 그 아래에 매물 카드가 **2열 그리드**로 들어간다 (컬럼 162 · 갭 8)

### detail-panel 내부 (위 → 아래)

1. 패널 헤더 — 높이 50. 좌 매물번호 `{typography.heading-2}` · 우 아이콘 2(24, 갭 16)
2. `tabs/underline` — 높이 58. 넘치면 우측 셰브런
3. **photo-grid** (탭 이동 없이 최상단일 때) — 좌 1 대형 + 우 2×2, 갭 4, 하단 캡션 바 34
4. 가격 헤드 — 가격 `{typography.heading-1}` → 설명 2줄 (피치 32) → 스펙 2열 아이콘 행 2개 → `badge/tag` 랩 → 조회/찜 `{typography.body-sm}`
5. `button/outline` 전폭 (높이 48)
6. **섹션 띠** — `{colors.surface-muted}` 높이 40
7. 가격정보 — 섹션 제목 → `kv-row` × N
8. 섹션 띠 → 상세정보 → `kv-row` × 6
9. 섹션 띠 → 옵션 — 제목 + 개수 → `option-tile-grid` 4열 → `button/outline` (더보기, 높이 44)
10. 섹션 띠 → 보안/안전시설 → 거래정보(요약 카드 + 차트 + 표) …
11. **action-bar** (하단 고정, 높이 92)

- 좌우 패딩 24, 내부 콘텐츠 폭 400 — 전 섹션 공통
- `kv-row`: 라벨 열 150 + 값 열 250, 행 피치 66, 행마다 하단 1px `{colors.border}`
- 닫기 버튼은 패널 **바깥** 지도 위, 패널 우측에서 12

## 여백 리듬

| 구간 | 값 |
| --- | --- |
| 패널 좌우 패딩 | `{spacing.xl}` (24) |
| 섹션 제목 위 / 아래 | 24 / 16 |
| 섹션 사이 (회색 띠) | 40 |
| `kv-row` 피치 | 66 |
| 옵션 타일 갭 | `{spacing.lg}` (20) |
| 태그 칩 갭 | `{spacing.xs}` (8) |
| action-bar 내부 패딩 | `{spacing.md}` (16), 버튼 갭 `{spacing.xs}` |
| 목록 행 피치 | 192 |
| filter-bar pulldown 갭 | `{spacing.md}` (16) |

## 반응형 붕괴

**미관측 — 데스크톱 컷만 있음.** 뷰포트 2554px 단일 폭이고 모바일·태블릿 컷이 없다.

아래는 승인된 브레이크포인트(모바일 ~767 / 태블릿 768~1023 / 데스크톱 1024~, 컨테이너 max 1200)에서 위 구조를 접는 **제안(미관측)** 이다. 계측값이 아니다.

### 제안(미관측) — 태블릿 768~1023

- type-rail → filter-bar 위로 올려 **가로 스크롤 칩 줄**로 전환 (높이 56)
- list-panel 폭 447 → **360** 으로 축소, 지도는 나머지
- detail-panel 은 지도를 덮는 **오버레이**로 전환 (폭 360, 좌측 list-panel 위에 겹침)
- `kv-row` 라벨 열 150 → 120

### 제안(미관측) — 모바일 ~767

- **지도 전면 + 목록 하단 시트.** list-panel → 하단 시트(핸들 + 3단 스냅: 접힘 88 / 반 50vh / 전체 100vh)
- detail-panel → **전체 화면 오버레이**. 상단에 뒤로가기 + 매물번호, `tabs/underline` 은 가로 스크롤
- action-bar 는 전체 화면 오버레이 하단에 그대로 고정 (높이 92 → 80, 버튼 높이 60 → 52, 터치 타겟 최소 44 충족)
- filter-bar → 가로 스크롤 pulldown 줄 1줄 (넘치는 필터는 「필터」 버튼 → 바텀시트)
- top-nav → 로고 + 검색 아이콘 + 햄버거 (높이 80 → 56)
- `option-tile-grid` 4열 → **3열**
- `photo-grid` 1+2×2 → 가로 스와이프 캐러셀

### 제안(미관측) — 데스크톱 1024~

- split 구조 유지. **map 만 유동**, type-rail(67) · list-panel(447) · detail-panel(445) 은 고정
- 1024 폭에서 세 고정 영역 합이 959 → 지도가 65px 밖에 안 남는다. **1024~1279 구간에서는 detail-panel 을 오버레이로 전환**해야 지도가 유지된다 (참고 사이트는 초광폭 한 컷뿐이라 이 구간 동작을 확인할 수 없다)
