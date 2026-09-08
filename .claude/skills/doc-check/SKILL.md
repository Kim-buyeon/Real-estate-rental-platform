---
name: doc-check
description: 바꾼 것과 문서가 어긋나지 않았는지 확인한다. 변경 범위에 한정해 API 명세·DB 설계·비즈니스 로직·인프라 문서와 대조하고, 문서를 고쳤으면 그 문서를 인용한 쪽이 낡지 않았는지 본다.
---

# doc-check

**변경 범위 안에서만 본다.** 저장소 전체를 훑지 않는다. 매번 전부 보면 매번 건너뛰게 된다.

## 1. 범위를 잡는다

| 시점 | 명령 |
| --- | --- |
| 커밋 전 | `git diff --cached --name-only` |
| PR 전 | `git diff develop...HEAD --name-only` |

| 바뀐 것 | 할 일 |
| --- | --- |
| 코드·설정 | 2장 · 3장 |
| 문서 | 4장 |
| 둘 다 | 전부 |

경로 확인(5장)은 어느 경우든 한다.

## 2. 코드가 바뀌었다 → 대응하는 문서를 본다

**바뀐 파일의 경로로 찾는다.** 해당하는 행만 본다.

### 애플리케이션

| 바뀐 것 | 볼 문서 | 어긋나는 것 |
| --- | --- | --- |
| Controller 매핑 | `docs/api/<영역>.md` | 경로 · 메서드 · 요청/응답 필드 · 오류 코드 |
| 엔드포인트 신설 | `docs/infra/traffic.md` | 요청 조합과 비중에 들어갔는가 |
| `ErrorCode` | `docs/api/common.md` | 코드 값과 메시지 |
| DTO 필드 · enum 상수 | `docs/conventions.md` · `docs/api/<영역>.md` | 도메인 용어와 1:1인가. 표기 규칙 |
| 마이그레이션 · 엔티티 | `docs/architecture/database.md` · `docs/conventions.md` | 테이블 · 컬럼 · 인덱스 · 제약. 소문자 스네이크 |
| 판정 로직 | `docs/business-logic.md` · `docs/architecture/testing.md` | 산식 · 임계값 · 의사코드의 컬럼명. 기준을 코드 상수로 박지 않았는가 |
| 알림 생성 · SSE | `docs/architecture/notification.md` | 커밋 이후 발행 · 전 인스턴스 팬아웃 · 이벤트 본문 범위 |
| 외부 연동 어댑터 | `docs/tech-stack.md` · `docs/architecture/data-loading.md` | 연동 대상 · 1단계와 차기 구분 · Mock/Real/Fault 세 구현 |
| 커스텀 지표 이름 | `docs/infra/observability.md` · `docs/infra/system.md` · `docs/infra/runbook.md` | 지표 카탈로그 · 가용성 PromQL · `observe.sh` 질의 |
| Actuator · Security 설정 | `docs/api/infra.md` | 노출 엔드포인트 · Nginx 차단 대상 |
| `build.gradle` · `package.json` | `docs/tech-stack.md` | 버전 · 표에 없는 라이브러리 · 차기 범위로 표시된 것 |
| 테스트 구성 | `docs/architecture/testing.md` | Testcontainers 전제 · 계층별 범위 |
| 계층 간 호출 방향 | `docs/conventions.md` | 단방향 참조 · 계층 우회 |

### 인프라

| 바뀐 것 | 볼 문서 | 어긋나는 것 |
| --- | --- | --- |
| `infra/nginx/` | `docs/infra/runbook.md` · `docs/api/infra.md` · `docs/infra/observability.md` | 슬롯 · 포트 · 타임아웃 · `/actuator` 차단. `max_fails`가 알림 임계의 근거다 |
| `infra/prometheus/` 스크레이프 | `docs/infra/observability.md` · `docs/api/observability.md` · `docs/api/infra.md` | 주기 · 대상 노드 · 바인딩 주소 |
| `infra/prometheus/` 알림 규칙 | `docs/infra/runbook.md` · `docs/infra/observability.md` · `docs/api/observability.md` | 그 알림의 조치 절차 · 임계 근거 · 규칙 이름과 그룹 |
| `infra/alertmanager/` | `docs/api/infra.md` · `docs/infra/observability.md` | 채널 · 묶음 주기 · 템플릿 필드 |
| `infra/grafana/` | `docs/infra/observability.md` | 대시보드 구성 · 패널 PromQL |
| `infra/promtail/` | `docs/api/observability.md` · `docs/infra/observability.md` | 로그 라벨 · 구조화 전제 |
| PostgreSQL · Redis 설정 | `docs/infra/tech-stack.md` · `docs/infra/system.md` | 자원 상한 · WAL · 복제 슬롯 · 보존 기간 |
| `docker-compose*.yml` | `docs/infra/system.md` · `docs/api/infra.md` · `docs/infra/tech-stack.md` | 포트와 접근 통제 표 · 구성 요소 목록 |
| 배포 · 관측 스크립트 | `docs/infra/runbook.md` | **스크립트 전문이 문서에 실려 있다.** 인자 · 대기 시간 · 임계 |
| `chaos-harness/load/` | `docs/infra/traffic.md` · `docs/infra/test-plan.md` | 파일명 · executor · RPS · 엔드포인트 비중 · think time |
| `chaos-harness/scenarios/` | `docs/infra/test-plan.md` · `docs/features/infra.md` | 시나리오 번호 · 주입 명령 · 기능과 검증의 대응 |
| `chaos-harness/report/` | `docs/infra/test-plan.md` | 결과서 파일명 규칙 · 수집 구간 |
| `.env.example` | `docs/api/infra.md` · `docs/infra/system.md` · `docs/infra/observability.md` · `docs/architecture/data-loading.md` 1.3 | 변수 이름과 개수. 외부 API 키는 1.3 표와 이름이 같은가 |

### 저장소 규약

| 바뀐 것 | 볼 문서 |
| --- | --- |
| `.github/workflows/` | `docs/git/branch.md` · `docs/architecture/testing.md` · `docs/infra/test-plan.md` · `docs/infra/runbook.md` · `docs/tech-stack.md` |
| `.github/ISSUE_TEMPLATE/` · `PULL_REQUEST_TEMPLATE.md` | `docs/git/issue.md` · `docs/git/pull-request.md` |
| `.claude/agents/` · `.claude/skills/` | `docs/workflow.md` · `docs/infra/test-plan.md` |
| 커밋 훅 | `docs/workflow.md` |
| 디렉터리 재배치 | `docs/git/commit.md`「`infra` 범위」 · 루트 `CLAUDE.md` 프로젝트 구조 |

## 3. 개수와 목록이 적힌 것

**항목을 늘리거나 줄이면 개수를 적은 문서가 전부 낡는다.** 아래는 여러 문서가 같은 수를 적고 있다.

| 세어지는 것 | 개수가 적힌 곳 |
| --- | --- |
| 알림 규칙 | `docs/infra/observability.md` · `docs/roadmap.md` |
| 대시보드 | `docs/infra/observability.md` · `docs/infra/tech-stack.md` · `docs/roadmap.md` |
| 장애 주입 시나리오 | `docs/infra/test-plan.md` · `docs/features/infra.md` · `docs/infra/traffic.md` |
| 부하 프로파일 `T…` | `docs/infra/traffic.md` · `docs/git/commit.md` · `docs/git/pull-request.md` |
| 1단계 기능 | `docs/features/overview.md` · `docs/roadmap.md` |
| 더미 데이터 건수 | `docs/infra/traffic.md` · `docs/roadmap.md` |
| 인프라 기능 `INF-…` | `docs/features/infra.md` · `docs/git/branch.md` · `docs/git/commit.md` |

**여기에 숫자를 적지 않는다.** 적으면 이 파일이 먼저 낡는다.

## 4. 문서가 바뀌었다 → 인용한 쪽을 본다

루트 `CLAUDE.md`의 규칙이다. 숫자 · 절 번호 · 경로는 다른 문서에 인용되어 있다.

```bash
grep -rn "<바뀐 문서의 경로>" docs/ .claude/ CLAUDE.md
grep -rn "<바뀐 수치나 절 번호>" docs/
```

| 인용 대상 | 확인 |
| --- | --- |
| 경로 | 파일명이나 위치를 바꿨으면 인용한 쪽도 고친다 |
| 절 번호 | 절을 넣거나 뺐으면 「4장」 식 인용이 한 칸씩 밀린다 |
| 수치 | 임계값 · 개수 · 주기는 여러 문서에 나온다. 3장 표를 함께 본다 |
| 색인 | 새 문서를 만들었으면 `docs/features/overview.md` 또는 `docs/architecture/overview.md` 색인에 들어갔는가 |

## 5. 경로를 확인한다

| 검사 | 방법 | 통과 조건 |
| --- | --- | --- |
| 죽은 경로 | 바뀐 파일에 적힌 `docs/…` 경로를 모아 실재 여부를 본다 | 전부 있다 |
| 경로 유출 | 바뀐 파일에 `backend/CLAUDE.md` · `frontend/CLAUDE.md` · `.claude/agents/`가 있으면 `grep -n "docs/"` | 결과 없음 |

두 번째는 루트 `CLAUDE.md`가 정한 것이다. 이 파일들은 자동으로 읽히므로 `docs/` 경로를 적으면 `slice-start`와 지시가 겹친다.

## 6. 알린다

| 어긋남 | 다음 |
| --- | --- |
| 경로 · 절 번호가 낡았다 | 고친다. 사실 관계가 명확하다 |
| 코드와 명세가 다르다 | **어느 쪽이 맞는지 판단하지 않는다.** 양쪽을 제시하고 멈춘다 |
| 수치 · 임계값 · 판정 기준이 다르다 | 루트 `CLAUDE.md`의 문서 수정 규칙을 따른다 |

어긋남이 없으면 무엇과 무엇을 대조했는지만 알리고 넘긴다.