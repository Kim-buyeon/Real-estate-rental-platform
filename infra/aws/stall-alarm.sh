#!/usr/bin/env bash
# APP-01 「살아 있지만 멈춘」 상태의 자동 재부팅(#266). 운영 절차서 2장.
#
#   bash infra/aws/stall-alarm.sh     APP-01 에 건다(멱등 — 같은 이름은 덮어쓴다). 알림 주제는 status-check-alarms.sh 가 만든 것
#
# 상태 검사 경보(status-check-alarms.sh)는 OS 가 하이퍼바이저에 응답하지 않을 때만 울린다. 2026-09-26 19:18 ~ 20:23 APP-01 은
# SSH · HTTP 무응답이었는데 상태 검사는 ok 였다(CPU 55% 고정, 메모리 회수에 매달린 것으로 추정) — 약 65분 중단(#258).
# 그 멈춤을 가리는 신호가 NetworkOut 이다(5분 합, 기본 모니터링). 같은 날 실측:
#
#   평소 유휴                       약 780 ~ 960 KB
#   사고 ① 17:28 ~ 17:53(OS 정지)   약 330 ~ 350 KB → 이후 0
#   사고 ② 19:18 ~ 20:23(검사 ok)   약 365 ~ 455 KB
#
# 멈춰도 0 이 되지 않는 것은 커널의 NAT 중계(다른 노드의 지표 · 로그 전송)가 계속 흐르고 APP-01 자신의 송신만 끊기기 때문으로 본다.
# 그래서 기준은 550 KB 미만 3구간(15분) 연속이다 — 두 사고를 모두 잡고 평소 유휴의 최저와 약 1.4배 떨어져 있다. 표본은 하루치라
# 잠정값이다. 오탐이 나면 재부팅 한 번(API 복귀 약 90초)이 대가다.
#
# APP-01 에만 건다. 다른 노드는 출구가 APP-01 NAT 라, APP-01 이 멈추면 그 노드들의 송신도 함께 줄어 신호가 섞인다.
# CPU 는 신호가 되지 못한다 — 사고 ② 에서 55% 로 고정돼 부하 상태와 구분되지 않았다.
# 정지(stopped)해 둔 동안은 지표가 없다 — notBreaching 이라 울리지 않는다. 켠 직후 첫 5분 구간은 부팅 중이라 적을 수 있으나
# 3구간 연속이 필요해 울리지 않는다.
# 요금 — 표준 경보 10개까지 무료. 상태 검사 경보 8개 + 이것 1개 = 9개(시스템 구성서 6장).
set -euo pipefail
REGION=${AWS_REGION:-ap-northeast-2}
THRESHOLD_BYTES=550000

ID=$(aws ec2 describe-instances --region "$REGION" \
  --filters Name=tag:Project,Values=rental Name=tag:Name,Values=APP-01 Name=instance-state-name,Values=running,stopped \
  --query 'Reservations[0].Instances[0].InstanceId' --output text | tr -d '\r')   # Windows 의 AWS CLI 는 줄 끝에 CR 을 붙인다
case "$ID" in i-*) ;; *) echo "APP-01 을 찾지 못했다 — 경보를 만들지 않는다" >&2; exit 1 ;; esac

TOPIC=$(aws sns list-topics --region "$REGION" --query "Topics[?ends_with(TopicArn, ':rental-node-alarms')].TopicArn | [0]" \
  --output text | tr -d '\r')
case "$TOPIC" in arn:*) ;; *) echo "알림 주제 rental-node-alarms 가 없다 — status-check-alarms.sh 를 먼저 돌린다" >&2; exit 1 ;; esac

aws cloudwatch put-metric-alarm --region "$REGION" \
  --alarm-name "rental-APP-01-stall-reboot" \
  --alarm-description "APP-01 NetworkOut 5분 합 ${THRESHOLD_BYTES} 바이트 미만 3구간 연속 → 재부팅(#266). 상태 검사가 못 잡는 멈춤" \
  --namespace AWS/EC2 --metric-name NetworkOut --dimensions Name=InstanceId,Value="$ID" \
  --statistic Sum --period 300 --evaluation-periods 3 --datapoints-to-alarm 3 \
  --threshold "$THRESHOLD_BYTES" --comparison-operator LessThanThreshold --treat-missing-data notBreaching \
  --alarm-actions "arn:aws:automate:${REGION}:ec2:reboot" "$TOPIC" --ok-actions "$TOPIC" \
  --tags Key=Project,Value=rental
echo "APP-01 ($ID) 멈춤 경보"
