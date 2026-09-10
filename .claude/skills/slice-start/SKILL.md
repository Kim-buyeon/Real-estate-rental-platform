---
name: slice-start
description: 기능이나 인프라 작업에 착수할 때 어느 문서를 읽을지 정한다. 기능 ID 또는 작업 내용을 받아 갈래를 판별하고, 그 갈래에 필요한 기능 정의·API 명세·구현 방침만 컨텍스트에 올린다.
---

# slice-start

작업에 필요한 문서만 컨텍스트에 올린다. **문서 목록을 여기에 두지 않는다.** 색인 두 개를 읽고 그것이 가리키는 것을 따라간다.

## 1. 갈래를 정한다

무엇을 읽을지는 갈래가 정한다. 다섯 중 하나다.

| 갈래 | 판별 | 올린다 |
| --- | --- | --- |
| 백엔드 기능 | 기능 ID가 있고 서버 작업 | 영역 + 구현 방침 |
| 프론트 기능 | 기능 ID가 있고 화면 작업 | 영역. 구현 방침은 알림 작업일 때만 |
| 인프라 | `INF-01` ~ `06` | 영역(인프라 행). `docs/api/common.md`는 제외 |
| 기반 | 기능 ID가 없고 코드·설정을 만든다 | 아래 목록 + 구현 방침 |
| 운영 | **서버를 대상으로 한다** — 상태를 바꾸든 읽든. 배포 · 롤백 · 점검 · 복구 · 장애 대응 · 운영 보고. INF ID가 붙어 있어도 저장소를 바꾸지 않으면 운영이다 | 2장 에이전트 표의 `infra-operator` 행. 그것만 |

기능 작업으로 보이는데 ID만 빠졌으면 내용으로 추정하고 **추정한 ID를 먼저 확인받는다.** 갈래를 잘못 잡으면 이후 작업 전체가 어긋난다.

### 기반 작업

**여러 기능이 의존하지만 어느 하나의 소유도 아닌 것**이다.

| 종류 | 예 |
| --- | --- |
| 공통 클래스 | `BaseEntity` · `ApiResponse` · `CursorPage` · 커서 인코딩·디코딩 클래스 · `ErrorResponse` · `ErrorCode` · `BusinessException` · 전역 예외 처리기 |
| 설정 | `SecurityConfig` · `RedisConfig` · JPA Auditing · MyBatis 설정(생성자 매핑 · 카멜 변환) · AOP · Resilience4j 기본값 · Spring Batch · springdoc |
| 관측 | 로그 규약(JSON · `traceId` 전파 · 마스킹) · Actuator·Micrometer 커스텀 지표 |
| 스키마 | Flyway 초기 마이그레이션 · 시드 데이터 |
| 환경 | 로컬 Compose · Testcontainers 지원 클래스 |

**영역 색인만 거치지 않는다.** 기능 ID로 갈리므로 성립하지 않는다. 그 자리에 API 명세를 직접 지정한다.

- `docs/api/common.md` — **항상.** 응답 형식과 오류 코드는 어느 공통 클래스를 만들든 기준이 된다
- 인증 설정이면 `docs/api/user.md` — 토큰 발급·갱신 규약과 인증 오류 코드가 거기 있다

**구현 방침은 2장 색인을 그대로 쓴다.** 「읽어야 할 때」 열에 엔티티·초기 스키마 작성, AOP·인증 설정 작성, 테스트 지원 클래스 구성, 환경 구성이 들어 있다. 기반 작업은 대부분 여기서 걸린다.

## 2. 색인을 따라간다

| 색인 | 고르는 기준 | 읽는 범위 |
| --- | --- | --- |
| `docs/features/overview.md` 1장 | 기능 ID가 속한 영역 | 그 행의 기능 정의 · API 명세 · 함께 읽을 문서 + `docs/api/common.md` |
| `docs/architecture/overview.md` 2장 | 이번 작업의 주제 | 「읽어야 할 때」 열이 해당하는 행만. 전부 읽지 않는다 |

### 붙는 에이전트에 따라 더 올린다

**1장의 갈래가 어느 에이전트가 붙는지를 정한다.** 그 에이전트가 항상 필요로 하는 문서를 **여기서 함께 올린다.** 에이전트 정의에는 `docs/` 경로를 적지 않으므로, 여기서 올리지 않으면 그 문서는 올라오지 않는다.

| 에이전트 | 더 올린다 | 언제 붙는가 |
| --- | --- | --- |
| `test-engineer` | `docs/architecture/testing.md` | 테스트를 쓰는 작업 전부 |
| `load-tester` | `docs/infra/test-plan.md` · `docs/infra/traffic.md` · `docs/infra/observability.md` | 부하 시험 |
| `chaos-runner` | `docs/infra/test-plan.md` · `docs/infra/traffic.md` · `docs/infra/runbook.md` · `docs/infra/observability.md` | 장애 주입 시험 |
| `traffic-builder` | `docs/infra/traffic.md` · `docs/infra/test-plan.md` · `docs/api/common.md` · 요청 조합의 엔드포인트가 속한 영역의 API 명세 | 부하 스크립트 작성 |
| `infra-operator` | `docs/infra/runbook.md` · `docs/infra/system.md` · `docs/infra/observability.md` · `docs/api/infra-observation.md` · `docs/infra/tech-stack.md` · `docs/infra/test-plan.md` · `docs/features/infra.md` | 운영 갈래 전부 |
| `monitoring-engineer` | `docs/infra/observability.md` · `docs/api/infra-observation.md` · `docs/api/infra.md` · `docs/infra/system.md` · `docs/infra/tech-stack.md` · `docs/infra/runbook.md` · `docs/infra/test-plan.md` · `docs/features/infra.md` | 관측 설정 작성 |

`traffic-builder`는 인프라 갈래인데도 `docs/api/common.md`를 올린다. 1장의 제외 규칙은 인프라 구성이 앱 API를 쓰지 않기 때문인데, 부하 스크립트는 앱 API를 호출하고 응답 봉투를 파싱하므로 예외다.

나중에 부를 것이라도 **착수 시점에 올린다.** 작업 도중에 다시 고르지 않는다.

## 3. 착수 가능한지 확인한다

| 확인 | 근거 | 해당하면 |
| --- | --- | --- |
| 차기 범위인가 | `docs/features/overview.md` 3장 | 착수하지 않고 범위 밖임을 알린다 |
| 선행 기능이 있는가 | 같은 문서 2장 색인의 `선행` 열 | 그 영역 문서도 함께 읽는다 |
| 동결 이후인가 | `docs/roadmap.md` 판정 지점 | 기능 추가면 착수하지 않는다 |

기반 갈래는 **동결만 확인한다.** 차기 범위와 선행 기능은 기능에만 적용된다. **운영 갈래는 3장을 건너뛴다.** 기능을 만드는 작업이 아니다.

하나라도 걸리면 **문서를 더 읽지 않고 멈춘다.**

## 4. 올리지 않는다

| 대상 | 이유 |
| --- | --- |
| `docs/workflow.md` · `docs/git/` · `docs/conventions.md` | 루트 `CLAUDE.md`가 지시한다 |
| `backend/CLAUDE.md` · `frontend/CLAUDE.md` | 그 폴더 파일을 열면 자동으로 읽힌다 |
| 다른 영역의 기능·명세 문서 | 선행 관계가 아니면 필요 없다 |
| 다른 갈래의 문서 | 프론트 작업에 영속성 방침, 백엔드 작업에 화면 방침 |
| `docs/architecture/`의 나머지 | 2장에서 고른 것만 |

**필요할지도 모른다는 이유로 올리지 않는다.** 작업 중에 필요해지면 그때 읽는다.

## 5. 무엇을 올렸는지 알린다

갈래와 읽은 문서를 목록으로 제시하고 다음 단계로 넘긴다. 이후는 `docs/workflow.md`의 순서를 따른다 — 운영 갈래는 그 문서 4장.