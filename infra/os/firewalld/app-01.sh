#!/usr/bin/env bash
# APP-01 호스트 방화벽 — 서버 운영 기반 설계서 3.2(NAT 인스턴스 · 마스커레이드) · 3.3(접근 통제의 둘째 겹) · 8장(firewalld 행).
# 노드 준비의 firewalld 단계(운영 절차서 9.1)에서 root 로 한 번 돌린다. 여러 번 돌려도 결과가 같다(이미 있는 것은 건너뛴다).
#
#   sudo bash app-01.sh
#
# 전제: firewalld 가 설치 · 활성이어야 한다. Rocky 9 EC2 공식 AMI 에는 설치돼 있지 않다(2026-09-25 실측) —
#       dnf install -y firewalld && systemctl enable --now firewalld. 기본 public 영역에 ssh 가 있어 켜는 순간 세션이 끊기지 않는다.
#       Docker 설치 전에 돌린다(운영 절차서 9.1 순서). Docker 설치 뒤에 다시 돌릴 때 --reload 가 Docker 규칙에 주는
#       영향은 노드에서 확인하지 않았다 — 그때는 docker compose ps · 외부 접속을 함께 본다.
#
# ── 이 노드가 받는 것 ──
#  - ssh · http. 앞단 Nginx 가 80 을 받는다. 출발지 제한(22 는 운영자 IP)은 보안 그룹이 한다 — 1차 방어는 보안 그룹이고
#    firewalld 는 그것이 잘못 열렸을 때 한 번 더 막는 둘째 겹이다(설계서 3.3). 노드별 허용 표는 시스템 구성서 4장.
#  - 컨테이너 포트는 여기서 열지 않는다. Docker 가 게시한 포트는 firewalld 를 우회하므로(DNAT 이 입력 규칙보다 먼저 목적지를
#    바꾼다) 막는 것은 루프백 게시다 — 셋째 겹, 운영 Compose 가 127.0.0.1 로만 게시한다.
#
# ── NAT 를 겸한다 ──
#  사설 서브넷(DB-01 · DB-02)의 인터넷 출구다 — dnf · 이미지 수신(설계서 3.2). 사설 경로표의 0/0 이 이 노드의 ENI 를 가리킨다.
#  NIC 이 eth0 하나라 사설 서브넷에서 들어온 패킷이 같은 NIC(같은 public 영역)로 다시 나간다. 그래서
#   (1) 영역 안 전달(--add-forward, firewalld 1.x) — 같은 영역 안의 인터페이스 사이 전달을 허용한다. 기본은 막혀 있다.
#   (2) 마스커레이드(--add-masquerade) — 나가는 패킷의 출발지를 이 노드 주소로 바꾼다. 켜면 firewalld 가 IPv4 포워딩
#       (net.ipv4.ip_forward)도 켠다 — 끝에서 값을 확인한다.
#  영역 안 전달은 출발지를 가리지 않는다. 사설 두 서브넷 밖에서 전달 요청이 들어오지 못하게 하는 것은 보안 그룹이다
#  (rental-app: 사설 두 서브넷에서만 전체 허용, 인터넷에서는 80 · 22 만).
#  AWS 쪽 전제 — 소스/대상 확인(source/destination check)을 꺼야 자기 주소가 아닌 패킷을 받는다. APP-01 은 이미 껐다.
#  S3 는 게이트웨이 엔드포인트로 나가 이 노드를 거치지 않는다(설계서 3.2).
set -euo pipefail

ZONE=public
SERVICES=(ssh http)
# 기본 public 영역에 딸려 오는 것 중 이 노드가 쓰지 않는 것. 없으면 건너뛴다.
UNUSED=(cockpit dhcpv6-client)

fail() { echo "app-01.sh: $*" >&2; exit 1; }

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

firewall-cmd --permanent --zone="${ZONE}" --query-forward >/dev/null \
  || firewall-cmd --permanent --zone="${ZONE}" --add-forward
firewall-cmd --permanent --zone="${ZONE}" --query-masquerade >/dev/null \
  || firewall-cmd --permanent --zone="${ZONE}" --add-masquerade

firewall-cmd --reload

firewall-cmd --zone="${ZONE}" --list-all
[[ "$(sysctl -n net.ipv4.ip_forward)" == "1" ]] || fail "net.ipv4.ip_forward 가 1 이 아니다 — 마스커레이드가 반영되지 않았다"
echo "net.ipv4.ip_forward = 1"
