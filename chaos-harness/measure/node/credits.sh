#!/usr/bin/env bash
# CPU 크레딧 · EBS 버스트 스냅샷 — 서비스 노드 넷(APP-01 · APP-02 · DB-01 · DB-02)의 CloudWatch 최신 값(INF-06 #378).
#
#   bash credits.sh <출력 파일.json>
#
# 노드는 모두 unlimited 다(앱 노드 t3.medium · DB 노드 t3.small — 2026-10-05 describe-instance-credit-specifications. 2026-10-06 #447 에서 DB 노드를 t3.medium 으로 올렸다). 크레딧이 바닥나도 느려지지 않고 초과 사용(유료)으로 넘어가므로, 회차 전후 값으로
# 「이 회차가 버스트 위에서 돌았는가 · 초과 과금이 생겼는가」를 본다. 디스크(gp2 계열)는 BurstBalance 가 바닥나면 기본 IOPS 로 떨어진다.
#
#   cpu_credit_balance    CPUCreditBalance            쌓인 크레딧
#   cpu_surplus_balance   CPUSurplusCreditBalance     잔고가 0 일 때 미리 당겨 쓴 크레딧
#   cpu_surplus_charged   CPUSurplusCreditsCharged    과금으로 넘어간 크레딧
#   ebs_burst_balance     AWS/EBS BurstBalance(%)     루트 볼륨. gp3 · io 계열은 이 지표를 내지 않는다 — 그때는 null
#
# 최근 15분 · 5분 주기 · Average 의 가장 최근 점(get-metric-data 기본 정렬이 최신 먼저). 점이 없으면 null.
# 인스턴스는 Name 태그로 찾는다(status-check-alarms.sh 와 같이 Project=rental). 정지 · 미존재 노드는 값이 모두 null 이다.
# 읽기만 한다 — 아무것도 만들거나 바꾸지 않는다. 자격 증명은 실행하는 사람의 AWS CLI 프로필이다.
#   AWS_REGION   기본 ap-northeast-2(infra/aws/*.sh 와 같다)
#   NODE_NAMES   기본 "APP-01 APP-02 DB-01 DB-02"
set -euo pipefail

OUT=${1:-}
[ -n "$OUT" ] || { echo "사용법: bash credits.sh <출력 파일.json>" >&2; exit 2; }
command -v aws > /dev/null || { echo "aws CLI 가 없다 — 크레딧을 기록하지 못한다" >&2; exit 1; }

REGION=${AWS_REGION:-ap-northeast-2}
NODE_NAMES=${NODE_NAMES:-APP-01 APP-02 DB-01 DB-02}

# 프로세스 치환 안의 실패는 set -e 에 걸리지 않는다 — 변수로 받아 조회 실패에서 멈춘다.
# 루트 볼륨은 JMESPath 안에서 부모(RootDeviceName)를 참조할 수 없어 장치:볼륨 쌍을 모두 받아 아래에서 고른다
NAMES_CSV=$(echo "$NODE_NAMES" | tr ' ' ',')
LIST=$(aws ec2 describe-instances --region "$REGION" \
  --filters Name=tag:Project,Values=rental "Name=tag:Name,Values=$NAMES_CSV" \
            Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query "Reservations[].Instances[].[Tags[?Key=='Name']|[0].Value, InstanceId, RootDeviceName, join(',', BlockDeviceMappings[].join(':', [DeviceName, Ebs.VolumeId]))]" \
  --output text | tr -d '\r')   # Windows 의 AWS CLI 는 줄 끝에 CR 을 붙인다

END=$(date -u +%Y-%m-%dT%H:%M:%SZ)
START=$(date -u -d '-15 min' +%Y-%m-%dT%H:%M:%SZ)

num_or_null() { if [[ ${1:-} =~ ^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$ ]]; then printf '%s' "$1"; else printf 'null'; fi; }
str_or_null() { if [ -n "${1:-}" ] && [ "$1" != None ]; then printf '"%s"' "$1"; else printf 'null'; fi; }

metric() {  # <Id> <Namespace> <MetricName> <차원 이름> <차원 값>
  printf '{"Id":"%s","MetricStat":{"Metric":{"Namespace":"%s","MetricName":"%s","Dimensions":[{"Name":"%s","Value":"%s"}]},"Period":300,"Stat":"Average"},"ReturnData":true}' \
    "$1" "$2" "$3" "$4" "$5"
}

TMP="$OUT.tmp"
mkdir -p "$(dirname "$OUT")"
{
  printf '{\n  "ts": "%s",\n  "region": "%s",\n  "nodes": {\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" "$REGION"
  first=1
  for name in $NODE_NAMES; do
    id="" vol=""
    row=$(printf '%s\n' "$LIST" | awk -F'\t' -v n="$name" '$1 == n { print; exit }')
    if [ -n "$row" ]; then
      IFS=$'\t' read -r _ id root maps <<< "$row"
      IFS=',' read -r -a pairs <<< "$maps"
      for p in "${pairs[@]}"; do if [ "${p%%:*}" = "$root" ]; then vol=${p#*:}; fi; done
    fi
    bal="" sur="" chg="" burst=""
    if [ -n "$id" ]; then
      queries="[$(metric bal AWS/EC2 CPUCreditBalance InstanceId "$id"),$(metric sur AWS/EC2 CPUSurplusCreditBalance InstanceId "$id"),$(metric chg AWS/EC2 CPUSurplusCreditsCharged InstanceId "$id")"
      if [ -n "$vol" ]; then queries="$queries,$(metric burst AWS/EBS BurstBalance VolumeId "$vol")"; fi
      queries="$queries]"
      if res=$(aws cloudwatch get-metric-data --region "$REGION" --start-time "$START" --end-time "$END" \
                 --metric-data-queries "$queries" --query 'MetricDataResults[].[Id, Values[0]]' --output text | tr -d '\r'); then
        while IFS=$'\t' read -r k v; do
          case $k in bal) bal=$v ;; sur) sur=$v ;; chg) chg=$v ;; burst) burst=$v ;; esac
        done <<< "$res"
      else
        echo "!!! $name 지표 조회 실패 — 값을 null 로 남긴다" >&2
      fi
    else
      echo "!!! $name 인스턴스를 찾지 못했다 — 값을 null 로 남긴다" >&2
    fi
    [ "$first" -eq 1 ] || printf ',\n'
    first=0
    # 한 노드를 한 줄에 둔다 — pre-round.sh 의 경고가 줄 단위로 읽는다
    printf '    "%s": {"instance_id": %s, "volume_id": %s, "cpu_credit_balance": %s, "cpu_surplus_balance": %s, "cpu_surplus_charged": %s, "ebs_burst_balance": %s}' \
      "$name" "$(str_or_null "$id")" "$(str_or_null "$vol")" "$(num_or_null "$bal")" "$(num_or_null "$sur")" \
      "$(num_or_null "$chg")" "$(num_or_null "$burst")"
  done
  printf '\n  }\n}\n'
} > "$TMP"
mv -f "$TMP" "$OUT"
echo "크레딧 기록 — $OUT"
