"""집계 시험 — 단위(파서 · 산식)와 가짜 회차 전체.

    cd chaos-harness/measure && python -m unittest discover -s analyze/tests -t .
"""
from __future__ import annotations

import csv
import gzip
import tempfile
import unittest
from pathlib import Path

from analyze import compare, misc, nginx, pipeline, prom, report, traces, db, explain, alerts, steps, fixtures as fx
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

    def test_stmt_key_and_match(self):
        # 질의 통계($n) · 추적(?) · 사람이 쓴 질의 파일(공백 · 리터럴)이 같은 키
        a = traces.stmt_key("select a,b from t where x=$1 and y=$2")
        self.assertEqual(a, traces.stmt_key("SELECT a, b\nFROM t WHERE x = 'EMAIL' AND y = 42;".rstrip(";")))
        self.assertEqual(a, traces.stmt_key("select a,b from t where x=? and y=?"))
        # 앞 100자가 같은 긴 문장 둘이 섞이지 않는다(JPA 열 목록)
        cols = ",".join(f"t.c{i}" for i in range(40))
        k1, k2 = traces.stmt_key(f"select {cols} from t where id=?"), traces.stmt_key(f"select {cols} from t where name=?")
        self.assertNotEqual(k1, k2)
        # 잘린 문장(앞부분)은 후보가 하나일 때만 잇는다
        self.assertEqual(traces.match_key(k1[:120], {k1}), k1)
        self.assertIsNone(traces.match_key(k1[:120], {k1, k2}))

    def test_load_queries_minimal_yaml(self):
        with tempfile.TemporaryDirectory() as d:
            p, p2 = Path(d) / "q.yaml", Path(d) / "endpoints.yaml"
            p.write_text(fx.QUERIES_YAML, encoding="utf-8")
            p2.write_text(fx.ENDPOINTS_YAML, encoding="utf-8")
            q = explain.check_queries(explain.load_many([p, p2]))       # 질의 파일 여럿을 합친다
            ids = [x["id"] for x in q["queries"]]
            self.assertEqual(ids, ["login_find_auth", "login_update_last", "login_insert_token", "map_heavy"])
            self.assertEqual((q["queries"][0]["endpoint"], q["queries"][3]["endpoint"]), ("auth/login", "map/clusters"))
            with self.assertRaises(ValueError):                         # 파일끼리 id 가 겹치면 멈춘다
                explain.check_queries(explain.load_many([p, p]))
            self.assertEqual(q["queries"][0]["kind"], "read")                     # 「# 주석」은 값이 아니다
            self.assertEqual(q["queries"][1]["source"], "UserCommandService.login — last_login_at 갱신")
            self.assertEqual(q["queries"][3]["source"], "MapQueryMapper.findMarkers")
            self.assertIn("\nFROM user_auth ua1_0\n", q["queries"][0]["sql"])  # | 는 줄을 지킨다
            self.assertEqual(q["queries"][2]["sql"], "INSERT INTO refresh_token (user_id, token) VALUES (42, 'x')")   # > 는 접는다
            self.assertEqual(q["snapshots"][0]["node"], "primary")                 # 생략하면 primary
            lines = explain.split([p, p2], Path(d) / "out")
            self.assertIn("query\tmap_heavy\tread\treplica", lines)
            self.assertEqual(explain.main(["split", str(Path(d) / "out2"), str(p), str(p2)]), 0)   # explain.sh 가 부르는 모양
            self.assertEqual(lines[1], "query\tlogin_update_last\twrite\tprimary")
            self.assertFalse((Path(d) / "out" / "q-login_find_auth.sql").read_text(encoding="utf-8").rstrip().endswith(";"))
            p.write_text("queries:\n  - id: bad id\n    sql: SELECT 1\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                explain.check_queries(explain.load_queries(p))

    def test_plan_seven_lines(self):
        w = explain.seven(explain.parse_plan(fx.EXPLAIN_HEAVY))
        self.assertEqual(w["access"], ["Seq Scan on property", "Index Scan (risk_analysis_property_id_idx) on risk_analysis"])
        self.assertEqual(w["join"], ["Nested Loop"])
        self.assertEqual((w["est_rows"], w["act_rows"]), (100, 5000.0))
        self.assertEqual(w["error_factor"], 50.0)                    # 5000 ÷ 100 — 10배 넘으면 S6 경고
        self.assertTrue(w["error_over_limit"])
        self.assertEqual((w["blocks_hit"], w["blocks_read"]), (1200, 300))   # 맨 위 노드의 Buffers(누적)
        self.assertEqual(w["sort"], "external merge Disk 2048kB")
        self.assertEqual(w["temp_blocks"], 512)
        self.assertTrue(w["disk_sort"])
        self.assertEqual(w["execution_ms"], 216.0)
        self.assertEqual(w["casts"], [{"column": "district_code", "type": "text"}])
        c = explain.seven(explain.parse_plan(fx.EXPLAIN_FIND.format(read=" read=3", ms="0.950")))
        self.assertEqual((c["blocks_hit"], c["blocks_read"]), (4, 3))      # Planning: 아래 Buffers 는 계획 몫이라 안 센다
        self.assertEqual(c["indexes"], ["user_auth_auth_type_provider_id_key"])
        self.assertEqual(c["settings"], "random_page_cost = '1.1', work_mem = '4MB'")
        ct = explain.parse_plan("Index Scan using i on t  (cost=0.1..1.0 rows=1 width=4) (actual rows=1 loops=1)\n"
                                "  Index Cond: ((name)::character varying = 'a'::text)\n")
        self.assertEqual(ct["casts"], [{"column": "name", "type": "character varying"}])
        self.assertIsNone(ct["nodes"][0]["time_ms"])                 # auto_explain(log_timing off) 모양

    def test_steps_by_route(self):
        # 혼합 단계를 엔드포인트별로 — jtl URL · 추적 route · Nginx 경로가 같은 키로
        rows = [{"ts": 1.0, "elapsed": 10.0 + i, "code": "200", "success": True, "url": "https://h/api/auth/login", "label": "login"} for i in range(20)]
        rows += [{"ts": 1.0, "elapsed": 900.0, "code": "500", "success": False, "url": "https://h/api/map/clusters?a=1", "label": "map"}]
        ps = [{"route": "auth/login", "dur": 8.0, "method_self": {"X.a": 2.0}, "bcrypt_ms": 0.0, "sql_ms": 5.0, "store_ms": 0.0,
               "ext_ms": 0.0, "other_self_ms": 0.0}]
        ng = [{"path": "/api/auth/login", "urt": 0.010, "start": 1.0}]
        br = steps.by_route(rows, ps, ng, 10.0, None)
        self.assertEqual(list(br), ["auth/login", "map/clusters"])
        self.assertEqual((br["auth/login"]["count"], br["auth/login"]["actual_rps"]), (20, 2.0))
        self.assertEqual(br["auth/login"]["p95"], round(percentile([10.0 + i for i in range(20)], 95), 2))
        self.assertEqual(br["auth/login"]["components"]["sql"], 5.0)
        self.assertEqual(br["auth/login"]["components"]["queue"], 2.0)      # urt 10 − 서버 8
        self.assertEqual(br["map/clusters"]["rate_5xx"], 100.0)
        self.assertIsNone(br["map/clusters"]["components"])                 # 추적 없는 엔드포인트

    def test_alerts_from_annotations(self):
        tr = alerts.from_annotations([{"time": 1_700_000_000_000, "prevState": "Normal", "newState": "Alerting", "alertName": "WAL 전송 멈춤"},
                                      {"time": 1_700_000_060_000, "text": "x"}])
        self.assertEqual(len(tr), 1)
        self.assertEqual((tr[0]["rule_title"], tr[0]["to"]), ("WAL 전송 멈춤", "Alerting"))
        self.assertEqual(alerts._ms(1_700_000_000), 1_700_000_000_000)     # 초가 와도 밀리초로

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

    def test_new_os_elements(self):
        # 관측 설계서 3.2 의 나머지(#390) — fixtures.node_extra 의 고정 증가량에서 손으로 나온 값
        n = self.r1["resources"]["nodes"]["db-01"]
        self.assertEqual(n["pgmajfault_per_sec"]["max"], 10.0)                   # O4 +50 / 5초
        self.assertEqual(sorted(n["filesystems"]), ["/", "/boot"])               # O6 tmpfs /run 은 뺀다
        self.assertEqual(n["filesystems"]["/"]["used_pct_max"], 75.0)            # 1 − 5e9 ÷ 2e10 (부하 1.0)
        self.assertEqual(n["fs_inodes_used_pct_max"], 10.0)
        self.assertFalse(n["fs_readonly"])
        self.assertEqual((n["disk_await_ms"]["read"], n["disk_await_ms"]["write"], n["disk_await_ms"]["all"]), (0.5, 2.0, 1.0))   # O8
        self.assertEqual(n["tcp_retrans"]["ratio_pct"], 0.2)                     # O9 2 ÷ 1,000
        self.assertEqual(n["net_errors"]["tx_errs"], 0)                          # lo 는 뺀다(물리 장치만)
        self.assertGreater(n["net_errors"]["rx_errs"], 0)
        self.assertEqual(n["tcp_estab"]["max"], 60)                              # O10 40 + 20 × 1.0
        self.assertEqual(n["time_sync"], {"synced_min": 1.0, "offset_abs_max_ms": 0.2})   # O12
        self.assertFalse(n["boot"]["rebooted"])                                  # O13
        self.assertEqual(n["systemd_failed"], ["rental-wal-ship.service"])       # O14 — 구간 중 failed 였던 유닛만
        self.assertEqual(self.r1["resources"]["nodes"]["app-01"]["systemd_failed"], [])
        self.assertEqual(self.r1["resources"]["nodes"]["app-01"]["procs_running"]["max"], 4)   # O2 1 + 3 × 1.0

    def test_container_memory(self):
        # cgroup 직접(containers.py) — 슬롯 600 / 680 MiB(부하 1.0), 반쯤 쓰인 마지막 멤버는 버린다
        c = self.r1["containers"]["app-01"]["app-1"]
        self.assertEqual((c["max_bytes"], c["limit_bytes"], c["max_pct"]), (600 * fx.MIB, 680 * fx.MIB, 88.2))
        self.assertEqual((c["oom_kills"], c["restarts"]), (0, 0))
        res = self.r1["resources"]
        self.assertEqual(res["slots"]["app-02/app-2"]["container_memory_max_bytes"], 590 * fx.MIB)
        self.assertEqual(res["postgres"]["db-02"]["container_memory_max_bytes"], 450 * fx.MIB)
        self.assertEqual(res["ingress_container"]["max_bytes"], 30 * fx.MIB)
        rows = {r["name"]: r for r in report.headroom_rows(self.r1)[0]}
        self.assertEqual(rows["슬롯 컨테이너 메모리"]["headroom"], 11.8)       # 1 − 600 ÷ 680
        self.assertEqual(rows["DB 컨테이너 메모리"]["headroom"] if "DB 컨테이너 메모리" in rows else rows["컨테이너 메모리"]["headroom"], 51.2)
        self.assertEqual(rows["앞단 컨테이너 메모리"]["headroom"], 53.1)
        self.assertNotEqual(report.app_rows(self.r1, None, None)[8]["cur"], "미측정")

    def test_server_latency_w1(self):
        w = self.r1["resources"]["server_latency"]["/api/map/clusters"]
        self.assertEqual(w["route"], "map/clusters")
        self.assertEqual(w["p50_ms"], 100.0)      # 버킷 0.1 초가 정확히 절반
        self.assertEqual(w["p95_ms"], 442.9)      # 0.3 + 0.2 × (0.95 − 0.9) ÷ (0.97 − 0.9)
        self.assertEqual(w["rate_5xx_pct"], 0.0)
        html_ctx = report.collapse_view(self.r1)
        self.assertEqual(html_ctx["systemd_failed"], ["db-01 rental-wal-ship.service"])
        self.assertIsNone(html_ctx["alerts"])     # alerts.json 없는 회차 → 미측정

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


class StepRound(unittest.TestCase):
    """계단 회차(_fixture_S01) — 단계별 분해 · 포화점 · 급등 · 질의 속도 · 실제 값 EXPLAIN · 알림. 기대값은 fixtures 머리 주석."""

    @classmethod
    def setUpClass(cls):
        from datetime import datetime, timezone
        fx.make_step_round("_fixture_S01", datetime(2026, 10, 3, 9, 0, 0, tzinfo=timezone.utc))
        cls.s = pipeline.analyze_round("_fixture_S01", write=False)
        cls.st = cls.s["steps"]

    def _jtl_step(self, k):
        """단계 k(1부터)의 도구 경과를 jtl 에서 직접 — 통계 구간(워밍업 뒤)으로 자른 단계 구간."""
        x = self.st["steps"][k - 1]
        t0 = self.st["start_epoch"]
        lo = max(t0 + (k - 1) * fx.STEP_SEC, self.s["window"]["stats_start_utc"])
        hi = min(t0 + k * fx.STEP_SEC, self.s["window"]["end_utc"])
        with open(RESULTS_DIR / "_fixture_S01" / "jmeter" / "result.jtl", encoding="utf-8") as f:
            el = [float(r["elapsed"]) for r in csv.DictReader(f) if lo <= int(r["timeStamp"]) / 1000 <= hi]
        return x, el

    def test_steps_basis_and_warmup(self):
        self.assertEqual(self.st["basis"], "steps.json")
        self.assertEqual(len(self.st["steps"]), 4)
        x, el = self._jtl_step(1)
        self.assertAlmostEqual(x["duration_sec"], fx.STEP_SEC - fx.STEP_WARMUP, delta=0.2)   # 워밍업은 1단계에서 빠진다
        self.assertEqual(x["count"], len(el))
        self.assertAlmostEqual(x["actual_rps"], 5.0, delta=0.05)

    def test_step_percentiles_from_jtl(self):
        for k in range(1, 5):
            x, el = self._jtl_step(k)
            self.assertEqual(x["p95"], round(percentile(el, 95), 2))
            self.assertEqual(x["p50"], round(percentile(el, 50), 2))
        self.assertAlmostEqual(self.st["steps"][3]["rate_5xx"], 100 * 31 / 1230, delta=0.01)   # 40건마다 500

    def test_step_components(self):
        c2 = self.st["steps"][1]["components"]
        self.assertAlmostEqual(c2["bcrypt"], 62.0, delta=0.01)      # 60 + (0..4 평균 2)
        self.assertAlmostEqual(c2["methods_other"], 2.0, delta=0.01)  # BCrypt 를 뺀 메서드 자기 시간
        self.assertAlmostEqual(c2["sql"], 5.0, delta=0.01)
        self.assertAlmostEqual(c2["redis"], 1.0, delta=0.01)
        self.assertAlmostEqual(c2["unexplained"], 1.0, delta=0.01)
        self.assertAlmostEqual(c2["queue"], 1.0, delta=0.1)         # urt − 서버 구간
        self.assertAlmostEqual(c2["pool_wait"], 0.5, delta=0.01)    # 획득 합 ÷ 횟수
        self.assertAlmostEqual(self.st["steps"][3]["components"]["pool_wait"], 50.0, delta=0.01)
        self.assertEqual(self.st["steps"][1]["top_functions"][0]["method"], "BCryptPasswordEncoder.matchesNonNull")
        r4 = self.st["steps"][3]["resources"]
        self.assertEqual(r4["pool_pending_max"], 3)
        self.assertEqual(r4["slots"]["app-01/app-1"]["busy_max"], 50)
        self.assertAlmostEqual(r4["nodes"]["app-01"]["cpu_mean"], 95.0, delta=0.5)
        self.assertIn("요청 스레드", self.st["steps"][3]["first_resource"]["name"])

    def test_saturation_spike_collapse(self):
        sat = self.st["saturation"]
        self.assertEqual(sat["index"], 4)
        self.assertEqual(set(sat["conditions"]), {"p95 500 ms 초과", "5xx 1% 초과", "처리량이 목표를 못 따라가고 정체"})
        self.assertEqual([x["saturation_conditions"] for x in self.st["steps"][:3]], [[], [], []])
        sp = self.st["spikes"]
        self.assertEqual([x["index"] for x in sp], [4])
        self.assertEqual(sp[0]["component"], "BCrypt 대조 (메서드 자기 시간)")
        self.assertEqual(sp[0]["function"], "BCryptPasswordEncoder.matchesNonNull")
        self.assertEqual((self.st["limit"]["index"], self.st["limit"]["reached"]), (3, True))
        self.assertEqual(self.st["collapse"]["index"], 4)
        self.assertEqual(self.st["collapse_gap_rps"], 0.5)          # 20.5 − 20.0
        c = self.s["containers"]["app-01"]["app-1"]                  # 단계 4(200초)에 슬롯 상한 OOM 1회 → 새 컨테이너
        self.assertEqual((c["oom_kills"], c["restarts"]), (1, 1))
        self.assertEqual(self.s["resources"]["app_totals"]["container_oom_kills"], 1)

    def test_levels_and_p95_at(self):
        self.assertEqual((self.st["target_rps"], self.st["target_basis"]), (10.0, "round.json target_rps"))
        self.assertEqual([x["index"] for x in self.st["levels"]], [2, 3, 3, 4])   # 목표 · ×2 · 한계 · 초과
        self.assertEqual([(x["fraction"], x["index"]) for x in self.st["p95_at"]], [(0.5, 2), (0.8, 3), (1.0, 3)])

    def test_statement_speed_and_pgss(self):
        rows = {r["key"]: r for r in self.s["traces"]["statements"]}
        find = rows[traces.stmt_key(fx.Q_FIND.format(p1="?", p2="?"))]
        self.assertEqual((find["p50_ms"], find["p95_ms"], find["per_request"]), (2.0, 2.0, 1.0))
        self.assertEqual(find["db_nodes"], {"db-01": find["calls"]})
        self.assertEqual(find["pgss"]["db-01"]["stddev_ms"], 0.01)  # $n 문장과 이어졌다
        self.assertEqual(find["pgss"]["db-01"]["mean_ms"], 0.05)
        upd = rows[traces.stmt_key(fx.Q_UPDATE.format(p1="?", p2="?"))]
        self.assertEqual(upd["p95_ms"], 3.0)
        self.assertNotIn("_by_key", self.s["db"]["pgss"]["db-01"])   # 잇고 지운다
        self.assertTrue(all(r["trace"] for r in self.s["db"]["top_merged"]))
        # 단계별
        self.assertEqual(self.st["steps"][3]["statements"][find["key"]]["p95_ms"], 2.0)

    def test_explain_attached(self):
        e = self.s["explain"]
        q = {x["id"]: x for x in e["queries"]}
        f = q["login_find_auth"]
        self.assertEqual((f["auto_explain_plans"], f["auto_explain_same"]), (3, 2))   # 실제 계획 셋 중 Index Scan 둘
        self.assertEqual(f["trace"]["p95_ms"], 2.0)                                  # 질의 파일 문장 ↔ 추적 문장
        self.assertEqual(len(f["trace_by_step"]), 4)
        self.assertEqual((f["cold"]["blocks_read"], f["warm"]["blocks_read"]), (3, 0))
        self.assertTrue(q["login_insert_token"]["skipped"])
        self.assertIsNone(q["login_insert_token"]["warm"])
        self.assertEqual((e["error_over_limit"], e["disk_sorts"]), (1, 1))
        self.assertEqual(e["s4"][0]["diff_pct"], -5.0)
        self.assertEqual([x["id"] for x in e["s5"]], ["user_auth_stats"])
        self.assertEqual((f["endpoint"], q["map_heavy"]["endpoint"]), ("auth/login", "map/clusters"))   # 두 질의 파일
        rs = self.st["route_saturation"]["auth/login"]                       # 엔드포인트별 — 단독 회차는 그 하나
        self.assertEqual((rs["index"], rs["limit_index"], rs["limit_rps"]), (4, 3, 20.0))
        self.assertEqual(set(self.st["steps"][0]["routes"]), {"auth/login"})

    def test_alerts(self):
        a = self.s["alerts"]
        self.assertEqual(a["source"], "rules/history")
        self.assertEqual([(x["rule"], x["minutes_after_start"], x["after_end"]) for x in a["fired"]], [("수집 끊김", 3.3, False)])
        self.assertTrue(a["transitions"][-1]["after_end"])
        self.assertTrue(a["rules_compare"]["same"])
        self.assertEqual(len(a["rules_defined"]), 7)                 # 관측 설계서 5.1 의 일곱
        self.assertEqual(a["errors"], {"annotations_error": "HTTP 403"})

    def test_single_step_setup_dup_ramp(self):
        # 고정 부하 회차(steps.json 단계 하나) · 「 setup」 표본 · 같은 구간 두 번(로컬 수신기) · 선형 단계 — 깨지지 않는다
        import json
        import shutil
        src, dst = RESULTS_DIR / "_fixture_S01", RESULTS_DIR / "_fixture_S02"
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
        try:
            sj = json.loads((dst / "steps.json").read_text(encoding="utf-8"))
            sj["steps"] = [{"index": 1, "target_rps": 20.0, "ramp_from_rps": 5.0, "from_s": 0, "to_s": 240}]
            (dst / "steps.json").write_text(json.dumps(sj), encoding="utf-8")
            with open(dst / "jmeter" / "result.jtl", "a", encoding="utf-8") as f:
                t = int(sj["start_epoch_ms"]) - 30_000            # 회차 앞 가입 표본 — 통계 구간을 앞당기면 안 된다
                f.write(f"{t},5000,auth/signup setup,201,Created,setup 1-1,text,true,,100,100,1,1,https://h/api/auth/signup,1,0,1\n")
            tr = dst / "traces" / "spans.jsonl"
            lines = tr.read_text(encoding="utf-8").splitlines()
            tr.write_text("\n".join(lines + lines[:200]) + "\n", encoding="utf-8")
            s2 = pipeline.analyze_round("_fixture_S02", write=False)
        finally:
            shutil.rmtree(dst, ignore_errors=True)
        st = s2["steps"]
        self.assertEqual(len(st["steps"]), 1)
        # 실제 14.7 은 끝 값 20 의 90% 아래지만 단계 평균 목표 12.5 의 90% 위 — 정체가 아니다(p95 만 넘었다)
        self.assertEqual(st["saturation"]["conditions"], ["p95 500 ms 초과"])
        self.assertEqual(st["steps"][0]["target_mean_rps"], 12.5)        # (5 + 20) ÷ 2
        self.assertEqual(st["spikes"], [])
        self.assertEqual(s2["window"]["start_utc"], self.s["window"]["start_utc"])   # setup 표본은 구간을 정하지 않는다
        self.assertEqual(s2["key"]["requests"], self.s["key"]["requests"])
        self.assertNotIn("auth/signup setup", s2["jtl"]["labels"])
        self.assertEqual(s2["traces"]["spans"], self.s["traces"]["spans"])         # 중복 구간을 걸렀다
        with tempfile.TemporaryDirectory() as d:
            html = report.render_html(report.build_context(s2, None, Path(d)), Path(d) / "r.html").read_text(encoding="utf-8")
        self.assertNotIn("{{", html)

    def test_report_fills_step_cells(self):
        with tempfile.TemporaryDirectory() as d:
            ctx = report.build_context(self.s, None, Path(d))
            html = report.render_html(ctx, Path(d) / "r.html").read_text(encoding="utf-8")
        self.assertNotIn("{{", html)
        self.assertIn("포화점 (보조 판정", html)
        self.assertIn("login_find_auth", html)                      # [4.2] 질의마다
        self.assertIn("3 중 2", html)                                # auto_explain 실제 / 같은 계획
        self.assertIn("규칙 수집 끊김 · 시작 3.3분 뒤", html)           # [7.8]
        self.assertIn("Pending → Alerting", html)                   # [9.4](라)
        self.assertIn("(auth_type)::text", html)                    # [6.1]


if __name__ == "__main__":
    unittest.main()
