#!/usr/bin/env bash
# 입구 HTTPS 인증서 발급 · 갱신 — 보안 · 암호화 설계서 4.1 · 4.5 · 5장(INF-07, 이슈 #288). 정기 작업(rental-tls.service)이 앱 노드에서 root 로 부른다.
#
#   bash issue-cert.sh                  필요할 때만 발급한다. 두 번 연달아 돌리면 두 번째는 확인만 하고 끝난다
#   LE_STAGING=1 bash issue-cert.sh     Let's Encrypt 시험 서버로 발급한다(신뢰되지 않는 인증서 — 절차 확인용, 발급 한도를 쓰지 않는다)
#   KEY_TYPE=rsa bash issue-cert.sh     RSA 2048 키로 발급한다(기본 ecdsa — P-256)
#   PREPARE_ONLY=1 bash issue-cert.sh   첫 반영 — 인증서 자리(임시 자체 서명)만 채우고 끝낸다. Nginx 를 새 마운트로 다시 만들기 전에 한 번
#
# 왜 이 작업이 있나 — APP-01 의 공인 IP 는 켤 때마다 바뀌고(고정 IP 없음), Let's Encrypt IP 주소 인증서는 shortlived 프로필 전용이라
# 유효 160시간이다(https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability). 그래서 켤 때마다 · 6일 안에 다시 받아야 하고,
# 사람 손에 맡기면 만료가 곧 입구 전면 장애다(설계서 5장). 발급 · 갱신 · 만료 감시(지표 → Grafana 경보)를 한 작업에 둔다.
#
# 순서: 공인 IP(IMDSv2) → 인증서가 없으면 임시 자체 서명 → 발급이 필요한가(아니면 여기서 끝) → 앞단 80 이 ACME 경로를 내보내는지 대기 →
#       certbot(컨테이너) 발급 → /etc/rental/tls/ 로 복사 → nginx -t · reload → 지표 → (입구 이중화) 상대 노드에 사본.
#
# ── 입구 이중화(#404) — 두 앱 노드 ──
#  ENTRY_IP(입구 고정 IP — Elastic IP)가 있으면(/etc/rental/entry.env — 유닛의 EnvironmentFile) 인증서는 그 IP 로 발급한다.
#  발급은 **그 IP 를 지금 가진 노드만** 한다 — IMDS 의 public-ipv4 가 ENTRY_IP 와 같은 노드다. Let's Encrypt 의 http-01 확인이 그 IP 로
#  오므로 다른 노드는 발급할 수 없고, 해 봐야 검증 실패 한도만 쓴다.
#   - 가진 노드(활성): 지금처럼 확인 · 발급하고, 끝나면 지금 인증서 쌍을 상대 노드에 넘긴다(PEER_SSH — 이 노드의 deploy 계정 키로
#     상대 deploy 의 ~/rental-tls-inbox/ 에. 배포 스크립트가 쓰는 노드 간 신뢰 경로와 같다 — 서버 운영 기반 설계서 6.2).
#     넘기기에 실패해도 이 작업은 실패로 만들지 않는다 — 지표 rental_job_last_success_timestamp_seconds{task="tls_cert_copy"} 가 멈춘다.
#   - 대기 노드: 발급하지 않는다. 넘겨받은 쌍이 쓸 만하면(CA 발급 · SAN 에 ENTRY_IP · 키가 짝 · 지금 것보다 늦게 만료) 바꿔 끼우고 reload.
#     그래서 고정 IP 가 옮겨 오면(infra/os/entry/entry-watch.sh) 이 노드의 Nginx 는 이미 같은 IP 의 유효한 인증서를 내보낸다.
#     넘겨받은 것이 없으면 임시 자체 서명(SAN ENTRY_IP)인 채로 기다린다 — 옮겨 온 뒤 이 작업이 그 노드를 「가진 노드」로 보고 발급한다.
#  넘겨받는 주기는 이 작업의 주기(부팅 20초 뒤 · 12시간마다)다 — 활성 노드가 새로 발급한 뒤 최대 12시간 늦게 대기 노드에 들어간다.
#  갱신 기준이 72시간 전이라 그 사이 대기 노드의 사본은 60시간 넘게 유효하다.
#  S3 를 거치지 않는 이유 — 앱 노드 역할은 쓰기만 갖는다(infra/backup/aws/README.md). 개인키를 읽을 권한을 역할에 더하지 않는다.
#  ENTRY_IP 가 비어 있으면 예전과 같다 — 이 노드의 공인 IP 로 발급하고 넘기지 않는다.
# 실패하면 0 이 아닌 코드로 끝난다 — journalctl -u rental-tls 로 본다. 유닛이 5분 뒤 다시 부른다(rental-tls.service 주석).
#
# ── 자리 ──
#  /etc/rental/tls/{fullchain,privkey}.pem   Nginx 가 읽는 인증서(운영 Compose 가 읽기 전용으로 마운트). 디렉터리 root 0700 · 개인키 0600 —
#                                            읽는 것은 Nginx 컨테이너의 마스터 프로세스(root)뿐이다. 워커(nginx 계정)는 키를 읽지 않는다
#  /etc/letsencrypt                          certbot 상태(계정 · 발급 이력 · live/ 링크). certbot 컨테이너에 붙이는 호스트 디렉터리
#  /var/lib/rental-acme                      ACME http-01 웹루트. certbot 이 .well-known/acme-challenge/<토큰> 을 쓰고 앞단 Nginx 가 80 에서 내보낸다.
#                                            root 0755 — Nginx 워커(nginx 계정)가 토큰 파일을 읽어야 한다
#  /var/lib/rental-metrics/tls_cert*.prom    결과 지표(아래 「지표」)
#  /home/deploy/rental-tls-inbox/            (입구 이중화 · 대기 노드) 상대가 넘긴 쌍. deploy 700 — 바꿔 끼운 뒤 지운다(TLS_INBOX)
#
# ── 왜 certbot 을 컨테이너로 ──
#  webroot 방식의 IP 주소 발급은 certbot 5.4 부터다(certbot CHANGELOG 5.4.0 「The webroot plugin now supports IP address issuance」,
#  https://letsencrypt.org/2026/03/11/shorter-certs-certbot). Rocky 저장소판이 5.4 미만일 수 있어 공식 이미지의 고정 태그를 쓴다(이슈 #288 계획).
#  nginx 플러그인은 IP 를 지원하지 않는다(같은 글) — 그래서 설치(Nginx 설정 바꾸기)는 certbot 에 맡기지 않고 이 스크립트가 복사 · reload 한다.
#
# ── 발급 한도 보호 ──
#  Let's Encrypt 는 같은 식별자 집합(여기서는 IP 하나)에 7일에 5장까지만 발급한다 — 계정과 상관없이 전역, 34시간에 1장씩 회복
#  (https://letsencrypt.org/docs/rate-limits/ 「New Certificates per Exact Set of Identifiers」, 2026-09-28 확인). 그래서 매번 발급하지 않고
#  「자체 서명이다 · SAN 의 IP 가 지금 IP 와 다르다 · 남은 시간이 RENEW_BEFORE_HOURS 미만이다」 중 하나일 때만 발급한다.
#  검증 실패도 한도가 있다 — 한 계정 · 한 식별자에 1시간 5회(같은 문서 「Authorization Failures per Identifier per Account」).
#  유닛의 재시도 상한(rental-tls.service 의 StartLimit*)이 이 한도 안에 머물게 둔다.
#  ARI(갱신 정보)로 조율한 갱신은 한도에서 빠지지만(같은 문서) 이 스크립트는 certbot renew 가 아니라 certonly --force-renewal 로 부르므로
#  ARI 면제에 기대지 않는다 — 한도 계산은 위 두 줄로만 한다.
set -euo pipefail
umask 077

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }
fail() { log "!!! $*"; exit 1; }

# ── 설정 — 값은 환경 변수로 덮을 수 있다. 시크릿은 없다(계정은 이메일 없이 등록한다) ──
TLS_DIR=${TLS_DIR:-/etc/rental/tls}
LE_DIR=${LE_DIR:-/etc/letsencrypt}
ACME_WEBROOT=${ACME_WEBROOT:-/var/lib/rental-acme}
# 운영 Compose 가 있는 체크아웃 안 infra/ — deploy.sh 의 APP_NODE_DIR 기본값과 같은 자리(운영 체크아웃은 deploy 홈, 운영 절차서 9장)
RENTAL_DIR=${RENTAL_DIR:-/home/deploy/rental/infra}
# 5.4 이상이어야 한다(위 「왜 certbot 을 컨테이너로」). v5.8.0 은 2026-09-01 에 나온 판으로 Docker Hub 에 태그가 있음을 2026-09-28 에 확인했다
# (hub.docker.com certbot/certbot 태그 목록). 노드에서 실제로 받아 도는지는 미확인 — 첫 반영 때 docker pull 로 확인한다
CERTBOT_IMAGE=${CERTBOT_IMAGE:-certbot/certbot:v5.8.0}
CERT_NAME=rental-ip
# 인증서 키 — ECDSA P-256(C-2 로 확정 — 설계서 4.1, #288: 새 연결 400/초에 APP-01 CPU 48.7 % 대 RSA 2048 65.9 %). 바꿀 수 있게는 둔다
# 발급이 필요할 때만 쓰인다 — 지금 인증서가 유효하면(3단계) 키 종류가 달라도 다시 발급하지 않는다(발급 한도 보호).
# C-2 측정의 RSA 회차는 발급하지 않고 자체 서명 RSA 를 손으로 바꿔 끼운다(이슈 #288) — 그 동안은 timer 를 멈춰 둔다.
# 멈추지 않으면 이 스크립트가 자체 서명을 보고 곧바로 Let's Encrypt 발급으로 바꿔 놓는다
KEY_TYPE=${KEY_TYPE:-ecdsa}
# 갱신 시작 기준(시간) — 유효 160시간의 약 45%가 남았을 때. 근거:
#  (1) timer 가 12시간마다 돈다 — 72시간이면 만료 전에 여섯 번 시도한다. 경보(Grafana 「인증서 만료 임박」)는 48시간 남았을 때라,
#      경보가 울리기 전에 24시간(timer 두 번 + 실패 재시도) 동안 스스로 회복할 기회가 있다.
#  (2) 같은 IP 로 켜 둔 채면 88시간(160 - 72)마다 한 장 — 7일에 2장 남짓이라 위 한도 5장에 닿지 않는다.
#  스스로 정한 잠정값이다(이슈 #288 계획 「발급 조건」). 문서에 확정값으로 올라가면 거기를 따른다
RENEW_BEFORE_HOURS=${RENEW_BEFORE_HOURS:-72}
LE_STAGING=${LE_STAGING:-0}
# 앞단 80 응답 대기 — 5초 간격 최대 5분(아래 3단계)
WAIT_TRIES=${WAIT_TRIES:-60}
WAIT_INTERVAL=${WAIT_INTERVAL:-5}
PREPARE_ONLY=${PREPARE_ONLY:-0}
# 공인 IP 를 손으로 줄 때만 쓴다(IMDS 가 없는 곳에서 시험할 때). 운영에서는 비워 둔다 — 비어 있으면 IMDSv2 로 얻는다
PUBLIC_IP=${PUBLIC_IP:-}
# 입구 이중화(#404, 위) — 비어 있으면 예전 방식. 값은 /etc/rental/entry.env(유닛의 EnvironmentFile)
ENTRY_IP=${ENTRY_IP:-}
# 상대 노드에 넘길 SSH 대상(deploy@<상대 사설 IP>)과 그 SSH 를 부를 이 노드의 계정. 비우면 넘기지 않는다
PEER_SSH=${PEER_SSH:-}
PUSH_USER=${PUSH_USER:-deploy}
# 넘겨받는 자리(대기 노드) — 상대가 PUSH_USER 의 홈 기준 rental-tls-inbox 에 쓴다
TLS_INBOX=${TLS_INBOX:-/home/deploy/rental-tls-inbox}

case "$KEY_TYPE" in
  ecdsa) KEY_ARGS=(--key-type ecdsa --elliptic-curve secp256r1) ;;
  # Mozilla intermediate 의 RSA 하한이 2048 이다(https://ssl-config.mozilla.org/guidelines/latest.json v6.0 rsa_key_size)
  rsa)   KEY_ARGS=(--key-type rsa --rsa-key-size 2048) ;;
  *)     fail "KEY_TYPE 은 ecdsa 또는 rsa 다: $KEY_TYPE" ;;
esac
# 시험 서버 발급은 다른 이름에 둔다 — 운영 발급 이력이 있는 이름에 --staging 을 주면 certbot 이 멈춘다(--break-my-certs 요구)
STAGING_ARGS=()
if [ "$LE_STAGING" = 1 ]; then
  STAGING_ARGS=(--staging)
  CERT_NAME=rental-ip-staging
fi

[ "${EUID:-$(id -u)}" -eq 0 ] || fail "root 로 실행한다 — 인증서 디렉터리(root 0700)와 docker 를 다룬다"

# 같은 작업이 겹치지 않게 한다(timer 실행 중 손으로 한 번 더 부른 경우). 겹치면 발급을 두 번 요청해 한도를 쓴다
exec 9>/run/rental-tls.lock
flock -n 9 || fail "다른 rental-tls 실행이 진행 중이다"

# ── 정기 작업 결과 지표 — node exporter 의 textfile 수집기가 읽는다(운영 Compose 의 node-exporter 주석) ──
# infra/backup/*.sh 와 같은 함수 · 같은 도움말 문장이다. 도움말 문장은 글자까지 같아야 한다 — 같은 지표 이름의 HELP 가 파일마다 다르면
# 수집기가 뒤에 읽은 파일의 그 지표를 버리고 node_textfile_scrape_error 를 1 로 둔다(그 스크립트들의 주석).
# 디렉터리(backup 소유 755)는 노드 준비에서 모든 노드에 만든다(운영 절차서 — 노드 지표). root 는 그 디렉터리에 쓸 수 있다.
# 없으면 지표만 건너뛰고 작업은 실패시키지 않는다. 파일은 644 여야 컨테이너의 nobody 가 읽는다(umask 077 을 덮는다).
# 파일 둘 — wal-ship.sh 의 wal_ship.prom · wal_ship_pending.prom 과 같은 나눔이다:
#   tls_cert.prom          rental_job_last_success_timestamp_seconds{task="tls_cert"} — 성공(발급 또는 확인 통과)으로 끝날 때만 바꾼다.
#                          last_success 는 파일 이름을 작업 라벨로 쓰므로 인증서 상태와 한 파일에 둘 수 없다
#   tls_cert_expiry.prom   rental_tls_cert_expiry_timestamp_seconds · rental_tls_cert_self_signed — 실패로 끝나도 지금 인증서 상태를 쓴다.
#                          만료 경보가 이 값을 본다 — 발급이 실패하는 동안에도 남은 시간이 줄어드는 것이 보여야 한다
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
# cert_state — /etc/rental/tls 의 지금 인증서를 지표로 쓴다. 인증서가 없으면(읽지 못하면) 쓰지 않는다 — 옛 값이 남는다
cert_state() {
  local crt="$TLS_DIR/fullchain.pem" end ss
  [ -r "$crt" ] || { log "인증서가 없어 상태 지표를 쓰지 않는다: $crt"; return 0; }
  end=$(cert_end_epoch "$crt") || { log "!!! 인증서 만료 시각을 읽지 못했다: $crt"; return 0; }
  if cert_self_signed "$crt"; then ss=1; else ss=0; fi
  printf '%s\n' \
    '# HELP rental_tls_cert_expiry_timestamp_seconds Unix time when the TLS certificate served by the front Nginx expires.' \
    '# TYPE rental_tls_cert_expiry_timestamp_seconds gauge' \
    "rental_tls_cert_expiry_timestamp_seconds $end" \
    '# HELP rental_tls_cert_self_signed Whether the TLS certificate served by the front Nginx is the temporary self-signed one (1) or CA-issued (0).' \
    '# TYPE rental_tls_cert_self_signed gauge' \
    "rental_tls_cert_self_signed $ss" \
    | write_metrics tls_cert_expiry || log "!!! 지표를 쓰지 못했다 — $RENTAL_METRICS_DIR/tls_cert_expiry.prom"
}

# ── 인증서 읽기 — 호스트의 openssl 로 본다(Rocky 기본 설치에 있다) ──
# 만료 시각(epoch 초)
cert_end_epoch() {
  local end
  end=$(openssl x509 -in "$1" -noout -enddate 2>/dev/null) || return 1
  date -d "${end#notAfter=}" +%s
}
# 자체 서명인가 — 발급자와 주체가 같으면 자체 서명이다. Let's Encrypt 인증서는 발급자가 중간 인증서(Let's Encrypt 조직)라 다르다
cert_self_signed() {
  [ "$(openssl x509 -in "$1" -noout -issuer 2>/dev/null | sed 's/^issuer=//')" = \
    "$(openssl x509 -in "$1" -noout -subject 2>/dev/null | sed 's/^subject=//')" ]
}
# 시험 서버가 발급했는가 — 시험 서버 인증서 계층의 이름에는 (STAGING) 이 붙는다(https://letsencrypt.org/docs/staging-environment/). 운영 발급으로 넘어가야 한다
cert_staging() {
  openssl x509 -in "$1" -noout -issuer 2>/dev/null | grep -q 'STAGING'
}
# SAN 의 IP 가 주어진 IP 를 담는가
cert_has_ip() {
  openssl x509 -in "$1" -noout -ext subjectAltName 2>/dev/null | grep -Eq "IP Address:$(printf '%s' "$2" | sed 's/[.]/\\./g')(,|\$|[[:space:]])"
}
# 발급이 필요한 이유를 출력한다. 필요 없으면 아무것도 출력하지 않고 1 로 끝난다
needs_issue() {
  local crt=$1 end now
  [ -r "$crt" ] || { echo "인증서 없음"; return 0; }
  if cert_self_signed "$crt"; then echo "임시 자체 서명 인증서"; return 0; fi
  if [ "$LE_STAGING" != 1 ] && cert_staging "$crt"; then echo "시험 서버 발급 인증서"; return 0; fi
  if ! cert_has_ip "$crt" "$IP"; then echo "SAN 의 IP 가 지금 IP($IP)와 다르다"; return 0; fi
  end=$(cert_end_epoch "$crt") || { echo "만료 시각을 읽지 못함"; return 0; }
  now=$(date +%s)
  if [ $(( end - now )) -lt $(( RENEW_BEFORE_HOURS * 3600 )) ]; then
    echo "남은 시간 $(( (end - now) / 3600 ))시간 < 기준 ${RENEW_BEFORE_HOURS}시간"; return 0
  fi
  return 1
}

# ── Nginx — deploy.sh 와 같은 방식(체크아웃 infra/ 에서 docker compose exec -T nginx)으로 검사 · reload 한다 ──
# root 로 체크아웃 안에서 docker compose 를 부른다 — infra/.env 의 COMPOSE_PROFILES 등을 Compose 가 스스로 읽는다.
# 체크아웃 파일은 읽기만 한다. deploy 홈(700)을 root 가 읽는 것이 SELinux(enforcing)에서 막히지 않는지는 노드에서 미확인이다 —
# systemd 가 부른 스크립트는 비제한 도메인에서 돌 것으로 보지만 첫 반영 때 저널 · ausearch 로 확인한다
compose() { (cd "$RENTAL_DIR" && docker compose "$@"); }
# grep -q 를 쓰지 않는다 — 찾자마자 끝나면 앞 명령이 SIGPIPE(141)를 받고 pipefail 이 파이프 전체를 거짓으로 만든다.
# 그러면 떠 있는 Nginx 를 「떠 있지 않다」로 보고 reload 를 건너뛰어, 새 인증서 파일과 달리 옛 인증서를 계속 내보낸다(검토 지적, #288)
nginx_running() { compose ps --status running --services 2>/dev/null | grep -x nginx >/dev/null; }
nginx_reload() { compose exec -T nginx nginx -t && compose exec -T nginx nginx -s reload; }

# ── 1. 공인 IP — IMDSv2(토큰을 PUT 으로 받고 그 토큰으로 GET) ──
# IMDSv1(토큰 없는 GET)은 인스턴스 설정에 따라 막혀 있을 수 있어 쓰지 않는다. 토큰 수명은 이 실행 동안이면 된다(60초)
imds_public_ip() {
  local token
  token=$(curl -fsS -m 5 -X PUT http://169.254.169.254/latest/api/token \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 60') || return 1
  curl -fsS -m 5 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/public-ipv4
}
if [ -n "$PUBLIC_IP" ]; then
  IP=$PUBLIC_IP
  log "공인 IP(손으로 준 값): $IP"
else
  IP=$(imds_public_ip) || fail "IMDSv2 로 공인 IP 를 얻지 못했다 — 공인 IP 가 없는 인스턴스거나 메타데이터 접근이 막혔다"
  log "공인 IP(IMDSv2): $IP"
fi
[[ "$IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || fail "공인 IP 모양이 아니다: $IP"
# 입구 이중화 — 인증서의 IP 는 늘 ENTRY_IP 다. 지금 그 IP 를 가졌는가로 역할이 갈린다(머리 주석)
ROLE=single
if [ -n "$ENTRY_IP" ]; then
  [[ "$ENTRY_IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || fail "ENTRY_IP 모양이 아니다: $ENTRY_IP"
  if [ "$IP" = "$ENTRY_IP" ]; then
    ROLE=active
    log "입구 고정 IP 를 이 노드가 가졌다 — 확인 · 발급하고 상대에 넘긴다"
  else
    ROLE=standby
    log "입구 고정 IP($ENTRY_IP)는 상대 노드에 있다 — 발급하지 않고 넘겨받은 것만 본다"
  fi
  IP=$ENTRY_IP
fi

install -d -o root -g root -m 0700 "$TLS_DIR"
install -d -o root -g root -m 0755 "$ACME_WEBROOT"
install -d -o root -g root -m 0700 "$LE_DIR"

CRT="$TLS_DIR/fullchain.pem"
KEY="$TLS_DIR/privkey.pem"

# ── 2. 임시 자체 서명 — Nginx 가 443 으로 뜰 자리를 먼저 채운다 ──
# 443 서버 블록은 인증서 파일이 없으면 설정 적재에 실패하고, 그러면 80(ACME 확인 자리)까지 함께 뜨지 못한다 — 닭과 달걀.
# 그래서 인증서가 없을 때 · 있는 것이 자체 서명인데 이미 만료됐거나 IP 가 다를 때만 새로 만든다. CA 발급 인증서는 만료됐어도
# 덮지 않는다 — Nginx 는 만료된 인증서로도 뜨므로(80 이 살아 있다) 아래 발급이 바로 교체한다.
# ECDSA P-256 · 유효 2일 — 발급이 이틀 넘게 실패하면 경보(자체 서명 사용 중)가 먼저 울린다. 브라우저는 어차피 신뢰하지 않는다
make_self_signed() {
  local tkey="$TLS_DIR/.privkey.pem.tmp" tcrt="$TLS_DIR/.fullchain.pem.tmp"
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 \
    -subj "/CN=$IP" -addext "subjectAltName=IP:$IP" \
    -keyout "$tkey" -out "$tcrt" 2>/dev/null || { rm -f -- "$tkey" "$tcrt"; return 1; }
  chmod 600 "$tkey"; chmod 644 "$tcrt"
  mv -f -- "$tkey" "$KEY"
  mv -f -- "$tcrt" "$CRT"
}
if [ ! -s "$CRT" ] || [ ! -s "$KEY" ]; then
  make_self_signed || fail "임시 자체 서명 인증서를 만들지 못했다"
  log "인증서가 없어 임시 자체 서명을 만들었다(CN/SAN $IP, 유효 2일)"
  SELF_MADE=1
elif cert_self_signed "$CRT" && { ! cert_has_ip "$CRT" "$IP" || ! openssl x509 -in "$CRT" -noout -checkend 0 >/dev/null; }; then
  make_self_signed || fail "임시 자체 서명 인증서를 다시 만들지 못했다"
  log "임시 자체 서명이 만료됐거나 IP 가 달라 다시 만들었다(CN/SAN $IP)"
  SELF_MADE=1
else
  SELF_MADE=0
fi
if [ "$SELF_MADE" = 1 ] && [ "$PREPARE_ONLY" != 1 ] && nginx_running; then
  nginx_reload || log "!!! 임시 인증서로 reload 하지 못했다 — 발급 단계의 80 확인에서 다시 본다"
fi
cert_state

# ── 입구 이중화 — 상대에 넘기기(활성) · 넘겨받기(대기) ──
# 키와 인증서가 짝인가 — 공개키를 견준다. 짝이 아니면 Nginx 가 설정을 싣지 못한다
cert_key_match() {
  [ "$(openssl x509 -in "$1" -noout -pubkey 2>/dev/null | openssl sha256)" = \
    "$(openssl pkey -in "$2" -pubout 2>/dev/null | openssl sha256)" ]
}
# 지금 쌍을 상대 노드에 넘긴다. root 가 읽고(인증서 디렉터리 0700) PUSH_USER 의 SSH 로 보낸다 — 그 계정의 키 · ~/.ssh/config ·
# known_hosts 를 쓴다(배포 스크립트의 노드 간 SSH 와 같은 것). 받는 쪽은 임시 디렉터리에 풀고 옮긴다. 비대화식이라 묻지 않고 실패한다
push_to_peer() {
  [ "$ROLE" = active ] && [ -n "$PEER_SSH" ] || return 0
  if cert_self_signed "$CRT"; then log "임시 자체 서명이라 상대에 넘기지 않는다"; return 0; fi
  # shellcheck disable=SC2016 # 작은따옴표 안은 원격 셸에서 풀 변수다
  if tar -C "$TLS_DIR" -cf - fullchain.pem privkey.pem \
      | runuser -u "$PUSH_USER" -- ssh -o BatchMode=yes -o ConnectTimeout=10 "$PEER_SSH" \
          'umask 077; d=rental-tls-inbox; rm -rf "$d/.new" && mkdir -p "$d/.new" && tar -C "$d/.new" -xf - && mv -f "$d/.new/fullchain.pem" "$d/.new/privkey.pem" "$d/" && rmdir "$d/.new"'; then
    log "상대 노드에 인증서 사본을 넘겼다 — $PEER_SSH:rental-tls-inbox/"
    last_success tls_cert_copy
  else
    log "!!! 상대 노드에 인증서 사본을 넘기지 못했다($PEER_SSH) — 입구가 옮겨 가면 그 노드가 새로 발급한다(발급 한도 한 장). 작업은 실패로 두지 않는다"
  fi
}
# 넘겨받은 쌍을 본다(대기 노드). 쓸 만하면 바꿔 끼우고 reload 한 뒤 받은 쌍을 지운다. 쓸 수 없으면 지우고 알린다
import_from_inbox() {
  local icrt="$TLS_INBOX/fullchain.pem" ikey="$TLS_INBOX/privkey.pem" cur_end new_end
  if [ ! -r "$icrt" ] || [ ! -r "$ikey" ]; then
    log "넘겨받은 인증서가 없다($TLS_INBOX) — 지금 것을 둔다"
    return 0
  fi
  if cert_self_signed "$icrt" || { [ "$LE_STAGING" != 1 ] && cert_staging "$icrt"; } || ! cert_has_ip "$icrt" "$IP" \
      || ! openssl x509 -in "$icrt" -noout -checkend 0 >/dev/null || ! cert_key_match "$icrt" "$ikey"; then
    log "!!! 넘겨받은 인증서를 쓸 수 없다(자체 서명 · 시험 서버 · SAN 에 $IP 없음 · 만료 · 키 불일치 중 하나) — 지우고 다음 사본을 기다린다"
    rm -f -- "$icrt" "$ikey"
    return 0
  fi
  new_end=$(cert_end_epoch "$icrt") || { log "!!! 넘겨받은 인증서의 만료 시각을 읽지 못했다"; return 0; }
  cur_end=0
  if [ -r "$CRT" ] && ! cert_self_signed "$CRT" && cert_has_ip "$CRT" "$IP"; then cur_end=$(cert_end_epoch "$CRT" || echo 0); fi
  if [ "$new_end" -le "$cur_end" ]; then
    log "넘겨받은 인증서가 지금 것보다 늦게 만료되지 않는다 — 지우고 지금 것을 둔다"
    rm -f -- "$icrt" "$ikey"
    return 0
  fi
  cp -f -- "$CRT" "$TLS_DIR/fullchain.pem.prev"
  cp -f -- "$KEY" "$TLS_DIR/privkey.pem.prev"
  chmod 600 "$TLS_DIR/privkey.pem.prev"
  install -o root -g root -m 600 "$ikey" "$TLS_DIR/.privkey.pem.tmp"
  install -o root -g root -m 644 "$icrt" "$TLS_DIR/.fullchain.pem.tmp"
  mv -f -- "$TLS_DIR/.privkey.pem.tmp" "$KEY"
  mv -f -- "$TLS_DIR/.fullchain.pem.tmp" "$CRT"
  if nginx_running && ! nginx_reload; then
    mv -f -- "$TLS_DIR/fullchain.pem.prev" "$CRT"
    mv -f -- "$TLS_DIR/privkey.pem.prev" "$KEY"
    cert_state
    fail "넘겨받은 인증서로 nginx -t · reload 실패 — 옛 인증서로 되돌렸다"
  fi
  rm -f -- "$TLS_DIR/fullchain.pem.prev" "$TLS_DIR/privkey.pem.prev" "$icrt" "$ikey"
  log "넘겨받은 인증서로 바꿨다 — 만료 $(date -d "@$new_end" '+%F %T')"
  cert_state
}

# 첫 반영 — 인증서 자리만 채우고 끝낸다. 운영 Compose 의 nginx 를 새 마운트로 다시 만들기 전에 부른다(그 서비스의 마운트 주석).
# 이때 떠 있는 Nginx 는 옛 설정 · 옛 마운트라 reload 도 발급도 하지 않는다
if [ "$PREPARE_ONLY" = 1 ]; then
  log "PREPARE_ONLY=1 — 인증서 자리만 확인하고 끝낸다($CRT). 발급은 Nginx 를 다시 만든 뒤 rental-tls.service 가 한다"
  exit 0
fi

# 대기 노드 — 발급하지 않는다(머리 주석 「입구 이중화」)
if [ "$ROLE" = standby ]; then
  import_from_inbox
  last_success tls_cert
  exit 0
fi

# ── 3. 발급이 필요한가 — 필요 없으면 Nginx 를 기다리지도 않고 끝낸다(두 번째 실행은 여기서 끝난다) ──
if ! reason=$(needs_issue "$CRT"); then
  log "발급하지 않는다 — CA 발급 · SAN $IP · 만료 $(date -d "@$(cert_end_epoch "$CRT")" '+%F %T') · 기준 ${RENEW_BEFORE_HOURS}시간 전(발급 한도 보호)"
  last_success tls_cert
  push_to_peer
  exit 0
fi
log "발급이 필요하다 — $reason"

# certbot 에 이미 받아 둔 쓸 만한 인증서가 있으면(발급은 됐는데 복사 · reload 에서 멈췄던 경우) 다시 받지 않고 그것을 쓴다 — 한도 보호
LIVE="$LE_DIR/live/$CERT_NAME"
if [ -r "$LIVE/fullchain.pem" ] && ! needs_issue "$LIVE/fullchain.pem" >/dev/null; then
  log "certbot 에 이미 받은 인증서가 있다($LIVE) — 새로 발급하지 않고 그것을 쓴다"
else
  # ── 4. 앞단 80 이 ACME 확인 경로를 내보낼 때까지 기다린다 ──
  # 2026-09-28 논리 백업 timer 가 부팅 1초 뒤, DB 컨테이너가 뜨기 전에 돌아 실패하고 그날 다시 시도하지 않았다(#286).
  # 같은 함정 — 이 작업도 부팅 뒤 timer 로 돌고, 앞단 Nginx 가 80 을 받기 전에 certbot 을 부르면 Let's Encrypt 의 http-01 확인이
  # 실패해 검증 실패 한도(1시간 5회)만 쓴다. 그래서 certbot 을 부르기 전에, 웹루트에 둔 확인 파일이 80 으로 그대로 나오는지 본다.
  # 「80 이 무엇이든 답한다」가 아니라 「이 웹루트를 이 경로로 내보낸다」를 본다 — 옛 설정(ACME location 없음) · 웹루트 마운트 빠짐도 여기서 걸린다.
  # 루프백으로 본다 — 밖에서 닿는지(보안 그룹 · firewalld 의 80)는 확인하지 못한다. 그것이 막혀 있으면 certbot 이 실패로 알린다
  PROBE_NAME="rental-probe-$(openssl rand -hex 8)"
  PROBE_BODY=$(openssl rand -hex 16)
  PROBE_DIR="$ACME_WEBROOT/.well-known/acme-challenge"
  install -d -o root -g root -m 0755 "$ACME_WEBROOT/.well-known" "$PROBE_DIR"
  ( umask 022; printf '%s' "$PROBE_BODY" > "$PROBE_DIR/$PROBE_NAME" )
  trap 'rm -f -- "$PROBE_DIR/$PROBE_NAME"' EXIT
  ok=0 code=
  for ((i = 1; i <= WAIT_TRIES; i++)); do
    # 본문 뒤에 공백 하나와 상태 코드를 덧붙여 받는다 — 본문(16진 문자열이라 공백이 없다)과 코드를 한 요청으로 함께 본다
    body=$(curl -s -m 3 -w ' %{http_code}' "http://127.0.0.1/.well-known/acme-challenge/$PROBE_NAME" || true)
    code=${body##* }
    if [ "$code" = 200 ] && [ "${body% *}" = "$PROBE_BODY" ]; then ok=1; break; fi
    sleep "$WAIT_INTERVAL"
  done
  rm -f -- "$PROBE_DIR/$PROBE_NAME"
  [ "$ok" = 1 ] || fail "앞단 80 이 $(( WAIT_TRIES * WAIT_INTERVAL ))초 안에 ACME 확인 경로를 내보내지 않았다(마지막 HTTP ${code:-없음}, 000 = 연결 안 됨) — docker compose ps nginx · 로그, 컨테이너의 /var/lib/rental-acme 마운트, conf.d/default.conf 의 80 서버를 본다. 다음 실행(유닛 재시도 · timer)이 다시 시도한다"
  log "앞단 80 이 ACME 확인 경로를 내보낸다"

  # ── 5. 발급 ──
  # --force-renewal — 발급 여부는 위에서 이미 정했다. 없으면 certbot 이 스스로 「아직 갱신 때가 아니다」로 건너뛸 수 있다.
  # --cert-name 이 같고 IP 가 바뀌면 certbot 은 「새 식별자로 바꾸는가」를 묻는데, 비대화식에서는 기본값(바꾼다)으로 진행한다
  # (certbot v5.8.0 _internal/main.py _ask_user_to_confirm_new_sans — yesno default=True). 키 종류를 바꿀 때는 --cert-name 과
  # --key-type 을 함께 주면 확인 없이 바꾼다(같은 파일 _handle_unexpected_key_type_migration). 노드에서 실제로 돌려 보지는 않았다 — 미확인.
  # 계정은 이메일 없이 등록한다 — 저장소 · 노드에 주소를 두지 않는다. 만료 감시는 이 스크립트의 지표 → Grafana 경보가 한다
  log "certbot 발급 시작 — $CERTBOT_IMAGE, 키 $KEY_TYPE, 이름 $CERT_NAME, 시험 서버 LE_STAGING=$LE_STAGING"
  docker run --rm \
    -v "$LE_DIR:/etc/letsencrypt" \
    -v "$ACME_WEBROOT:/var/lib/rental-acme" \
    "$CERTBOT_IMAGE" certonly \
    --non-interactive --agree-tos --register-unsafely-without-email \
    --webroot -w /var/lib/rental-acme \
    --preferred-profile shortlived \
    --ip-address "$IP" \
    --cert-name "$CERT_NAME" \
    "${KEY_ARGS[@]}" \
    "${STAGING_ARGS[@]}" \
    --force-renewal \
    || fail "certbot 발급 실패 — 위 certbot 출력을 본다. 흔한 원인: 보안 그룹 · firewalld 의 80, 앞단 80 서버 블록의 ACME 경로, 발급 한도"
  [ -r "$LIVE/fullchain.pem" ] && [ -r "$LIVE/privkey.pem" ] || fail "certbot 은 성공했는데 $LIVE 에 인증서가 없다"
  cert_has_ip "$LIVE/fullchain.pem" "$IP" || fail "발급된 인증서의 SAN 에 $IP 가 없다"
fi

# ── 6. /etc/rental/tls 로 옮기고 Nginx 에 반영 ──
# live/ 는 archive/ 를 가리키는 링크라 링크를 따라 내용을 복사한다(cp -L). 같은 디렉터리의 임시 파일 → mv 로 바꾼다.
# 옛 쌍은 .prev 로 남겨 nginx -t 가 실패하면 되돌린다 — 키와 인증서가 짝이 맞지 않으면 Nginx 가 설정을 싣지 못한다
cp -f -- "$CRT" "$TLS_DIR/fullchain.pem.prev"
cp -f -- "$KEY" "$TLS_DIR/privkey.pem.prev"
chmod 600 "$TLS_DIR/privkey.pem.prev"
cp -L -f -- "$LIVE/privkey.pem" "$TLS_DIR/.privkey.pem.tmp"
cp -L -f -- "$LIVE/fullchain.pem" "$TLS_DIR/.fullchain.pem.tmp"
chown root:root "$TLS_DIR/.privkey.pem.tmp" "$TLS_DIR/.fullchain.pem.tmp"
chmod 600 "$TLS_DIR/.privkey.pem.tmp"
chmod 644 "$TLS_DIR/.fullchain.pem.tmp"
mv -f -- "$TLS_DIR/.privkey.pem.tmp" "$KEY"
mv -f -- "$TLS_DIR/.fullchain.pem.tmp" "$CRT"
log "인증서를 $TLS_DIR 로 옮겼다 — 만료 $(date -d "@$(cert_end_epoch "$CRT")" '+%F %T')"

if nginx_running; then
  if ! nginx_reload; then
    mv -f -- "$TLS_DIR/fullchain.pem.prev" "$CRT"
    mv -f -- "$TLS_DIR/privkey.pem.prev" "$KEY"
    cert_state
    fail "nginx -t · reload 실패 — 옛 인증서로 되돌렸다. docker compose exec nginx nginx -t 출력을 본다"
  fi
  log "Nginx reload 완료"
else
  # 떠 있지 않으면 다음 기동 때 새 인증서를 읽는다. reload 할 대상이 없을 뿐 발급은 성공이다
  log "Nginx 가 떠 있지 않아 reload 하지 않았다 — 다음 기동 때 새 인증서를 읽는다"
fi
rm -f -- "$TLS_DIR/fullchain.pem.prev" "$TLS_DIR/privkey.pem.prev"

# ── 7. 지표 ──
cert_state
last_success tls_cert
# ── 8. (입구 이중화) 상대 노드에 사본 ──
push_to_peer
log "완료"
