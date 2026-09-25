#!/usr/bin/env bash
# 설정 사본 — 서버 운영 기반 설계서 5.1(대상 · 암호화) · 5.4(보존) · 7.2(매주 일 01:00), 운영 절차서 9.3.
# 정기 작업(rental-config-copy.service)이 부른다.
#
#   bash config-copy.sh        그 시각의 설정 사본을 한 번 만든다(root 로)
#
# 순서: 리비전 기록 → infra/ 와 함께 tar.gz 스트림 → 암호화 → 목적지 적재 → (CONFIG_S3_URI 가 있으면) S3 사본 →
#       최신 CONFIG_KEEP 개만 남기고 삭제.
# 실패하면 0 이 아닌 코드로 끝난다 — journalctl -u rental-config-copy 로 본다(설계서 7.1). 0 으로 끝날 때만 마지막 성공 시각을
# 지표로 남긴다(아래 「정기 작업 결과 지표」) — S3 사본이 실패한 실행은 로컬 사본이 남아도 성공으로 치지 않는다.
#
# 목적지 — 노드 구성마다 다르다. 값은 노드의 EnvironmentFile 에 둔다(아래 「목적지」).
#   3노드 APP-01   로컬 /var/backups/rental/config + S3 사본 s3://<버킷>/config (infra/backup/aws/)
#   옛 단일 노드    NAS(NFS) /mnt/nas/backup/config — 자기 자신에게 건 공유라 원본과 같은 디스크다. S3 사본 없음
#
# 복원: backup 계정으로 gpg --batch --passphrase-file <키> --decrypt config-<시각>.tar.gz.gpg | tar -xz
#       — REVISION(커밋 · 작업 트리 변경)과 infra/(.env 포함)가 나온다. 평문 .env 가 풀리므로 NAS 가 아닌 곳의
#       700 임시 디렉터리에서 풀고 끝나면 지운다(운영 절차서 9.3). S3 사본은 운영자 자격 증명으로 내려받아 같은 방법으로 푼다.
#
# ── 두 계정으로 나눠 돈다 ──
#  - 읽기는 root 다. 원본 체크아웃이 ec2-user 홈(700) 안이라 backup 계정은 읽지 못한다. 그렇다고 홈을 풀면 .env 가 든
#    체크아웃을 여는 셈이다(논리 백업 유닛과 같은 이유). root 는 읽기만 하고 평문을 파일로 남기지 않는다 — 스트림으로만 넘긴다.
#  - 암호화 · 쓰기 · 정리는 backup 계정이다. NAS 공유가 root_squash 라 root 는 공유에 쓸 수 없다(설계서 5.3).
#    백업 디렉터리 소유자도 backup 이다(설계서 6.2).
#  - S3 사본은 root 셸에서 올린다. 인스턴스 역할 자격 증명은 인스턴스 메타데이터에서 오므로 어느 계정으로 부르든 같다 —
#    backup 으로 바꿔 부를 이유가 없다. 올릴 파일은 backup 소유 600 이지만 root 는 읽을 수 있고, 읽기만 한다.
#
# ── 암호화 ──
#  묶음 전체를 gpg 대칭 암호화(AES256, --passphrase-file)한다 — 논리 백업(pg-dump.sh)과 같은 도구 · 같은 키 파일이다(설계서 5.1).
#  .env 만이 아니라 전체를 암호화한다 — 나눠 두면 복원 때 짝을 맞춰야 하고, 나머지 설정도 공격면 정보다.
#  키는 저장소가 아니라 노드에 두고 NAS 와 분리 보관한다(기술 스택 3장). 이 스크립트는 값을 읽지도 출력하지도 않는다.
set -euo pipefail
umask 077

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }

# ── 정기 작업 결과 지표 — node exporter 의 textfile 수집기가 읽는다(운영 Compose 의 node-exporter 주석) ──
# 디렉터리(backup 소유 755)는 노드 준비에서 만든다. 없으면 지표만 건너뛰고 작업은 실패시키지 않는다 —
# 지표는 작업 결과를 보이게 하는 수단이지 작업의 일부가 아니다. 파일은 644 여야 컨테이너의 nobody 가 읽는다(umask 077 을 덮는다).
# 같은 디렉터리의 점 이름 임시 파일에 쓰고 mv 로 바꾼다 — 같은 파일시스템 안의 이름 바꾸기라 수집기가 반쯤 쓴 파일을 읽지 않고,
# 수집기는 .prom 으로 끝나는 이름만 읽으므로 임시 파일(.<작업>.prom.tmp)은 걸리지 않는다.
# 세 스크립트에 같은 함수를 둔다 — 노드에는 스크립트를 파일마다 따로 설치하므로(운영 절차서) 나눠 두면 설치할 파일이 하나 는다.
# 도움말 문장은 세 스크립트가 글자까지 같아야 한다 — 같은 지표 이름의 HELP 가 파일마다 다르면 수집기가 뒤에 읽은 파일의 그 지표를
# 버리고 node_textfile_scrape_error 를 1 로 둔다(node exporter v1.9.1 collector/textfile.go).
# 호출은 `… | write_metrics <작업> || log …` 로 한다 — || 안이라 set -e 가 걸리지 않으므로 명령마다 || return 1 로 멈춘다.
RENTAL_METRICS_DIR=${RENTAL_METRICS_DIR:-/var/lib/rental-metrics}
write_metrics() {
  local dir=${RENTAL_METRICS_DIR%/} tmp
  if [ ! -d "$dir" ]; then
    cat > /dev/null
    log "지표 디렉터리가 없다: $dir — 지표($1.prom)만 건너뛴다"
    return 0
  fi
  tmp="$dir/.$1.prom.tmp"
  { cat > "$tmp" && chmod 644 "$tmp" && mv -f -- "$tmp" "$dir/$1.prom"; } || { rm -f -- "$tmp"; return 1; }
}
# last_success <작업 라벨> — 지금 시각을 마지막 성공 시각으로 쓴다. 성공으로 끝나는 경로에서만 부른다
last_success() {
  printf '%s\n' \
    '# HELP rental_job_last_success_timestamp_seconds Unix time of the last successful run of a rental scheduled job.' \
    '# TYPE rental_job_last_success_timestamp_seconds gauge' \
    "rental_job_last_success_timestamp_seconds{task=\"$1\"} $(date +%s)" \
    | write_metrics "$1" || log "!!! 지표를 쓰지 못했다 — $RENTAL_METRICS_DIR/$1.prom. 작업은 성공했다"
}

# ── 원본 — 값이 없으면 지어내지 않고 실패한다. 값은 노드의 EnvironmentFile 에 둔다 ──
# 체크아웃 루트. 체크아웃 자리가 바뀌면(ec2-user → deploy) 이 값만 바뀐다
: "${CONFIG_SRC:?CONFIG_SRC 가 필요하다 — 체크아웃 루트를 EnvironmentFile 에 둔다(운영 절차서 9.3)}"
[ -d "$CONFIG_SRC/infra" ] || { log "!!! $CONFIG_SRC/infra 가 없다"; exit 1; }

# ── 목적지 ──
# 기본값은 옛 단일 노드의 NAS 경로다. 단일 노드에서는 자기 자신에게 건 NFS 공유라 원본과 같은 디스크에 있다 — 노드 소실은
# 막지 못한다. 3노드 APP-01 에는 NFS 가 없어 EnvironmentFile 에서 로컬 경로(/var/backups/rental/config)를 준다.
CONFIG_DEST=${CONFIG_DEST:-/mnt/nas/backup/config}
BACKUP_USER=${BACKUP_USER:-backup}
# 오프사이트(S3) 사본은 선택이다. 값이 있으면 적재한 암호문을 <URI>/<이름> 으로 한 벌 올린다 — 3노드 APP-01 은
# s3://<버킷>/config 를 준다(infra/backup/aws/). 비어 있으면 지금처럼 로컬만 남긴다(옛 단일 노드는 S3 쓰기 권한이 없다).
# 권한은 인스턴스 역할로 준다 — 키 파일을 두지 않는다(설계서 6.1). 노드 소실을 막는 것이 이 사본이다.
CONFIG_S3_URI=${CONFIG_S3_URI:-}
AWS=${AWS:-aws}
# 형식이 틀리면 묶기 전에 멈춘다 — 사본만 실패하는 실행을 매주 되풀이하지 않게 한다
if [ -n "$CONFIG_S3_URI" ]; then
  case "$CONFIG_S3_URI" in
    s3://?*) ;;
    *) log "!!! CONFIG_S3_URI 는 s3:// 로 시작해야 한다: $CONFIG_S3_URI"; exit 1 ;;
  esac
fi

# ── 보존 ──
# **잠정 최신 4개** — 주 1회 × 4주, 물리 백업(4개)과 같은 주기다. 설계서 5.4 가 설정 사본 보존을 미확정으로 두고
# 「첫 백업 크기 · NAS 볼륨 크기를 보고 정한다」고 했다. 재조정 시점은 설계서 10장의 「운영 1개월 뒤」다.
CONFIG_KEEP=${CONFIG_KEEP:-4}
# 0 이면 방금 만든 사본까지 지우고, 숫자가 아니면 아래 비교가 조용히 정리를 건너뛴다 — 1 이상의 정수만 받는다
[[ "$CONFIG_KEEP" =~ ^[1-9][0-9]*$ ]] || { log "!!! CONFIG_KEEP 는 1 이상의 정수여야 한다: $CONFIG_KEEP"; exit 1; }

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
# --no-optional-locks — git status 는 권한이 되면 인덱스를 새로 고쳐 다시 쓴다. root(umask 077)로 쓰면 인덱스가
# root 소유 0600 이 되어 체크아웃 주인의 git 이 깨진다. 이 옵션이 그 쓰기를 막아 root 는 정말 읽기만 한다.
# 값은 변수로 먼저 받는다 — printf 인자 안의 명령 치환은 실패해도 set -e 에 걸리지 않아 빈 커밋이 기록된다.
git_src() { git --no-optional-locks -c safe.directory="$CONFIG_SRC" -C "$CONFIG_SRC" "$@"; }
REV=$(git_src rev-parse HEAD)
STATUS=$(git_src status --porcelain)
printf 'commit %s\nstatus --porcelain\n%s\n' "$REV" "$STATUS" > "$WORK/REVISION"

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

# ── 4-1. 오프사이트(S3) 사본 ──
# 로컬 사본은 이미 적재됐다. 사본이 실패해도 아래 보존 정리는 그대로 하고, 끝에서 0 이 아닌 코드로 끝낸다 — 물리 백업
# (pg-basebackup.sh)과 같은 원칙이다. 정리가 멈추면 로컬 디스크가 자라고, 성공으로 끝내면 실패가 저널에 남지 않는다.
# 올리는 것은 이미 적재한 암호문이다 — 암호화가 끝난 파일이라 잘린 입력을 올릴 일이 없고 임시 파일도 필요 없다.
# root 셸에서 부른다(위 「두 계정으로 나눠 돈다」). if 안이라 set -e 가 걸리지 않는다 — 결과는 종료 코드로만 본다.
OFFSITE_FAILED=0
if [ -n "$CONFIG_S3_URI" ]; then
  log "오프사이트 사본 — ${CONFIG_S3_URI%/}/$NAME"
  if ! "$AWS" s3 cp --only-show-errors "$CONFIG_DEST/$NAME" "${CONFIG_S3_URI%/}/$NAME"; then
    OFFSITE_FAILED=1
    log "!!! 오프사이트 사본 실패 — 로컬 사본 $CONFIG_DEST/$NAME 은 남아 있다. 보존 정리는 이어서 하고 끝에서 실패로 끝낸다"
  fi
fi

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
if [ "$OFFSITE_FAILED" -ne 0 ]; then
  log "!!! 오프사이트 사본이 실패해 0 이 아닌 코드로 끝낸다 — 로컬 사본은 남아 있다"
  exit 1
fi
# 여기까지 오면 S3 사본까지 성공했다(CONFIG_S3_URI 가 빈 옛 단일 노드는 사본 없이 로컬만으로 성공이다)
last_success config_copy
