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


def _timeseries(rows, start, end):
    """10초 창 시계열 — 워밍업 포함, 그래프에서 음영 처리한다.
    창의 TPS 는 그 창이 실제로 덮은 길이로 나눈다(마지막 창은 짧다). covered 가 창의 절반 미만이면 partial."""
    if not rows:
        return []
    t0 = start if start is not None else rows[0]["ts"]
    t1 = end if end is not None else rows[-1]["ts"]
    buckets = defaultdict(list)
    for r in rows:
        buckets[int((r["ts"] - t0) // TS_BUCKET)].append(r)
    out = []
    for b in sorted(buckets):
        rs = buckets[b]
        el = [r["elapsed"] for r in rs]
        th = [r["threads"] for r in rs if r["threads"] is not None]
        errs = sum(1 for r in rs if not r["success"])
        covered = max(min(TS_BUCKET, t1 - (t0 + b * TS_BUCKET)), 1e-3)
        out.append({
            "t": b * TS_BUCKET,
            "covered_sec": rnd(covered, 2),
            "partial": covered < TS_BUCKET * 0.5,
            "threads": max(th) if th else None,
            "tps": rnd(len(rs) / covered, 3),
            "p50": rnd(percentile(el, 50)),
            "p95": rnd(percentile(el, 95)),
            "p99": rnd(percentile(el, 99)),
            "error_rate": rnd(100.0 * errs / len(rs), 3),
        })
    return out


def saturation(overall, labels, ts, warmup):
    """포화 판정 보조 — 시험 계획서 2.3 의 세 조건. 판정은 사람이 [1.4] 에서 한다."""
    p95_over = [k for k, v in labels.items() if v["p95"] is not None and v["p95"] > TARGET_P95_MS]
    # 워밍업 창과 절반도 안 덮은 마지막 창은 정체 판정에서 뺀다
    win = [w for w in ts if w["t"] >= (warmup or 0) and not w.get("partial")]
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
    """통계 구간은 JMeter 가 정한다 — 시작 = 첫 표본 시작(timeStamp, 5.6.3 기본 jmeter.properties 의
    sampleresult.timestamp.start=true), 끝 = 마지막 (timeStamp + elapsed). 워밍업은 이 시작부터 센다.
    window 를 그 값으로 고친다 — 다른 원자료가 모두 같은 구간을 쓴다. 기록(meta) 구간은 파일 범위만 정한다."""
    rows_all = read_rows(path)
    rec_lo, rec_hi = window.start, window.end
    rows_all = [r for r in rows_all if (rec_lo is None or r["ts"] >= rec_lo) and (rec_hi is None or r["ts"] <= rec_hi)]
    if not rows_all:
        return None, []
    window.start = rows_all[0]["ts"]
    window.end = max(r["ts"] + r["elapsed"] / 1000.0 for r in rows_all)
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
    ts = _timeseries(rows_all, window.start, window.end)
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
