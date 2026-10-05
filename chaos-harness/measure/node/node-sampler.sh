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
#                            「# SCRAPE <유닉스 초(소수 3자리) — 긁기 시작 시각> <대상 이름>」 한 줄. 본문은 다 받았을 때만 붙인다 —
#                            실패(연결 · HTTP 오류 · 3초 초과)면 본문 없이 「# SCRAPE_ERROR <대상 이름>」 한 줄만.
#                            긁을 때마다 gzip 한 덩어리(멤버)로 덧붙인다 — 노드 /tmp 에 원문을 쌓지 않는다(한 시간에 수백 MB).
#                            여러 멤버를 이은 gzip 은 그대로 하나의 gzip 파일로 읽힌다(gzip · Python gzip 모듈)
#   wait-<노드>.csv          DB 노드. 1초마다 활성 클라이언트 세션 수를 대기 종류별로(대기 없음 = CPU). 활성이 0 이면 NONE,0 한 줄
#   containers-<노드>.jsonl.gz  모든 노드. 간격마다 컨테이너별 메모리(cgroup memory.current · memory.max · oom_kill) 한 줄 — cloop 주석(#390)
#   nginx-access.log         APP-01. 앞단 nginx 의 접근 로그(컨테이너 표준 출력)를 시작 시점부터 따라 적는다 — json-file 이 10m 에서
#                            회전하므로 끝난 뒤 잘라 오면 앞부분이 없다
#
# 대상 이름 — node · redis · nginx · app-1 · app-2 · postgres. 주소는 운영 Compose 의 게시 자리(전부 루프백, APP-02 슬롯만 사설 IP)
set -uo pipefail

BASE=${MEASURE_TMP:-/tmp/rental-measure}
WAIT_APPNAME=rental-measure-wait

alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

# ── 지표 긁기 본체 ────────────────────────────────────────────────────────────
# 한 번 긁은 본문은 먼저 임시 파일(<출력>.scrape)에 받고, curl 이 성공했을 때만 「# SCRAPE」 줄과 함께 gzip 멤버로 붙인다 —
# -m 3 에 걸리면 curl 은 본문 일부를 쓴 뒤 실패하므로, 바로 흘려 넣으면 잘린 본문이 표본으로 남는다. 실패면 「# SCRAPE_ERROR」 한 줄만.
# 멈춤은 TERM 을 이 프로세스에 먼저 보낸다(stop). bash 는 진행 중인 자식(curl · gzip)이 끝난 뒤에 트랩을 돌리므로
# 마지막 gzip 멤버가 반쯤 쓰인 채 남지 않는다
loop() {
  local out=$1 interval=$2; shift 2
  local stop=0 t name url next now d ts tmp=$out.scrape
  trap 'stop=1' TERM INT
  next=$(date +%s%N)
  while [ "$stop" -eq 0 ]; do
    for t in "$@"; do
      [ "$stop" -eq 0 ] || break
      name=${t%%=*} url=${t#*=}
      ts=$(date +%s.%3N)
      if curl -sf --connect-timeout 1 -m 3 -o "$tmp" "$url"; then
        # 본문이 줄바꿈으로 끝나지 않으면 하나 붙인다 — 다음 멤버의 「# SCRAPE」 줄이 본문 끝에 붙지 않게
        { printf '# SCRAPE %s %s\n' "$ts" "$name"; cat "$tmp"; [ -z "$(tail -c 1 "$tmp")" ] || echo; } | gzip -1 >> "$out"
      else
        printf '# SCRAPE_ERROR %s\n' "$name" | gzip -1 >> "$out"
      fi
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
  rm -f "$tmp"
}

# ── 컨테이너 메모리 본체(#390) — containers-<노드>.jsonl.gz ────────────────────────────────────────────────
# 간격마다 한 줄(JSON): {"ts": <유닉스 초>, "c": [{"svc": <Compose 서비스>, "id": <앞 12자>, "cur": <바이트>, "max": <바이트|null>,
#                                               "oom": <memory.events 의 oom_kill 누적|null>}, …]}
# cgroup 파일을 직접 읽는다 — `docker stats --no-stream` 은 부를 때마다 데몬이 모든 컨테이너의 CPU 를 약 1초 재어 돌려주므로
# 5초 간격에 비해 무겁다. 컨테이너 목록(docker ps)은 매번 다시 읽는다 — 슬롯이 재시작하면 id 가 바뀐다(재시작 = id 변화).
# cgroup v2(Rocky 9 기본): systemd 드라이버 /sys/fs/cgroup/system.slice/docker-<id>.scope, cgroupfs 드라이버 /sys/fs/cgroup/docker/<id>.
# v1 이면 memory/docker/<id>/memory.usage_in_bytes · limit_in_bytes. 상한이 없으면(max) null
cloop() {
  local out=$1 interval=$2
  local stop=0 next now d ts line id svc p cur max oom first
  trap 'stop=1' TERM INT
  next=$(date +%s%N)
  while [ "$stop" -eq 0 ]; do
    ts=$(date +%s.%3N)
    line="{\"ts\": $ts, \"c\": ["
    first=1
    while read -r id svc; do
      [ -n "$id" ] || continue
      cur= max= oom=null
      for p in "/sys/fs/cgroup/system.slice/docker-$id.scope" "/sys/fs/cgroup/docker/$id"; do
        if [ -r "$p/memory.current" ]; then
          cur=$(cat "$p/memory.current" 2>/dev/null); max=$(cat "$p/memory.max" 2>/dev/null)
          oom=$(awk '$1 == "oom_kill" {print $2}' "$p/memory.events" 2>/dev/null); break
        fi
      done
      if [ -z "$cur" ] && [ -r "/sys/fs/cgroup/memory/docker/$id/memory.usage_in_bytes" ]; then
        p=/sys/fs/cgroup/memory/docker/$id
        cur=$(cat "$p/memory.usage_in_bytes"); max=$(cat "$p/memory.limit_in_bytes")
      fi
      [ -n "$cur" ] || continue
      [[ $max =~ ^[0-9]+$ ]] && [ "${#max}" -lt 19 ] || max=null     # 「max」 · v1 의 무한대(9223…)는 상한 없음
      [[ $oom =~ ^[0-9]+$ ]] || oom=null
      [ "$first" -eq 1 ] || line+=", "
      first=0
      line+="{\"svc\": \"${svc:-?}\", \"id\": \"${id:0:12}\", \"cur\": $cur, \"max\": $max, \"oom\": $oom}"
    done < <(docker ps --no-trunc --format '{{.ID}} {{.Label "com.docker.compose.service"}}' 2>/dev/null)
    printf '%s]}\n' "$line" | gzip -1 >> "$out"
    next=$((next + interval * 1000000000)); now=$(date +%s%N); d=$((next - now))
    if [ "$d" -gt 0 ]; then
      sleep "$(printf '%d.%09d' $((d / 1000000000)) $((d % 1000000000)))" &
      wait $! 2>/dev/null || true
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
    app01|app02)
      # 두 앱 노드가 같은 몫이다(입구 이중화 #404 — APP-02 에도 redis · nginx exporter 가 있다).
      # 슬롯은 그 노드의 사설 IP 하나에 게시된다(APP_PUBLISH_ADDR) — 루프백으로는 닿지 않는다(prometheus/agent*.yml).
      # 비어 있으면 루프백(#404 전 APP-01 · 단일 노드)
      addr=$(grep -E '^APP_PUBLISH_ADDR=' "$cdir/.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"' ")
      addr=${addr:-127.0.0.1}
      targets=(node=http://127.0.0.1:9100/metrics redis=http://127.0.0.1:9121/metrics nginx=http://127.0.0.1:9113/metrics
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

  if alive "$d/containers.pid"; then
    echo "컨테이너 메모리 — 이미 돈다(pid $(cat "$d/containers.pid"))"
  else
    setsid nohup bash "$BASE/node-sampler.sh" cloop "$d/containers-$node.jsonl.gz" "$interval" \
      > "$d/containers.log" 2>&1 < /dev/null &
    echo $! > "$d/containers.pid"
    echo "컨테이너 메모리 시작 — ${interval}초(cgroup memory.current · memory.max · oom_kill)"
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
        # cd 는 따로 둔다 — 「cd && setsid … &」 이면 & 가 목록 전체를 감싸 $! 가 중간 서브셸이 되고, stop 이 실제 프로세스를 놓친다.
        # 비대화형 bash 의 백그라운드 자식은 그룹 리더가 아니므로 setsid 는 fork 없이 제자리에서 새 세션을 연다 — $! = 세션 id
        if (
          cd "$cdir" || exit 1
          setsid nohup docker compose exec -T -e PGAPPNAME=$WAIT_APPNAME "$svc" \
            sh -c 'exec psql -X -At -F, -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
            < "$d/wait.sql" > "$d/wait-$node.csv" 2> "$d/wait.err" &
          echo $! > "$d/wait.pid"
        ); then
          echo "대기 종류 표본 시작 — 1초($svc)"
        else
          echo "경고: Compose 자리가 없다($cdir) — 대기 종류 표본을 받지 않는다"
        fi
      fi ;;
  esac

  if [ "$node" = app01 ]; then
    if alive "$d/nginx.pid"; then
      echo "nginx 접근 로그 — 이미 따라간다(pid $(cat "$d/nginx.pid"))"
    else
      since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
      # 표준 출력(접근 로그)만 받는다. 표준 오류(nginx 오류 로그)는 따로 둔다
      # 대기 종류 표본과 같은 꼴 — setsid 를 한 줄에 단독으로 백그라운드에 둬서 $! 가 세션 id 가 되게
      if (
        cd "$cdir" || exit 1
        id=$(docker compose ps -q nginx) && [ -n "$id" ] || exit 1
        setsid nohup docker logs -f --since "$since" "$id" > "$d/nginx-access.log" 2> "$d/nginx-error.log" < /dev/null &
        echo $! > "$d/nginx.pid"
      ); then
        echo "nginx 접근 로그 따라가기 시작 — $since 부터"
      else
        echo "경고: nginx 컨테이너를 찾지 못했다 — 접근 로그를 받지 않는다"
      fi
    fi
  fi
}

# 멈춘다 — pid 파일의 값은 setsid 로 연 세션의 id(= 세션 리더 pid · 프로세스 그룹 id)다(start 주석).
# 지표 긁기(그룹째 0)는 먼저 리더에만 TERM 을 보내 마지막 gzip 멤버를 마무리하게 기다린다. 그다음 모두 세션째 끝낸다 —
# 리더가 먼저 끝나도 자식(docker compose exec · docker logs · sleep)이 같은 세션에 남을 수 있으므로 세션 id 로 찾는다.
# 끝에 그 세션에 남은 프로세스가 없는지 확인해 알린다
# /proc/<pid>/stat 의 여섯째 칸(이름 괄호 뒤 넷째)이 세션 id 다 — ps(procps) 없이 읽는다
session_pids() {
  local f line a
  for f in /proc/[0-9]*/stat; do
    { read -r line < "$f"; } 2> /dev/null || continue
    read -r -a a <<< "${line##*) }"
    [ "${a[3]:-}" = "$1" ] && echo "${f//[^0-9]/}"
  done
  return 0
}
wait_gone() {  # <반초 횟수> <명령…> — 명령의 출력이 빌 때까지 기다린다
  local n=$1; shift
  for _ in $(seq 1 "$n"); do [ -n "$("$@")" ] || return 0; sleep 0.5; done
  [ -z "$("$@")" ]
}
leader_alive() { kill -0 "$1" 2>/dev/null && echo "$1"; }
stop_one() {  # <pid 파일> <이름> <그룹째>
  local f=$1 what=$2 group=$3 sid left p forced=0
  [ -f "$f" ] || { echo "$what — 돌고 있지 않다(pid 파일 없음)"; return 0; }
  sid=$(cat "$f")
  [[ $sid =~ ^[0-9]+$ ]] || { echo "$what — pid 파일이 올바르지 않다: $f"; return 1; }
  if [ -z "$(session_pids "$sid")" ]; then echo "$what — 돌고 있지 않다"; return 0; fi
  if [ "$group" != 1 ] && kill -0 "$sid" 2>/dev/null; then
    kill -TERM "$sid" 2>/dev/null
    wait_gone 30 leader_alive "$sid" || true
  fi
  # 세션 전체 — 그룹 신호와 세션 구성원 하나하나 둘 다(구성원이 그룹을 바꿨어도 세션은 같다)
  kill -TERM -- "-$sid" 2>/dev/null
  for p in $(session_pids "$sid"); do kill -TERM "$p" 2>/dev/null; done
  if ! wait_gone 30 session_pids "$sid"; then
    forced=1
    kill -KILL -- "-$sid" 2>/dev/null
    for p in $(session_pids "$sid"); do kill -KILL "$p" 2>/dev/null; done
    wait_gone 10 session_pids "$sid" || true
  fi
  left=$(session_pids "$sid" | tr '\n' ' ')
  if [ -n "$left" ]; then echo "$what — 경고: 세션 $sid 에 남은 프로세스: $left"; return 1; fi
  if [ "$forced" = 1 ]; then echo "$what — 강제 종료(남은 프로세스 없음)"; else echo "$what — 멈춤(남은 프로세스 없음)"; fi
}

stop() {
  local round=$1 node=$2 cdir=$3 d svc
  d=$BASE/$round
  [ -d "$d" ] || { echo "표본 자리가 없다: $d"; return 0; }
  stop_one "$d/metrics.pid" "지표 긁기" 0
  stop_one "$d/containers.pid" "컨테이너 메모리" 0
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
  cloop) cloop "$@" ;;
  start) start "$@" ;;
  stop)  stop "$@" ;;
  *) echo "사용법: bash node-sampler.sh start|stop|loop …" >&2; exit 2 ;;
esac
