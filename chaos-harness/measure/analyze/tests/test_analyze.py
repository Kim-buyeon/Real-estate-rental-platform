"""집계 시험 — 단위(파서 · 산식)와 가짜 회차 전체.

    cd chaos-harness/measure && python -m unittest discover -s analyze/tests -t .
"""
from __future__ import annotations

import csv
import gzip
import tempfile
import unittest
from pathlib import Path

from analyze import compare, misc, nginx, pipeline, prom, report, traces, db
from analyze.util import percentile, route_key, Window, parse_time, RESULTS_DIR


class Units(unittest.TestCase):
    def test_percentile_linear(self):
        vals = list(range(1, 101))
        self.assertAlmostEqual(percentile(vals, 95), 95.05)
        self.assertAlmostEqual(percentile(vals, 50), 50.5)
        self.assertEqual(percentile([7], 99), 7.0)
        self.assertIsNone(percentile([], 95))

    def test_route_key(self):
        self.assertEqual(route_key("/api/properties/123/risk?x=1"), "properties/{id}/risk")
        self.assertEqual(route_key("/api/properties/{propertyId}/risk"), "properties/{id}/risk")
        self.assertEqual(route_key("https://1.2.3.4/api/map/clusters?a=b"), "map/clusters")

    def test_parse_sample(self):
        name, labels, v = prom.parse_sample('http_server_requests_seconds_count{uri="/a,b",msg="x\\"y"} 12.5 1700000000000')
        self.assertEqual(name, "http_server_requests_seconds_count")
        self.assertEqual(labels, {"uri": "/a,b", "msg": 'x"y'})
        self.assertEqual(v, 12.5)
        self.assertEqual(prom.parse_sample("node_load1 0.5"), ("node_load1", {}, 0.5))
        self.assertIsNone(prom.parse_sample("# HELP node_load1 x"))
        self.assertEqual(prom.parse_sample('x{a="1"} NaN')[2] != prom.parse_sample('x{a="1"} NaN')[2], True)

    def test_prom_multimember_and_error(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "app01.prom.gz"
            with open(p, "wb") as f:
                f.write(gzip.compress(b"# SCRAPE 100.0 app-1\n# TYPE c counter\nhikaricp_connections_timeout_total{pool=\"primary\"} 5\n"))
                # 수집기 모양 — 머리 · 잘린 본문 · 오류 표시가 한 멤버. 통째로 버려야 한다(F11)
                f.write(gzip.compress(b"# SCRAPE 102.0 app-1\nhikaricp_connections_timeout_total{pool=\"primary\"} 999\n\n# SCRAPE_ERROR app-1\n"))
                f.write(gzip.compress(b"# SCRAPE 105.0 app-1\nhikaricp_connections_timeout_total{pool=\"primary\"} 8\n"))
                # 지금 수집기 — 본문 없는 실패 한 줄(멤버 하나). 앞의 온전한 본문(105)은 남는다
                f.write(gzip.compress(b"# SCRAPE_ERROR app-1\n"))
                f.write(gzip.compress(b"# SCRAPE 110.0 app-1\nhikaricp_connections_timeout_total{pool=\"primary\"} 2\n"))
            m = prom.NodeMetrics.load(p, "app-01")
            pts = sorted(m.agg("app-1", "hikaricp_connections_timeout_total").get(None).items())
            self.assertEqual(pts, [(100.0, 5.0), (105.0, 8.0), (110.0, 2.0)])   # 실패한 긁기의 줄은 버린다
            self.assertEqual(prom.increase(pts), 3 + 2)                          # 재시작이면 새 값부터
            # 멤버 경계가 없는 평문 — 같은 대상의 본문 바로 뒤 실패면 잘린 본문으로 보고 버린다, 다른 대상 뒤면 앞 본문을 남긴다
            q = Path(d) / "app01.prom"
            q.write_text("# SCRAPE 1.0 app-1\nnode_load1 1\n# SCRAPE 2.0 node\nnode_load1 2\n# SCRAPE_ERROR app-1\n"
                         "# SCRAPE 3.0 app-1\nnode_load1 3\n# SCRAPE_ERROR app-1\n")
            m2 = prom.NodeMetrics.load(q, "app-01")
            self.assertEqual(sorted(m2.agg("app-1", "node_load1").get(None).items()), [(1.0, 1.0)])
            self.assertEqual(sorted(m2.agg("node", "node_load1").get(None).items()), [(2.0, 2.0)])

    def test_timeseries_partial_window(self):
        from analyze import jtl
        rows = [{"ts": 100.0 + i * 0.5, "elapsed": 10.0, "success": True, "threads": 2.0} for i in range(26)]  # 100 ~ 112.5
        ts = jtl._timeseries(rows, 100.0, 113.0)
        self.assertEqual(ts[0]["tps"], 2.0)                 # 20건 ÷ 10초
        self.assertTrue(ts[1]["partial"])                   # 3초만 덮은 마지막 창
        self.assertAlmostEqual(ts[1]["tps"], 6 / 3.0, 3)    # 덮은 길이로 나눈다(F8)

    def test_per_row_rule(self):
        self.assertEqual(traces.PER_ROW_LIMIT, 100)

    def test_redis_classes(self):
        sp = traces.Span()
        sp.kind, sp.scope, sp.a = 1, traces.METHOD_SCOPE, {"code.namespace": "com.duri.rentalplatform.common.lock.DistributedLockAspect$Renewal"}
        sp.name = "DistributedLockAspect$Renewal.run"
        self.assertTrue(traces.is_store(sp))
        sp.a, sp.name = {"code.namespace": "com.duri.rentalplatform.common.lock.DistributedLockAspect"}, "DistributedLockAspect.lock"
        self.assertFalse(traces.is_store(sp))                                     # @Around advice — 보통 메서드
        sp.name = "DistributedLockAspect.acquire"
        self.assertTrue(traces.is_store(sp))
        sp.a, sp.name = {"code.namespace": "com.duri.rentalplatform.domain.auth.store.TokenStore"}, "TokenStore.get"
        self.assertTrue(traces.is_store(sp))
        sp.a = {"code.namespace": "com.duri.rentalplatform.domain.x.service.Foo"}
        self.assertFalse(traces.is_store(sp))

    def test_vmstat_first_line_dropped(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "vmstat.txt"
            p.write_text("procs ---\n r  b swpd free buff cache si so bi bo in cs us sy id wa st\n"
                         " 1 0 0 1 1 1 0 0 0 0 1 1 97 2 1 0 0\n 1 0 0 1 1 1 0 0 0 0 1 1 10 5 85 0 0\n")
            self.assertEqual(misc.loadgen_vmstat(p, None)["cpu_max_pct"], 15.0)   # F12

    def test_self_time(self):
        def sp(sid, pid, s, e):
            x = traces.Span()
            x.tid, x.sid, x.pid, x.name, x.kind, x.s, x.e, x.a, x.scope, x.inst, x.err = "t", sid, pid, sid, 1, s, e, {}, "", "", False
            return x
        root = sp("root", None, 0, 100)
        a = sp("a", "root", 0, 80)
        q1 = sp("q1", "a", 10, 30)
        q2 = sp("q2", "a", 25, 40)      # q1 과 겹친다 — 합집합 10~40 = 30
        st = sp("st", "a", 50, 60)
        self.assertEqual(traces.self_time(a, [q1, q2, st]), 80 - 30 - 10)
        self.assertEqual(traces.self_time(root, [a]), 20)
        self.assertEqual(traces.union_ms([(0, 10), (5, 15), (20, 25)]), 20)

    def test_commandstats_delta(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d) / "a.txt", Path(d) / "b.txt"
            a.write_text("# captured_utc 2026-10-03T00:00:00Z\n# Commandstats\r\ncmdstat_get:calls=100,usec=200,usec_per_call=2.00,rejected_calls=0,failed_calls=0\r\n")
            b.write_text("# captured_utc 2026-10-03T00:05:00Z\n# Commandstats\r\ncmdstat_get:calls=1100,usec=2200,usec_per_call=2.00,rejected_calls=0,failed_calls=0\r\n"
                         "cmdstat_hset:calls=40,usec=80,usec_per_call=2.00,rejected_calls=0,failed_calls=1\r\n")
            r = misc.commandstats_delta(a, b, requests=500)
            self.assertEqual(r["commands"]["get"]["calls"], 1000)
            self.assertEqual(r["commands"]["hset"]["calls"], 40)
            self.assertEqual(r["total_calls"], 1040)
            self.assertAlmostEqual(r["calls_per_request"], 2.08)

    def test_commandstats_admin_excluded(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d) / "a.txt", Path(d) / "b.txt"
            a.write_text("cmdstat_get:calls=0,usec=0\ncmdstat_info:calls=10,usec=10\n")
            b.write_text("cmdstat_get:calls=50,usec=50\ncmdstat_info:calls=110,usec=500\ncmdstat_config|get:calls=5,usec=5\n")
            r = misc.commandstats_delta(a, b, requests=10)
            self.assertEqual(r["total_calls"], 50)          # 앱 명령만(F9)
            self.assertEqual(r["admin_calls"], 105)
            self.assertNotIn("info", r["commands"])

    def test_wait_none(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "db01-wait.csv"
            p.write_text("ts,wait_event_type,count\n2026-10-03T00:00:01Z,CPU,2\n2026-10-03T00:00:01Z,IO,1\n"
                         "2026-10-03T00:00:02Z,NONE,0\n2026-10-03T00:00:03Z,CPU,1\n")
            w = db.wait_events(p, Window(parse_time("2026-10-03T00:00:00Z"), parse_time("2026-10-03T00:00:10Z")))
            self.assertEqual(w["samples"], 3)                 # 빈 초도 표본이다
            self.assertAlmostEqual(w["aas"]["CPU"], 1.0)      # (2 + 1) ÷ 3
            self.assertNotIn("NONE", w["aas"])

    def test_nginx_retry_line(self):
        line = ('1.2.3.4 - - [03/Oct/2026:15:00:01 +0900] "GET /api/properties/7?x=1 HTTP/1.1" 200 512 "-" "ua" '
                'upstream=10.0.0.1:8081, 10.0.0.2:8081 rt=0.120 rid=abc uct=0.001, 0.002 urt=0.050, 0.060')
        r = nginx.parse_line(line)
        self.assertEqual(r["upstream"], "10.0.0.2:8081")
        self.assertEqual(r["retries"], 1)
        self.assertEqual(r["urt"], 0.060)
        self.assertEqual(r["uct"], 0.002)
        r2 = nginx.parse_line(line.replace("uct=0.001, 0.002 urt=0.050, 0.060", "uct=- urt=-"))
        self.assertIsNone(r2["urt"])

    def test_verdict(self):
        self.assertEqual(compare.verdict(100, 97, True, 5)[1], "변화없음")
        self.assertEqual(compare.verdict(100, 80, True, 5)[1], "개선")
        self.assertEqual(compare.verdict(100, 80, False, 5)[1], "악화")
        self.assertEqual(compare.verdict(None, 80, True, 5)[1], "미측정")


class FixtureRounds(unittest.TestCase):
    """가짜 회차 둘을 만들어 전체를 돌린다."""

    @classmethod
    def setUpClass(cls):
        from analyze import fixtures
        fixtures.main()
        cls.r1 = pipeline.analyze_round("_fixture_R01")
        cls.r2 = pipeline.analyze_round("_fixture_R02", write=False)
        cls.r2["comparison"] = compare.compare(cls.r2, cls.r1)
        cls.w1 = pipeline.analyze_round("_fixture_W01", write=False)

    def test_p95_from_jtl(self):
        # 도구 결과에서 워밍업을 빼고 직접 센 값과 같아야 한다
        rdir = RESULTS_DIR / "_fixture_R01"
        w = self.r1["window"]
        lo, hi = w["start_utc"] + w["warmup_sec"], w["end_utc"]
        with open(rdir / "jmeter" / "result.jtl", encoding="utf-8") as f:
            el = [float(r["elapsed"]) for r in csv.DictReader(f) if lo <= int(r["timeStamp"]) / 1000 <= hi]
        self.assertEqual(self.r1["jtl"]["requests"], len(el))
        self.assertAlmostEqual(self.r1["key"]["p95"], round(percentile(el, 95), 2))
        self.assertEqual(self.r1["jtl"]["usable_tail"], "p99")

    def test_window_is_jmeter_based(self):
        # B2 — 기록은 JMeter 보다 60초 먼저 시작해 30초 늦게 끝났다. 통계 구간 = JMeter 첫 표본 ~ 마지막 끝, 워밍업은 그 시작부터
        with open(RESULTS_DIR / "_fixture_W01" / "jmeter" / "result.jtl", encoding="utf-8") as f:
            rows = [(int(r["timeStamp"]) / 1000, float(r["elapsed"])) for r in csv.DictReader(f)]
        j0 = min(t for t, _ in rows)
        j1 = max(t + e / 1000 for t, e in rows)
        w = self.w1["window"]
        self.assertAlmostEqual(w["start_utc"], j0, 3)
        self.assertAlmostEqual(w["end_utc"], j1, 3)
        self.assertGreater(j0 - w["record_start_utc"], 59)
        self.assertGreater(w["record_end_utc"] - j1, 29)
        inwin = [e for t, e in rows if j0 + 30 <= t <= j1]
        self.assertEqual(self.w1["key"]["requests"], len(inwin))
        self.assertAlmostEqual(self.w1["key"]["tps"], round(len(inwin) / (j1 - j0 - 30), 3), 3)
        self.assertAlmostEqual(self.w1["key"]["p95"], round(percentile(inwin, 95), 2))
        # 다른 원자료도 같은 구간 — 대기 표본 수는 JMeter 구간(워밍업 제외)의 초 수와 같다
        self.assertLessEqual(self.w1["db"]["wait"]["db-01"]["samples"], int(j1 - j0 - 30) + 1)
        self.assertEqual(self.w1["window"]["basis"], "jmeter")

    def test_pgss_all_gz_app_db(self):
        # B1 — 전체 파일(gzip)의 앱 DB 행만 합에 쓴다. postgres DB 질의(99,999 ms)는 빠진다
        p = self.r1["db"]["pgss"]["db-01"]
        self.assertEqual(p["total_basis"], "pgss-all")
        self.assertEqual(p["app_db"], "rental")
        with gzip.open(RESULTS_DIR / "_fixture_R01" / "db" / "db01-pgss-all.csv.gz", "rt", encoding="utf-8") as f:
            rows = [r for r in csv.DictReader(f) if r["datname"] == "rental"]
        self.assertAlmostEqual(p["total_exec_ms"], round(sum(float(r["total_exec_time"]) for r in rows), 1), 1)
        self.assertEqual(p["total_calls"], sum(float(r["calls"]) for r in rows))
        self.assertEqual(p["statements"], 5)
        self.assertLess(self.r1["db"]["total_db_ms"], 99999)

    def test_pgss_app_db_without_top20(self):
        # 상위 20 이 없으면 postgres · template 를 뺀 DB 중 총 시간이 가장 큰 것
        with tempfile.TemporaryDirectory() as d:
            src = RESULTS_DIR / "_fixture_R01" / "db" / "db01-pgss-all.csv.gz"
            (Path(d) / "db01-pgss-all.csv.gz").write_bytes(src.read_bytes())
            p = db.pgss(Path(d), "db-01", requests=100)
            self.assertEqual(p["app_db"], "rental")
            self.assertEqual(p["total_basis"], "pgss-all")

    def test_meta_merge(self):
        m = self.r1["meta"]
        self.assertEqual(m["scenario"], "S1")
        self.assertEqual(m["warmup_sec"], 30)
        self.assertEqual(self.r1["window"]["warmup_sec"], 30)

    def test_resources(self):
        res = self.r1["resources"]
        self.assertEqual(sorted(res["nodes"]), ["app-01", "app-02", "db-01", "db-02"])
        self.assertEqual(res["missing_metrics"], [])
        self.assertEqual(res["nodes"]["db-01"]["vcpu"], 2)
        self.assertGreater(res["nodes"]["db-01"]["cpu_pct"]["max"], 0)
        self.assertEqual(res["app_totals"]["pools"]["primary"]["max_per_slot"], 8)
        self.assertEqual(res["postgres"]["db-01"]["max_connections"], 48)

    def test_dbstats_hit_ratio(self):
        ds = self.r1["db"]["dbstats"]["db-01"]
        self.assertAlmostEqual(ds["hit_ratio_pct"], round(100 * 2_000_000 / 2_004_000, 3))

    def test_traces(self):
        tr = self.r1["traces"]
        self.assertGreater(tr["spans"], 0)
        risk = tr["routes"]["properties/{id}/risk"]
        self.assertEqual(risk["sql_per_request"]["mean"], 12)          # 가짜 회차가 넣은 질의 수
        self.assertEqual(risk["redis_calls_per_request"], 1)
        self.assertTrue(risk["repeated_statements"])
        self.assertIn("RefreshScheduler.run", tr["background"])
        # Service 줄에 store 메서드가 들어가지 않는다
        names = [r["name"] for r in self.r1["budget"]["properties/{id}/risk"]["rows"]]
        self.assertFalse(any("TokenStore" in n for n in names))

    def test_sse_redis_classes_per_row(self):
        tr = self.r1["traces"]
        self.assertEqual(tr["sse_connections"], 3)                               # F6
        self.assertNotIn("notifications/stream", tr["routes"])
        self.assertNotIn("notifications/stream", self.r1["budget"])
        self.assertNotIn("notifications/stream", self.r1["matrix"]["columns"])
        self.assertNotIn("notifications/stream", [x["route"] for x in tr["slowest"]])
        w = tr["routes"]["me/wishlist"]
        # N1 — 락 advice(lock)는 Redis 구간이 아니다: acquire · Renewal.run · release 셋만 세고,
        # Redis 시간은 그 자기 시간 합(2 + 0.5 + 1 = 3.5 ms)이지 lock 구간 길이(39 ms)가 아니다
        self.assertEqual(w["redis_calls_per_request"], 3)
        self.assertAlmostEqual(w["redis_ms_per_request"]["mean"], 3.5, delta=0.01)
        req = w["decomp"]["p95_request"]
        self.assertAlmostEqual(req["redis"], 3.5, delta=0.01)
        self.assertAlmostEqual(req["db"], 13.0, delta=0.01)
        self.assertEqual(req["overlap"], 0)                                      # 겹쳐 세지 않는다
        self.assertAlmostEqual(sum(v for k, v in req.items() if k not in ("server", "trace_id", "overlap")), 90.0, delta=0.01)
        self.assertNotEqual((self.r1["budget"]["me/wishlist"]["largest"] or {}).get("layer"), "캐시")
        self.assertEqual(w["per_row_methods"], ["CodeConverter.toName"])          # F10 — 120번 > 100

    def test_budget_decomposition(self):
        # F7 — p95 순위 요청 하나를 나눈 값: 구성 요소 + 설명되지 않은 시간 = 서버 구간, 설명되지 않은 시간 ≥ 0
        n = 0
        for r, b in self.r1["budget"].items():
            if b["server_ms"] is None:
                continue
            n += 1
            tot = sum(x["ms"] for x in b["rows"]) + b["unexplained_ms"]
            self.assertAlmostEqual(tot, b["server_ms"] + (b["overlap_ms"] or 0), delta=0.05)
            self.assertGreaterEqual(b["unexplained_ms"], 0)
            self.assertTrue(all("합에 넣지 않음" in x["source"] for x in b["extra"]))
        self.assertGreater(n, 0)

    def test_failed_scrape_and_vmstat(self):
        self.assertLess(self.r1["resources"]["nginx"]["connections_active_max"], 99999)   # F11
        self.assertLess(self.r1["loadgen"]["cpu_max_pct"], 98)                           # F12

    def test_commandstats_and_checks(self):
        self.assertEqual(self.r1["redis_commandstats"]["total_calls"], 1210)
        self.assertEqual(self.r1["redis_commandstats"]["admin_calls"], 660)
        self.assertTrue(all(v for v in self.r1["checks"].values()))

    def test_matrix_rows_sum_100(self):
        for k, row in self.r1["matrix"]["rows"].items():
            vals = [v for v in row["shares"].values() if v is not None]
            if vals:
                self.assertAlmostEqual(sum(vals), 100.0, delta=0.6)

    def test_comparison(self):
        c = self.r2["comparison"]
        p95 = next(m for m in c["metrics"] if m["key"] == "p95")
        self.assertEqual(p95["base"], self.r1["key"]["p95"])
        self.assertLess(p95["delta_pct"], 0)

    def test_report_renders(self):
        with tempfile.TemporaryDirectory() as d:
            ctx = report.build_context(self.r2, self.r1, Path(d))
            out = report.render_html(ctx, Path(d) / "r.html")
            html = out.read_text(encoding="utf-8")
            self.assertNotIn("{{", html)
            self.assertIn("_fixture_R02", html)
            self.assertIn("map/clusters", html)


if __name__ == "__main__":
    unittest.main()
