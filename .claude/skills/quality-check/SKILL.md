---
name: quality-check
description: 바뀐 코드의 성능·구조·유지보수성을 근거(파일:줄)로 점검한다. 프론트는 재렌더·번들·쿼리 키·리스너 누수·토큰 이탈, 백엔드는 N+1·누락 인덱스·대량 적재·정밀 연산을 본다. 결과는 통과·위반·N-A·미검증으로 적고, 규칙 문서에 근거가 있는 위반만 code-reviewer의 등급으로 넘긴다. code-reviewer가 ci-check 뒤에 부른다.
---

# quality-check

**규칙 문서가 항목으로 정한 것은 `code-reviewer` 3장이 본다.** 여기서는 **비용이 드는 패턴을 찾는 방법**을 정한다. 발견마다 `파일:줄` · 왜 비용인지 · 한 줄 개선 방향을 적는다. **측정이나 코드 근거가 없는 추정은 「미검증」이다.**

`ci-check` 뒤에 돈다 — 빌드 출력(번들 크기)과 테스트 통과가 전제다.

## 1. 대상

```bash
git diff develop...HEAD --name-only
```

**이번 브랜치가 바꾼 파일만 본다.** 바뀐 파일이 호출하는 쪽까지 따라가되, 주변 코드의 기존 문제는 대상이 아니다.

## 2. 프론트 (`frontend/`)

| 차원 | 찾는 것 | 방법 | 근거 문서 |
| --- | --- | --- | --- |
| 재렌더 | 렌더마다 새 객체 · 함수가 props로 내려가 무거운 자식을 다시 그린다 | JSX props의 인라인 객체 · 화살표 함수를 찾고, 받는 자식이 `memo`이거나 지도 · 목록처럼 무거운지 본다. 둘 다면 위반, 자식이 가벼우면 통과 | `frontend/CLAUDE.md` |
| 렌더 내 연산 | 컴포넌트 본문에서 배열 정렬 · 그룹핑 · 거리 계산이 `useMemo` 없이 매 렌더 실행된다 | 훅 밖 본문의 `.sort(` `.filter(` `.reduce(`와 좌표 계산. 마커 배열이 대상이면 위반 | `frontend/CLAUDE.md` |
| 번들 | 초기 청크가 크다. 라우트 단위 분할이 없다 | `vite build` 출력의 청크 크기. Vite 기본 경고 기준 500 kB(`build.chunkSizeWarningLimit`, 비압축). 빌드 출력이 없으면 미검증 | 없음 — 기준 크기는 어느 문서도 정하지 않았다 |
| 큰 동기 import | 차트 · 지도 · 날짜 라이브러리를 최상위에서 통째로 import | `import` 문의 패키지 크기. 카카오맵은 `kakao-map` 2장대로 `<script>` 태그 로드인지 | 없음 — SDK 접근 방식(직접 / 래퍼)은 `docs/tech-stack.md` 4장에서 미확정. 확정 전엔 규칙 제안 |
| 쿼리 키 | 키에 없는 값으로 요청을 만든다 → 다른 조건의 결과가 캐시에서 나온다 | `queryFn`이 쓰는 변수와 `queryKey`의 원소를 대조한다. 키 리터럴(`queryKey: [`)이 팩토리 밖에 있으면 위반 | `docs/tech-stack.md` 4장 (쿼리 키 팩토리) · `frontend/CLAUDE.md` |
| 리스너 · 연결 누수 | 정리되지 않는 리스너, 화면 안에서 여는 SSE | `kakao.maps.event.addListener`에 대응하는 `removeListener`가 정리 함수에 있는가. 오버레이 `content` 엘리먼트의 `addEventListener`가 오버레이 제거 시 해제되는가. `new EventSource(`가 최상위 연결 관리자 밖에 있으면 위반 | `kakao-map` 5장 · `docs/tech-stack.md` 4장 (EventSource) · `docs/architecture/notification.md` |
| 토큰 이탈 | 색 · 간격이 토큰 변수가 아니라 리터럴이다 | `grep -rnE '#[0-9a-fA-F]{3,8}\b' frontend/src` — 토큰 파일 자신을 제외한 결과가 있으면 위반. px 리터럴은 레이아웃 1회성만 허용 | 디자인 토큰 정의서(경로는 `slice-start`) · `frontend/CLAUDE.md` |
| 열거값 분기 | 열거값을 `switch` · `if`로 문구 · 색에 매핑한다 | `riskGrade` `contractType` 등 열거값 위의 분기 | `docs/conventions.md` 「표시 문구는 열거형이 갖는다」 |
| 서버 값의 HTML 삽입 | 서버 문자열이 HTML로 해석되는 경로 | `dangerouslySetInnerHTML`, 오버레이 `content`에 문자열 템플릿 | `kakao-map` 5장 · `frontend/CLAUDE.md` |

**`frontend/CLAUDE.md`가 없는 동안** 그 문서를 근거로 삼는 행의 위반은 전부 4장의 「규칙 제안」이다.

## 3. 백엔드 (`backend/`)

| 차원 | 찾는 것 | 방법 | 근거 문서 |
| --- | --- | --- | --- |
| N+1 | 반복문 안의 조회, 목록을 JPA로 가져와 화면에 전달 | `for` · `stream().map` 안의 Repository · Mapper 호출. 화면 전달용 목록이 JPA 엔티티면 위반 — 조회 경로는 MyBatis다 | `docs/architecture/persistence.md` 1.1 |
| 누락 인덱스 | 자주 필터 · 정렬 · 조인되는 컬럼에 인덱스가 없다 | 매퍼 XML의 `WHERE` · `ORDER BY` · `JOIN` 컬럼과 마이그레이션의 `CREATE INDEX`를 대조한다. 대상 쿼리를 `EXPLAIN (ANALYZE)`로 돌려 Seq Scan이면 위반. 실행 계획을 못 뽑으면 미검증 | `docs/architecture/performance.md` 1.1 · 1.2 |
| 대량 적재 | 전체 테이블을 메모리에 올린다 | `findAll()` · `LIMIT` 없는 목록 조회 · 페이지네이션 없는 배치 읽기 | `docs/api/common.md` 1.4 · `docs/architecture/performance.md` 1.1 |
| 캐시 | 만료 없는 캐시, 키가 무한히 늘어나는 캐시 | `@Cacheable`의 캐시에 TTL 설정이 있는가. 키에 좌표처럼 연속값이 그대로 들어가는가 | 없음 — `docs/architecture/performance.md` 1.1은 「유효 기간을 넉넉히」까지만 정한다 |
| 읽기 전용 | 조회 트랜잭션에 읽기 전용 속성이 없다 | QueryService의 `@Transactional(readOnly = true)` | `docs/architecture/performance.md` 1.1 |
| 정밀 연산 | 금액 · 이율에 `double` · `float` | 금액 · 비율 필드와 연산의 타입 | `docs/architecture/persistence.md` 1.2 |
| 중첩 · 길이 | 3단계 넘는 중첩, 화면 하나를 넘는 함수 | 읽어서 본다 | `docs/conventions.md` |

## 4. 판정

| 결과 | 뜻 | 필수 기록 |
| --- | --- | --- |
| 통과 | 패턴 없음 | 무엇을 어떻게 봤는지 |
| 위반 | 패턴 있음 | `파일:줄` · 왜 비용인지 · 개선 방향 |
| N-A | 이번 변경에 해당 차원이 없다 | 사유 한 줄 |
| 미검증 | 도구 · 출력 · 환경이 없어 확인 못 했다 | 무엇이 있어야 확인되는지 |

**억지로 채우지 않는다.** 확인 못 한 것은 미검증이고, 통과로 쓰지 않는다.

위반은 근거 문서의 유무로 갈린다. `code-reviewer` 3장 — 규칙 문서에 근거가 없으면 지적하지 않는다.

| 위반의 근거 | `code-reviewer`에게 |
| --- | --- |
| 근거 문서가 금지한 패턴 | 지적 — 「막는다」 |
| 근거 문서가 요구하는 것을 하지 않았다 | 지적 — 「고친다」 |
| 근거 문서에 없다 | **지적이 아니다.** 「규칙 제안」으로 따로 적어 사용자에게 보인다 — 해당 `CLAUDE.md`나 방침 문서에 넣을지는 사용자가 정한다 |

## 5. 보안 의심

| 발견 | 처리 |
| --- | --- |
| 시크릿 리터럴, 서버 값의 HTML 삽입, 토큰의 콘솔 출력, 사용자 입력으로 조립한 SQL · 경로 | **여기서 판정하지 않는다.** 위치만 적어 `code-reviewer` 3장의 규칙 대조로 넘긴다. 자격 증명은 커밋 훅이 별도로 차단한다 |

## 6. 리포트

```markdown
## quality-check — <브랜치>
| 차원 | 결과 | 근거 |
| --- | --- | --- |
| 재렌더 | 위반 | src/features/map/MarkerLayer.tsx:41 — 매 렌더 새 onClick, 자식은 memo → 재렌더. 핸들러를 useCallback으로 |
| 번들 | 미검증 | vite build 출력 없음 |
| N+1 | N-A | 백엔드 변경 없음 |
## 규칙 제안
- 번들 청크 상한 — 어느 문서도 정하지 않았다. frontend/CLAUDE.md 후보
```

**「문제 없음」만 남기지 않는다.** 무엇을 어떻게 봤는지가 없으면 다음 사람이 다시 봐야 한다.