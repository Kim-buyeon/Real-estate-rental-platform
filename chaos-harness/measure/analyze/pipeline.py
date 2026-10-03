"""회차 폴더 하나 → summary.json. 원자료별 집계를 모으고 엔드포인트 기준으로 잇는다."""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from . import jtl, prom, db, nginx, traces, misc
from .util import Window, parse_time, read_json, write_json, rnd, percentile, route_key, fmt_kst, RESULTS_DIR

TARGET_P95_MS = 500
MATRIX_COLS = 6     # [7.11] 에 따로 세우는 엔드포인트 수 — 나머지는 「그 외」


def round_dir(name: str) -> Path:
    return RESULTS_DIR / name


ROUND_KEYS = ("scenario", "changed", "commit", "migration", "warmup_sec", "notes", "kind")


def load_meta(rdir: Path):
    """meta.json(수집 스크립트) 위에 round.json(측정자가 쓴 조건)을 덮는다. 없는 키는 None."""
    meta = read_json(rdir / "meta.json", {}) or {}
    extra = read_json(rdir / "round.json", {}) or {}
    meta.update({k: v for k, v in extra.items() if v is not None})
    for k in ROUND_KEYS:
        meta.setdefault(k, None)
    meta.setdefault("round", rdir.name)
    return meta


def analyze_round(name: str, write=True):
    rdir = round_dir(name)
    if not rdir.exists():
        raise SystemExit(f"회차 폴더가 없다: {rdir}")
    meta = load_meta(rdir)
    win = Window(parse_time(meta.get("start_utc")), parse_time(meta.get("end_utc")), meta.get("warmup_sec") or 0)
    rec = (win.start, win.end)
    s = {"round": meta.get("round", name), "folder": name, "meta": meta}

    # 1. 부하 도구 — 통계 구간이 여기서 정해진다(JMeter 첫 표본 ~ 마지막 끝)
    j, jrows = (None, [])
    jp = rdir / "jmeter" / "result.jtl"
    if jp.exists():
        j, jrows = jtl.analyze(jp, win)
    s["jtl"] = j
    requests = j["requests"] if j else None
    # 이 뒤 모든 원자료가 같은 구간(JMeter 기준)을 쓴다. JMeter 결과가 없으면 기록 구간 그대로
    s["window"] = {"basis": "jmeter" if j else "record", "start_utc": win.start, "end_utc": win.end, "warmup_sec": win.warmup,
                   "stats_start_utc": win.lo, "duration_sec": rnd(win.duration, 1),
                   "start_kst": fmt_kst(win.start), "end_kst": fmt_kst(win.end),
                   "record_start_utc": rec[0], "record_end_utc": rec[1]}

    # 2. 지표 · DB · 입구 · 추적 · 그 밖
    s["resources"] = prom.analyze(rdir / "metrics", win, requests)
    s["pre"] = read_json(rdir / "pre" / "pre.json")
    s["db"] = db.analyze(rdir / "db", win, requests, s["pre"])
    _dbstats_fallback(s)
    s["nginx"] = nginx.analyze(rdir / "nginx" / "access.log", win)
    s["traces"] = traces.analyze(rdir / "traces" / "spans.jsonl", win)
    s["redis_commandstats"] = misc.commandstats_delta(rdir / "pre" / "redis-commandstats.txt", rdir / "post" / "redis-commandstats.txt", requests)
    s["credits"] = misc.credits(rdir / "pre" / "credits.json", rdir / "post" / "credits.json")
    s["profiler"] = misc.profiler(rdir / "profiler")
    s["loadgen"] = None
    for cand in ("gen/vmstat.txt", "loadgen/vmstat.txt"):
        if (rdir / cand).exists():
            s["loadgen"] = misc.loadgen_vmstat(rdir / cand, win)
            break
    s["heavy_files"] = sorted(p.name for p in (rdir / "heavy").glob("*")) if (rdir / "heavy").exists() else []

    # 3. 엔드포인트로 잇기
    s["route_tool"] = _route_tool(jrows, win)
    s["budget"] = budgets(s)
    s["matrix"] = matrix(s)
    s["endpoints"] = endpoints(s)
    _attach_pgss_routes(s)
    s["key"] = key_metrics(s)
    s["checks"] = checks(s)
    if write:
        write_json(rdir / "out" / "summary.json", s)
    return s


def _dbstats_fallback(s):
    """지표 긁기에 적중률이 없으면 회차 전후 pg_stat_database 차이로 채운다."""
    pg = s["resources"].setdefault("postgres", {})
    for node, ds in (s["db"].get("dbstats") or {}).items():
        p = pg.setdefault(node, {})
        if p.get("hit_ratio_pct") is None and ds.get("hit_ratio_pct") is not None:
            p["hit_ratio_pct"] = ds["hit_ratio_pct"]
            p["hit_ratio_source"] = "dbstats(회차 전후)"
        if p.get("blks_read_delta") is None and ds.get("blks_read_delta") is not None:
            p["blks_read_delta"] = ds["blks_read_delta"]


def _route_tool(rows, win):
    """엔드포인트 키별 도구 쪽 p95 — 레이블이 여럿이 한 경로를 부르면 합친다."""
    by = defaultdict(list)
    for r in rows:
        if win.contains(r["ts"]):
            by[route_key(r["url"]) or r["label"]].append(r["elapsed"])
    return {k: {"count": len(v), "p95": rnd(percentile(v, 95)), "mean": rnd(sum(v) / len(v))} for k, v in by.items()}


# ---------------------------------------------------------------- [7.1] 시간 예산

def _pool_acquire(s):
    pools = (s["resources"].get("app_totals") or {}).get("pools") or {}
    vals = [p.get("acquire_p95_ms") or p.get("acquire_mean_ms") for p in pools.values()]
    vals = [v for v in vals if v is not None]
    return max(vals) if vals else None


def budget_for(s, route):
    """[7.1] — 요청마다 나눈 값(traces.decompose)으로. 구간별 p95 를 따로 구해 더하지 않는다.
    rows: 서로 겹치지 않는 구성 요소(summed=True) — 평균과 「p95 순위 요청 하나」의 분해.
    extra: 분포끼리의 차이(입구 · 큐)와 전 경로 풀 지표 — 합에 넣지 않는다."""
    tr = ((s.get("traces") or {}).get("routes") or {}).get(route)
    ng = ((s.get("nginx") or {}).get("routes") or {}).get(route)
    tool = (s.get("route_tool") or {}).get(route)
    tool_p95 = tool["p95"] if tool else None
    dc = (tr or {}).get("decomp") or {}
    mean_, req = dc.get("mean") or {}, dc.get("p95_request") or {}
    rows = []

    def add(key, layer, name, src):
        rows.append({"key": key, "layer": layer, "name": name, "source": src, "summed": True,
                     "mean_ms": mean_.get(key), "ms": req.get(key)})

    for m in dc.get("named_methods") or []:
        add("method:" + m, "앱", "Service — " + m, "메서드 구간(자기 시간)")
    for key, layer, name, src in traces.DECOMP_PARTS:
        add(key, layer, name, src)
    srv = req.get("server")
    for r in rows:
        r["share_pct"] = rnd(100.0 * r["ms"] / srv, 1) if r["ms"] is not None and srv else None
    total = sum(r["ms"] for r in rows if r["ms"] is not None) if req else None
    srv_p95 = ((tr or {}).get("server_ms") or {}).get("p95")
    urt = ng["urt_p95"] if ng else None
    extra = [
        {"layer": "입구", "name": "Nginx · 네트워크 (도구 p95 − urt p95)", "ms": rnd(tool_p95 - urt, 3) if tool_p95 is not None and urt is not None else None,
         "source": "분포 차이 — 합에 넣지 않음"},
        {"layer": "입구", "name": "슬롯 연결 (uct p95)", "ms": ng["uct_p95"] if ng else None, "source": "Nginx 로그 — 합에 넣지 않음"},
        {"layer": "앱", "name": "요청 큐 대기 추정 (urt p95 − 서버 구간 p95)", "ms": rnd(urt - srv_p95, 3) if urt is not None and srv_p95 is not None else None,
         "source": "분포 차이 — 합에 넣지 않음"},
        {"layer": "경계", "name": "커넥션 획득 대기 (전 경로 풀 지표 — 함수 자기 시간과 겹칠 수 있음)", "ms": _pool_acquire(s),
         "source": "hikaricp acquire — 합에 넣지 않음"},
    ]
    big = max((r for r in rows if r["ms"] is not None), key=lambda r: r["ms"], default=None)
    return {
        "route": route,
        "rows": rows,
        "extra": extra,
        "trace_id": req.get("trace_id"),
        "server_ms": srv,                       # p95 순위 요청의 서버 구간
        "server_mean_ms": mean_.get("server"),
        "sum_ms": rnd(total, 3) if total is not None else None,
        "unexplained_ms": req.get("unexplained"),
        "unexplained_pct": rnd(100.0 * req["unexplained"] / srv, 1) if req.get("unexplained") is not None and srv else None,
        "unexplained_mean_ms": mean_.get("unexplained"),
        "overlap_ms": req.get("overlap"),
        "tool_p95_ms": tool_p95,
        "server_p95_ms": srv_p95,
        "largest": {"layer": big["layer"], "name": big["name"]} if big else None,
    }


def budgets(s):
    routes = set(((s.get("traces") or {}).get("routes") or {}).keys()) | set((s.get("route_tool") or {}).keys())
    routes.discard(traces.SSE_ROUTE)   # 연결 시간이라 시간 예산이 없다
    order = sorted(routes, key=lambda r: -((s.get("route_tool") or {}).get(r, {}).get("count") or 0))
    return {r: budget_for(s, r) for r in order}


# ---------------------------------------------------------------- [7.11] 점유율

COMPONENTS = [
    ("db_ms", "DB 시간", "추적 SQL 구간"),
    ("db_queries", "DB 질의 수", "추적 SQL 구간"),
    ("threads", "Tomcat 스레드", "초당 요청 × 평균 응답(리틀)"),
    ("bytes", "응답 바이트", "Nginx 로그"),
    ("redis", "Redis 명령", "store 메서드 구간 수"),
    ("app_cpu", "앱 CPU", "미측정(엔드포인트별 CPU 원천 없음)"),
    ("conn_hold", "커넥션 점유", "추적 커밋 구간"),
]


def matrix(s):
    tr = (s.get("traces") or {}).get("routes") or {}
    ng = (s.get("nginx") or {}).get("routes") or {}
    routes = sorted(set(tr) | set(ng), key=lambda r: -((tr.get(r) or {}).get("requests") or (ng.get(r) or {}).get("count") or 0))
    if not routes:
        return None
    cols = routes[:MATRIX_COLS]
    raw = {
        "db_ms": {r: (tr.get(r) or {}).get("db_ms_sum") for r in routes},
        "db_queries": {r: (tr.get(r) or {}).get("sql_n_sum") for r in routes},
        "threads": {r: (tr.get(r) or {}).get("server_ms_sum") for r in routes},
        "bytes": {r: (ng.get(r) or {}).get("bytes_sum") for r in routes},
        "redis": {r: (tr.get(r) or {}).get("redis_calls_sum") for r in routes},
        "app_cpu": {r: None for r in routes},
        "conn_hold": {r: (tr.get(r) or {}).get("txn_hold_ms_sum") for r in routes},
    }
    rows = {}
    for key, label, src in COMPONENTS:
        vals = raw[key]
        tot = sum(v for v in vals.values() if v)
        row = {}
        for c in cols:
            v = vals.get(c)
            row[c] = rnd(100.0 * v / tot, 1) if v is not None and tot else None
        rest = [vals[r] for r in routes[MATRIX_COLS:] if vals.get(r) is not None]
        row["그 외"] = rnd(100.0 * sum(rest) / tot, 1) if rest and tot else (0.0 if tot and len(routes) <= MATRIX_COLS else None)
        rows[key] = {"label": label, "source": src, "shares": row, "total": tot or None}
    return {"columns": cols + ["그 외"], "rows": rows}


# ---------------------------------------------------------------- [1.4] 엔드포인트

def endpoints(s):
    j = s.get("jtl") or {}
    tr = (s.get("traces") or {}).get("routes") or {}
    ng = (s.get("nginx") or {}).get("routes") or {}
    mx = s.get("matrix") or {}
    out = []
    for label, st in (j.get("labels") or {}).items():
        r = st.get("route")
        t = tr.get(r) or {}
        n = ng.get(r) or {}
        fail = (st["p95"] is not None and st["p95"] > TARGET_P95_MS) or (st["rate_5xx"] or 0) > 1.0
        status = "빨강" if fail else ("회색" if st["p95"] is None else "통과(헤드룸 미측정)")
        # 주로 쓰는 자원 — 이 엔드포인트의 점유율이 가장 큰 구성 요소
        main = None
        if mx and r in mx.get("columns", []):
            cand = [(row["shares"].get(r), row["label"]) for row in mx["rows"].values() if row["shares"].get(r) is not None]
            if cand:
                v, lab = max(cand)
                main = f"{lab} {v}%"
        b = (s.get("budget") or {}).get(r) or {}
        out.append({
            "label": label, "route": r, "count": st["count"], "p50": st["p50"], "p95": st["p95"], "p99": st["p99"],
            "p99_usable": st["p99_usable"], "tail_note": st.get("tail_note"), "tps": st["tps"], "max": st["max"],
            "error_rate": st["error_rate"], "rate_5xx": st["rate_5xx"], "bytes_mean": st["bytes_mean"],
            "target_p95": TARGET_P95_MS, "status": status,
            "bottleneck_layer": (b.get("largest") or {}).get("layer"), "bottleneck_part": (b.get("largest") or {}).get("name"),
            "main_resource": main,
            "server_p95": (t.get("server_ms") or {}).get("p95"),
            "sql_per_request": (t.get("sql_per_request") or {}).get("mean"),
            "sql_per_request_max": (t.get("sql_per_request") or {}).get("max"),
            "db_ms_per_request": (t.get("db_ms_per_request") or {}).get("mean"),
            "redis_calls_per_request": t.get("redis_calls_per_request"),
            "per_row_methods": t.get("per_row_methods"),
            "repeated_statements": t.get("repeated_statements"),
            "nginx_bytes_mean": n.get("bytes_mean"), "nginx_bytes_p95": n.get("bytes_p95"),
        })
    return out


def _attach_pgss_routes(s):
    sr = (s.get("traces") or {}).get("statement_routes") or {}
    if not sr:
        return
    keys = list(sr.keys())
    for node, p in (s.get("db") or {}).get("pgss", {}).items():
        for r in p["top"]:
            k = traces.stmt_key(r["query"])
            hit = sr.get(k)
            if hit is None and k:
                # 앞부분만 같은 경우(잘린 문장)
                hit = next((sr[x] for x in keys if x[:60] == k[:60]), None)
            r["routes"] = hit[:3] if hit else None
    for r in (s.get("db") or {}).get("top_merged", []):
        k = traces.stmt_key(r["query"])
        r["routes"] = (sr.get(k) or [None])[:3] if sr.get(k) else None


# ---------------------------------------------------------------- 핵심 지표 · 반영 확인

def key_metrics(s):
    j = (s.get("jtl") or {}).get("overall") or {}
    res = s.get("resources") or {}
    nodes = res.get("nodes") or {}
    at = res.get("app_totals") or {}
    d = s.get("db") or {}
    tr = s.get("traces") or {}
    cpu = lambda n: ((nodes.get(n) or {}).get("cpu_pct") or {}).get("mean")
    cpu_max = lambda n: ((nodes.get(n) or {}).get("cpu_pct") or {}).get("max")
    db_cpus = [c for c in (cpu("db-01"), cpu("db-02")) if c is not None]
    app_cpus = [c for c in (cpu("app-01"), cpu("app-02")) if c is not None]
    pend = [p.get("pending_max") for p in (at.get("pools") or {}).values() if p.get("pending_max") is not None]
    holds = [((r.get("txn_hold_ms") or {}).get("p95")) for r in (tr.get("routes") or {}).values() if r.get("txn_hold_ms")]
    q_req = None
    if tr.get("server_requests"):
        q_req = rnd(sum(r["sql_n_sum"] for r in tr["routes"].values()) / tr["server_requests"], 3)
    rc = s.get("redis_commandstats") or {}
    return {
        "requests": j.get("count"),
        "p50": j.get("p50"), "p95": j.get("p95"), "p99": j.get("p99"),
        "tps": j.get("tps"), "error_rate": j.get("error_rate"), "rate_5xx": j.get("rate_5xx"),
        "db_cpu": max(db_cpus) if db_cpus else None,
        "db01_cpu": cpu("db-01"), "db02_cpu": cpu("db-02"),
        "db01_cpu_max": cpu_max("db-01"), "db02_cpu_max": cpu_max("db-02"),
        "app_cpu": max(app_cpus) if app_cpus else None,
        "total_db_ms": d.get("total_db_ms"),
        "db_ms_per_request": d.get("db_ms_per_request"),
        "queries_per_request": q_req if q_req is not None else d.get("queries_per_request"),
        "queries_per_request_source": "추적" if q_req is not None else ("질의 통계" if d.get("queries_per_request") is not None else None),
        "redis_calls_per_request": rc.get("calls_per_request"),
        "tomcat_busy_max": at.get("tomcat_busy_max"),
        "gc_pause_sec_per_sec": at.get("gc_pause_sec_per_sec_max"),
        "pool_pending_max": max(pend) if pend else None,
        "conn_hold_p95_ms": max(holds) if holds else None,
        "db_blks_read": sum((p.get("blks_read_delta") or 0) for p in (res.get("postgres") or {}).values()) if res.get("postgres") else None,
        "ebs_start_min": (s.get("credits") or {}).get("db_ebs_pre_min"),
    }


def checks(s):
    pg = (s.get("resources") or {}).get("postgres") or {}
    brt = [p.get("blk_read_time_delta") for p in pg.values() if p.get("blk_read_time_delta") is not None]
    return {
        "traces": bool((s.get("traces") or {}).get("spans")),
        "wait_events": bool((s.get("db") or {}).get("wait")),
        "blk_read_time_nonzero": (any(v > 0 for v in brt) if brt else None),
        "uct_urt": (s.get("nginx") or {}).get("uct_urt_present"),
        "credits": s.get("credits") is not None,
        "loadgen": s.get("loadgen") is not None,
        "profiler": s.get("profiler") is not None,
        "auto_explain": bool((s.get("db") or {}).get("auto_explain")),
    }
