#!/usr/bin/env bash
# 회차 기록 — 회차 동안 노드 · 부하 생성기에서 표본을 쌓고, 끝나면 가져온다(INF-06 #378).
#
#   bash record.sh start <회차>     표본기를 띄우고 시작 시각을 남긴다. JMeter 를 돌리기 직전에
#   bash record.sh stop  <회차>     표본기를 멈추고 결과를 results/<회차>/ 로 가져온다. JMeter 가 끝난 직후에
#   bash record.sh clean <회차>     노드에 남은 표본 자리($MEASURE_TMP/<회차>)를 지운다 — stop 이 가져간 것을 확인한 뒤
#
# 노드 쪽(node-sampler.sh 를 노드에 올려 띄운다 — SSH 가 끊겨도 돈다)
#   모든 노드   지표 긁기 — INTERVAL 초(기본 5)마다 그 노드의 수집 대상(node · redis · nginx · app-1 · app-2 · postgres)
#   DB 노드     대기 종류 1초 표본(pg_stat_activity)
#   APP-01     nginx 접근 로그 따라 적기 — 컨테이너 로그가 10m 에서 회전하므로 끝난 뒤가 아니라 회차 동안 받는다
# 이 스크립트를 돌리는 기계(부하 생성기)
#   vmstat -t -n 5 · sar -n DEV 5(있으면) → results/<회차>/gen/ — 생성기가 병목이 아니었음을 보이는 근거
#
# 결과(stop 뒤)
#   results/<회차>/meta.json                 {"round", "start_utc", "end_utc", "interval_s", "generator", "nodes", "node_clock_utc"}
#   results/<회차>/metrics/<노드>.prom.gz     노드 이름은 app01 · app02 · db01 · db02
#   results/<회차>/db/<노드>-wait.csv         머리 ts,wait_event_type,count
#   results/<회차>/nginx/access.log
#   results/<회차>/gen/vmstat.txt · sar-dev.txt
#   results/<회차>/post/redis-commandstats.txt · post/credits.json
#
# 서버 상태를 바꾸지 않는다 — 노드 /tmp 에 파일을 쓰고 읽기 전용 질의 · 로그 읽기만 한다.
# 같은 명령을 다시 불러도 된다 — start 는 이미 도는 표본기를 그대로 두고, stop 은 멈춘 것은 건너뛰고 다시 가져온다.
#   INTERVAL   지표 긁기 간격(초, 기본 5)
#   NODES      표본을 둘 노드(기본 "app01 app02 db01 db02")
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

usage() { echo "사용법: bash record.sh start|stop|clean <회차>" >&2; exit 2; }
ACTION=${1:-}; ROUND=${2:-}
[ -n "$ACTION" ] && [ -n "$ROUND" ] || usage
check_round "$ROUND"
INTERVAL=${INTERVAL:-5}
[[ $INTERVAL =~ ^[1-9][0-9]*$ ]] || die "INTERVAL 은 1 이상의 정수(초)다: $INTERVAL"
NODES=${NODES:-app01 app02 db01 db02}
RD=$(round_dir "$ROUND")
NODE_RD="$MEASURE_TMP/$ROUND"

sampler() {  # <노드> <하위 명령과 인자…> — 노드에 올린 표본기를 부른다
  local node=$1; shift
  local args="" a
  for a in "$@"; do args="$args $(q "$a")"; done
  on_node "$node" "MEASURE_TMP=$(q "$MEASURE_TMP") bash $(q "$MEASURE_TMP/node-sampler.sh")$args"
}

# 생성기 쪽 표본 — 이 기계에서 백그라운드로 돈다
gen_start() {
  mkdir -p "$RD/gen"
  if command -v vmstat > /dev/null; then
    if [ -f "$RD/gen/vmstat.pid" ] && kill -0 "$(cat "$RD/gen/vmstat.pid")" 2>/dev/null; then
      log "생성기 vmstat — 이미 돈다"
    else
      LC_ALL=C nohup vmstat -t -n 5 > "$RD/gen/vmstat.txt" 2>&1 < /dev/null &
      echo $! > "$RD/gen/vmstat.pid"
      log "생성기 vmstat 시작"
    fi
  else
    warn "이 기계에 vmstat 이 없다 — 생성기 CPU 를 기록하지 않는다(부하 생성기 Linux 에서 돌린다)"
  fi
  if command -v sar > /dev/null; then
    if [ -f "$RD/gen/sar.pid" ] && kill -0 "$(cat "$RD/gen/sar.pid")" 2>/dev/null; then
      log "생성기 sar — 이미 돈다"
    else
      # LC_ALL=C — 시각을 24시간제로(로캘에 따라 AM/PM 이 붙는다)
      LC_ALL=C nohup sar -n DEV 5 > "$RD/gen/sar-dev.txt" 2>&1 < /dev/null &
      echo $! > "$RD/gen/sar.pid"
      log "생성기 sar -n DEV 시작"
    fi
  else
    warn "sar 가 없다(sysstat) — 생성기 네트워크를 기록하지 않는다"
  fi
}
gen_stop() {
  local f
  for f in "$RD/gen/vmstat.pid" "$RD/gen/sar.pid"; do
    [ -f "$f" ] || continue
    kill "$(cat "$f")" 2>/dev/null || true
    rm -f "$f"
  done
}

do_start() {
  mkdir -p "$RD"
  if [ -f "$RD/meta.json" ] && grep -q '"start_utc"' "$RD/meta.json"; then
    log "meta.json 이 이미 있다 — 시작 시각을 덮지 않는다(다시 시작하려면 회차 이름을 바꾼다)"
  fi
  local node clocks="" sep=""
  for node in $NODES; do
    log "[$(node_label "$node")] 표본기 올림"
    on_node "$node" "mkdir -p $(q "$NODE_RD") && cat > $(q "$MEASURE_TMP/node-sampler.sh")" < "$NODE_SCRIPTS/node-sampler.sh" \
      || die "[$(node_label "$node")] 표본기를 올리지 못했다"
    sampler "$node" start "$ROUND" "$node" "$INTERVAL" "$NODE_DIR" | sed "s|^|    [$(node_label "$node")] |"       || die "[$(node_label "$node")] 표본기를 띄우지 못했다 — 이미 띄운 노드는 record.sh stop $ROUND 로 멈춘다"
    # 노드 시계 — 노드 시각으로 찍힌 표본(지표 · 대기 종류)을 생성기 시각과 맞출 때 어긋남을 본다
    clocks="$clocks$sep\"$node\": \"$(on_node "$node" 'date -u +%Y-%m-%dT%H:%M:%S.%3NZ')\""
    sep=", "
  done
  gen_start
  if [ ! -f "$RD/meta.json" ]; then
    local nodes_json="" n s=""
    for n in $NODES; do nodes_json="$nodes_json$s\"$n\""; s=", "; done
    # start_utc 는 표본기를 다 띄운 뒤의 시각이다 — 이 시각부터 모든 표본이 쌓이고 있다
    cat > "$RD/meta.json" <<EOF
{
  "round": "$ROUND",
  "start_utc": "$(utc_now_ms)",
  "end_utc": null,
  "interval_s": $INTERVAL,
  "generator": $(json_str "$(hostname)"),
  "nodes": [$nodes_json],
  "node_clock_utc": {$clocks}
}
EOF
  fi
  log "기록 시작 — $RD/meta.json. 이제 JMeter 를 돌린다(결과는 $RD/jmeter/result.jtl)"
}

fetch() {  # <노드> <원격 경로> <로컬 경로> — 받으면 크기를 보이고, 없으면 알린다
  local node=$1 src=$2 dst=$3
  mkdir -p "$(dirname "$dst")"
  if on_node "$node" "cat $(q "$src")" > "$dst.part" 2>/dev/null; then
    mv -f "$dst.part" "$dst"
    log "  받음 $(fsize "$dst") B  $dst"
  else
    rm -f "$dst.part"
    warn "  못 받음 — $(node_label "$node"):$src"
  fi
}

do_stop() {
  [ -d "$RD" ] || die "회차 폴더가 없다: $RD — start 를 먼저 했는가"
  local node end
  end=$(utc_now_ms)
  # 끝 시각은 처음 stop 의 것만 남긴다 — 다시 불러도 구간이 늘지 않게
  if [ -f "$RD/meta.json" ] && grep -q '"end_utc": null' "$RD/meta.json"; then
    sed -i "s|\"end_utc\": null|\"end_utc\": \"$end\"|" "$RD/meta.json"
    log "끝 시각 $end"
  fi
  gen_stop
  for node in $NODES; do
    log "[$(node_label "$node")] 표본기 멈춤"
    sampler "$node" stop "$ROUND" "$node" "$NODE_DIR" | sed "s|^|    [$(node_label "$node")] |" \
      || warn "[$(node_label "$node")] 멈춤 명령이 실패했다 — 노드의 $NODE_RD/*.pid 를 확인한다"
  done

  log "가져오기"
  for node in $NODES; do
    fetch "$node" "$NODE_RD/metrics-$node.prom.gz" "$RD/metrics/$node.prom.gz"
    case $node in
      db01|db02)
        # \watch 가 끼우는 빈 줄 · 머리 줄을 거르고 머리를 붙인다
        mkdir -p "$RD/db"
        if on_node "$node" "cat $(q "$NODE_RD/wait-$node.csv")" > "$RD/db/$node-wait.raw" 2>/dev/null; then
          { echo "ts,wait_event_type,count"; grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}T' "$RD/db/$node-wait.raw" || true; } > "$RD/db/$node-wait.csv"
          rm -f "$RD/db/$node-wait.raw"
          log "  받음 $(fsize "$RD/db/$node-wait.csv") B  $RD/db/$node-wait.csv"
        else
          rm -f "$RD/db/$node-wait.raw"
          warn "  못 받음 — $(node_label "$node"):$NODE_RD/wait-$node.csv"
        fi ;;
      app01)
        mkdir -p "$RD/nginx"
        # 노드에서 압축해 옮긴다 — 부하 회차의 접근 로그는 수십 MB 다
        if on_node app01 "gzip -c $(q "$NODE_RD/nginx-access.log")" | gzip -dc > "$RD/nginx/access.log.part"; then
          mv -f "$RD/nginx/access.log.part" "$RD/nginx/access.log"
          log "  받음 $(fsize "$RD/nginx/access.log") B  $RD/nginx/access.log"
        else
          rm -f "$RD/nginx/access.log.part"
          warn "  못 받음 — APP-01:$NODE_RD/nginx-access.log"
        fi ;;
    esac
  done

  mkdir -p "$RD/post"
  if { echo "# captured_utc $(utc_now_ms)"; redis_commandstats; } > "$RD/post/redis-commandstats.txt"; then
    log "  Redis 명령 통계 $(fsize "$RD/post/redis-commandstats.txt") B  $RD/post/redis-commandstats.txt"
  else
    warn "  Redis 명령 통계를 받지 못했다"
  fi
  bash "$NODE_SCRIPTS/credits.sh" "$RD/post/credits.json" || warn "  크레딧을 기록하지 못했다 — 나중에 credits.sh 로 다시 받을 수 있다(CloudWatch 는 15분 안의 최신 점)"

  local f
  for f in "$RD/gen/vmstat.txt" "$RD/gen/sar-dev.txt"; do
    if [ -f "$f" ]; then log "  생성기 $(fsize "$f") B  $f"; fi
  done
  log "기록 끝 — 다음은 collect.sh $ROUND"
}

do_clean() {
  local node
  for node in $NODES; do
    if on_node "$node" "rm -rf $(q "$NODE_RD")"; then
      log "[$(node_label "$node")] $NODE_RD 지움"
    else
      warn "[$(node_label "$node")] $NODE_RD 를 지우지 못했다"
    fi
  done
}

case $ACTION in
  start) do_start ;;
  stop)  do_stop ;;
  clean) do_clean ;;
  *) usage ;;
esac
