#!/usr/bin/env bash
# 논리 백업 — 서버 운영 기반 설계서 5.1 · 7.2, 운영 절차서 5장. 정기 작업(rental-backup.service)이 부른다.
#
#   bash pg-dump.sh            그 시각의 백업을 한 번 만든다
#
# 순서: primary 확인 → pg_dump -Fc → 암호화 → 목적지 적재 → 보존 기간 지난 것 삭제.
# 실패하면 0 이 아닌 코드로 끝난다 — 모니터링이 없어 journalctl -u rental-backup 이 유일한 확인 수단이다(설계서 7.1).
#
# ── 접속 방식 — 호스트의 PostgreSQL 클라이언트로 TCP 접속한다(컨테이너 안의 pg_dump 를 부르지 않는다) ──
#  - 정기 작업은 backup 계정으로 돈다(설계서 6.2). 그 계정은 로그인 불가이고 docker 그룹이 아니다 —
#    docker 그룹은 root 와 같은 권한이라 백업을 위해 줄 수 없다(같은 절). docker compose exec 는 이 계정으로 불가능하다.
#  - 접속 정보를 libpq 표준 변수(PGHOST · PGPORT · PGUSER · PGDATABASE)로만 받으므로, 6주차에 DB 노드가 분리되면
#    PGHOST 만 DB-01 사설 IP 로 바뀐다. 컨테이너 이름 · Compose 프로젝트 이름에 묶이지 않는다.
#  - 전제 두 가지. 호스트에 서버와 같거나 높은 버전(현재 17)의 클라이언트가 있어야 한다 — Amazon Linux 기본 저장소의
#    postgresql17 이 서버와 같은 17.x 다. Rocky 노드로 옮기면 거기서도 같은 주 버전의 클라이언트 패키지를 깐다.
#    DB 가 컨테이너로 도는 동안에는 그 포트가 호스트 루프백에 게시되어 있어야 한다 — 운영 Compose 가 postgres 를
#    127.0.0.1:5432 에 게시한다(DOCKER-USER 규칙 대신 루프백 바인딩 — 설계서 3.3).
#
# ── 비밀번호 ──
#  명령줄 인자로 넘기지 않는다 — ps 에 그대로 보인다. libpq 가 스스로 읽는 두 경로만 쓴다:
#   (1) backup 계정의 ~/.pgpass (0600)   (2) PGPASSWORD — systemd EnvironmentFile(0600, backup 소유)로 넣는다.
#  이 스크립트는 값을 읽지도 출력하지도 않는다. -w 로 대화형 입력을 막아 timer 안에서 멈추지 않게 한다.
set -euo pipefail
umask 077

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }

# ── 접속 — 값이 없으면 지어내지 않고 실패한다. 값은 노드의 EnvironmentFile 에 둔다 ──
PGHOST=${PGHOST:-127.0.0.1}
PGPORT=${PGPORT:-5432}
# 접속 역할은 앱의 소유자 역할이 아니라 백업 전용 읽기 역할(rental_backup, pg_read_all_data)이다 — 운영 절차서 9.3.
# 정기 작업이 쓰기 권한과 최고 권한 비밀번호를 쥐지 않게 한다.
: "${PGUSER:?PGUSER 가 필요하다 — 백업 전용 역할 이름을 EnvironmentFile 에 둔다(운영 절차서 9.3)}"
: "${PGDATABASE:?PGDATABASE 가 필요하다 — infra/.env 의 POSTGRES_DB 와 같은 값을 EnvironmentFile 에 둔다}"
export PGHOST PGPORT PGUSER PGDATABASE
PSQL=${PSQL:-psql}
PG_DUMP=${PG_DUMP:-pg_dump}

# ── 목적지 ──
# 로컬 경로로 고정한다. NAS(설계서 5.1 · 공유 경로 예 /srv/nfs/backup)는 7주차라 아직 없다 — 구축 뒤 이 값만 바꾼다.
# 로컬이라 노드 소실은 막지 못한다. 그것을 막을 오프사이트 사본(아래 BACKUP_S3_URI)은 이 계정에 S3 쓰기 권한이 없어 비워 둔다.
BACKUP_DEST=${BACKUP_DEST:-/var/backups/rental}
# 덤프를 만들고 암호화하는 자리. 목적지가 NFS 여도 암호화는 노드 안에서 끝난다(기술 스택 3장 · 설계서 5.1)
BACKUP_WORK_DIR=${BACKUP_WORK_DIR:-/var/tmp}
# 오프사이트 사본은 선택이다. 값이 있으면 그 자리로도 한 벌 올린다. 권한은 인스턴스 역할로 준다 — 키 파일을 두지 않는다(설계서 6.1)
# 지금은 비어 있다(S3 쓰기 권한 없음). 권한이 생기면 EnvironmentFile 에 이 값만 넣는다 — 스크립트는 바꾸지 않는다
BACKUP_S3_URI=${BACKUP_S3_URI:-}
AWS=${AWS:-aws}

# ── 보존 ──
# **잠정 7일.** 설계서 5.4 가 보존 기간을 미확정으로 두고 「첫 백업 크기 · NAS 볼륨 크기를 보고 정한다」고 했다.
# 재조정 시점은 설계서 10장의 「논리 백업 · 설정 사본 보존 기간, 작업 시각 — 운영 1개월 뒤」다.
BACKUP_RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-7}

# ── 암호화 ──
# **도구는 gpg 대칭 암호화(AES256, --passphrase-file)로 정했다**(설계서 5.1 · 10장). Amazon Linux 2023 은 기본이
# gnupg2-minimal 이라 gpg-agent 를 띄우지 못해 대칭 암호화가 실패한다 — 노드 준비에서 gnupg2-full 로 바꾼다(운영 절차서 9.3).
# openssl enc 를 쓰지 않는 이유 — 인증 암호 모드(CCM · GCM)를 지원하지 않아 변조 · 손상된 백업을 복호화 단계에서 잡지 못한다
# (openssl-enc(1) 공식 문서).
# 바꿔 끼울 자리는 남겨 둔다 — BACKUP_ENCRYPT_CMD 는 표준입력을 받아 표준출력으로 내보내는 필터여야 한다.
# 공백이 든 인자는 쓸 수 없다(공백으로 나눈다). 값을 주지 않으면 아래 gpg 기본값을 쓰되, 키 파일 경로는 지어내지 않고 반드시 받는다.
BACKUP_ENCRYPT_CMD=${BACKUP_ENCRYPT_CMD:-}
if [ -z "$BACKUP_ENCRYPT_CMD" ]; then
  : "${BACKUP_KEY_FILE:?BACKUP_KEY_FILE 이 필요하다 — 복호화 키는 저장소가 아니라 노드에 두고 NAS 와 분리 보관한다(기술 스택 3장)}"
  [ -r "$BACKUP_KEY_FILE" ] || { log "!!! 키 파일을 읽을 수 없다: $BACKUP_KEY_FILE"; exit 1; }
  BACKUP_ENCRYPT_CMD="gpg --batch --yes --quiet --symmetric --cipher-algo AES256 --passphrase-file $BACKUP_KEY_FILE"
  BACKUP_ENCRYPT_SUFFIX=${BACKUP_ENCRYPT_SUFFIX:-.gpg}
fi
BACKUP_ENCRYPT_SUFFIX=${BACKUP_ENCRYPT_SUFFIX:-.enc}
read -r -a ENCRYPT_ARGV <<< "$BACKUP_ENCRYPT_CMD"

# ── 1. primary 일 때만 돈다 ──
# timer 는 DB-01 · DB-02 양쪽에 두고 자기가 primary 인지 확인한다 — 승격 뒤 새 primary 가 그대로 이어받는다(설계서 7.2).
IN_RECOVERY=$("$PSQL" -w -Atqc 'SELECT pg_is_in_recovery()')
if [ "$IN_RECOVERY" != "f" ]; then
  log "standby 다(pg_is_in_recovery = $IN_RECOVERY). 아무것도 하지 않고 끝낸다 — 설계서 7.2"
  exit 0
fi

STAMP=$(date '+%Y%m%d-%H%M%S')
NAME="$PGDATABASE-$STAMP.dump$BACKUP_ENCRYPT_SUFFIX"
WORK="$BACKUP_WORK_DIR/$NAME"
trap 'rm -f "$WORK"' EXIT

# ── 2. 덤프 → 암호화 ──
# 평문 파일을 만들지 않는다 — 덤프를 파이프로 그대로 암호화한다. set -o pipefail 이라 pg_dump 가 실패하면 여기서 멈춘다.
log "덤프 — $PGUSER@$PGHOST:$PGPORT/$PGDATABASE → $NAME"
"$PG_DUMP" -w -Fc | "${ENCRYPT_ARGV[@]}" > "$WORK"
[ -s "$WORK" ] || { log "!!! 덤프 결과가 비어 있다"; exit 1; }
log "크기 $(wc -c < "$WORK") 바이트"

# ── 3. 목적지 적재 ──
mkdir -p "$BACKUP_DEST"
cp "$WORK" "$BACKUP_DEST/$NAME"
chmod 600 "$BACKUP_DEST/$NAME"
log "적재 — $BACKUP_DEST/$NAME"

if [ -n "$BACKUP_S3_URI" ]; then
  log "오프사이트 사본 — ${BACKUP_S3_URI%/}/$NAME"
  "$AWS" s3 cp "$BACKUP_DEST/$NAME" "${BACKUP_S3_URI%/}/$NAME"
fi

# ── 4. 보존 기간 지난 것 삭제 ──
# 이 스크립트가 만든 이름만 지운다 — 같은 경로의 다른 백업을 건드리지 않는다.
log "보존 ${BACKUP_RETENTION_DAYS}일 밖 정리"
find "$BACKUP_DEST" -maxdepth 1 -type f -name "$PGDATABASE-*.dump$BACKUP_ENCRYPT_SUFFIX" \
  -mtime +"$BACKUP_RETENTION_DAYS" -print -delete

log "백업 완료 — $NAME"
