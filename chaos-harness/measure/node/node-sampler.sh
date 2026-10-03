#!/usr/bin/env bash
# 노드 쪽 표본기 — record.sh 가 노드에 올려 부른다(INF-06 #378). 운영자가 직접 부를 일은 없다.
#
#   bash node-sampler.sh start <회차> <노드> <간격초> <Compose 자리>     이 노드에 맞는 표본기를 띄운다(이미 돌면 그대로 둔다)
#   bash node-sampler.sh stop  <회차> <노드> <Compose 자리>              멈춘다(이미 멈췄으면 그대로)
#   bash node-sampler.sh loop  <출력.gz> <간격초> <이름=URL>…            지표 긁기 본체 — start 가 띄운다
#
# 표본은 노드의 $MEASURE_TMP/<회차>/ 에 쌓는다(기본 /tmp/rental-measure). SSH 가 끊겨도 멈추지 않게 setsid + nohup 으로
# 세션에서 떼어 띄우고 pid 를 파일로 남긴다.
#
#   metrics-<노드>.prom.gz   모든 노드. 간격마다 그 노드의 루프백 수집 대상을 긁어 붙인다. 각 본문 앞에
#                            「# SCRAPE <유닉스 초(소수 3자리)> <대상 이름>」 한 줄. 실패하면 「# SCRAPE_ERROR <대상 이름>」.
#                            긁을 때마다 gzip 한 덩어리(멤버)로 덧붙인다 — 노드 /tmp 에 원문을 쌓지 않는다(한 시간에 수백 MB).
#                            여러 멤버를 이은 gzip 은 그대로 하나의 gzip 파일로 읽힌다(gzip · Python gzip 모듈)
#   wait-<노드>.csv          DB 노드. 1초마다 활성 클라이언트 세션 수를 대기 종류별로(대기 없음 = CPU). 활성이 0 이면 NONE,0 한 줄
#   nginx-access.log         APP-01. 앞단 nginx 의 접근 로그(컨테이너 표준 출력)를 시작 시점부터 따라 적는다 — json-file 이 10m 에서
#                            회전하므로 끝난 뒤 잘라 오면 앞부분이 없다
#
# 대상 이름 — node · redis · nginx · app-1 · app-2 · postgres. 주소는 운영 Compose 의 게시 자리(전부 루프백, APP-02 슬롯만 사설 IP)
set -uo pipefail

BASE=${MEASURE_TMP:-/tmp/rental-measure}
WAIT_APPNAME=rental-measure-wait

alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

# ── 지표 긁기 본체 ────────────────────────────────────────────────────────────
# 멈춤은 TERM 을 이 프로세스에만 보낸다(stop). bash 는 진행 중인 자식(curl | gzip)이 끝난 뒤에 트랩을 돌리므로
# 마지막 gzip 멤버가 반쯤 쓰인 채 남지 않는다
loop() {
  local out=$1 interval=$2; shift 2
  local stop=0 t name url next now d
  trap 'stop=1' TERM INT
  next=$(date +%s%N)
  while [ "$stop" -eq 0 ]; do
    for t in "$@"; do
      [ "$stop" -eq 0 ] || break
      name=${t%%=*} url=${t#*=}
      { printf '# SCRAPE %s %s\n' "$(date +%s.%3N)" "$name"
        curl -sf --connect-timeout 1 -m 3 "$url" || printf '\n# SCRAPE_ERROR %s\n' "$name"
      } | gzip -1 >> "$out"
    done
    # 간격을 시각에 맞춘다 — 긁는 시간만큼 밀리지 않게
    next=$((next + interval * 1000000000)); now=$(date +%s%N); d=$((next - now))
    if [ "$d" -gt 0 ]; then
      sleep "$(printf '%d.%09d' $((d / 1000000000)) $((d % 1000000000)))" &
      wait $! 2>/dev/null || true       # 트랩이 sleep 을 기다리지 않고 바로 돌게
    else
      next=$now
    fi
  done
}

start() {
  local round=$1 node=$2 interval=$3 cdir=$4 d addr svc since
  d=$BASE/$round
  mkdir -p "$d"
  local targets=()
  case $node in
    app01)
      targets=(node=http://127.0.0.1:9100/metrics redis=http://127.0.0.1:9121/metrics nginx=http://127.0.0.1:9113/metrics
               app-1=http://127.0.0.1:8081/actuator/prometheus app-2=http://127.0.0.1:8082/actuator/prometheus) ;;
    app02)
      # 이 노드의 슬롯은 사설 IP 하나에 게시된다(APP_PUBLISH_ADDR) — 루프백으로는 닿지 않는다(prometheus/agent-app-node.yml)
      addr=$(grep -E '^APP_PUBLISH_ADDR=' "$cdir/.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"' ")
      addr=${addr:-127.0.0.1}
      targets=(node=http://127.0.0.1:9100/metrics
               "app-1=http://$addr:8081/actuator/prometheus" "app-2=http://$addr:8082/actuator/prometheus") ;;
    db01|db02)
      targets=(node=http://127.0.0.1:9100/metrics postgres=http://127.0.0.1:9187/metrics) ;;
    *) echo "알 수 없는 노드: $node" >&2; return 1 ;;
  esac

  # /tmp 가 tmpfs 면 쌓이는 것이 곧 메모리다 — 알리기만 한다(gzip 으로 쌓아 시간당 수십 MB 수준)
  if [ "$(findmnt -n -o FSTYPE --target "$d" 2>/dev/null)" = tmpfs ]; then
    echo "경고: $d 가 tmpfs 다 — 표본이 메모리를 쓴다"
  fi

  if alive "$d/metrics.pid"; then
    echo "지표 긁기 — 이미 돈다(pid $(cat "$d/metrics.pid"))"
  else
    setsid nohup bash "$BASE/node-sampler.sh" loop "$d/metrics-$node.prom.gz" "$interval" "${targets[@]}" \
      > "$d/metrics.log" 2>&1 < /dev/null &
    echo $! > "$d/metrics.pid"
    echo "지표 긁기 시작 — ${interval}초, 대상 ${#targets[@]}개(${targets[*]%%=*})"
  fi

  case $node in
    db01|db02)
      svc=postgres; [ "$node" = db02 ] && svc=postgres-standby
      if alive "$d/wait.pid"; then
        echo "대기 종류 표본 — 이미 돈다(pid $(cat "$d/wait.pid"))"
      else
        # 한 psql 이 \watch 1 로 같은 질의를 1초마다 돈다. 시각은 UTC · 밀리초(clock_timestamp — 질의 시점)
        cat > "$d/wait.sql" <<'SQL'
WITH n AS MATERIALIZED (
  SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS ts
), a AS (
  SELECT coalesce(wait_event_type, 'CPU') AS w
  FROM pg_stat_activity
  WHERE state = 'active' AND backend_type = 'client backend' AND pid <> pg_backend_pid()
)
SELECT n.ts, a.w, count(*) FROM n, a GROUP BY n.ts, a.w
UNION ALL
SELECT n.ts, 'NONE', 0 FROM n WHERE NOT EXISTS (SELECT 1 FROM a)
\watch 1
SQL
        # 멈출 때 이름(application_name)으로 서버 쪽 세션을 끊는다 — docker compose exec 클라이언트만 죽이면 컨테이너 안 psql 이 남을 수 있다
        # shellcheck disable=SC2016  # 컨테이너 안 sh 가 푼다
        ( cd "$cdir" && setsid nohup docker compose exec -T -e PGAPPNAME=$WAIT_APPNAME "$svc" \
            sh -c 'exec psql -X -At -F, -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
            < "$d/wait.sql" > "$d/wait-$node.csv" 2> "$d/wait.err" &
          echo $! > "$d/wait.pid" )
        echo "대기 종류 표본 시작 — 1초($svc)"
      fi ;;
  esac

  if [ "$node" = app01 ]; then
    if alive "$d/nginx.pid"; then
      echo "nginx 접근 로그 — 이미 따라간다(pid $(cat "$d/nginx.pid"))"
    else
      since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
      # 표준 출력(접근 로그)만 받는다. 표준 오류(nginx 오류 로그)는 따로 둔다
      ( cd "$cdir" && id=$(docker compose ps -q nginx) && [ -n "$id" ] \
          && { setsid nohup docker logs -f --since "$since" "$id" > "$d/nginx-access.log" 2> "$d/nginx-error.log" < /dev/null &
               echo $! > "$d/nginx.pid"; } ) \
        && echo "nginx 접근 로그 따라가기 시작 — $since 부터" \
        || echo "경고: nginx 컨테이너를 찾지 못했다 — 접근 로그를 받지 않는다"
    fi
  fi
}

# 멈춘다 — pid 의 프로세스(지표 긁기)는 TERM 뒤 끝날 때까지 기다리고, 나머지는 세션(프로세스 그룹)째 끝낸다
stop_one() {  # <pid 파일> <이름> <그룹째>
  local f=$1 what=$2 group=$3 pid
  if ! alive "$f"; then echo "$what — 돌고 있지 않다"; return 0; fi
  pid=$(cat "$f")
  if [ "$group" = 1 ]; then kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null; else kill -TERM "$pid" 2>/dev/null; fi
  for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  if kill -0 "$pid" 2>/dev/null; then kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null; echo "$what — 강제 종료"; else echo "$what — 멈춤"; fi
}

stop() {
  local round=$1 node=$2 cdir=$3 d svc
  d=$BASE/$round
  [ -d "$d" ] || { echo "표본 자리가 없다: $d"; return 0; }
  stop_one "$d/metrics.pid" "지표 긁기" 0
  case $node in
    db01|db02)
      svc=postgres; [ "$node" = db02 ] && svc=postgres-standby
      ( cd "$cdir" && docker compose exec -T "$svc" sh -c \
          "psql -X -At -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = '$WAIT_APPNAME'\"" ) \
        > /dev/null 2>&1 || true
      stop_one "$d/wait.pid" "대기 종류 표본" 1 ;;
  esac
  if [ "$node" = app01 ]; then stop_one "$d/nginx.pid" "nginx 접근 로그" 1; fi
  ls -l "$d"
}

cmd=${1:-}; shift || true
case $cmd in
  loop)  loop "$@" ;;
  start) start "$@" ;;
  stop)  stop "$@" ;;
  *) echo "사용법: bash node-sampler.sh start|stop|loop …" >&2; exit 2 ;;
esac
