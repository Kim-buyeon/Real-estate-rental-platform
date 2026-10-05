#!/usr/bin/env bash
# 입구 이중화(INF-01, #404) — 앱 노드 인스턴스 역할에 입구 고정 IP 를 옮길 권한. 운영 절차서 「입구 이중화 반영」. 운영자 PC 에서(멱등).
#
#   bash infra/aws/entry-iam.sh     entry-eip.sh 뒤, 새 APP-02 를 만든 뒤에 돌린다(두 노드의 주 ENI ID 를 정책에 적는다).
#                                   APP-02(또는 APP-01)를 다시 만들면 ENI 가 바뀌므로 다시 돌린다
#
# 권한 — 입구 감시(infra/os/entry/entry-watch.sh)가 부르는 것만:
#   ec2:AssociateAddress   자원 둘을 함께 좁힌다 — 그 고정 IP 하나(태그 Name=rental-entry 의 할당 ID)의 elastic-ip ARN 과 두 앱 노드의
#                          **주 ENI** ARN(network-interface/eni-…)뿐이다. 감시기는 --network-interface-id 로 부른다(인스턴스 ID 로 부르지 않는다).
#                          그래서 이 역할로는 그 고정 IP 를 두 앱 노드의 ENI 말고 다른 곳에 붙일 수 없고, 다른 고정 IP 는 아예 옮길 수 없다.
#                          ENI 는 entry-nat-c.sh 와 같은 방식으로 찾는다(Name 태그의 인스턴스 → NetworkInterfaces[0]).
#                          **전환 리허설에서 확인할 것 둘** — ① ENI 로 부를 때 인스턴스 ARN 도 평가되는가(거부되면 두 인스턴스 ARN 을 더한다)
#                          ② --allow-reassociation 으로 다른 노드에서 떼어 오는 것이 이 동작 하나로 되는가(AWS CLI 문서 associate-address —
#                          거부되면 ec2:DisassociateAddress 를 같은 범위로 더한다)
#   ec2:DescribeAddresses  읽기 — 자원 단위로 좁힐 수 없는 동작이다(Service Authorization Reference — 자원 유형 없음). 감시 스크립트는
#                          IMDS 로 판정해 부르지 않고, 노드에서 손으로 확인할 때(운영 절차서) 쓴다
# 역할 — APP-01 은 이미 있는 rental-app-config(설정 사본, infra/backup/aws/README.md)에 인라인 정책을 더한다. APP-02 는 역할이 없었으므로
# rental-app-node 를 새로 만든다(신뢰 정책은 infra/backup/aws/role-trust.json — EC2 만). APP-02 에는 S3 권한을 주지 않는다 — 설정 사본은 APP-01 만 한다.
# IMDSv2 required · 홉 1 이 먼저여야 한다(서버 운영 기반 설계서 3.3 「역할을 붙이기 전에 잠근다」) — 새 APP-02 는 그렇게 만든다(운영 절차서).
set -euo pipefail
REGION=${AWS_REGION:-ap-northeast-2}
HERE=$(cd "$(dirname "$0")" && pwd)
TRUST="$HERE/../backup/aws/role-trust.json"
POLICY_NAME=rental-entry-eip
q() { aws --region "$REGION" "$@" --output text | tr -d '\r'; }
must() { case "$2" in "$1"*) ;; *) echo "!!! $3 — 찾은 값: '$2'" >&2; exit 1 ;; esac; }

ACCOUNT=$(q sts get-caller-identity --query Account)
ALLOC=$(q ec2 describe-addresses --filters Name=tag:Project,Values=rental Name=tag:Name,Values=rental-entry --query 'Addresses[0].AllocationId')
must eipalloc- "$ALLOC" "입구 고정 IP 가 없다 — entry-eip.sh 를 먼저"
# 인스턴스 ID 와 주 ENI — entry-nat-c.sh 의 inst() 와 같은 조회
inst() { q ec2 describe-instances --filters Name=tag:Project,Values=rental Name=tag:Name,Values="$1" Name=instance-state-name,Values=running,stopped \
  --query 'Reservations[0].Instances[0].[InstanceId,NetworkInterfaces[0].NetworkInterfaceId]'; }
read -r A1 A1_ENI <<< "$(inst APP-01)"; must i- "$A1" "APP-01 을 찾지 못했다"; must eni- "$A1_ENI" "APP-01 의 ENI 를 찾지 못했다"
read -r A2 A2_ENI <<< "$(inst APP-02)"; must i- "$A2" "APP-02 를 찾지 못했다"; must eni- "$A2_ENI" "APP-02 의 ENI 를 찾지 못했다"

ARN="arn:aws:ec2:$REGION:$ACCOUNT"
DOC=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "MoveEntryEipBetweenAppNodes",
      "Effect": "Allow",
      "Action": "ec2:AssociateAddress",
      "Resource": [
        "$ARN:elastic-ip/$ALLOC",
        "$ARN:network-interface/$A1_ENI",
        "$ARN:network-interface/$A2_ENI"
      ]
    },
    {
      "Sid": "ReadAddresses",
      "Effect": "Allow",
      "Action": "ec2:DescribeAddresses",
      "Resource": "*"
    }
  ]
}
EOF
)
echo "정책 $POLICY_NAME — 고정 IP $ALLOC · APP-01 $A1($A1_ENI) · APP-02 $A2($A2_ENI)"

# APP-01 — 이미 붙은 역할(rental-app-config)
aws iam get-role --role-name rental-app-config > /dev/null || { echo "!!! 역할 rental-app-config 가 없다 — infra/backup/aws/README.md 8" >&2; exit 1; }
aws iam put-role-policy --role-name rental-app-config --policy-name "$POLICY_NAME" --policy-document "$DOC"
echo "rental-app-config 에 $POLICY_NAME"

# APP-02 — 역할 · 인스턴스 프로필 rental-app-node
if ! aws iam get-role --role-name rental-app-node > /dev/null 2>&1; then
  aws iam create-role --role-name rental-app-node --assume-role-policy-document "file://$TRUST" --tags Key=Project,Value=rental --query Role.Arn --output text
fi
aws iam put-role-policy --role-name rental-app-node --policy-name "$POLICY_NAME" --policy-document "$DOC"
if ! aws iam get-instance-profile --instance-profile-name rental-app-node > /dev/null 2>&1; then
  aws iam create-instance-profile --instance-profile-name rental-app-node --tags Key=Project,Value=rental --query InstanceProfile.Arn --output text
  aws iam add-role-to-instance-profile --instance-profile-name rental-app-node --role-name rental-app-node
fi
echo "rental-app-node 에 $POLICY_NAME"

# 인스턴스 프로필 연결 — 이미 붙어 있으면 그대로(다른 프로필이면 알린다)
for pair in "$A1 rental-app-config" "$A2 rental-app-node"; do
  read -r id want <<< "$pair"
  cur=$(q ec2 describe-iam-instance-profile-associations --filters Name=instance-id,Values="$id" Name=state,Values=associated \
    --query 'IamInstanceProfileAssociations[0].IamInstanceProfile.Arn')
  case "$cur" in
    */"$want") echo "$id — $want 붙어 있음" ;;
    arn:*) echo "!!! $id 에 다른 프로필($cur)이 붙어 있다 — 손으로 본다" >&2 ;;
    *) echo "$id 에 $want 를 붙인다"
       q ec2 associate-iam-instance-profile --instance-id "$id" --iam-instance-profile Name="$want" --query IamInstanceProfileAssociation.State ;;
  esac
done
echo "노드에서 확인: aws ec2 describe-addresses --region $REGION --allocation-ids $ALLOC (성공) · 역할 자격 증명이 노드에 닿기까지 수십 초 걸릴 수 있다"
