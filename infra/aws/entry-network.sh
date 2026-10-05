#!/usr/bin/env bash
# 입구 이중화(INF-01, #404) — 네트워크. 운영 절차서 「입구 이중화 반영」 1단계. 운영자 PC 에서 돌린다(멱등 — 있는 것은 건너뛴다).
#
#   bash infra/aws/entry-network.sh            바꾸고 끝에 상태를 출력한다
#   DRY_RUN=1 bash infra/aws/entry-network.sh  조회만 하고 무엇을 바꿀지 출력한다
#
# 하는 일 —
#   1. 2c 공개 서브넷 10.20.1.0/24 를 만들고 공개 경로표(2a 공개 서브넷의 것 — 0/0 → 인터넷 게이트웨이)에 붙인다.
#      APP-02 를 여기로 다시 만든다(운영 절차서) — 공인 IP 를 가지고 입구 고정 IP 를 받을 수 있어야 해서다.
#   2. 사설 경로표를 AZ 마다 나눈다. 2a · 2c 사설 서브넷이 한 경로표를 같이 쓰고 있으면 2c 용을 새로 만들어 같은 경로(0/0 → APP-01)를
#      먼저 넣고 붙인다 — 붙이는 순간 DB-02 의 경로가 바뀌지 않는다. 0/0 을 APP-02 로 돌리는 것은 entry-nat-c.sh(APP-02 를 만든 뒤).
#   3. S3 게이트웨이 엔드포인트를 두 사설 경로표 모두에 붙인다 — WAL · 백업 사본(DB 노드)이 NAT 를 거치지 않는 것은 그대로다
#      (서버 운영 기반 설계서 3.2). 2c 용 경로표가 새로 생기면 여기서 붙이지 않으면 DB-02 의 S3 전송이 NAT 로 돌아간다.
#
# 대역 10.20.1.0/24 를 고른 이유 — VPC 10.20.0.0/16 안에서 지금 쓰는 셋(10.20.0.0/24 공개 2a · 10.20.10.0/24 사설 2a · 10.20.20.0/24 사설 2c)과
# 겹치지 않고, 공개는 10.20.0.x ~ 10.20.9.x · 사설은 10.20.10.x 부터라는 지금의 나눔을 그대로 잇는다(공개 2a 의 바로 다음 /24).
# APP-02 의 새 사설 IP 10.20.1.10 은 APP-01(10.20.0.10)과 같은 호스트 자리다. AWS 가 서브넷마다 앞 넷(.0 ~ .3)과 마지막(.255)을 갖는다.
#
# 서브넷의 자동 공인 IP(MapPublicIpOnLaunch)는 켜지 않는다 — APP-02 를 만들 때 --associate-public-ip-address 로 준다(APP-01 과 같다).
# 요금 — 서브넷 · 경로표 · 게이트웨이 엔드포인트는 추가 요금이 없다(AWS VPC 요금 — 게이트웨이 엔드포인트 무료, 서버 운영 기반 설계서 3.2).
set -euo pipefail
REGION=${AWS_REGION:-ap-northeast-2}
VPC_CIDR=10.20.0.0/16
PUB_A=10.20.0.0/24
PRIV_A=10.20.10.0/24
PRIV_C=10.20.20.0/24
PUB_C=10.20.1.0/24
AZ_C=${REGION}c
DRY_RUN=${DRY_RUN:-0}

# Windows 의 AWS CLI 는 줄 끝에 CR 을 붙인다(stall-alarm.sh 와 같은 처리)
q() { aws --region "$REGION" "$@" --output text | tr -d '\r'; }
do_() { if [ "$DRY_RUN" = 1 ]; then echo "  (DRY_RUN) aws $*"; else aws --region "$REGION" "$@" --output text | tr -d '\r'; fi; }
must() { case "$2" in "$1"-*) ;; *) echo "!!! $3 — 찾은 값: '$2'" >&2; exit 1 ;; esac; }

VPC=$(q ec2 describe-vpcs --filters Name=cidr,Values=$VPC_CIDR Name=tag:Project,Values=rental --query 'Vpcs[0].VpcId')
must vpc "$VPC" "VPC($VPC_CIDR, Project=rental)를 찾지 못했다"
subnet_id() { q ec2 describe-subnets --filters Name=vpc-id,Values="$VPC" Name=cidr-block,Values="$1" --query 'Subnets[0].SubnetId'; }
# 서브넷이 명시적으로 붙은 경로표. 없으면 VPC 기본(main) 경로표를 쓰는 것이다
rtb_of() {
  local r
  r=$(q ec2 describe-route-tables --filters Name=association.subnet-id,Values="$1" --query 'RouteTables[0].RouteTableId')
  case "$r" in rtb-*) echo "$r" ;; *) q ec2 describe-route-tables --filters Name=vpc-id,Values="$VPC" Name=association.main,Values=true --query 'RouteTables[0].RouteTableId' ;; esac
}
# 서브넷의 명시적 연결 ID(경로표를 바꿔 붙일 때). 없으면 None
assoc_of() {
  q ec2 describe-route-tables --filters Name=association.subnet-id,Values="$1" \
    --query "RouteTables[0].Associations[?SubnetId=='$1'].RouteTableAssociationId | [0]"
}

S_PUB_A=$(subnet_id $PUB_A);  must subnet "$S_PUB_A" "공개 서브넷 $PUB_A 를 찾지 못했다"
S_PRIV_A=$(subnet_id $PRIV_A); must subnet "$S_PRIV_A" "사설 서브넷 $PRIV_A 를 찾지 못했다"
S_PRIV_C=$(subnet_id $PRIV_C); must subnet "$S_PRIV_C" "사설 서브넷 $PRIV_C 를 찾지 못했다"
RT_PUB=$(rtb_of "$S_PUB_A");   must rtb "$RT_PUB" "공개 경로표를 찾지 못했다"
echo "VPC $VPC · 공개 2a $S_PUB_A($RT_PUB) · 사설 2a $S_PRIV_A · 사설 2c $S_PRIV_C"

# ── 1. 2c 공개 서브넷 ──
S_PUB_C=$(subnet_id $PUB_C)
if [[ "$S_PUB_C" != subnet-* ]]; then
  echo "1. 2c 공개 서브넷 $PUB_C 를 만든다($AZ_C)"
  S_PUB_C=$(do_ ec2 create-subnet --vpc-id "$VPC" --cidr-block $PUB_C --availability-zone "$AZ_C" \
    --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=rental-public-c},{Key=Project,Value=rental}]" \
    --query Subnet.SubnetId)
else
  echo "1. 2c 공개 서브넷 있음 — $S_PUB_C"
fi
if [[ "$S_PUB_C" == subnet-* ]]; then
  if [ "$(rtb_of "$S_PUB_C")" = "$RT_PUB" ] && [[ "$(assoc_of "$S_PUB_C")" == rtbassoc-* ]]; then
    echo "   공개 경로표 $RT_PUB 에 붙어 있음"
  else
    echo "   공개 경로표 $RT_PUB 에 붙인다"
    do_ ec2 associate-route-table --route-table-id "$RT_PUB" --subnet-id "$S_PUB_C" --query AssociationId
  fi
fi

# ── 2. 사설 경로표를 AZ 마다 ──
RT_A=$(rtb_of "$S_PRIV_A"); must rtb "$RT_A" "2a 사설 경로표를 찾지 못했다"
RT_C=$(rtb_of "$S_PRIV_C"); must rtb "$RT_C" "2c 사설 경로표를 찾지 못했다"
if [ "$RT_A" = "$RT_C" ]; then
  echo "2. 두 사설 서브넷이 경로표 $RT_A 를 같이 쓴다 — 2c 용을 새로 만든다"
  NAT_ENI=$(q ec2 describe-route-tables --route-table-ids "$RT_A" --query "RouteTables[0].Routes[?DestinationCidrBlock=='0.0.0.0/0'].NetworkInterfaceId | [0]")
  must eni "$NAT_ENI" "2a 사설 경로표의 0/0 이 NAT ENI(APP-01)를 가리키지 않는다 — 손으로 본다"
  if [ "$DRY_RUN" = 1 ]; then
    echo "  (DRY_RUN) create-route-table · 0/0 → $NAT_ENI · S3 엔드포인트 · replace-route-table-association($S_PRIV_C)"
    RT_C=""
  else
    RT_C=$(q ec2 create-route-table --vpc-id "$VPC" \
      --tag-specifications "ResourceType=route-table,Tags=[{Key=Name,Value=rental-private-c},{Key=Project,Value=rental}]" \
      --query RouteTable.RouteTableId)
    must rtb "$RT_C" "2c 사설 경로표를 만들지 못했다"
    # 붙이기 전에 경로를 채운다 — 붙이는 순간 DB-02 의 출구 · S3 길이 지금과 같아야 한다
    q ec2 create-route --route-table-id "$RT_C" --destination-cidr-block 0.0.0.0/0 --network-interface-id "$NAT_ENI" --query Return
    NEW_RT_C=1
  fi
else
  echo "2. 사설 경로표가 이미 나뉘어 있음 — 2a $RT_A · 2c $RT_C"
fi

# ── 3. S3 게이트웨이 엔드포인트 — 두 사설 경로표 ──
VPCE=$(q ec2 describe-vpc-endpoints --filters Name=vpc-id,Values="$VPC" Name=service-name,Values="com.amazonaws.$REGION.s3" Name=vpc-endpoint-type,Values=Gateway \
  --query 'VpcEndpoints[0].VpcEndpointId')
must vpce "$VPCE" "S3 게이트웨이 엔드포인트를 찾지 못했다(운영 절차서 9.1 3노드 표 1 에서 만들었다)"
ATTACHED=$(q ec2 describe-vpc-endpoints --vpc-endpoint-ids "$VPCE" --query 'VpcEndpoints[0].RouteTableIds')
for rt in "$RT_A" ${RT_C:+"$RT_C"}; do
  if grep -qw -- "$rt" <<< "$ATTACHED"; then
    echo "3. S3 엔드포인트 $VPCE — $rt 에 붙어 있음"
  else
    echo "3. S3 엔드포인트 $VPCE 를 $rt 에 붙인다"
    do_ ec2 modify-vpc-endpoint --vpc-endpoint-id "$VPCE" --add-route-table-ids "$rt" --query Return
  fi
done

# 2 의 마지막 — 경로 · 엔드포인트를 채운 2c 용 경로표를 2c 사설 서브넷에 붙인다
if [ "${NEW_RT_C:-0}" = 1 ]; then
  OLD_ASSOC=$(assoc_of "$S_PRIV_C")
  if [[ "$OLD_ASSOC" == rtbassoc-* ]]; then
    echo "2. 2c 사설 서브넷을 $RT_C 로 바꿔 붙인다(연결 $OLD_ASSOC)"
    q ec2 replace-route-table-association --association-id "$OLD_ASSOC" --route-table-id "$RT_C" --query NewAssociationId
  else
    echo "2. 2c 사설 서브넷(기본 경로표를 쓰던)을 $RT_C 에 붙인다"
    q ec2 associate-route-table --route-table-id "$RT_C" --subnet-id "$S_PRIV_C" --query AssociationId
  fi
fi

echo
echo "── 확인 ──"
for s in "$S_PUB_A" "${S_PUB_C:-}" "$S_PRIV_A" "$S_PRIV_C"; do
  [[ "$s" == subnet-* ]] || continue
  rt=$(rtb_of "$s")
  printf '%s %s → %s · 0/0 %s\n' "$s" "$(q ec2 describe-subnets --subnet-ids "$s" --query 'Subnets[0].[CidrBlock,AvailabilityZone]')" "$rt" \
    "$(q ec2 describe-route-tables --route-table-ids "$rt" --query "RouteTables[0].Routes[?DestinationCidrBlock=='0.0.0.0/0'].[GatewayId,NetworkInterfaceId] | [0]")"
done
echo "S3 엔드포인트 $VPCE 경로표: $(q ec2 describe-vpc-endpoints --vpc-endpoint-ids "$VPCE" --query 'VpcEndpoints[0].RouteTableIds')"
