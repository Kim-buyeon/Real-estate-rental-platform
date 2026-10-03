#!/usr/bin/env bash
# 슬롯 JVM 프로파일 — async-profiler 로 CPU(itimer) · 할당을 일정 시간 표본한다(INF-06 #378). **운영 작업 — 도는 JVM 에 붙는다.**
#
#   bash profiler.sh <app01|app02> <app-1|app-2> <cpu|alloc> <초> <회차>
#
# 결과 — results/<회차>/profiler/<노드>-<슬롯>-<모드>.collapsed(접힌 스택 — 집계가 상위 메서드 · JIT 비중을 낸다) · .html(불꽃 그래프)
#
# 순서: 노드 여유 메모리 확인 → (처음 한 번) 로컬에 받아 체크섬 대조 → 노드에 올려 다시 대조 · 풀기 → 슬롯 컨테이너 안 /tmp/ap 로 복사
# → JVM 사용자로 start → <초> 대기 → dump(불꽃 그래프) → stop(접힌 스택) → 결과를 노드로 꺼내고 → 로컬로 받는다 → 컨테이너 · 노드 정리.
#
#  - 라이브러리는 **컨테이너 안에** 있어야 한다 — JVM 이 자기 파일시스템에서 dlopen 한다. 경로는 늘 /tmp/ap 로 같게 둔다:
#    한 번 붙은 라이브러리는 JVM 이 끝날 때까지 실린 채 남고, 같은 경로면 다음 실행이 그것을 다시 쓴다.
#    끝나면 파일은 지우지만 실린 라이브러리는 슬롯이 재생성될 때까지 메모리에 남는다(멈춘 상태라 표본하지 않는다).
#  - 이벤트 — cpu 는 itimer(perf_events 없이 — 컨테이너의 seccomp · perf_event_paranoid 와 무관), alloc 은 TLAB 표본(JVMTI).
#  - JVM 은 컨테이너의 PID 1(이미지 ENTRYPOINT 가 exec 형식 java)이고 사용자는 app 이다. 붙는 쪽(asprof)이 같은 사용자여야 해서
#    /proc/<pid> 의 uid 로 docker exec -u 한다. 실행 이미지(JRE)에 pgrep · jps 가 없어 /proc 를 훑어 java 를 찾는다.
#  - 2026-09-28 APP-01 이 프로파일러를 붙이던 중 메모리가 바닥나 멈췄다. APP-01 의 MemAvailable 이 MEM_MIN_MB(기본 300) 미만이면
#    거부한다. APP-02 는 값만 보인다.
#
# 버전 고정 — 아래 두 값은 GitHub 릴리스(async-profiler/async-profiler v4.5, linux-x64)의 자산 이름과 그 릴리스가 낸 sha256 이다.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

AP_VERSION=4.5
AP_SHA256=89546fbb9ee0fc5496c7edd4099b0709489bc78b0d8057ccbb4b801f6b032b62
AP_NAME=async-profiler-$AP_VERSION-linux-x64
AP_URL=https://github.com/async-profiler/async-profiler/releases/download/v$AP_VERSION/$AP_NAME.tar.gz
CACHE=$MEASURE_ROOT/.cache
MEM_MIN_MB=${MEM_MIN_MB:-300}

usage() { echo "사용법: bash profiler.sh <app01|app02> <app-1|app-2> <cpu|alloc> <초> <회차>" >&2; exit 2; }
[ $# -eq 5 ] || usage
NODE=$1 SLOT=$2 MODE=$3 SECS=$4 ROUND=$5
case $NODE in app01|app02) ;; *) usage ;; esac
case $SLOT in app-1|app-2) ;; *) usage ;; esac
case $MODE in cpu) EVENT=itimer ;; alloc) EVENT=alloc ;; *) usage ;; esac
[[ $SECS =~ ^[1-9][0-9]*$ ]] || usage
check_round "$ROUND"
L=$(node_label "$NODE")
OUTDIR=$(round_dir "$ROUND")/profiler
OUTBASE=$OUTDIR/$NODE-$SLOT-$MODE
NODE_AP=$MEASURE_TMP/ap

# ── 여유 메모리 ───────────────────────────────────────────────────────────────────────
AVAIL=$(on_node "$NODE" "awk '/^MemAvailable:/ { print int(\$2 / 1024) }' /proc/meminfo")
[[ $AVAIL =~ ^[0-9]+$ ]] || die "[$L] MemAvailable 을 읽지 못했다: '$AVAIL'"
log "[$L] MemAvailable ${AVAIL} MiB"
if [ "$NODE" = app01 ] && [ "$AVAIL" -lt "$MEM_MIN_MB" ]; then
  die "[$L] 여유 메모리 ${AVAIL} MiB < ${MEM_MIN_MB} MiB — 붙이지 않는다(2026-09-28 APP-01 이 붙이던 중 메모리가 바닥나 멈췄다)"
fi

# ── 로컬 사본 — 처음 한 번 받는다 ─────────────────────────────────────────────────────────
mkdir -p "$CACHE"
TGZ=$CACHE/$AP_NAME.tar.gz
if [ ! -f "$TGZ" ]; then
  log "async-profiler $AP_VERSION 받음 — $AP_URL"
  curl -fsSL -o "$TGZ.part" "$AP_URL"
  mv -f "$TGZ.part" "$TGZ"
fi
echo "$AP_SHA256  $TGZ" | sha256sum -c --quiet - || { rm -f "$TGZ"; die "체크섬이 다르다 — 받은 파일을 지웠다. 버전 · 값을 확인한다"; }

# ── 노드 · 컨테이너 정리 — 끝나거나 멈추면 늘 ──────────────────────────────────────────────────
CID="" JPID="" JUID="" STARTED=0
cleanup() {
  # 중간에 멈췄으면(Ctrl-C · 실패) 표본을 먼저 멈춘다 — 그대로 두면 JVM 안에서 계속 돈다
  if [ "$STARTED" -eq 1 ]; then
    on_node "$NODE" "docker exec -u $JUID $CID /tmp/ap/bin/asprof stop $JPID" > /dev/null 2>&1 || true
  fi
  if [ -n "$CID" ]; then
    on_node "$NODE" "docker exec -u 0 $CID rm -rf /tmp/ap /tmp/rental-ap.collapsed /tmp/rental-ap.html" > /dev/null 2>&1 || true
  fi
  on_node "$NODE" "rm -rf $(q "$NODE_AP")" > /dev/null 2>&1 || true
}
trap cleanup EXIT

log "[$L] 노드에 올림"
on_node "$NODE" "mkdir -p $(q "$NODE_AP") && cat > $(q "$NODE_AP/ap.tgz")" < "$TGZ"
on_node "$NODE" "cd $(q "$NODE_AP") && echo '$AP_SHA256  ap.tgz' | sha256sum -c --quiet - && tar xzf ap.tgz" \
  || die "[$L] 노드에서 체크섬 · 풀기 실패"

CID=$(on_node "$NODE" "cd $(q "$NODE_DIR") && docker compose ps -q $SLOT")
[ -n "$CID" ] || die "[$L] $SLOT 컨테이너가 없다"
on_node "$NODE" "docker exec -u 0 $CID rm -rf /tmp/ap && docker cp $(q "$NODE_AP/$AP_NAME") $CID:/tmp/ap"

# JVM pid — PID 1 이 java 면 그것, 아니면 /proc 를 훑는다
JPID=$(on_node "$NODE" "docker exec $CID sh -c 'if [ \"\$(cat /proc/1/comm)\" = java ]; then echo 1; exit; fi; for p in /proc/[0-9]*; do [ \"\$(cat \$p/comm 2>/dev/null)\" = java ] && { echo \${p#/proc/}; exit; }; done; echo 1'")
JUID=$(on_node "$NODE" "docker exec $CID stat -c %u /proc/$JPID")
log "[$L $SLOT] JVM pid $JPID · uid $JUID · $MODE($EVENT) ${SECS}초"

asprof() { on_node "$NODE" "docker exec -u $JUID $CID /tmp/ap/bin/asprof $* $JPID"; }

asprof start -e "$EVENT"
STARTED=1
sleep "$SECS"
# 불꽃 그래프는 멈추지 않고 덤프하고(dump), 멈추면서 접힌 스택을 쓴다 — 한 번의 표본에서 두 형식을 얻는다.
# 두 명령 사이(수십 ms)의 표본은 접힌 스택에만 있다
DUMP_OK=1
asprof dump -o flamegraph --title "$NODE-$SLOT-$MODE-$ROUND" -f /tmp/rental-ap.html || { DUMP_OK=0; warn "[$L] 불꽃 그래프 덤프 실패 — 접힌 스택만 남긴다"; }
asprof stop -o collapsed -f /tmp/rental-ap.collapsed
STARTED=0

mkdir -p "$OUTDIR"
on_node "$NODE" "docker exec $CID cat /tmp/rental-ap.collapsed" > "$OUTBASE.collapsed"
log "  $(fsize "$OUTBASE.collapsed") B  $OUTBASE.collapsed"
if [ "$DUMP_OK" -eq 1 ]; then
  on_node "$NODE" "docker exec $CID cat /tmp/rental-ap.html" > "$OUTBASE.html"
  log "  $(fsize "$OUTBASE.html") B  $OUTBASE.html"
fi
AFTER=$(on_node "$NODE" "awk '/^MemAvailable:/ { print int(\$2 / 1024) }' /proc/meminfo" || echo '?')
log "[$L] 끝 — MemAvailable ${AFTER} MiB(시작 ${AVAIL})"
