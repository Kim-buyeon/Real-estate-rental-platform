"""DB 원자료 — 대기 종류(D6) · 질의 통계 · auto_explain. [4.1] [4.2] [4.6]."""
from __future__ import annotations

import csv
import gzip
import re
from collections import defaultdict
from pathlib import Path

from .traces import stmt_key
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


def read_pgss_all(path: Path):
    """collect.sh 의 `<node>-pgss-all.csv.gz` — datname 열 + pg_stat_statements 전 열(원문 질의).
    PG 17 열 이름(total_exec_time · shared_blk_read_time)과 상위 20 파일의 열 이름을 둘 다 받는다."""
    rows = []
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", errors="replace", newline="") as f:
        for r in csv.DictReader(f):
            g = lambda *ks: next((r[k] for k in ks if r.get(k) not in (None, "")), None)
            rows.append({
                "datname": r.get("datname") or "",
                "queryid": r.get("queryid"),
                "calls": _f(g("calls")) or 0,
                "total_ms": _f(g("total_exec_time", "total_exec_time_ms")) or 0.0,
                "mean_ms": _f(g("mean_exec_time", "mean_exec_time_ms")),
                "stddev_ms": _f(g("stddev_exec_time", "stddev_exec_time_ms")),
                "rows": _f(g("rows")),
                "blks_hit": _f(g("shared_blks_hit")),
                "blks_read": _f(g("shared_blks_read")),
                "temp_written": _f(g("temp_blks_written")),
                "blk_read_time_ms": _f(g("shared_blk_read_time", "blk_read_time", "blk_read_time_ms")),
                "query": re.sub(r"\s+", " ", (r.get("query") or "")).strip(),
            })
    rows.sort(key=lambda x: -x["total_ms"])
    return rows


def _app_db(allrows, top, dbstats_name=None):
    """앱 DB 고르기 — ① 상위 20 의 queryid 가 가장 많이 걸리는 datname ② dbstats 의 datname
    ③ postgres · template* 를 뺀 DB 중 총 실행 시간이 가장 큰 것. (DB 이름, 근거)."""
    ids = {r["queryid"] for r in top or [] if r.get("queryid")}
    if ids:
        hits = defaultdict(int)
        for r in allrows:
            if r["queryid"] in ids:
                hits[r["datname"]] += 1
        if hits:
            return max(hits, key=hits.get), "상위 20 의 queryid"
    names = {r["datname"] for r in allrows}
    if dbstats_name and dbstats_name in names:
        return dbstats_name, "dbstats 의 datname"
    tot = defaultdict(float)
    for r in allrows:
        if r["datname"] != "postgres" and not r["datname"].startswith("template"):
            tot[r["datname"]] += r["total_ms"]
    if tot:
        return max(tot, key=tot.get), "총 실행 시간 최대(postgres · template 제외)"
    return None, "고를 DB 없음"


def pgss(db_dir: Path, node: str, requests=None, dbstats_name=None):
    """상위 20 · 점유율. 합(총 시간 · 호출 수)은 전체 파일(<node>-pgss-all.csv.gz)의 앱 DB 행으로 —
    없으면 상위 20 합(점유율이 부풀어 보인다, total_basis 에 적힌다)."""
    top_path = _pick(db_dir, node, "-pgss.csv")
    all_path = next((p for p in (_pick(db_dir, node, "-pgss-all.csv.gz"), _pick(db_dir, node, "-pgss-all.csv")) if p.exists()), None)
    if not top_path.exists() and all_path is None:
        return None
    top = read_pgss(top_path) if top_path.exists() else None
    app_db, why = None, None
    allrows = None
    if all_path is not None:
        raw = read_pgss_all(all_path)
        app_db, why = _app_db(raw, top, dbstats_name)
        allrows = [r for r in raw if r["datname"] == app_db] if app_db else None
    if top is None:
        top = (allrows or [])[:20]
    basis = allrows if allrows else top
    total = sum(r["total_ms"] for r in basis)
    total_calls = sum(r["calls"] for r in basis)
    out = []
    for i, r in enumerate(top[:20], 1):
        d = dict(r)
        d["rank"] = i
        d["share_pct"] = rnd(100.0 * r["total_ms"] / total, 2) if total else None
        d["total_ms"] = rnd(r["total_ms"], 2)
        d["mean_ms"] = rnd(r["mean_ms"], 3)
        out.append(d)
    shares = [d["share_pct"] or 0 for d in out]
    # 문장 키(추적과 같은 정규화 — $n · 리터럴 → ?)별 합 — 추적 SQL 구간의 문장별 속도와 잇는다(pipeline 이 쓰고 지운다).
    # 같은 키에 queryid 가 여럿이면 호출 · 시간 · 행 · 블록은 더하고, 표준편차는 호출이 가장 많은 행의 값이다(합칠 원천이 없다)
    by_key = {}
    for r in basis:
        k = stmt_key(r["query"])
        if not k:
            continue
        x = by_key.get(k)
        if x is None:
            by_key[k] = x = {"calls": 0.0, "total_ms": 0.0, "rows": 0.0, "blks_hit": 0.0, "blks_read": 0.0,
                             "stddev_ms": r.get("stddev_ms"), "_top_calls": r["calls"], "queryids": []}
        elif r["calls"] > x["_top_calls"]:
            x["stddev_ms"], x["_top_calls"] = r.get("stddev_ms"), r["calls"]
        x["calls"] += r["calls"]
        x["total_ms"] += r["total_ms"]
        for f in ("rows", "blks_hit", "blks_read"):
            x[f] += r.get(f) or 0
        x["queryids"].append(r.get("queryid"))
    for x in by_key.values():
        x.pop("_top_calls")
        x["mean_ms"] = rnd(x["total_ms"] / x["calls"], 3) if x["calls"] else None
        x["rows_per_call"] = rnd(x["rows"] / x["calls"], 2) if x["calls"] else None
        x["stddev_ms"] = rnd(x["stddev_ms"], 3)
        x["total_ms"] = rnd(x["total_ms"], 2)
    return {
        "_by_key": by_key,
        "total_basis": "pgss-all" if allrows else "top20",
        "app_db": app_db,
        "app_db_basis": why,
        "total_exec_ms": rnd(total, 1),
        "total_calls": total_calls,
        "statements": len(basis),
        "top1_share_pct": rnd(sum(shares[:1]), 2),
        "top3_share_pct": rnd(sum(shares[:3]), 2),
        "db_ms_per_request": rnd(total / requests, 3) if requests else None,
        "calls_per_request": rnd(total_calls / requests, 3) if requests else None,
        "top": out,
    }


_PLAN_START = re.compile(r"duration:\s*([\d.]+)\s*ms\s+plan:", re.I)


def auto_explain_blocks(path: Path):
    """auto_explain 로그 → [(duration_ms, 계획 본문)]. 본문은 「Query Text:」 줄부터 다음 블록 앞까지."""
    text = path.read_text(encoding="utf-8", errors="replace")
    starts = [m for m in _PLAN_START.finditer(text)]
    return [(float(m.group(1)), text[m.end(): starts[i + 1].start() if i + 1 < len(starts) else len(text)])
            for i, m in enumerate(starts)]


def query_text(body):
    """auto_explain 블록의 Query Text — 여러 줄이면 다음 계획 줄(들여쓴 노드 줄) 앞까지 잇는다."""
    m = re.search(r"Query Text:\s*(.*)", body)
    if not m:
        return None
    lines = [m.group(1)]
    for ln in body[m.end():].splitlines()[1:]:
        s = ln.strip()
        if not s or re.match(r"^(->\s*)?[A-Z][A-Za-z ]+.*\(cost=", s) or s.startswith(("Query Parameters:", "Settings:")):
            break
        lines.append(s)
    return " ".join(lines).strip()


def auto_explain(path: Path):
    """계획 블록 수 · 노드 종류별 등장 수 · 블록별 접근 방법."""
    blocks = []
    for dur, body in auto_explain_blocks(path):
        counts = {}
        for node in PLAN_NODES:
            # "Bitmap Index Scan" 은 Index Scan 으로 세지 않는다
            c = len(re.findall(r"(?<!Bitmap )\b" + re.escape(node) + r"\b", body))
            if c:
                counts[node] = c
        qm = re.search(r"Query Text:\s*(.+)", body)
        blocks.append({"duration_ms": dur, "nodes": counts, "query": (qm.group(1).strip()[:120] if qm else None)})
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
        ds = dbstats(db_dir, node, pre)
        if ds:
            res["dbstats"][node] = ds
        p = pgss(db_dir, node, requests, (ds or {}).get("datname"))
        if p:
            res["pgss"][node] = p
        a = _pick(db_dir, node, "-auto-explain.log")
        if a.exists():
            res["auto_explain"][node] = auto_explain(a)
    tot = [p["total_exec_ms"] for p in res["pgss"].values() if p.get("total_exec_ms") is not None]
    calls = [p["total_calls"] for p in res["pgss"].values()]
    res["total_db_ms"] = rnd(sum(tot), 1) if tot else None
    res["db_ms_per_request"] = rnd(sum(tot) / requests, 3) if tot and requests else None
    res["queries_per_request"] = rnd(sum(calls) / requests, 3) if calls and requests else None
    res["pgss_basis"] = {n: {"total_basis": p["total_basis"], "app_db": p["app_db"], "app_db_basis": p["app_db_basis"]}
                         for n, p in res["pgss"].items()}
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
