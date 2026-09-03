# 전월세 부동산 금융 플랫폼

서울시 전월세 매물을 지도에서 탐색하고, 등기·건축물대장·시세·보증보험 기준을 대조해 **전세사기 위험도를 3단계로 판정한다.** 안전 판정을 받은 매물에는 대출 한도를 함께 제시하고, 관심 매물의 등급이 바뀌면 알린다.

- **작업 순서는 `docs/workflow.md`를 따른다.** 무엇부터 하고 어느 문서를 읽을지가 거기 있다.
- **브랜치·커밋·이슈·PR 규칙은 `docs/git/`을 따른다.**
- **네이밍과 코딩 규칙은 `docs/conventions.md`를 따른다.**

셋 다 작업 착수 전에 읽는다.

## 문서를 고칠 때

- **한 문서는 자기가 결정하는 것만 담는다.** 다른 문서가 정한 내용은 옮겨 적지 않고 참조한다. 두 곳에 적으면 한쪽만 고쳐진다.
- **확정되지 않은 값은 미확정으로 남긴다.** 그럴듯한 숫자로 채우지 않고 언제 확정되는지를 적는다.
- **문서를 고쳤으면 그 문서를 참조하는 쪽도 함께 본다.** 숫자·절 번호·경로가 다른 문서에 인용되어 있다.

## 프로젝트 구조

```
.
├── backend/              Spring Boot 4.1 · Java 21 — 내부 구조는 backend/CLAUDE.md
├── frontend/             React · TypeScript · Vite — 내부 구조는 frontend/CLAUDE.md
│
├── docs/                 설계 문서
│   ├── api/                요청·응답 명세. 공통 규약 + 영역별
│   ├── architecture/       구현 방침. 어떻게 만드는가
│   ├── features/           영역별 기능 정의. 무엇을 만드는가
│   ├── git/                브랜치 · 커밋 · 이슈 · PR 규칙
│   ├── infra/              노드 구성 · 관측 · 부하 · 시험 · 운영 절차
│   ├── business-logic.md   판정 산식과 임계값
│   ├── conventions.md      네이밍 · 도메인 용어 · 코딩 규칙
│   ├── roadmap.md          주차별 일정과 판정 지점
│   └── workflow.md         작업 순서와 각 단계의 수단
│
├── infra/                운영 구성. 서버에 반영되는 설정과 스크립트
│   ├── nginx/              요청 분산 · 점검 모드 · TLS
│   ├── prometheus/         스크레이프 설정 · 알림 규칙
│   ├── alertmanager/       라우팅 · 수신자
│   ├── promtail/           로그 수집
│   └── grafana/            대시보드 정의
│
├── chaos-harness/        시험 실행. 부하 생성 노드에서 구동
│   ├── load/               k6 프로파일과 토큰 풀
│   ├── scenarios/          장애 주입·복구 스크립트
│   └── report/             지표 수집과 결과서 생성
│
├── .github/
│   ├── workflows/          빌드·테스트 · 배포 · 시험 실행
│   └── ISSUE_TEMPLATE/     이슈 템플릿
│
├── .claude/
│   ├── agents/             역할별 에이전트 정의
│   └── skills/             반복 작업 절차
│
├── docker-compose.yml    로컬 구성. PostgreSQL · Redis · 관측 스택
├── .env.example          환경 변수 목록. 값 없음, 커밋 대상
├── .env                  실제 값. 커밋하지 않음
├── .gitignore
└── CLAUDE.md             본 문서
```