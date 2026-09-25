#!/usr/bin/env bash
# 설정 사본 — 서버 운영 기반 설계서 5.1(대상 · 암호화) · 5.4(보존) · 7.2(매주 일 01:00), 운영 절차서 9.3.
# 정기 작업(rental-config-copy.service)이 부른다.
#
#   bash config-copy.sh        그 시각의 설정 사본을 한 번 만든다(root 로)
#
# 순서: 리비전 기록 → infra/ 와 함께 tar.gz 스트림 → 암호화 → 목적지 적재 → 최신 CONFIG_KEEP 개만 남기고 삭제.
# 실패하면 0 이 아닌 코드로 끝난다 — journalctl -u rental-config-copy 로 본다(설계서 7.1).
#
# 복원: backup 계정으로 gpg --batch --passphrase-file <키> --decrypt config-<시각>.tar.gz.gpg | tar -xz
#       — REVISION(커밋 · 작업 트리 변경)과 infra/(.env 포함)가 나온다.
#
# ── 두 계정으로 나눠 돈다 ──
#  - 읽기는 root 다. 원본 체크아웃이 ec2-user 홈(700) 안이라 backup 계정은 읽지 못한다. 그렇다고 홈을 풀면 .env 가 든
#    체크아웃을 여는 셈이다(논리 백업 유닛과 같은 이유). root 는 읽기만 하고 평문을 파일로 남기지 않는다 — 스트림으로만 넘긴다.
#  - 암호화 · 쓰기 · 정리는 backup 계정이다. NAS 공유가 root_squash 라 root 는 공유에 쓸 수 없다(설계서 5.3).
#    백업 디렉터리 소유자도 backup 이다(설계서 6.2).
#
# ── 암호화 ──
#  묶음 전체를 gpg 대칭 암호화(AES256, --passphrase-file)한다 — 논리 백업(pg-dump.sh)과 같은 도구 · 같은 키 파일이다(설계서 5.1).
#  .env 만이 아니라 전체를 암호화한다 — 나눠 두면 복원 때 짝을 맞춰야 하고, 나머지 설정도 공격면 정보다.
#  키는 저장소가 아니라 노드에 두고 NAS 와 분리 보관한다(기술 스택 3장). 이 스크립트는 값을 읽지도 출력하지도 않는다.
set -euo pipefail
umask 077

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }

# ── 원본 — 값이 없으면 지어내지 않고 실패한다. 값은 노드의 EnvironmentFile 에 둔다 ──
# 체크아웃 루트. 체크아웃 자리가 바뀌면(ec2-user → deploy) 이 값만 바뀐다
: "${CONFIG_SRC:?CONFIG_SRC 가 필요하다 — 체크아웃 루트를 EnvironmentFile 에 둔다(운영 절차서 9.3)}"
[ -d "$CONFIG_SRC/infra" ] || { log "!!! $CONFIG_SRC/infra 가 없다"; exit 1; }

# ── 목적지 ──
# 단일 노드에서는 자기 자신에게 건 NFS 공유라 원본과 같은 디스크에 있다 — 노드 소실은 막지 못한다(S3 사본은 범위 밖)
CONFIG_DEST=${CONFIG_DEST:-/mnt/nas/backup/config}
BACKUP_USER=${BACKUP_USER:-backup}

# ── 보존 ──
# **잠정 최신 4개** — 주 1회 × 4주, 물리 백업(4개)과 같은 주기다. 설계서 5.4 가 설정 사본 보존을 미확정으로 두고
# 「첫 백업 크기 · NAS 볼륨 크기를 보고 정한다」고 했다. 재조정 시점은 설계서 10장의 「운영 1개월 뒤」다.
CONFIG_KEEP=${CONFIG_KEEP:-4}

# ── 키 ──
: "${BACKUP_KEY_FILE:?BACKUP_KEY_FILE 이 필요하다 — 복호화 키는 저장소가 아니라 노드에 두고 NAS 와 분리 보관한다(기술 스택 3장)}"

# backup 계정으로 명령을 돌린다. runuser 는 -l 없이 부르면 HOME 을 바꾸지 않아 gpg 가 root 의 ~/.gnupg 를 쓰려다
# 실패한다 — backup 의 홈(거기 .gnupg 가 있다)을 passwd 에서 읽어 HOME 으로 준다. 경로를 지어내지 않는다.
BACKUP_HOME=$(getent passwd "$BACKUP_USER" | cut -d: -f6)
[ -n "$BACKUP_HOME" ] || { log "!!! 계정 $BACKUP_USER 의 홈을 찾지 못했다"; exit 1; }
as_backup() { runuser -u "$BACKUP_USER" -- env HOME="$BACKUP_HOME" "$@"; }

# 키는 root 가 아니라 backup 이 읽는다 — root 로 확인하면 권한 문제를 놓친다
as_backup test -r "$BACKUP_KEY_FILE" || { log "!!! $BACKUP_USER 가 키 파일을 읽을 수 없다: $BACKUP_KEY_FILE"; exit 1; }

STAMP=$(date '+%Y%m%d-%H%M%S')
NAME="config-$STAMP.tar.gz.gpg"
TMP_NAME=".$NAME.tmp"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; as_backup rm -f -- "$CONFIG_DEST/$TMP_NAME" 2>/dev/null || true' EXIT

# ── 1. 리비전 기록 ──
# 어느 커밋의 설정이고 작업 트리에 커밋 안 된 변경이 있었는지 남긴다. root 가 남의 저장소를 읽으면 git 이 소유자 불일치로
# 거부한다(safe.directory) — 이 저장소 하나만 이 명령에 한해 허용한다. 전역 설정은 바꾸지 않는다.
git_src() { git -c safe.directory="$CONFIG_SRC" -C "$CONFIG_SRC" "$@"; }
{
  printf 'commit %s\n' "$(git_src rev-parse HEAD)"
  printf 'status --porcelain\n'
  git_src status --porcelain
} > "$WORK/REVISION"

# ── 2. 목적지 준비 ──
if ! as_backup test -d "$CONFIG_DEST"; then
  log "목적지 생성 — $CONFIG_DEST"
  as_backup install -d -m 700 "$CONFIG_DEST"
fi

# ── 3. 묶기 → 암호화 → 임시 이름으로 쓰기 ──
# 평문 파일을 만들지 않는다 — tar 스트림을 그대로 암호화한다. set -o pipefail 이라 tar 가 실패해도 여기서 멈춘다.
# 임시 이름(점으로 시작)으로 쓰고 성공한 뒤에만 옮긴다 — 도중에 끊긴 파일이 정리 대상 이름으로 남지 않게 한다.
log "묶기 · 암호화 — $CONFIG_SRC/infra → $CONFIG_DEST/$NAME"
tar -czf - -C "$WORK" REVISION -C "$CONFIG_SRC" infra \
  | as_backup gpg --batch --yes --quiet --symmetric --cipher-algo AES256 \
      --passphrase-file "$BACKUP_KEY_FILE" --output "$CONFIG_DEST/$TMP_NAME"
as_backup test -s "$CONFIG_DEST/$TMP_NAME" || { log "!!! 암호화 결과가 비어 있다"; exit 1; }

# ── 4. 적재 ──
as_backup chmod 600 "$CONFIG_DEST/$TMP_NAME"
as_backup mv -- "$CONFIG_DEST/$TMP_NAME" "$CONFIG_DEST/$NAME"
log "적재 — $CONFIG_DEST/$NAME ($(as_backup stat -c %s "$CONFIG_DEST/$NAME") 바이트)"

# ── 5. 최신 CONFIG_KEEP 개만 남긴다 ──
# 이 스크립트가 만든 이름만 지운다 — 같은 경로의 다른 파일을 건드리지 않는다. 이름의 시각이 고정 폭이라 이름순이 시각순이다.
log "보존 최신 ${CONFIG_KEEP}개 밖 정리"
mapfile -t ALL < <(as_backup find "$CONFIG_DEST" -maxdepth 1 -type f -name 'config-*.tar.gz.gpg' | sort)
if [ "${#ALL[@]}" -gt "$CONFIG_KEEP" ]; then
  OLD=("${ALL[@]:0:${#ALL[@]}-CONFIG_KEEP}")
  printf '%s\n' "${OLD[@]}"
  as_backup rm -f -- "${OLD[@]}"
fi

log "설정 사본 완료 — $NAME"
