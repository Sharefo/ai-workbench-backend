"""一句话造数：只产出「表结构 + 字段生成规则」，由程序按规则批量生成几千行。"""
import hashlib
import random
import re
import time
from datetime import date, datetime, timedelta

from . import db

# 每个字段 = 一条「生成规则」，AI 只负责挑规则，不负责吐数据行
FIELDS = {
    "id": ("INTEGER", "从 1 开始自增的序号", "seq"),
    "name": ("TEXT", "随机中文姓名", "name"),
    "city": ("TEXT", "从 8 个常见城市里随机取", "city"),
    "phone": ("TEXT", "以 13/15/18 开头的 11 位手机号", "phone"),
    "gender": ("TEXT", "男 / 女 随机", "gender"),
    "age": ("INTEGER", "18~60 之间的随机整数", "age"),
    "level": ("TEXT", "普通 / 银卡 / 金卡 随机", "level"),
    "amount": ("REAL", "1~2000 之间的随机金额（两位小数）", "amount"),
    "status": ("TEXT", "已完成 / 待处理 / 已取消 随机", "status"),
    "created_at": ("TEXT", "2024 年内的随机日期", "date"),
    "user_id": ("INTEGER", "1~1000 之间的随机用户ID", "uid"),
    "event_type": ("TEXT", "浏览 / 加购 / 下单 / 支付 / 收藏 随机", "event"),
    "device": ("TEXT", "iOS / Android / Web 随机", "device"),
    "order_no": ("TEXT", "ORD + 8 位流水号", "orderno"),
    "product_id": ("INTEGER", "1~200 之间的随机商品ID", "pid"),
    "quantity": ("INTEGER", "1~5 之间的随机数量", "qty"),
    "price": ("REAL", "10~999 之间的随机单价", "price"),
}

FIELD_SETS = {
    "event": ["id", "user_id", "event_type", "device", "city", "amount", "created_at"],
    "order": ["id", "order_no", "user_id", "product_id", "quantity", "price", "status", "created_at"],
    "user": ["id", "name", "phone", "city", "gender", "age", "level", "created_at"],
    "default": ["id", "name", "city", "amount", "status", "created_at"],
}

TABLE_NAMES = [("用户行为", "user_events"), ("行为", "user_events"), ("事件", "user_events"),
               ("日志", "event_logs"), ("订单", "orders_demo"), ("交易", "orders_demo"),
               ("用户", "users_demo"), ("会员", "users_demo"), ("商品", "products_demo"),
               ("测试", "demo_table"), ("示例", "demo_table")]

CITIES = ["北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京"]
SURNAMES = list("王李张刘陈杨黄赵周吴徐孙马朱胡郭何高林罗郑")
GIVEN = ["小雨", "子涵", "一诺", "佳琪", "思远", "宇航", "欣怡", "梓萱", "浩然", "语桐"]


def _rows_from(prompt: str) -> int:
    m = re.search(r"(\d+)\s*(?:行|条|笔|rows?)", prompt, re.I)
    n = int(m.group(1)) if m else 1000
    return max(1, min(n, 20000))


def _kind(prompt: str) -> str:
    if any(w in prompt for w in ("行为", "事件", "日志", "埋点")):
        return "event"
    if any(w in prompt for w in ("订单", "交易", "购买")):
        return "order"
    if any(w in prompt for w in ("用户", "会员", "客户")):
        return "user"
    return "default"


def _table_name(prompt: str, schema: dict) -> str:
    base = None
    for cn, en in TABLE_NAMES:
        if cn in prompt:
            base = en
            break
    if not base:
        m = re.search(r"([A-Za-z][A-Za-z0-9_]{2,24})\s*表", prompt)
        base = m.group(1).lower() if m else "gen_table"
    base = re.sub(r"[^a-z0-9_]", "_", base)
    name, i = base, 2
    while name in schema:
        name = f"{base}_{i}"
        i += 1
    return name


def _rand_for(plan) -> random.Random:
    key = f"{plan['table']}|{plan['rows']}|{','.join(c['name'] for c in plan['columns'])}"
    seed = int(hashlib.md5(key.encode("utf-8")).hexdigest()[:12], 16)
    return random.Random(seed)


def _gen_value(kind: str, i: int, rnd: random.Random):
    if kind == "seq":
        return i
    if kind == "name":
        return rnd.choice(SURNAMES) + rnd.choice(GIVEN)
    if kind == "city":
        return rnd.choice(CITIES)
    if kind == "phone":
        return rnd.choice(["13", "15", "18"]) + "".join(str(rnd.randint(0, 9)) for _ in range(9))
    if kind == "gender":
        return rnd.choice(["男", "女"])
    if kind == "age":
        return rnd.randint(18, 60)
    if kind == "level":
        return rnd.choice(["普通", "银卡", "金卡"])
    if kind == "amount":
        return round(rnd.uniform(1, 2000), 2)
    if kind == "status":
        return rnd.choice(["已完成", "待处理", "已取消"])
    if kind == "date":
        return (date(2024, 1, 1) + timedelta(days=rnd.randint(0, 364))).isoformat()
    if kind == "uid":
        return rnd.randint(1, 1000)
    if kind == "event":
        return rnd.choice(["浏览", "加购", "下单", "支付", "收藏"])
    if kind == "device":
        return rnd.choice(["iOS", "Android", "Web"])
    if kind == "orderno":
        return f"ORD{i:08d}"
    if kind == "pid":
        return rnd.randint(1, 200)
    if kind == "qty":
        return rnd.randint(1, 5)
    if kind == "price":
        return round(rnd.uniform(10, 999), 2)
    return ""


def _ddl(table: str, columns) -> str:
    defs = ", ".join(f"{c['name']} {c['type']}" for c in columns)
    return f"CREATE TABLE {table} ({defs})"


def build_plan(prompt: str, space: str):
    """只产出表结构 + 字段规则 + 5 行预览，绝不写库。"""
    schema = db.schema_for_ai(space)
    kind = _kind(prompt)
    columns = [{"name": f, "type": FIELDS[f][0], "rule": FIELDS[f][1], "gen": FIELDS[f][2]}
               for f in FIELD_SETS[kind]]
    plan = {"table": _table_name(prompt, schema), "columns": columns,
            "rows": _rows_from(prompt), "prompt": prompt, "kind": kind}
    plan["ddl"] = _ddl(plan["table"], columns)
    rnd = _rand_for(plan)
    preview = [[_gen_value(c["gen"], i, rnd) for c in columns] for i in range(1, 6)]
    plan["preview"] = {"columns": [c["name"] for c in columns], "rows": preview}
    plan["message"] = (f"准备建表 `{plan['table']}`，共 {len(columns)} 个字段、{plan['rows']} 行。"
                       f"已识别为「{ {'event':'用户行为类','order':'订单交易类','user':'用户类','default':'通用测试类'}[kind] }」数据，"
                       "下面是字段规则和前 5 行预览；确认后才会真正写入。")
    return plan


def execute_plan(space: str, plan: dict):
    """按规则批量生成（5000 行也是秒级）。前端必须带 confirm=true。"""
    table = plan.get("table", "")
    if not re.fullmatch(r"[a-z][a-z0-9_]{1,40}", table or ""):
        return {"ok": False, "message": "表名不合法，只能是小写英文字母、数字和下划线"}
    columns = plan.get("columns") or []
    if not columns or any(c["name"] not in FIELDS or c["type"] != FIELDS[c["name"]][0] for c in columns):
        return {"ok": False, "message": "字段规则不合法，请重新预览一次再执行"}
    rows = max(1, min(int(plan.get("rows", 1000)), 20000))
    plan = dict(plan, rows=rows)
    rnd = _rand_for(plan)
    started = time.perf_counter()
    conn = db.connect(space)
    try:
        if table in db.schema_for_ai(space):
            conn.execute(f'DROP TABLE "{table}"')
        conn.execute(_ddl(table, columns))
        names = [c["name"] for c in columns]
        placeholders = ",".join("?" for _ in names)
        sql = f'INSERT INTO "{table}" ({",".join(names)}) VALUES ({placeholders})'
        batch = []
        for i in range(1, rows + 1):
            batch.append([_gen_value(c["gen"], i, rnd) for c in columns])
            if len(batch) >= 1000:
                conn.executemany(sql, batch)
                batch = []
        if batch:
            conn.executemany(sql, batch)
        conn.commit()
        count = conn.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
    except Exception as e:
        conn.rollback()
        return {"ok": False, "message": f"造数失败：{e}"}
    finally:
        conn.close()
    elapsed = round((time.perf_counter() - started) * 1000, 2)
    db._append_history(space, {"time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                               "sql": f"-- 一键造数：{plan.get('prompt','')}\n{_ddl(table, columns)}",
                               "ok": True, "message": f"造数成功，生成 {count} 行", "rowCount": count,
                               "mode": "write", "rows": [], "columns": []})
    return {"ok": True, "table": table, "rows": count, "elapsedMs": elapsed,
            "message": f"造数完成：表 `{table}` 已生成 {count} 行，耗时 {elapsed} 毫秒。左侧刷新一下就能看到它。"}
