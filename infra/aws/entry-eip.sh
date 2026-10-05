#!/usr/bin/env bash
# 입구 이중화(INF-01, #404) — 입구 고정 IP(Elastic IP). 운영 절차서 「입구 이중화 반영」. 운영자 PC 에서(멱등).
#
#   bash infra/aws/entry-eip.sh alloc    없으면 할당만 한다 — 주소가 정해진다. 입구는 아직 그대로다
#   bash infra/aws/entry-eip.sh attach   어디에도 붙어 있지 않으면 APP-01 에 붙인다 — **이 순간 입구 주소가 바뀐다**. 이미 붙어 있으면 그대로 둔다
#
# 둘로 나눈 이유 — 주소를 미리 알아야 바뀌기 전에 준비할 수 있다(운영 절차서 9.6): 카카오맵 JS 키의 사이트 도메인 등록 · SSH 설정 ·
# 두 노드의 /etc/rental/entry.env · 새 인증서 작업 설치. 인증서 자체는 붙인 뒤에만 받는다 — Let's Encrypt 의 http-01 확인이 그 주소로
# 오기 때문이다. 그래서 attach 직후 APP-01 에서 rental-tls.service 를 곧바로 부르고, 그 수십 초는 옛 주소의 인증서가 나간다.
#
# 출력하는 할당 ID · 주소를 두 앱 노드의 /etc/rental/entry.env(ENTRY_ALLOC_ID · ENTRY_IP)에 넣는다 — infra/os/entry/entry-watch.sh 머리 주석.
# **이미 붙어 있으면 옮기지 않는다** — 되찾지 않음(non-preemptive). 지금 입구가 APP-02 여도 이 스크립트는 APP-01 로 되돌리지 않는다.
# 처음 한 번만 APP-01 이 받는다(사용자 결정 — 평시 입구는 APP-01).
#
# 왜 고정 IP 인가 — 입구가 노드를 옮겨 가도 사용자가 보는 주소가 같아야 한다. 관리형 로드 밸런서는 도메인이 필요하고 존재하는 동안
# 과금되며 관리형 의존이다(인프라 기술 스택 1.1 — 사용자 결정 B안, #404). EC2 는 VRRP 로 VIP 를 옮길 수 없어(시스템 구성서 3장)
# 고정 IP 를 API 로 옮긴다.
# 요금 — 공인 IPv4 는 붙어 있든 아니든 시간당 $0.005(2024-02 부터 같은 값 — AWS 공개 가격, #404 계획 2026-10-05 확인). 노드를 꺼 둔 동안에도
# 고정 IP 는 과금된다 — 시스템 구성서 6장. 노드마다의 자동 공인 IP 도 켜 둔 동안 같은 단가다.
# 고정 IP 를 붙이면 그 노드의 자동 공인 IP 는 반납된다. 고정 IP 가 떨어져 나간 노드는 새 자동 공인 IP 를 받는다(AWS EC2 문서
# 「Public IPv4 addresses」 — ENI 가 하나일 때). 받기까지의 시간과 실제로 받는지는 **전환 리허설에서 확인한다(미확인)** — 그동안 그 노드와
# 그 AZ 사설 노드의 인터넷 출구가 끊긴다.
set -euo pipefail
REGION=${AWS_REGION:-ap-northeast-2}
q() { aws --region "$REGION" "$@" --output text | tr -d '\r'; }

MODE=${1:-}
case "$MODE" in alloc|attach) ;; *) echo "사용법: bash entry-eip.sh alloc | attach" >&2; exit 2 ;; esac

read -r ALLOC IP ASSOC_INST <<< "$(q ec2 describe-addresses --filters Name=tag:Project,Values=rental Name=tag:Name,Values=rental-entry \
  --query 'Addresses[0].[AllocationId,PublicIp,InstanceId]')"
if [[ "$ALLOC" != eipalloc-* ]]; then
  [ "$MODE" = alloc ] || { echo "!!! 입구 고정 IP 가 없다 — alloc 을 먼저" >&2; exit 1; }
  echo "입구 고정 IP 를 할당한다"
  read -r ALLOC IP <<< "$(q ec2 allocate-address --domain vpc \
    --tag-specifications 'ResourceType=elastic-ip,Tags=[{Key=Name,Value=rental-entry},{Key=Project,Value=rental}]' \
    --query '[AllocationId,PublicIp]')"
  ASSOC_INST=None
fi
case "$ALLOC" in eipalloc-*) ;; *) echo "!!! 할당 ID 를 얻지 못했다: $ALLOC" >&2; exit 1 ;; esac

if [ "$MODE" = alloc ]; then
  echo "할당만 했다 — 붙어 있는 곳: ${ASSOC_INST}. 준비를 마친 뒤 attach(운영 절차서 9.6)"
elif [[ "$ASSOC_INST" == i-* ]]; then
  NAME=$(q ec2 describe-instances --instance-ids "$ASSOC_INST" --query "Reservations[0].Instances[0].Tags[?Key=='Name'].Value | [0]")
  echo "이미 $NAME($ASSOC_INST)에 붙어 있다 — 옮기지 않는다(되찾지 않음)"
else
  A1=$(q ec2 describe-instances --filters Name=tag:Project,Values=rental Name=tag:Name,Values=APP-01 Name=instance-state-name,Values=running \
    --query 'Reservations[0].Instances[0].InstanceId')
  case "$A1" in i-*) ;; *) echo "!!! 실행 중인 APP-01 을 찾지 못했다 — 켠 뒤 다시(고정 IP 는 할당만 됐다: $ALLOC $IP)" >&2; exit 1 ;; esac
  echo "APP-01($A1)에 붙인다 — 이 순간 APP-01 의 자동 공인 IP 가 반납되고 입구 주소가 $IP 로 바뀐다"
  q ec2 associate-address --allocation-id "$ALLOC" --instance-id "$A1" --query AssociationId
fi

echo
echo "ENTRY_ALLOC_ID=$ALLOC"
echo "ENTRY_IP=$IP"
echo "확인: aws ec2 describe-addresses --region $REGION --allocation-ids $ALLOC --query 'Addresses[0].[PublicIp,InstanceId,PrivateIpAddress]' --output text"
