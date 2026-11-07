"""阶段 3.4：把验收清单里能自动检查的部分固化成测试。

运行：cd sql-lab/backend && python3 -m unittest discover -s tests -v
每次改动先跑一遍，全绿再交付。
"""
import json
import os
import sys
import time
import unittest
import urllib.request
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

from core import ai, db, generator, guard, quiz, seed  # noqa: E402

SPACE = "test_space"
BASE = "http://127.0.0.1:8765"


def http(path, payload=None):
    req = urllib.request.Request(BASE + path, method="POST" if payload is not None else "GET",
                                 data=json.dumps(payload).encode() if payload is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.read(), r.headers.get("Content-Type", "")


def http_json(path, payload=None):
    body, _ = http(path, payload)
    return json.loads(body.decode())


def service_up():
    try:
        http_json("/api/health")
        return True
    except Exception:
        return False


SERVICE_UP = service_up()


def reset_test_space():
    """每次跑测试都从干净空间开始：造数测试会往空间里加表，不重置会让下一次运行的表数断言失败。"""
    for p in [db.space_file(SPACE), db.history_file(SPACE)]:
        if p.exists():
            p.unlink()
    db.ensure_space(SPACE)


reset_test_space()


class TestP0Skeleton(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        db.ensure_space(SPACE)

    def test_01_内置表与样例数据(self):
        tables = db.table_summaries(SPACE)
        names = {t["name"] for t in tables}
        self.assertEqual(len(seed.TABLE_META), 10)
        self.assertTrue(set(seed.TABLE_META).issubset(names), "10 张内置表必须都在")
        self.assertTrue(len(tables) >= 10)  # 允许造数测试新增的表存在
        self.assertTrue(all(t["rowCount"] > 0 for t in tables))
        self.assertTrue(all(t["sample"]["rows"] for t in tables))
        self.assertTrue(all(t["columns"] for t in tables))

    def test_02_手写查询返回结果与耗时(self):
        r = db.execute(SPACE, "SELECT id, name, city FROM users ORDER BY id LIMIT 5;")
        self.assertTrue(r["ok"])
        self.assertEqual(r["rowCount"], 5)
        self.assertEqual(r["columns"], ["id", "name", "city"])
        self.assertIn("elapsedMs", r)

    def test_03_危险语句被拦且提示中文(self):
        for sql in ("DELETE FROM users;", "DROP TABLE users;", "UPDATE users SET age=1;",
                    "TRUNCATE TABLE orders;", "ATTACH DATABASE 'x' AS y;"):
            r = db.execute(SPACE, sql)
            self.assertFalse(r["ok"], sql)
            self.assertTrue(r["blocked"], sql)
            self.assertTrue(any("\u4e00" <= c <= "\u9fff" for c in r["message"]), sql)

    def test_04_一次只能一条语句(self):
        r = db.execute(SPACE, "SELECT 1; DELETE FROM users;")
        self.assertFalse(r["ok"])
        self.assertIn("一次只能执行一条", r["message"])

    def test_05_写入模式危险操作要二次确认(self):
        r = db.execute(SPACE, "DELETE FROM users WHERE id=999;", mode="write")
        self.assertTrue(r["needConfirm"])
        self.assertFalse(r["ok"])
        r2 = db.execute(SPACE, "DELETE FROM users WHERE id=999;", mode="write", confirm=True)
        self.assertTrue(r2["ok"])


class TestP1Guards(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        db.ensure_space(SPACE)

    def test_01_语法错误中文化并指出来(self):
        r = db.execute(SPACE, "SELECT * FORM users;")
        self.assertFalse(r["ok"])
        self.assertIn("FROM", r["message"])
        self.assertIn("FORM", r["message"])

    def test_02_表不存在中文化(self):
        r = db.execute(SPACE, "SELECT * FROM userz;")
        self.assertFalse(r["ok"])
        self.assertIn("找不到表", r["message"])

    def test_03_字段不存在中文化(self):
        r = db.execute(SPACE, "SELECT nam FROM users;")
        self.assertFalse(r["ok"])
        self.assertIn("字段", r["message"])

    def test_04_中文标点提示(self):
        msg = guard.translate_error('near "，": syntax error', "SELECT id，name FROM users;")
        self.assertIn("中文", msg)

    def test_05_执行记录可查可清空(self):
        db.execute(SPACE, "SELECT 1;")
        self.assertTrue(db.get_history(SPACE))
        db.clear_history(SPACE)
        self.assertEqual(db.get_history(SPACE), [])

    def test_06_空间隔离(self):
        db.ensure_space("test_other")
        try:
            conn = db.connect("test_other")
            conn.execute("CREATE TABLE only_here (id INTEGER)")
            conn.commit()
            conn.close()
            self.assertNotIn("only_here", db.schema_for_ai(SPACE))
            self.assertIn("only_here", db.schema_for_ai("test_other"))
        finally:
            for p in [db.space_file("test_other"), db.history_file("test_other")]:
                if p.exists():
                    p.unlink()


class TestP2AI(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        db.ensure_space(SPACE)
        cls.schema = db.schema_for_ai(SPACE)

    def test_01_无key时走免费离线规则(self):
        self.assertFalse(ai.model_enabled())
        sql, why, source = ai.generate("统计每个城市的订单数，按从多到少排前 10", self.schema)
        self.assertEqual(source, "rules")
        self.assertTrue(why)
        ok, err = _verify(sql)
        self.assertTrue(ok, err)

    def test_02_多个中文需求生成的SQL都能跑通(self):
        questions = ["查所有上架商品", "每个分类有多少商品", "销量最好的 5 个商品",
                     "各支付方式的成功金额", "统计每个城市的用户数", "找出没下过单的用户数",
                     "按月份统计订单金额", "查库存不足的商品"]
        for q in questions:
            sql, _, _ = ai.generate(q, self.schema)
            ok, err = _verify(sql)
            self.assertTrue(ok, f"{q} → {sql} → {err}")

    def test_03_修复后的SQL能跑通(self):
        cases = ["SELECT * FORM users LIMIT 5;", "SELECT nam FROM users;",
                 "SELECT * FROM userz;", "SELECT id，name FROM users;"]
        for bad in cases:
            r = db.execute(SPACE, bad)
            if r["ok"]:
                continue
            fixed, why, source = ai.fix(bad, r.get("rawError", ""), self.schema)
            ok, err = _verify(fixed)
            self.assertTrue(ok, f"{bad} → {fixed} → {err}")
            self.assertTrue(why)


def _verify(sql):
    try:
        conn = db.connect(SPACE, readonly=True)
        try:
            conn.execute(sql.strip().rstrip(";"))
            return True, ""
        finally:
            conn.close()
    except Exception as e:
        return False, str(e)


class TestP3Generator(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        db.ensure_space(SPACE)

    def test_01_预览不写库且给出规则与样例(self):
        plan = generator.build_plan("建一张用户行为表，造 5000 行", SPACE)
        self.assertEqual(plan["rows"], 5000)
        self.assertTrue(plan["columns"])
        self.assertEqual(len(plan["preview"]["rows"]), 5)
        self.assertNotIn(plan["table"], db.schema_for_ai(SPACE))

    def test_02_确认后批量造数且很快(self):
        plan = generator.build_plan("建一张用户行为表，造 5000 行", SPACE)
        started = time.perf_counter()
        r = generator.execute_plan(SPACE, plan)
        self.assertTrue(r["ok"], r.get("message"))
        self.assertEqual(r["rows"], 5000)
        self.assertLess(time.perf_counter() - started, 5)
        conn = db.connect(SPACE, readonly=True)
        try:
            self.assertEqual(conn.execute(f'SELECT COUNT(*) FROM "{plan["table"]}"').fetchone()[0], 5000)
        finally:
            conn.close()

    @unittest.skipUnless(SERVICE_UP, "服务未启动")
    def test_03_执行接口不给确认就拒绝(self):
        r = http_json("/api/generate-data/execute", {"space": SPACE, "plan": {"table": "x"}, "confirm": False})
        self.assertTrue(r["needConfirm"])


class TestP4Quiz(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        db.ensure_space(SPACE)

    def test_01_共15道题(self):
        self.assertEqual(len(quiz.list_quizzes()), 15)

    def test_02_每题参考答案都能跑通并判分通过(self):
        for q in quiz.QUIZZES:
            r = quiz.judge(SPACE, q["id"], q["answer"])
            self.assertTrue(r["passed"], f"第 {q['id']} 题：{r['message']}")

    def test_03_错误答案不通过且有中文差异说明(self):
        r = quiz.judge(SPACE, 1, "SELECT * FROM products LIMIT 3;")
        self.assertFalse(r["passed"])
        self.assertTrue(r["message"])


@unittest.skipUnless(SERVICE_UP, "服务未启动，跳过 HTTP 端到端")
class TestHTTPEndToEnd(unittest.TestCase):
    def test_01_健康检查(self):
        self.assertTrue(http_json("/api/health")["ok"])

    def test_02_首页能打开且铺满屏幕的页面元素齐全(self):
        body, ctype = http("/")
        self.assertIn("text/html", ctype)
        self.assertIn(b"SQL", body)
        css, _ = http("/styles.css")
        self.assertIn(b"height:100vh", css)
        js, _ = http("/app.js")
        self.assertIn(b"api/ai/generate", js)

    def test_03_SQL接口查询与拦截(self):
        ok = http_json("/api/sql", {"space": SPACE, "sql": "SELECT * FROM users LIMIT 2;"})
        self.assertTrue(ok["ok"])
        bad = http_json("/api/sql", {"space": SPACE, "sql": "DROP TABLE users;"})
        self.assertTrue(bad["blocked"])

    def test_04_AI生成是流式的(self):
        req = urllib.request.Request(BASE + "/api/ai/generate", method="POST",
                                     data=json.dumps({"space": SPACE, "question": "统计每个城市的订单数，排前 10"}).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=20) as r:
            self.assertIn("text/event-stream", r.headers.get("Content-Type", ""))
            text = r.read().decode()
        self.assertIn('"type": "delta"', text)
        self.assertIn('"type": "done"', text)
        self.assertTrue(json.loads([l[6:] for l in text.splitlines() if l.startswith("data: ")][-1])["verified"])

    def test_05_造数预览与确认执行(self):
        r = http_json("/api/generate-data/preview", {"space": SPACE, "prompt": "建一张测试表，造 300 行"})
        self.assertTrue(r["ok"])
        plan = r["plan"]
        r2 = http_json("/api/generate-data/execute", {"space": SPACE, "plan": plan, "confirm": True})
        self.assertTrue(r2["ok"], r2.get("message"))
        self.assertEqual(r2["rows"], 300)

    def test_06_题库接口与判分(self):
        qs = http_json("/api/quiz")["quizzes"]
        self.assertEqual(len(qs), 15)
        r = http_json("/api/quiz/submit", {"space": SPACE, "id": 4, "sql": "SELECT COUNT(*) FROM products;"})
        self.assertTrue(r["passed"])

    def test_07_空间列表与执行记录接口(self):
        self.assertIn("demo", http_json("/api/spaces")["spaces"])
        self.assertIn("history", http_json("/api/history?space=" + SPACE))


class TestSecurityRules(unittest.TestCase):
    def test_01_gitignore排除密钥与本地数据(self):
        content = (BACKEND / ".gitignore").read_text()
        for item in (".env", "*.db", "data/", "__pycache__/", "build/", "dist/"):
            self.assertIn(item, content)

    def test_02_源码里没有写死的密钥(self):
        files = [BACKEND / "server.py"] + list((BACKEND / "core").glob("*.py"))
        for f in files:
            text = f.read_text("utf-8")
            self.assertNotIn("sk-", text, f.name)

    def test_03_环境变量示例存在(self):
        self.assertTrue((BACKEND / ".env.example").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
