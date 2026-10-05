#!/usr/bin/env bash
# 입구 감시 — 입구 고정 IP(Elastic IP)를 가진 상대 노드가 응답하지 않으면 그 IP 를 자기에게 옮긴다(INF-01 입구 이중화, #404).
# 서비스 유닛(rental-entry-watch.service)이 두 앱 노드에서 root 로 늘 돌린다. 시스템 구성서 3장 · 운영 절차서(입구 전환).
#
#   systemctl start rental-entry-watch.service     설치 · 반영은 그 유닛 머리 주석
#   ONCE=1 bash entry-watch.sh                      한 바퀴만 돌고 끝낸다(판정만 보고 옮기지 않으려면 DRY_RUN=1 을 함께)
#
# ── 판정 ──
#  액티브-스탠바이다. 고정 IP 를 가진 노드(활성)는 아무것도 하지 않는다 — 이 스크립트는 대기 노드에서만 일한다.
#  내가 가졌는가는 IMDSv2 의 public-ipv4 가 ENTRY_IP 와 같은가로 본다(AWS API 를 부르지 않는다 — 노드 안에서 끝난다).
#  대기 노드는 INTERVAL 초마다 상대 노드의 HTTPS(사설 IP · HEALTH_PATH)를 두드린다. FAIL_THRESHOLD 번 연달아 실패하면
#   (1) 자기 입구가 성한가 — 같은 요청을 자기 사설 IP 로. 자기도 실패하면 옮겨 봐야 소용이 없다(DB 가 멈춘 경우 등 두 노드 공통 장애)
#   (2) 고정 IP 로도 실패하는가 — 같은 요청을 인터넷 쪽(ENTRY_IP)으로. 사설망만 끊긴 경우(상대는 밖에서 멀쩡히 받는다) 옮기지 않는다 —
#       옮기면 끊긴 사설망 너머의 상대도 같은 판정으로 되찾아 가 고정 IP 가 두 노드 사이를 오간다
#  둘 다 맞으면 aws ec2 associate-address --allow-reassociation 으로 옮긴다 — 대상은 인스턴스가 아니라 **이 노드의 주 ENI** 다
#  (IMDS 로 얻는다). 인스턴스 역할의 권한이 두 노드의 ENI 로만 열려 있다(infra/aws/entry-iam.sh). 상대의 연결은 그 순간 끊긴다.
#  **되찾지 않는다**(non-preemptive) — 옮긴 뒤 상대가 돌아와도 그대로 둔다. 상대가 대기 노드가 되어 같은 감시를 한다.
#  고정 IP 를 잃은 노드는 HOLD_DOWN 초 동안 옮기지 않는다 — 판정이 엇갈려도 곧바로 되가져가지 않게 하는 마지막 안전장치다.
#  부팅 뒤 BOOT_GRACE 초 동안도 옮기지 않는다 — 두 노드를 함께 켜면(운영 절차서 9.1 「켜고 끄기」) 먼저 뜬 쪽이 아직 뜨는 중인 상대를
#  죽은 것으로 보고 고정 IP 를 가져간다. APP-01 재부팅에서 API 200 까지 92초였다(운영 절차서 9.5) — 그보다 넉넉히 둔다.
#  **연속 이동 상한(플래핑)** — 이 노드가 가져온 뒤 FLAP_WINDOW 초 안에 상대가 도로 가져갔으면(핑퐁 — 두 노드의 판정이 엇갈린다는 뜻)
#  FLAP_HOLD 초 동안 이 노드는 자동으로 옮기지 않는다. 로그 「!!! 핑퐁」과 지표 rental_entry_flap_hold_until_timestamp_seconds 로 남는다 —
#  그동안 입구는 상대에 고정되고, 원인(사설망 · 판정 값)을 사람이 본다. 서비스를 재시작해도 지표 파일에서 이어받는다 —
#  손으로 풀 때는 entry.prom 을 지우고 재시작한다(운영 절차서 9.6). 상대도 같은 규칙이라, 핑퐁이 한 번 나면 늦어도 두 번째에 멈춘다.
#  **부하 시험 중에는 감시를 끈다**(운영 절차서 9.6 · 시험 계획서) — 과부하로 입구 응답이 4초를 넘으면 그것만으로 옮긴다.
#
# ── 성공 판정 ──
#  HTTP 응답 코드가 2xx · 3xx · 4xx 또는 503 이면 그 노드의 입구가 산 것으로 본다. 앞단 Nginx 가 응답했다는 뜻이다 —
#  503 은 점검 모드(conf.d/default.conf — 사람이 켠 것)라 장애가 아니다. 000(연결 · 시간 초과) · 그 밖의 5xx(502 = web 이 죽음 등)는 실패.
#  인증서는 검증하지 않는다(-k) — 사설 IP 로 부르므로 이름이 맞지 않고, 보고 싶은 것은 입구가 응답하는가다. 인증서 만료는 따로 경보가 있다.
#
# ── 값(잠정) ──
#  INTERVAL 5초 · FAIL_THRESHOLD 3 · 요청 상한 연결 2초 · 전체 4초. 상대 입구가 죽은 순간부터 associate-address 를 부르기까지 —
#    한 바퀴 = 실패한 상대 확인(거절이면 약 0초 · 패킷이 버려지면 연결 상한 2초 · 연결 뒤 매달리면 전체 상한 4초) + 대기 5초.
#    실패 3번이 필요하다 — 죽은 순간이 확인 바로 앞이면 그 사이 대기 2번, 바로 뒤면 3번. 셋째 실패 뒤 마지막 확인 둘 — 자기 입구(성하면
#    바로 응답, 약 0초) · 고정 IP(상대가 죽었으니 거절 약 0초 · 버려지면 2초 · 매달리면 4초). IMDS 호출(로컬, 수 ms)은 뺀다.
#      가장 빠름  대기 2 × 5 + 확인 3 × 0 + 마지막 0          = 약 10초     (노드는 살고 Nginx 만 죽어 거절)
#      흔한 경우  대기 2 ~ 3 × 5 + 확인 3 × 2 + 마지막 2      = 약 18 ~ 23초 (노드가 통째로 멈춰 패킷이 버려진다)
#      가장 느림  대기 3 × 5 + 확인 3 × 4 + 마지막 4          = 약 31초     (연결은 되는데 응답이 없다)
#    여기에 associate-address 의 API 응답 시간과 고정 IP 가 새 노드로 길을 바꾸는 시간이 더해진다.
#  2026-10-05 전환 리허설에서 1회 쟀다(운영 절차서 9.6 전환 리허설 「결과」 — 이동 시간 · API 시간 · 입구 공백의 정본). 표본 1이라
#  값을 바꾸지 않았다 — 확정은 표본을 더 모으고 오판 여부(운영 중 저널)를 본 뒤에 한다. HOLD_DOWN 300초 · BOOT_GRACE 300초 ·
#  FLAP_WINDOW 1800초 · FLAP_HOLD 1800초도 잠정이다.
#
# ── 설정 — /etc/rental/entry.env(유닛의 EnvironmentFile). 시크릿이 없다. 노드마다 PEER_ADDR · SELF_ADDR 만 다르다 ──
#  ENTRY_ALLOC_ID   고정 IP 할당 ID(eipalloc-…) — infra/aws/entry-eip.sh 가 출력한다
#  ENTRY_IP         고정 IP 주소
#  SELF_ADDR        이 노드의 사설 IP · PEER_ADDR 상대 노드의 사설 IP
#  AWS_REGION       ap-northeast-2
#
# ── 기록 ──
#  옮기면 저널(journalctl -u rental-entry-watch)에 남고, node exporter textfile 지표(/var/lib/rental-metrics/entry.prom)를 쓴다 —
#  rental_entry_eip_held(0 · 1 — 이 노드가 지금 입구인가) · rental_entry_takeover_last_timestamp_seconds(마지막으로 옮겨 온 시각) ·
#  rental_entry_peer_up(0 · 1 — 마지막 상대 판정) · rental_entry_flap_hold_until_timestamp_seconds(핑퐁으로 자동 이동을 멈춘 끝 시각, 0 = 없음). 백업 · 인증서 스크립트와 같은 방식(쓰기 함수 · 644 · 임시 파일 → mv)이다.
#  Grafana 에서 「입구가 옮겨졌다」(held 가 바뀜) · 「대기 노드가 상대를 못 본다」(peer_up 0 지속) 알림을 걸 자리다 — 규칙은 관측 작업에서 만든다.
set -uo pipefail
umask 022

log() { printf '%s >>> %s\n' "$(date '+%F %T')" "$*"; }

ENV_FILE=${ENV_FILE:-/etc/rental/entry.env}
# 유닛이 EnvironmentFile 로 이미 읽었어도 손으로 부를 때를 위해 한 번 더 읽는다(값은 같다)
if [ -r "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

: "${ENTRY_ALLOC_ID:?ENTRY_ALLOC_ID 가 없다 — $ENV_FILE}"
: "${ENTRY_IP:?ENTRY_IP 가 없다 — $ENV_FILE}"
: "${SELF_ADDR:?SELF_ADDR 가 없다 — $ENV_FILE}"
: "${PEER_ADDR:?PEER_ADDR 가 없다 — $ENV_FILE}"
AWS_REGION=${AWS_REGION:-ap-northeast-2}
INTERVAL=${INTERVAL:-5}
FAIL_THRESHOLD=${FAIL_THRESHOLD:-3}
HOLD_DOWN=${HOLD_DOWN:-300}
BOOT_GRACE=${BOOT_GRACE:-300}
FLAP_WINDOW=${FLAP_WINDOW:-1800}
FLAP_HOLD=${FLAP_HOLD:-1800}
HEALTH_PATH=${HEALTH_PATH:-/}
CONNECT_TIMEOUT=${CONNECT_TIMEOUT:-2}
MAX_TIME=${MAX_TIME:-4}
DRY_RUN=${DRY_RUN:-0}
ONCE=${ONCE:-0}
RENTAL_METRICS_DIR=${RENTAL_METRICS_DIR:-/var/lib/rental-metrics}
export AWS_REGION AWS_DEFAULT_REGION=$AWS_REGION

# ── IMDSv2 — 토큰을 PUT 으로 받고 그 토큰으로 GET(issue-cert.sh 와 같다) ──
imds() {
  local token
  token=$(curl -fsS -m 2 -X PUT http://169.254.169.254/latest/api/token \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 60') || return 1
  curl -fsS -m 2 -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"
}

# 그 주소의 입구가 응답하는가 — 위 「성공 판정」
probe() {
  local code
  code=$(curl -k -s -o /dev/null -w '%{http_code}' --connect-timeout "$CONNECT_TIMEOUT" -m "$MAX_TIME" "https://$1$HEALTH_PATH" 2>/dev/null) || true
  case "$code" in
    2??|3??|4??|503) return 0 ;;
    *) return 1 ;;
  esac
}

# ── 지표 — infra/backup/*.sh · infra/tls/issue-cert.sh 의 write_metrics 와 같은 방식 ──
write_metrics() {
  local dir=${RENTAL_METRICS_DIR%/} tmp
  if [ ! -d "$dir" ]; then cat > /dev/null; return 0; fi
  tmp="$dir/.$1.prom.tmp"
  { cat > "$tmp" && chmod 644 "$tmp" && mv -f -- "$tmp" "$dir/$1.prom"; } || { rm -f -- "$tmp"; return 1; }
}
LAST_TAKEOVER=0
FLAP_UNTIL=0
# 이전 실행이 남긴 마지막 이동 시각 · 핑퐁 멈춤 끝 시각을 이어받는다 — 재시작으로 0 이 되면 「옮긴 적 없다」 · 「멈춤 없음」으로 보인다
if [ -r "$RENTAL_METRICS_DIR/entry.prom" ]; then
  LAST_TAKEOVER=$(awk '/^rental_entry_takeover_last_timestamp_seconds /{print $2}' "$RENTAL_METRICS_DIR/entry.prom" 2>/dev/null)
  FLAP_UNTIL=$(awk '/^rental_entry_flap_hold_until_timestamp_seconds /{print $2}' "$RENTAL_METRICS_DIR/entry.prom" 2>/dev/null)
  LAST_TAKEOVER=${LAST_TAKEOVER:-0}
  FLAP_UNTIL=${FLAP_UNTIL:-0}
fi
metrics() {   # <held 0|1> <peer_up 0|1>
  printf '%s\n' \
    '# HELP rental_entry_eip_held Whether this node currently holds the entry Elastic IP (1) or stands by (0).' \
    '# TYPE rental_entry_eip_held gauge' \
    "rental_entry_eip_held $1" \
    '# HELP rental_entry_peer_up Whether the last probe from this standby node reached the peer entry (1) or not (0). 1 while this node holds the IP.' \
    '# TYPE rental_entry_peer_up gauge' \
    "rental_entry_peer_up $2" \
    '# HELP rental_entry_takeover_last_timestamp_seconds Unix time when this node last moved the entry Elastic IP to itself (0 if never).' \
    '# TYPE rental_entry_takeover_last_timestamp_seconds gauge' \
    "rental_entry_takeover_last_timestamp_seconds $LAST_TAKEOVER" \
    '# HELP rental_entry_flap_hold_until_timestamp_seconds Unix time until which this node will not move the entry IP automatically after a ping-pong (0 if none).' \
    '# TYPE rental_entry_flap_hold_until_timestamp_seconds gauge' \
    "rental_entry_flap_hold_until_timestamp_seconds $FLAP_UNTIL" \
    | write_metrics entry || log "!!! 지표를 쓰지 못했다 — $RENTAL_METRICS_DIR/entry.prom"
}

command -v aws > /dev/null || log "!!! aws CLI 가 없다 — 옮겨야 할 때 실패한다(운영 절차서 — AWS CLI v2 설치)"
log "시작 — 입구 $ENTRY_IP ($ENTRY_ALLOC_ID) · 나 $SELF_ADDR · 상대 $PEER_ADDR · ${INTERVAL}초 × ${FAIL_THRESHOLD}회 · 되찾지 않음 · 잃은 뒤 ${HOLD_DOWN}초 대기"

fails=0
held_prev=""
hold_until=0
while :; do
  now=$(date +%s)
  if ! pub=$(imds public-ipv4 2>/dev/null); then pub=""; fi
  if [ "$pub" = "$ENTRY_IP" ]; then
    # 활성 — 아무것도 하지 않는다
    [ "$held_prev" = 1 ] || log "이 노드가 입구다($ENTRY_IP) — 감시만 쉰다"
    held_prev=1; fails=0
    metrics 1 1
  else
    if [ "$held_prev" = 1 ]; then
      hold_until=$(( now + HOLD_DOWN ))
      log "!!! 입구를 잃었다 — 지금 공인 IP ${pub:-없음}. ${HOLD_DOWN}초 동안 옮기지 않는다(되찾지 않는다 — 상대가 입구다)"
      # 핑퐁 — 이 노드가 가져온 지 FLAP_WINDOW 안에 상대가 도로 가져갔다
      if [ "$LAST_TAKEOVER" -gt 0 ] && [ $(( now - LAST_TAKEOVER )) -lt "$FLAP_WINDOW" ]; then
        FLAP_UNTIL=$(( now + FLAP_HOLD ))
        log "!!! 핑퐁 — 이 노드가 $(( now - LAST_TAKEOVER ))초 전에 가져온 입구를 상대가 도로 가져갔다. $(date -d "@$FLAP_UNTIL" '+%F %T')까지 자동으로 옮기지 않는다 — 사설망 · 판정 값을 본다(운영 절차서 9.6)"
      fi
    elif [ -z "$held_prev" ]; then
      log "대기 노드다 — 지금 공인 IP ${pub:-없음}. 상대 입구(https://$PEER_ADDR$HEALTH_PATH)를 본다"
    fi
    held_prev=0
    if probe "$PEER_ADDR"; then
      [ "$fails" -eq 0 ] || log "상대 입구 응답 복귀(연속 실패 ${fails}회에서)"
      fails=0
      metrics 0 1
    else
      fails=$(( fails + 1 ))
      log "상대 입구 응답 없음 ${fails}/${FAIL_THRESHOLD}"
      metrics 0 0
      if [ "$fails" -ge "$FAIL_THRESHOLD" ]; then
        up=$(cut -d. -f1 /proc/uptime 2>/dev/null || echo "$BOOT_GRACE")
        if [ "$now" -lt "$FLAP_UNTIL" ]; then
          log "옮기지 않는다 — 핑퐁 뒤 자동 이동 멈춤(남은 $(( FLAP_UNTIL - now ))초)"
        elif [ "$now" -lt "$hold_until" ]; then
          log "옮기지 않는다 — 입구를 잃은 뒤 대기 중(남은 $(( hold_until - now ))초)"
        elif [ "$up" -lt "$BOOT_GRACE" ]; then
          log "옮기지 않는다 — 부팅 뒤 ${up}초(기준 ${BOOT_GRACE}초). 함께 켠 상대가 아직 뜨는 중일 수 있다"
        elif ! probe "$SELF_ADDR"; then
          log "옮기지 않는다 — 이 노드의 입구도 응답하지 않는다(https://$SELF_ADDR$HEALTH_PATH). 두 노드 공통 장애로 본다"
        elif probe "$ENTRY_IP"; then
          log "옮기지 않는다 — 고정 IP 로는 입구가 응답한다. 사설망만 끊긴 것으로 본다"
        elif [ "$DRY_RUN" = 1 ]; then
          log "DRY_RUN=1 — 옮길 조건이다. 옮기지 않는다"
        else
          # 주 ENI — IMDS 의 mac 은 주 ENI(장치 0)의 것이다
          eni=""
          if mac=$(imds mac 2>/dev/null); then eni=$(imds "network/interfaces/macs/$mac/interface-id" 2>/dev/null) || eni=""; fi
          if [[ "$eni" != eni-* ]]; then
            log "!!! 이 노드의 ENI 를 얻지 못했다(IMDS) — 다음 바퀴에 다시"
          else
            log "입구를 옮긴다 — $ENTRY_ALLOC_ID → $eni"
            t0=$(date +%s.%N)
            if out=$(aws ec2 associate-address --allocation-id "$ENTRY_ALLOC_ID" --network-interface-id "$eni" \
                      --allow-reassociation --query AssociationId --output text 2>&1); then
              LAST_TAKEOVER=$(date +%s)
              log "옮겼다 — association $out · API $(awk -v a="$t0" -v b="$(date +%s.%N)" 'BEGIN{printf "%.1f", b-a}')초. 인증서 확인을 부른다"
              metrics 1 0
              # 인증서는 상대가 넘겨 둔 사본(같은 고정 IP)이 이미 있다 — 확인 한 번이면 발급하지 않고 끝난다(issue-cert.sh)
              systemctl start --no-block rental-tls.service 2>/dev/null || log "rental-tls.service 를 부르지 못했다 — 손으로 확인한다"
              fails=0
            else
              log "!!! 옮기지 못했다 — $out"
            fi
          fi
        fi
      fi
    fi
  fi
  [ "$ONCE" = 1 ] && break
  sleep "$INTERVAL"
done
