"""DB 원자료 — 대기 종류(D6) · 질의 통계 · auto_explain. [4.1] [4.2] [4.6]."""
from __future__ import annotations

import csv
import re
from collections import defaultdict
from pathlib import Path

from .util import parse_time, rnd, file_node

WAIT_GROUPS = ("CPU", "IO", "Lock", "LWLock", "Client")
PLAN_NODES = ("Seq Scan", "Index Only Scan", "Index Scan", "Bitmap Heap Scan", "Nested Loop", "Hash Join", "Merge Join")


def wait_events(path: Path, win):
    """1초 표본 → 대기 종류별 평균 활성 세션(AAS)과 비중."""
    sums = defaultdict(float)
    stamps = set()
    per_ts = defaultdict(float)
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        for r in csv.DictReader(f):
            try:
                t = parse_time(r["ts"])
                c = float(r["count"])
            except (KeyError, TypeError, ValueError):
                continue
            if not win.in_round(t):
                continue
            wt = (r.get("wait_event_type") or "").strip() or "CPU"
            if wt == "NONE":
                # 활성 세션이 없던 초 — 표본으로는 세고 비중에서는 뺀다
                c = 0.0
            per_ts[t] += c
            if not win.contains(t):
                continue
            stamps.add(t)
            if c:
                sums[wt] += c
    n = len(stamps)
    if not n:
        return None
    aas = {k: v / n for k, v in sums.items()}
    total = sum(aas.values())
    grouped = defaultdict(float)
    for k, v in aas.items():
        grouped[k if k in WAIT_GROUPS else "기타"] += v
    return {
        "samples": n,
        "aas_total": rnd(total, 3),
        "aas": {k: rnd(v, 3) for k, v in sorted(aas.items(), key=lambda x: -x[1])},
        "share_pct": {k: rnd(100.0 * v / total, 1) if total else None for k, v in aas.items()},
        "grouped": {k: {"aas": rnd(grouped.get(k, 0.0), 3), "share_pct": rnd(100.0 * grouped.get(k, 0.0) / total, 1) if total else None}
                    for k in WAIT_GROUPS + ("기타",)},
        "_ts": sorted(per_ts.items()),
    }


def _f(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def read_pgss(path: Path):
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        for r in csv.DictReader(f):
            rows.append({
                "queryid": r.get("queryid"),
                "calls": _f(r.get("calls")) or 0,
                "total_ms": _f(r.get("total_exec_time_ms")) or 0.0,
                "mean_ms": _f(r.get("mean_exec_time_ms")),
                "rows": _f(r.get("rows")),
                "blks_hit": _f(r.get("shared_blks_hit")),
                "blks_read": _f(r.get("shared_blks_read")),
                "temp_written": _f(r.get("temp_blks_written")),
                "blk_read_time_ms": _f(r.get("blk_read_time_ms")),
                "query": (r.get("query") or "").strip(),
            })
    rows.sort(key=lambda x: -x["total_ms"])
    return rows


def _pick(db_dir: Path, node: str, suffix: str):
    """db01-wait.csv 와 db-01-wait.csv 둘 다 받는다."""
    for n in (file_node(node), node):
        p = db_dir / f"{n}{suffix}"
        if p.exists():
            return p
    return db_dir / f"{file_node(node)}{suffix}"


def dbstats(db_dir: Path, node: str, pre: dict | None):
    """회차 뒤 pg_stat_database 한 줄 − 회차 전(pre.json db.<node>) → 적중률. 지표가 없을 때의 대안."""
    p = _pick(db_dir, node, "-dbstats.csv")
    if not p.exists():
        return None
    rows = []
    with open(p, "r", encoding="utf-8", errors="replace", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        return None
    # 앱 DB = blks_hit 가 가장 큰 줄
    r = max(rows, key=lambda x: _f(x.get("blks_hit")) or 0)
    post = {k: _f(v) for k, v in r.items()}
    out = {"datname": r.get("datname"), "post": {k: v for k, v in post.items() if v is not None}}
    pd = ((pre or {}).get("db") or {}).get(file_node(node)) or ((pre or {}).get("db") or {}).get(node) or {}
    h0, r0 = _f(pd.get("blks_hit")), _f(pd.get("blks_read"))
    h1, r1 = post.get("blks_hit"), post.get("blks_read")
    if None not in (h0, r0, h1, r1) and h1 >= h0 and r1 >= r0 and (h1 - h0) + (r1 - r0) > 0:
        out["blks_hit_delta"] = h1 - h0
        out["blks_read_delta"] = r1 - r0
        out["hit_ratio_pct"] = rnd(100.0 * (h1 - h0) / ((h1 - h0) + (r1 - r0)), 3)
    return out


def pgss(db_dir: Path, node: str, requests=None):
    """상위 20 · 점유율. 전체 합은 <node>-pgss-all.csv 가 있으면 그것으로(없으면 상위 20 합 — 점유율이 부풀어 보인다)."""
    top_path = _pick(db_dir, node, "-pgss.csv")
    all_path = _pick(db_dir, node, "-pgss-all.csv")
    if not top_path.exists() and not all_path.exists():
        return None
    top = read_pgss(top_path) if top_path.exists() else read_pgss(all_path)[:20]
    allrows = read_pgss(all_path) if all_path.exists() else None
    total = sum(r["total_ms"] for r in (allrows or top))
    total_calls = sum(r["calls"] for r in (allrows or top))
    out = []
    for i, r in enumerate(top[:20], 1):
        d = dict(r)
        d["rank"] = i
        d["share_pct"] = rnd(100.0 * r["total_ms"] / total, 2) if total else None
        d["total_ms"] = rnd(r["total_ms"], 2)
        d["mean_ms"] = rnd(r["mean_ms"], 3)
        out.append(d)
    shares = [d["share_pct"] or 0 for d in out]
    return {
        "total_basis": "pgss-all" if allrows else "top20",
        "total_exec_ms": rnd(total, 1),
        "total_calls": total_calls,
        "statements": len(allrows) if allrows else len(top),
        "top1_share_pct": rnd(sum(shares[:1]), 2),
        "top3_share_pct": rnd(sum(shares[:3]), 2),
        "db_ms_per_request": rnd(total / requests, 3) if requests else None,
        "calls_per_request": rnd(total_calls / requests, 3) if requests else None,
        "top": out,
    }


_PLAN_START = re.compile(r"duration:\s*([\d.]+)\s*ms\s+plan:", re.I)


def auto_explain(path: Path):
    """계획 블록 수 · 노드 종류별 등장 수 · 블록별 접근 방법."""
    text = path.read_text(encoding="utf-8", errors="replace")
    starts = [m for m in _PLAN_START.finditer(text)]
    blocks = []
    for i, m in enumerate(starts):
        body = text[m.end(): starts[i + 1].start() if i + 1 < len(starts) else len(text)]
        counts = {}
        for node in PLAN_NODES:
            # "Bitmap Index Scan" 은 Index Scan 으로 세지 않는다
            c = len(re.findall(r"(?<!Bitmap )\b" + re.escape(node) + r"\b", body))
            if c:
                counts[node] = c
        qm = re.search(r"Query Text:\s*(.+)", body)
        blocks.append({"duration_ms": float(m.group(1)), "nodes": counts, "query": (qm.group(1).strip()[:120] if qm else None)})
    totals = defaultdict(int)
    with_node = defaultdict(int)
    for b in blocks:
        for k, v in b["nodes"].items():
            totals[k] += v
            with_node[k] += 1
    durs = sorted(b["duration_ms"] for b in blocks)
    return {
        "plans": len(blocks),
        "node_counts": dict(totals),
        "plans_with_node": dict(with_node),
        "max_duration_ms": durs[-1] if durs else None,
        "slowest": sorted(blocks, key=lambda b: -b["duration_ms"])[:5],
    }


def analyze(db_dir: Path, win, requests=None, pre=None):
    res = {"wait": {}, "pgss": {}, "auto_explain": {}, "wait_ts": {}, "dbstats": {}}
    if not db_dir.exists():
        return res
    for node in ("db-01", "db-02"):
        w = _pick(db_dir, node, "-wait.csv")
        if w.exists():
            we = wait_events(w, win)
            if we:
                ts = we.pop("_ts")
                if win.start is not None:
                    res["wait_ts"][node] = [[rnd(t - win.start, 1), rnd(v, 2)] for t, v in ts]
                res["wait"][node] = we
        p = pgss(db_dir, node, requests)
        if p:
            res["pgss"][node] = p
        a = _pick(db_dir, node, "-auto-explain.log")
        if a.exists():
            res["auto_explain"][node] = auto_explain(a)
        ds = dbstats(db_dir, node, pre)
        if ds:
            res["dbstats"][node] = ds
    tot = [p["total_exec_ms"] for p in res["pgss"].values() if p.get("total_exec_ms") is not None]
    calls = [p["total_calls"] for p in res["pgss"].values()]
    res["total_db_ms"] = rnd(sum(tot), 1) if tot else None
    res["db_ms_per_request"] = rnd(sum(tot) / requests, 3) if tot and requests else None
    res["queries_per_request"] = rnd(sum(calls) / requests, 3) if calls and requests else None
    # 두 노드 합친 상위 — 점유율은 두 노드 합 기준
    merged = []
    for node, p in res["pgss"].items():
        for r in p["top"]:
            merged.append(dict(r, node=node))
    merged.sort(key=lambda x: -x["total_ms"])
    tot_all = sum(tot) if tot else 0
    for r in merged:
        r["share_all_pct"] = rnd(100.0 * r["total_ms"] / tot_all, 2) if tot_all else None
    res["top_merged"] = merged[:20]
    return res
