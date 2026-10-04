"""명령 진입점.

    python -m analyze <ROUND> [--baseline <ROUND>] [--report]
    python -m analyze jitter R01 R02 R03
    python -m analyze fixtures
"""
from __future__ import annotations

import argparse
import sys

from . import compare, graphs, pipeline
from .util import RESULTS_DIR, read_json, write_json


def run_round(name, baseline=None, report=False):
    s = pipeline.analyze_round(name, write=False)
    base = None
    if baseline:
        base = compare.load_summary(baseline) or pipeline.analyze_round(baseline)
        s["comparison"] = compare.compare(s, base)
    rdir = RESULTS_DIR / name
    write_json(rdir / "out" / "summary.json", s)
    fixtures = name.startswith("_fixture")
    summaries = compare.all_summaries(include_fixtures=fixtures)
    sig = compare.load_sig(fixtures)
    contrib = read_json(RESULTS_DIR / "contrib.json")
    s["graphs"] = graphs.render_all(s, base, summaries, sig, contrib, rdir / "out" / "graphs")
    write_json(rdir / "out" / "summary.json", s)
    k = s["key"]
    print(f"[{s['round']}] 요청 {k['requests']} · p95 {k['p95']} ms · TPS {k['tps']} · 오류율 {k['error_rate']}% · "
          f"DB CPU {k['db_cpu']}% → {rdir / 'out' / 'summary.json'}")
    miss = s["resources"].get("missing_metrics") or []
    if miss:
        print(f"  없는 지표 {len(miss)}개: " + ", ".join(miss[:8]) + (" …" if len(miss) > 8 else ""))
    st = s.get("steps") or {}
    if st.get("basis") == "steps.json":
        sat = st.get("saturation")
        print(f"  단계 {len(st['steps'])}개 · 포화(보조) " + (f"단계 {sat['index']} 실제 {sat['actual_rps']} TPS · p95 {sat['p95']} ms — {' · '.join(sat['conditions'])}"
                                                     if sat else "닿지 않음"))
    if s.get("comparison"):
        for m in s["comparison"]["metrics"][:6]:
            print(f"  {m['name']}: {m['base']} → {m['cur']} ({m['delta_pct']}%, {m['verdict']})")
    if report:
        from . import report as rep
        pdf = rep.build(s, base)
        print("보고서:", pdf)
    return s


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "jitter":
        if len(argv) < 3:
            raise SystemExit("사용: python -m analyze jitter R01 R02 R03")
        sig = compare.jitter(argv[1:])
        compare.print_jitter(sig)
        return
    if argv and argv[0] == "fixtures":
        from . import fixtures
        fixtures.main()
        return
    ap = argparse.ArgumentParser(prog="python -m analyze", description="부하 시험 회차 집계 · 그래프 · 보고서")
    ap.add_argument("round", help="results/ 아래 회차 폴더 이름")
    ap.add_argument("--baseline", help="비교할 기준 회차")
    ap.add_argument("--report", action="store_true", help="보고서 HTML · PDF 를 만든다")
    a = ap.parse_args(argv)
    run_round(a.round, a.baseline, a.report)


if __name__ == "__main__":
    main()
