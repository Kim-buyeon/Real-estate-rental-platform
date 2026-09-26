#!/usr/bin/env bash
# 앱 노드(APP-02) 호스트 방화벽 — 서버 운영 기반 설계서 3.3(접근 통제의 둘째 겹) · 8장(firewalld 행). INF-01.
# 노드 준비의 firewalld 단계(운영 절차서 9.1)에서 root 로 한 번 돌린다. 여러 번 돌려도 결과가 같다(이미 있는 것은 건너뛴다).
#
#   sudo bash app-node.sh
#
# 전제: firewalld 가 설치 · 활성이어야 한다. Rocky 9 EC2 공식 AMI 에는 설치돼 있지 않다(2026-09-25 실측) —
#       dnf install -y firewalld && systemctl enable --now firewalld. 설치 패키지는 APP-01(NAT)을 거쳐 받는다.
#       기본 public 영역에 ssh 가 있어 켜는 순간 ProxyJump 세션이 끊기지 않는다. Docker 설치 전에 돌린다(운영 절차서 9.1 순서).
#       Docker 설치 뒤에 다시 돌릴 때 --reload 가 Docker 규칙에 주는 영향은 노드에서 확인하지 않았다 — 그때는 슬롯 접속
#       (APP-01 에서 readiness)을 함께 본다.
#
# ── 이 노드가 받는 것 ──
#  - ssh — 사설 서브넷(2c)이라 공인 IP 가 없고 APP-01 을 거쳐 들어온다(ProxyJump). 배포 스크립트도 APP-01 에서 SSH 로 슬롯을
#    바꾼다. 출발지 제한은 보안 그룹(rental-app-node: 22 ← rental-app)이 한다. 노드별 허용 표는 시스템 구성서 4장.
#  - 앱 슬롯(8081 · 8082)은 여기서 열지 않는다. Docker 가 게시한 포트는 firewalld 를 우회하므로(DNAT 이 입력 규칙보다 먼저
#    목적지를 바꾼다) 이 규칙이 있어도 없어도 결과가 같다. 이 포트를 지키는 것은 보안 그룹(rental-app-node: 8081 · 8082 ←
#    rental-app)과 사설 서브넷 두 겹이고, 게시 주소를 노드 사설 IP 하나로 좁히는 것(0.0.0.0 금지)은 운영 Compose 의 몫이다
#    (APP_PUBLISH_ADDR). DB 노드의 5432 와 같은 예외다(설계서 3.3). DOCKER-USER 체인으로 한 겹 더 두지 않는 것도 같은 이유다 —
#    보안 그룹이 이미 그룹 단위로 출발지를 좁혀 같은 출발지를 IP 로 한 번 더 적는 것뿐이다(2026-09-25 결정, 같은 절).
#  - 관측 수집기 · node exporter 는 루프백이라 받을 것이 없다. 지표 · 로그는 나가는 전송뿐이다(APP-01 NAT 경유).
set -euo pipefail

ZONE=public
SERVICES=(ssh)
# 기본 public 영역에 딸려 오는 것 중 이 노드가 쓰지 않는 것. 없으면 건너뛴다.
UNUSED=(cockpit dhcpv6-client)

fail() { echo "app-node.sh: $*" >&2; exit 1; }

[[ ${EUID} -eq 0 ]] || fail "root 로 실행한다"
command -v firewall-cmd >/dev/null || fail "firewalld 가 설치돼 있지 않다 — dnf install -y firewalld"
systemctl is-active --quiet firewalld || fail "firewalld 가 활성이 아니다 — systemctl enable --now firewalld"
# eth0 는 영역을 따로 지정하지 않아 기본 영역에 든다. 기본 영역이 public 이 아니면 아래 규칙이 NIC 에 걸리지 않는다.
[[ "$(firewall-cmd --get-zone-of-interface=eth0 2>/dev/null)" == public ]] || fail "eth0 가 public 영역이 아니다 — 연결에 다른 영역이 묶여 있으면 아래 규칙이 NIC 에 걸리지 않는다"
[[ "$(firewall-cmd --get-default-zone)" == "${ZONE}" ]] || fail "기본 영역이 ${ZONE} 가 아니다"

for s in "${SERVICES[@]}"; do
  firewall-cmd --permanent --zone="${ZONE}" --query-service="${s}" >/dev/null \
    || firewall-cmd --permanent --zone="${ZONE}" --add-service="${s}"
done
for s in "${UNUSED[@]}"; do
  if firewall-cmd --permanent --zone="${ZONE}" --query-service="${s}" >/dev/null; then
    firewall-cmd --permanent --zone="${ZONE}" --remove-service="${s}"
  fi
done

firewall-cmd --reload

firewall-cmd --zone="${ZONE}" --list-all
