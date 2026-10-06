"""보고서 — summary.json + narrative.json → 양식 HTML(Jinja2) → PDF(Edge 헤드리스).

숫자 칸은 집계에서 채우고, 판단이 필요한 칸은 results/narrative.json 에서 가져온다. 없으면 비워 둔다(지어내지 않는다).
측정해야 하는데 값이 없는 칸은 「미측정」.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

from jinja2 import ChainableUndefined, Environment, FileSystemLoader, Undefined

from . import compare
from .prom import EBS_BASELINE, ebs_type
from .util import RESULTS_DIR, REPORT_SRC_DIR, KST, read_json, rnd, fmt_kst

MISSING = "미측정"
# 앱 노드 t3.medium — MemTotal 3,831 MiB(#394 반영 뒤 실측). 회차에 MemTotal 이 없을 때만 쓴다 — 있으면 실측을 적는다
# (#394 전 회차의 앱 노드는 2 GiB 였다)
APP_MEM_TXT = "4 GiB (MemTotal 3,831 MiB) × 2"


# ---------------------------------------------------------------- 필터

def _none(x):
    return x is None or isinstance(x, Undefined)


def f_v(x, digits=1, unit=""):
    """측정값 — 없으면 「미측정」."""
    if _none(x) or x == "":
        return MISSING
    if isinstance(x, bool):
        return "예" if x else "아니오"
    if isinstance(x, (int, float)):
        if float(x).is_integer() and abs(x) >= 100:
            s = f"{int(x):,}"
        else:
            s = f"{x:,.{digits}f}"
        return s + unit
    return f"{x}{unit}"


def f_n(x):
    """서술 — 없으면 빈 칸."""
    return "" if _none(x) else x


def f_mb(x, digits=0):
    return MISSING if _none(x) else f"{x / 1048576:,.{digits}f} MB"


def f_ox(x):
    if _none(x):
        return MISSING
    return "있음" if x else "없음"


def f_short(x, n=60):
    if _none(x):
        return ""
    x = re.sub(r"\s+", " ", str(x))
    return x if len(x) <= n else x[:n] + "…"


def t_given(x):
    return not _none(x) and x != ""


# ---------------------------------------------------------------- 표 만들기

def _get(d, *path):
    for p in path:
        if d is None:
            return None
        d = d.get(p) if isinstance(d, dict) else None
    return d


def _solo(x, name=""):
    """단독 회차 — round.json kind 가 solo 이거나 이름이 U 로 시작(10/4 이름)."""
    return bool(x) and (_get(x, "meta", "kind") == "solo" or str(x.get("round", name)).upper().startswith("U"))


def _delta(a, b, lower_better=True, thr=None):
    """전 → 후 변화 문자열."""
    if a is None or b is None or not isinstance(a, (int, float)) or not isinstance(b, (int, float)):
        return ""
    if a == 0:
        return "변화없음" if b == 0 else f"{b - a:+.2f}"
    d = (b - a) / abs(a) * 100
    tag = ""
    if thr is not None:
        tag = " 변화없음" if abs(d) < thr else (" 개선" if (d < 0) == lower_better else " 악화")
    return f"{d:+.1f}%{tag}"


def _slot_key(x):
    return re.sub(r"\D", "", x or "")


def node_pair(s, key, sub="mean"):
    n = _get(s, "resources", "nodes", key, "cpu_pct", sub)
    return n


def comparability(s, b, nar):
    """[1.3] — 자동으로 아는 칸은 채우고 나머지는 narrative.comparability 에서."""
    nc = nar.get("comparability") or {}

    def cred(x, k):
        if not x or not x.get("credits"):
            return None
        vals = [v[k]["pre"] for n, v in x["credits"]["nodes"].items() if v[k]["pre"] is not None]
        return min(vals) if vals else None

    def means(x):
        m = (x or {}).get("meta") or {}
        parts = []
        if m.get("tracing_ratio") is not None:
            parts.append(f"추적 {m['tracing_ratio']}")
        if m.get("profiler"):
            parts.append(f"프로파일러 {m['profiler']}")
        if m.get("auto_explain_ms") is not None:
            parts.append(f"auto_explain {m['auto_explain_ms']} ms")
        return " · ".join(parts) or None

    auto = {
        "데이터 규모": lambda x: _get(x, "meta", "data_scale"),
        "커밋": lambda x: _get(x, "meta", "commit"),
        "부하 생성기": lambda x: _get(x, "meta", "generator"),
        "EBS 크레딧 (출발)": lambda x: cred(x, "ebs_burst_balance"),
        "CPU 크레딧 (출발)": lambda x: cred(x, "cpu_credit_balance"),
        "측정 수단": means,
        # 회차 길이(통계 구간, 워밍업 제외) — 시간 제약으로 줄였으면 narrative.comparability["회차 길이"].impact 에 사유를 적는다
        "회차 길이": lambda x: (f"{_get(x, 'window', 'duration_sec'):.0f}초 (워밍업 {_get(x, 'window', 'warmup_sec') or 0}초 제외)"
                             if _get(x, "window", "duration_sec") is not None else None),
    }
    items = [("-", "데이터 규모"), ("DB", "plan_cache_mode"), ("DB", "random_page_cost"), ("DB", "병렬 워커 상한"),
             ("DB", "인덱스 (마이그레이션)"), ("앱", "커밋"), ("앱", "슬롯 메모리 상한"), ("앱", "누수 감지"), ("경계", "읽기 분산"),
             ("-", "부하 생성기"), ("OS", "EBS 크레딧 (출발)"), ("OS", "CPU 크레딧 (출발)"), ("-", "측정 수단"), ("-", "회차 길이")]
    rows = []
    for layer, item in items:
        n = nc.get(item) or {}
        fa = auto.get(item)
        base = n.get("base") if n.get("base") is not None else (fa(b) if fa and b else None)
        cur = n.get("cur") if n.get("cur") is not None else (fa(s) if fa else None)
        rows.append({"layer": layer, "item": item, "base": base, "cur": cur, "impact": n.get("impact")})
    for item, n in nc.items():
        if item not in dict((i, l) for l, i in items):
            rows.append({"layer": n.get("layer", ""), "item": item, "base": n.get("base"), "cur": n.get("cur"), "impact": n.get("impact")})
    return rows


def solo_limits(summaries):
    """단독 계단 회차(U*, steps.json) → 엔드포인트 키별 단독 한계 — 그 회차에서 가장 많이 부른 경로의 한계 단계 실제 RPS.
    포화에 닿지 않았으면 마지막 단계 값에 「이상」을 붙인다(reached False)."""
    out = {}
    for name, x in summaries:
        if not _solo(x, name):
            continue
        st = x.get("steps") or {}
        labels = (x.get("jtl") or {}).get("labels") or {}
        if st.get("basis") != "steps.json" or not labels or not st.get("limit"):
            continue
        route = max(labels.values(), key=lambda v: v["count"]).get("route")
        if route:
            out[route] = {"round": x["round"], "limit_rps": st["limit"]["actual_rps"], "reached": st["limit"]["reached"]}
    return out


def signal_rows(s, nar, solo=None):
    ne = nar.get("endpoints") or {}
    solo = solo or {}
    rows = []
    for e in s.get("endpoints") or []:
        n = ne.get(e["label"]) or {}
        if n.get("single_limit_tps") is None and e.get("route") in solo:
            u = solo[e["route"]]
            n = dict(n, single_limit_tps=u["limit_rps"], single_limit_note=f"{u['round']}{'' if u['reached'] else ' · 포화 미도달(이상)'}")
        target = n.get("target_p95_ms")
        hr = n.get("headroom")
        p95 = e["p95"]
        fail = (p95 is not None and p95 > (target or 500)) or (e["rate_5xx"] or 0) > 1.0
        if p95 is None:
            color, cls = "회색", "lg-n"
        elif fail:
            color, cls = "빨강", "lg-r"
        elif hr is None:
            color, cls = "통과 · 헤드룸 미측정", "lg-n"
        elif hr >= 3:
            color, cls = "초록", "lg-g"
        else:
            color, cls = "노랑", "lg-y"
        if n.get("color") in ("빨강", "노랑", "초록"):  # 혼합 p95 밖의 근거(단독 한계 불합격 등)로 사람이 정한 색
            color, cls = n["color"], {"빨강": "lg-r", "노랑": "lg-y", "초록": "lg-g"}[n["color"]]
        rows.append(dict(e, target=target if target is not None else "500 (단일 기준)", single_limit=n.get("single_limit_tps"),
                         single_limit_note=n.get("single_limit_note"),
                         headroom=hr, color=color, cls=cls,
                         p95_note=("" if e["count"] >= 100 else " (표본 부족)")))
    return rows


def db_rows(s, b, thr):
    """[4.6] — 노드별 전/후."""
    def nd(x, node, *p):
        return _get(x, "resources", "nodes", node, *p)

    def pg(x, node, k):
        return _get(x, "resources", "postgres", node, k)

    def pool(x, name):
        return _get(x, "resources", "app_totals", "pools", name, "pending_max")

    def wait(x, node, k):
        return _get(x, "db", "wait", node, "grouped", k, "aas")

    spec = [
        ("노드 CPU", lambda x, n: nd(x, n, "cpu_pct", "mean"), lambda v: f_v(v, 1, " %"), True, lambda x, n: nd(x, n, "cpu_pct", "max")),
        ("iowait · steal", lambda x, n: nd(x, n, "cpu_modes_pct", "iowait", "mean"), None, True, None),
        ("버퍼 적중률", lambda x, n: pg(x, n, "hit_ratio_pct"), lambda v: f_v(v, 2, " %"), False, None),
        ("디스크 읽기 (blk_read_time)", lambda x, n: pg(x, n, "blk_read_time_delta"), lambda v: f_v(v, 0, " ms"), True, None),
        ("디스크 IOPS · 처리량 (기준선 대비)", lambda x, n: nd(x, n, "disk_iops", "max"), None, True, None),
        ("임시 파일 (work_mem 초과)", lambda x, n: pg(x, n, "temp_bytes_delta"), lambda v: f_mb(v, 1), True, None),
        ("활성 커넥션 (최대)", lambda x, n: _get(x, "resources", "postgres", n, "connections_by_state_max", "active"), lambda v: f_v(v, 0), True, None),
        ("커넥션 대기 (최대)", lambda x, n: pool(x, "primary" if n == "db-01" else "replica"), lambda v: f_v(v, 0), True, None),
        ("오래 열린 트랜잭션 (최대)", lambda x, n: pg(x, n, "max_tx_duration_sec"), lambda v: f_v(v, 2, " s"), True, None),
        ("락 대기 / 데드락", lambda x, n: wait(x, n, "Lock"), None, True, None),
        ("복제 지연 (최대)", lambda x, n: pg(x, n, "replication_lag_max_sec"), lambda v: f_v(v, 3, " s"), True, None),
        ("WAL 생성량 (회차)", lambda x, n: pg(x, n, "wal_bytes_delta"), lambda v: f_mb(v, 1), True, None),
        ("체크포인트 (회차)", lambda x, n: pg(x, n, "checkpoints_delta"), lambda v: f_v(v, 1), True, None),
        ("컨테이너 메모리 (최대)", lambda x, n: pg(x, n, "container_memory_max_bytes"), lambda v: f_mb(v), True, None),
    ]
    rows = []
    for name, fn, fmt, lb, fmax in spec:
        cells = []
        chg = []
        for node in ("db-01", "db-02"):
            for x in (b, s):
                v = fn(x, node) if x else None
                txt = (fmt or (lambda y: f_v(y, 2)))(v) if x else ""
                if name == "노드 CPU" and x and fmax:
                    txt += f" (최대 {f_v(fmax(x, node), 1)})"
                if name == "iowait · steal" and x:
                    txt = f"{f_v(v, 2)} / {f_v(nd(x, node, 'cpu_modes_pct', 'steal', 'mean'), 2)} %"
                if name == "디스크 IOPS · 처리량 (기준선 대비)" and x:
                    bps = nd(x, node, "disk_bps", "max")
                    txt = f"{f_v(v, 0)} IOPS · {f_mb(bps, 1)}/s"
                if name == "락 대기 / 데드락" and x:
                    txt = f"Lock {f_v(v, 3)} 세션 / 데드락 {f_v(pg(x, node, 'deadlocks_delta'), 0)}"
                cells.append(txt)
            if b:
                d = _delta(fn(b, node), fn(s, node), lb, thr)
                if d:
                    chg.append(f"{node.upper()} {d}")
        rows.append({"name": name, "cells": cells, "change": " · ".join(chg)})
    return rows


def wait_rows(s, b):
    out = []
    for g, label in (("CPU", "CPU (빈 값)"), ("IO", "IO"), ("Lock", "Lock"), ("LWLock", "LWLock"), ("Client", "Client"), ("기타", "기타")):
        def cell(x):
            if not x or not _get(x, "db", "wait"):
                return "" if not x else MISSING
            return " / ".join(f"{f_v(_get(x, 'db', 'wait', n, 'grouped', g, 'aas'), 2)}" for n in ("db-01", "db-02"))

        def share(x, n):
            return _get(x, "db", "wait", n, "grouped", g, "share_pct")
        ch = ""
        if b:
            parts = []
            for n in ("db-01", "db-02"):
                a, c = share(b, n), share(s, n)
                if a is not None and c is not None:
                    parts.append(f"{n.upper()} {a:.0f}→{c:.0f}%")
            ch = " · ".join(parts)
        else:
            ch = " · ".join(f"{n.upper()} {f_v(share(s, n), 0, '%')}" for n in ("db-01", "db-02"))
        out.append({"label": label, "base": cell(b), "cur": cell(s), "change": ch})
    return out


def app_rows(s, b, thr):
    def at(x, k):
        return _get(x, "resources", "app_totals", k)

    def app_cpu(x):
        vals = [(_get(x, "resources", "nodes", n, "cpu_pct", "mean"), _get(x, "resources", "nodes", n, "cpu_pct", "max")) for n in ("app-01", "app-02")]
        return " · ".join(f"{n.upper()} {f_v(a, 1)}% (최대 {f_v(m, 1)})" for n, (a, m) in zip(("app-01", "app-02"), vals))

    def steal_load(x):
        return " · ".join(f"{n.upper()} steal {f_v(_get(x, 'resources', 'nodes', n, 'cpu_modes_pct', 'steal', 'mean'), 2)}% · load÷vCPU {f_v(_get(x, 'resources', 'nodes', n, 'load_per_vcpu', 'max'), 2)}"
                          for n in ("app-01", "app-02"))

    def threads(x):
        return f"{f_v(at(x, 'tomcat_busy_max'), 0)} / {f_v(_get(x, 'resources', 'slots', next(iter(_get(x, 'resources', 'slots') or {'-': 0})), 'tomcat_max'), 0)} (슬롯당)"

    def container(x):
        """슬롯 컨테이너 메모리 최대 / 상한 — 슬롯 중 상한 대비 비율이 가장 큰 것(cgroup, containers.py)."""
        vals = [v for v in (_get(x, "resources", "slots") or {}).values() if v.get("container_memory_max_bytes")]
        if not vals:
            return MISSING
        v = max(vals, key=lambda d: d.get("container_memory_max_pct") or 0)
        lim = v.get("container_memory_limit_bytes")
        return f"{f_mb(v['container_memory_max_bytes'])} / {f_mb(lim) if lim else '상한 없음'}" + (f" ({v['container_memory_max_pct']}%)" if v.get("container_memory_max_pct") is not None else "")

    def execq(x):
        vals = [v.get("executor_queued_max") for v in (_get(x, "resources", "slots") or {}).values() if v.get("executor_queued_max")]
        if not vals:
            return MISSING
        m = {}
        for d in vals:
            for k, v in d.items():
                if v is not None:
                    m[k] = max(m.get(k, 0), v)
        return " · ".join(f"{k} {f_v(v, 0)}" for k, v in m.items())

    spec = [
        ("앱 노드 CPU", app_cpu, None),
        ("앱 노드 steal · load÷vCPU", steal_load, None),
        ("요청 처리 스레드 (최대 / 상한)", threads, "tomcat_busy_max"),
        ("스레드 큐 대기 발생 여부", lambda x: ("■ 예 (바쁜 스레드가 상한에 닿음)" if at(x, "tomcat_queue_suspected") else "□ 아니오"), None),
        ("힙 사용 (최대)", lambda x: f_mb(at(x, "heap_used_max_bytes")), "heap_used_max_bytes"),
        ("힙 밖 사용 (최대)", lambda x: f_mb(at(x, "nonheap_used_max_bytes")), "nonheap_used_max_bytes"),
        ("GC 일시정지 (초당 합)", lambda x: f_v(at(x, "gc_pause_sec_per_sec_max"), 4, " s/s"), "gc_pause_sec_per_sec_max"),
        ("GC 횟수 (분당)", lambda x: f_v(at(x, "gc_count_per_min_max"), 1), "gc_count_per_min_max"),
        ("컨테이너 메모리 (최대 / 상한)", container, None),
        ("활성 스레드 수", lambda x: f_v(at(x, "threads_live_max"), 0), "threads_live_max"),
        ("열린 파일 디스크립터", lambda x: f_v(at(x, "open_files_max"), 0), "open_files_max"),
        ("비동기 · 스케줄러 큐 (W6)", execq, None),
        ("서버 쪽 TPS ÷ 도구 쪽 TPS", lambda x: f_v(at(x, "server_over_tool"), 3), "server_over_tool"),
        ("요청당 힙 할당", lambda x: f_v((at(x, "alloc_bytes_per_request") or 0) / 1024 if at(x, "alloc_bytes_per_request") is not None else None, 1, " KB"), "alloc_bytes_per_request"),
    ]
    rows = []
    for name, fn, key in spec:
        rows.append({"name": name, "base": fn(b) if b else "", "cur": fn(s),
                     "change": _delta(at(b, key), at(s, key), True, thr) if b and key else ""})
    return rows


def slot_rows(s):
    tr = _get(s, "traces", "slots") or {}
    tmap = {_slot_key(k): v for k, v in tr.items()}
    rows = []
    for key, v in sorted((_get(s, "resources", "slots") or {}).items()):
        t = tmap.get(_slot_key(key)) or {}
        rows.append({"slot": key, "share": v.get("request_share_pct"), "p95": t.get("p95_ms"),
                     "heap": _get(v, "heap_used", "max"), "restarts": v.get("restarts"),
                     "cmem": v.get("container_memory_max_bytes"), "cmem_pct": v.get("container_memory_max_pct"),
                     "coom": v.get("container_oom_kills")})
    if not rows and tr:
        tot = sum(v["requests"] for v in tr.values())
        rows = [{"slot": k, "share": rnd(100.0 * v["requests"] / tot, 1), "p95": v["p95_ms"], "heap": None, "restarts": None} for k, v in tr.items()]
    return rows


def profiler_view(x):
    """CPU 프로파일 파일들의 표본 가중 합 — [5.2]."""
    prof = (x or {}).get("profiler") or {}
    cpu = {k: v for k, v in prof.items() if "alloc" not in k}
    if not cpu:
        return None
    tot = sum(v["samples"] for v in cpu.values())
    cats = {}
    for c in ("bcrypt", "leak_detection", "jit", "code_conversion", "serialization"):
        vals = [(v["categories_pct"].get(c), v["samples"]) for v in cpu.values()]
        if all(a is None for a, _ in vals):
            cats[c] = None
        else:
            cats[c] = rnd(sum((a or 0) * n for a, n in vals) / tot, 1) if tot else None
    top = {}
    for v in cpu.values():
        for m in v["top_self"]:
            top[m["method"]] = top.get(m["method"], 0) + m["samples"]
    return {"samples": tot, "cats": cats, "jit": cats.get("jit"),
            "top": [{"method": k, "pct": rnd(100.0 * c / tot, 1)} for k, c in sorted(top.items(), key=lambda x: -x[1])[:5]]}


def headroom_rows(s):
    """[7.4] — 양식의 고정 행 순서대로 최대값 · 헤드룸."""
    R = lambda *p: _get(s, "resources", *p)
    nodes = R("nodes") or {}
    at = R("app_totals") or {}

    def mx(keys, *p):
        vals = [_get(nodes, k, *p) for k in keys]
        vals = [v for v in vals if v is not None]
        return max(vals) if vals else None

    def hr(limit, peak):
        return rnd(100.0 * (limit - peak) / limit, 1) if limit and peak is not None else None

    apps, dbs = ("app-01", "app-02"), ("db-01", "db-02")
    rows = []

    def add(layer, name, limit, peak_txt, h=None):
        rows.append({"layer": layer, "name": name, "limit": limit, "peak": peak_txt, "headroom": h})

    def procs(keys):
        """O2 — 실행 대기 · IO 에 막힌 프로세스 수(최대). 둘 다 없으면 빈 글."""
        r, b_ = mx(keys, "procs_running", "max"), mx(keys, "procs_blocked", "max")
        return "" if r is None and b_ is None else f" · 실행 대기 {f_v(r, 0)} · IO 막힘 {f_v(b_, 0)}"

    a_cpu = mx(apps, "cpu_pct", "max")
    add("OS", "앱노드 CPU", "2 vCPU × 2", f_v(a_cpu, 1, " %"), hr(100, a_cpu))
    add("OS", "앱노드 steal · load÷vCPU", "-", f"steal {f_v(mx(apps, 'cpu_modes_pct', 'steal', 'max'), 1)}% · load {f_v(mx(apps, 'load_per_vcpu', 'max'), 2)}" + procs(apps), "-")
    avail = [(_get(nodes, k, "mem_available_min_bytes"), _get(nodes, k, "mem_total_bytes")) for k in apps]
    avail = [(a, t) for a, t in avail if a is not None and t]
    pf = mx(apps, "pgmajfault_per_sec", "max")
    pf_txt = f" · 주요 페이지 폴트 최대 {f_v(pf, 1)}/s" if pf is not None else ""
    if avail:
        a, t = min(avail, key=lambda x: x[0] / x[1])
        add("OS", "앱노드 가용 메모리", f"MemTotal {f_mb(t)} × {len(avail)}", f"최소 가용 {f_mb(a)}{pf_txt}", rnd(100.0 * a / t, 1))
    else:
        add("OS", "앱노드 가용 메모리", APP_MEM_TXT, MISSING + pf_txt, None)
    d_cpu = mx(dbs, "cpu_pct", "max")
    add("OS", "DB노드 CPU", "2 vCPU", f_v(d_cpu, 1, " %"), hr(100, d_cpu))
    add("OS", "DB노드 steal · iowait", "-", f"steal {f_v(mx(dbs, 'cpu_modes_pct', 'steal', 'max'), 1)}% · iowait {f_v(mx(dbs, 'cpu_modes_pct', 'iowait', 'max'), 1)}%" + procs(dbs), "-")
    # EBS 기준선은 인스턴스 유형마다 다르다 — DB 노드(t3.small)와 앱 노드를 따로 잰다. 앱 노드 유형은 회차 MemTotal 로 고른다
    # (prom.ebs_type — 두 앱 노드 중 작은 쪽. #394 전 회차는 t3.small)
    for keys, is_db, who in ((dbs, True, "DB노드"), (apps, False, "앱노드")):
        mts = [m for m in (_get(nodes, k, "mem_total_bytes") for k in keys) if m]
        e_type, guess = ebs_type(is_db, min(mts) if mts else None)
        e_iops, e_bps = EBS_BASELINE[e_type]
        e_type += " (추정)" if guess else ""
        iops = mx(keys, "disk_iops", "max")
        aw = mx(keys, "disk_await_ms", "all")
        aw_txt = f" · 요청당 대기 {f_v(aw, 2, ' ms')} (구간 최대 {f_v(mx(keys, 'disk_await_ms', 'max_interval'), 2, ' ms')})" if aw is not None else ""
        add("OS", f"{who} 디스크 IOPS (EBS 기준선)", f"{e_iops:,.0f} ({e_type})", f_v(iops, 0) + aw_txt, hr(e_iops, iops))
        bps = mx(keys, "disk_bps", "max")
        add("OS", f"{who} 디스크 처리량 (EBS 기준선)", f"{e_bps / 1e6:g} MB/s ({e_type})", f_v(bps / 1e6 if bps is not None else None, 2, " MB/s"),
            hr(e_bps, bps))
    cr = s.get("credits") or {}
    add("OS", "EBS 버스트 크레딧", "100%", f"회차 뒤 최소 {f_v(cr.get('ebs_post_min'), 1, '%')}", "-")
    cpuc = [v["cpu_credit_balance"]["post"] for v in (cr.get("nodes") or {}).values() if v["cpu_credit_balance"]["post"] is not None]
    add("OS", "CPU 크레딧", "", f"회차 뒤 최소 {f_v(min(cpuc) if cpuc else None, 1)}", "비용 항목")
    rx = mx(list(nodes), "net_bps_max", "rx")
    tx = mx(list(nodes), "net_bps_max", "tx")
    # O9 — 물리 장치 오류 · 버림(회차 증가 합)과 TCP 재전송(노드 중 최대 비율)
    errs = [v for k in nodes for v in (_get(nodes, k, "net_errors") or {}).values() if v is not None]
    rtx = mx(list(nodes), "tcp_retrans", "ratio_pct")
    o9 = (f" · 오류 · 버림 {f_v(sum(errs), 0)} · 재전송 {f_v(rtx, 3, '%')}" if errs or rtx is not None else "")
    add("OS", "네트워크", "", f"수신 {f_v(rx / 1e6 if rx is not None else None, 2)} · 송신 {f_v(tx / 1e6 if tx is not None else None, 2)} MB/s" + o9, None)
    lo = [_get(nodes, k, "listen_overflows_delta") for k in nodes]
    lo = [v for v in lo if v is not None]
    est = mx(list(nodes), "tcp_estab", "max")
    add("OS", "TCP 대기열 넘침 · TIME_WAIT", "0", f"넘침 {f_v(sum(lo) if lo else None, 0)} · TIME_WAIT 최대 {f_v(mx(list(nodes), 'time_wait_max'), 0)}"
        + (f" · ESTAB 최대 {f_v(est, 0)}" if est is not None else ""), "-")
    ct = _get(nodes, "app-01", "conntrack")
    add("OS", "conntrack (APP-01)", f_v((ct or {}).get("limit"), 0) if ct else "65,536", f_v((ct or {}).get("max"), 0),
        hr((ct or {}).get("limit"), (ct or {}).get("max")) if ct else None)
    oom = [_get(nodes, k, "oom_kills_delta") for k in nodes]
    oom = [v for v in oom if v is not None]
    add("OS", "OOM 강제 종료", "0", f_v(sum(oom) if oom else None, 0), "-")
    # 컨테이너 메모리 — cgroup 직접(containers.py). 상한은 그 컨테이너의 memory.max(운영 Compose mem_limit), 헤드룸은 비율이 가장 큰 것
    cm = [v for v in (R("slots") or {}).values() if v.get("container_memory_max_bytes")]
    if cm:
        v = max(cm, key=lambda d: d.get("container_memory_max_pct") or 0)
        lim = v.get("container_memory_limit_bytes")
        add("앱", "슬롯 컨테이너 메모리", f"{f_mb(lim)} × {len(cm)}" if lim else "768 MiB × 4", f_mb(v["container_memory_max_bytes"]),
            hr(lim, v["container_memory_max_bytes"]) if lim else None)
    else:
        add("앱", "슬롯 컨테이너 메모리", "768 MiB × 4", MISSING, None)
    add("앱", "요청 처리 스레드", f_v(at.get("tomcat_max_total"), 0) + " (합)" if at.get("tomcat_max_total") else "200 (합)",
        f"동시 최대 합 {f_v(at.get('tomcat_busy_sum_max'), 0)}", hr(at.get("tomcat_max_total"), at.get("tomcat_busy_sum_max")))
    hmax = [v.get("heap_max_bytes") for v in (R("slots") or {}).values() if v.get("heap_max_bytes")]
    add("앱", "힙", f_mb(max(hmax)) + " (슬롯당)" if hmax else "", f_mb(at.get("heap_used_max_bytes")), hr(max(hmax) if hmax else None, at.get("heap_used_max_bytes")))
    for pool, label in (("primary", "기본 커넥션 풀"), ("replica", "읽기용 커넥션 풀")):
        p = (at.get("pools") or {}).get(pool) or {}
        add("경계", label, f"{f_v(p.get('max_per_slot'), 0)} × {p.get('slots', 0)}" if p else "",
            f"활성 최대 {f_v(p.get('active_max'), 0)} · 대기 최대 {f_v(p.get('pending_max'), 0)}" if p else MISSING,
            hr(p.get("max_per_slot"), p.get("active_max")) if p else None)
    pgs = R("postgres") or {}
    nb = [(v.get("numbackends_max"), v.get("max_connections")) for v in pgs.values() if v.get("numbackends_max") is not None]
    if nb:
        peak, lim = max(nb)
        add("DB", "최대 커넥션", f_v(lim, 0), f_v(peak, 0), hr(lim, peak))
    else:
        add("DB", "최대 커넥션", "", MISSING, None)
    dcm = [v for v in pgs.values() if v.get("container_memory_max_bytes")]
    if dcm:
        v = max(dcm, key=lambda d: (d["container_memory_max_bytes"] / d["container_memory_limit_bytes"]) if d.get("container_memory_limit_bytes") else 0)
        lim = v.get("container_memory_limit_bytes")
        add("DB", "컨테이너 메모리", f_mb(lim) if lim else "", f_mb(v["container_memory_max_bytes"]), hr(lim, v["container_memory_max_bytes"]) if lim else None)
    else:
        add("DB", "컨테이너 메모리", "", MISSING, None)
    add("입구", "앞단 요청 상한", "", MISSING, None)
    ig = R("ingress_container") or {}
    add("입구", "앞단 컨테이너 메모리", f_mb(ig["limit_bytes"]) if ig.get("limit_bytes") else "", f_mb(ig["max_bytes"]) if ig.get("max_bytes") else MISSING,
        hr(ig.get("limit_bytes"), ig.get("max_bytes")) if ig.get("limit_bytes") else None)
    r = R("redis") or {}
    add("캐시", "캐시 메모리", f_mb(r.get("memory_max_bytes")) if r.get("memory_max_bytes") else "", f_mb(r.get("memory_used_max_bytes")) if r else MISSING,
        hr(r.get("memory_max_bytes"), r.get("memory_used_max_bytes")) if r else None)
    lg = s.get("loadgen") or {}
    add("시험", "부하 생성기 CPU", "100%", f_v(lg.get("cpu_max_pct"), 1, " %"), hr(100, lg.get("cpu_max_pct")))
    tight = [r_["name"] for r_ in rows if isinstance(r_["headroom"], (int, float)) and r_["headroom"] < 20]
    return rows, tight


def overall_rows(s, b, thr, nar):
    k = s.get("key") or {}
    bk = (b or {}).get("key") or {}
    idx = pre_index_bytes
    spec = [
        ("-", "실측 한계 TPS", None, None, lambda x: _get(nar, "limit_tps", x.get("round")) if x else None, False),
        ("-", "전체 p95 (목표 부하) — 판정", "p95", " ms", None, True),
        ("-", "전체 p99 (목표 부하) — 참고", "p99", " ms", None, True),
        ("-", "오류율 (목표 부하)", "error_rate", " %", None, True),
        ("DB", "총 질의 시간 (회차)", "total_db_ms", " ms", None, True),
        ("DB", "노드 CPU (목표 부하)", "db_cpu", " %", None, True),
        ("DB", "디스크 읽기 (블록)", "db_blks_read", "", None, True),
        ("DB", "인덱스 총 크기", None, None, idx, True),
        ("앱", "노드 CPU (목표 부하)", "app_cpu", " %", None, True),
        ("앱", "요청 스레드 최대", "tomcat_busy_max", "", None, True),
        ("앱", "GC 일시정지", "gc_pause_sec_per_sec", " s/s", None, True),
        ("경계", "요청당 질의 수", "queries_per_request", "", None, True),
        ("경계", "커넥션 풀 대기 최대", "pool_pending_max", "", None, True),
        ("경계", "커넥션 점유 시간 (p95)", "conn_hold_p95_ms", " ms", None, True),
        ("캐시", "요청당 Redis 명령 수", "redis_calls_per_request", "", None, True),
    ]
    rows = []
    for layer, name, key, unit, fn, lb in spec:
        if key:
            a, c = bk.get(key), k.get(key)
            fa, fc = (f_v(a, 3 if key in ("gc_pause_sec_per_sec",) else 2, unit) if b else ""), f_v(c, 3 if key in ("gc_pause_sec_per_sec",) else 2, unit)
        else:
            a, c = (fn(b) if b else None), fn(s)
            fmt = (lambda v: f_mb(v)) if name == "인덱스 총 크기" else (lambda v: f_v(v, 1))
            fa, fc = (fmt(a) if b else ""), fmt(c)
        rows.append({"layer": layer, "name": name, "base": fa, "cur": fc, "change": _delta(a, c, lb, thr) if b else ""})
    hb = nar.get("headroom_basis")
    rows.append({"layer": "-", "name": f"대표 헤드룸 (분모 {hb or '____'})", "base": f_n(_get(nar, "headroom_by_round", (b or {}).get("round", ""))) if b else "",
                 "cur": f_n(_get(nar, "headroom_by_round", s.get("round"))), "change": ""})
    return rows


def round_rows(summaries, sig):
    thr = (sig or {}).get("threshold_pct")
    rows = []
    prev = None
    for name, x in summaries:
        if not x:
            continue
        m = x.get("meta") or {}
        k = x.get("key") or {}
        rid = x.get("round", name)
        if m.get("verdict"):  # 측정자가 round.json 에 적은 판정 — 조건이 다른 앞 회차와 기계적으로 견주지 않을 때
            verdict = m["verdict"]
        elif _solo(x, name):
            verdict = "단가"
        elif sig and rid in (sig.get("rounds") or []):
            verdict = "흔들림"
        elif prev is None or thr is None:
            verdict = "기준" if prev is None else "하한 미정"
        else:
            _, verdict = compare.verdict(prev.get("p95"), k.get("p95"), True, thr)
            verdict = {"개선": "효과", "악화": "나빠짐"}.get(verdict, verdict)
        rows.append({"round": rid, "time": _get(x, "window", "start_kst"), "scenario": m.get("scenario"), "commit": m.get("commit"),
                     "migration": m.get("migration"), "changed": m.get("changed"), "p95": k.get("p95"), "tps": k.get("tps"),
                     "ebs": k.get("ebs_start_min"), "verdict": verdict})
        if not _solo(x, name):
            prev = k
    return rows


def unit_costs(summaries):
    """[7.11](2) — 단독 회차(U*) 하나가 엔드포인트 하나의 단가 열."""
    cols = []
    for name, x in summaries:
        if not _solo(x, name):
            continue
        routes = _get(x, "traces", "routes") or {}
        if not routes:
            continue
        r, t = max(routes.items(), key=lambda kv: kv[1]["requests"])
        ng = _get(x, "nginx", "routes", r) or {}
        cols.append({"round": x["round"], "route": r, "redis": t.get("redis_calls_per_request"),
                     "db_ms": _get(t, "db_ms_per_request", "mean"), "queries": _get(t, "sql_per_request", "mean"),
                     "kb": (ng.get("bytes_mean") or 0) / 1024 if ng.get("bytes_mean") is not None else None,
                     "alloc_mb": (_get(x, "resources", "app_totals", "alloc_bytes_per_request") or 0) / 1048576
                     if _get(x, "resources", "app_totals", "alloc_bytes_per_request") is not None else None})
    return cols


def interference(s, units):
    """[7.11](4) — 예상 = 단독 단가 × 혼합 요청 수. 단독 회차가 있을 때만."""
    if not units:
        return None
    routes = _get(s, "traces", "routes") or {}
    exp_db = exp_redis = 0.0
    covered = 0
    for u in units:
        t = routes.get(u["route"])
        if not t:
            continue
        covered += 1
        exp_db += (u["db_ms"] or 0) * t["requests"]
        exp_redis += (u["redis"] or 0) * t["requests"]
    if not covered:
        return None
    act_db = sum(routes[u["route"]]["db_ms_sum"] for u in units if u["route"] in routes)
    act_redis = sum(routes[u["route"]]["redis_calls_sum"] for u in units if u["route"] in routes)
    return [{"name": "DB 시간 (ms)", "exp": rnd(exp_db, 0), "act": rnd(act_db, 0), "diff": _delta(exp_db, act_db)},
            {"name": "Redis 명령 (store 구간)", "exp": rnd(exp_redis, 0), "act": rnd(act_redis, 0), "diff": _delta(exp_redis, act_redis)},
            {"name": "앱 CPU", "exp": None, "act": None, "diff": ""}]


def _pre_db(p, node="db01"):
    return ((p or {}).get("db") or {}).get(node) or {}


def pre_index_bytes(x):
    p = (x or {}).get("pre") or {}
    v = _pre_db(p).get("index_size_bytes")
    return v if v is not None else p.get("index_bytes_total")


def pre_rows(summaries):
    """[11.2] 회차 전 확인 — 최근 넷. pre.json(수집 스크립트 형식) · credits.json 에서."""
    cols = []
    for name, x in summaries[-4:]:
        if not x:
            continue
        p = x.get("pre") or {}
        d1 = _pre_db(p)
        tables = d1.get("tables") or {}
        cr = _get(x, "credits", "nodes") or {}
        # standby(db02)는 n_live_tup 이 0 이라 primary(db01) 값을 쓴다
        rows_ = {t: (v.get("n_live_tup") or v.get("reltuples")) for t, v in tables.items()}
        rows_ = {t: v for t, v in rows_.items() if v is not None}
        main = "property" if "property" in rows_ else (max(rows_, key=rows_.get) if rows_ else None)
        vis = {t: v.get("vm_ratio") for t, v in tables.items() if v.get("vm_ratio") is not None}
        slots = p.get("slots") or {}
        flat = [st for n in slots.values() for st in (n.values() if isinstance(n, dict) else [n])]
        cols.append({
            "round": x["round"],
            "rows": (f"{main} {int(rows_[main]):,} · 합 {int(sum(rows_.values())):,}" if main else ""),
            "index": f_mb(pre_index_bytes(x)) if pre_index_bytes(x) is not None else "",
            "hit": f_v(d1.get("buffer_hit_ratio"), 2, " %") if d1.get("buffer_hit_ratio") is not None else "",
            "vis": (" · ".join(f"{t} {float(v):.3f}" for t, v in sorted(vis.items(), key=lambda kv: -(rows_.get(kv[0]) or 0))[:2]) if vis else ""),
            "ebs": " / ".join(f_v(_get(cr, n, "ebs_burst_balance", "pre"), 1) for n in ("DB-01", "DB-02")) if cr else "",
            "cpu": " · ".join(f_v(_get(cr, n, "cpu_credit_balance", "pre"), 0) for n in ("APP-01", "APP-02", "DB-01", "DB-02")) if cr else "",
            "slots": (f"{sum(1 for v in flat if str(v).lower() == 'healthy')} / {len(flat)} healthy" if flat else None),
            "warnings": p.get("warnings") or [],
        })
    return cols


def unmeasured_rows(s, nar):
    rows = list(nar.get("unmeasured") or [])
    miss = _get(s, "resources", "missing_metrics") or []
    for m in miss:
        rows.append({"layer": "", "item": f"지표 없음 — {m}", "why": f"{s['round']} 원자료에 없다", "how": "수집 대상 · 지표 이름 확인"})
    for k, v in (s.get("checks") or {}).items():
        if v is False or v is None:
            label = {"traces": "추적", "wait_events": "D6 대기 종류", "blk_read_time_nonzero": "D8 블록 읽기 시간", "uct_urt": "W7 uct / urt",
                     "credits": "크레딧 스냅샷", "loadgen": "부하 생성기 자원", "profiler": "프로파일", "auto_explain": "auto_explain"}[k]
            rows.append({"layer": "", "item": label, "why": f"{s['round']} 에 값이 없다", "how": ""})
    return rows


def collapse_view(s):
    """[7.8] 프로세스 종료 · 알림 — 원천: 슬롯 재시작(W2) · OOM(O5) · 부팅 시각(O13) · systemd 실패(O14) · DB 재시작 · 알림 이력."""
    nodes = _get(s, "resources", "nodes") or {}
    app = _get(s, "resources", "app_totals", "restarts")
    oom = [v.get("oom_kills_delta") for v in nodes.values() if v.get("oom_kills_delta") is not None]
    reboot = sorted(n for n, v in nodes.items() if (v.get("boot") or {}).get("rebooted"))
    failed = sorted({f"{n} {u}" for n, v in nodes.items() for u in (v.get("systemd_failed") or [])})
    sysd_known = any(v.get("systemd_failed") is not None for v in nodes.values())
    boot_known = any(v.get("boot") is not None for v in nodes.values())
    db = [v.get("restarts") for v in (_get(s, "resources", "postgres") or {}).values() if v.get("restarts") is not None]
    coom = _get(s, "resources", "app_totals", "container_oom_kills")
    ro = sorted(n for n, v in nodes.items() if v.get("fs_readonly"))
    al = s.get("alerts")
    fired = (al or {}).get("fired") or []
    return {
        "app_restarts": app, "oom": sum(oom) if oom else None, "container_oom": coom, "rebooted": reboot, "boot_known": boot_known,
        "systemd_failed": failed, "systemd_known": sysd_known, "db_restarts": sum(db) if db else None, "fs_readonly": ro,
        "any_exit": bool(app) or bool(coom) or bool(sum(oom) if oom else 0) or bool(reboot) or bool(sum(db) if db else 0),
        "alerts": al, "fired": fired,
    }


def explain_pairs(s, b):
    """[4.2] — 질의 id 마다 (전, 후). 후는 이 회차, 전은 기준 회차의 같은 id."""
    cur = (s.get("explain") or {}).get("queries") or []
    base = {q["id"]: q for q in ((b or {}).get("explain") or {}).get("queries") or []}
    return [{"q": q, "b": base.get(q["id"])} for q in cur]


def alert_rows(s, nar):
    """[9.4](라) — 시험에서 본 상태 · 울린 규칙은 이력에서, 「울렸어야 했나」는 사람(narrative.feedback.alerts)."""
    rows = list(_get(nar, "feedback", "alerts") or [])
    al = s.get("alerts")
    if not al:
        return rows
    have = {r.get("rule") for r in rows}
    for t in al.get("transitions") or []:
        rule = t.get("rule_title") or t.get("rule_uid")
        if rule in have:
            continue
        when = f" · 시작 {t['minutes_after_start']}분 뒤" if t.get("minutes_after_start") is not None else ""
        rows.append({"state": f"{t.get('from')} → {t.get('to')}{when}{' (회차 끝난 뒤)' if t.get('after_end') else ''}",
                     "rule": rule, "should": ""})
    if not al.get("transitions") and al.get("available"):
        rows.append({"state": f"{s['round']} 구간 상태 전환 없음 (이력 {al.get('source')})", "rule": "없음", "should": ""})
    return rows


# ---------------------------------------------------------------- 묶음 · 렌더

def build_context(s, b, out_dir: Path):
    nar = read_json(RESULTS_DIR / "narrative.json", {}) or {}
    sig = compare.load_sig(s.get("folder", "").startswith("_fixture"))
    thr = (sig or {}).get("threshold_pct")
    fixtures = s.get("folder", "").startswith("_fixture")
    summaries = compare.all_summaries(include_fixtures=fixtures)
    if not any(n == s.get("folder") for n, _ in summaries):
        summaries.append((s.get("folder"), s))
    summaries = [(n, s if n == s.get("folder") else x) for n, x in summaries]
    starts = [x["window"]["start_utc"] for _, x in summaries if x and _get(x, "window", "start_utc")]
    ends = [x["window"]["end_utc"] for _, x in summaries if x and _get(x, "window", "end_utc")]
    rids = [x["round"] for _, x in summaries if x]

    def gpath(x, g):
        name = _get(x, "graphs", g)
        if not name:
            return None
        p = RESULTS_DIR / x["folder"] / "out" / "graphs" / name
        return os.path.relpath(p, out_dir).replace("\\", "/")

    graphs = {g: gpath(s, g) for g in ("G1", "G1s", "G2", "G3", "G4", "G5", "G6", "G7")}
    units = unit_costs(summaries)
    hrows, tight = headroom_rows(s)
    tr_routes = _get(s, "traces", "routes") or {}
    btr = _get(b, "traces", "routes") or {}
    budget_routes = [r for r in (s.get("budget") or {}) if (s["budget"][r].get("server_ms") is not None)][:5]
    return {
        "s": s, "b": b, "R": s["round"], "B": (b or {}).get("round"), "nar": nar, "sig": sig, "thr": thr,
        "today": datetime.now(KST).strftime("%Y-%m-%d"),
        "period": f"{fmt_kst(min(starts))} ~ {fmt_kst(max(ends))} (KST)" if starts and ends else MISSING,
        "rids": rids, "recent": [x for _, x in summaries if x][-6:],
        "graphs": graphs,
        "comparability": comparability(s, b, nar),
        "signals": signal_rows(s, nar, solo_limits(summaries)),
        "db_rows": db_rows(s, b, thr), "wait_rows": wait_rows(s, b),
        "app_rows": app_rows(s, b, thr), "slot_rows": slot_rows(s),
        "prof": profiler_view(s), "bprof": profiler_view(b),
        "headroom": hrows, "tight": tight,
        "overall": overall_rows(s, b, thr, nar),
        "rounds": round_rows(summaries, sig),
        "units": units, "interf": interference(s, units),
        "pre_cols": pre_rows(summaries),
        "unmeasured": unmeasured_rows(s, nar),
        "collapse": collapse_view(s),
        "explain_pairs": explain_pairs(s, b),
        "alert_rows": alert_rows(s, nar),
        "budget_routes": budget_routes,
        "tr_routes": tr_routes, "btr": btr,
        "trend": [x for n, x in summaries if x and not _solo(x, n)][-4:],
        "delta": lambda a, c, lb=True: _delta(a, c, lb, thr),
    }


def render_html(ctx, out_html: Path):
    env = Environment(loader=FileSystemLoader(str(REPORT_SRC_DIR)), undefined=ChainableUndefined, autoescape=True,
                      trim_blocks=True, lstrip_blocks=True)
    env.filters.update(v=f_v, n=f_n, mb=f_mb, ox=f_ox, short=f_short)
    env.tests["given"] = t_given
    env.globals["get"] = _get
    html = env.get_template("template.html.j2").render(**ctx)
    out_html.write_text(html, encoding="utf-8")
    return out_html


def find_browser():
    cands = [r"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", r"C:/Program Files/Microsoft/Edge/Application/msedge.exe"]
    for c in cands:
        if Path(c).exists():
            return c
    for n in ("chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "microsoft-edge"):
        p = shutil.which(n)
        if p:
            return p
    return None


def to_pdf(html: Path, pdf: Path):
    exe = find_browser()
    if not exe:
        print("PDF 변환기(Edge · Chromium)를 찾지 못했다 — HTML 만 남긴다", file=sys.stderr)
        return None
    if pdf.exists():
        pdf.unlink()
    prof = tempfile.mkdtemp(prefix="edge-pdf-")
    # 목록 인자로 넘긴다 — 셸을 거치면 한글 경로가 깨진다
    cmd = [exe, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", "--no-first-run",
           f"--user-data-dir={prof}", f"--print-to-pdf={pdf}", html.resolve().as_uri()]
    try:
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)
        # Windows Edge 는 실행기가 먼저 끝나고 자식이 파일을 쓴다 — 크기가 멈출 때까지 기다린다
        last = -1
        for _ in range(120):
            if pdf.exists():
                size = pdf.stat().st_size
                if size > 0 and size == last:
                    break
                last = size
            time.sleep(1)
    finally:
        shutil.rmtree(prof, ignore_errors=True)
    return pdf if pdf.exists() else None


def page_count(pdf: Path):
    data = pdf.read_bytes()
    return len(re.findall(rb"/Type\s*/Page[^s]", data))


def build(s, b=None):
    out_dir = RESULTS_DIR / "report"
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(KST).strftime("%Y-%m-%d")
    tag = "-fixture" if s.get("folder", "").startswith("_fixture") else ""
    html = out_dir / f"report-{stamp}{tag}.html"
    ctx = build_context(s, b, out_dir)
    render_html(ctx, html)
    pdf = to_pdf(html, out_dir / f"report-{stamp}{tag}.pdf")
    if pdf:
        print(f"PDF {page_count(pdf)}쪽")
        return pdf
    return html
