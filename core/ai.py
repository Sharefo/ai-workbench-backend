"""中文→SQL、报错→修复。有 AI_API_KEY 用模型；没有则走免费离线规则引擎（只改配置不改代码）。"""
import difflib
import json
import os
import re
import urllib.request

from . import guard


def model_enabled() -> bool:
    return bool(os.environ.get("AI_API_KEY", "").strip())


def _clean_sql(text: str) -> str:
    text = re.sub(r"```(?:sql)?", "", text or "", flags=re.I)
    text = text.replace("```", "").strip()
    return text.strip().rstrip(";").strip() + ";"


def _call_model(system: str, user: str):
    key = os.environ.get("AI_API_KEY", "").strip()
    if not key:
        return None
    base = os.environ.get("AI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    model = os.environ.get("AI_MODEL", "gpt-4o-mini")
    payload = json.dumps({"model": model, "temperature": 0.1, "messages": [
        {"role": "system", "content": system}, {"role": "user", "content": user}]}).encode("utf-8")
    req = urllib.request.Request(base + "/chat/completions", data=payload, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + key})
    with urllib.request.urlopen(req, timeout=20) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def _schema_text(schema: dict) -> str:
    return "\n".join(f"- {t}({', '.join(cols)})" for t, cols in schema.items())


def _limit_from(q: str) -> int:
    m = re.search(r"(?:前|top|最多|取)\s*(\d+)|(\d+)\s*(?:条|名|个)", q, re.I)
    n = int(m.group(1) or m.group(2)) if m else 20
    return max(1, min(n, 200))


def rule_generate(question: str, schema: dict):
    """按中文关键词映射到可执行 SQL；表结构来自真实数据库，不凭猜。"""
    q = (question or "").strip()
    low = q.lower()
    n = _limit_from(q)
    tables = set(schema)
    has = lambda *words: any(w in q or w.lower() in low for w in words)

    if has("评价", "评分", "好评", "口碑"):
        sql = ("SELECT p.name AS product_name, ROUND(AVG(r.rating),2) AS avg_rating, "
               "COUNT(*) AS review_count FROM reviews r JOIN products p ON r.product_id = p.id "
               "GROUP BY p.name ORDER BY avg_rating DESC, review_count DESC LIMIT 10")
        why = "评价在 reviews 表，商品名在 products 表，用 product_id 关联后按商品分组算平均分。"
    elif has("支付方式", "付款方式", "支付流水", "支付金额", "支付成功"):
        sql = ("SELECT method, COUNT(*) AS pay_count, ROUND(SUM(amount),2) AS total_amount "
               "FROM payments WHERE status = '成功' GROUP BY method ORDER BY total_amount DESC")
        why = "按 method 分组统计成功支付笔数与金额，金额用 SUM 汇总。"
    elif has("销量", "卖得最好", "热销", "最畅销"):
        sql = ("SELECT p.name AS product_name, SUM(oi.quantity) AS total_qty "
               "FROM order_items oi JOIN products p ON oi.product_id = p.id "
               "GROUP BY p.name ORDER BY total_qty DESC LIMIT 10")
        why = "销量来自订单明细的 quantity，按商品求和后倒序取前 10。"
    elif has("销售额", "gmv", "总金额", "营收", "总额"):
        if has("每月", "按月", "月份"):
            sql = ("SELECT SUBSTR(order_date,1,7) AS month, ROUND(SUM(amount),2) AS total_amount "
                   "FROM orders GROUP BY month ORDER BY month")
            why = "用 SUBSTR 取日期前 7 位当作月份，再按月份汇总订单金额。"
        else:
            sql = "SELECT ROUND(SUM(amount),2) AS total_amount, COUNT(*) AS order_count FROM orders"
            why = "所有订单的金额加总就是销售额，顺便给出订单数便于核对。"
    elif has("城市") and has("订单", "下单", "销售额"):
        sql = ("SELECT u.city, COUNT(*) AS order_count, ROUND(SUM(o.amount),2) AS total_amount "
               "FROM orders o JOIN users u ON o.user_id = u.id "
               f"GROUP BY u.city ORDER BY order_count DESC LIMIT {n}")
        why = "订单表只有 user_id，城市在用户表，用 JOIN 关联后按城市分组统计，再按订单数倒序取前 %d。" % n
    elif has("每个城市", "各城市", "按城市") and has("用户", "会员"):
        sql = ("SELECT city, COUNT(*) AS user_count FROM users "
               "GROUP BY city ORDER BY user_count DESC, city")
        why = "按 city 分组，COUNT(*) 数出每个城市的用户数。"
    elif has("分类", "类目") and has("商品", "数量", "多少"):
        sql = ("SELECT c.name AS category_name, COUNT(*) AS product_count "
               "FROM products p JOIN categories c ON p.category_id = c.id "
               "GROUP BY c.name ORDER BY product_count DESC")
        why = "商品按 category_id 关联分类表，再按分类名分组数商品数。"
    elif has("没下过单", "未下单", "从未下单", "没有订单"):
        sql = ("SELECT COUNT(*) AS never_ordered FROM users u "
               "WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)")
        why = "用 NOT EXISTS 找出在订单表里一条记录都没有的用户，再计数。"
    elif has("每月", "按月", "月份", "月度") and has("订单", "下单"):
        sql = ("SELECT SUBSTR(order_date,1,7) AS month, COUNT(*) AS order_count, "
               "ROUND(SUM(amount),2) AS total_amount FROM orders GROUP BY month ORDER BY month")
        why = "取日期前 7 位作月份，按月份统计订单数和金额。"
    elif has("商品", "产品") and has("最贵", "价格最高", "高价"):
        sql = f"SELECT id, name, price, stock FROM products ORDER BY price DESC LIMIT {n}"
        why = "按 price 倒序就是价格从高到低，LIMIT %d 取前几名。" % n
    elif has("库存", "缺货", "补货"):
        sql = "SELECT id, name, price, stock FROM products WHERE stock < 50 ORDER BY stock ASC"
        why = "筛出库存低于 50 的商品，按库存从少到多排，方便看该补哪些货。"
    elif has("商品", "产品"):
        if has("上架", "在售"):
            sql = ("SELECT id, name, price, stock FROM products WHERE status = '上架' "
                   f"ORDER BY price DESC LIMIT {n}")
            why = "只取状态为上架的商品，按价格倒序展示。"
        else:
            sql = f"SELECT id, name, price, stock, status FROM products ORDER BY id LIMIT {n}"
            why = f"商品信息都在 products 表，先取前 {n} 行看看长什么样。"
    elif has("用户", "会员", "注册"):
        sql = f"SELECT id, name, city, age, level, register_date FROM users ORDER BY id LIMIT {n}"
        why = f"用户基本信息在 users 表，先取前 {n} 行。"
    elif has("订单状态", "各状态", "状态分布"):
        sql = "SELECT status, COUNT(*) AS order_count FROM orders GROUP BY status ORDER BY order_count DESC"
        why = "按 status 分组计数，看每种订单状态各有多少笔。"
    elif has("订单", "下单"):
        if has("关联", "join", "用户名", "带上用户"):
            sql = ("SELECT o.id, u.name, o.amount, o.order_date FROM orders o "
                   "JOIN users u ON o.user_id = u.id ORDER BY o.id LIMIT 20")
            why = "订单表和用户表用 user_id = id 关联，就能在订单里看到用户姓名。"
        else:
            sql = (f"SELECT id, user_id, order_date, status, amount FROM orders "
                   f"ORDER BY amount DESC LIMIT {n}")
            why = f"订单主表是 orders，按金额倒序取前 {n} 笔。"
    else:
        sql = "SELECT * FROM orders LIMIT 20"
        why = ("没从这句话里认出明确的表，先给你一条订单表的通用查询作起点；"
               "换个说法（例如『统计每个城市的订单数』）会更准。可用的表：" + ", ".join(sorted(tables)[:8]))
    return _clean_sql(sql), why, "rules"


def generate(question: str, schema: dict):
    if model_enabled():
        try:
            system = ("你是 SQL 助教。只输出一条可直接执行的 SQLite SQL，不要解释、不要用 Markdown 代码块。"
                      "只能用下面这些真实表和字段，绝对不要编造字段名：\n" + _schema_text(schema))
            text = _call_model(system, question)
            if text:
                return _clean_sql(text), "由配置的免费额度模型结合真实表结构生成。", "model"
        except Exception as e:  # 额度不可用 / 网络失败 → 自动降级，只改配置不改代码
            sql, why, _ = rule_generate(question, schema)
            return sql, why + f"（模型不可用，已自动降级到本地规则：{e}）", "rules-fallback"
    return rule_generate(question, schema)


def rule_fix(sql: str, error: str, schema: dict):
    s = (sql or "").strip()
    changes = []
    fixes = [(r"\bFORM\b", "FROM", "把 FORM 改成 FROM"), (r"\bSELCT\b", "SELECT", "把 SELCT 改成 SELECT"),
             (r"\bWEHRE\b", "WHERE", "把 WEHRE 改成 WHERE"), (r"\bFRMO\b", "FROM", "把 FRMO 改成 FROM"),
             (r"\bGRUOP\s+BY\b", "GROUP BY", "把 GROUP BY 拼写修正"),
             (r"\bORDRE\s+BY\b", "ORDER BY", "把 ORDER BY 拼写修正"),
             (r"\bLIMT\b", "LIMIT", "把 LIMT 改成 LIMIT"),
             (r"\bCOUN\b", "COUNT", "把 COUNT 拼写修正")]
    for pat, rep, desc in fixes:
        if re.search(pat, s, re.I):
            s = re.sub(pat, rep, s, flags=re.I)
            changes.append(desc)
    for bad, good in [("“", '"'), ("”", '"'), ("‘", "'"), ("’", "'"), ("，", ","), ("；", ";")]:
        if bad in s:
            s = s.replace(bad, good)
            changes.append("把中文标点换成英文半角符号")
    s = re.sub(r",\s*(FROM|WHERE|ORDER|GROUP|LIMIT)", r" \1", s, flags=re.I)
    if re.search(r",\s*(FROM|WHERE|ORDER|GROUP|LIMIT)", sql or "", re.I):
        changes.append("删掉最后一个字段后多出的逗号")

    tables = list(schema)
    for m in list(re.finditer(r"(?i)\b(?:FROM|JOIN)\s+([A-Za-z_][\w]*)", s)):
        name = m.group(1)
        if name in schema or name.upper() in ("SELECT", "VALUES"):
            continue
        near = difflib.get_close_matches(name, tables, n=1, cutoff=0.5)
        if near:
            s = re.sub(r"(?i)\b" + re.escape(name) + r"\b", near[0], s)
            changes.append(f"把猜错的表名 {name} 改成真实表 {near[0]}")
    columns = sorted({c for cols in schema.values() for c in cols})
    col_err = re.search(r"no such column:\s*([\w.]+)", error or "", re.I)
    if col_err:
        bad = col_err.group(1).split(".")[-1]
        near = difflib.get_close_matches(bad, columns, n=1, cutoff=0.4)
        if near:
            s = re.sub(r"\b" + re.escape(bad) + r"\b", near[0], s, flags=re.I)
            changes.append(f"把不存在的字段 {bad} 改成 {near[0]}")
    if re.match(r"(?i)^SELECT", s) and not re.search(r"\bFROM\b", s, re.I):
        s = s.rstrip(";") + " FROM orders;"
        changes.append("补上缺失的 FROM orders")
    s = s.strip().rstrip(";").strip() + ";"
    if not changes:
        # 没识别出具体错处时，给一条安全可跑的兜底查询，保证「修复后能跑通」
        s = "SELECT * FROM orders LIMIT 20;"
        changes.append("没定位到具体错处，已换成一条肯定能跑通的示例查询，可在此基础上修改")
    why = "已按真实表结构做了这些修正：" + "；".join(changes) + "。"
    return s, why, "rules"


def fix(sql: str, error: str, schema: dict):
    if model_enabled():
        try:
            system = ("你是 SQL 纠错助教。只输出修正后的一条 SQLite SQL，不要解释、不要 Markdown。"
                      "真实表结构：\n" + _schema_text(schema))
            text = _call_model(system, f"原始 SQL：\n{sql}\n报错：\n{error}\n请输出修正后的 SQL：")
            if text:
                return _clean_sql(text), "由配置的模型结合真实表结构与报错重写。", "model"
        except Exception:
            pass
    return rule_fix(sql, error, schema)
