"""단계별 분해 — 계단 부하(열린 모델 RPS 계단)의 단계마다 응답 시간 · 구성 요소 · 자원. [0.4](2) [7.2] [7.5](3)(6) · G1.

입력: results/<회차>/steps.json (JMeter 실행 스크립트 run.sh 가 쓴다)
    {"start_epoch_ms": <회차 첫 표본 시각 ms — 「 setup」 표본 제외>, "launch_epoch_ms": <JMeter 띄운 시각>, "start_basis": "<무엇으로 정했나>",
     "profile": "<load_profile 문자열>",
     "steps": [{"index": 1, "target_rps": 10.0, "from_s": 0, "to_s": 180}, ...]}     from · to 는 start 기준 초
    선형(line) 단계는 "ramp_from_rps" 가 붙고 target_rps 는 끝 값이다 — 정체 판정은 단계 평균 목표((처음 + 끝) ÷ 2)와 견준다.
    고정 부하 회차(단계 하나)도 같은 모양 — 단계 하나로 나오고 포화 · 급등은 판정하지 않는 것과 같다(비교할 앞 단계가 없다).
없으면 jtl 의 10초 창 하나를 단계 하나로 본다(목표 RPS 없음 — 정체 판정은 스레드 증가 대비 TPS 로).

단계 구간은 통계 구간(워밍업 뒤 ~ JMeter 끝)으로 자른다 — 워밍업은 첫 단계에서 빠진다.
"""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from .jtl import TS_BUCKET, TARGET_P95_MS, P95_MIN
from .util import read_json, parse_time, percentile, mean, rnd, route_key

# 아래 넷은 집계 규칙이다 — 시험 계획서 2.3 은 「목표 RPS 를 따라가지 못하고 정체」라고만 쓰고 수치를 두지 않는다.
# 그래서 이 판정은 보조다(보고서에 「보조 판정」으로 적는다). 확정은 G1 을 보고 사람이 한다. 바꾸면 analyze/README.md 도 고친다
SPIKE_RATIO = 2.0          # 앞 단계 대비 p95 가 이 배를 넘으면 급등 지점(#390 계획)
LAG_RATIO = 0.9            # 실제 RPS 가 목표의 이 비율 아래면 「못 따라감」 — 열린 모델도 실제가 목표보다 조금 낮게 나온다
PLATEAU_GROWTH_PCT = 5.0   # 앞 단계 대비 실제 RPS 증가가 이보다 작으면 「정체」(jtl.saturation 과 같은 값)
LEVEL_TOLERANCE = 0.25     # [7.2] 「목표 × 2」 단계를 찾을 때 허용하는 어긋남 — 넘으면 그 줄은 「해당 단계 없음」

# 단계마다의 구성 요소 평균(ms/요청) — [7.1] 과 같은 나눔. summed=False 는 서버 구간 합에 넣지 않는 줄
COMPONENTS = [
    ("bcrypt", "앱", "BCrypt 대조 (메서드 자기 시간)", True),
    ("methods_other", "앱", "그 밖의 메서드 자기 시간", True),
    ("sql", "DB", "질의 실행 (SQL 구간 합집합)", True),
    ("redis", "캐시", "Redis 왕복 (store 메서드 자기 시간)", True),
    ("external", "외부", "외부 호출", True),
    ("other_spans", "앱", "기타 계측 구간 자기 시간", True),
    ("unexplained", "설명 안 됨", "설명되지 않은 시간", True),
    ("queue", "앱", "요청 큐 대기 (urt 평균 − 서버 구간 평균, 합 밖)", False),
    ("pool_wait", "경계", "커넥션 획득 대기 (풀 지표 평균, 합 밖)", False),
]
COMP_META = {k: (layer, name) for k, layer, name, _ in COMPONENTS}


# ---------------------------------------------------------------- 단계 경계

def boundaries(rdir: Path, win, jrows):
    """[{"index", "target_rps", "lo", "hi", "lo_raw", "hi_raw", "from_rel_s"}] 와 근거. lo · hi 는 통계 구간으로 자른 값."""
    sj = read_json(rdir / "steps.json")
    out = []
    basis, profile, t0 = None, None, None
    if sj and sj.get("steps") and sj.get("start_epoch_ms") is not None:
        t0 = parse_time(sj["start_epoch_ms"])
        basis, profile = "steps.json", sj.get("profile")
        for i, st in enumerate(sj["steps"]):
            try:
                lo_raw, hi_raw = t0 + float(st["from_s"]), t0 + float(st["to_s"])
            except (KeyError, TypeError, ValueError):
                continue
            out.append({"index": st.get("index", i + 1), "target_rps": st.get("target_rps"), "ramp_from_rps": st.get("ramp_from_rps"),
                        "lo_raw": lo_raw, "hi_raw": hi_raw})
    elif jrows and win.start is not None and win.end is not None:
        basis = f"jtl {TS_BUCKET}초 창(steps.json 없음)"
        k = 0
        while win.start + k * TS_BUCKET < win.end:
            out.append({"index": k + 1, "target_rps": None, "lo_raw": win.start + k * TS_BUCKET,
                        "hi_raw": min(win.start + (k + 1) * TS_BUCKET, win.end)})
            k += 1
    for st in out:
        st["lo"] = max(st["lo_raw"], win.lo) if win.lo is not None else st["lo_raw"]
        st["hi"] = min(st["hi_raw"], win.hi) if win.hi is not None else st["hi_raw"]
        st["duration_sec"] = rnd(max(st["hi"] - st["lo"], 0.0), 1)
        st["from_rel_s"] = rnd(st["lo_raw"] - win.start, 1) if win.start is not None else None
        # 창의 절반도 못 덮은 단계(마지막 조각 · 워밍업에 거의 먹힌 단계)는 판정에서 뺀다
        st["partial"] = (st["hi"] - st["lo"]) < 0.5 * (st["hi_raw"] - st["lo_raw"])
    return out, {"basis": basis, "profile": profile, "start_epoch": t0,
                 "start_basis": (sj or {}).get("start_basis"), "launch_epoch": parse_time((sj or {}).get("launch_epoch_ms"))}


# ---------------------------------------------------------------- 단계 하나

def _in(t, st):
    return st["lo"] <= t <= st["hi"]


def _components(ps, ngx, pool_ms):
    """요청 분해 목록 → 구성 요소 평균. 요청마다 나눈 뒤 평균한다([7.1] 과 같다)."""
    if not ps:
        return None
    acc = defaultdict(float)
    for p in ps:
        meth = sum(p["method_self"].values())
        bc = p.get("bcrypt_ms") or 0.0
        parts = {"bcrypt": bc, "methods_other": max(meth - bc, 0.0), "sql": p["sql_ms"], "redis": p["store_ms"],
                 "external": p["ext_ms"], "other_spans": p["other_self_ms"]}
        parts["unexplained"] = max(p["dur"] - sum(parts.values()), 0.0)
        for k, v in parts.items():
            acc[k] += v
        acc["server"] += p["dur"]
    n = len(ps)
    out = {k: rnd(v / n, 3) for k, v in acc.items()}
    urt = [r["urt"] * 1000 for r in ngx if isinstance(r.get("urt"), float)]
    out["queue"] = rnd(mean(urt) - out["server"], 3) if urt else None
    out["pool_wait"] = pool_ms
    return out


def _top_functions(ps, n=5):
    tot = defaultdict(float)
    for p in ps:
        for k, v in p["method_self"].items():
            tot[k] += v
    srv = sum(p["dur"] for p in ps)
    return [{"method": k, "mean_ms": rnd(v / len(ps), 3), "share_pct": rnd(100.0 * v / srv, 1) if srv else None}
            for k, v in sorted(tot.items(), key=lambda x: -x[1])[:n]] if ps else []


def _statements(ps):
    d = defaultdict(list)
    for p in ps:
        for k, dur, _ in p["sql_list"]:
            if k:
                d[k].append(dur)
    return {k: {"calls": len(v), "p50_ms": rnd(percentile(v, 50), 3), "p95_ms": rnd(percentile(v, 95), 3)} for k, v in d.items()}


def _resources(pst):
    """prom.analyze 의 단계 집계 → 노드 CPU · 슬롯 자원 요약."""
    if not pst:
        return {"nodes": {}, "slots": {}, "pool_pending_max": None, "pool_acquire_mean_ms": None, "restarts": None}
    pend, acq, rst = [], [], []
    for s in pst["slots"].values():
        for p in (s.get("pools") or {}).values():
            if p.get("pending_max") is not None:
                pend.append(p["pending_max"])
            if p.get("acquire_mean_ms") is not None:
                acq.append(p["acquire_mean_ms"])
        if s.get("restarts") is not None:
            rst.append(s["restarts"])
    return {"nodes": pst["nodes"], "slots": pst["slots"],
            "pool_pending_max": max(pend) if pend else None,
            "pool_acquire_mean_ms": max(acq) if acq else None,
            "restarts": sum(rst) if rst else None}


def first_resource(res):
    """그 단계에서 사용률이 가장 높은 자원 — (이름, 계층, %). 노드 CPU(OS) · 요청 스레드(앱) · 풀 활성(경계)."""
    cand = []
    for node, v in (res.get("nodes") or {}).items():
        if v.get("cpu_mean") is not None:
            cand.append((v["cpu_mean"], f"{node} CPU (평균)", "OS"))
    for key, s in (res.get("slots") or {}).items():
        if s.get("busy_max") is not None and s.get("tomcat_max"):
            cand.append((100.0 * s["busy_max"] / s["tomcat_max"], f"{key} 요청 스레드 (최대)", "앱"))
        for pool, p in (s.get("pools") or {}).items():
            if p.get("active_max") is not None and p.get("max"):
                pct = 100.0 * p["active_max"] / p["max"]
                name = f"{key} 풀 {pool} 활성 (최대)" + (f" · 대기 {p['pending_max']:.0f}" if p.get("pending_max") else "")
                cand.append((pct, name, "경계"))
    if not cand:
        return None
    v, name, layer = max(cand, key=lambda x: x[0])
    return {"name": name, "layer": layer, "pct": rnd(v, 1)}


def largest_component(comp):
    if not comp:
        return None
    srv = comp.get("server")
    cand = [(comp.get(k), k) for k, _, _, summed in COMPONENTS if summed and comp.get(k) is not None]
    if not cand:
        return None
    v, k = max(cand)
    layer, name = COMP_META[k]
    return {"key": k, "name": name, "layer": layer, "mean_ms": v, "share_pct": rnd(100.0 * v / srv, 1) if srv else None}


def _route_part(rows, ps, ng, dur, pool_ms):
    """한 단계 안 엔드포인트 하나 — 응답(jtl) · 구성 요소(추적) · 함수 상위. 자원 지표는 엔드포인트로 나눌 원천이 없어 넣지 않는다."""
    el = [r["elapsed"] for r in rows]
    n = len(rows)
    s5 = sum(1 for r in rows if r["code"][:1] == "5" and r["code"].isdigit())
    comp = _components(ps, ng, pool_ms)
    return {"count": n, "actual_rps": rnd(n / dur, 3) if n else 0.0,
            "p50": rnd(percentile(el, 50)), "p95": rnd(percentile(el, 95)), "p99": rnd(percentile(el, 99)),
            "p95_usable": n >= P95_MIN, "rate_5xx": rnd(100.0 * s5 / n, 3) if n else None, "count_5xx": s5,
            "traced_requests": len(ps), "components": comp, "top_functions": _top_functions(ps),
            "largest_component": largest_component(comp)}


def by_route(rows, ps, ng, dur, pool_ms):
    """엔드포인트 키별로 나눈 단계 — 혼합 회차는 엔드포인트마다, 단독 회차는 그 하나. 키는 jtl URL · 추적 route · Nginx 경로가
    같은 route_key 로 모인 것(레이블이 아니라 경로)."""
    jr, pr, nr = defaultdict(list), defaultdict(list), defaultdict(list)
    for r in rows:
        jr[route_key(r["url"]) or r["label"]].append(r)
    for p in ps:
        pr[p.get("route")].append(p)
    for r in ng:
        nr[route_key(r["path"])].append(r)
    return {k: _route_part(v, pr.get(k, []), nr.get(k, []), dur, pool_ms) for k, v in sorted(jr.items(), key=lambda kv: -len(kv[1]))}


def one_step(st, jrows, profiles, ngx, pst):
    rows = [r for r in jrows if _in(r["ts"], st)]
    el = [r["elapsed"] for r in rows]
    n = len(rows)
    dur = max(st["hi"] - st["lo"], 1e-9)
    s5 = sum(1 for r in rows if r["code"][:1] == "5" and r["code"].isdigit())
    errs = sum(1 for r in rows if not r["success"])
    ps = [p for p in profiles if _in(p["t"], st)]
    # Nginx 로그 시각($time_local)은 초 단위라 「끝 시각 − rt」로 되돌린 시작이 실제보다 최대 1초 이르다 — 단계 끝 1초는 빼서
    # 다음 단계(부하가 다른) 요청이 섞이지 않게 한다
    ng = [r for r in ngx if st["lo"] <= r["start"] <= st["hi"] - 1.0]
    res = _resources(pst)
    comp = _components(ps, ng, res["pool_acquire_mean_ms"])
    th = [r["threads"] for r in rows if r.get("threads") is not None]
    return {
        "index": st["index"], "target_rps": st["target_rps"], "ramp_from_rps": st.get("ramp_from_rps"),
        # 비교에 쓰는 목표 — 선형(line) 단계는 처음 → 끝 사이를 고르게 올리므로 단계 평균 목표 = (처음 + 끝) ÷ 2
        "target_mean_rps": rnd((st["ramp_from_rps"] + st["target_rps"]) / 2.0, 3)
        if st.get("ramp_from_rps") is not None and st.get("target_rps") is not None else st["target_rps"],
        "from_rel_s": st["from_rel_s"], "duration_sec": st["duration_sec"],
        "partial": st["partial"],
        "count": n, "actual_rps": rnd(n / dur, 3) if n else 0.0,
        "p50": rnd(percentile(el, 50)), "p95": rnd(percentile(el, 95)), "p99": rnd(percentile(el, 99)),
        "p95_usable": n >= P95_MIN,
        "rate_5xx": rnd(100.0 * s5 / n, 3) if n else None, "count_5xx": s5,
        "error_rate": rnd(100.0 * errs / n, 3) if n else None,
        "threads_max": max(th) if th else None,
        "traced_requests": len(ps),
        "components": comp,
        "top_functions": _top_functions(ps),
        "statements": _statements(ps),
        "resources": res,
        "first_resource": first_resource(res),
        "largest_component": largest_component(comp),
        "routes": by_route(rows, ps, ng, dur, res["pool_acquire_mean_ms"]),
    }


# ---------------------------------------------------------------- 판정

def _growth(a, b):
    return (b - a) / a * 100.0 if a else None


def saturation(steps):
    """[0.4](2) — 세 조건 중 먼저 닿는 단계. 단계마다 conditions 를 붙이고 첫 단계를 돌려준다."""
    prev = None
    first = None
    for s in steps:
        if not s["count"] or s["partial"]:
            continue
        cond = []
        # 표본이 모자란 단계의 p95 는 판정에 쓰지 않는다([0.4](5) — 수백 건 미만이면 꼬리가 몇 건에 좌우된다)
        if s["p95"] is not None and s["p95"] > TARGET_P95_MS and s.get("p95_usable"):
            cond.append("p95 500 ms 초과")
        if (s["rate_5xx"] or 0) > 1.0:
            cond.append("5xx 1% 초과")
        if s["target_rps"]:
            lag = s["actual_rps"] < LAG_RATIO * (s.get("target_mean_rps") or s["target_rps"])
            g = _growth(prev["actual_rps"], s["actual_rps"]) if prev else None
            # 정체는 앞 단계와 견줘야 성립한다 — 첫 단계 하나만으로는 「못 따라감」이 워밍업 · 계단 시작 어긋남과 구분되지
            # 않는다(#390 U02: 1단계 5 RPS 목표에 3.55, p95 33 ms 를 포화로 오판)
            if lag and g is not None and g < PLATEAU_GROWTH_PCT:
                cond.append("처리량이 목표를 못 따라가고 정체")
        elif prev and prev.get("threads_max") and s.get("threads_max") is not None:
            g, tg = _growth(prev["actual_rps"], s["actual_rps"]), _growth(prev["threads_max"], s["threads_max"])
            if g is not None and tg is not None and g < PLATEAU_GROWTH_PCT and tg > 10:
                cond.append("처리량 정체(스레드 증가 대비)")
        s["saturation_conditions"] = cond
        if cond and first is None:
            first = {"index": s["index"], "conditions": cond, "target_rps": s["target_rps"], "actual_rps": s["actual_rps"],
                     "p95": s["p95"], "rate_5xx": s["rate_5xx"]}
        prev = s
    return first


def spikes(steps):
    """앞 단계 대비 p95 가 2배 넘게 뛴 단계와 그때 가장 많이 늘어난 구성 요소 · 함수."""
    out = []
    usable = [s for s in steps if s["count"] and not s["partial"]]
    for a, b in zip(usable, usable[1:]):
        if not a["p95"] or b["p95"] is None or b["p95"] <= SPIKE_RATIO * a["p95"]:
            continue
        ca, cb = a["components"] or {}, b["components"] or {}
        deltas = [((cb.get(k) or 0) - (ca.get(k) or 0), k) for k, _, _, _ in COMPONENTS if cb.get(k) is not None]
        comp = max(deltas) if deltas else None
        fa = {f["method"]: f["mean_ms"] for f in a["top_functions"]}
        fd = [((f["mean_ms"] or 0) - (fa.get(f["method"]) or 0), f["method"]) for f in b["top_functions"]]
        fn = max(fd) if fd else None
        out.append({"index": b["index"], "prev_index": a["index"], "p95_prev": a["p95"], "p95": b["p95"],
                    "ratio": rnd(b["p95"] / a["p95"], 2),
                    "component": COMP_META[comp[1]][1] if comp else None, "component_layer": COMP_META[comp[1]][0] if comp else None,
                    "component_delta_ms": rnd(comp[0], 3) if comp else None,
                    "function": fn[1] if fn else None, "function_delta_ms": rnd(fn[0], 3) if fn else None})
    return out


def _pick(steps, rps, by_target=True, tol=None):
    """목표(또는 실제) RPS 가 rps 에 가장 가까운 단계."""
    cand = []
    for s in steps:
        if not s["count"]:
            continue
        v = s["target_rps"] if by_target and s["target_rps"] else s["actual_rps"]
        if v:
            cand.append((abs(v - rps), v, s))
    if not cand:
        return None
    d, v, s = min(cand, key=lambda x: x[0])
    if tol is not None and rps and d > tol * rps:
        return None
    return s


def _level(label, s):
    if not s:
        return {"label": label, "index": None}
    fr, lc = s.get("first_resource"), s.get("largest_component")
    return {"label": label, "index": s["index"], "target_rps": s["target_rps"], "actual_rps": s["actual_rps"], "p95": s["p95"],
            "resource": fr, "component": lc,
            "text": " · ".join(x for x in (
                f"{fr['name']} {fr['pct']}% ({fr['layer']})" if fr else None,
                f"시간 최대 {lc['name']} {lc['share_pct']}% ({lc['layer']})" if lc and lc.get("share_pct") is not None else None) if x)
            or None}


def analyze(rdir: Path, win, jrows, profiles, ngx_rows, prom_steps, meta, bounds=None, info=None):
    """bounds · info — boundaries() 결과를 이미 구했으면 넘긴다(지표 집계가 단계 경계를 먼저 쓴다 — pipeline)."""
    if bounds is None:
        bounds, info = boundaries(rdir, win, jrows)
    if not bounds:
        return None
    steps = [one_step(st, jrows, profiles, ngx_rows, (prom_steps or {}).get(str(st["index"]))) for st in bounds]
    sat = saturation(steps)
    spk = spikes(steps)
    usable = [s for s in steps if s["count"] and not s["partial"]]
    # 한계 지점 = 포화 단계 바로 앞 단계(포화가 없으면 마지막 단계 — 한계 미도달)
    limit, reached = None, False
    if sat:
        pos = [u["index"] for u in usable].index(sat["index"])
        limit = usable[pos - 1] if pos > 0 else None          # 첫 단계부터 포화면 한계는 이 회차 범위 아래
        reached = True
    elif usable:
        limit = usable[-1]
    target = meta.get("target_rps")
    target_basis = "round.json target_rps" if target else None
    if not target and usable:
        target = usable[0]["target_rps"] or usable[0]["actual_rps"]
        target_basis = "첫 단계 목표" if usable[0]["target_rps"] else "첫 단계 실제"
    over = next((s for s in usable if sat and s["index"] == sat["index"]), None)
    levels = [_level(f"목표 ({target:g} TPS)" if target else "목표", _pick(usable, target) if target else None),
              _level("목표 × 2", _pick(usable, 2 * target, tol=LEVEL_TOLERANCE) if target else None),
              _level("한계 지점", limit), _level("한계 초과", over)]
    p95_at = []
    if limit and limit["actual_rps"]:
        li = next(i for i, u in enumerate(usable) if u is limit)
        cand = usable[:li + 1]
        for f in (0.5, 0.8, 1.0):
            s = limit if f == 1.0 else _pick(cand, f * limit["actual_rps"], by_target=False)
            p95_at.append({"fraction": f, "index": s["index"] if s else None, "actual_rps": s["actual_rps"] if s else None,
                           "p95": s["p95"] if s else None})
    col = next((s for s in usable if (s["count_5xx"] or 0) > 0 or (s["resources"].get("restarts") or 0) > 0), None)
    collapse = None
    if col:
        why = [w for w, c in (("5xx", (col["count_5xx"] or 0) > 0), ("슬롯 재시작", (col["resources"].get("restarts") or 0) > 0)) if c]
        collapse = {"index": col["index"], "target_rps": col["target_rps"], "actual_rps": col["actual_rps"], "reason": " · ".join(why)}
    lim_rps = limit["actual_rps"] if limit else None
    # 엔드포인트별 — 혼합 회차에서 어느 엔드포인트가 먼저 넘었나. 목표 RPS 가 엔드포인트마다 없어 정체 조건은 쓰지 않는다
    # (p95 500 ms · 5xx 1% 만). 한계 = 그 앞 단계의 그 엔드포인트 실제 RPS
    routes = {}
    for r in sorted({k for s in usable for k in (s.get("routes") or {})}):
        prev, hit = None, None
        for s in usable:
            x = (s.get("routes") or {}).get(r)
            if not x or not x["count"]:
                continue
            cond = [c for c, ok in (("p95 500 ms 초과", x["p95"] is not None and x["p95"] > TARGET_P95_MS),
                                    ("5xx 1% 초과", (x["rate_5xx"] or 0) > 1.0)) if ok]
            if cond:
                hit = {"index": s["index"], "conditions": cond, "actual_rps": x["actual_rps"], "p95": x["p95"],
                       "p95_usable": x["p95_usable"], "limit_index": prev[0] if prev else None,
                       "limit_rps": prev[1] if prev else None, "largest_component": x["largest_component"]}
                break
            prev = (s["index"], x["actual_rps"])
        routes[r] = hit or {"index": None, "limit_index": prev[0] if prev else None, "limit_rps": prev[1] if prev else None}
    return {
        "basis": info["basis"], "profile": info["profile"], "start_epoch": info["start_epoch"],
        "start_basis": info.get("start_basis"), "launch_epoch": info.get("launch_epoch"),
        "target_rps": target, "target_basis": target_basis,
        "steps": steps,
        "saturation": sat,
        "spikes": spk,
        "limit": {"index": limit["index"], "actual_rps": limit["actual_rps"], "target_rps": limit["target_rps"], "p95": limit["p95"],
                  "reached": reached} if limit else None,
        "levels": levels,
        "p95_at": p95_at,
        "collapse": collapse,
        "collapse_gap_rps": rnd(collapse["actual_rps"] - lim_rps, 3) if collapse and lim_rps is not None else None,
        "route_saturation": routes,
    }
