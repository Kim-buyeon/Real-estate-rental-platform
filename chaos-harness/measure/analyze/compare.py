"""회차 비교 — 기준 회차 대비 변화와 흔들림(유의차 하한). [0.2](3) [7.10] · G3 · G6."""
from __future__ import annotations

import csv
from pathlib import Path

from .util import RESULTS_DIR, read_json, write_json, rnd, median

SIG_PATH = RESULTS_DIR / "significance.json"


def sig_path(fixture=False):
    """가짜 회차의 흔들림은 실제 하한을 덮어쓰지 않게 따로 둔다."""
    return RESULTS_DIR / "_fixture_significance.json" if fixture else SIG_PATH


def load_sig(fixture=False):
    return read_json(sig_path(fixture))

# (키, 이름, 낮을수록 좋은가)
KEYS = [
    ("p95", "전체 p95 (ms)", True),
    ("p99", "전체 p99 (ms, 참고)", True),
    ("tps", "달성 TPS", False),
    ("error_rate", "오류율 (%)", True),
    ("db01_cpu", "DB-01 CPU 평균 (%)", True),
    ("db02_cpu", "DB-02 CPU 평균 (%)", True),
    ("app_cpu", "앱 노드 CPU 평균 최대 (%)", True),
    ("total_db_ms", "DB 총 실행 시간 (ms)", True),
    ("db_ms_per_request", "요청당 DB 시간 (ms)", True),
    ("queries_per_request", "요청당 질의 수", True),
    ("redis_calls_per_request", "요청당 Redis 명령 수", True),
    ("tomcat_busy_max", "요청 스레드 최대", True),
    ("gc_pause_sec_per_sec", "GC 일시정지 (초/초)", True),
    ("pool_pending_max", "풀 대기 최대", True),
    ("conn_hold_p95_ms", "커넥션 점유 p95 (ms)", True),
    ("db_blks_read", "디스크 읽기 블록", True),
]

# 흔들림을 재는 네 지표 — 양식 [0.2](3)
JITTER = [("p95", "p95 (ms)"), ("tps", "달성 TPS"), ("db_cpu", "DB 노드 CPU"), ("error_rate", "오류율")]


def load_summary(round_name):
    return read_json(RESULTS_DIR / round_name / "out" / "summary.json")


def threshold(fixture=False):
    sig = load_sig(fixture)
    return sig.get("threshold_pct") if sig else None


def verdict(base, cur, lower_better, thr):
    if base is None or cur is None:
        return None, "미측정"
    if base == 0:
        if cur == 0:
            return 0.0, "변화없음"
        return None, ("악화" if (cur > 0) == lower_better else "개선")
    d = (cur - base) / abs(base) * 100.0
    if thr is None:
        return rnd(d, 1), "하한 미정"
    if abs(d) < thr:
        return rnd(d, 1), "변화없음"
    better = d < 0 if lower_better else d > 0
    return rnd(d, 1), ("개선" if better else "악화")


def compare(cur, base):
    thr = threshold(str(cur.get("folder", "")).startswith("_fixture"))
    ck, bk = cur.get("key") or {}, base.get("key") or {}
    rows = []
    for k, name, lb in KEYS:
        d, v = verdict(bk.get(k), ck.get(k), lb, thr)
        rows.append({"key": k, "name": name, "base": bk.get(k), "cur": ck.get(k), "delta_pct": d, "verdict": v})
    # 엔드포인트별 p95 — 레이블로 잇는다
    be = {e["label"]: e for e in base.get("endpoints") or []}
    eps = []
    for e in cur.get("endpoints") or []:
        b = be.get(e["label"])
        d, v = verdict(b["p95"] if b else None, e["p95"], True, thr)
        eps.append({"label": e["label"], "base_p95": b["p95"] if b else None, "cur_p95": e["p95"], "delta_pct": d, "verdict": v})
    return {"base_round": base.get("round"), "cur_round": cur.get("round"), "threshold_pct": thr, "metrics": rows, "endpoints": eps}


def jitter(rounds):
    """아무것도 고치지 않은 회차들 → 편차 = (최대 − 최소) ÷ 중위수, 하한 = 2 × 최대 편차."""
    sums = []
    for r in rounds:
        s = load_summary(r)
        if s is None:
            from .pipeline import analyze_round
            s = analyze_round(r)
        sums.append(s)
    metrics = {}
    devs = []
    for k, name in JITTER:
        vals = [(s.get("key") or {}).get(k) for s in sums]
        ok = [v for v in vals if v is not None]
        med = median(ok) if ok else None
        rng = (max(ok) - min(ok)) if ok else None
        dev = rnd(100.0 * rng / med, 2) if ok and med else None
        if dev is not None:
            devs.append(dev)
        metrics[k] = {"name": name, "values": vals, "median": rnd(med, 3), "min": min(ok) if ok else None,
                      "range": rnd(rng, 3), "dev_pct": dev}
    thr = rnd(2 * max(devs), 2) if devs else None
    out = {"rounds": rounds, "metrics": metrics, "threshold_pct": thr,
           "note": "편차 = (최대 − 최소) ÷ 중위수, 하한 = 2 × 최대 편차. 중위수가 0 인 지표(오류율 등)는 편차에서 뺀다"}
    out["path"] = str(sig_path(all(r.startswith("_fixture") for r in rounds)))
    write_json(Path(out["path"]), out)
    return out


def print_jitter(sig):
    rs = sig["rounds"]
    w = max(10, max(len(r) for r in rs) + 2)
    print(f"{'지표':<12}" + "".join(f"{r:>{w}}" for r in rs) + f"{'중위수':>10}{'최대-최소':>10}{'편차%':>8}")
    for k, m in sig["metrics"].items():
        vals = "".join(f"{('-' if v is None else round(v, 2)):>{w}}" for v in m["values"])
        print(f"{m['name']:<12}{vals}{str(m['median']):>10}{str(m['range']):>10}{str(m['dev_pct']):>8}")
    print(f"유의차 하한 = ± {sig['threshold_pct']} %  → {sig['path']}")


def round_order():
    """회차 순서 — results/rounds.csv(round 열)가 있으면 그것, 없으면 summary.json 이 있는 폴더 이름순."""
    p = RESULTS_DIR / "rounds.csv"
    if p.exists():
        with open(p, encoding="utf-8", newline="") as f:
            order = [r.get("round") for r in csv.DictReader(f) if r.get("round")]
        return [r for r in order if (RESULTS_DIR / r / "out" / "summary.json").exists()]
    return sorted(d.name for d in RESULTS_DIR.iterdir()
                  if d.is_dir() and d.name != "report" and (d / "out" / "summary.json").exists())


def all_summaries(include_fixtures=None):
    names = round_order()
    if include_fixtures is False:
        names = [n for n in names if not n.startswith("_fixture")]
    elif include_fixtures is True:
        names = [n for n in names if n.startswith("_fixture")]
    return [(n, load_summary(n)) for n in names]
