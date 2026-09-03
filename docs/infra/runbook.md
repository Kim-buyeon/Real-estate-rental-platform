# 전월세 부동산 금융 플랫폼 — 운영 절차서

> 배포 · 복구 · 점검 절차
> Runbook · Nginx 설정 · 배포 스크립트
> ※ 설계 근거는 `docs/features/infra.md`를, 지표와 알림은 `docs/infra/observability.md`를 따른다.
> 작성 기준일 : 2026년 8월

## 1. 문서 목적

본 문서는 손으로 실행하는 절차만 담는다. 왜 그렇게 설계했는지는 `docs/features/infra.md`에 있으므로, 여기서는 무엇을 어떤 순서로 실행하는지에 집중한다.

장애 상황에서 읽는 문서이므로 판단이 필요한 지점과 판단 기준을 함께 표기한다. **절차서의 목적은 당황한 상태에서도 순서를 틀리지 않는 것이다.**

---

## 2. 일상 점검

| 주기 | 항목 |
|---|---|
| 일 1회 | 배치 실행 결과, 등급 변동 건수 이상 여부, 알림 발송 실패 건수, 디스크 사용률 |
| 주 1회 | 전체 백업 성공 여부, 복제 지연 추이, 슬로우 쿼리 상위 10건, 캐시 적중률 추이 |
| 월 1회 | 백업 복원 검증, 가용성 목표 달성률 집계(`docs/infra/system.md` 5.1절), 용량 추이 대비 증설 시점 판단, 외부 API 호출량·비용 집계 |
| 분기 1회 | 페일오버·페일백 시험, PITR 시험, 베이스 이미지 갱신 후 재스캔(3.1절), 절차서 갱신 |

**계획된 정지 작업 전에 외부 경로 감시를 일시정지한다.** HetrixTools 무료 플랜에는 유지보수 윈도우가 없어, 그대로 두면 계획된 작업이 장애 알림으로 발송된다. 콘솔에서 모니터를 정지하고 작업 종료 후 반드시 다시 켠다.

---

## 3. 배포 절차

### 3.1 실행 규칙

설계 근거는 `docs/features/infra.md` INF-02에 있다. 실행 시 반드시 지킬 규칙은 다음과 같다.

| 규칙 | 이유 |
|---|---|
| **reload만 사용한다. restart 금지** | restart는 모든 연결을 끊는다. SSE 알림 연결이 전 사용자에게서 동시에 끊긴다 |
| **`nginx -t`를 reload 전에 항상 실행** | 설정을 스크립트로 수정하므로 치환 실패 가능성이 있다 |
| **`down` 플래그만 토글한다** | 서버 목록 자체를 바꾸면 실패 시 설정과 컨테이너 상태가 어긋난다 |
| **제외 → drain → 교체 → 확인 → 복귀 순서 고정** | 컨테이너를 먼저 정지하면 그 사이 요청이 전부 실패한다 |
| **실패 시 해당 슬롯을 down으로 유지하고 중단** | 남은 슬롯이 구버전으로 계속 서비스하므로 별도 롤백이 불필요하다 |
| **첫 슬롯 복귀 후 관찰(6분)** | 교체 직후는 신·구 50:50 상태다. 지표를 확인한 뒤 진행하고, 이상이면 되돌린다 |
| **이전 이미지 태그 3개 보존** | 배포 완료 후 문제가 발견된 경우의 롤백 경로 |
| **배포 전 이미지 취약점 스캔** | 취약한 이미지를 올리면 발견할 때까지 노출된 상태로 운영된다 |

**취약점 스캔** — Trivy로 컨테이너 이미지의 OS 패키지와 Java 의존성을 한 번에 검사한다. CRITICAL이 남아 있으면 배포를 중단하고, HIGH는 기록만 남기고 진행한다. 1인 운영에서 HIGH까지 차단하면 배포가 멈춘 채로 시간이 흐른다.

```bash
trivy image --severity HIGH,CRITICAL --exit-code 0 "$IMAGE" > report/trivy-$(date +%F).txt
trivy image --severity CRITICAL --exit-code 1 "$IMAGE" || {
  echo "!!! CRITICAL 취약점. 배포를 중단한다."; exit 1; }
```

배포 시마다 실행하므로 별도 점검 주기를 두지 않는다. 다만 코드가 바뀌지 않아도 새 취약점은 계속 공개되므로, 분기 1회 베이스 이미지를 갱신해 다시 빌드하고 스캔한다.

마이그레이션은 하위 호환이어야 한다. 컬럼 삭제·이름 변경·NOT NULL 추가는 단일 배포에서 수행하지 않고 세 단계(추가 → 양쪽 기록 → 제거)로 분리한다.

### 3.2 배포 스크립트

**전제** — GitHub Actions가 이미지를 빌드해 GitHub Container Registry(`ghcr.io/<계정>/<저장소>:<커밋 해시>`)에 올리고, 스크립트의 `docker compose pull`이 그것을 받아온다. 파이프라인은 4주차(9월 17일~)에 구성하며, 그때까지는 로컬 빌드 이미지를 사용한다.

**시크릿** — DB 비밀번호와 외부 API 키는 서버의 `.env` 파일에 두고 `docker compose`가 컨테이너에 주입한다. 파일 권한은 소유자만 읽도록 제한하고 저장소에는 커밋하지 않는다. 백업 복호화 키만 이 파일과 분리해 보관한다. 1주차 셋업 시점에 확정하고 본 절을 갱신한다.

```bash
#!/usr/bin/env bash
set -euo pipefail

UP=./nginx/conf.d/upstream.conf   # 컨테이너에 볼륨 마운트
SLOTS=("app-2 8082" "app-1 8081")
FIRST=1

reload() { docker compose exec -T nginx nginx -t \
             && docker compose exec -T nginx nginx -s reload; }

down_slot() { sed -i "s|\(127\.0\.0\.1:$1[^;]*\);|\1 down;|" "$UP"; reload; }
up_slot()   { sed -i "s|\(127\.0\.0\.1:$1\) down;|\1;|"      "$UP"; reload; }

for slot in "${SLOTS[@]}"; do
  read -r NAME PORT <<< "$slot"

  echo ">>> [$NAME] upstream 제외 후 drain"
  down_slot "$PORT"
  sleep 30

  echo ">>> [$NAME] 이미지 교체"
  docker compose pull "$NAME"
  docker compose up -d --force-recreate "$NAME"

  echo ">>> [$NAME] readiness 대기"
  for i in $(seq 1 30); do
    if curl -fs "http://127.0.0.1:$PORT/actuator/health/readiness" \
         | grep -q '"status":"UP"'; then
      break
    fi
    if [ "$i" -eq 30 ]; then
      echo "!!! [$NAME] 기동 실패. down 상태를 유지하고 배포를 중단한다."
      exit 1
    fi
    sleep 2
  done

  echo ">>> [$NAME] upstream 복귀"
  up_slot "$PORT"

  # 첫 슬롯 교체 직후는 신·구 버전이 50:50인 상태다. 여기서 지표를 확인한다.
  if [ "$FIRST" -eq 1 ]; then
    FIRST=0
    echo ">>> 관찰 시작 (신버전 50% 노출)"
    sleep 360                                 # 워밍업 60초 + 관찰 5분
    if ! bash observe.sh; then
      echo "!!! 지표 이상. [$NAME] 을 제외하고 배포를 중단한다."
      down_slot "$PORT"
      exit 1
    fi
    echo ">>> 관찰 통과. 다음 슬롯으로 진행"
  fi
done

echo ">>> 배포 완료. Grafana에 마커 기록"
```

**Nginx도 컨테이너로 구동한다.** 설정 파일과 TLS 인증서는 호스트 디렉터리를 볼륨으로 마운트해 컨테이너 밖에서 수정하고, `reload`만 컨테이너 안에서 실행한다. 스크립트가 `sed`로 고치는 `upstream.conf`는 마운트된 호스트 파일이다.

### 3.3 관찰 판정 (`observe.sh`)

첫 슬롯이 복귀하면 신·구 버전이 50:50으로 서비스된다. 관찰이 끝난 시점에 한 번 실행해 다음 슬롯으로 진행할지 판정한다.

**대기 6분, 조회 5분.** 방금 기동한 JVM은 첫 1분이 JIT 컴파일로 느리므로 6분을 기다린 뒤 직전 5분치를 조회해 그 구간을 제외한다. 부하 시험의 워밍업 제외 기준과 같다(`docs/infra/traffic.md` 3.4절).

```bash
#!/usr/bin/env bash
# 통과 0, 이상 1
set -uo pipefail
PROM=${PROM:-http://127.0.0.1:9090}

q() { curl -sfG "$PROM/api/v1/query" --data-urlencode "query=$1" \
        | grep -o '"value":\[[^]]*\]' | sed 's/.*,"//;s/"//'; }

# 1. 관측이 살아 있는가
UP=$(q 'count(up{job="app"} == 1)')
if [ -z "${UP:-}" ]; then
  echo "Prometheus가 앱을 스크레이프하지 못한다. 배포를 중단한다."
  exit 1
fi

REQ=$(q 'sum(increase(http_server_requests_seconds_count[5m]))');                   REQ=${REQ:-0}
ERR=$(q 'sum(increase(http_server_requests_seconds_count{status=~"5.."}[5m]))');    ERR=${ERR:-0}
P95=$(q 'histogram_quantile(0.95,
         sum(rate(http_server_requests_seconds_bucket[5m])) by (le))')

printf '요청 %.0f건  5xx %.0f건  p95 %ss\n' "$REQ" "$ERR" "${P95:-NA}"

# 2. 판정할 표본이 있는가
if awk -v r="$REQ" 'BEGIN { exit !(r < 100) }'; then
  echo "요청 100건 미만. 지표 판정을 생략하고 진행한다."
  exit 0
fi

# 3. 기준: 5xx 1% 미만, p95 1초 미만
awk -v r="$REQ" -v e="$ERR" -v p="${P95:-99}" \
    'BEGIN { exit !(e / r < 0.01 && p < 1.0) }'
```

| 확인 | 통과하지 못하면 |
|---|---|
| Prometheus가 앱을 스크레이프하는가 | 중단. 관측이 없는 상태의 승격은 근거가 없다 |
| 5분간 요청이 100건 이상인가 | 지표 판정을 생략하고 진행 |
| 5xx 1% 미만, p95 1초 미만인가 | 해당 슬롯을 `down`으로 되돌리고 중단 |

**표본이 없는 것과 판정에 실패한 것을 구분한다.** 트래픽이 적은 시간대에는 5분간 요청이 몇 건뿐일 수 있고, 3건 중 1건이 실패하면 33%가 되어 어떤 임계로도 통과하지 못한다.

**임계값은 `docs/infra/observability.md` 5장 알림 규칙과 같은 값이다.** 배포 판정이 더 느슨하면 배포는 통과했는데 직후에 알림이 울린다.

### 3.4 이미지 태그 보존

배포가 끝난 뒤에 문제가 발견되는 경우가 있다. 관찰 구간을 넘긴 뒤에 드러나는 결함이 그렇다.

- 이미지 태그는 커밋 해시로 부여하고 **최근 3개를 보존**한다. `latest`만 쓰면 되돌아갈 지점이 없다
- 롤백은 대상 태그를 지정해 같은 스크립트를 다시 실행하는 것으로 갈음한다. **이때 관찰 단계는 건너뛴다** — 되돌아갈 대상이 이미 검증된 버전이기 때문이다. 소요는 약 3분이다 (drain 30초 × 2 + 기동·확인)
- 스키마 마이그레이션이 포함된 배포는 롤백해도 스키마가 되돌아가지 않는다. 하위 호환 규칙(3.1절)을 지켰다면 구버전이 신 스키마에서 동작하므로 문제가 없다. **이것이 하위 호환을 강제하는 실질적 이유다**

## 4. Nginx 설정

### 4.1 upstream 정의

슬롯 목록을 별도 파일로 분리해 배포 스크립트가 이 파일만 수정하게 한다. 서버 항목은 항상 두 개가 상주하며 `down` 플래그만 토글된다.

```nginx
# ./nginx/conf.d/upstream.conf  — 컨테이너에 볼륨 마운트
upstream app {
    server 127.0.0.1:8081 max_fails=3 fail_timeout=10s;
    server 127.0.0.1:8082 max_fails=3 fail_timeout=10s;
    keepalive 32;
}
```

**주소를 컨테이너 이름이 아니라 루프백 IP로 고정한다.** Nginx는 upstream 호스트명을 설정 로드 시점에 한 번 해석하므로, 컨테이너 이름을 쓰면 컨테이너 재생성으로 IP가 바뀌었을 때 옛 IP로 계속 전달하는 문제가 발생할 수 있다. 두 애플리케이션 프로세스가 동일 노드에 있으므로 루프백 고정으로 이 문제를 원천 차단한다.

**Nginx 컨테이너는 `network_mode: host`로 기동한다.** 브리지 네트워크에 두면 컨테이너 안의 `127.0.0.1`이 호스트가 아니라 컨테이너 자신을 가리켜 위 설정이 동작하지 않는다. 호스트 네트워크를 쓰면 애플리케이션이 노출한 루프백 포트에 그대로 접근할 수 있고, 컨테이너 이름 해석에 의존하지 않으므로 DNS 캐싱 문제도 발생하지 않는다.

```yaml
nginx:
  image: nginx:1.26-alpine
  network_mode: host
  volumes:
    - ./nginx/conf.d:/etc/nginx/conf.d
    - ./nginx/certs:/etc/letsencrypt
```

### 4.2 서버 블록

```nginx
worker_shutdown_timeout 30s;   # SSE로 인한 old worker 누적 방지

server {
    listen 443 ssl;
    server_name example.com;

    # 일반 API
    location /api/ {
        proxy_pass http://app;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_connect_timeout 2s;
        proxy_next_upstream error timeout http_502 http_503;
    }

    # SSE — 버퍼링을 끄지 않으면 이벤트가 버퍼에 갇힌다
    location /api/notifications/stream {
        proxy_pass http://app;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }

    # 점검 모드 — 페일오버 시 활성화(6장 2단계)
    # location / { return 503; }
}
```

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

RISK_ANALYSIS와 같이 재계산 가능한 데이터는 PITR 대신 **재분석 배치의 강제 재실행**이 더 빠르다. 복구 수단을 데이터 성격에 따라 구분한다.

---

## 6. 페일오버 · 페일백

### 6.1 페일오버 절차

Primary 장애 판정부터 서비스 정상화까지의 절차다. 각 단계에 예상 소요를 병기해 RTO 30분의 근거로 삼는다.

| 단계 | 작업 | 예상 소요 | 판단 기준 |
|---|---|---|---|
| 1 | 알림 수신 및 장애 확인 | 3분 | primary 헬스체크 3회 연속 실패 + SSH 접속 불가 |
| 2 | 애플리케이션을 점검 모드로 전환 (Nginx 503 반환) | 2분 | 이중 기록 방지. 승격 전 필수 |
| 3 | standby의 복제 지연 확인 (`pg_last_wal_replay_lsn`) | 2분 | 지연이 크면 WAL 아카이브 추가 재생 |
| 4 | standby 승격 (`pg_ctl promote`) | 3분 | 승격 후 쓰기 가능 여부 확인 |
| 5 | 애플리케이션의 DB 접속 대상 전환 후 재기동 | 5분 | 설정 변경 + 롤링 재기동 |
| 6 | 점검 모드 해제, 핵심 기능 확인 | 5분 | 로그인·매물 조회·위험도 조회 |
| 7 | 신규 standby 재구축 | 30분 | 서비스 정상화 이후 수행. RTO에 미포함 |
| 8 | 장애 보고서 작성 | — | 원인·조치·재발 방지 |
| **합계(1~6)** | | **20분 (추정)** | 판정 기준은 1등급 RTO 30분. 실측은 시나리오 3에서 기록한다 |

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
| 6 | 현재 primary의 쓰기 중단 확인 후 구 primary 승격 | 승격 전 `pg_current_wal_lsn` 일치 확인 |
| 7 | 애플리케이션 DB 접속 대상 전환 후 롤링 재기동 | 3장 배포 절차와 동일한 방식 |
| 8 | 점검 모드 해제, 반대편을 다시 standby로 재구축 | 원래 구성 복원 완료 |

**2단계의 재구축 수단을 먼저 판정한다.** 데이터 크기에 따라 소요가 크게 달라진다.

| 조건 | 수단 | 소요 |
|---|---|---|
| `wal_log_hints = on` 또는 데이터 체크섬 활성, 승격 이후 구 primary에 쓰기가 없었음 | `pg_rewind` | 분 단위 |
| 위 조건 불충족 | `pg_basebackup` 전체 재구축 | 데이터 크기에 비례 |

`pg_rewind`를 쓰려면 **장애가 나기 전에** 해당 설정이 켜져 있어야 한다. 장애 발생 후에는 선택할 수 없으므로 초기 구성 시점에 결정한다.

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
| 변경 | 배포 횟수, 롤백 횟수와 사유 | 이미지 태그 이력, Grafana 배포 마커 |
| 용량 | 헤드룸 추이, 디스크 소진 예측, 증설 판단 | `docs/infra/observability.md` 4장 용량 대시보드 |
| 복구 검증 | 백업 복원 검증 결과, 복원 소요 | `docs/infra/test-plan.md` 6장 |
| 미결 | 이월된 조치 사항과 기한 | 전월 보고서 |

**마지막 절을 비워두지 않는다.** 조치하지 못한 항목이 다음 달로 넘어가는 것을 기록해야 방치와 보류가 구분된다.

---