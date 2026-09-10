---
name: add-mapper
description: 화면에 전달할 데이터를 반환하는 MyBatis 조회를 만드는 절차. JPA와의 경계를 반환 타입으로 가르고, 조회 DTO → Condition → 매퍼 인터페이스 → XML → 매퍼 테스트 → 실행 계획 순서로 만든다. record DTO의 생성자 매핑, 커서 페이지네이션의 책임 분담, 바운딩 박스 조건, 실제 데이터베이스 테스트의 확인 항목을 정한다. 매퍼와 테스트는 같은 변경 단위다. backend-dev가 따른다.
---

# add-mapper

**경계는 반환 타입이다** — `docs/architecture/persistence.md` 1.1. 매퍼는 조회 전용이고 쓰기는 JPA다. 작성 규칙은 `backend/CLAUDE.md` Mapper · DTO 절이 갖는다. 여기서는 순서 · 확인 · 매핑 설정만 정한다.

## 1. 경계 판정 — 시작 전

| 필요한 것 | 어디로 |
| --- | --- |
| 화면에 전달할 DTO | **매퍼.** 담당 범위 목록은 `persistence.md` 1.1 표 |
| 엔티티 (변경 · 연관 탐색 · 인증 대상) | JPA Repository |
| 등록 · 수정 · 삭제 | JPA. 매퍼에 쓰기를 두지 않는다 |
| 같은 조회를 두 기술로 | 만들지 않는다. 반환 타입이 다르면 용도가 다른 것이다 |

## 2. 순서

```
1. 조회 DTO          record. 필드명은 명세와 같게
        │
2. Condition          요청 값 ≠ 조회 조건일 때만. 변환은 서비스가
        │
3. 매퍼 인터페이스     domain/<도메인>/mapper/
        │
4. XML                resources/mapper/<도메인>/
        │
5. 매퍼 테스트         실제 PostgreSQL. 같은 변경 단위       (6장)
        │
6. 실행 계획          EXPLAIN (ANALYZE, BUFFERS)             (7장)
```

| 단계 | 확인 | 규칙 |
| --- | --- | --- |
| 1 | 응답 필드명이 명세와 글자까지 같다. 한 Response를 여러 화면에 재사용하지 않는다. 매퍼가 이 DTO를 직접 반환한다 | `backend/CLAUDE.md` DTO |
| 2 | 가공(커서 → 식별자, 반경 → 바운딩 박스)이 있을 때만 Condition. 변환은 서비스에서 | 같음 |
| 3 | 파라미터는 Condition 객체 하나 | `backend/CLAUDE.md` Mapper |
| 4 | 필요한 컬럼만. 동적 조건은 `<where>` · `<if>`. 커서 · 정렬은 4장, 좌표 조건은 5장. 벤더 종속 함수는 쓰지 않는다 — `persistence.md` 1.3이 그 전제 위에 서 있다 | 같음 · `persistence.md` 1.3 |
| 5 | 6장 | `docs/architecture/testing.md` 1.1 매퍼 테스트 |
| 6 | 7장 | `docs/architecture/performance.md` 1.1 · 1.2 |

## 3. record 매핑

DTO는 `record`다. setter가 없으므로 MyBatis는 **생성자로** 값을 넣어야 한다.

| 설정 | 값 | 이유 |
| --- | --- | --- |
| `mybatis.configuration.arg-name-based-constructor-auto-mapping` | `true` | 자동 매핑 때 컬럼 순서가 아니라 **생성자 인자 이름**으로 맞춘다. 기본은 `false`. 스타터가 끌어오는 코어에 이 설정 키가 있는지 빌드 파일에서 확인한다 — 없으면 `<constructor>` 또는 `@Param` |
| 컴파일 옵션 `-parameters` | 켜져 있어야 한다 | 인자 이름을 런타임에 읽는 조건. 빌드 도구가 켜는지 확인한다 — Gradle이면 Spring Boot 플러그인이 `JavaCompile`에 자동으로 붙인다. 없으면 생성자 인자마다 `@Param`이 필요하다 |
| `mybatis.configuration.map-underscore-to-camel-case` | `true` | 스네이크 컬럼 → 카멜 인자. 기본은 `false` |
| 열거형 | 기본 `EnumTypeHandler` | 상수명 문자열로 매핑한다. 저장 값 = 상수명은 `docs/architecture/database.md` 6장의 전제 |
| 금액 · 비율 | `Long` / `BigDecimal` | BIGINT는 `Long`, NUMERIC은 `BigDecimal`. 금액 · 이율에 `double`을 쓰지 않는다 — `persistence.md` 1.2 |
| 좌표 | `backend/CLAUDE.md` Condition 예시가 쓰는 타입 | BigDecimal 강제의 범위는 금액 · 이율이다(`persistence.md` 1.2). 단 DB 설계서 6장은 좌표를 NUMERIC 군에 넣는다 — 예시의 타입과 DB 타입이 다르므로 **대조 필요**로 남긴다 |
| **중첩 객체** | `<resultMap>`에 **`<constructor>`를 명시**하고 중첩 객체는 `<arg name=… resultMap=…>` | `<association>`은 프로퍼티(setter)에 매핑하므로 record에 쓸 수 없다. `<constructor>`를 선언하면 자동 생성자 매핑은 그 조회에 관여하지 않는다 |
| **중첩 배열** (`providers[]` · `districts[].gradeCounts` · `products[]`) | `<constructor>`로는 채울 수 없다 — `<collection>`도 setter 매핑이다 | 조회를 나누어 서비스에서 묶거나, 평면으로 받아 서비스에서 그룹핑한다. **어느 쪽인지는 조회마다 계획에서 정한다** |

설정 키는 기반 작업(설정 — MyBatis 설정)에서 한 번 넣는다 — `slice-start` 1장. **평면 DTO는 `<constructor>`를 손으로 쓰지 않는다** — 인자 순서가 바뀌면 조용히 틀린다. 중첩 객체가 필요한 조회만 `<constructor>`를 쓴다.

## 4. 커서 페이지네이션

값(기본 · 최댓값)은 `docs/api/common.md` 1.4, 봉투 형태는 1.2가 갖는다. 여기서는 **누가 무엇을 맡는지**만 정한다.

| 책임 | 누가 | 내용 |
| --- | --- | --- |
| 기본값 채움 | Request의 compact 생성자 | `size`가 없으면 기본값 — `backend/CLAUDE.md` DTO 「기본값이 필요하면 compact 생성자」 |
| 상한 검증 | Request의 검증 애노테이션 | 검증 실패의 상태 코드는 `common.md` 1.3. **하한(0 이하)은 명세에 없다** — 처리를 명세에 추가 제안 |
| 커서 해석 | 서비스 (공통 커서 클래스로) | 문자열 커서 → 식별자. 매퍼는 문자열 커서를 모른다 — Condition의 `lastId` |
| `+1` 조회와 `hasNext` | 서비스 | `limit + 1`을 매퍼에 넘기고, 결과가 `limit`을 넘으면 마지막 1건을 잘라 `hasNext`로 |
| 정렬 기준 + 식별자 | XML | 정렬 컬럼이 같은 행을 식별자로 갈라 빠짐 · 겹침을 막는다 |
| 정렬 키 허용 목록 | 서비스 | 요청의 `sort` 값을 SQL에 그대로 붙이지 않는다 |
| 커서 문자열 형식 | **미확정** — 공통 커서 클래스가 정한다 | 명세 예시는 형식을 보여주지만 규칙으로 적혀 있지 않다. 그 클래스는 `slice-start` 1장 기반 작업(공통 클래스)이다 |

OFFSET을 쓰지 않는 이유는 `performance.md` 1.1. 좌표 조건 조회는 커서를 쓰지 않는다 — `docs/api/property.md` 1.3.

## 5. 바운딩 박스 · 반경

| 규칙 | 근거 |
| --- | --- |
| 공간 확장 없이 (latitude, longitude) 인덱스 + BETWEEN. `district`를 선행 필터로 | `persistence.md` 1.3 |
| 바운딩 박스는 서비스가 계산해 Condition으로 넘긴다 | `backend/CLAUDE.md` DTO — Condition 예시 |
| 반경 요청이면 Haversine으로 최종 거리 판정 + 거리순 정렬. 바운딩 박스는 1차 필터 | `persistence.md` 1.3 · `docs/api/property.md` 1.3 |
| Haversine을 SQL 표현식으로 둘지 서비스에서 계산할지 | **미확정.** 계획에서 정한다. SQL이면 표준 함수만으로 쓸 수 있는지 먼저 확인 |

## 6. 매퍼 테스트

**컴파일 시점 검증이 없다.** 컬럼명 오타 · 문법 오류 · 매핑 누락은 실행해야 드러난다 — `testing.md` 1.1.

| 확인 | 방법 |
| --- | --- |
| 컬럼명 · 문법 | 실제 PostgreSQL 컨테이너(Testcontainers)에 Flyway 마이그레이션을 적용한 스키마로 실행한다 |
| record 매핑 | 모든 필드에 값이 들어왔다 — `null`이면 생성자 인자 이름과 컬럼 별칭이 다르다 |
| 동적 조건 | 조건 조합마다 한 케이스 — 조건 없음, 하나만, 전부 |
| 커서 | 첫 페이지 · 중간 · 마지막 · 빈 결과. 정렬 컬럼이 같은 행이 다음 페이지로 밀리지 않는다 |
| 바운딩 박스 | 경계 안쪽 · 바깥 좌표 각 1건. 등호 포함 여부가 SQL과 같다 |
| 열거형 | VARCHAR 값이 열거 상수로 돌아온다 |
| 집계 | 그룹별 개수가 삽입한 픽스처와 맞는다 |

| 원칙 | 내용 |
| --- | --- |
| 픽스처는 테스트가 넣는다 | 마이그레이션의 시드에 기대지 않는다. 운영 데이터 샘플을 넣지 않는다 |
| 하나의 테스트는 하나를 검증한다 | 이름이 조건을 말한다 |
| 통과만 보지 않는다 | 컬럼 별칭을 잠시 틀어 빨개지는지 본다 — `test-engineer` 5단계 |

## 7. 실행 계획

| 확인 | 방법 | 결과 |
| --- | --- | --- |
| 인덱스를 타는가 | 로컬 · 컨테이너 DB에 더미 데이터를 넣고 `EXPLAIN (ANALYZE, BUFFERS)` | 자주 필터 · 정렬하는 컬럼에서 Seq Scan이면 인덱스 누락 |
| 인덱스가 없다 | 마이그레이션에 `CREATE INDEX`. 커밋 type은 `docs/git/commit-convention.md` 「마이그레이션의 type」 | — |
| 더미가 없다 | 「미검증」으로 적고 넘긴다. 예비 부하 측정이 인덱스를 확정한다 — `docs/roadmap.md` 1주차 | — |

수치를 기록한다 — 적용 전후 비교가 `performance.md` 1.2의 검증 방법이다.

## 8. 함께 커밋한다

짝은 `docs/git/commit-convention.md`의 「함께 커밋해야 하는 것」 두 표가 갖고 `git-check`가 본다. 「매퍼 + 매퍼 테스트」가 거기 있다.

## 9. 하지 않는다

- **INSERT · UPDATE · DELETE를 매퍼에 쓰지 않는다.** 엔티티를 반환하지 않는다.
- **같은 트랜잭션에서 JPA로 바꾼 값을 매퍼로 읽지 않는다.** 반영 전 값을 읽는다 — `backend/CLAUDE.md` Service.
- **평면 DTO에 `<constructor>`를 손으로 쓰지 않는다.** 중첩만 예외다.
- **커밋하지 않는다.** 커밋은 별도 단계이며 `git-check`를 거친다.