# shellcheck shell=bash
# 측정 스크립트 공용 — 노드 접속 · 회차 폴더 · 슬롯 재생성(INF-06 #378). 직접 실행하지 않고 같은 폴더의 스크립트가 source 한다.
#
# 접속 — 운영자 PC(Git Bash) 또는 부하 생성기(Linux)에서 SSH 로 노드에 붙는다. 사용자가 만든 ssh_config 의 별칭을 쓴다.
#   app01 · db01 · db02   ssh -F "$SSH_CONFIG" <별칭>            (db 는 그 설정의 ProxyJump 가 app01 을 거친다)
#   app02                 ssh -F "$SSH_CONFIG" app01 ssh <사설 IP>   APP-01 의 deploy 키로 한 번 더 붙는다(배포 스크립트와 같은 길)
# 파일은 scp 대신 `ssh … cat` 으로 받는다 — app02 는 두 단 SSH 라 scp 가 바로 닿지 않고, 네 노드를 같은 방식으로 다루려고 한다.
#
# 노드 체크아웃 — 노드마다 /home/deploy/rental 이고 Compose 는 그 안 infra/ 에서 부른다(운영 절차서 9장 · deploy.sh 의 APP_NODE_DIR).
# 컨테이너 이름을 적지 않고 `docker compose ps -q <서비스>` 로 찾는다.
#
# 바꿀 수 있는 값(환경 변수)
#   SSH_CONFIG    ssh_config 경로(기본 C:/Users/bu200/loadtest-data/ssh_config — 운영자 PC)
#   NODE_DIR      노드의 Compose 자리(기본 /home/deploy/rental/infra)
#   MEASURE_TMP   노드에서 표본을 쌓는 자리(기본 /tmp/rental-measure). 회차마다 그 아래 <회차>/
#   APP02_ADDR    APP-02 사설 IP(기본 10.20.1.10 — deploy.sh 의 NODE_ADDRS 둘째 값. #404 에서 2c 공개 서브넷으로 옮겼다, 전에는 10.20.20.30)
#   APP_NODES     슬롯을 다룰 앱 노드(기본 "app01 app02"). APP-02 를 끈 구성이면 "app01"
#   RESULTS_DIR   회차 결과 자리(기본 chaos-harness/measure/results)

# 아래 값 일부는 이 파일을 source 하는 스크립트만 쓴다
# shellcheck disable=SC2034
MEASURE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
NODE_SCRIPTS=$MEASURE_ROOT/node
RESULTS_DIR=${RESULTS_DIR:-$MEASURE_ROOT/results}
SSH_CONFIG=${SSH_CONFIG:-C:/Users/bu200/loadtest-data/ssh_config}
NODE_DIR=${NODE_DIR:-/home/deploy/rental/infra}
MEASURE_TMP=${MEASURE_TMP:-/tmp/rental-measure}
APP02_ADDR=${APP02_ADDR:-10.20.1.10}
APP_NODES=${APP_NODES:-app01 app02}
DB_NODES="db01 db02"
# 배치 스위치 넷 — 시험 동안 전부 false 여야 한다(.env.example 의 배치 주석). 줄이 없으면 앱 기본값 true 다.
# pre-round.sh 가 기록 · 경고하고, measure-mode.sh on 이 넷 중 하나라도 false 가 아니면 거부한다
BATCH_KEYS="PROPERTY_BATCH_REFRESH_ENABLED RISK_BATCH_REGISTRYREFRESH_ENABLED RISK_BATCH_MOCKLEDGERREPLACE_ENABLED BATCH_STARTUPCATCHUP_ENABLED"

log()  { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }
warn() { printf '%s !!! %s\n' "$(date '+%F %T')" "$*" >&2; }
die()  { warn "$*"; exit 1; }

# 회차 이름 — 폴더 이름이 되므로 글자를 좁힌다(R01 · U03 처럼)
check_round() {
  [[ ${1:-} =~ ^[A-Za-z0-9_-]+$ ]] || die "회차 이름이 올바르지 않다: '${1:-}' — 영문 · 숫자 · _ · - 만(예: R01)"
}
round_dir() { printf '%s/%s' "$RESULTS_DIR" "$1"; }

node_label() {
  case $1 in
    app01) echo APP-01 ;; app02) echo APP-02 ;; db01) echo DB-01 ;; db02) echo DB-02 ;;
    *) die "알 수 없는 노드: $1 (app01 · app02 · db01 · db02)" ;;
  esac
}
# DB 노드의 PostgreSQL 서비스 이름 — DB-01 은 primary(postgres), DB-02 는 standby(postgres-standby)
db_service() {
  case $1 in db01) echo postgres ;; db02) echo postgres-standby ;; *) die "DB 노드가 아니다: $1" ;; esac
}

q() { printf '%q' "$1"; }

# on_node <노드> <원격 명령 한 줄> — 표준 입력은 그대로 원격에 넘어간다.
# ServerAlive — 사설망에서 패킷이 버려지면 ssh 가 끝없이 매달린다. 15초 × 4 무응답이면 끊고 실패한다(deploy.sh 와 같은 값)
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
on_node() {
  local node=$1 cmd=$2
  case $node in
    app02)
      # 두 단 — APP-01 의 셸이 한 번 더 풀므로 원격 명령을 한 번 더 감싼다
      ssh -F "$SSH_CONFIG" "${SSH_OPTS[@]}" app01 \
        "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 $(q "$APP02_ADDR") $(q "$cmd")" ;;
    app01|db01|db02)
      ssh -F "$SSH_CONFIG" "${SSH_OPTS[@]}" "$node" "$cmd" ;;
    *) die "알 수 없는 노드: $node" ;;
  esac
}

# psql_node <DB 노드> [DB 이름] — SQL 을 표준 입력으로 받아 그 노드의 DB 컨테이너 안 psql 로 돈다.
# 접속은 컨테이너 안 유닉스 소켓 · 컨테이너 환경의 POSTGRES_USER(superuser)다 — pg_hba 의 local trust. 비밀번호를 다루지 않는다.
# DB 이름을 비우면 앱 DB(POSTGRES_DB). SQL 안에서 :'appdb' 로 앱 DB 이름을 쓸 수 있다. 출력은 -At -F, (머리 없음 · 쉼표)
psql_node() {
  local node=$1 db=${2:-} svc
  svc=$(db_service "$node")
  # 작은따옴표 안은 컨테이너의 sh 가 푼다 — $POSTGRES_USER 등은 컨테이너 환경 값이다
  on_node "$node" "cd $(q "$NODE_DIR") && docker compose exec -T $svc sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -v appdb=\"\$POSTGRES_DB\" -U \"\$POSTGRES_USER\" -d \"\${1:-\$POSTGRES_DB}\" -At -F,' sh $(q "$db")"
}

# 질의 통계(pg_stat_statements) 뷰가 있는 DB — 앱 DB 에 확장이 있으면 앱 DB, 없으면 postgres(운영 절차서 2장 「질의 통계」).
# 뷰는 클러스터 전체를 보이므로 어느 쪽이든 dbid 로 앱 DB 만 골라 읽는다
pgss_db() {
  local has
  has=$(printf "SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements';\n" | psql_node "$1" 2>/dev/null || true)
  if [ "$has" = 1 ]; then echo ""; else echo postgres; fi
}

utc_now_ms() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }

# JSON 문자열 값 — 따옴표 · 역슬래시만 감싼다(값은 상태 이름 · 플래그 · 경로처럼 짧은 것만 넣는다)
json_str() {
  local s=${1//\\/\\\\}
  s=${s//\"/\\\"}
  printf '"%s"' "$s"
}

# 파일 크기(바이트) — 받은 결과를 보여 줄 때
fsize() { if [ -f "$1" ]; then wc -c < "$1" | tr -d ' '; else echo 0; fi; }

# Redis 명령 통계 — APP-01 의 redis 컨테이너 안 redis-cli. 비밀번호는 컨테이너 환경에서 REDISCLI_AUTH 로만 넘긴다(명령줄 · 출력에 남지 않는다)
redis_commandstats() {
  on_node app01 "cd $(q "$NODE_DIR") && docker compose exec -T redis sh -c 'if [ -n \"\${REDIS_PASSWORD:-}\" ]; then export REDISCLI_AUTH=\"\$REDIS_PASSWORD\"; fi; exec redis-cli INFO commandstats'" \
    | tr -d '\r'
}

# ── 슬롯 재생성 — 새 세션 · 새 설정을 슬롯에 들이는 수단(auto-explain.sh · measure-mode.sh) ─────────────────────────────
# 배포 스크립트(deploy.sh)를 부르지 않는다 — 이미지는 그대로이고, 그 스크립트는 upstream 토글 · 예열 · 관찰까지 묶여 있다.
# 여기서는 슬롯을 한 번에 하나씩 `up -d --no-deps --force-recreate` 하고 그 노드에서 readiness 를 기다린다.
# **upstream 에서 빼지 않는다(drain 없음)** — 부하가 없는 때(회차 사이)에만 부른다. 그동안 nginx 는 그 슬롯 연결 실패를
# 다른 슬롯으로 넘긴다(passive). 실패하면 그 자리에서 멈춘다 — 남은 슬롯은 그대로 서비스한다.
# 순서는 deploy.sh 와 같다 — 노드마다 한 슬롯씩 번갈아(APP-02 app-2 → APP-01 app-2 → APP-02 app-1 → APP-01 app-1)
recreate_slots() {
  local slot node
  for slot in app-2 app-1; do
    for node in app02 app01; do
      case " $APP_NODES " in *" $node "*) ;; *) continue ;; esac
      log "[$(node_label "$node") $slot] 재생성"
      on_node "$node" "cd $(q "$NODE_DIR") && docker compose up -d --no-deps --force-recreate $slot" \
        || die "[$(node_label "$node") $slot] 재생성 실패 — 여기서 멈춘다. 남은 슬롯은 그대로 서비스한다"
      log "[$(node_label "$node") $slot] readiness 대기(최대 약 2 ~ 5분)"
      # 그 노드에서 자기 슬롯을 두드린다 — APP-02 는 게시 주소가 사설 IP 하나라(APP_PUBLISH_ADDR) 루프백으로 닿지 않는다
      if ! on_node "$node" "bash -s -- $(q "$NODE_DIR") $slot" <<'EOF'
set -u
cd "$1"
port=8081; [ "$2" = app-2 ] && port=8082
addr=$(grep -E '^APP_PUBLISH_ADDR=' .env 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"' ")
addr=${addr:-127.0.0.1}
for i in $(seq 1 60); do
  if curl -fs --connect-timeout 2 -m 3 "http://$addr:$port/actuator/health/readiness" | grep -q '"status":"UP"'; then
    echo "UP ($i 회째)"; exit 0
  fi
  sleep 2
done
exit 1
EOF
      then
        die "[$(node_label "$node") $slot] readiness 를 받지 못했다 — 여기서 멈춘다. docker compose logs $slot 로 원인을 본다"
      fi
    done
  done
  log "슬롯 재생성 완료"
}
