#!/usr/bin/env bash
# 입구 이중화(INF-01, #404) — 보안 그룹. 운영 절차서 「입구 이중화 반영」. 운영자 PC 에서(멱등 — 있는 규칙은 건너뛴다).
#
#   bash infra/aws/entry-sg.sh      규칙을 더하고, APP-01 에 rental-app-node 그룹을 더 단다
#
# **두 앱 노드가 같은 두 그룹(rental-app · rental-app-node)을 단다.** 입구가 어느 노드로 옮겨 가도 같은 규칙으로 받기 위해서다 —
#   rental-app       밖에서 443 · 80 · 22(운영자 IP), 사설 두 서브넷에서 전체(NAT) — 그대로 둔다. 새 APP-02 는 만들 때 단다(운영 절차서)
#   rental-app-node  앱 노드끼리 · DB-02 에서 오는 것. 출발지를 그룹으로 좁힌다(CIDR 규칙 없음 — 서버 운영 기반 설계서 3.3)
# 더하는 규칙 — 출발지가 rental-app 이면 두 앱 노드 모두를 뜻한다(두 노드가 그 그룹을 단다):
#   rental-app-node ← rental-app   22(배포 · 인증서 사본 — 이제 두 방향) · 8081-8082(상대 노드 nginx → 슬롯, 있던 규칙) · 6380(Redis 복제 ·
#                                   슬롯) · 26379(Sentinel)
#   rental-app-node ← rental-db    6380 · 26379(DB-02 의 Sentinel 이 두 앱 노드의 Redis · Sentinel 에 붙는다)
#   rental-db       ← rental-app   26379(슬롯 · 앱 노드 Sentinel → DB-02 의 Sentinel)
# 입구 감시의 상대 HTTPS(443)는 rental-app 의 443(전체 허용)이 이미 받는다.
# DB 노드끼리(rental-db ← rental-db 5432)는 그대로. DB-01 에는 Sentinel 이 없지만 그룹 단위라 26379 가 열린다 — 듣는 것이 없고 firewalld 도
# 열지 않는다(infra/os/firewalld/db.sh 는 SENTINEL=1 일 때만).
set -euo pipefail
REGION=${AWS_REGION:-ap-northeast-2}
q() { aws --region "$REGION" "$@" --output text | tr -d '\r'; }

VPC=$(q ec2 describe-vpcs --filters Name=cidr,Values=10.20.0.0/16 Name=tag:Project,Values=rental --query 'Vpcs[0].VpcId')
case "$VPC" in vpc-*) ;; *) echo "!!! VPC 를 찾지 못했다" >&2; exit 1 ;; esac
sg() { q ec2 describe-security-groups --filters Name=vpc-id,Values="$VPC" Name=group-name,Values="$1" --query 'SecurityGroups[0].GroupId'; }
APP=$(sg rental-app); NODE=$(sg rental-app-node); DB=$(sg rental-db)
for g in "$APP" "$NODE" "$DB"; do case "$g" in sg-*) ;; *) echo "!!! 보안 그룹을 찾지 못했다(rental-app · rental-app-node · rental-db)" >&2; exit 1 ;; esac; done
echo "rental-app $APP · rental-app-node $NODE · rental-db $DB"

# allow <대상 그룹> <포트 또는 범위> <출발 그룹> <설명>
allow() {
  local from=${2%-*} to=${2#*-} out
  if out=$(aws --region "$REGION" ec2 authorize-security-group-ingress --group-id "$1" \
      --ip-permissions "IpProtocol=tcp,FromPort=$from,ToPort=$to,UserIdGroupPairs=[{GroupId=$3,Description=$4}]" \
      --tag-specifications 'ResourceType=security-group-rule,Tags=[{Key=Project,Value=rental}]' --query 'SecurityGroupRules[0].SecurityGroupRuleId' --output text 2>&1); then
    echo "  더함   $1 tcp $2 ← $3 ($4) $(tr -d '\r' <<< "$out")"
  elif grep -q InvalidPermission.Duplicate <<< "$out"; then
    echo "  있음   $1 tcp $2 ← $3"
  else
    echo "!!! $1 tcp $2 ← $3 — $out" >&2; return 1
  fi
}
allow "$NODE" 22          "$APP"  "deploy-and-tls-copy-404"
allow "$NODE" 8081-8082   "$APP"  "peer-nginx-to-slots"
allow "$NODE" 6380        "$APP"  "redis-tls-replication-404"
allow "$NODE" 26379       "$APP"  "redis-sentinel-404"
allow "$NODE" 6380        "$DB"   "db02-sentinel-to-redis-404"
allow "$NODE" 26379       "$DB"   "db02-sentinel-404"
allow "$DB"   26379       "$APP"  "redis-sentinel-on-db02-404"

# APP-01 에 rental-app-node 를 더 단다(modify-instance-attribute --groups 는 목록 전체를 바꾸므로 지금 목록에 더해 준다)
# shellcheck disable=SC2016 # 역따옴표는 JMESPath 문자열이다(셸이 풀지 않는다)
read -r A1 A1_GROUPS <<< "$(q ec2 describe-instances --filters Name=tag:Project,Values=rental Name=tag:Name,Values=APP-01 Name=instance-state-name,Values=running,stopped \
  --query 'Reservations[0].Instances[0].[InstanceId, join(`,`, SecurityGroups[].GroupId)]')"
case "$A1" in i-*) ;; *) echo "!!! APP-01 을 찾지 못했다" >&2; exit 1 ;; esac
if grep -qw -- "$NODE" <<< "${A1_GROUPS//,/ }"; then
  echo "APP-01($A1) 그룹: $A1_GROUPS — rental-app-node 있음"
else
  # shellcheck disable=SC2086 # 그룹 ID 목록을 낱말로 나눈다
  aws --region "$REGION" ec2 modify-instance-attribute --instance-id "$A1" --groups ${A1_GROUPS//,/ } "$NODE"
  echo "APP-01($A1) 에 rental-app-node 를 더 달았다"
fi
echo "확인: aws ec2 describe-security-group-rules --region $REGION --filters Name=group-id,Values=$NODE,$DB --query 'SecurityGroupRules[?!IsEgress].[GroupId,FromPort,ToPort,ReferencedGroupInfo.GroupId]' --output table"
