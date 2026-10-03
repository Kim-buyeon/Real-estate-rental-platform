"""JMeter 결과(.jtl, CSV) — 응답 시간의 정본. [0.4] [1.4] [7.10] · G1."""
from __future__ import annotations

import csv
from collections import defaultdict
from pathlib import Path

from .util import percentile, mean, rnd, route_key

P95_MIN = 100     # p95 를 쓸 수 있는 최소 표본
P99_MIN = 1000    # p99 를 쓸 수 있는 최소 표본
TARGET_P95_MS = 500
TS_BUCKET = 10    # 시계열 창(초)


def read_rows(path: Path):
    """CSV 를 읽어 필요한 열만 남긴다. 없는 선택 열은 None."""
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        for r in csv.DictReader(f):
            try:
                ts = int(float(r["timeStamp"])) / 1000.0
                el = float(r["elapsed"])
            except (KeyError, TypeError, ValueError):
                continue
            code = (r.get("responseCode") or "").strip()
            rows.append({
                "ts": ts,
                "elapsed": el,
                "label": r.get("label") or "?",
                "code": code,
                "success": (r.get("success") or "").strip().lower() == "true",
                "bytes": _num(r.get("bytes")),
                "threads": _num(r.get("allThreads")),
                "url": r.get("URL"),
                "latency": _num(r.get("Latency")),
                "connect": _num(r.get("Connect")),
            })
    rows.sort(key=lambda x: x["ts"])
    return rows


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _stats(rows, duration):
    el = [r["elapsed"] for r in rows]
    n = len(el)
    errs = sum(1 for r in rows if not r["success"])
    s5 = sum(1 for r in rows if r["code"][:1] == "5" and r["code"].isdigit())
    by = [r["bytes"] for r in rows if r["bytes"] is not None]
    st = {
        "count": n,
        "p50": rnd(percentile(el, 50)),
        "p95": rnd(percentile(el, 95)),
        "p99": rnd(percentile(el, 99)),
        "mean": rnd(mean(el)),            # 참고용 — 판정에 쓰지 않는다
        "max": rnd(max(el)) if el else None,
        "tps": rnd(n / duration, 3) if duration else None,
        "error_rate": rnd(100.0 * errs / n, 3) if n else None,
        "rate_5xx": rnd(100.0 * s5 / n, 3) if n else None,
        "bytes_mean": rnd(mean(by), 1),
        "p95_usable": n >= P95_MIN,
        "p99_usable": n >= P99_MIN,
    }
    if n < P95_MIN:
        st["tail_note"] = "참고(표본 부족) — p95 도 판정 불가"
    elif n < P99_MIN:
        st["tail_note"] = "p99 참고(표본 부족)"
    else:
        st["tail_note"] = None
    return st


def _timeseries(rows, start):
    """10초 창 시계열 — 워밍업 포함, 그래프에서 음영 처리한다."""
    if not rows:
        return []
    t0 = start if start is not None else rows[0]["ts"]
    buckets = defaultdict(list)
    for r in rows:
        buckets[int((r["ts"] - t0) // TS_BUCKET)].append(r)
    out = []
    for b in sorted(buckets):
        rs = buckets[b]
        el = [r["elapsed"] for r in rs]
        th = [r["threads"] for r in rs if r["threads"] is not None]
        errs = sum(1 for r in rs if not r["success"])
        out.append({
            "t": b * TS_BUCKET,
            "threads": max(th) if th else None,
            "tps": rnd(len(rs) / TS_BUCKET, 3),
            "p50": rnd(percentile(el, 50)),
            "p95": rnd(percentile(el, 95)),
            "p99": rnd(percentile(el, 99)),
            "error_rate": rnd(100.0 * errs / len(rs), 3),
        })
    return out


def saturation(overall, labels, ts, warmup):
    """포화 판정 보조 — 시험 계획서 2.3 의 세 조건. 판정은 사람이 [1.4] 에서 한다."""
    p95_over = [k for k, v in labels.items() if v["p95"] is not None and v["p95"] > TARGET_P95_MS]
    win = [w for w in ts if w["t"] >= (warmup or 0)]
    plateau = None
    detail = None
    if len(win) >= 3:
        a, c = win[-3], win[-1]
        if a["tps"] and a["threads"] and c["threads"] is not None:
            tps_g = (c["tps"] - a["tps"]) / a["tps"] * 100
            thr_g = (c["threads"] - a["threads"]) / a["threads"] * 100
            plateau = tps_g < 5 and thr_g > 10
            detail = {"tps_growth_pct": rnd(tps_g, 1), "threads_growth_pct": rnd(thr_g, 1)}
    return {
        "p95_over_500": bool(p95_over) or (overall["p95"] or 0) > TARGET_P95_MS,
        "p95_over_labels": p95_over,
        "rate_5xx_over_1pct": (overall["rate_5xx"] or 0) > 1.0,
        "throughput_plateau": plateau,
        "plateau_detail": detail,
    }


def analyze(path: Path, window):
    rows_all = read_rows(path)
    if not rows_all:
        return None, []
    if window.start is None:
        window.start = rows_all[0]["ts"]
    if window.end is None:
        window.end = rows_all[-1]["ts"] + rows_all[-1]["elapsed"] / 1000.0
    rows = [r for r in rows_all if window.contains(r["ts"])]
    dur = window.duration
    overall = _stats(rows, dur)
    by_label = defaultdict(list)
    for r in rows:
        by_label[r["label"]].append(r)
    labels = {}
    for lab, rs in sorted(by_label.items()):
        st = _stats(rs, dur)
        # 레이블 → 엔드포인트 키 (가장 흔한 URL 경로)
        keys = defaultdict(int)
        for r in rs:
            k = route_key(r["url"])
            if k:
                keys[k] += 1
        st["route"] = max(keys, key=keys.get) if keys else None
        labels[lab] = st
    ts = _timeseries([r for r in rows_all if window.in_round(r["ts"])], window.start)
    usable = "p99" if overall["count"] >= P99_MIN else ("p95" if overall["count"] >= P95_MIN else "참고(표본 부족)")
    res = {
        "requests": overall["count"],
        "duration_sec": rnd(dur, 1),
        "usable_tail": usable,
        "overall": overall,
        "labels": labels,
        "timeseries": ts,
        "saturation": saturation(overall, labels, ts, window.warmup),
    }
    return res, rows
