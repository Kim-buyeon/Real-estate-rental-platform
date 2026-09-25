#!/bin/sh
# WAL 아카이브 한 건 — postgres · postgres-standby 의 archive_command 가 부른다(INF-04, docker-compose.yml 의 x-postgres-command).
#
#   rental-archive-wal.sh %p %f      %p 는 데이터 디렉터리 기준 세그먼트 경로, %f 는 파일 이름(서버가 채운다)
#
# POSIX sh 다 — postgres:17-alpine 에는 bash 가 없고 busybox sh 가 돈다. cmp · sync · mv 도 busybox 것이다
# (busybox 1.37 의 sync 는 FILE 인자를 받아 그 파일만 fsync 한다).
#
# ── 이미 같은 이름이 있을 때 — PostgreSQL 17 문서 25.3.1 ──
#   「When an archive command encounters a pre-existing file, it should return a zero status if the WAL file has
#    identical contents to the pre-existing archive and the pre-existing archive is fully persisted to storage.
#    If … different contents …, the archive command must return a nonzero status.」
#  같은 내용이면 0 — 아카이브 뒤 서버가 성공 기록을 남기기 전에 멈췄다 다시 뜨면 같은 세그먼트를 다시 보낸다.
#  test ! -f 만으로는 이때 영구히 실패해 pg_wal 이 자란다. 다른 내용이면 0 이 아니다 — 덮어쓰면 아카이브가 망가진다.
#  (승격 뒤 새 primary 는 새 타임라인 이름으로 쓰므로 구 primary 의 파일과 이름이 겹치지 않는다.)
#  「fully persisted」 — 있는 파일도 sync 한 뒤 0 을 돌려준다. 앞선 시도가 mv 뒤 디렉터리 sync 전에 끊겼을 수 있다.
#
# ── 원자성 — 임시 이름으로 받고 sync 뒤 mv 한다 ──
#  최종 이름에 바로 cp 하다 kill(컨테이너 정지 · OOM)되면 잘린 세그먼트가 그 이름으로 남는다. 그러면 다음 시도는
#  「다른 내용」으로 영구 실패하고, PITR 은 그 잘린 파일을 재생하다 깨진다. 임시 이름은 점으로 시작해
#  pg_archivecleanup · 복구의 restore_command 가 세그먼트로 보지 않는다. 끊겨 남은 임시 파일은 같은 세그먼트의 다음 시도가 덮어쓴다.
#
# ── 권한 — 0640, 그룹 backup ──
#  WAL 전송 정기 작업(infra/backup/wal-ship.sh)이 호스트의 backup 계정으로 돌며 이 파일을 읽어 노드 밖으로 보낸다.
#  서버의 umask 로는 0600 이라 그 계정이 읽지 못한다. 디렉터리가 setgid(2770, 그룹 backup)라 새 파일의 그룹은
#  이미 backup 이다 — 그룹 읽기만 연다. 쓰기 · 삭제는 디렉터리 권한 그대로다.
#  임시 이름일 때 바꾼다 — mv 뒤에 바꾸면 최종 이름이 잠깐 0600 으로 보이고, 그 사이 끊기면 그대로 남는다.
#  이미 있는 파일(같은 내용 경로)은 건드리지 않는다 — 이 변경 전에 아카이브된 파일은 반영 때 한 번 바꾼다.
set -eu

[ "$#" -eq 2 ] || { echo "rental-archive-wal: 인자 두 개(%p %f)가 필요하다" >&2; exit 2; }
src=$1
name=$2
# 컨테이너 안 경로 — docker-compose.yml 이 호스트 /var/backups/rental/wal 을 여기에 마운트한다
dir=/archive/wal
dest="$dir/$name"
tmp="$dir/.$name.tmp"

if [ -e "$dest" ]; then
  if cmp -s "$src" "$dest"; then
    sync "$dest" "$dir"
    exit 0
  fi
  echo "rental-archive-wal: $dest 가 이미 있고 내용이 다르다 — 덮어쓰지 않는다" >&2
  exit 1
fi

cp "$src" "$tmp"
chmod 0640 "$tmp"
sync "$tmp"
mv -f "$tmp" "$dest"
sync "$dir"
