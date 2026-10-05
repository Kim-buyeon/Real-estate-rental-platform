#!/usr/bin/env bash
# 앱 노드(APP-02) 호스트 방화벽 — 서버 운영 기반 설계서 3.2 · 3.3 · 8장(firewalld 행). INF-01.
# 노드 준비의 firewalld 단계(운영 절차서 9.1)에서 root 로 한 번 돌린다. 여러 번 돌려도 결과가 같다(이미 있는 것은 건너뛴다).
#
#   sudo bash app-node.sh
#
# **입구 이중화(#404)부터 APP-01 과 같은 규칙이다 — app-01.sh 를 그대로 부른다.** 규칙과 그 이유는 그 파일 머리 주석이 갖는다.
#   - ssh · http · https — APP-02 도 공개 서브넷(2c)에 서서 입구 고정 IP(Elastic IP)가 옮겨 오면 443 · 80 을 받는다
#   - 26379/tcp — Redis Sentinel
#   - 영역 안 전달 · 마스커레이드 — 2c 사설 서브넷(DB-02)의 NAT 를 겸한다
# 전에는(#255) 사설 서브넷의 앱 슬롯 노드라 ssh 만 열었다. 두 노드의 규칙을 한 파일에 두어 한쪽만 고쳐지는 일을 막는다.
set -euo pipefail
exec bash "$(dirname "$(readlink -f "$0")")/app-01.sh" "$@"
