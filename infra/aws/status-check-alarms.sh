#!/usr/bin/env bash
# EC2 상태 검사 경보 — 노드가 스스로 회복하지 못할 때 사람 없이 되살린다(#258). 운영 절차서 2장.
#
#   ALARM_EMAIL=<받을 주소> bash infra/aws/status-check-alarms.sh     Project=rental 서비스 노드 전부에 건다(멱등 — 같은 이름은 덮어쓴다)
#
# 경보가 울리면 자동 동작과 함께 SNS 주제 rental-node-alarms 로 메일을 보낸다 — 재부팅이 몇 분 만에 끝나면
# Grafana 「수집 끊김」(노드 통째 정지는 약 10분)이 울리기 전에 끝나 사람이 모를 수 있다. 메일 주소는 저장소에 두지 않고
# 실행할 때 받는다. 처음 구독하면 AWS 가 확인 메일을 보내고, 그 링크를 눌러야 받기 시작한다(운영 절차서 2장).
#
# 2026-09-26 부하 시험 T2 에서 APP-01 의 OS 가 멈춰 인스턴스 상태 검사가 impaired 로 남았고, 사람이 재부팅할 때까지
# 약 70분 서비스가 멈췄다(#257). 노드 안의 워치독은 OS 가 멈추면 함께 멈추므로 밖에서 보는 수단이 필요하다.
#
#   인스턴스 상태 검사 실패(StatusCheckFailed_Instance) 3분 연속 → 재부팅     OS · 커널 쪽 정지
#   시스템 상태 검사 실패(StatusCheckFailed_System)     2분 연속 → recover     AWS 하드웨어 · 호스트 쪽. 같은 인스턴스를 다른 호스트로
#
# 관리형 의존(인프라 기술 스택 1.1)의 예외다 — 인스턴스를 켜고 끄는 것 자체가 EC2 기능이고, 이 경보는 그 제어를 조건부로 부른다.
# 자체 호스팅으로 옮기면 하이퍼바이저의 감시가 이 자리를 대신한다.
#
# 정지(stopped)해 둔 동안은 지표가 없다 — TreatMissingData=notBreaching 이라 경보가 울리지 않는다(작업할 때만 켠다).
# 부하 생성기처럼 Name 이 LOAD 로 시작하는 노드는 빼고, 서비스 노드(APP-01 · APP-02 · DB-01 · DB-02)에만 건다.
# 요금 — 표준 경보 10개까지 무료(노드 넷 × 둘 = 8개). 넘으면 경보당 월 과금 — 시스템 구성서 6장.
set -euo pipefail
REGION=${AWS_REGION:-ap-northeast-2}
: "${ALARM_EMAIL:?ALARM_EMAIL 이 필요하다 — 경보 메일을 받을 주소}"
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)

# 프로세스 치환 안의 실패는 set -e 에 걸리지 않는다 — 변수로 받아 조회 실패에서 멈춘다
LIST=$(aws ec2 describe-instances --region "$REGION" \
  --filters Name=tag:Project,Values=rental Name=instance-state-name,Values=running,stopped \
  --query "Reservations[].Instances[?!(Tags[?Key=='Name' && starts_with(Value, 'LOAD')])].[InstanceId, Tags[?Key=='Name']|[0].Value]" \
  --output text | tr -d '\r')   # Windows 의 AWS CLI 는 줄 끝에 CR 을 붙인다 — 경보 이름에 섞이면 거부된다
[ -n "$LIST" ] || { echo "서비스 노드를 찾지 못했다 — 경보를 만들지 않는다" >&2; exit 1; }
mapfile -t NODES <<< "$LIST"

# 알림 주제 — create-topic 은 같은 이름이면 기존 ARN 을 돌려준다(멱등). subscribe 도 같은 주소면 같은 구독이다
TOPIC=$(aws sns create-topic --region "$REGION" --name rental-node-alarms --tags Key=Project,Value=rental \
  --query TopicArn --output text | tr -d '\r')
aws sns subscribe --region "$REGION" --topic-arn "$TOPIC" --protocol email \
  --notification-endpoint "$ALARM_EMAIL" --query SubscriptionArn --output text

for row in "${NODES[@]}"; do
  read -r ID NAME <<< "$row"
  aws cloudwatch put-metric-alarm --region "$REGION" \
    --alarm-name "rental-${NAME}-instance-check-reboot" \
    --alarm-description "${NAME} 인스턴스 상태 검사 3분 연속 실패 → 재부팅(#258)" \
    --namespace AWS/EC2 --metric-name StatusCheckFailed_Instance --dimensions Name=InstanceId,Value="$ID" \
    --statistic Maximum --period 60 --evaluation-periods 3 --datapoints-to-alarm 3 \
    --threshold 1 --comparison-operator GreaterThanOrEqualToThreshold --treat-missing-data notBreaching \
    --alarm-actions "arn:aws:automate:${REGION}:ec2:reboot" "$TOPIC" --ok-actions "$TOPIC" \
    --tags Key=Project,Value=rental
  aws cloudwatch put-metric-alarm --region "$REGION" \
    --alarm-name "rental-${NAME}-system-check-recover" \
    --alarm-description "${NAME} 시스템 상태 검사 2분 연속 실패 → recover(#258)" \
    --namespace AWS/EC2 --metric-name StatusCheckFailed_System --dimensions Name=InstanceId,Value="$ID" \
    --statistic Maximum --period 60 --evaluation-periods 2 --datapoints-to-alarm 2 \
    --threshold 1 --comparison-operator GreaterThanOrEqualToThreshold --treat-missing-data notBreaching \
    --alarm-actions "arn:aws:automate:${REGION}:ec2:recover" "$TOPIC" --ok-actions "$TOPIC" \
    --tags Key=Project,Value=rental
  echo "$NAME ($ID) 경보 두 개"
done
echo "계정 $ACCOUNT · 확인: aws cloudwatch describe-alarms --region $REGION --alarm-name-prefix rental- --query 'MetricAlarms[].[AlarmName,StateValue]' --output table"
