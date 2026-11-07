"""内置模拟电商数据：10 张表 + 中文说明 + small 规模样例数据。"""
import random
from datetime import date, timedelta

SCALE_ROWS = {"small": 1, "medium": 5, "large": 25}

TABLE_META = {
    "users": {"label": "用户表", "desc": "注册用户基本信息", "columns": {
        "id": "用户ID", "name": "姓名", "city": "所在城市", "gender": "性别",
        "age": "年龄", "level": "会员等级", "register_date": "注册日期"}},
    "categories": {"label": "商品分类", "desc": "商品所属类目", "columns": {
        "id": "分类ID", "name": "分类名称"}},
    "products": {"label": "商品表", "desc": "在售商品与价格库存", "columns": {
        "id": "商品ID", "name": "商品名称", "category_id": "分类ID", "price": "单价",
        "stock": "库存数量", "status": "上架状态"}},
    "orders": {"label": "订单表", "desc": "每笔订单主记录", "columns": {
        "id": "订单ID", "user_id": "下单用户ID", "order_date": "下单日期", "status": "订单状态",
        "amount": "订单金额", "coupon_id": "使用的优惠券ID"}},
    "order_items": {"label": "订单明细", "desc": "订单里买了哪些商品", "columns": {
        "id": "明细ID", "order_id": "订单ID", "product_id": "商品ID",
        "quantity": "购买数量", "unit_price": "成交单价"}},
    "payments": {"label": "支付流水", "desc": "每笔订单的支付结果", "columns": {
        "id": "流水ID", "order_id": "订单ID", "method": "支付方式", "amount": "支付金额",
        "paid_at": "支付时间", "status": "支付状态"}},
    "shipments": {"label": "物流表", "desc": "发货与签收信息", "columns": {
        "id": "物流ID", "order_id": "订单ID", "carrier": "快递公司", "tracking_no": "运单号",
        "shipped_at": "发货时间", "delivered_at": "签收时间"}},
    "coupons": {"label": "优惠券表", "desc": "可使用的优惠券", "columns": {
        "id": "券ID", "code": "券码", "discount": "减免金额", "min_amount": "最低消费",
        "valid_from": "生效日", "valid_to": "失效日"}},
    "reviews": {"label": "评价表", "desc": "用户对商品的评分", "columns": {
        "id": "评价ID", "product_id": "商品ID", "user_id": "用户ID", "rating": "评分(1-5)",
        "comment": "评价内容", "created_at": "评价时间"}},
    "addresses": {"label": "收货地址", "desc": "用户保存的地址", "columns": {
        "id": "地址ID", "user_id": "用户ID", "province": "省份", "city": "城市",
        "detail": "详细地址", "is_default": "是否默认(1/0)"}},
}

DDL = [
    "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, city TEXT, gender TEXT, age INTEGER, level TEXT, register_date TEXT)",
    "CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT)",
    "CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, category_id INTEGER, price REAL, stock INTEGER, status TEXT)",
    "CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, order_date TEXT, status TEXT, amount REAL, coupon_id INTEGER)",
    "CREATE TABLE order_items (id INTEGER PRIMARY KEY, order_id INTEGER, product_id INTEGER, quantity INTEGER, unit_price REAL)",
    "CREATE TABLE payments (id INTEGER PRIMARY KEY, order_id INTEGER, method TEXT, amount REAL, paid_at TEXT, status TEXT)",
    "CREATE TABLE shipments (id INTEGER PRIMARY KEY, order_id INTEGER, carrier TEXT, tracking_no TEXT, shipped_at TEXT, delivered_at TEXT)",
    "CREATE TABLE coupons (id INTEGER PRIMARY KEY, code TEXT, discount REAL, min_amount REAL, valid_from TEXT, valid_to TEXT)",
    "CREATE TABLE reviews (id INTEGER PRIMARY KEY, product_id INTEGER, user_id INTEGER, rating INTEGER, comment TEXT, created_at TEXT)",
    "CREATE TABLE addresses (id INTEGER PRIMARY KEY, user_id INTEGER, province TEXT, city TEXT, detail TEXT, is_default INTEGER)",
]

CITIES = ["北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京"]
SURNAMES = list("王李张刘陈杨黄赵周吴徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾")
GIVEN = ["小雨", "子涵", "一诺", "佳琪", "思远", "宇航", "欣怡", "梓萱", "浩然", "语桐", "明轩", "若曦"]
CATEGORY_NAMES = ["数码", "家居", "服饰", "美妆", "食品", "图书", "运动", "母婴"]
PRODUCT_WORDS = ["无线耳机", "保温杯", "纯棉T恤", "精华液", "坚果礼盒", "笔记本", "瑜伽垫", "婴儿湿巾",
                 "机械键盘", "收纳箱", "运动鞋", "口红", "咖啡豆", "台灯", "双肩包", "体脂秤"]
ORDER_STATUS = ["已完成", "已完成", "已完成", "已支付", "待支付", "已取消"]
PAY_METHODS = ["微信支付", "支付宝", "银行卡", "余额"]
CARRIERS = ["顺丰", "京东物流", "中通", "圆通"]
COMMENTS = ["很好用，会回购", "性价比不错", "物流挺快", "一般般", "超出预期", "包装仔细"]
PROVINCES = {"北京": "北京市", "上海": "上海市", "广州": "广东省", "深圳": "广东省", "杭州": "浙江省",
             "成都": "四川省", "武汉": "湖北省", "南京": "江苏省"}


def _day(offset: int) -> str:
    return (date(2024, 1, 1) + timedelta(days=offset)).isoformat()


def seed_db(conn, scale: str = "small") -> None:
    """建表 + 灌入固定随机种子的模拟数据（结果可复现，练习题答案稳定）。"""
    k = SCALE_ROWS.get(scale, 1)
    rnd = random.Random(20261107)
    cur = conn.cursor()
    for ddl in DDL:
        cur.execute(ddl)

    n_users, n_products, n_orders = 60 * k, 40 * k, 200 * k
    categories = [(i + 1, CATEGORY_NAMES[i]) for i in range(8)]
    cur.executemany("INSERT INTO categories VALUES (?,?)", categories)

    users = []
    for i in range(1, n_users + 1):
        city = CITIES[i % len(CITIES)]
        users.append((i, rnd.choice(SURNAMES) + rnd.choice(GIVEN), city,
                      "男" if i % 2 else "女", 18 + (i * 7) % 40,
                      ["普通", "银卡", "金卡"][i % 3], _day(i % 120)))
    cur.executemany("INSERT INTO users VALUES (?,?,?,?,?,?,?)", users)

    products = []
    for i in range(1, n_products + 1):
        cat = 1 + (i - 1) % 8
        products.append((i, f"{CATEGORY_NAMES[cat-1]}{PRODUCT_WORDS[(i-1) % len(PRODUCT_WORDS)]}{i}号",
                         cat, round(rnd.uniform(9.9, 999), 2), rnd.randint(0, 500),
                         "上架" if i % 5 else "下架"))
    cur.executemany("INSERT INTO products VALUES (?,?,?,?,?,?)", products)

    orders, items = [], []
    for i in range(1, n_orders + 1):
        # 只让前 50/k 个用户下过单，保证「从未下单的用户」这道题有答案
        uid = 1 + (i % max(1, 50 * k))
        day = _day((i * 3) % 180)
        amount = round(rnd.uniform(20, 2000), 2)
        orders.append((i, uid, day, ORDER_STATUS[i % len(ORDER_STATUS)], amount,
                       1 + i % 10 if i % 7 == 0 else None))
    cur.executemany("INSERT INTO orders VALUES (?,?,?,?,?,?)", orders)

    for i in range(1, n_orders * 2 + 1):
        oid = 1 + (i % n_orders)
        pid = 1 + ((i * 7) % n_products)
        items.append((i, oid, pid, rnd.randint(1, 5), round(rnd.uniform(10, 500), 2)))
    cur.executemany("INSERT INTO order_items VALUES (?,?,?,?,?)", items)

    payments = []
    for i in range(1, int(n_orders * 0.9) + 1):
        payments.append((i, i, PAY_METHODS[i % 4], round(rnd.uniform(20, 2000), 2),
                         _day((i * 3) % 180), "成功" if i % 9 else ("失败" if i % 2 else "退款")))
    cur.executemany("INSERT INTO payments VALUES (?,?,?,?,?,?)", payments)

    shipments = []
    for i in range(1, int(n_orders * 0.75) + 1):
        shipped = _day((i * 3) % 180)
        shipments.append((i, i, CARRIERS[i % 4], f"SF{i:08d}", shipped,
                          _day((i * 3) % 180 + 3)))
    cur.executemany("INSERT INTO shipments VALUES (?,?,?,?,?,?)", shipments)

    coupons = [(i, f"Coupon{i:03d}", round(5 + i * 3, 2), 50 + i * 20,
                _day(0), _day(200)) for i in range(1, 11)]
    cur.executemany("INSERT INTO coupons VALUES (?,?,?,?,?,?)", coupons)

    reviews = []
    for i in range(1, 80 * k + 1):
        pid = 1 + ((i - 1) % 20)  # 前 20 个商品各 4 条评价，保证 HAVING COUNT>=2 有结果
        reviews.append((i, pid, 1 + (i % n_users), rnd.choice([3, 4, 4, 5, 5, 2, 5, 4]),
                        rnd.choice(COMMENTS), _day((i * 2) % 180)))
    cur.executemany("INSERT INTO reviews VALUES (?,?,?,?,?,?)", reviews)

    addresses = []
    for i in range(1, 90 * k + 1):
        uid = 1 + (i % n_users)
        city = users[uid - 1][2]
        addresses.append((i, uid, PROVINCES[city], city, f"演示路 {i} 号", 1 if i % 3 == 0 else 0))
    cur.executemany("INSERT INTO addresses VALUES (?,?,?,?,?,?)", addresses)
    conn.commit()
