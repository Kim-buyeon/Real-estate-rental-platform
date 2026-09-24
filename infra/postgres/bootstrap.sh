#!/bin/sh
# PostgreSQL 공통 초기화 — postgres · postgres-standby 두 서비스가 entrypoint 로 쓴다(INF-03).
# Compose 가 이 파일을 :ro 로 마운트하고 /bin/sh 로 부른다 — 실행 비트에 기대지 않는다.
# 이미지(postgres:17-alpine)의 /bin/sh 는 busybox ash 다. bash 문법을 쓰지 않는다.
#
# 하는 일은 셋이고, 끝나면 명령 목록(x-postgres-command)을 "$@" 로 받아 이미지의 docker-entrypoint.sh 로 넘긴다.
#   1. 복제 계정 변수가 둘 다 있으면 비밀번호 파일(PGPASSFILE)을 tmpfs 에 쓴다. 없으면 건너뛴다(평시 primary).
#   2. 데이터 디렉터리가 비었고(PG_VERSION 이 없고) 받을 곳(PG_BOOTSTRAP_FROM)이 있으면 거기서 pg_basebackup 으로 받는다.
#      - postgres-standby 는 늘 postgres 에서 받는다(슬롯 standby_1).
#      - postgres 는 평시 받을 곳이 비어 있다. 페일백 때만 postgres-standby 로 채워 구 primary 를 standby 로 되받는다
#        (운영 절차서 6.2).
#   3. 받을 곳이 비면 아무것도 하지 않는다 — 이미지 기본 동작(빈 디렉터리면 initdb, 있으면 기존 데이터로 기동) 그대로다.
#
# 받기 —
#   - 받는 것은 데이터 디렉터리가 비었을 때만이다. 재기동마다 전체를 복사하지 않는다.
#   - 슬롯(PG_BOOTSTRAP_SLOT)은 받는 쪽이 아니라 **원본에 미리 만든다**(-S 는 있는 슬롯을 쓴다). pg_basebackup -C 는
#     슬롯이 이미 있으면 실패하므로 재구축할 때마다 걸린다.
#   - -R 이 standby.signal 과 postgresql.auto.conf 의 primary_conninfo(· -S 를 주면 primary_slot_name)를 쓴다.
#   - -X stream 은 복사하는 동안의 WAL 을 따로 받아 받은 사본만으로 일관되게 한다. -c fast 는 시작 체크포인트를 바로 한다.
#   - 이미지에 gosu · pg_basebackup 이 있다. 데이터 디렉터리는 postgres(uid 70) 소유 · 700 이어야 서버가 뜬다.
#   - 받는 도중 실패하면 pg_basebackup 이 받은 것을 지운다. 컨테이너가 강제로 죽어 조각이 남았는데 PG_VERSION 까지
#     들어왔다면 서버가 복구 시작점을 못 찾고 기동을 거부한다(조용히 뜨지 않는다) — 그때는 볼륨을 지우고 다시 올린다.
#   - 받은 뒤에는 데이터 디렉터리가 있으므로 docker-entrypoint.sh 가 initdb 를 건너뛴다.
#   - 받을 곳이 있는데 복제 계정이 비어 있으면 실패시킨다 — 계정을 지어내지 않는다.
#
# 복제 비밀번호는 **볼륨에 쓰지 않는다.** pg_basebackup -R 은 접속에 쓴 비밀번호를 primary_conninfo 에 평문으로
# 옮겨 적는다(PGPASSWORD 로 받았을 때 postgresql.auto.conf 에 password=… 가 남는 것을 17.11 에서 확인). 그러면
# 비밀번호가 데이터 디렉터리와 그것을 복사하는 모든 곳(볼륨 사본 · 재구축 원본)에 따라간다. 대신
#   - 기동할 때마다 이 스크립트가 환경 변수로 비밀번호 파일(PGPASSFILE)을 tmpfs(메모리)에 쓴다. pg_basebackup 과 이후
#     walreceiver 가 이 파일로 인증한다. 파일 안의 : 와 \ 는 \ 로 이스케이프한다(libpq 비밀번호 파일 형식).
#   - 그러면 -R 은 password 대신 passfile=<경로> 를 적는다. 경로는 재기동마다 다시 채워지므로 볼륨에 비밀이 남지 않는다.
#   - 비밀번호 환경 변수는 서버로 넘기기 전에 지운다 — 서버 프로세스 환경에 남기지 않는다.
# 비밀번호를 바꾸면 infra/.env 를 고치고 두 서비스를 재생성한다(파일이 다시 쓰인다).
set -eu

name="bootstrap"
from="${PG_BOOTSTRAP_FROM:-}"
slot="${PG_BOOTSTRAP_SLOT:-}"
repl_user="${POSTGRES_REPLICATION_USER:-}"
repl_pass="${POSTGRES_REPLICATION_PASSWORD:-}"

if [ -n "$repl_user" ] && [ -n "$repl_pass" ]; then
  : "${PGPASSFILE:?$name: PGPASSFILE 가 비어 있다 — Compose 의 environment}"
  esc() { printf '%s' "$1" | sed 's/[\\:]/\\&/g'; }
  (
    umask 077
    printf '*:*:replication:%s:%s\n' "$(esc "$repl_user")" "$(esc "$repl_pass")" > "$PGPASSFILE"
  )
  chown -R postgres:postgres "${PGPASSFILE%/*}"
fi
unset POSTGRES_REPLICATION_PASSWORD repl_pass

if [ ! -s "$PGDATA/PG_VERSION" ] && [ -n "$from" ]; then
  if [ -z "$repl_user" ] || [ ! -s "${PGPASSFILE:-/nonexistent}" ]; then
    echo "$name: 받을 곳($from)이 있는데 복제 계정(POSTGRES_REPLICATION_USER · POSTGRES_REPLICATION_PASSWORD)이 비어 있다 — infra/.env" >&2
    exit 1
  fi
  mkdir -p "$PGDATA"
  find "$PGDATA" -mindepth 1 -delete
  chown postgres:postgres "$PGDATA"
  chmod 700 "$PGDATA"
  echo "$name: 데이터 디렉터리가 비어 있다 — $from 에서 pg_basebackup 으로 받는다${slot:+(슬롯 $slot)}"
  if [ -n "$slot" ]; then
    gosu postgres pg_basebackup -h "$from" -U "$repl_user" -w -D "$PGDATA" -S "$slot" -R -X stream -c fast
  else
    gosu postgres pg_basebackup -h "$from" -U "$repl_user" -w -D "$PGDATA" -R -X stream -c fast
    # 원본이 옛 standby 면 받은 postgresql.auto.conf 에 원본 자신의 primary_slot_name 이 따라온다. 그 이름을 이어받으면
    # 원본에 그 슬롯이 없을 때 붙지 못한다(2026-09-24 노드 리허설, #205 · #207). 슬롯을 지정해 받는 경우는 -S 가 적은
    # 이름이 남으므로 여기서만 지운다. postgres 로 돌려 파일 소유를 유지한다(busybox sed -i).
    echo "$name: 원본 설정에서 이어받은 슬롯 이름을 지운다"
    gosu postgres sed -i '/^[[:space:]]*primary_slot_name[[:space:]]*=/d' "$PGDATA/postgresql.auto.conf"
  fi
fi

exec docker-entrypoint.sh "$@"
