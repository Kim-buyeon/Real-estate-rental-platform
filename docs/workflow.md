# 작업 흐름

> 이슈 → 브랜치 → 구현 → 커밋 → PR → merge
> ※ 본 문서는 **순서와 수단**만 다룬다. 각 단계의 규칙은 근거 문서가 갖는다.

## 1. 원칙

- **이슈에서 시작한다.** 이슈 없이 브랜치를 파지 않는다.
- **한 번에 하나만 연다.** 기능 하나를 끝내고 이슈를 닫은 뒤 다음 이슈를 만든다. 슬라이스의 이슈를 미리 등록하지 않는다.
- **테스트는 부가 작업이 아니라 절차의 일부다.** 리뷰어가 없으므로 테스트가 유일한 확인 수단이다.
- **차기 범위 기능은 착수하지 않고 범위 밖임을 알린다.** 판단 근거는 `docs/features/overview.md`.
- **`main`·`develop`에 직접 push하지 않는다.** PR만 사용한다.

---

## 2. 기능 작업 (USER · PROP · RISK · LOAN · NOTI · ADMIN)

| # | 단계 | 수단 | 근거 문서 |
| --- | --- | --- | --- |
| 1 | 읽을 문서를 고른다 | `slice-start` | — |
| 2 | 이슈를 만든다 | — | `docs/git/issue.md` |
| 3 | `develop`에서 브랜치를 딴다 | — | `docs/git/branch.md` |
| 4 | 구현한다 | `backend-dev` · `frontend-dev` | `docs/conventions.md` · `docs/architecture/` |
| 5 | 테스트를 작성한다 | `test-engineer` / 판정 로직이면 `add-judgment` | `docs/architecture/testing.md` |
| 6 | 커밋한다 | — | `docs/git/commit.md` |
| 7 | 검토를 받는다 | `code-reviewer` | — |
| 8 | PR을 만든다 (`develop` 대상, `closes #N`) | — | `docs/git/pull-request.md` |
| 9 | CI 통과 후 squash merge, 브랜치 삭제 | — | `docs/git/branch.md` |

**커밋 전에 `git-check`로 확인한다.** 메시지 규약, 커밋 단위, 함께 커밋해야 하는 짝.

---

## 3. 인프라 작업 (INF-01 ~ 06)

인프라도 2장의 순서를 따른다. 다만 **CI가 검증하지 못하므로 커밋 body의 확인 명령 결과가 게이트이고, 함께 커밋해야 하는 짝이 다르다.** 둘 다 `docs/git/commit.md`.

구성·배포·복제·복구는 `infra-builder`, 관측은 `monitoring-engineer`, 부하 시험과 장애 주입은 `load-tester`·`chaos-runner`가 맡는다.

---

## 4. 동결 이후

| 시점 | 이후 하지 않는 것 |
| --- | --- |
| 9/16 | 기능 추가와 화면 작업 |
| 9/23 | 기능 코드 수정 (성능·복원력 수정은 허용) |

미완 기능은 다음 구간으로 넘기지 않고 버린다. 날짜로 지킨다.