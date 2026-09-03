# 브랜치 전략

## 브랜치 구조

```
main ──────────────────────────► 배포 가능한 상태만 유지
  ▲ ▲
  │ └─ hotfix/* ───────────────► 배포 후 긴급 수정 (main에서 분기)
develop ───────────────────────► 통합 브랜치
  ▲
feature/*, fix/*, chore/*, docs/* ─► 작업 브랜치 (develop에서 분기)
```

| 브랜치 | 역할 | 분기 원점 | merge 대상 | 직접 push |
| --- | --- | --- | --- | --- |
| `main` | 배포 가능한 상태만 유지 | — | — | 금지 (PR만) |
| `develop` | 다음 배포를 위한 통합 | `main` | `main` | 금지 (PR만) |
| `feature/*` | 기능 개발 | `develop` | `develop` | 자유 |
| `fix/*` | 배포 전 버그 수정 | `develop` | `develop` | 자유 |
| `chore/*` | 설정·빌드·의존성 | `develop` | `develop` | 자유 |
| `docs/*` | 문서 작업 | `develop` | `develop` | 자유 |
| `hotfix/*` | 배포 후 긴급 수정 | `main` | `main` + `develop` | 자유 |

`main`과 `develop`은 항상 유지되고, 나머지는 merge 후 삭제하는 임시 브랜치다.

---

## 브랜치 네이밍

```
<prefix>/<scope>/<기능ID>-<기능명(영문)>[_<작업-키워드>]
```

**scope는 `docs/git/commit.md`와 같은 값을 쓴다** — `be` · `fe` · `infra`. 어느 쪽도 아니면 scope를 생략한다.

| Scope | 대상 | 형식 |
| --- | --- | --- |
| `be` | `backend/` 하위 | `feature/be/RISK-05-guarantee-judgment` |
| `fe` | `frontend/` 하위 | `feature/fe/PROP-02-map-drilldown` |
| `infra` | 인프라 구성·운영 | `feature/infra/INF-02-rolling-deploy` |
| 생략 | 문서·루트 설정 | `docs/update-api-spec` |

### 기능ID

정의서의 ID를 그대로 쓴다.

| 계열 | ID | 출처 |
| --- | --- | --- |
| 서비스 기능 | `USER-01` · `PROP-02` · `RISK-05` · `LOAN-01` · `NOTI-02` · `ADMIN-01` | `docs/features/` |
| 인프라 기능 | `INF-01` ~ `INF-06` | `docs/features/infra.md` |

### 예시

```
feature/be/RISK-05-guarantee-judgment
feature/be/PROP-08-district-aggregation
feature/fe/PROP-02-map-drilldown
feature/fe/RISK-01-grade-badge
feature/infra/INF-02-rolling-deploy
feature/infra/INF-04-backup-pitr
fix/be/RISK-02-mortgage-sum
chore/infra/setup-docker-compose
chore/be/init-schema
docs/update-api-spec
```

### 규칙

- prefix는 `feature` · `fix` · `hotfix` · `chore` · `docs` 다섯 개만 사용한다.
  - `fix`는 배포 전 수정, `hotfix`는 배포 후 긴급 수정이다.
- 기능명은 영문 소문자, 단어 구분은 하이픈(`-`). 기능ID의 대문자는 그대로 둔다.
- 한 기능을 **같은 영역 안에서** 여러 브랜치로 쪼갤 때만 언더스코어(`_`)로 작업 키워드를 붙인다.
  - `feature/be/RISK-05-guarantee-judgment_criteria` — 기준 테이블
  - `feature/be/RISK-05-guarantee-judgment_engine` — 판정 엔진
  - 백엔드와 프런트의 분리는 scope가 담당하므로 언더스코어를 쓰지 않는다
- 한글·공백·괄호를 쓰지 않는다.
  - 괄호는 셸에서 문법 문자라 `git switch` 시 오류가 난다.
  - 한글은 OS별 유니코드 정규화 차이로 같은 이름의 브랜치가 중복 생성될 수 있다.
- 기능ID가 없는 작업은 scope 뒤에 내용만 쓴다.
  - `chore/infra/setup-nginx-upstream`, `chore/be/init-schema`, `docs/update-api-spec`

### scope를 앞에 두는 이유

영역별로 브랜치를 골라낼 수 있다.

```bash
git branch --list 'feature/be/*'      # 백엔드 작업만
git branch --list '*/infra/*'         # 인프라 작업 전체
git branch --list '*RISK-05*'         # 기능별은 와일드카드로
```

`docs/git/commit.md`의 `feat(be):`와 순서가 같아 브랜치와 커밋을 나란히 읽을 수 있다.

---

## 백엔드와 프런트를 나누는 방법

**한 기능이라도 브랜치를 나눈다.** PR 단위가 작아지고, 영역별로 CI 대상이 갈린다.

**순서는 백엔드가 먼저다.**

1. `feature/be/PROP-02-map-drilldown` — API 완성 후 `develop`에 merge
2. `feature/fe/PROP-02-map-drilldown` — merge된 `develop`에서 분기해 화면 작업

프런트를 먼저 열면 작업 도중 API 응답 형태가 바뀌어 두 번 고치게 된다. 백엔드를 먼저 `develop`에 넣으면 프런트는 **고정된 API를 상대로** 작업한다.

부득이하게 한 브랜치에서 양쪽을 건드리면 **커밋은 반드시 분리한다.** `docs/git/commit.md`의 `feat(be,fe):`는 정말 나눌 수 없을 때만 쓴다.

---

## 슬라이스 단위 작업

로드맵의 슬라이스는 여러 기능을 묶으므로, 슬라이스 착수 시 기능 단위로 브랜치를 나눈다.

**슬라이스 5 (위험도 판정)**

```
chore/be/seed-guarantee-criteria         기준 테이블 적재
feature/be/RISK-02-negative-equity       깡통전세 판정
feature/be/RISK-05-guarantee-judgment    보증보험 가입 판정
feature/be/RISK-01-grade-judgment        등급 판정
feature/fe/RISK-01-grade-badge           등급 표시 화면
```

**인프라 슬라이스 (4~7주차)**

```
feature/infra/INF-01-app-redundancy      앱 이중화
feature/infra/INF-02-rolling-deploy      슬롯 순차 교체 배포
feature/infra/INF-03-db-replication      DB 복제
feature/infra/INF-04-backup-pitr         백업과 시점 복구
feature/infra/INF-06-load-harness        부하·장애 주입 환경
feature/infra/INF-05-observability       관측 스택 기동
```

스키마 작업은 기능ID가 없으므로 `chore/be/init-schema` 형태를 쓴다.

---

## 작업 흐름

1. 이슈 생성
2. `develop`에서 작업 브랜치 생성

   ```bash
   git switch develop
   git pull origin develop
   git switch -c feature/be/RISK-05-guarantee-judgment
   ```

3. 작업, 커밋, push
4. PR 생성 (`develop` 대상)
5. **CI 통과 후** merge
6. 브랜치 삭제
   - 원격: GitHub의 Delete branch
   - 로컬: `git branch -d feature/be/RISK-05-guarantee-judgment`

---

## Merge 방식

| 방향 | 방식 | 이유 |
| --- | --- | --- |
| `feature/*` · `fix/*` · `chore/*` · `docs/*` → `develop` | Squash merge | 작업 중 커밋을 하나로 정리해 develop 이력을 읽기 쉽게 유지 |
| `develop` → `main` | Merge commit | 배포 시점을 이력에 남긴다 |
| `hotfix/*` → `main` · `develop` | Squash merge | 수정 내용을 커밋 하나로 명확히 남긴다 |

Squash merge 시 커밋 메시지는 `docs/git/commit.md` 형식으로 정리한다. **브랜치의 scope와 squash 커밋의 scope를 일치시킨다.**

- `feature/infra/INF-02-rolling-deploy` → `feat(infra): 슬롯 순차 교체 배포 스크립트 추가 (INF-02)`

---

## Hotfix 흐름

배포된 `main`에서 오류가 발견되면 `develop`을 거치지 않고 `main`에서 분기한다. `develop`에는 아직 배포되지 않은 작업이 섞여 있어 경유하면 수정만 골라 배포할 수 없다.

```
main ──●──────────────●──► 재배포
        \            ▲
         hotfix/*    │
                     │
develop ◄────────────┘ 동일 수정 반영
```

1. `main`에서 `hotfix/*` 생성
2. 수정 후 `main`으로 PR, CI 통과 후 merge → 재배포
3. **`develop`으로도 PR을 만들어 merge한다.** 빠뜨리면 다음 배포에서 같은 버그가 되살아난다
4. 브랜치 삭제

**네이밍** — `hotfix/<scope>/<기능ID>-<수정 내용>`

```
hotfix/be/RISK-01-grade-null
hotfix/fe/PROP-02-marker-overflow
hotfix/infra/INF-01-health-path
```

커밋 type은 `fix`를 쓴다.

**인프라 hotfix는 별도로 유의한다.** 설정 파일 변경은 컴파일도 테스트도 되지 않으므로, CI만으로는 검증되지 않는다. `docs/git/commit.md`에 따라 `nginx -t`·`promtool check rules` 같은 확인 결과를 body에 남긴다.

---

## 브랜치 최신화

작업이 길어져 `develop`과 벌어지면 merge로 따라잡는다.

```bash
git switch feature/be/내-브랜치
git fetch origin
git merge origin/develop
```

rebase는 사용하지 않는다. 이력 조작 중 실수가 나면 복구가 번거롭다.

---

## 브랜치 보호 규칙

`main`과 `develop`에 공통 적용한다.

- PR 없이 push 금지
- CI status check 통과 필수
- force push 금지

**승인 인원은 두지 않는다.** 1인 개발이라 리뷰어가 없으므로 승인 규칙을 걸면 아무것도 merge할 수 없다. 리뷰어 역할은 CI와 code-reviewer 에이전트가 대신한다.

- CI가 유일한 게이트이므로 **테스트 실패 시 merge가 차단되도록** 반드시 설정한다.
- PR을 만드는 이유는 승인이 아니라 CI 실행과 변경 이력 정리다.