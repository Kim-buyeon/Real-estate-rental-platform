"""컨테이너 메모리 — 슬롯 · DB · 앞단 컨테이너의 사용 최대 · 상한 · 비율 · 상한 OOM · 재시작. [4.6] [5.1] [7.4] [7.8].

입력: results/<회차>/containers/<노드>.jsonl.gz (node-sampler.sh cloop — 간격마다 한 줄, gzip 멤버 하나)
    {"ts": <유닉스 초>, "c": [{"svc": <Compose 서비스>, "id": <앞 12자>, "cur": <바이트>, "max": <바이트|null>, "oom": <누적|null>}, …]}
슬롯 메모리 상한(운영 Compose mem_limit 680m)과 OOM 이 이 시험의 핵심 위험이라 cAdvisor 없이 cgroup 을 직접 읽은 값이다.
"""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

from .prom import _chunks
from .util import canon_node, rnd

# Compose 서비스 → summary 자리. 슬롯은 resources.slots["<노드>/<서비스>"], DB 는 resources.postgres[<노드>]
SLOTS = ("app-1", "app-2")
DB_SERVICES = ("postgres", "postgres-standby")
INGRESS = "nginx"


def read(path: Path):
    """[(ts, [컨테이너 …])] — 반쯤 쓰인 마지막 멤버 · 깨진 줄은 버린다."""
    out = []
    for _, text in _chunks(path):
        for line in text.splitlines():
            try:
                d = json.loads(line)
                out.append((float(d["ts"]), d.get("c") or []))
            except (ValueError, KeyError, TypeError):
                continue
    return out


def node_summary(rows, win):
    """서비스별 — 구간(워밍업 포함 회차 전체) 안 최대 사용 · 상한 · 비율 · oom_kill 증가 · id 가 바뀐 횟수(재시작)."""
    acc = defaultdict(lambda: {"cur": [], "max": None, "oom": [], "ids": []})
    for ts, cs in rows:
        if not win.in_round(ts):
            continue
        for c in cs:
            a = acc[c.get("svc") or "?"]
            if isinstance(c.get("cur"), (int, float)):
                a["cur"].append(c["cur"])
            if isinstance(c.get("max"), (int, float)):
                a["max"] = c["max"]
            if isinstance(c.get("oom"), (int, float)):
                a["oom"].append((c.get("id"), c["oom"]))
            if c.get("id") and (not a["ids"] or a["ids"][-1] != c["id"]):
                a["ids"].append(c["id"])
    out = {}
    for svc, a in sorted(acc.items()):
        if not a["cur"]:
            continue
        mx = max(a["cur"])
        # oom_kill 은 컨테이너(cgroup, id)마다 따로 센다 — id 별 (마지막 − 처음) 합. 상한 OOM 으로 컨테이너가 죽으면 그 cgroup 이
        # 다음 표본 전에 사라져 증가가 안 보일 수 있다 — 그래서 재시작(id 변화)을 함께 본다
        by_id = defaultdict(list)
        for i, v in a["oom"]:
            by_id[i].append(v)
        oom = sum(v[-1] - v[0] for v in by_id.values()) if by_id else None
        out[svc] = {"max_bytes": mx, "limit_bytes": a["max"],
                    "max_pct": rnd(100.0 * mx / a["max"], 1) if a["max"] else None,
                    "oom_kills": oom, "restarts": max(len(a["ids"]) - 1, 0)}
    return out


def analyze(cdir: Path, win):
    if not cdir.exists():
        return None
    out = {}
    for p in sorted(cdir.glob("*.jsonl*")):
        node = canon_node(p.name.split(".jsonl")[0])
        s = node_summary(read(p), win)
        if s:
            out[node] = s
    return out or None


def attach(s):
    """resources 의 빈 칸(container_memory_*)을 채운다 — 슬롯 · DB · 앞단. 상한 OOM 은 app_totals 에 합."""
    c = s.get("containers")
    res = s.get("resources")
    if not c or res is None:
        return
    slot_oom = []
    for node, svcs in c.items():
        for svc, v in svcs.items():
            if svc in SLOTS:
                sl = res.setdefault("slots", {}).setdefault(f"{node}/{svc}", {})
                sl["container_memory_max_bytes"] = v["max_bytes"]
                sl["container_memory_limit_bytes"] = v["limit_bytes"]
                sl["container_memory_max_pct"] = v["max_pct"]
                sl["container_oom_kills"] = v["oom_kills"]
                if v["oom_kills"] is not None:
                    slot_oom.append(v["oom_kills"])
            elif svc in DB_SERVICES:
                pg = res.setdefault("postgres", {}).setdefault(node, {})
                pg["container_memory_max_bytes"] = v["max_bytes"]
                pg["container_memory_limit_bytes"] = v["limit_bytes"]
                pg["container_oom_kills"] = v["oom_kills"]
            elif svc == INGRESS:
                res["ingress_container"] = dict(v, node=node)
    if slot_oom:
        res.setdefault("app_totals", {})["container_oom_kills"] = sum(slot_oom)
