#!/usr/bin/env bash
# 실제 값 EXPLAIN · 스냅샷 — 질의 파일의 질의마다 EXPLAIN (ANALYZE, BUFFERS, SETTINGS) 를 세 번 돌려 남긴다(INF-06 #390).
#
#   bash explain.sh <회차> <질의 파일> [<질의 파일> …] [--allow-write] [--local]
#   예) bash explain.sh E01 ../../jmeter/queries/login.yaml ../../jmeter/queries/endpoints.yaml
#
# 운영 작업 여부
#   읽기 질의만(기본)   서버 설정 · 데이터를 바꾸지 않는다. 다만 EXPLAIN ANALYZE 는 질의를 **실제로 돌린다** — 그 시간만큼 DB 에
#                      부하가 걸리고 버퍼 캐시가 바뀐다. 회차 도중에 돌리지 않는다(비교 회차에 섞지 않는다 — 계획의 E01 따로).
#                      큰 표를 읽는 질의는 먼저 EXPLAIN(ANALYZE 없이)으로 계획을 본다.
#   --allow-write      **운영 작업 — 서버 상태를 바꾼다.** 쓰기 질의를 BEGIN; EXPLAIN (ANALYZE …) <쓰기>; ROLLBACK; 으로 돌린다.
#                      데이터는 되돌아가지만 죽은 행 · 가시성 맵 해제 · 인덱스 페이지 분할 · WAL 은 남는다 — 끝나면 그 표를
#                      VACUUM 하고 가시성 맵 비율을 다시 본다(측정 README). 이 인자가 없으면 쓰기 질의는 돌리지 않고 <id>.skipped 만 남긴다.
#   --local            로컬 구성(저장소 루트 docker-compose.yml 의 postgres 서비스)에 돈다. 서버에 붙지 않는다. primary · replica 가
#                      같은 컨테이너다(로컬에는 standby 가 없다).
#
# 질의 파일(chaos-harness/jmeter/queries/*.yaml) — 이 모양만 읽는다(PyYAML 없이 — analyze/explain.py 의 load_queries)
#   queries:
#     - id: login_find_auth                     영문 · 숫자 · _ . - (결과 파일 이름)
#       source: UserAuthRepository.findByAuthTypeAndProviderId (UserCommandService.java:111)
#       kind: read                              read | write
#       node: primary                           primary(DB-01) | replica(DB-02)
#       endpoint: auth/login                    선택 — 엔드포인트 키. 집계가 [4.1] 「엔드포인트」 · [5.3] 에 쓴다
#       sql: |                                실제 파라미터 값을 넣은 문장 — GENERIC_PLAN 으로 판정하지 않는다(질의 통계 문장은 리터럴도 $n)
#         SELECT ...
#   snapshots:                                  EXPLAIN 없이 그대로 돌려 결과를 CSV 로(S4 통계상 행 수 vs 실제 · S5 컬럼 통계)
#     - id: user_auth_rows
#       node: primary                           생략하면 primary
#       sql: |
#         SELECT ...
#
# 결과 — results/<회차>/explain/
#   queries/<파일>.yaml   받은 질의 파일들의 사본(집계가 출처 · 종류 · 노드 · 엔드포인트를 여기서 읽는다). 파일끼리 id 가 겹치면 멈춘다
#   <id>.cold.txt         첫 번째 실행(콜드 — 버퍼에 없던 블록이 read 로 보인다)
#   <id>.txt              세 번째 실행(데운 뒤). 두 번째는 버린다
#   <id>.skipped          돌리지 않은 질의와 사유
#   <id>.error            실패한 질의의 psql 오류(값 · 비밀이 아니라 오류 문구)
#   <id>.csv              스냅샷 — COPY ( … ) TO STDOUT CSV HEADER
#   run.json              local · allow_write · utc · 질의 파일 이름들
#
# 접속은 lib.sh 의 psql_node — 노드 DB 컨테이너 안 psql, 유닉스 소켓 · POSTGRES_USER(비밀번호를 다루지 않는다). 출력 -At(머리 없음).
# 표준 입력 — 질의마다 SQL 파일을 그대로 넘긴다. 목록은 배열로 돌린다: `while read` 안에서 ssh · docker compose exec 를 부르면
# 그 명령이 루프의 표준 입력(남은 목록)을 삼킨다.
#   EXPLAIN_STATEMENT_TIMEOUT   주면 질의마다 SET statement_timeout = '<값>' 을 앞에 붙인다(예: 120s). 기본은 붙이지 않는다
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

usage() { echo "사용법: bash explain.sh <회차> <질의 파일> [<질의 파일> …] [--allow-write] [--local]" >&2; exit 2; }
ROUND=${1:-}; [ -n "$ROUND" ] || usage
shift
check_round "$ROUND"
ALLOW_WRITE=0 LOCAL=0 QFILES=()
for a in "$@"; do
  case $a in
    --allow-write) ALLOW_WRITE=1 ;;
    --local) LOCAL=1 ;;
    -*) usage ;;
    *) [ -f "$a" ] || die "질의 파일이 없다: $a"; QFILES+=("$a") ;;
  esac
done
[ "${#QFILES[@]}" -gt 0 ] || usage

RD=$(round_dir "$ROUND")
OUT=$RD/explain
mkdir -p "$OUT"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
REPO_ROOT=$(cd "$MEASURE_ROOT/../.." && pwd)

# python — Windows 의 python3 는 스토어 안내 껍데기일 수 있어 실제로 도는 쪽을 고른다(infra/grafana/apply.sh 와 같다).
# Windows 의 python 은 /c/... 경로를 읽지 못한다 — cygpath 가 있으면 Windows 경로로 넘긴다
PY=
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import json' >/dev/null 2>&1; then PY=$c; break; fi
done
[ -n "$PY" ] || die "python 을 찾지 못했다 — 질의 파일을 읽는 데 필요하다"
export PYTHONIOENCODING=utf-8
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# 질의 파일들 → 질의 · 스냅샷 SQL 파일과 목록(「query|snapshot <탭> id <탭> kind <탭> node」). 형식 오류 · id 겹침이면 여기서 멈춘다.
# python 은 측정 폴더에서 도므로 질의 파일은 절대 경로로 넘긴다
WIN_FILES=()
for f in "${QFILES[@]}"; do WIN_FILES+=("$(winpath "$(cd "$(dirname "$f")" && pwd)/$(basename "$f")")"); done
LIST=$(cd "$MEASURE_ROOT" && "$PY" -m analyze.explain split "$(winpath "$WORK")" "${WIN_FILES[@]}" | tr -d '\r') \
  || die "질의 파일을 읽지 못했다: ${QFILES[*]}"
# 사본 — 이전 실행의 사본을 지우고 이번 파일들만 둔다(집계가 이 폴더의 모든 *.yaml 을 읽는다)
rm -rf "$OUT/queries" "$OUT/queries.yaml"
mkdir -p "$OUT/queries"
for f in "${QFILES[@]}"; do cp "$f" "$OUT/queries/$(basename "$f")"; done
mapfile -t ITEMS <<< "$LIST"
[ -n "${ITEMS[0]:-}" ] || die "질의 파일에 queries · snapshots 가 없다"

# run_sql <primary|replica> — SQL 을 표준 입력으로 받는다
run_sql() {
  if [ "$LOCAL" -eq 1 ]; then
    # 로컬 — 저장소 루트 Compose 의 postgres 서비스. 컨테이너 환경의 POSTGRES_USER · POSTGRES_DB(값을 이 셸이 보지 않는다)
    docker compose -f "$REPO_ROOT/docker-compose.yml" exec -T postgres \
      sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At'
  else
    case $1 in
      primary) psql_node db01 ;;
      replica) psql_node db02 ;;
      *) die "node 는 primary | replica: $1" ;;
    esac
  fi
}

where() { if [ "$LOCAL" -eq 1 ]; then echo "로컬 postgres"; else case $1 in primary) echo DB-01 ;; *) echo DB-02 ;; esac; fi; }

PREFIX=""
if [ -n "${EXPLAIN_STATEMENT_TIMEOUT:-}" ]; then
  [[ $EXPLAIN_STATEMENT_TIMEOUT =~ ^[0-9]+(ms|s|min)?$ ]] || die "EXPLAIN_STATEMENT_TIMEOUT 형식: 120s · 500ms · 2min"
  PREFIX="SET statement_timeout = '$EXPLAIN_STATEMENT_TIMEOUT';"
fi

if [ "$LOCAL" -eq 0 ] && [ "$ALLOW_WRITE" -eq 1 ]; then
  warn "운영 작업 — 쓰기 질의를 BEGIN … ROLLBACK 으로 돈다. 끝나면 그 표를 VACUUM 한다(측정 README)"
fi

done_n=0 skip_n=0 fail_n=0
for line in "${ITEMS[@]}"; do
  IFS=$'\t' read -r what id kind node <<< "$line"
  rm -f "$OUT/$id.error" "$OUT/$id.skipped"
  if [ "$what" = snapshot ]; then
    log "[$(where "$node")] 스냅샷 $id"
    { printf '%s\nCOPY (\n' "$PREFIX"; cat "$WORK/s-$id.sql"; printf ') TO STDOUT WITH (FORMAT csv, HEADER);\n'; } > "$WORK/run.sql"
    if run_sql "$node" < "$WORK/run.sql" > "$OUT/$id.csv" 2> "$WORK/err"; then
      log "  $(fsize "$OUT/$id.csv") B  $OUT/$id.csv"; done_n=$((done_n + 1))
    else
      cp "$WORK/err" "$OUT/$id.error"; rm -f "$OUT/$id.csv"; warn "  [$id] 실패 — $OUT/$id.error"; fail_n=$((fail_n + 1))
    fi
    continue
  fi
  if [ "$kind" = write ] && [ "$ALLOW_WRITE" -ne 1 ]; then
    echo "쓰기 질의 — --allow-write 없이 돌리지 않았다(운영 작업: BEGIN … ROLLBACK 뒤 VACUUM 필요)" > "$OUT/$id.skipped"
    log "[$id] 쓰기 질의 — 건너뜀(--allow-write 없음)"; skip_n=$((skip_n + 1))
    continue
  fi
  log "[$(where "$node")] EXPLAIN $id ($kind) × 3"
  {
    [ "$kind" = write ] && echo "BEGIN;"
    [ -n "$PREFIX" ] && echo "$PREFIX"
    printf 'EXPLAIN (ANALYZE, BUFFERS, SETTINGS)\n'; cat "$WORK/q-$id.sql"; echo ";"
    [ "$kind" = write ] && echo "ROLLBACK;"
    true
  } > "$WORK/run.sql"
  ok=1
  for i in 1 2 3; do
    if ! run_sql "$node" < "$WORK/run.sql" > "$WORK/out$i" 2> "$WORK/err"; then
      cp "$WORK/err" "$OUT/$id.error"; warn "  [$id] ${i}번째 실패 — $OUT/$id.error"; ok=0; break
    fi
  done
  if [ "$ok" -eq 1 ]; then
    tr -d '\r' < "$WORK/out1" > "$OUT/$id.cold.txt"
    tr -d '\r' < "$WORK/out3" > "$OUT/$id.txt"
    log "  $(grep -m1 'Execution Time' "$OUT/$id.txt" || echo '실행 시간 줄 없음')"
    done_n=$((done_n + 1))
  else
    fail_n=$((fail_n + 1))
  fi
done

NAMES=""
for f in "${QFILES[@]}"; do NAMES+="${NAMES:+, }$(json_str "$(basename "$f")")"; done
printf '{"round": %s, "local": %s, "allow_write": %s, "utc": %s, "query_files": [%s]}\n' \
  "$(json_str "$ROUND")" "$([ "$LOCAL" -eq 1 ] && echo true || echo false)" "$([ "$ALLOW_WRITE" -eq 1 ] && echo true || echo false)" \
  "$(json_str "$(utc_now_ms)")" "$NAMES" > "$OUT/run.json"
log "끝 — 완료 $done_n · 건너뜀 $skip_n · 실패 $fail_n → $OUT"
if [ "$ALLOW_WRITE" -eq 1 ] && [ "$LOCAL" -eq 0 ]; then
  warn "쓰기 질의를 돌렸다 — 그 표를 VACUUM 하고 가시성 맵 비율을 다시 본다"
fi
[ "$fail_n" -eq 0 ]
