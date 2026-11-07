"""15 道闯关题 + 自动判分（比对结果集，允许多种 SQL 写法）。"""
import sqlite3

from . import db

QUIZZES = [
    {"id": 1, "level": "入门", "title": "看看商品表长什么样", "points": 5,
     "question": "查出前 20 个商品的 ID、名称、单价、库存、状态，按 ID 顺序排。",
     "hint": "SELECT 列名 FROM 表名 ORDER BY 某列 LIMIT 20",
     "answer": "SELECT id, name, price, stock, status FROM products ORDER BY id LIMIT 20;"},
    {"id": 2, "level": "入门", "title": "找出贵的商品", "points": 5,
     "question": "查出单价大于 100 元的商品，显示 ID、名称、单价，按单价从高到低排。",
     "hint": "WHERE price > 100，再用 ORDER BY price DESC",
     "answer": "SELECT id, name, price FROM products WHERE price > 100 ORDER BY price DESC;"},
    {"id": 3, "level": "入门", "title": "最贵的 5 个商品", "points": 5,
     "question": "按价格从高到低，取最贵的 5 个商品，显示 ID、名称、价格。",
     "hint": "ORDER BY price DESC LIMIT 5",
     "answer": "SELECT id, name, price FROM products ORDER BY price DESC LIMIT 5;"},
    {"id": 4, "level": "入门", "title": "一共有多少商品", "points": 5,
     "question": "统计商品表里一共有多少个商品，结果取个别名叫 product_count。",
     "hint": "COUNT(*) AS product_count",
     "answer": "SELECT COUNT(*) AS product_count FROM products;"},
    {"id": 5, "level": "进阶", "title": "每个分类有多少商品", "points": 10,
     "question": "按分类统计商品数量，显示分类名和数量，数量从多到少排。",
     "hint": "products 用 category_id 关联 categories，再 GROUP BY c.name",
     "answer": "SELECT c.name AS category_name, COUNT(*) AS product_count FROM products p JOIN categories c ON p.category_id = c.id GROUP BY c.name ORDER BY product_count DESC, c.name;"},
    {"id": 6, "level": "进阶", "title": "每个城市的用户数", "points": 10,
     "question": "统计每个城市各有多少用户，按人数从多到少排。",
     "hint": "GROUP BY city，COUNT(*)，ORDER BY 人数 DESC",
     "answer": "SELECT city, COUNT(*) AS user_count FROM users GROUP BY city ORDER BY user_count DESC, city;"},
    {"id": 7, "level": "入门", "title": "订单总金额", "points": 5,
     "question": "算出所有订单的金额总和，取别名 total_amount（保留两位小数）。",
     "hint": "ROUND(SUM(amount), 2) AS total_amount",
     "answer": "SELECT ROUND(SUM(amount), 2) AS total_amount FROM orders;"},
    {"id": 8, "level": "进阶", "title": "各状态订单数", "points": 10,
     "question": "统计每种订单状态各有多少笔，按笔数从多到少排。",
     "hint": "GROUP BY status",
     "answer": "SELECT status, COUNT(*) AS order_count FROM orders GROUP BY status ORDER BY order_count DESC, status;"},
    {"id": 9, "level": "进阶", "title": "金额最高的 10 笔订单", "points": 10,
     "question": "找出金额最高的 10 笔订单，显示 ID、用户ID、金额、下单日期。",
     "hint": "ORDER BY amount DESC LIMIT 10",
     "answer": "SELECT id, user_id, amount, order_date FROM orders ORDER BY amount DESC, id LIMIT 10;"},
    {"id": 10, "level": "进阶", "title": "订单带上用户名", "points": 10,
     "question": "用 JOIN 把订单和用户关联，显示订单ID、用户姓名、订单金额，按订单ID 取前 20 条。",
     "hint": "FROM orders o JOIN users u ON o.user_id = u.id",
     "answer": "SELECT o.id, u.name, o.amount FROM orders o JOIN users u ON o.user_id = u.id ORDER BY o.id LIMIT 20;"},
    {"id": 11, "level": "挑战", "title": "每个城市的订单数", "points": 15,
     "question": "统计每个城市的订单数和订单总金额（金额保留两位小数），按订单数从多到少排。",
     "hint": "orders JOIN users，GROUP BY u.city，COUNT(*) 与 ROUND(SUM(o.amount),2)",
     "answer": "SELECT u.city, COUNT(*) AS order_count, ROUND(SUM(o.amount),2) AS total_amount FROM orders o JOIN users u ON o.user_id = u.id GROUP BY u.city ORDER BY order_count DESC, u.city;"},
    {"id": 12, "level": "挑战", "title": "销量最好的 5 个商品", "points": 15,
     "question": "按购买数量汇总，找出销量最高的 5 个商品，显示商品名和总销量。",
     "hint": "order_items JOIN products，SUM(quantity)，ORDER BY ... DESC LIMIT 5",
     "answer": "SELECT p.name AS product_name, SUM(oi.quantity) AS total_qty FROM order_items oi JOIN products p ON oi.product_id = p.id GROUP BY p.name ORDER BY total_qty DESC, p.name LIMIT 5;"},
    {"id": 13, "level": "挑战", "title": "好评最多的商品", "points": 15,
     "question": "只看评价数不少于 2 条的商品，算平均评分（保留两位小数）和评价数，按平均分从高到低取前 5。",
     "hint": "GROUP BY p.name HAVING COUNT(*) >= 2，AVG(r.rating)",
     "answer": "SELECT p.name AS product_name, ROUND(AVG(r.rating),2) AS avg_rating, COUNT(*) AS review_count FROM reviews r JOIN products p ON r.product_id = p.id GROUP BY p.name HAVING COUNT(*) >= 2 ORDER BY avg_rating DESC, review_count DESC, p.name LIMIT 5;"},
    {"id": 14, "level": "挑战", "title": "各支付方式的成功金额", "points": 15,
     "question": "只统计支付成功的流水，按支付方式汇总金额（保留两位小数），从高到低排。",
     "hint": "WHERE status = '成功'，GROUP BY method",
     "answer": "SELECT method, ROUND(SUM(amount),2) AS total_amount FROM payments WHERE status = '成功' GROUP BY method ORDER BY total_amount DESC;"},
    {"id": 15, "level": "挑战", "title": "从没下过单的用户", "points": 15,
     "question": "统计一条订单都没有的用户数量，取别名 never_ordered。",
     "hint": "NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)",
     "answer": "SELECT COUNT(*) AS never_ordered FROM users u WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id);"},
]


def list_quizzes():
    return [{k: q[k] for k in ("id", "level", "title", "question", "hint", "points")} for q in QUIZZES]


def get_quiz(qid: int):
    return next((q for q in QUIZZES if q["id"] == int(qid)), None)


def _cell(v):
    if v is None:
        return ""
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return f"{float(v):.2f}"
    return str(v).strip()


def _norm(rows):
    return sorted(tuple(_cell(v) for v in row) for row in rows)


def _run_answer(space, sql):
    conn = db.connect(space, readonly=True)
    try:
        cur = conn.execute(sql.strip().rstrip(";"))
        columns = [d[0] for d in cur.description] if cur.description else []
        rows = cur.fetchmany(db.MAX_ROWS + 1)[:db.MAX_ROWS]
        return {"columns": columns, "rows": [[db._jsonable(v) for v in r] for r in rows]}
    finally:
        conn.close()


def judge(space: str, qid: int, sql: str):
    quiz = get_quiz(qid)
    if not quiz:
        return {"ok": False, "passed": False, "message": "没有找到这道题，请回到题目列表重新点开。"}
    user = db.execute(space, sql, mode="query", confirm=False)
    if not user.get("ok"):
        return {"ok": True, "passed": False, "score": 0, "message":
                "这条 SQL 没跑通：" + user.get("message", "未知错误") + " 改完再提交一次。",
                "actual": {"columns": [], "rows": []}, "expected": None}
    expected = _run_answer(space, quiz["answer"])
    a = _norm(user["rows"])
    e = _norm(expected["rows"])
    same_cols = len(user["columns"]) == len(expected["columns"])
    passed = a == e and same_cols
    if passed:
        msg = f"✅ 判分通过！结果和参考答案一致（{len(e)} 行），本题得 {quiz['points']} 分。写法不要求一样，查对就行。"
    elif not same_cols:
        msg = (f"还差一点：你返回了 {len(user['columns'])} 列，参考答案是 {len(expected['columns'])} 列。"
               "对照题目看看要显示哪几个字段。")
    else:
        msg = (f"还差一点：你查到 {len(a)} 行，参考答案是 {len(e)} 行，内容对不上。"
               "看看是不是漏了 WHERE 条件、LIMIT 条数或排序/分组方式。")
    return {"ok": True, "passed": passed, "score": quiz["points"] if passed else 0,
            "message": msg, "actual": {"columns": user["columns"], "rows": user["rows"]},
            "expected": expected, "answer": quiz["answer"], "hint": quiz["hint"]}
