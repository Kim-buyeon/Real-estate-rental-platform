#!/usr/bin/env bash
# 입구 이중화(INF-01, #404) — 2c 사설 서브넷(DB-02)의 인터넷 출구를 APP-02 로. 운영 절차서 「입구 이중화 반영」. 운영자 PC 에서(멱등).
#
#   bash infra/aws/entry-nat-c.sh          APP-02 의 소스/대상 확인을 끄고 2c 사설 경로표의 0/0 을 APP-02 ENI 로
#   bash infra/aws/entry-nat-c.sh back     되돌리기 — 2c 사설 경로표의 0/0 을 APP-01 ENI 로(APP-02 가 NAT 를 못 할 때)
#
# 전제 — entry-network.sh 로 사설 경로표가 AZ 마다 나뉘어 있고, APP-02 가 2c 공개 서브넷에 공인 IP 를 갖고 서 있으며
# 그 노드의 firewalld 가 영역 안 전달 · 마스커레이드를 켰다(infra/os/firewalld/app-node.sh — ip_forward 1). 그 전에 돌리면 DB-02 의 출구가 끊긴다.
# NAT 를 AZ 마다 두는 이유 — 한 AZ 의 앱 노드가 멈춰도 다른 AZ 사설 노드의 출구는 남는다(서버 운영 기반 설계서 3.2).
# 소스/대상 확인을 끄는 이유 — 자기 주소가 아닌 패킷을 받아 넘겨야 NAT 가 된다(AWS VPC 문서 「NAT instances」). APP-01 은 3노드 때 껐다.
set -euo pipefail
REGION=${AWS_REGION:-ap-northeast-2}
PRIV_A=10.20.10.0/24
PRIV_C=10.20.20.0/24
MODE=${1:-}

q() { aws --region "$REGION" "$@" --output text | tr -d '\r'; }
must() { case "$2" in "$1"-*) ;; *) echo "!!! $3 — 찾은 값: '$2'" >&2; exit 1 ;; esac; }
inst() { q ec2 describe-instances --filters Name=tag:Project,Values=rental Name=tag:Name,Values="$1" Name=instance-state-name,Values=running,stopped \
  --query 'Reservations[0].Instances[0].[InstanceId,NetworkInterfaces[0].NetworkInterfaceId,SourceDestCheck,VpcId]'; }

read -r A1 A1_ENI _ VPC <<< "$(inst APP-01)"
must i "$A1" "APP-01 을 찾지 못했다"
read -r A2 A2_ENI A2_SDC _ <<< "$(inst APP-02)"
must i "$A2" "APP-02 를 찾지 못했다"
subnet_id() { q ec2 describe-subnets --filters Name=vpc-id,Values="$VPC" Name=cidr-block,Values="$1" --query 'Subnets[0].SubnetId'; }
rtb_explicit() { q ec2 describe-route-tables --filters Name=association.subnet-id,Values="$1" --query 'RouteTables[0].RouteTableId'; }
RT_A=$(rtb_explicit "$(subnet_id $PRIV_A)")
RT_C=$(rtb_explicit "$(subnet_id $PRIV_C)")
must rtb "$RT_C" "2c 사설 서브넷에 붙은 경로표가 없다 — entry-network.sh 를 먼저"
[ "$RT_A" != "$RT_C" ] || { echo "!!! 두 사설 서브넷이 경로표 $RT_C 를 같이 쓴다 — entry-network.sh 를 먼저(2a 의 출구까지 바뀐다)" >&2; exit 1; }

case "$MODE" in
  back) TARGET=$A1_ENI; WHO=APP-01 ;;
  "")   TARGET=$A2_ENI; WHO=APP-02
        if [ "$A2_SDC" = True ]; then
          echo "APP-02($A2) 소스/대상 확인을 끈다"
          q ec2 modify-instance-attribute --instance-id "$A2" --no-source-dest-check
        else
          echo "APP-02($A2) 소스/대상 확인 이미 꺼짐"
        fi ;;
  *) echo "사용법: bash entry-nat-c.sh [back]" >&2; exit 2 ;;
esac

CUR=$(q ec2 describe-route-tables --route-table-ids "$RT_C" --query "RouteTables[0].Routes[?DestinationCidrBlock=='0.0.0.0/0'].NetworkInterfaceId | [0]")
if [ "$CUR" = "$TARGET" ]; then
  echo "2c 사설 경로표 $RT_C 의 0/0 이 이미 $WHO($TARGET)다"
elif [[ "$CUR" == eni-* ]]; then
  echo "2c 사설 경로표 $RT_C 의 0/0 을 $CUR → $WHO($TARGET) 로 바꾼다"
  q ec2 replace-route --route-table-id "$RT_C" --destination-cidr-block 0.0.0.0/0 --network-interface-id "$TARGET"
else
  echo "2c 사설 경로표 $RT_C 에 0/0 → $WHO($TARGET) 를 만든다"
  q ec2 create-route --route-table-id "$RT_C" --destination-cidr-block 0.0.0.0/0 --network-interface-id "$TARGET" --query Return
fi
echo "확인: $(q ec2 describe-route-tables --route-table-ids "$RT_C" --query "RouteTables[0].Routes[?DestinationCidrBlock=='0.0.0.0/0'].[NetworkInterfaceId,State] | [0]") · 2a $RT_A 0/0 $(q ec2 describe-route-tables --route-table-ids "$RT_A" --query "RouteTables[0].Routes[?DestinationCidrBlock=='0.0.0.0/0'].NetworkInterfaceId | [0]")(APP-01 $A1_ENI 여야 한다)"
echo "DB-02 에서: curl -sS -o /dev/null -w '%{http_code}\\n' https://download.docker.com/ 가 200 · dnf makecache 가 성공하면 출구가 선다"
