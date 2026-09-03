# 커밋 컨벤션

## 기본 형식

```
<type>(<scope>): <subject> (<기능ID>)

<body>
```

예시

```
feat(be): 보증보험 3사 가입 판정 로직 추가 (RISK-05)

기관별 집 단위 조건 검사와 개인 자격 조건 안내를 분리해 반환한다.
```

---

## Type

| Type | 용도 |
| --- | --- |
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `design` | 화면 변경 (색상, 레이아웃, 애니메이션) |
| `refactor` | 동작 변경 없는 구조 개선 |
| `style` | 포매팅 등 로직과 무관한 변경 |
| `test` | 테스트 코드 추가·수정 |
| `docs` | 문서 관련 작업 (생성·수정·삭제·이동) |
| `chore` | 빌드 설정, 의존성, CI, **기능과 무관한 마이그레이션** |

### 헷갈리는 구분

**`design` vs `style`** — 렌더링 결과가 달라지면 `design`, 안 달라지면 `style`.

- `design(fe): 위험 등급 배지 색상 조정 (RISK-01)` — 화면이 바뀜
- `style(fe): 컴포넌트 포매팅 정리` — 화면 그대로

**`refactor` vs `style`** — 포매터가 자동으로 할 수 있으면 `style`, 사람이 재작성했으면 `refactor`.

- `refactor(be): 선순위채권 합산 로직 계산 클래스로 분리 (RISK-02)`
- `style(be): import 순서 정렬`

| type | 동작 | 코드 구조 | 텍스트 |
| --- | --- | --- | --- |
| `feat` · `fix` | 바뀜 | 바뀜 | 바뀜 |
| `refactor` | 그대로 | **바뀜** | 바뀜 |
| `style` | 그대로 | 그대로 | **바뀜** |

이 구분이 중요한 이유는 버그를 추적할 때 `style` 커밋을 후보에서 제외할 수 있기 때문이다. `refactor`를 `style`로 잘못 달면 그 필터링이 깨진다.

**마이그레이션의 type** — 기능을 위한 스키마 변경은 그 기능의 `feat`에 포함시킨다. `chore`로 다는 것은 기능과 무관한 인덱스 추가·제약 정리뿐이다.

- `feat(be): 관심 매물 등록·해제 추가 (PROP-05)` — 테이블 추가 마이그레이션 포함
- `chore(be): 매물 조회 커버링 인덱스 추가` — 기능 변화 없음

마이그레이션을 무조건 `chore`로 달면 엔티티와 함께 커밋해야 한다는 규칙과 충돌한다.

---

## Scope

| Scope | 대상 |
| --- | --- |
| `be` | `backend/` 하위 |
| `fe` | `frontend/` 하위 |
| `infra` | 인프라 구성·운영 파일 |
| 생략 | `be` · `fe` · `infra` 그 외. `docs/`, `.claude/`, 루트 설정 |

백엔드와 프론트를 한 커밋에 섞지 않는다. 부득이하면 `feat(be,fe):`로 표기한다.

### `infra` 범위

| 경로 | 내용 |
| --- | --- |
| `docker-compose.yml` · `infra/docker-compose.prod.yml` · `Dockerfile` | 컨테이너 정의 |
| `infra/nginx/` | Nginx 설정, upstream |
| `infra/prometheus/` · `infra/grafana/` · `infra/alertmanager/` · `infra/promtail/` | 스크레이프 설정, 알림 규칙, 대시보드 |
| `infra/` | `postgresql.conf`, `pg_hba.conf`, 복제 설정 |
| `infra/` 스크립트 | `deploy.sh` · `observe.sh` · 페일오버 |
| `.github/workflows/` | CI |
| `chaos-harness/` | k6 부하 스크립트, 장애 주입 스크립트, 수집기 |

**`infra`도 type을 전부 쓴다.** 인프라 변경이라고 모두 `chore`가 아니다.

- `feat(infra): 슬롯 순차 교체 배포 스크립트 추가 (INF-02)`
- `fix(infra): 헬스체크 경로를 readiness로 수정 (INF-01)`
- `refactor(infra): 배포 스크립트의 슬롯 목록을 별도 파일로 분리 (INF-02)`
- `chore(infra): Prometheus 스크레이프 주기 15초로 설정`
- `test(infra): SSE 동시 연결 부하 스크립트 추가 (INF-06)`

### 그 외

`be` · `fe` · `infra` 어디에도 속하지 않으면 scope를 생략하고 type으로 구분한다.

- `docs: 인프라 API 명세서 추가`
- `docs: 시스템 구성서 데이터 증가량 절 삭제`
- `docs: 트래픽 정의서 요청 조합에 SSE 동시 연결 추가`
- `docs: 절 번호 변경에 따른 문서 간 참조 정정`
- `chore: .gitignore에 로컬 환경 파일 추가`
- `chore: doc-check 스킬 추가`

`.claude/` 하위(에이전트·스킬)는 `chore`로 단다. 코드가 아니라 작업 규약이므로 `docs`보다 `chore`가 맞다.

---

## 기능ID

정의서의 ID를 subject 끝에 괄호로 붙인다.

| 계열 | ID | 출처 |
| --- | --- | --- |
| 서비스 기능 | `USER-01` · `PROP-02` · `RISK-05` · `LOAN-01` · `NOTI-02` · `ADMIN-01` | `docs/features/` |
| 인프라 기능 | `INF-01` ~ `INF-06` | `docs/features/infra.md` |
| 부하 프로파일 | `T1` ~ `T7` | `docs/infra/traffic.md` |

- 기능별 추적에 사용한다. `git log --oneline | grep RISK-05`
- 장애 시나리오 9종은 별도 ID를 두지 않고 `INF-06`으로 묶는다
- 특정 기능에 속하지 않는 커밋(설정, 문서, 공통 리팩토링)은 생략한다

---

## Subject

- 50자 이내
- 한국어, 종결은 "~추가" · "~수정" 형태
- 마침표를 붙이지 않는다
- 제목만 보고 무엇을 했는지 파악되어야 한다

**좋은 예**

- `feat(be): 자치구별 매물 집계 조회 추가 (PROP-08)`
- `feat(fe): 지도 마커 미리보기 카드 추가 (PROP-02)`
- `fix(be): 선순위채권 합산에 말소 등기 포함되는 버그 수정 (RISK-02)`
- `feat(infra): WAL 아카이빙과 주간 전체 백업 구성 추가 (INF-04)`
- `chore: PostgreSQL·Redis docker compose 설정 추가`

**나쁜 예**

- `수정` — 무엇을?
- `feat: 작업` — 의미 없음
- `feat(be)/RISK-05: 판정 추가` — 형식 깨짐. ID는 끝에 괄호로
- `feat(be): 매퍼 만들고 서비스 고치고 테스트 추가` — 커밋을 쪼갠다

---

## Body

- 제목만으로 부족할 때 **왜** 변경했는지를 쓴다
- 제목과 본문 사이에 빈 줄 하나

### 인프라 커밋은 동작 확인 방법을 적는다

인프라 변경은 **컴파일도 테스트도 되지 않는 것이 많다.** Nginx 설정, `prometheus.yml`, Compose 파일은 틀려도 커밋 시점에 아무 일이 없고 배포할 때 터진다. 무엇으로 확인했는지 남기지 않으면 이력만으로는 검증 여부를 알 수 없다.

```
fix(infra): upstream 주소를 컨테이너 이름에서 루프백으로 변경 (INF-01)

Nginx는 upstream 호스트명을 설정 로드 시점에 한 번만 해석한다.
컨테이너 재생성으로 IP가 바뀌면 옛 IP로 계속 전달한다.

확인: nginx -t 통과, 두 슬롯 교대 정지 후 요청 손실 0건
```

| 대상 | 확인 명령 |
| --- | --- |
| Nginx 설정 | `nginx -t` |
| 알림 규칙 | `promtool check rules` |
| Compose | `docker compose config` |
| 배포 스크립트 | 실제 슬롯 교체 1회 |
| k6 스크립트 | `dropped_iterations == 0` 확인 |

---

## 커밋 단위

- 하나의 커밋은 하나의 논리적 변경이다
- 작업 전체를 몰아넣지 않는다
- 커밋이 커지면 쪼갤 수 없는지 먼저 검토한다

### 함께 커밋해야 하는 것 — 개발

프로젝트 규칙상 짝으로 묶이는 변경이 있다. 따로 커밋하면 어느 한쪽만 반영된 상태가 이력에 남는다.

| 함께 커밋 | 이유 |
| --- | --- |
| 매퍼 + 매퍼 테스트 | 컴파일 시점 검증이 없어 테스트가 유일한 확인 수단이다 |
| 마이그레이션 + 엔티티 | 한쪽만 반영되면 `ddl-auto validate`에서 기동이 실패한다 |
| 판정 로직 + 케이스 테스트 | 기대값 표 없이 통과한 판정은 검증된 것이 아니다 |
| 엔드포인트 + 요청 조합 반영 | 부하 시험 조합에서 빠지면 5주차 측정이 실제 트래픽과 달라진다 |

### 함께 커밋해야 하는 것 — 인프라

| 함께 커밋 | 이유 |
| --- | --- |
| exporter 추가 + Prometheus 스크레이프 설정 | **한쪽만 하면 지표가 조용히 빈다.** 오류가 발생하지 않는다 |
| Nginx upstream + 배포 스크립트 | 슬롯 이름·포트가 양쪽에 나온다. 한쪽만 바꾸면 배포가 존재하지 않는 슬롯을 건드린다 |
| 알림 규칙 + 조치 절차 | 알림 본문이 절차를 참조한다. 규칙만 추가하면 알림을 받고도 조치를 알 수 없다 |
| Compose 포트 추가 + 접근 통제 표 | `docs/infra/system.md` 4장과 `docs/api/infra.md` 1장이 낡는다 |