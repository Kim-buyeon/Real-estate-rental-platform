"""Nginx 접근 로그 — 입구 시간(rt) · 슬롯 응답(urt) · 슬롯 연결(uct) · 경로별 바이트. [6.6] [7.1] · W2."""
from __future__ import annotations

import re
from collections import defaultdict
from datetime import datetime
from pathlib import Path

from .util import percentile, mean, rnd, route_key, open_text

SSE_PATH = "/api/notifications/stream"
_LINE = re.compile(
    r'^(?P<addr>\S+) - (?P<user>\S+) \[(?P<time>[^\]]+)\] "(?P<req>[^"]*)" (?P<status>\d{3}) (?P<bytes>\d+) '
    r'"(?P<ref>[^"]*)" "(?P<ua>[^"]*)" upstream=(?P<up>.*?) rt=(?P<rt>\S+) rid=(?P<rid>\S+) '
    r'uct=(?P<uct>.*?) urt=(?P<urt>.*?)\s*$')


def _last(v):
    """재시도면 쉼표로 여럿 — 마지막이 응답한 슬롯이다. '-' 는 None."""
    if v is None:
        return None
    parts = [p.strip() for p in v.split(",") if p.strip()]
    if not parts:
        return None
    p = parts[-1]
    if p == "-":
        return None
    try:
        return float(p)
    except ValueError:
        return p


def parse_line(line):
    m = _LINE.match(line.rstrip("\n"))
    if not m:
        return None
    try:
        t = datetime.strptime(m["time"], "%d/%b/%Y:%H:%M:%S %z").timestamp()
    except ValueError:
        return None
    req = m["req"].split()
    path = req[1] if len(req) >= 2 else m["req"]
    ups = [u.strip() for u in m["up"].split(",") if u.strip()]
    return {
        "ts": t,
        "method": req[0] if req else None,
        "path": path,
        "status": int(m["status"]),
        "bytes": int(m["bytes"]),
        "upstream": ups[-1] if ups else None,
        "retries": max(len(ups) - 1, 0),
        "rt": _num(m["rt"]),
        "uct": _last(m["uct"]),
        "urt": _last(m["urt"]),
        "rid": m["rid"],
    }


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def analyze(path: Path, win, collect=None):
    """collect — 리스트를 주면 구간 안 줄(시작 시각 start 를 붙여)을 담아 돌려준다(단계별 큐 대기 · steps.py)."""
    if not path.exists():
        return None
    rows = []
    bad = 0
    with open_text(path) as f:
        for line in f:
            r = parse_line(line)
            if r is None:
                bad += 1
                continue
            # 로그 시각은 응답을 끝낸 시각 — 시작 시각으로 되돌려 구간에 넣는다
            start = r["ts"] - (r["rt"] or 0)
            if win.contains(start):
                r["start"] = start
                rows.append(r)
    if collect is not None:
        collect.extend(r for r in rows if r["path"].split("?")[0] != SSE_PATH)
    routes = defaultdict(list)
    sse = 0
    for r in rows:
        if r["path"].split("?")[0] == SSE_PATH:
            sse += 1
            continue
        routes[route_key(r["path"])].append(r)
    out = {}
    for k, rs in sorted(routes.items(), key=lambda x: -len(x[1])):
        ms = lambda key: [r[key] * 1000 for r in rs if isinstance(r[key], float)]
        by = [r["bytes"] for r in rs]
        rt, urt, uct = ms("rt"), ms("urt"), ms("uct")
        # 같은 요청 안의 차이 — 입구 · 네트워크 몫
        rt_urt = [(r["rt"] - r["urt"]) * 1000 for r in rs if isinstance(r["rt"], float) and isinstance(r["urt"], float)]
        out[k] = {
            "count": len(rs),
            "rt_p95": rnd(percentile(rt, 95)), "rt_p50": rnd(percentile(rt, 50)),
            "urt_p95": rnd(percentile(urt, 95)), "uct_p95": rnd(percentile(uct, 95), 3),
            "rt_minus_urt_p95": rnd(percentile(rt_urt, 95), 3),
            "bytes_mean": rnd(mean(by), 1), "bytes_p95": rnd(percentile(by, 95), 0), "bytes_sum": sum(by),
            "status_5xx": sum(1 for r in rs if r["status"] >= 500),
            "retries": sum(r["retries"] for r in rs),
        }
    ups = defaultdict(int)
    for r in rows:
        if r["upstream"] and r["upstream"] != "-":
            ups[r["upstream"]] += 1
    tot = sum(ups.values())
    has_timing = any(isinstance(r["urt"], float) for r in rows)
    return {
        "lines": len(rows),
        "unparsed": bad,
        "sse_excluded": sse,
        "uct_urt_present": has_timing,
        "routes": out,
        "upstreams": {u: {"count": c, "share_pct": rnd(100.0 * c / tot, 1)} for u, c in sorted(ups.items())} if tot else {},
    }
