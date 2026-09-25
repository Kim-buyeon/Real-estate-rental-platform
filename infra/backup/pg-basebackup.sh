#!/usr/bin/env bash
# 물리 백업 — 인프라 기술 스택 3장(pg_basebackup 주 1회 · 4주 보존), 서버 운영 기반 설계서 7.2, 운영 절차서 5장.
# 정기 작업(rental-basebackup.service)이 부른다.
#
#   bash pg-basebackup.sh      그 시각의 물리 백업을 한 번 만든다
#
# 순서: primary 확인 → pg_basebackup -Ft -z(WAL 포함) → (BASEBACKUP_S3_URI 가 있으면) 암호화한 S3 사본 →
#       최신 BASEBACKUP_KEEP 개만 남기고 삭제 → 남은 것 중 가장 오래된 백업보다 앞선 WAL 아카이브 정리(pg_archivecleanup).
# 실패하면 0 이 아닌 코드로 끝난다 — journalctl -u rental-basebackup 으로 본다(설계서 7.1). 0 으로 끝날 때만 마지막 성공 시각을
# 지표로 남긴다(아래 「정기 작업 결과 지표」) — S3 사본이 실패한 실행은 로컬 백업이 남아도 성공으로 치지 않는다.
#
# 시점 복구(PITR)는 이 백업 하나 위에 WAL 아카이브(docker-compose.yml 의 archive_command 가 쓰는 자리)를 재생해서 한다.
# 그래서 WAL 정리는 「남긴 백업 중 가장 오래된 것」의 시작 WAL 까지만 지운다 — 그보다 앞선 WAL 은 어느 백업도 쓰지 않는다.
#
# ── 접속 방식 — 논리 백업(pg-dump.sh)과 같다. 호스트의 PostgreSQL 도구로 TCP 접속한다 ──
#  - 정기 작업은 backup 계정으로 돈다(설계서 6.2). 그 계정은 docker 그룹이 아니라 컨테이너 안의 pg_basebackup 을 부를 수 없다.
#  - 접속 정보는 libpq 표준 변수(PGHOST · PGPORT · PGUSER)로만 받는다. DB 노드가 분리되면 PGHOST 만 바뀐다.
#  - 접속 역할은 복제 권한만 가진 전용 역할(rental_basebackup, REPLICATION)이다 — standby 의 replicator 를 재사용하지 않는다.
#    역할마다 비밀번호 하나(운영 절차서 9.3 의 rental_backup 과 같은 원칙). 복제 연결이라 pg_hba.conf 의 replication 줄이 받는다.
#  - 전제: 호스트에 서버와 같은 주 버전(17)의 pg_basebackup · pg_archivecleanup 이 있어야 한다. 둘은 postgresql17 클라이언트
#    패키지가 아니라 서버 · contrib 패키지에 들어 있다(운영 절차서 5장).
#
# ── 비밀번호 ──
#  명령줄 인자로 넘기지 않는다 — ps 에 그대로 보인다. libpq 가 스스로 읽는 두 경로만 쓴다:
#   (1) backup 계정의 ~/.pgpass (0600)   (2) PGPASSWORD — systemd EnvironmentFile(0600, backup 소유)로 넣는다.
#  이 스크립트는 값을 읽지도 출력하지도 않는다. -w 로 대화형 입력을 막아 timer 안에서 멈추지 않게 한다.
#
# ── 로컬 사본은 암호화하지 않는다 ──
#  목적지가 노드 로컬이라 데이터 볼륨과 같은 디스크 · 같은 노출이다. 노드 밖으로 나갈 때 암호화한다(설계서 7.2).
#  목적지 디렉터리 권한(backup 소유 700)과 umask 077 이 막는다. S3 사본은 노드를 떠나기 전에 파일마다 gpg 로 암호화한다
#  — 논리 백업(pg-dump.sh)과 같은 도구 · 같은 키 파일이다(기술 스택 3장).
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

# ── 접속 — 값이 없으면 지어내지 않고 실패한다. 값은 노드의 EnvironmentFile 에 둔다 ──
PGHOST=${PGHOST:-127.0.0.1}
PGPORT=${PGPORT:-5432}
: "${PGUSER:?PGUSER 가 필요하다 — 물리 백업 전용 역할 이름(rental_basebackup)을 EnvironmentFile 에 둔다}"
# primary 확인(psql)은 일반 연결이라 데이터베이스 이름이 필요하다. 주지 않으면 postgres 에 붙는다 —
# 이 역할은 CONNECT 만 있으면 되고 테이블 권한은 필요 없다(pg_is_in_recovery() 는 누구나 부를 수 있다).
PGDATABASE=${PGDATABASE:-postgres}
export PGHOST PGPORT PGUSER PGDATABASE
PSQL=${PSQL:-psql}
PG_BASEBACKUP=${PG_BASEBACKUP:-pg_basebackup}
PG_ARCHIVECLEANUP=${PG_ARCHIVECLEANUP:-pg_archivecleanup}

# ── 목적지 ──
# 로컬 경로다(기능 정의서 INF-04 행 · 운영 절차서 5장).
BASEBACKUP_DEST=${BASEBACKUP_DEST:-/var/backups/rental/physical}
# 오프사이트(S3) 사본은 선택이다. 값이 있으면 <URI>/<시각>/ 아래로 암호화해 한 벌 올린다 — 3노드는 s3://<버킷>/physical 을 준다.
# 비어 있으면 지금처럼 로컬만 남긴다(단일 노드). 권한은 인스턴스 역할로 준다 — 키 파일을 두지 않는다(설계서 6.1).
# 암호화 키(BACKUP_KEY_FILE)는 이 값이 있을 때만 필요하다 — 없으면 S3 사본 단계에서 실패한다
BASEBACKUP_S3_URI=${BASEBACKUP_S3_URI:-}
AWS=${AWS:-aws}
# WAL 아카이브 — 컨테이너의 /archive/wal 이 마운트된 호스트 디렉터리(docker-compose.yml)
WAL_ARCHIVE_DIR=${WAL_ARCHIVE_DIR:-/var/backups/rental/wal}

# ── 보존 ──
# 4개 — 주 1회 × 4주(인프라 기술 스택 3장). 날짜가 아니라 개수로 센다 — 실행이 한 주 빠져도 남는 백업이 줄지 않는다.
BASEBACKUP_KEEP=${BASEBACKUP_KEEP:-4}

# ── 값 방어 — 아래 삭제가 이 값들로 경로를 만든다 ──
case "$BASEBACKUP_KEEP" in
  ''|*[!0-9]*) log "!!! BASEBACKUP_KEEP 은 1 이상의 정수여야 한다: '$BASEBACKUP_KEEP'"; exit 1 ;;
esac
[ "$BASEBACKUP_KEEP" -ge 1 ] || { log "!!! BASEBACKUP_KEEP 은 1 이상이어야 한다 — 0 이면 방금 만든 백업까지 지운다"; exit 1; }
for d in "$BASEBACKUP_DEST" "$WAL_ARCHIVE_DIR"; do
  case "$d" in
    /*) ;;
    *) log "!!! 절대 경로여야 한다: '$d'"; exit 1 ;;
  esac
  [ "${d%/}" != "" ] || { log "!!! 루트(/)는 목적지가 될 수 없다"; exit 1; }
done
BASEBACKUP_DEST=${BASEBACKUP_DEST%/}
WAL_ARCHIVE_DIR=${WAL_ARCHIVE_DIR%/}
[ -d "$WAL_ARCHIVE_DIR" ] || { log "!!! WAL 아카이브 디렉터리가 없다: $WAL_ARCHIVE_DIR — 경로가 틀리면 정리하지 않고 멈춘다"; exit 1; }

# ── 1. primary 일 때만 돈다 ──
# 논리 백업과 같다(설계서 7.2). standby 에서는 아무것도 하지 않고 끝나므로 승격 뒤 새 primary 가 그대로 이어받는다.
IN_RECOVERY=$("$PSQL" -w -Atqc 'SELECT pg_is_in_recovery()')
if [ "$IN_RECOVERY" != "f" ]; then
  log "standby 다(pg_is_in_recovery = $IN_RECOVERY). 아무것도 하지 않고 끝낸다 — 설계서 7.2"
  # 성공 시각을 쓰지 않는다 — 백업을 만든 것이 아니다. 이 지표는 primary 에서만 뜻을 갖는다
  exit 0
fi

BASE_DIR="$BASEBACKUP_DEST/base"
mkdir -p "$BASE_DIR"
STAMP=$(date '+%Y%m%d-%H%M%S')
FINAL="$BASE_DIR/$STAMP"
# 받는 동안은 임시 이름이다 — 아래 보존 계산은 최종 이름(YYYYmmdd-HHMMSS)만 세므로, 도중에 끊긴 백업이 「최신 4개」에
# 끼어 멀쩡한 백업을 밀어내거나 WAL 정리의 기준이 되지 않는다.
WORK="$BASE_DIR/.partial-$STAMP"
[ ! -e "$FINAL" ] && [ ! -e "$WORK" ] || { log "!!! 같은 이름이 이미 있다: $STAMP"; exit 1; }
trap 'rm -rf -- "$WORK"' EXIT
# systemd 의 실행 시간 상한은 SIGTERM 으로 끊는다. bash 는 신호로 죽을 때 EXIT trap 을 돌리지 않으므로 exit 로 바꿔 받는다
trap 'exit 143' TERM INT

# ── 2. 물리 백업 ──
# -Ft -z   tar + gzip. base.tar.gz · pg_wal.tar.gz · backup_manifest 가 생긴다
# -X       주지 않는다 — 기본 stream 이 백업 동안의 WAL 을 pg_wal.tar.gz 에 담아 이 백업 하나로 일관된 지점까지 복구된다
# -c fast  시작 체크포인트를 바로 친다. 기본 spread 는 checkpoint_completion_target 만큼 기다린다 — 쓰기가 적은 새벽이라 부담이 작다
log "물리 백업 — $PGUSER@$PGHOST:$PGPORT → $FINAL"
"$PG_BASEBACKUP" -w -D "$WORK" -Ft -z -c fast -l "rental-$STAMP"

# 결과 확인 — PostgreSQL 17 의 pg_verifybackup 은 tar 형식을 검사하지 못한다. 대신 파일이 있고 gzip 이 깨지지 않았는지 본다
for f in base.tar.gz pg_wal.tar.gz backup_manifest; do
  [ -s "$WORK/$f" ] || { log "!!! 백업 결과에 $f 가 없다"; exit 1; }
done
gzip -t "$WORK/base.tar.gz" "$WORK/pg_wal.tar.gz" || { log "!!! 압축 파일이 손상됐다"; exit 1; }

mv -- "$WORK" "$FINAL"
trap - EXIT TERM INT
log "크기 $(du -sk "$FINAL" | cut -f1) KiB"

# ── 2-1. 오프사이트(S3) 사본 ──
# 로컬 백업은 이미 성공했다. 사본이 실패해도 아래 보존 · WAL 정리는 그대로 하고, 끝에서 0 이 아닌 코드로 끝낸다 —
# 사본 실패로 정리가 멈추면 로컬 디스크가 자라고, 성공으로 끝내면 실패가 저널에 남지 않는다.
# 파일마다 임시 파일로 암호화한 뒤 올린다 — gpg 출력을 aws s3 cp - 로 흘리면 gpg 가 도중에 실패해도 aws 는 끝난 입력으로
# 보고 잘린 객체를 올린다. 임시 파일은 목적지 디렉터리(같은 디스크 · 700)에 점 이름으로 둔다 — 아래 보존 계산에 걸리지 않는다.
# backup_manifest 를 마지막에 올린다 — 그것이 있으면 세 파일이 다 올라간 것이다.
# 이 함수는 if 안에서 불려 set -e 가 걸리지 않는다 — 명령마다 || return 1 로 멈춘다.
offsite_copy() {
  [ -n "${BACKUP_KEY_FILE:-}" ] || { log "!!! BACKUP_KEY_FILE 이 필요하다 — 복호화 키는 저장소가 아니라 노드에 둔다(기술 스택 3장)"; return 1; }
  [ -r "$BACKUP_KEY_FILE" ] || { log "!!! 키 파일을 읽을 수 없다: $BACKUP_KEY_FILE"; return 1; }
  case "$BASEBACKUP_S3_URI" in
    s3://?*) ;;
    *) log "!!! BASEBACKUP_S3_URI 는 s3:// 로 시작해야 한다: $BASEBACKUP_S3_URI"; return 1 ;;
  esac
  local f dest
  ENC=$(mktemp "$BASEBACKUP_DEST/.s3-upload.XXXXXX") || return 1
  for f in base.tar.gz pg_wal.tar.gz backup_manifest; do
    dest="${BASEBACKUP_S3_URI%/}/$STAMP/$f.gpg"
    log "오프사이트 사본 — $f → $dest"
    gpg --batch --yes --quiet --symmetric --cipher-algo AES256 \
      --passphrase-file "$BACKUP_KEY_FILE" --output "$ENC" "$FINAL/$f" || return 1
    [ -s "$ENC" ] || { log "!!! 암호화 결과가 비어 있다 — $f"; return 1; }
    "$AWS" s3 cp --only-show-errors "$ENC" "$dest" || return 1
  done
}
OFFSITE_FAILED=0
if [ -n "$BASEBACKUP_S3_URI" ]; then
  ENC=
  # 임시 파일은 어떻게 끝나든 지운다 — 실행 시간 상한의 SIGTERM 도 exit 로 바꿔 EXIT trap 을 돌린다(위 2 와 같다)
  trap 'rm -f -- "${ENC:-}"' EXIT
  trap 'exit 143' TERM INT
  if ! offsite_copy; then
    OFFSITE_FAILED=1
    log "!!! 오프사이트 사본 실패 — 로컬 백업 $FINAL 은 성공했다. 보존 · WAL 정리는 이어서 하고 끝에서 실패로 끝낸다"
  fi
  rm -f -- "$ENC"
  trap - EXIT TERM INT
fi

# ── 3. 최신 BASEBACKUP_KEEP 개만 남긴다 ──
# 이 스크립트가 만든 이름(YYYYmmdd-HHMMSS 디렉터리)만 센다 — 같은 경로의 다른 것을 건드리지 않는다.
# 이름이 시각이라 글자 순서가 시간 순서다.
mapfile -t BACKUPS < <(find "$BASE_DIR" -mindepth 1 -maxdepth 1 -type d \
  -regextype posix-extended -regex '.*/[0-9]{8}-[0-9]{6}' -printf '%f\n' | LC_ALL=C sort)
# 방금 만든 것이 목록에 없으면 경로 계산이 틀린 것이다 — 아무것도 지우지 않고 멈춘다
printf '%s\n' "${BACKUPS[@]}" | grep -qx "$STAMP" || { log "!!! 방금 만든 백업이 목록에 없다 — 정리하지 않는다"; exit 1; }

COUNT=${#BACKUPS[@]}
if [ "$COUNT" -gt "$BASEBACKUP_KEEP" ]; then
  for old in "${BACKUPS[@]:0:COUNT-BASEBACKUP_KEEP}"; do
    [ -n "$old" ] && [ "$old" != "$STAMP" ] || { log "!!! 삭제 대상 계산이 틀렸다: '$old'"; exit 1; }
    log "보존 ${BASEBACKUP_KEEP}개 밖 삭제 — $BASE_DIR/$old"
    rm -rf -- "${BASE_DIR:?}/$old"
  done
  BACKUPS=("${BACKUPS[@]:COUNT-BASEBACKUP_KEEP}")
fi

# ── 4. 남긴 백업 중 가장 오래된 것보다 앞선 WAL 정리 ──
# 시작 WAL 이름은 base.tar.gz 안 backup_label 의 「START WAL LOCATION: 0/… (file 이름)」에서 읽는다.
# pg_archivecleanup 은 그 이름보다 앞선 세그먼트만 지우고 그 파일 자신과 .history(타임라인 기록 — 승격 뒤 복구에 필요)는 남긴다.
# -b 는 앞선 백업의 기록 파일(….backup)도 함께 지운다(PostgreSQL 17 에서 생긴 선택지) — 없으면 주마다 하나씩 남는다.
# 아카이브 스크립트(infra/postgres/archive-wal.sh)의 임시 파일 .<이름>.tmp 는 건드리지 않는다 — pg_archivecleanup 은 이름이
# 세그먼트 · .partial · (-b 일 때) .backup 형식인 것만 지우고 점으로 시작하는 이름은 그 형식이 아니다. 임시 파일은 같은
# 세그먼트의 다음 시도가 덮어쓰고 mv 로 사라지므로 따로 치우지 않는다.
OLDEST="${BACKUPS[0]}"
LABEL=$(tar -xzOf "$BASE_DIR/$OLDEST/base.tar.gz" backup_label) \
  || { log "!!! backup_label 을 읽지 못했다 — $OLDEST. WAL 을 정리하지 않는다"; exit 1; }
START_WAL=$(printf '%s\n' "$LABEL" | sed -n 's/^START WAL LOCATION: .* (file \([0-9A-F]\{24\}\))$/\1/p')
[ -n "$START_WAL" ] || { log "!!! backup_label 에서 시작 WAL 이름을 찾지 못했다 — $OLDEST. WAL 을 정리하지 않는다"; exit 1; }
log "WAL 정리 — $WAL_ARCHIVE_DIR 에서 $START_WAL 보다 앞선 것(기준 백업 $OLDEST)"
"$PG_ARCHIVECLEANUP" -b "$WAL_ARCHIVE_DIR" "$START_WAL"

log "물리 백업 완료 — $STAMP (보존 ${#BACKUPS[@]}개)"
if [ "$OFFSITE_FAILED" -ne 0 ]; then
  log "!!! 오프사이트 사본이 실패해 0 이 아닌 코드로 끝낸다 — 로컬 백업은 남아 있다"
  exit 1
fi
# 여기까지 오면 S3 사본까지 성공했다(BASEBACKUP_S3_URI 가 빈 단일 노드는 사본 없이 로컬만으로 성공이다)
last_success physical_backup
