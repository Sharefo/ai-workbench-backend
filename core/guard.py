"""安全护栏：危险语句拦截 + 英文报错中文化。"""
import re

# 任何时候都不允许（防止绕过只读限制读写外部文件）
ALWAYS_FORBIDDEN = (
    "ATTACH", "DETACH", "PRAGMA", "VACUUM", "REINDEX",
    "LOAD_EXTENSION", "CREATE VIRTUAL TABLE",
)
READ_FIRST = {"SELECT", "WITH", "EXPLAIN", "SHOW", "DESCRIBE"}
WRITE_FIRST = {"CREATE", "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "REPLACE", "TRUNCATE"}
DANGEROUS_FIRST = {"DROP", "DELETE", "TRUNCATE", "ALTER", "UPDATE"}


def first_word(sql: str) -> str:
    m = re.match(r"^([A-Za-z]+)", sql.strip())
    return m.group(1).upper() if m else ""


def check(sql: str, mode: str = "query", confirm: bool = False):
    """返回 (ok, need_confirm, message)。message 一律中文人话。"""
    if not isinstance(sql, str) or not sql.strip():
        return False, False, "还没有写 SQL 呢，先在编辑框里写一句，例如：SELECT * FROM users LIMIT 5"
    s = sql.strip().rstrip(";").strip()
    if ";" in s:
        return False, False, "一次只能执行一条 SQL。请把多条语句拆开，一条一条运行。"
    upper = s.upper()
    for word in ALWAYS_FORBIDDEN:
        if re.search(r"\b" + re.escape(word).replace(r"\ ", r"\s+") + r"\b", upper):
            return False, False, f"语句里出现了被禁止的操作「{word}」。练习场只允许对模拟数据做查询和常规建表/写入。"
    verb = first_word(s)
    if not verb:
        return False, False, "看不出这是一条什么语句。SQL 一般以 SELECT、CREATE、INSERT 等词开头。"
    if mode == "query":
        if verb not in READ_FIRST:
            return False, False, (
                f"已拦下「{verb}」语句：当前是【查询模式】，只能查数据，不能改数据。"
                "这是练习场的保护，避免误删模拟数据。确实要建表或写入时，请把模式切成【写入模式】并二次确认。"
            )
        return True, False, ""
    if mode == "write":
        if verb not in WRITE_FIRST:
            return False, False, f"写入模式只接受建表/插入/更新/删除语句，不认识「{verb}」开头。请检查语句。"
        if verb in DANGEROUS_FIRST and not confirm:
            return False, True, (
                f"⚠️ 这条「{verb}」语句会改动或删除数据，需要你再确认一次。"
                "确认后才会真正执行；练习场里建议优先用『一键造数』，别直接删表。"
            )
        return True, False, ""
    return False, False, "运行模式不对，只能是【查询模式】或【写入模式】。"


def translate_error(msg: str, sql: str = "", tables=None, columns=None) -> str:
    """把 sqlite 的英文报错翻成能照着改的中文。"""
    tables = tables or []
    columns = columns or []
    m = re.search(r"no such table:\s*([\w\u4e00-\u9fff]+)", msg, re.I)
    if m:
        hint = ""
        if tables:
            import difflib
            near = difflib.get_close_matches(m.group(1), tables, n=1, cutoff=0.5)
            hint = f"最接近的表是 `{near[0]}`。" if near else f"可用的表有：{', '.join(tables[:8])}。"
        return f"找不到表 `{m.group(1)}`。{hint}点左侧表名可以展开看字段，别凭猜写表名。"
    m = re.search(r"no such column:\s*([\w\u4e00-\u9fff.]+)", msg, re.I)
    if m:
        name = m.group(1).split(".")[-1]
        import difflib
        near = difflib.get_close_matches(name, columns, n=1, cutoff=0.5)
        hint = f"最接近的字段是 `{near[0]}`。" if near else "点左侧表名展开，能看到这张表的全部字段名。"
        return f"字段 `{name}` 不存在。{hint}"
    if "ambiguous column name" in msg.lower():
        return "有同名字段，数据库不知道你要哪一个。多表关联时给字段加上表别名，例如 `o.id`、`u.name`。"
    if "syntax error" in msg.lower() or "near" in msg.lower():
        extra = ""
        if re.search(r"\bFORM\b", sql, re.I):
            extra = " 我发现你把 `FROM` 写成了 `FORM`，改成 `FROM` 就好。"
        elif re.search(r"[\u201c\u201d\u2018\u2019，；]", sql):
            extra = " 语句里有中文引号或中文标点，SQL 只能用英文半角符号。"
        elif re.search(r",\s*(FROM|WHERE|ORDER|GROUP|LIMIT)", sql, re.I):
            extra = " 最后一个字段后面多了一个逗号，删掉它。"
        return "SQL 语法写错了。" + extra + " 常见原因：单词拼错、少了空格、括号不配对。可以点『一键修复』试试。"
    if "no such function" in msg.lower():
        return "用到了数据库不认识的函数。练习场支持 COUNT / SUM / AVG / MAX / MIN / ROUND / SUBSTR 等常用函数。"
    if "incomplete input" in msg.lower():
        return "语句没写完，可能少了半边括号、引号或关键字。"
    if "misuse of aggregate" in msg.lower() or "aggregate" in msg.lower():
        return "聚合函数（COUNT / SUM 等）和普通字段混用了。用 GROUP BY 把普通字段分组，或者只查聚合结果。"
    if "readonly" in msg.lower() or "attempt to write" in msg.lower():
        return "这条语句想改数据，但当前连接是只读的。查询模式下不允许写入，请切成写入模式并二次确认。"
    if "already exists" in msg.lower():
        return "同名的表已经存在了。换一个表名，或先删掉旧表（需要二次确认）。"
    if "no such table" in msg.lower():
        return "表不存在，点左侧列表确认表名。"
    return f"执行失败：{msg}"
