#!/usr/bin/env bash
# WAL 아카이브 오프사이트 전송 — 인프라 기술 스택 3장, 운영 절차서 5장 · 9.3. 정기 작업(rental-wal-ship.service)이 매분 부른다.
#
#   bash wal-ship.sh           로컬 WAL 아카이브에서 아직 보내지 않은 것을 암호화해 S3 로 한 번 보낸다
#
# 순서: 보낸 표시 정리 → 대상 고르기 → 한 건씩 암호화 → 조건부 쓰기(있으면 덮지 않는다) → 보낸 표시.
# 실패하면 0 이 아닌 코드로 끝난다 — journalctl -u rental-wal-ship 으로 본다(설계서 7.1). 다음 분에 남은 것부터 다시 한다.
#
# ── 왜 archive_command 가 아니라 호스트 timer 인가 ──
#  archive_command(infra/postgres/archive-wal.sh)는 노드 로컬 디렉터리에만 쓴다. 거기서 S3 로 바로 보내면 컨테이너에
#  AWS CLI · 키 · 자격 증명이 들어가야 하고, S3 가 느리거나 막히면 아카이브가 밀려 WAL 이 pg_wal 에 쌓인다.
#  이 스크립트는 그 로컬 아카이브를 뒤따라 보낸다. 노드 소실 때 잃는 범위는 로컬 아카이브에 들어가기 전(archive_timeout)
#  과 이 작업의 주기(1분)를 더한 만큼이다 — 타이머 주석.
#
# ── 계정 · 권한 ──
#  backup 계정으로 돈다(설계서 6.2). 아카이브 파일은 0640 · 그룹 backup 이라 읽을 수 있다(archive-wal.sh 의 권한 주석).
#  S3 쓰기는 인스턴스 역할로 한다 — 키 파일을 두지 않는다(설계서 6.1). 역할은 wal/ 아래 PutObject 만 가져 목록 · 읽기 ·
#  삭제가 없다(infra/backup/aws/). 그래서 「이미 보냈는가」를 S3 에 물어볼 수 없고 로컬 표시와 조건부 쓰기로 가린다.
#
# ── 암호화 ──
#  논리 백업(pg-dump.sh)과 같은 도구 · 같은 키 파일이다 — gpg 대칭 암호화(AES256, --passphrase-file). 노드를 떠나기 전에
#  암호화한다(기술 스택 3장). 평문은 노드 밖으로 나가지 않고, 암호문은 PrivateTmp 의 임시 파일에만 잠깐 있다.
#  gpg 는 HOME(systemd 가 User= 의 홈으로 준다) 또는 EnvironmentFile 의 GNUPGHOME 아래 설정 디렉터리를 쓴다.
#  이 스크립트는 키 값을 읽지도 출력하지도 않는다.
set -euo pipefail
umask 077

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }

# ── 값 — 필수 값이 없으면 지어내지 않고 실패한다. 값은 노드의 EnvironmentFile(/etc/rental/wal-ship.env)에 둔다 ──
# 아카이브 — 컨테이너의 /archive/wal 이 마운트된 호스트 디렉터리(docker-compose.yml). 물리 백업의 WAL 정리와 같은 자리다
WAL_ARCHIVE_DIR=${WAL_ARCHIVE_DIR:-/var/backups/rental/wal}
: "${WAL_S3_URI:?WAL_S3_URI 가 필요하다 — 보낼 자리(s3://<버킷>/wal)를 EnvironmentFile 에 둔다}"
: "${BACKUP_KEY_FILE:?BACKUP_KEY_FILE 이 필요하다 — 복호화 키는 저장소가 아니라 노드에 두고 NAS 와 분리 보관한다(기술 스택 3장)}"
# 보낸 표시 — 이름마다 빈 파일 하나. 노드마다 따로 둔다(각 노드의 로컬 아카이브를 따라가므로)
WAL_SHIP_STATE_DIR=${WAL_SHIP_STATE_DIR:-/var/lib/rental-backup/wal-shipped}
AWS=${AWS:-aws}

# ── 값 방어 — 아래 표시 정리가 이 경로에서 파일을 지운다 ──
for d in "$WAL_ARCHIVE_DIR" "$WAL_SHIP_STATE_DIR"; do
  case "$d" in
    /*) ;;
    *) log "!!! 절대 경로여야 한다: '$d'"; exit 1 ;;
  esac
  [ "${d%/}" != "" ] || { log "!!! 루트(/)는 쓸 수 없다"; exit 1; }
done
WAL_ARCHIVE_DIR=${WAL_ARCHIVE_DIR%/}
WAL_SHIP_STATE_DIR=${WAL_SHIP_STATE_DIR%/}
[ "$WAL_ARCHIVE_DIR" != "$WAL_SHIP_STATE_DIR" ] || { log "!!! 아카이브와 표시 디렉터리가 같다 — 표시 정리가 아카이브를 건드린다"; exit 1; }
[ -d "$WAL_ARCHIVE_DIR" ] || { log "!!! WAL 아카이브 디렉터리가 없다: $WAL_ARCHIVE_DIR"; exit 1; }
[ -r "$BACKUP_KEY_FILE" ] || { log "!!! 키 파일을 읽을 수 없다: $BACKUP_KEY_FILE"; exit 1; }

# s3://<버킷>/<접두어> 를 가른다 — s3api put-object 는 URI 가 아니라 버킷 · 키를 따로 받는다
[[ "$WAL_S3_URI" =~ ^s3://([^/]+)(/(.*))?$ ]] || { log "!!! WAL_S3_URI 는 s3://<버킷>[/<접두어>] 형식이어야 한다: $WAL_S3_URI"; exit 1; }
BUCKET=${BASH_REMATCH[1]}
PREFIX=${BASH_REMATCH[3]}
PREFIX=${PREFIX%/}

# 보낼 이름 — PostgreSQL 이 아카이브하는 세 가지만. 이름 형식으로 고르므로 아카이브 스크립트의 임시 파일(.<이름>.tmp) ·
# 점 파일 · 끝나지 않은 .partial 은 걸리지 않는다
#   세그먼트  <타임라인 8><로그 8><세그먼트 8>              000000010000000000000003
#   타임라인  <타임라인 8>.history                            00000002.history — 승격 뒤 복구가 새 타임라인을 따라가는 데 필요하다
#   백업 기록 <세그먼트 24>.<오프셋 8>.backup                  물리 백업이 남긴다
is_wal_name() {
  [[ "$1" =~ ^[0-9A-F]{24}$ || "$1" =~ ^[0-9A-F]{8}\.history$ || "$1" =~ ^[0-9A-F]{24}\.[0-9A-F]{8}\.backup$ ]]
}

# ── 1. 표시 디렉터리 ──
# 부모(/var/lib/rental-backup, backup 소유 700)는 노드 준비에서 만든다(운영 절차서 9.3). 여기서는 그 아래만 만든다
install -d -m 700 "$WAL_SHIP_STATE_DIR"

# ── 2. 보낸 표시 정리 ──
# 아카이브에서 사라진 이름(물리 백업의 pg_archivecleanup 이 지운 것)의 표시를 지운다 — 표시가 끝없이 쌓이지 않게 한다.
# 보내기보다 먼저 한다 — 전송이 계속 실패해도 정리는 돈다. 이름 형식이 맞는 것만 지운다(같은 경로의 다른 것을 건드리지 않는다).
# 사라진 이름이 다시 생기면 표시가 없어 다시 보내지만, S3 조건부 쓰기가 덮어쓰기를 막는다(아래 3).
REMOVED=0
while IFS= read -r -d '' mark; do
  name=${mark##*/}
  is_wal_name "$name" || continue
  [ -e "$WAL_ARCHIVE_DIR/$name" ] && continue
  rm -f -- "$mark"
  REMOVED=$((REMOVED + 1))
done < <(find "$WAL_SHIP_STATE_DIR" -mindepth 1 -maxdepth 1 -type f -print0)

# ── 3. 보내기 ──
# 이름순이 시간순이다(고정 폭 16진). 타임라인 기록(00000002.history)은 '.' 이 숫자보다 앞서 그 타임라인의 세그먼트보다 먼저 간다.
# 한 건이라도 실패하면 그 자리에서 멈춘다 — 뒤의 것을 먼저 보내 사이에 구멍을 만들지 않는다.
#
# 이미 있으면 덮어쓰지 않는다 — If-None-Match: * 조건부 쓰기. S3 는 같은 키가 있으면 412 Precondition Failed 를 돌려준다.
#  아카이브 스크립트의 「다른 내용이면 덮어쓰지 않는다」와 같은 원칙이다 — 되살린 옛 primary 같은 쪽이 같은 이름을 다른
#  내용으로 덮지 못하게 한다. 412 는 다른 노드나 앞선 실행(표시를 남기기 전에 끊긴 것)이 이미 보낸 것이라 성공으로 친다.
#  암호문은 같은 평문이어도 매번 달라(무작위 솔트 · 세션 키) 내용 비교는 할 수 없다 — 이름으로만 가린다.
#  두 쓰기가 겹치면 S3 는 409 ConditionalRequestConflict 를 준다 — 실패로 끝내고 다음 분에 다시 하면 412 로 가려진다.
ENC=$(mktemp)
ERR=$(mktemp)
trap 'rm -f -- "$ENC" "$ERR"' EXIT
# systemd 의 실행 시간 상한은 SIGTERM 으로 끊는다. bash 는 신호로 죽을 때 EXIT trap 을 돌리지 않으므로 exit 로 바꿔 받는다
trap 'exit 143' TERM INT

SENT=0
EXISTED=0
SKIPPED=0
while IFS= read -r name; do
  is_wal_name "$name" || continue
  if [ -e "$WAL_SHIP_STATE_DIR/$name" ]; then
    SKIPPED=$((SKIPPED + 1))
    continue
  fi
  key="${PREFIX:+$PREFIX/}$name.gpg"

  gpg --batch --yes --quiet --symmetric --cipher-algo AES256 \
    --passphrase-file "$BACKUP_KEY_FILE" --output "$ENC" "$WAL_ARCHIVE_DIR/$name"
  [ -s "$ENC" ] || { log "!!! 암호화 결과가 비어 있다 — $name"; exit 1; }

  if "$AWS" s3api put-object --bucket "$BUCKET" --key "$key" --body "$ENC" --if-none-match '*' > /dev/null 2> "$ERR"; then
    log "보냄 — $name → s3://$BUCKET/$key"
    SENT=$((SENT + 1))
  elif grep -q 'PreconditionFailed' "$ERR"; then
    log "이미 있다(412) — s3://$BUCKET/$key. 덮어쓰지 않고 보낸 것으로 친다"
    EXISTED=$((EXISTED + 1))
  else
    cat "$ERR" >&2
    log "!!! 전송 실패 — $name. 여기서 멈추고 다음 실행에 다시 한다"
    exit 1
  fi
  : > "$WAL_SHIP_STATE_DIR/$name"
done < <(find "$WAL_ARCHIVE_DIR" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort)

log "WAL 전송 — 보냄 $SENT · 이미 있음 $EXISTED · 건너뜀(보낸 표시) $SKIPPED · 표시 정리 $REMOVED"
