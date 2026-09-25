# 전월세 부동산 금융 플랫폼 — 운영 절차서

> 배포 · 복구 · 점검 절차
> Runbook · Nginx 설정 · 배포 스크립트
> ※ 설계 근거는 `docs/features/infra.md`를, 서버 · 네트워크 · 계정 · 정기 작업의 설계는 `docs/infra/platform.md`를 따른다. 지표와 알림은 `docs/infra/observability.md`를 따른다 — 미작성, 관측 작업에서 쓴다.
> 작성 기준일 : 2026년 8월

## 1. 문서 목적

본 문서는 손으로 실행하는 절차만 담는다. 왜 그렇게 설계했는지는 `docs/features/infra.md`에 있으므로, 여기서는 무엇을 어떤 순서로 실행하는지에 집중한다.

장애 상황에서 읽는 문서이므로 판단이 필요한 지점과 판단 기준을 함께 표기한다. **절차서의 목적은 당황한 상태에서도 순서를 틀리지 않는 것이다.**

---

## 2. 일상 점검

| 주기 | 항목 |
|---|---|
| 일 1회 | 배치 실행 결과, 등급 변동 건수 이상 여부, 알림 발송 실패 건수, 디스크 사용률(전 노드 `df -h` — 데이터 볼륨 포함), **정기 작업 결과**(`systemctl list-timers` · `journalctl -u <작업> --since yesterday` — 9.3절) |
| 주 1회 | 전체 백업 성공 여부, NAS의 논리 백업 · S3 사본 생성 여부(9.3절), 복제 지연 추이, 슬로우 쿼리 상위 10건, 캐시 적중률 추이 |
| 월 1회 | 커널 · 보안 갱신 반영을 위한 계획 재부팅(한 노드씩 — 노드별 영향과 AL2023의 갱신 명령은 표 아래), 계정 목록 대조(전 노드 동일 — 9.2절), 백업 복원 검증(물리 · 논리 각각), 가용성 목표 달성률 집계(`docs/infra/system.md` 5.1절), 용량 추이 대비 증설 시점 판단, 외부 API 호출량·비용 집계, **밖에서 본 열린 포트 확인**(아래) |
| 분기 1회 | 페일오버·페일백 시험, PITR 시험, 베이스 이미지 갱신 후 재스캔(3.1절), 절차서 갱신 |

**열린 포트는 밖에서 본다.** 노드 안에서 `ss`로 보는 것과 인터넷에서 닿는 것은 다르다 — Docker가 게시한 포트는 호스트 방화벽을 우회하므로(설계서 3.3) 루프백 바인딩이 실제로 먹는지는 외부에서 두드려야 판정된다. 시스템 구성서 4장의 허용 표와 대조해 **표에 없는 포트가 열려 있으면 그것이 결함이다.** 특히 데이터 저장소(5432 · 6379)가 닿으면 즉시 조치한다. 운영자 접속(22)의 출발지가 표대로 좁혀져 있는지도 함께 본다.

**월 주기로 둔 이유는 1차 배포 구간 때문이다.** 그 구간은 호스트 방화벽 없이 두 겹으로 돈다(설계서 3.3). Rocky 이전까지가 짧아 분기 주기로는 그 안에서 한 번도 돌지 않고 끝날 수 있다 — 두 겹뿐인 동안을 덮으려고 만든 확인이 그 동안을 덮지 못한다. 세 겹이 갖춰진 뒤에 주기를 다시 본다.

**AL2023 노드는 재부팅 전에 갱신을 사람이 올린다.** `dnf-automatic`이 새 보안 갱신을 받지 못하기 때문이다(설계서 8장 패키지 갱신 행). `sudo dnf check-update --releasever=latest --security`로 대상을 보고, 그 시점의 날짜 박은 버전(`dnf check-release-update`가 알려 준다)으로 `sudo dnf upgrade --releasever=<버전>`을 실행한 뒤 재부팅한다. `latest`를 그대로 쓰지 않는 것은 같은 버전을 다른 노드 · 다음 노드에 다시 밟을 수 있게 하려는 것이다. Docker · containerd가 함께 올라오면 기동 뒤 배포 확인(3장)을 빠뜨리지 않는다.

**계획 재부팅의 노드별 영향** — 서비스가 끊기는 노드는 트래픽이 낮은 시간대에 미리 알리고 한다.

| 노드 | 재부팅 중 | 방법 |
|---|---|---|
| APP-01 | **서비스 전체 중단**(단일 노드 — 두 슬롯 · Nginx가 함께 멈춘다, 시스템 구성서 3장). **실측 — 부팅 후 전 컨테이너 정상 88초**(2026-09-23). 화면은 10초쯤에 뜨지만 그때 API 는 아직 실패한다 — **복귀 판정은 API 로 한다**(71초). 2026-09-25 재부팅(SELinux enforcing 전환 확인)은 명령에서 API 첫 200까지 약 93초(9.1) | 공지 후 저트래픽 시간대. 기동 뒤 배포 확인(3장)과 같은 확인 |
| DB-01 | **전 기능 중단**(시스템 구성서 5.2 Primary 정지) | 점검 모드(4.2)로 쓰기를 막고 재부팅, 기동 뒤 해제 |
| DB-02 · NAS-01 · NAT-01 | 영향 없음(복제 · 백업 · 갱신만 잠시 멈춘다) | 그대로 재부팅. DB-02는 기동 뒤 복제 재개 확인 |

**NAS가 멈추면 NFS 경로를 건드리는 명령이 멈춘다**(기본 hard 마운트). 일일 점검의 디스크 확인은 `df -h -x nfs4`로 로컬 볼륨만 보고, NAS는 NAS-01에서 직접 본다.

**알림이 아직 없으므로 점검은 사람이 확인하는 것이다**(INF-05 — 지표 · 로그 수집은 있고 알림 규칙은 미구현). 위 표의 확인을 거르면 디스크가 차거나 백업이 멈춰도 알 수 없다. 외부 경로 감시(HetrixTools) 일시정지 규칙은 관측을 세울 때 되살린다.

---

## 3. 배포 절차

### 3.1 실행 규칙

설계 근거는 `docs/features/infra.md` INF-02에 있다. 실행 시 반드시 지킬 규칙은 다음과 같다.

| 규칙 | 이유 |
|---|---|
| **reload만 사용한다. restart 금지** | restart는 모든 연결을 끊는다. SSE 알림 연결이 전 사용자에게서 동시에 끊긴다 |
| **`nginx -t`를 reload 전에 항상 실행** | 설정을 스크립트로 수정하므로 치환 실패 가능성이 있다 |
| **Nginx 설정 변경은 체크아웃 → `nginx -t` → reload로 반영한다. 진입 파일(`infra/nginx/entry.conf`)만 예외다** | `conf.d/` · `main/`은 폴더째 마운트해 `git checkout`이 파일을 새로 만들어도 이름으로 다시 찾는다. 진입 파일은 파일 단위 마운트라 바꾸면 컨테이너가 옛 파일을 계속 보므로 재생성이 필요하다(4.1) |
| **수집기 설정(`infra/prometheus/agent.yml`) 변경은 체크아웃 → `docker compose up -d --no-deps --force-recreate prom-agent`로 반영한다** | 저장소 파일은 자리표시자가 든 틀이고 기동 셸이 환경 변수로 채운 사본을 tmpfs에 써서 그것으로 뜬다(운영 Compose 주석). 정의가 그대로면 `up -d`는 재생성하지 않아 **옛 대상으로 조용히 계속 돈다** — #209에서 실측. 재생성하는 수십 초 동안 원격 쓰기가 빈다. 반영 뒤 노드에서 `curl -s 127.0.0.1:9090/api/v1/targets`로 전 대상 `up`을 본다(#209 · #211) |
| **`down` 플래그만 토글한다** | 서버 목록 자체를 바꾸면 실패 시 설정과 컨테이너 상태가 어긋난다 |
| **배포 착수 전에 `upstream.conf`가 추적 상태와 같은지 확인하고, 다르면 중단한다** | 위 두 규칙이 부딪히는 자리다. 실패로 멈춘 배포는 슬롯을 `down`으로 남기는데 그 상태에서 체크아웃이 돌면 **죽은 슬롯이 upstream에 되살아난다.** 배포 스크립트가 착수 단계에서 한 번만 본다(3.2) |
| **제외 → drain → 교체 → 확인 → 복귀 순서 고정** | 컨테이너를 먼저 정지하면 그 사이 요청이 전부 실패한다 |
| **실패 시 해당 슬롯을 down으로 유지하고 중단** | 남은 슬롯이 구버전으로 계속 서비스하므로 별도 롤백이 불필요하다 |
| **첫 슬롯은 복귀 전에 관찰한다** | 워밍업 60초 뒤 교체한 슬롯을 직접 두드려(3.3) 통과해야 upstream에 넣는다. 관측이 아직 없어 지표 대신 판정 요청을 스스로 만든다 |
| **화면(web)은 두 슬롯이 끝난 뒤 교체한다** | 중간에 멈추면 신버전 화면이 구버전 API를 부른다 |
| **이전 이미지 태그 3개 보존** | 배포 완료 후 문제가 발견된 경우의 롤백 경로 |
| **배포 전 이미지 취약점 스캔** | 취약한 이미지를 올리면 발견할 때까지 노출된 상태로 운영된다 |

**취약점 스캔** — Trivy로 컨테이너 이미지의 OS 패키지와 Java 의존성을 한 번에 검사한다. CRITICAL이 남아 있으면 배포를 중단하고, HIGH는 기록만 남기고 진행한다. 1인 운영에서 HIGH까지 차단하면 배포가 멈춘 채로 시간이 흐른다.

**스캔은 이미지를 올리기 전에 CI가 한다** — `.github/workflows/image.yml`. CRITICAL이면 이미지를 GHCR에 올리지 않으므로 서버가 받을 이미지가 없고 배포가 성립하지 않는다. HIGH · CRITICAL 목록은 워크플로 산출물(`trivy-backend` · `trivy-frontend`)로 남는다. PR에서는 빌드 · 스캔만 하고 올리지 않는다.

**워크플로의 서드파티 액션은 커밋 SHA로 고정한다.** 2026-03 trivy-action 태그 76개가 탈취돼 CI 비밀값을 빼가는 코드로 바뀐 사고가 있었다(GHSA-69fq-xp46-6x23). 태그는 다시 가리킬 수 있지만 SHA는 바꿀 수 없다.

이미지를 올릴 때마다 실행하므로 별도 점검 주기를 두지 않는다. 다만 코드가 바뀌지 않아도 새 취약점은 계속 공개되므로, 분기 1회 베이스 이미지를 갱신해 다시 빌드하고 스캔한다.

마이그레이션은 하위 호환이어야 한다. 컬럼 삭제·이름 변경·NOT NULL 추가는 단일 배포에서 수행하지 않고 세 단계(추가 → 양쪽 기록 → 제거)로 분리한다.

### 3.2 배포 스크립트

**전제** — GitHub Actions가 이미지를 빌드해 GHCR에 커밋 해시 태그로 올린다(`ghcr.io/kim-buyeon/real-estate-rental-platform/backend:<해시>` · `.../frontend:<해시>`). `develop` · `main`에 push되면 올라간다.

**시크릿** — DB 비밀번호와 외부 API 키는 서버의 **`infra/.env`** 에 두고 `docker compose`가 컨테이너에 주입한다(이름은 루트 `.env.example`과 같다). 파일 권한은 소유자만 읽도록 제한하고 저장소에는 커밋하지 않는다. 백업 복호화 키만 이 파일과 분리해 보관한다. **`.env`에는 그 노드의 프로필(`COMPOSE_PROFILES`)이 있어야 한다** — 운영 Compose의 서비스가 전부 프로필에 묶여 있어 비면 아무것도 뜨지 않는다. 옛 단일 노드는 `app,db,standby`, 3노드는 APP-01 `app` · DB-01 `db` · DB-02 `standby`(시스템 구성서 4장 · 9.5).

스크립트는 `infra/deploy.sh`다. `infra/`에서 실행한다.

```bash
bash deploy.sh <커밋 해시>              # 일상 배포
bash deploy.sh <이전 해시> --rollback   # 롤백 — 관찰을 건너뛴다(3.4)
```

| 단계 | 하는 일 | 실패하면 |
|---|---|---|
| 드리프트 가드 | `upstream.conf`가 추적 상태와 같은지 `git status --porcelain`으로 본다 | 다르면 `down`으로 남은 슬롯을 이름으로 짚고 복구 경로를 출력한 뒤, 아무것도 바꾸지 않은 채 멈춘다 |
| 이미지 확인 | 두 이미지가 로컬에 없으면 받는다 | 아무것도 바꾸지 않은 채 멈춘다 |
| 슬롯마다(app-2 → app-1) | `down` 토글 → drain 30초 → 교체(`--no-deps --force-recreate`) → readiness 최대 120초 | 그 슬롯을 `down`으로 둔 채 멈춘다. 남은 슬롯이 구버전으로 서비스한다 |
| 첫 슬롯만 | 워밍업 60초 → `smoke.sh`(3.3) → 통과해야 복귀 | 같다 |
| 화면 | 두 슬롯이 끝난 뒤 web 교체 | — |
| 기록 | `.env`의 `APP_IMAGE` · `WEB_IMAGE`를 새 태그로 | 중간에 멈추면 기록하지 않는다 — `.env`는 구버전을 가리키므로 이후 `docker compose up`이 남은 슬롯을 바꾸지 않는다 |
| 정리 | 최근 태그 3개(지금 것 포함)를 남기고 지운다 | — |

- `down` 토글은 이미 `down`인 줄을 건드리지 않는다 — 실패로 멈춘 슬롯을 롤백으로 다시 돌릴 때 `down down`이 되지 않게 한다.
- `DRAIN` · `WARMUP` · `REGISTRY` 환경 변수로 기본값을 덮을 수 있다(로컬 시험용).

**드리프트 가드가 걸렸을 때** — 배포가 실패로 멈춘 뒤 그대로 다시 배포하는 경우다. 순서대로 밟는다.

| 순서 | 하는 일 |
|---|---|
| 1 | 출력에서 `down`으로 남은 슬롯 이름과 차이를 확인한다. 직전 배포가 왜 멈췄는지 먼저 안다 |
| 2 | 원인을 고친 뒤 `bash deploy.sh <직전 태그> --rollback`으로 그 슬롯을 되살린다. 복귀하면서 `down`이 풀려 파일이 추적 상태로 돌아온다 |
| 3 | 되살릴 수 없으면 그 슬롯을 정지한 채 두고 `git checkout -- infra/nginx/conf.d/upstream.conf` → `nginx -t` → reload로 되돌린다. **컨테이너가 죽은 채 upstream에 넣지 않는다** |
| 4 | 절차서가 다루지 않는 상황이라 사람이 판단해 넘어가야 하면 `SKIP_DRIFT_CHECK=1`로 가드를 끄고 실행한다(기본은 켜짐) |

- 가드는 **루프 시작 전 한 번만** 돈다. 배포 도중에는 스크립트 자신이 파일을 고치므로 다시 검사하지 않는다.
- `git`이 없거나 저장소 밖에서 실행하면 가드를 건너뛰고 **건너뛰었다는 사실을 출력한다.** 조용히 통과하지 않는다.
- 배포가 정상으로 끝나면 같은 파일을 한 번 더 보고 차이가 남아 있으면 **경고만** 남긴다 — 그 시점에는 서비스가 이미 복구된 상태라 실패로 만들지 않는다.

### 3.3 관찰 판정 (`smoke.sh`)

**관측(INF-05)이 아직 없어 지표를 조회하지 않는다.** 2026-09-19에 차기 범위로 뺐다가 2026-09-23에 범위로 돌아왔다 — 관측을 세우면 이 절을 다시 본다. 대신 교체한 첫 슬롯을 **upstream에 넣기 전에** 직접 두드린다. 신버전이 사용자 요청을 받기 전에 걸러진다.

```bash
bash smoke.sh <포트>    # 통과 0, 이상 1
```

| 항목 | 값 | 이유 |
|---|---|---|
| 요청 | 대표 조회 경로 2개(`/api/properties/district-counts` · `/api/properties?size=20`)를 번갈아 60건 | 1등급 경로(시스템 구성서 5.1)의 대표 조회 |
| 기준 | 실패(5xx · 연결 실패) 1% 미만, p95 1초 미만 | 이전 지표 판정과 같은 값 |
| 대기 | 복귀 전 워밍업 60초 | 방금 기동한 JVM은 첫 1분이 JIT 컴파일로 느리다 |

**표본이 없어 판정을 생략하는 경우가 없다.** 판정 요청을 스스로 만들기 때문이다. 대신 **실제 사용자 트래픽 아래의 신버전은 보지 못한다** — 50:50 구간의 지표 비교는 관측을 세울 때 되살린다.

### 3.4 이미지 태그 보존

배포가 끝난 뒤에 문제가 발견되는 경우가 있다. 관찰 구간을 넘긴 뒤에 드러나는 결함이 그렇다.

- 이미지 태그는 커밋 해시로 부여하고 **최근 3개를 보존**한다. `latest`만 쓰면 되돌아갈 지점이 없다
- 롤백은 대상 태그를 지정해 같은 스크립트를 다시 실행하는 것으로 갈음한다. **이때 관찰 단계는 건너뛴다** — 되돌아갈 대상이 이미 검증된 버전이기 때문이다. 소요는 약 3분이다 (drain 30초 × 2 + 기동·확인)
- 스키마 마이그레이션이 포함된 배포는 롤백해도 스키마가 되돌아가지 않는다. 하위 호환 규칙(3.1절)을 지켰다면 구버전이 신 스키마에서 동작하므로 문제가 없다. **이것이 하위 호환을 강제하는 실질적 이유다**

## 4. Nginx 설정

### 4.1 upstream 정의

슬롯 목록을 별도 파일로 분리해 배포 스크립트가 이 파일만 수정하게 한다. 서버 항목은 항상 두 개가 상주하며 `down` 플래그만 토글된다.

설정 파일은 `infra/nginx/conf.d/upstream.conf`다. 앱 슬롯 두 개(`app` — 127.0.0.1:8081 · 8082, `max_fails=3 fail_timeout=10s`, `keepalive 32`)와 정적 화면 하나(`web` — 127.0.0.1:8090)를 둔다. **배포 스크립트는 `app`의 `down` 플래그만 토글한다.**

**주소를 컨테이너 이름이 아니라 루프백 IP로 고정한다.** Nginx는 upstream 호스트명을 설정 로드 시점에 한 번 해석하므로, 컨테이너 이름을 쓰면 컨테이너 재생성으로 IP가 바뀌었을 때 옛 IP로 계속 전달하는 문제가 발생할 수 있다. 두 애플리케이션 프로세스가 동일 노드에 있으므로 루프백 고정으로 이 문제를 원천 차단한다.

**Nginx 컨테이너는 `network_mode: host`로 기동한다.** 브리지 네트워크에 두면 컨테이너 안의 `127.0.0.1`이 호스트가 아니라 컨테이너 자신을 가리켜 위 설정이 동작하지 않는다. 호스트 네트워크를 쓰면 애플리케이션이 노출한 루프백 포트에 그대로 접근할 수 있고, 컨테이너 이름 해석에 의존하지 않으므로 DNS 캐싱 문제도 발생하지 않는다.

Compose 정의는 `infra/docker-compose.yml`의 `nginx` 서비스다(배포 스크립트가 `infra/`에서 `-f` 없이 부르므로 이 이름이다). 진입 파일(`entry.conf` → 컨테이너의 `/etc/nginx/nginx.conf`, 파일 단위)과 `main/` · `conf.d/` 폴더를 마운트한다 — main 문맥 지시어(`worker_shutdown_timeout`)를 두려고 이미지 기본 설정 대신 `main/nginx.conf`를 쓰고, 진입 파일은 그것을 `include`만 한다. 본문을 폴더로 마운트하는 이유는 폴더는 새로 만들어진 파일도 이름으로 다시 찾기 때문이다 — 파일 단위 마운트는 `git checkout`이 파일을 새로 만들면 옛 파일을 계속 본다. 그래서 진입 파일은 바꾸지 않는다(바꾸면 재생성). 인증서 마운트는 도메인을 붙일 때 더한다.

### 4.2 서버 블록

설정 파일은 `infra/nginx/conf.d/default.conf`(서버 블록)와 `infra/nginx/main/nginx.conf`(main · http 문맥)다. **공인 IP + HTTP이므로 `listen 80`이다**(시스템 구성서 2.1절). 도메인을 붙이면 443 블록과 80 → 443 리다이렉트를 더한다.

| 경로 | 전달 | 요점 |
|---|---|---|
| `/actuator` | 404 | 관리 경로는 밖으로 열지 않는다. readiness는 배포 스크립트가 슬롯 포트로 직접 본다 |
| `/api/` | `app` | 연결 · 읽기 · 쓰기에 상한을 두고, 슬롯 하나가 죽으면 남은 슬롯으로 재시도하되 **재시도 횟수에도 상한**을 둔다 — 상한이 없으면 재시도가 요청 시간을 곱한다 |
| `= /api/notifications/stream` | `app` | SSE — 버퍼링을 끄고 **읽기 타임아웃만** 길게 둔다. 연결이 오래 열려 있는 것과 응답이 느린 것은 다르다. 접근 로그를 쿼리 없는 형식으로 남겨 일회용 티켓이 로그에 남지 않게 한다 |
| `/` | `web` | 정적 화면. 클라이언트 라우팅 fallback은 프론트 이미지가 한다 |

**값은 설정 파일이 갖는다.** 여기에 옮겨 적지 않는다 — 두 곳에 적으면 한쪽만 고쳐진다. 서버 문맥에는 이 밖에 요청 본문 크기 상한과 헤더 · 본문 수신 타임아웃이 있고, 업스트림으로 **요청 추적 ID를 전달**한다(접근 로그의 `rid`와 같은 값이며, 앱은 이것을 로그의 `traceId`로 쓴다). 슬롯이 둘이라 이 키가 없으면 접근 로그와 앱 로그를 이을 수단이 없다.

`worker_shutdown_timeout 30s`는 설정 본문 `main/nginx.conf`에 있다 — SSE로 인한 옛 worker 누적 방지. 응답 압축(gzip — JSON · JS · CSS · SVG, 1KB 이상)도 `main/nginx.conf` http 문맥에 있고 SSE(`text/event-stream`)는 대상이 아니다. 점검 모드(6장 2단계)는 `bash maintenance.sh on` · `off`다. 표시 파일(`nginx/maintenance/on`)이 있으면 서버 블록이 **location 매칭보다 먼저** 전 경로에 503을 돌려준다 — `/`만 막으면 더 긴 접두사인 `/api/`가 우선해 쓰기가 계속 들어온다. 파일 유무는 요청마다 보므로 reload가 필요 없다.

**점검 중 사용자가 보는 것**은 안내 화면이다. 503에 `Retry-After`와 `Cache-Control: no-store`가 함께 나간다 — 점검인지 장애인지 구분되지 않으면 사용자가 같은 요청을 반복한다. 화면은 외부 폰트 · 스크립트 · 이미지를 쓰지 않는다. 점검 중에는 화면(web) 슬롯도 신뢰할 수 없기 때문이다. **쓰기 요청(GET · HEAD 외)은 본문 없는 503을 받는다** — Nginx의 정적 파일 핸들러가 그 메서드를 거부해 안내 화면을 주려 하면 405가 되고, 그러면 「전 경로 503」이 깨진다.

**업스트림이 낸 503은 이 화면으로 바뀌지 않는다.** 점검과 장애를 섞지 않기 위해서다. 두 슬롯이 모두 죽으면 Nginx는 502를 내므로 이 경우도 점검 화면이 아니다 — 점검 화면이 보이면 그것은 사람이 켠 것이다.

`proxy_next_upstream`이 실질적인 무손실 장치다. 한 슬롯이 죽어 502를 반환하면 Nginx가 동일 요청을 다른 슬롯으로 재시도한다. **다만 POST 등 비멱등 요청은 기본적으로 재시도하지 않는다**(중복 처리 방지). 따라서 "요청 손실 0건"은 조회 요청에 대한 서술이며, 쓰기 요청은 극소수 실패할 수 있다. 장애 시험 결과서에는 이 구분을 그대로 기록한다.

---

## 5. 백업 및 시점 복구

논리적 손상(잘못된 UPDATE·DELETE, 배치의 오적재)은 복제로 복구되지 않는다. standby에도 동일하게 복제되기 때문이다. 이 경우 PITR을 사용한다. 백업 정책은 `docs/features/infra.md` INF-04를 따른다.

```
1. 손상 발생 시각 T 확정 (감사 로그 · 배치 실행 이력 · 애플리케이션 로그로 특정)
2. 신규 인스턴스에 T 직전의 전체 백업 복원
3. recovery_target_time = T - 1초 로 설정
4. WAL 아카이브 재생 후 복구 완료 확인
5. 손상 대상 테이블만 추출해 운영 DB에 반영 (전체 교체가 아닌 부분 복구 우선)
6. 원인 제거 전까지 해당 배치를 비활성화
```

**NAS의 논리 백업(`pg_dump -Fc`, INF-08)은 하루 한 번이다** — 되살리면 최대 약 하루 전 상태다. 그래서 테이블 단위 복원에 쓰는 것은 **그 테이블이 백업 뒤 바뀌지 않았을 때**(기준 데이터 · 적재 뒤 고정된 데이터)와 다른 버전 · 서버로 옮길 때뿐이다. 그 밖의 손상은 위 순서대로 PITR로 직전 시각을 복원한 뒤 필요한 테이블만 추출한다 — 1등급 RPO 5분(시스템 구성서 5.1)은 물리 백업 + WAL이 지킨다. 논리 복원 순서는 9.3절.

RISK_ANALYSIS와 같이 재계산 가능한 데이터는 PITR 대신 **재분석 배치의 강제 재실행**이 더 빠르다. 복구 수단을 데이터 성격에 따라 구분한다.

**3노드의 정기 백업은 아직 옮기지 않았다**(2026-09-25) — 아래 논리 · 물리 백업과 WAL 아카이빙의 timer는 옛 노드에 있고, 옛 노드는 점검 모드라 **새 primary(DB-01)의 백업이 없는 구간이다.** DB-01의 WAL은 `archive_mode`로 로컬 `/var/backups/rental/wal`에 쌓인다. 백업 이전 이슈에서 푼다.

**논리 백업 정기 작업** — `infra/backup/pg-dump.sh`를 systemd timer(`rental-backup.timer`)가 부른다. 실행 시각은 서버 운영 기반 설계서 7.2가 정한다. 순서는 primary 확인 → `pg_dump -Fc` → 암호화 → 목적지 적재 → 보존 기간 지난 것 삭제이고, standby에서는 아무것도 하지 않고 끝난다. **왜 그렇게 두는지는 서버 운영 기반 설계서 5.1 · 7.2가 갖는다.** 여기에는 실행할 때 확인할 것만 적는다.

| 확인 | 내용 |
|---|---|
| 접속 | 호스트의 PostgreSQL 클라이언트가 `PGHOST` · `PGPORT` · `PGUSER` · `PGDATABASE`로 붙는다. 컨테이너 안의 `pg_dump`를 부르지 않는다 — 정기 작업은 `docker` 그룹이 아닌 `backup` 계정으로 돌고(설계서 6.2), DB 노드가 분리되면 `PGHOST`만 바뀐다 |
| 비밀번호 | `backup` 계정의 `.pgpass`나 유닛 `EnvironmentFile`의 `PGPASSWORD`로만 준다. 명령줄 인자로 넘기지 않는다 — `ps`에 그대로 보인다 |
| 평문이 남지 않는가 | 덤프를 파이프에서 곧바로 암호화한다. 중간 파일도 암호문이며 노드를 떠나기 전에 암호화가 끝난다 |
| 실패가 남는가 | 실패하면 0이 아닌 코드로 끝나 `journalctl -u rental-backup`에 남는다. 확인 항목은 9.3 |
| 값이 비어 있으면 | 접속 정보 · 암호화 키 경로가 없으면 지어내지 않고 실패한다 |

**목적지는 NFS 마운트(`/mnt/nas/backup`)다**(2026-09-25 · #227 — 그 전에는 로컬 `/var/backups/rental`). 노드가 하나라 NFS 공유가 **같은 노드 · 같은 디스크**다(9.3). 이 계정은 오브젝트 스토리지 쓰기 권한이 없다(#180). **그래서 노드가 통째로 사라지면 백업도 함께 사라진다** — 잘못된 UPDATE · 배치 오적재로부터는 되살리지만 노드 소실은 막지 못한다. 권한이 생기면 `BACKUP_S3_URI`에 값만 넣는다. 스크립트를 다시 쓰지 않는다.

**아직 정해지지 않은 것** — 보존 기간. 잠정값(7일)은 스크립트가 갖고, 확정 시점은 서버 운영 기반 설계서 5.4가 갖는다. 실행 시간 상한은 30분으로 확정했다(같은 설계서 5.3). 암호화 도구(`gpg`)와 `backup` 계정 UID · GID는 같은 설계서 5.1 · 6.2가 정했다. 호스트 클라이언트가 닿도록 운영 Compose가 PostgreSQL을 루프백(`127.0.0.1:5432`)에 게시한다(시스템 구성서 4장).

**WAL 연속 아카이빙** — 두 DB 서비스가 같은 인자로 `archive_mode=on` · `archive_timeout=300`(5분 강제 스위치)을 쓰고, 완료된 WAL 세그먼트를 호스트 `/var/backups/rental/wal`에 복사한다(#214). **1등급 RPO 5분을 지키는 것이 이 설정이다**(시스템 구성서 5.1). `archive_mode`는 재기동해야 바뀐다. standby는 복구 중에는 아카이브하지 않고 승격하면 이어서 한다 — 페일오버에 아카이빙 전환 단계가 없다.

| 확인 | 내용 |
|---|---|
| 아카이빙이 도는가 | primary에서 `SELECT archived_count, failed_count, last_archived_wal, last_failed_wal FROM pg_stat_archiver` — `failed_count`가 늘지 않는다 |
| 디렉터리 권한 | `/var/backups/rental/wal`은 `70:backup` `2770` — 컨테이너(uid 70)가 쓰고 `backup` 계정이 정리한다(삭제는 디렉터리 쓰기 권한만 필요). 세그먼트는 `0600` uid 70이라 `backup` 계정은 읽지 못한다 — PITR은 같은 uid로 도는 컨테이너가 읽는다. **디렉터리가 없으면 Docker가 root 소유로 만들어 아카이빙이 실패한다** — 먼저 만든다 |
| 실패하면 | `archive_command`가 실패한 세그먼트는 `pg_wal`에서 지워지지 않고 쌓인다. 디스크를 먼저 본다. **같은 이름이 이미 있고 내용이 다르면 스크립트가 1을 돌려 그 세그먼트에서 영구히 멈춘다** — PostgreSQL 로그에 `rental-archive-wal: … 가 이미 있고 내용이 다르다`. 두 서버가 같은 아카이브에 쓰고 있다는 뜻이다(리허설 컨테이너를 운영 인자로 띄운 경우 등). 덮어쓰지 말고 어느 쪽 파일인지 먼저 가린다 |

**물리 백업 정기 작업** — `infra/backup/pg-basebackup.sh`를 `rental-basebackup.timer`가 주 1회 부른다(시각은 서버 운영 기반 설계서 7.2). 순서는 primary 확인 → `pg_basebackup -Ft -z`(WAL 포함) → 최신 4개 보존 → 가장 오래 남긴 백업의 시작 WAL보다 앞선 아카이브를 `pg_archivecleanup`으로 정리. 접속 · 비밀번호 규칙은 위 논리 백업 표와 같고 역할만 다르다 — 복제 권한을 가진 전용 역할 `rental_basebackup`(`EnvironmentFile`은 `/etc/rental/basebackup.env`). 호스트 도구는 AL2023에서 `postgresql17-server`(`pg_basebackup`) · `postgresql17-contrib`(`pg_archivecleanup`)에 있다 — 설치해도 호스트의 `postgresql.service`는 켜지 않는다.

**로컬 단계에서는 암호화하지 않는다.** 데이터 볼륨과 같은 디스크 · 같은 노출이다. 노드 밖으로 내보낼 때 암호화한다 — 인프라 기술 스택 3장의 기준이 「전송 전 암호화」다(서버 운영 기반 설계서 7.2 「NAS → S3」 행도 같다). 논리 백업이 암호화하는 것은 목적지가 다른 노드(NAS)이기 때문이다.

**PITR 리허설** — 위 순서(1 ~ 4)를 운영 DB가 아닌 일회용 컨테이너에서 밟는다. 표식 행 A를 넣고 시각 T를 적은 뒤 표식 행 B를 넣고, 물리 백업을 새 데이터 디렉터리에 풀어 `restore_command = 'cp /archive/wal/%f %p'` · `recovery_target_time = T` · `recovery.signal`로 기동한다. A가 있고 B가 없으면 성공이다. **아카이브는 읽기 전용(`:ro`)으로 붙이고 `archive_mode`는 기본값(`off`)으로 띄운다** — 운영 인자 그대로 띄워 승격하면 새 타임라인의 `.history` · 세그먼트가 운영 아카이브에 섞이고, 나중에 운영 standby가 같은 타임라인 번호를 고르면 아카이빙이 영구 실패한다.

**리허설 실측**(APP-01 · t3.small · 2026-09-24 23:08 ~ 23:10, 트래픽 없는 시간, 표본 1회 · DB 36 MB) — 물리 백업 **3초 · 9,040 KiB**. 표식 A(23:09:15) → T(23:09:17.78) → 표식 B(23:09:19) 뒤 `pg_switch_wal()`로 B가 든 세그먼트를 아카이브에 넘기고, 같은 이미지의 일회용 컨테이너(`--memory 150m`)에 백업을 풀어 `recovery_target_action=pause`로 띄웠다. **풀기 + 재생 2.55초**, 로그 `recovery stopping before commit … 14:09:19.96+00` · `pausing at the end of recovery`. 판정 **리허설 `A` / 운영 `A,B`**, `property` 67,183건 양쪽 같음. 리허설 뒤 아카이브에 새 파일 없음. `pause`로 두면 승격하지 않아 새 타임라인이 생기지 않는다 — 확인만 하는 리허설은 이것으로 충분하다. **점검 구간**(`archive_mode`를 켜려고 primary 재생성) **7.03초**(#214).

---

## 6. 페일오버 · 페일백

> **3노드(2026-09-25 · #235)에서는 아래 명령의 대상이 바뀐다.** 이 장의 표는 같은 노드(옛 단일 노드) 기준으로 실측한 것이다. 3노드에서는 — 접속 대상 전환이 `DB_HOST=postgres-standby`가 아니라 **APP-01 `.env`의 `DB_HOST=10.20.20.10`**(DB-02 사설 IP), DB-02의 standby는 이미 자기 사설 IP 5432에 게시돼 있어 승격하면 그대로 받는다. 페일백의 구 primary 재구축은 DB-01에서 데이터 자리(`/srv/pgdata/data`)를 비우고 `.env`에 `PG_BOOTSTRAP_FROM=10.20.20.10`. **노드 간 명령과 소요는 페일오버 리허설에서 실측해 이 장을 고친다**

**지금 standby는 같은 노드의 컨테이너(`postgres-standby`)다**(시스템 구성서 2.2 · 3장). 아래 절차의 「primary」는 `postgres` 서비스, 「standby」는 `postgres-standby` 서비스로 읽는다. 노드가 통째로 멈추면 둘이 함께 멈추므로 1단계의 「SSH 접속 불가」 판정은 이 구성에서 성립하지 않는다 — **primary 컨테이너만 멈춘 경우의 절차 검증**이다. 명령은 로드맵 7주차 실행에서 이 절에 채운다(1장 원칙).

**복제가 붙어 있는지 먼저 본다** — primary에서 `SELECT client_addr, state, sync_state, replay_lag FROM pg_stat_replication`(`streaming` · `async`), `SELECT slot_name, active, wal_status FROM pg_replication_slots`(`active = t`). standby에서 `SELECT pg_is_in_recovery()`가 `t`.

**접속 대상 전환** — 앱의 접속 대상은 `infra/.env`의 `DB_HOST`다. 평시에는 두지 않는다(비우면 `postgres`). 6.1 6단계에서 `DB_HOST=postgres-standby`를 넣고 슬롯을 재생성하며, 페일백 7단계에서 그 줄을 지우고 다시 재생성한다(#199).

**standby 구성 순서** — APP-01에 2026-09-24 17:49 ~ 17:51에 실제로 밟은 순서다(#194). standby를 다시 만들 때(볼륨을 지운 재구축)는 4 · 5만 밟는다 — 역할과 슬롯은 primary에 남아 있다. 슬롯을 지웠으면 2의 슬롯 생성만 다시 한다.

| 순서 | 명령 | 확인 |
|---|---|---|
| 1 | `infra/.env`에 `POSTGRES_REPLICATION_USER=replicator` · `POSTGRES_REPLICATION_PASSWORD`(노드에서 `openssl rand -hex 24`로 만든 값, **화면에 찍지 않는다**). 역할 이름은 `infra/postgres/pg_hba.conf`의 복제 줄과 같아야 한다 | `grep -c '^POSTGRES_REPLICATION' .env` → 2 |
| 2 | primary에서 역할 · 슬롯 — `CREATE ROLE replicator REPLICATION LOGIN` → 비밀번호는 SQL 문에 쓰지 않는다 — `export RPW=$(sed -n 's/^POSTGRES_REPLICATION_PASSWORD=//p' .env)`로 `.env`에서 읽고 `docker compose exec -T -e RPW postgres psql …`(값 없이 `-e RPW`)에 `\getenv pw RPW` · `ALTER ROLE replicator PASSWORD :'pw'`로 넣은 뒤 `unset RPW`(값이 명령 인자 · 셸 이력에 남지 않는다) → `SELECT pg_create_physical_replication_slot('standby_1')` | `rolsuper = f` · `rolreplication = t`, 슬롯 `standby_1` `active = f` |
| 3 | **primary 재생성**(hba 파일 · 설정 반영) — `bash maintenance.sh on` → `docker compose up -d --no-deps postgres` → healthy · 두 슬롯 API 200 → `bash maintenance.sh off`. **실측 6.7초**(점검 모드 구간) | `SHOW hba_file` · `SHOW max_slot_wal_keep_size`가 운영 Compose(`x-postgres-command`)의 값과 같다 |
| 4 | `docker compose up -d --no-deps postgres-standby` — 빈 볼륨이면 스스로 `pg_basebackup`을 받는다. **실측 7초에 healthy**(DB 36 MB) | 로그에 `started streaming WAL from primary` |
| 5 | 복제 확인 — 위 「복제가 붙어 있는지」 질의 + primary에 시험 테이블 쓰기 → standby 조회 → 삭제 | `streaming` · `async` · 슬롯 `active = t` · standby `pg_is_in_recovery() = t` · 행이 도착 |

**첫 구성 실측**(t3.small · 2026-09-24 트래픽 없는 시간) — 재생 지연 0.02초, 매물 67,183건 양쪽 일치, standby 메모리 23 MiB, 노드 가용 376 MiB. hba를 저장소 파일로 옮긴 뒤 논리 백업(9.3) 수동 1회 성공.

### 6.1 페일오버 절차

Primary 장애 판정부터 서비스 정상화까지의 절차다. 각 단계에 예상 소요를 병기해 RTO 30분의 근거로 삼는다.

| 단계 | 작업 | 예상 소요 | 판단 기준 |
|---|---|---|---|
| 1 | 알림 수신 및 장애 확인 | 3분 | primary 헬스체크 3회 연속 실패 + SSH 접속 불가 |
| 2 | 애플리케이션을 점검 모드로 전환 (`bash maintenance.sh on` — 전 경로 503, 4.2절) | 2분 | 이중 기록 방지. 승격 전 필수 |
| 3 | 구 primary 격리 — 살아 있으면 정지한다(같은 노드: `docker compose stop postgres`). **정지만으로는 다음 `docker compose up -d`에서 다시 뜬다** — 페일백 전까지 Compose 전체를 올리는 명령(배포 포함)을 쓰지 않고 서비스를 지정해 `--no-deps`로만 다룬다 | 1분 | 스플릿 브레인 방지. 앱의 접속 대상이 바뀐 뒤에는 구 primary로 가는 쓰기가 없다 |
| 4 | standby의 복제 지연 확인 (`pg_last_wal_receive_lsn` · `pg_last_wal_replay_lsn`) | 2분 | 받은 것과 재생한 것이 같다. 지연이 크면 WAL 아카이브 추가 재생 |
| 5 | standby 승격 (`SELECT pg_promote(true, 60)` — `pg_ctl promote`와 같다) | 3분 | `pg_is_in_recovery()`가 `f`, 타임라인이 하나 는다 |
| 6 | 애플리케이션의 DB 접속 대상 전환 후 재기동 — 같은 노드: `.env`에 `DB_HOST=postgres-standby` → `docker compose up -d --no-deps app-1 app-2 postgres-exporter`(exporter도 `DB_HOST`를 따른다) | 5분 | 설정 변경 + 롤링 재기동 |
| 7 | 점검 모드 해제, 핵심 기능 확인 | 5분 | 로그인·매물 조회·위험도 조회 |
| 8 | 신규 standby 재구축 | 30분 | 서비스 정상화 이후 수행. RTO에 미포함 |
| 9 | 장애 보고서 작성 | — | 원인·조치·재발 방지 |
| **합계(1~6)** | | **20분 (추정)** | 판정 기준은 1등급 RTO 30분. 실측은 아래 |

**실측 — 같은 노드 리허설**(APP-01 t3.small · 2026-09-24 18:57, 트래픽 없는 시간 · #201). 1단계(판정)는 장애를 일부러 냈으므로 없다 — 실제 장애에서는 여기에 알림 · 판정 시간이 더해진다.

| 시각 | 단계 | 장애 주입부터 |
|---|---|---|
| 18:57:11.3 | 장애 주입 — primary 컨테이너 정지(3단계를 겸한다) | 0 |
| 18:57:16.9 | 2 점검 모드 | 5.6초 |
| 18:57:17.2 | 4 지연 확인 — 받은 LSN = 재생한 LSN(`0/501E980`) | 5.9초 |
| 18:57:17.4 | 5 승격 완료(명령 0.3초, timeline 2) | **6.2초** |
| 18:57:17.8 → 18:58:23.0 | 6 `.env`에 `DB_HOST=postgres-standby` → 두 슬롯 재생성 → readiness | **71.7초** — 대부분 JVM 기동 |
| 18:58:28.9 | 7 점검 모드 해제 · 외부 200 | **77.6초** |

추정 20분 대비 서비스 복귀 **77.6초**다. 차이는 사람의 판단 · 알림 시간이 빠진 데서 온다.

**2차 리허설**(같은 날 20:19 · #205 — 고친 절차 · exporter 포함) — 장애 → 승격 완료 **1.1초**, → 두 슬롯 readiness 63.6초, → 서비스 복귀 **69.7초**. 1차의 승격까지 6.2초는 대부분 장애 직후 확인 요청이 DB 연결 시간 초과를 기다린 시간이다(2차는 그 요청을 뺐다). **남은 시간의 대부분은 슬롯 재기동**이므로, 줄이려면 접속 대상을 재기동 없이 바꾸는 수단(앞단 프록시 · DNS 별칭)을 봐야 한다.

**승격 전 반드시 점검 모드로 전환한다.** 구 primary가 부분적으로 살아 있는 상태에서 standby를 승격하면 양쪽이 쓰기를 받는 스플릿 브레인이 발생한다. 자동 페일오버를 도입하지 않고 수동 승격을 채택한 이유가 여기에 있다. 감시 인력이 1인인 환경에서 자동 승격은 오탐 시 손상이 더 크다.

### 6.2 페일백 절차

페일오버가 끝난 상태는 **정상 구성이 아니다.** standby가 primary 역할을 하고 있고, standby는 원래 primary보다 낮은 사양으로 배치되어 있다. 서비스는 살아 있지만 처리 여력과 이중화가 모두 축소된 상태이므로, 원래 구성으로 되돌리는 절차를 함께 정의한다.

**페일백은 긴급 작업이 아니다.** 서비스가 이미 동작하고 있으므로 트래픽이 낮은 시간대에 계획해서 수행한다. 장애 직후의 급한 상태에서 곧바로 전환하면 두 번째 장애를 만든다.

| 단계 | 작업 | 판단 기준 |
|---|---|---|
| 1 | 구 primary의 장애 원인 제거 후 기동 | 원인이 특정되지 않았으면 재사용하지 않는다 |
| 2 | 구 primary를 현재 primary의 standby로 재구축 | 아래 재구축 수단 참조 |
| 3 | 복제 지연이 수렴할 때까지 대기 | `replay_lag` < 1초 |
| 4 | 전환 시각 결정 | 트래픽이 낮은 시간대. 즉시 수행하지 않는다 |
| 5 | 점검 모드 전환 | 6.1절과 동일. 스플릿 브레인 방지 |
| 6 | 현재 primary의 쓰기 중단 확인 후 구 primary 승격 | 승격 전 구 primary의 replay LSN이 현재 primary의 마지막 `pg_current_wal_lsn` **이상**인지 확인(같은 노드 순서 6행) |
| 7 | 애플리케이션 DB 접속 대상 전환 후 롤링 재기동 | 3장 배포 절차와 동일한 방식 |
| 8 | 점검 모드 해제, 반대편을 다시 standby로 재구축 | 원래 구성 복원 완료 |

**2단계의 재구축 수단을 먼저 판정한다.** 데이터 크기에 따라 소요가 크게 달라진다.

| 조건 | 수단 | 소요 |
|---|---|---|
| `wal_log_hints = on` 또는 데이터 체크섬 활성, 승격 이후 구 primary에 쓰기가 없었음 | `pg_rewind` | 분 단위 |
| 위 조건 불충족 | `pg_basebackup` 전체 재구축 | 데이터 크기에 비례 |

`pg_rewind`를 쓰려면 **장애가 나기 전에** 해당 설정이 켜져 있어야 한다. 장애 발생 후에는 선택할 수 없으므로 초기 구성 시점에 결정한다.

**`pg_rewind`가 「no rewind required」를 내면 끝난 것이 아니다.** 구 primary가 갈라지지 않고 **뒤처져만 있다**는 뜻이다 — 승격 뒤 새 primary에 쓰인 것은 아직 구 primary에 없다. 그대로 primary로 띄우면 그 쓰기를 잃는다. **되감기를 했든 안 했든 2 · 3단계 — 새 primary의 standby로 붙여 따라잡게 하는 것 — 를 건너뛰지 않는다.**

**검증은 되돌릴 수 없는 단계 앞의 멈춤 조건이다.** 6단계 승격 전 LSN(구 primary의 replay LSN이 현재 primary의 마지막 값 이상), 그리고 페일오버 중 새 primary에 써 둔 표식 행이 구 primary에 있는지를 본다. 하나라도 어긋나면 점검 모드를 유지한 채 멈춘다 — 서비스를 열거나 반대편 볼륨을 지우는 단계로 넘어가지 않는다.

**2026-09-24 리허설에서 이 두 규칙을 어겼다**(#201). 두 DB를 정상 종료한 뒤 `pg_rewind --source-pgdata`로 구 primary를 맞추려 했는데 결과가 「no rewind required」였고, 그대로 구 primary를 primary로 띄웠다. 승격된 쪽에 써 둔 시험 표식 테이블이 사라졌고, 이어서 standby 볼륨을 지워 되살릴 곳도 없앴다. **실제 데이터 손실은 없었다** — 그 구간 Nginx로 들어온 쓰기 요청 0건(확인용 GET 1건), DB에 쓰는 정기 작업 없음, 건수가 직전과 같다. 스크립트가 표식 행을 확인하고도 멈추지 않은 것이 원인이다.

**페일오버가 끝난 동안 논리 백업은 멈춘다.** 백업은 `postgres` 서비스가 루프백에 게시한 5432로 붙는데(5장) `postgres-standby`는 단일 노드에서 루프백 5433에 게시해 백업 경로(5432)에 없다. 그 상태를 오래 두지 않는다.

**같은 노드 페일백 순서**(#203) — 두 PostgreSQL 서비스는 같은 초기화 스크립트(`infra/postgres/bootstrap.sh`)를 쓴다. 볼륨이 비어 있고 `PG_BOOTSTRAP_FROM`이 있으면 그곳에서 `pg_basebackup`으로 받아 standby로 뜬다. 구 primary는 되감지 않고 **볼륨을 비워 새로 받는다** — 위 재구축 수단 표의 둘째 행이고, DB가 작아(36 MB) 수 초다. 각 단계는 위 표의 번호를 따른다.

| 순서 | 명령(`infra/`에서) | 멈춤 조건 — 어긋나면 다음으로 가지 않는다 |
|---|---|---|
| 2 앞 | **지우기 전 확인** — 새 primary(`postgres-standby`)에 페일오버 뒤 써 둔 표식 행이 있다. 논리 백업이 멈춰 있으므로 새 primary에서 `pg_dump -Fc` 한 벌을 받아 둔다 | 표식 행이 없거나 덤프가 실패하면 **여기서 멈춘다** — 다음 행이 되돌릴 수 없다 |
| 2 | `docker compose rm -sf postgres` → `docker volume rm rental-prod_pgdata` → `.env`에 `PG_BOOTSTRAP_FROM=postgres-standby` → `docker compose up -d --no-deps postgres` | `postgres`에서 `pg_is_in_recovery()` = `t` |
| 3 | 따라잡기 대기 | `postgres-standby`의 `pg_stat_replication`에 `streaming`, `replay_lag` < 1초. 표식 행이 `postgres`에서 보인다 |
| 5 | `bash maintenance.sh on` | — |
| 6 | `postgres-standby`에서 `SELECT pg_current_wal_lsn()` 기록 → `docker compose stop postgres-standby` → `postgres`에서 `pg_last_wal_replay_lsn()` 확인 → `SELECT pg_promote(true, 60)` | replay LSN이 기록한 값 **이상**이다. **같을 수는 없다** — 정상 종료가 종료 체크포인트 레코드를 하나 더 쓰고 standby가 그것까지 받는다(로컬 실측 종료 전 `0/5000160` → replay `0/5000210`). 승격 뒤 `pg_is_in_recovery()` = `f` |
| 7 | `.env`에서 `DB_HOST` · `PG_BOOTSTRAP_FROM` 줄 삭제 → `docker compose up -d --no-deps postgres app-1 app-2 postgres-exporter` → readiness. 점검 모드 안이라 두 슬롯을 한꺼번에 재생성한다 | `postgres`가 기존 데이터로 primary로 뜬다. 두 슬롯 API 200 |
| 8 | `bash maintenance.sh off` → `docker compose rm -sf postgres-standby` → `docker volume rm rental-prod_pgdata-standby` → 슬롯 `standby_1`이 없으면 만든다(6장 머리 「standby 구성 순서」 2) → `docker compose up -d --no-deps postgres-standby` → 표식 행 삭제 | `streaming` · `async` · 슬롯 active |

- 2단계의 `postgres`는 **슬롯 없이** 붙는다. 받을 때 원본(옛 standby)의 `postgresql.auto.conf`에서 `primary_slot_name='standby_1'`이 따라오는데, 초기화 스크립트가 슬롯 없이 받은 경우 그 줄을 지운다(#207). 2차 리허설(#205)은 이 처리 전이라 새 primary에 슬롯을 만들어 풀었다. 슬롯이 없으니 페일백 동안 새 primary가 WAL을 붙들어 주지 않는다 — 3단계의 `streaming` 확인이 그 구간을 짧게 둔다.
- 승격 뒤에도 `postgresql.auto.conf`에 `primary_conninfo`(비밀번호 없이 passfile 경로만)가 남는다. `standby.signal`이 없으면 쓰이지 않는다 — 그 줄로 standby라고 판단하지 않는다. 판단은 `pg_is_in_recovery()`로 한다.
- 이 순서는 로컬 Docker(#203)와 **노드(2026-09-24 20:20 ~ 20:24, #205)에서 밟았다.** 노드에서는 3단계 멈춤 조건(`streaming` 아님)에서 한 번 멈췄다 — 점검 모드를 켜기 전이라 서비스는 새 primary로 계속 돌았고, 새 primary에 슬롯을 만든 뒤 3단계부터 이어 갔다. 결과 — 받기 6.6초, 점검 모드 구간(5 → 8 해제) **78.6초**, LSN 마지막 `0/9000060` · replay `0/90000D8`, 반대편 재구축 약 10초에 `streaming`. **새 primary에 써 둔 표식 행이 원래 primary에 돌아왔다.**

**2단계에서 볼륨을 지우는 것이 되돌릴 수 없는 첫 단계다.** 그래서 그 앞에 「2 앞」 확인을 둔다(위 표).

**되돌리지 않는 선택지도 있다.** 승격된 노드를 계속 primary로 두고 구 primary를 standby로 붙이는 방식이다. 전환 작업이 한 번 줄어드는 대신, 두 노드의 사양이 뒤바뀐 채로 남으므로 사양을 맞추는 작업이 따로 필요하다. 어느 쪽을 택하든 **사양이 낮은 노드가 primary인 상태를 방치하지 않는다.**

승격된 노드가 목표 처리량을 감당하는지는 실측된 바 없다. 7주차 시나리오 3에서 페일오버 직후 상태의 처리량을 함께 기록하고, 감당하지 못하면 페일백을 계획이 아니라 즉시 조치로 재분류한다.

---

## 7. 장애 대응

### 7.1 대응 흐름

```
알림 수신
   │
   ├─ 사용자 영향 있음 ─┬─ 원인 즉시 특정 가능 → 조치 → 확인 → 보고서
   │                    └─ 특정 불가 → 점검 모드 전환 → 조사 → 조치 → 해제 → 보고서
   │
   └─ 사용자 영향 없음 ── 근무 시간 내 조사 → 조치 → 기록
```

**판단 기준은 하나다. 사용자에게 보이는가.** 복제 지연은 critical이지만 사용자 영향이 없으므로 점검 모드 전환 없이 조사한다. 5xx 급증은 즉시 조치 대상이다.

### 7.2 장애 보고서 양식

발생 시각 / 인지 시각 / 조치 완료 시각 / 영향 범위(기능·사용자 수·데이터) / 직접 원인 / 근본 원인 / 조치 내용 / 재발 방지 / 관측 개선 사항(이번 장애를 더 빨리 인지하려면 어떤 지표가 필요했는가).

마지막 항목이 중요하다. 장애마다 관측 카탈로그가 한 줄씩 늘어나는 구조를 만든다.

---

## 8. 월간 운영 보고

2장의 월 1회 점검 결과를 한 장으로 정리한다. 점검을 수행하고 기록하지 않으면 추이가 남지 않아 증설 시점을 판단할 근거가 사라진다.

`report/ops-YYYYMM.md`

| 절 | 내용 | 출처 |
|---|---|---|
| 가용성 | 등급별 달성률과 목표 대비 | `docs/infra/system.md` 5.1절 집계식 |
| 장애 | 발생 건수, 심각도별 분포, 평균 인지·복구 시간 | 7.2절 장애 보고서 |
| 변경 | 배포 횟수, 롤백 횟수와 사유 | 이미지 태그 이력(배포 스크립트 출력 · GHCR 태그). Grafana 배포 마커는 관측을 세우면 함께 |
| 용량 | 디스크 사용률 추이(노드별 데이터 볼륨 · NAS), 증설 판단 | 2장 일일 점검 기록 — 대시보드는 관측을 세우면 함께 |
| 복구 검증 | 백업 복원 검증 결과(물리 · 논리), 복원 소요 | 2장 월 1회 복원 검증 기록 · 9.3절 |
| 미결 | 이월된 조치 사항과 기한 | 전월 보고서 |

**마지막 절을 비워두지 않는다.** 조치하지 못한 항목이 다음 달로 넘어가는 것을 기록해야 방치와 보류가 구분된다.

---

## 9. 서버 운영 절차 (Rocky Linux)

서버 운영 기반(INF-07 ~ 09)의 손으로 밟는 순서다. **무엇을 왜 그렇게 두는지는 서버 운영 기반 설계서(`docs/infra/platform.md`)가 갖는다.** 명령은 첫 구축에서 실제로 실행하며 이 절에 채운다(1장 원칙) — 지금 명령까지 채워진 것은 9.1의 AL2023 OS 보안 기준 · 9.1의 Rocky 3노드 준비 · 9.2의 계정 추가 · 9.3의 논리 · 물리 백업 · NFS 공유이고 나머지는 단계와 확인 기준만 있다.

### 9.1 새 노드 준비

| 순서 | 작업 | 확인 |
|---|---|---|
| 1 | Rocky 9 공식 AMI로 인스턴스 생성 — 서브넷 · 보안 그룹은 시스템 구성서 4장 | 사설 노드에 공인 IP가 없다 |
| 2 | **인스턴스 메타데이터를 IMDSv2 required 로 잠근다**(설계서 3.3 「인스턴스 메타데이터」). **인스턴스 역할을 붙이기 전에 한다** | `describe-instances`에 `HttpTokens`가 `required`로 나온다 |
| 3 | 데이터 볼륨 부착 → XFS 생성 → UUID + `nofail`로 fstab → 재부팅. **볼륨은 암호화해서 만든다**(설계서 4.2) | 재부팅 뒤 마운트 유지. `describe-volumes`에 `Encrypted`가 참으로 나온다 |
| 4 | `backup`(고정 UID · GID)을 먼저, 그다음 운영자 개인 계정 · `deploy` 생성 — 순서가 노드마다 같아야 자동 할당 UID가 같아진다(9.2). `rocky`는 `authorized_keys` 제거 · 계정 만료 · `nologin` — `passwd -l`만으로는 공개키 로그인이 막히지 않는다(설계서 6.2) | `rocky`로 SSH 시도가 거부된다 |
| 5 | sshd — 공개키만 · root 금지 | 비밀번호 로그인 시도가 거부된다 |
| 6 | 시간대 `Asia/Seoul`, chrony에 169.254.169.123 | `chronyc sources`에 그 주소가 선택됨 |
| 7 | firewalld 노드별 포트 · SELinux enforcing 확인 | `getenforce` → Enforcing |
| 8 | Docker CE 설치(공식 RHEL 저장소) | `docker compose version`. 컨테이너 로그 상한은 노드가 아니라 Compose가 갖는다(설계서 8장) — 이 단계에서 할 일이 없다 |
| 9 | `dnf-automatic`(보안 갱신만 · 자동 재부팅 없음) timer 활성 | `systemctl list-timers` |
| 10 | NFS 클라이언트 마운트(해당 노드) | 9.3 |

**3노드(Rocky 9.8) 준비 — 실제로 밟은 순서**(사용자 본인 계정 · 서울 · 2026-09-25 12:16 ~ 12:31, #233). 위 표의 1 ~ 9를 세 노드에 밟았다 — **단 4의 `rocky` 잠금은 하지 않았다**(아래 6). 10(NFS)은 3노드 결정으로 NAS를 두지 않아 없다. 값(CIDR · 사설 IP · 인스턴스)은 시스템 구성서 2.1 · 3 · 4장이 갖는다.

| 순서 | 명령 | 확인 · 실측 |
|---|---|---|
| 1 | VPC · 서브넷 셋 · IGW · 공개 경로(0/0 → IGW) · 사설 경로 · S3 게이트웨이 엔드포인트(사설 경로) → 보안 그룹 `rental-app` · `rental-db` → 키 페어 → `run-instances`(서브넷 · 고정 사설 IP · 공인 IP는 APP-01만 · `--metadata-options HttpTokens=required,HttpPutResponseHopLimit=1` · 루트 20 + 데이터 20 GB gp3 `Encrypted`) → APP-01 `modify-instance-attribute --no-source-dest-check` → 사설 경로 0/0 → APP-01 ENI. **모든 리소스에 `Project=rental` 태그** | `describe-instances` — 공인 IP는 APP-01만, `HttpTokens required` · 홉 1, 볼륨 6개 `Encrypted` |
| 1 함정 | 새 계정 첫 생성이 `PendingVerification`(리전 검증, 「보통 몇 분 · 최대 4시간」)으로 거부될 수 있다 — 3분 뒤 재시도로 됐다. **CLI가 내준 키 페어 파일이 CRLF**라 `error in libcrypto` — 줄 끝 CR을 지운다(`sed -i 's/\r$//'`) | — |
| 2 | 사설 노드는 APP-01을 거쳐 들어간다 — `ssh -J`는 점프 호스트에 `-i`를 쓰지 않으므로 호스트별 `IdentityFile` · `ProxyJump`를 적은 SSH 설정 파일로 | DB 노드 접속 |
| 3 | 시간대 `sudo timedatectl set-timezone Asia/Seoul` — 이미지 기본이 UTC | `timedatectl` |
| 4 | 데이터 볼륨 — **루트가 아닌 디스크를 골라**(`lsblk -no PKNAME $(findmnt -no SOURCE /)`로 루트 디스크를 빼고) `mkfs.xfs` → UUID로 fstab(`defaults,nofail`) → `mount` → `restorecon`. APP-01 `/var/lib/docker`, DB `/srv/pgdata` | **재부팅 뒤 APP-01의 데이터 디스크 이름이 `nvme0n1` → `nvme1n1`로 바뀌었다** — UUID가 아니면 마운트가 틀렸다 |
| 5 | sshd · journald 조각(AL2023 표와 같은 파일) | `permitrootlogin no` |
| 6 | 계정 — `backup`(2001) 먼저 → `buyeon` · `deploy`(9.2) | 세 노드 모두 2001 · 2002 · 2003 — 옛 노드와 같다. `rocky`는 자동화용으로 남긴다 — **잠그지 않았다.** `buyeon`의 sudo 비밀번호가 정해지기 전에는 무비밀번호 sudo를 가진 계정이 `rocky`뿐이다(옛 노드 `ec2-user`와 같은 결정 — 설계서 6.2 적용 상태 행) |
| 7 | chrony — **할 일 없음.** 이미지가 DHCP로 169.254.169.123을 받아 선택한다 | `chronyc sources`에 `^* 169.254.169.123` |
| 8 | firewalld — **이미지에 없다.** `dnf install firewalld` → `enable --now` → APP-01 `infra/os/firewalld/app-01.sh`(ssh · http · 영역 내 전달 · 마스커레이드), DB `infra/os/firewalld/db.sh`(ssh · postgresql) | APP-01 `forward: yes` · `masquerade: yes` · `ip_forward = 1`, **DB 노드에서 NAT로 나간다**(`curl` 200 · `dnf makecache`) |
| 9 | SELinux — 이미지 기본 Enforcing | `getenforce` |
| 10 | `dnf install dnf-automatic` → `infra/os/dnf/automatic.conf`로 대체(키는 배포판 파일과 대조 — 이 판에 `reboot` 키가 있다) → `enable --now dnf-automatic.timer` | 다음 실행 06:00 + 무작위 |
| 11 | Docker — `dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo` → `docker-ce docker-ce-cli containerd.io docker-compose-plugin` → `enable --now docker`. **`systemctl enable --now …`의 출력을 `tail -0` 같은 파이프에 흘리지 않는다** — 파이프가 먼저 닫혀 systemctl이 중간에 죽고 서비스가 꺼진 채 남았다 | Docker 29.8.1 · Compose v5.5.1, `Firewall Backend: iptables+firewalld`, `hello-world`. APP-01에서 Docker가 뜬 뒤에도 DB 노드의 NAT가 된다 |
| 12 | 재부팅(`aws ec2 reboot-instances`) | 세 노드 약 12초에 부팅, 마운트 · 서비스 · 설정 유지, 거부 로그 0. **밖에서 APP-01은 22만 열림**(80은 보안 그룹이 열었으나 아직 듣는 것이 없다) |

**메모리** — Rocky 9.8 이미지는 **kdump용으로 192 MB를 예약한다**(`crashkernel=1G-4G:192M`) — t3.small의 `free` total이 **1713 MiB**(AL2023 1909)로 보이고, 서비스를 올린 APP-01의 available이 150 MiB까지 내려갔다. **세 노드에서 끈다** — `sudo grubby --update-kernel=ALL --remove-args=crashkernel` → `sudo systemctl disable --now kdump` → 재부팅(2026-09-25, 사용자 승인). 뒤에 total **1905 MiB**, APP-01 available **386 MiB**. 대가는 커널 패닉 때 덤프를 못 남기는 것이다. 운영 Compose의 메모리 상한(1909 기준)은 그대로 맞는다.

**켜고 끄기(본인 계정 — 작업할 때만 켠다)** — 끌 때 `aws ec2 stop-instances --instance-ids <세 ID>`, 켤 때 `start-instances` → `wait instance-status-ok` → `describe-instances --query '…PublicIpAddress'`로 **APP-01의 새 공인 주소**를 얻어 SSH 설정의 `HostName`을 고친다(Elastic IP를 쓰지 않는다 — 시스템 구성서 2.1). 사설 IP는 바뀌지 않는다. **정지해도 EBS는 과금된다.**

**APP-01(Amazon Linux 2023)에 적용하는 OS 보안 기준** — 위 표 중 이 노드에서 성립하는 5 · 6(chrony만 — 시간대는 이미 `Asia/Seoul`) · 7(SELinux만)과 저널이다(#223). 설정 조각은 `infra/os/`에 있고 체크아웃에서 설치한다. 9는 AL2023에서 성립하지 않는다 — 2장의 월 1회 갱신이 대신한다(설계서 8장).

| 순서 | 명령 | 확인 |
|---|---|---|
| 1 | **기존 SSH 세션을 닫지 않는다.** 원격에서 한 번에 밟을 때는 기존 세션을 붙들고 있기 어려우므로 **되돌리는 타이머를 먼저 건다** — `sudo systemd-run --unit=sshd-revert --on-active=5min /bin/sh -c 'rm -f /etc/ssh/sshd_config.d/40-rental.conf; systemctl reload sshd'`. 그다음 `sudo install -m 644 -o root -g root ~/rental/infra/os/sshd/40-rental.conf /etc/ssh/sshd_config.d/` → `sudo sshd -t` → `sudo systemctl reload sshd` | `sudo sshd -T`에 `permitrootlogin no` · `passwordauthentication no`. **새 창에서 접속이 되는 것을 본 뒤에** 기존 세션을 닫고 되돌림 타이머를 푼다(`sudo systemctl stop sshd-revert.timer`). `root@`로 접속하면 거부된다 |
| 2 | `sudo install -D -m 644 -o root -g root ~/rental/infra/os/journald/50-rental.conf /etc/systemd/journald.conf.d/50-rental.conf` → `sudo systemctl restart systemd-journald` | `systemd-analyze cat-config systemd/journald.conf`에 조각이 보인다 · `journalctl --disk-usage` |
| 3 | 시간 동기화 — AL2023은 기본 설정이 169.254.169.123을 쓴다. 할 일이 없다 | `chronyc sources`에서 그 주소가 `^*` |
| 4 | SELinux — **먼저 거부 기록이 없는지 본다.** `sudo ausearch --input-logs -m AVC,USER_AVC -ts this-week </dev/null`이 비어 있어야 한다(permissive는 거부를 막지 않고 기록만 한다). 그다음 `sudo setenforce 1` → `sudo sed -i 's/^SELINUX=permissive$/SELINUX=enforcing/' /etc/selinux/config`. 재레이블은 필요 없다 — permissive로 켜진 채 운영돼 레이블이 유지돼 있다 | `getenforce` → `Enforcing`. 수동 백업 1회(`sudo systemctl start rental-backup.service`)가 종료 0이고 그 뒤 AVC가 없다. 거부가 나면 `sudo setenforce 0`으로 즉시 되돌리고 `audit2why`로 원인을 본다 — 끄는 것으로 해결하지 않는다 |
| 5 | 계획 재부팅(2장 — 서비스 전체 중단) | 기동 뒤 `getenforce` → `Enforcing`, 배포 확인(3장)과 같은 확인. 컨테이너가 `spc_t`로 돈다(`ps -eZ`) — 설계서 8장 SELinux 행 |

**첫 적용 실측**(APP-01 · 2026-09-25 10:28 ~ 10:31, 표본 1회 — #223) — sshd 조각이 `50-redhat.conf`를 이김(`sshd -T` → `permitrootlogin no`), `root@` · 비밀번호 방식 접속 거부. journald `max 1.0G`. SELinux 전환 전 · 뒤 거부 0건, 전환 뒤 논리 · 물리 백업 종료 0, WAL 아카이브 실패 0, 두 슬롯 `smoke.sh` 60건 실패 0. **재부팅** — 명령 10:29:40 → 커널 10:29:51 → 화면 첫 200 **약 26초** → API 첫 200 **약 93초**(커널에서 약 82초, 바깥 폴링 오차 ±5초). 뒤에 `Enforcing` 유지 · 부팅 이후 거부 0 · 컨테이너 전부 `spc_t`. 9/23 실측(API 71초)보다 길지만 표본이 하나씩이라 enforcing 탓으로 단정하지 않는다.

**`ausearch`는 `</dev/null`을 붙인다.** 표준입력이 터미널이 아니면 로그 파일 대신 표준입력을 읽는다 — `ssh … 'bash -s'` 안에서는 남은 스크립트를 먹고 「no matches」로 끝난다. `--input-logs`가 로그 파일을 읽게 한다.

### 9.2 계정 추가 · 삭제

- **전 노드에 같은 순서로** 적용한다. 한 노드라도 빠지면 NFS 권한과 감사 기록이 어긋난다.
- 추가: 계정 생성 → 공개키 등록 → 필요한 그룹(`wheel` · `docker`)만 → 로그인 확인
- 삭제(퇴사 · 키 유출): 공개키 제거 → 계정 만료(`usermod --expiredate`) · `nologin` → 소유 파일 확인 후 삭제. `passwd -l`만으로는 공개키 로그인이 막히지 않는다(설계서 6.2). **키 유출이면 그 키로 들어갈 수 있던 전 노드에서 즉시**

**추가 명령** — APP-01에 `buyeon`(운영자 개인) · `deploy`를 만든 순서다(2026-09-25 11:43 ~ 11:48, #229). **1 ~ 5는 그때 밟았고, 1의 비밀구절과 6은 사용자가 직접 할 일로 남아 있다** — 6의 확인 칸은 기대값이다.

| 순서 | 명령 | 확인 |
|---|---|---|
| 1 | 운영자 PC에서 **계정마다 키를 따로** 만든다 — `ssh-keygen -t ed25519 -C "<계정>@<노드> <날짜>" -f ~/.ssh/rental-<계정>`. 비밀구절은 본인이 건다(`ssh-keygen -p -f …`). AWS 키 페어를 여러 계정에 재사용하지 않는다 — 키 하나가 새면 그 키가 열던 계정이 전부 열린다 | 개인 키는 운영자 PC에만 |
| 2 | `sudo useradd -m -G wheel -c '<설명>' buyeon` · `sudo useradd -m -G docker -c '<설명>' deploy` — **UID는 자동 할당이다.** `useradd`는 범위 안의 가장 큰 UID 다음을 주므로 `backup`(2001)이 있는 노드에서는 **2002부터** 나온다(APP-01: `buyeon` 2002 · `deploy` 2003). 노드마다 같은 UID가 나오게 하려면 **`backup`을 먼저 만들고 사람 · 역할 계정을 같은 순서로** 만든다 | `id <계정>` — 그룹이 `wheel` 또는 `docker` 하나 |
| 3 | `sudo install -d -o <계정> -g <계정> -m 700 /home/<계정>/.ssh` → 공개키 **한 줄**을 `authorized_keys`(600, 본인 소유)에 → `sudo restorecon -R /home/<계정>/.ssh` | `ls -Z`에 `ssh_home_t`. 파일 첫머리가 `ssh-ed25519 `인지 본다 — 다른 값이 들어가도 sshd는 거부만 하고(`Connection closed by authenticating user … [preauth]`) 이유를 남기지 않는다 |
| 4 | 로그인 확인 — 각 키로 `ssh -o IdentitiesOnly=yes -i ~/.ssh/rental-<계정> <계정>@<노드>` | 성공. 다른 계정의 키 · 비밀번호 방식은 거부. sshd 저널 `Accepted publickey for <계정> … ED25519 SHA256:…`로 어느 키인지 남는다 |
| 5 | 권한 확인 — `sudo -l -U <계정>` | `buyeon` → `(ALL) ALL`(비밀번호 요구 — 배포판 `%wheel ALL=(ALL) ALL` 그대로), `deploy` → `not allowed to run sudo`. `deploy`는 `docker ps` 가능 — **`docker` 그룹은 root와 같은 권한이다**(설계서 6.2) |
| 6 | 개인 계정의 sudo 비밀번호는 **본인이 정한다** — `ssh -t -i <AWS 키> ec2-user@<노드> sudo passwd <계정>`. 비밀번호는 sudo에만 쓰이고 SSH 비밀번호 로그인은 막혀 있다(9.1 AL2023 표 1) | `sudo passwd -S <계정>` → `PS` |

### 9.3 NAS · 정기 작업

| 확인 | 방법 |
|---|---|
| 마운트 | 클라이언트에서 `findmnt <백업 경로>` — NFSv4로 붙어 있는가 |
| 논리 백업이 쌓이는가 | NAS의 백업 경로에 오늘 날짜 파일 · 크기 |
| 정기 작업 성공 | `systemctl list-timers` 다음 실행 시각 · `journalctl -u <작업>` 마지막 실행 결과 |
| 논리 백업 복원 | 별도 인스턴스에서 복호화 → `pg_restore -l`로 목록 확인 → 필요한 테이블만 복원 → 건수 대조. 백업 뒤 바뀐 테이블이면 쓰지 않는다(5장) |

**논리 백업(`rental-backup`)** — 유닛은 `infra/backup/`에 있다. 스크립트는 체크아웃이 아니라 설치한 사본(`/opt/rental/infra/backup/pg-dump.sh`)을, 접속 정보는 `/etc/rental/backup.env`를 유닛이 가리킨다 — 왜 그 경로인지는 유닛 주석이 갖는다.

**설치 순서** — APP-01(Amazon Linux 2023, DB 컨테이너가 같은 노드)에 2026-09-24 00:35 ~ 15:01에 실제로 밟은 순서다. 1 ~ 9는 00:35 ~ 04:04에 적용했고 명령은 그 결과 상태(`id` · `stat` · `rpm` · dnf 이력 · 역할 조회)와 대조해 적었다 — 2는 01:22 첫 수동 실행이 실패한 뒤 더한 단계다. 10은 15:00 ~ 15:01. DB 노드가 분리되면 이 순서를 timer를 둘 노드(설계서 7.2)에서 다시 밟는다.

| 순서 | 명령 | 확인 |
|---|---|---|
| 1 | `sudo dnf install -y postgresql17` — 서버와 같은 주 버전의 클라이언트 | `pg_dump --version`이 서버(`SHOW server_version`)와 같은 17.x |
| 2 | `sudo dnf swap -y gnupg2-minimal gnupg2-full` — **AL2023 기본은 `gnupg2-minimal`이라 gpg-agent가 없어 대칭 암호화가 `gpg: can't connect to the gpg-agent`로 실패한다**([AL2023 사용자 안내서 GNUPG](https://docs.aws.amazon.com/linux/al2023/ug/gnupg-minimal.html)). Rocky는 전체판이 기본이라 이 단계가 없다 | `rpm -q gnupg2` 가 나오고 `gnupg2-minimal`이 없다 |
| 3 | `sudo groupadd -g 2001 backup` · `sudo useradd -u 2001 -g 2001 -r -d /var/backups/rental -s /sbin/nologin -c 'rental logical backup' backup` — 만들기 전에 `getent passwd 2001` · `getent group 2001`이 비었는지 본다 | `id backup` → `uid=2001 gid=2001` |
| 4 | `sudo install -d -o backup -g backup -m 700 /var/backups/rental` · `sudo install -d -m 755 /etc/rental` | 목적지가 `backup` 소유 700 |
| 5 | DB에 백업 전용 역할 — 앱 소유자 역할로 `docker compose exec postgres psql`에 들어가 `CREATE ROLE rental_backup LOGIN` → `GRANT pg_read_all_data TO rental_backup` → `\password rental_backup`. **비밀번호를 SQL 문에 쓰지 않는다** — psql 이력과 서버 로그에 남는다. `\password`는 암호화한 값만 보낸다 | `SELECT rolsuper FROM pg_roles WHERE rolname='rental_backup'` → `f` · `pg_has_role('rental_backup','pg_read_all_data','member')` → `t` |
| 6 | 키 파일 — `openssl rand -hex 32` 출력을 `/etc/rental/backup.key`에 쓰고 `backup` 소유 0400. **값을 화면에 찍지 않는다.** 키는 NAS에 두지 않고(설계서 5.1) 노드 밖 사본은 운영자가 보관한다(인프라 기술 스택 3장 「키 분리 보관」) | `stat -c '%U %a' /etc/rental/backup.key` → `backup 400` |
| 7 | `/etc/rental/backup.env`(`backup` 소유 0600) — `PGUSER=rental_backup` · `PGDATABASE`(`infra/.env`의 `POSTGRES_DB`) · `PGPASSWORD` · `BACKUP_KEY_FILE=/etc/rental/backup.key`. `PGHOST` · `PGPORT` · 목적지 · 보존은 스크립트 기본값을 쓴다 | `stat -c '%U %a'` → `backup 600` |
| 8 | 스크립트 설치 — `sudo install -D -o root -g root -m 755 ~/rental/infra/backup/pg-dump.sh /opt/rental/infra/backup/pg-dump.sh`. **스크립트가 바뀌면 이 명령을 다시 실행한다** | `diff`로 체크아웃과 같다 |
| 9 | 유닛 설치 — `systemd-analyze verify ~/rental/infra/backup/rental-backup.{service,timer}`(아래 표) → `sudo cp ~/rental/infra/backup/rental-backup.{service,timer} /etc/systemd/system/` → `sudo systemctl daemon-reload` | `systemctl status rental-backup.service`가 `loaded` |
| 10 | 수동 1회 → 복원 확인(아래 두 표) → `sudo systemctl enable --now rental-backup.timer` | `systemctl list-timers rental-backup.timer`에 다음 02:00 |

**논리 백업 복원** — 운영 DB에 복원하지 않는다. 같은 주 버전의 **일회용 컨테이너**를 띄워 거기에 푼다(노드가 하나뿐이라 「별도 인스턴스」의 자리를 컨테이너가 대신한다). **복호화 결과를 파일로 받은 뒤 `pg_restore`에 파일로 준다** — 표준입력으로 흘리면 `gpg: error writing to '-': Broken pipe`와 함께 일부만 복원되고도 오류 없이 끝나는 것을 실측했다(2026-09-24).

```bash
docker run -d --rm --name restore-check --memory 256m \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=restore_check postgres:17-alpine
sudo -u backup gpg --batch --quiet --decrypt --passphrase-file /etc/rental/backup.key <백업 파일> \
  | docker exec -i restore-check sh -c 'cat > /tmp/r.dump'
docker exec restore-check pg_restore -l /tmp/r.dump | grep -c 'TABLE DATA'
docker exec restore-check pg_restore -U postgres -d restore_check --no-owner --no-privileges --exit-on-error /tmp/r.dump
# 테이블별 건수를 운영 DB와 대조한 뒤
docker stop restore-check        # --rm 이라 복호화한 파일도 함께 사라진다
```

| 확인 | 방법 |
|---|---|
| 유닛 문법 | `systemd-analyze verify <유닛>` — 노드에 넣기 전에 본다 |
| 수동 1회 실행 | `systemctl start rental-backup.service` → `journalctl -u rental-backup -n 50`. 첫 실행은 이렇게 확인하고 소요 시간을 잰다 |
| primary에서만 도는가 | **DB-01 · DB-02 양쪽에 timer를 둔다.** standby의 저널에는 「standby」로 끝난 기록만 남고 파일이 생기지 않아야 한다 |
| 암호화되어 있는가 | 최신 파일의 앞부분에 `pg_dump` 평문 헤더(`PGDMP`)가 보이면 안 된다. 보이면 암호화가 빠진 것이다 |
| 보존이 도는가 | 보존 기간보다 오래된 파일이 남아 있지 않은가. 기간은 **잠정**이며 첫 백업 크기를 보고 조정한다(5장) |
| 되살아나는가 | 위 복원 순서로 풀고 **테이블별 건수를 운영 DB와 대조한다.** 백업 뒤 쓰기가 있었으면 그 테이블은 달라도 된다 |

**첫 적용 실측**(APP-01 · t3.small · 2026-09-24, 트래픽 없는 시간) — 덤프 + 암호화 **2초**, 암호문 **3.1 MB**(DB 36 MB), 파일 앞부분이 gpg 패킷(`0x8c`)이고 `PGDMP` 없음. 복원 1초, **33개 테이블 전부 운영과 건수 일치**(합 103,641행 — `property` 67,183 · `risk_analysis` 6,007). 밖에서 5432 접속 불가 · 80 접속 가능. 보존 7일은 **잠정 그대로 둔다**(설계서 5.4). 실행 시간 상한은 NFS 목적지로 옮긴 뒤 확정했다 — 아래 「NFS 공유」.

**물리 백업 · WAL 아카이빙(`rental-basebackup`)** — 5장. 설치 순서는 아래다(#214). 논리 백업 설치를 마친 노드를 전제한다 — `backup` 계정 · `/etc/rental` · 루프백 게시가 이미 있다.

| # | 단계 | 확인 |
|---|---|---|
| 1 | `sudo dnf install -y postgresql17-server postgresql17-contrib` — AL2023에서 `pg_basebackup`은 server, `pg_archivecleanup`은 contrib 패키지에 있다. **호스트 서비스는 켜지 않는다** | `pg_basebackup --version` 17.x · `systemctl is-enabled postgresql` → `disabled` |
| 2 | 디렉터리 — `sudo install -d -o 70 -g backup -m 2770 /var/backups/rental/wal` · `sudo install -d -o backup -g backup -m 700 /var/backups/rental/physical`. **컨테이너 재생성 전에 만든다** — 없으면 Docker가 root 소유로 만든다 | `stat -c '%u:%G %a'` → `70:backup 2770` |
| 3 | 역할 — `CREATE ROLE rental_basebackup LOGIN REPLICATION` → `\password rental_basebackup`. 평문 비밀번호를 SQL에 쓰지 않는다(위 5단계와 같은 이유). 대화형 터미널이 없으면 호스트에서 SCRAM-SHA-256 검증자를 만들어 `CREATE ROLE … PASSWORD 'SCRAM-SHA-256$…'`(이미 있으면 `ALTER ROLE`)로 **검증자만** 보낸다 — `\password`가 보내는 것과 같은 형태다. 형식은 `SCRAM-SHA-256$<반복>:<솔트>$<StoredKey>:<ServerKey>`(각 Base64) — 솔트 16바이트, 반복 4096(PostgreSQL 기본 `scram_iterations`), `SaltedPassword = PBKDF2-HMAC-SHA-256(비밀번호, 솔트, 반복)`, `StoredKey = SHA-256(HMAC(SaltedPassword, "Client Key"))`, `ServerKey = HMAC(SaltedPassword, "Server Key")`(RFC 5802 · SHA-256판은 RFC 7677). PostgreSQL은 먼저 SASLprep로 정규화하므로 비밀번호를 ASCII로 만든다 — 그러면 결과가 같다. #214에서는 노드의 python3 표준 라이브러리로 만들었고, 평문은 `umask 077` 임시 디렉터리의 env 파일에만 두고 `install`로 옮긴 뒤 지웠다 — SQL · 화면 · 로그에 없다 | `SELECT rolreplication, rolsuper FROM pg_roles WHERE rolname='rental_basebackup'` → `t` · `f` |
| 4 | `/etc/rental/basebackup.env`(`backup` 소유 0600) — `PGUSER=rental_basebackup` · `PGPASSWORD` | `stat -c '%U %a'` → `backup 600` |
| 5 | 체크아웃 → `bash maintenance.sh on` → `docker compose up -d --no-deps --force-recreate postgres` → healthy → `bash maintenance.sh off` → `docker compose up -d --no-deps --force-recreate postgres-standby` — `archive_mode`는 재기동해야 바뀐다. 접속 규칙(`pg_hba.conf`)도 이때 함께 읽힌다 | `SHOW archive_mode` → `on`, standby `streaming` |
| 6 | `SELECT pg_switch_wal()` | 아카이브 디렉터리에 세그먼트 · `pg_stat_archiver.failed_count` 0 |
| 7 | 스크립트 설치 — `sudo install -D -o root -g root -m 755 ~/rental/infra/backup/pg-basebackup.sh /opt/rental/infra/backup/pg-basebackup.sh`. **스크립트가 바뀌면 다시 실행한다** | `diff`로 체크아웃과 같다 |
| 8 | 유닛 — `systemd-analyze verify` → `/etc/systemd/system/`에 복사 → `sudo systemctl daemon-reload` | `loaded` |
| 9 | 수동 1회 `sudo systemctl start rental-basebackup.service` → PITR 리허설(5장) → `sudo systemctl enable --now rental-basebackup.timer` | `journalctl -u rental-basebackup` 종료 0 · `list-timers`에 다음 일 01:30 |

**원격으로 밟을 때 걸린 것 둘**(#214) — ① `ssh … '…'` 안의 큰따옴표 속 `$$`는 원격 셸이 PID로 푼다. SQL의 달러 인용은 표준입력으로 보낸다. ② `ssh … 'bash -s' <<EOF` 안에서 `docker compose exec -T`는 **남은 스크립트를 표준입력으로 먹는다** — 뒤 명령이 조용히 사라진다. 그 줄에 `</dev/null`을 붙인다.

**NFS 공유(INF-08)** — 단일 노드라 APP-01이 자기 자신에게 공유를 건다(설계서 5.3 「단일 노드의 자기 공유」). 조각은 `infra/os/nfs/`에 있다. 논리 백업 설치를 마친 노드를 전제한다 — `backup` 계정(2001)이 있다. 2026-09-25 11:14 ~ 11:17에 실제로 밟은 순서다(#227).

| 순서 | 명령 | 확인 |
|---|---|---|
| 1 | `rpm -q nfs-utils`(AL2023 기본 설치) · 공유 디렉터리 `sudo install -d -m 755 /srv/nfs` · `sudo install -d -o backup -g backup -m 700 /srv/nfs/backup` | `backup 700` |
| 2 | 조각 셋 설치 — `nfs.conf.d/50-rental.conf` → `/etc/nfs.conf.d/`, `nfs-mountd.service.d/v4only.conf` → `/etc/systemd/system/nfs-mountd.service.d/`, `exports.d/rental-backup.exports` → `/etc/exports.d/`(모두 root 644) | — |
| 3 | `sudo systemctl mask --now rpc-statd.service rpcbind.service rpcbind.socket` → `sudo systemctl daemon-reload` → `sudo systemctl enable --now nfs-server` | `sudo cat /proc/fs/nfsd/versions` → `-3 +4 +4.1 +4.2` · `sudo exportfs -v`에 `127.0.0.1(…rw,…root_squash…)` · `ss -ltn`에 **2049가 `127.0.0.1`에만**, 111 · 20048 없음 |
| 4 | `sudo install -d -m 755 /mnt/nas/backup` → fstab에 `127.0.0.1:/srv/nfs/backup  /mnt/nas/backup  nfs4  _netdev,nofail,x-systemd.requires=nfs-server.service  0 0` → `sudo findmnt --verify` → `sudo systemctl daemon-reload` → `sudo mount /mnt/nas/backup` | `findmnt /mnt/nas/backup` → `nfs4 … vers=4.2 … hard` |
| 5 | `root_squash` — `sudo touch /mnt/nas/backup/x` | **거부**(`Permission denied`). `sudo -u backup`으로 쓰면 성공, 소유 `2001:2001` |
| 6 | 로컬에 쌓인 논리 백업을 옮긴다 — `backup` 계정으로 `cp -p` → `cmp` 같으면 원본 삭제. 날짜가 보존돼야 보존 정리가 이어진다 | 옮긴 파일 수 · 시각 유지 |
| 7 | `/etc/rental/backup.env`에 `BACKUP_DEST=/mnt/nas/backup` → 유닛 재설치(`RequiresMountsFor=/mnt/nas/backup`) · `daemon-reload` → 수동 1회 | `systemctl show rental-backup -p RequiresMountsFor`에 그 경로, 종료 0, 파일이 NFS에 · 앞부분이 gpg 패킷 |
| 8 | 밖에서 2049 · 111 · 20048 | 닫힘 |

**실측**(2026-09-25) — NFS 목적지 논리 백업 **1.6초**(로컬 2초와 같은 수준), 거부 로그 0건(enforcing · 공유 디렉터리 기본 레이블 `var_t`). 수동 `rpc.nfsd` 재기동 때 `Unable to request RDMA services: Protocol not supported`가 찍힌다 — 배포판 `nfs.conf`의 `rdma=y` 때문이고 TCP 공유와 무관하다.

**실행 시간 상한 실증** — NAS가 멈춘 경우를 흉내 내려고 **`systemctl stop nfs-server`가 아니라 `sudo rpc.nfsd 0`으로 nfsd만 세운다.** `x-systemd.requires` 때문에 서비스를 멈추면 마운트도 함께 내려가 「마운트는 있는데 서버가 답하지 않는」 경우가 되지 않는다. 그 상태에서 `sudo systemd-run --no-block --unit=nas-stall-test --uid=backup --gid=backup -p Type=oneshot -p TimeoutStartSec=20s -p RequiresMountsFor=/mnt/nas/backup /bin/sh -c 'echo t > /mnt/nas/backup/stall-test'` — **쓰기에서 멈췄다가 20초에 `start operation timed out. Terminating.` → `Failed with result 'timeout'`**, 남은 프로세스 없음(NFS 대기는 kill 가능한 대기다). `sudo rpc.nfsd 8`(기본 스레드 수)로 되살리면 1초 안에 쓰기가 돌아온다. 끝나면 `systemctl reset-failed nas-stall-test`. 그래서 논리 백업의 상한 30분을 확정했다(설계서 5.3).

**설정 사본(`rental-config-copy`)** — 설계서 5.1 · 7.2의 「운영 설정 사본」이다. 체크아웃의 `infra/`(추적 밖의 `.env` 포함)와 리비전 기록(`REVISION` — 커밋과 `git status --porcelain`)을 묶어 **통째로** gpg 대칭 암호화(논리 백업과 같은 키)해 `/mnt/nas/backup/config/`에 둔다. **원본 읽기만 root, 암호화 · 쓰기 · 정리는 `backup`**(`runuser`) — 체크아웃은 `ec2-user` 홈(700) 안이라 `backup`이 읽지 못하고, NFS는 `root_squash`라 root가 쓰지 못한다. NFS 공유(위)를 마친 노드를 전제한다. 2026-09-25 12:01에 실제로 밟은 순서다(#231).

| 순서 | 명령 | 확인 |
|---|---|---|
| 1 | `sudo install -D -o root -g root -m 755 ~/rental/infra/backup/config-copy.sh /opt/rental/infra/backup/config-copy.sh` — **스크립트가 바뀌면 다시 실행한다** | `cmp`로 체크아웃과 같다 |
| 2 | `/etc/rental/config-copy.env`(root 0600) — `CONFIG_SRC=/home/ec2-user/rental` · `BACKUP_KEY_FILE=/etc/rental/backup.key`. 체크아웃 자리가 바뀌면 `CONFIG_SRC`만 고친다 | `stat` → `root 600` |
| 3 | `systemd-analyze verify` → `rental-config-copy.{service,timer}`를 `/etc/systemd/system/`에 → `daemon-reload` → 수동 1회 | 종료 0 |
| 4 | **되살아나는가** — NAS가 아닌 곳에 `mktemp -d`(700)로 임시 디렉터리를 만들어 `backup`에게 준 뒤, `backup`으로 `env HOME=/var/backups/rental gpg --batch --decrypt --passphrase-file /etc/rental/backup.key --output <임시>/c.tgz <사본>` → `tar -tzf` → `REVISION` · `infra/.env`만 풀어 `cmp`. **평문 `.env`가 풀리므로 임시 디렉터리는 바로 지운다** | `.env`가 운영 것과 같다 |
| 5 | `sudo systemctl enable --now rental-config-copy.timer` | `list-timers`에 다음 **일 01:00** |

**실측**(2026-09-25) — 수동 1회 **0.86초**, 암호문 **43,616바이트**(묶음 47개 항목 — `REVISION` 포함), 앞부분 gpg 패킷(`8c 0d`), `REVISION`의 커밋이 체크아웃과 같고 `.env` 일치. 연달아 5회 돌려 **최신 4개만 남는 것**을 확인했다. 거부 로그 0건. 상한은 10분(잠정 — 유닛 주석), 01:00에 멈춰도 01:30 물리 백업 전에 끝난다.

**NAS가 멈추면** 서비스는 영향이 없다(시스템 구성서 5.2). 물리 백업 · WAL(지금은 노드 로컬 — 5장, 설계 목표는 S3)은 계속되므로 급하게 손대지 않고, 복구 뒤 멈춘 기간의 논리 백업을 한 번 수동 실행한다.

### 9.4 Amazon Linux 2023 → Rocky Linux 9 이전

설계서 9장의 다섯 단계를 밟는다. **본인 계정 3노드는 Elastic IP를 쓰지 않으므로**(설계서 3.5) 4단계의 「Elastic IP를 옮긴다」는 「새 APP-01 주소를 알리고 카카오맵 사이트 도메인에 등록한다」로 읽는다. **되돌릴 길이 남아 있는지 단계마다 확인한다** — 전환(Elastic IP 재지정) 전까지는 옛 노드가 그대로 서비스한다.

| 순서 | 작업 | 되돌리기 |
|---|---|---|
| 1 | Rocky 노드 준비(9.1) | 새 노드 삭제 |
| 2 | 데이터 이전(논리 백업 복원 또는 standby로 복제 합류) | 같다 |
| 3 | 새 노드에서 배포 · 확인(3장) | 같다 |
| 4 | 점검 모드(4.2) → 마지막 차분 → Elastic IP를 새 APP-01로 → 점검 모드 해제 | Elastic IP를 옛 APP-01로 |
| 5 | 옛 노드 정지 보존 → 기간 경과 후 삭제 | 옛 노드 재기동 |

### 9.5 단일 노드 → 3노드 이전 — 실제로 밟은 순서

2026-09-25(노드 시각, #235) — 1 ~ 4는 12:45 ~ 13:05(체크아웃 12:45, 인증서 · `.env` 13:04, DB-01 initdb 13:04:36), 5 ~ 9는 13:06:11 ~ 13:21. 9.4의 다섯 단계 중 1 ~ 4를 이렇게 밟았다 — 두 계정의 VPC 사이에 경로가 없어 복제 합류 대신 논리 덤프로 옮겼다. 옛 노드는 5단계(정지 보존) 대신 **점검 모드로 남겼다** — 두 곳에서 쓰기가 갈라지지 않게.

| 순서 | 명령 | 확인 · 실측 |
|---|---|---|
| 1 | 새 노드 체크아웃 — `deploy`로 `git clone` → `/home/deploy/rental`(공개 저장소) | — |
| 2 | `.env` — 옛 노드의 것을 **운영자 PC 파이프로 스트림**(`ssh 옛 'cat …/.env' \| ssh 새 'sudo -u deploy sh -c "umask 077; cat > …"'` — 이 PC 디스크에 남지 않는다) → 노드별 값: APP-01 `COMPOSE_PROFILES=app` · `DB_HOST=10.20.10.10` · `APP_BASE_URL` · `PG_EXPORTER_SSLMODE=require`, DB-01 `db` · `PG_PUBLISH_ADDR=10.20.10.10` · `PG_SSL=on` · `PG_DATA_SOURCE=/srv/pgdata/data`, DB-02 `standby` · `PG_STANDBY_FROM=10.20.10.10` · `PG_STANDBY_PUBLISH_ADDR=10.20.20.10` · `PG_STANDBY_PUBLISH_PORT=5432` · `PG_SSL=on` · `PG_STANDBY_DATA_SOURCE=/srv/pgdata/data` | `docker compose config --services`(`deploy`로) — APP-01 11 · DB-01 `postgres` · DB-02 `postgres-standby` |
| 3 | DB 노드 — WAL 디렉터리(9.3 물리 백업 2단계와 같은 권한), TLS 인증서 `sudo openssl req -x509 -nodes -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -keyout /etc/rental/pg-tls/server.key -out /etc/rental/pg-tls/server.crt -days 825 -subj "/CN=<노드>.rental.internal" -addext "subjectAltName=IP:<사설 IP>"` → 둘 다 `70:70`, 키 600. 데이터 자리 `sudo install -d -o 70 -g 70 -m 700 /srv/pgdata/data` | 인증서 만료 2028-12-28 |
| 4 | DB-01 `docker compose up -d postgres`(빈 DB) | healthy, `show ssl` → `on`, `pg_hba_file_rules` 오류 0, 게시 `10.20.10.10:5432`만, 데이터가 데이터 볼륨에. APP-01에서 `sslmode=disable` → **거절**, `require` → **TLSv1.3** |
| 5 | 옛 노드 `bash maintenance.sh on` | `/` · API 503 |
| 6 | 옛 노드 `docker compose exec -T postgres pg_dumpall --globals-only`(역할 · SCRAM 검증자) · `pg_dump -Fc` → 파이프로 DB-01 `/tmp` → 컨테이너에 `docker cp` → 전역은 `psql -f`(「already exists」는 무시), DB는 `pg_restore --exit-on-error` → 임시 파일 삭제. **원격 명령의 다른 출력(`date` 등)을 덤프와 같은 표준출력에 섞지 않는다** — 1차 덤프가 그렇게 깨졌다(`input file does not appear to be a valid archive`) | 역할 5개 · 속성 유지, TABLE DATA 33, **테이블별 `count(*)`가 옛 노드와 33개 전부 같다**(합 103,641) |
| 7 | DB-01 `SELECT pg_create_physical_replication_slot('standby_1')` → DB-02 `docker compose up -d postgres-standby` | **14초에 healthy**, `pg_stat_replication` `streaming` · **ssl t · TLSv1.3**, 표식 행이 DB-02에 보인다, DB-02 건수도 같다 |
| 8 | APP-01 `docker compose pull` → `up -d` | 11개 Up, 두 슬롯 `smoke.sh` 실패 0, 밖에서 `/` · API 200, 앱 → DB 연결 전부 TLS, 밖에서 22 · 80만 |
| 9 | kdump 끄기 · 재부팅(9.1 메모리 단락) | 재부팅 명령에서 **API 200까지 92초**, 복제 · TLS 자동 재개 |

- **옛 노드에서는 전체 `docker compose up -d`를 하지 않는다.** 프로필 · `depends_on` 변경으로 두 앱 슬롯이 한꺼번에 재생성된다(`up --dry-run`으로 확인). 옛 노드 `.env`에는 `COMPOSE_PROFILES=app,db,standby`를 넣어 둔다
- 새 서비스 주소는 APP-01 공인 IP(켤 때마다 바뀐다 — 9.1 「켜고 끄기」). 카카오맵 JS 키의 사이트 도메인에 그 주소를 등록해야 지도가 뜬다
