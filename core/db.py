"""空间隔离、SQL 执行、表结构读取、执行记录。"""
import json
import os
import re
import sqlite3
import threading
import time
from datetime import datetime
from pathlib import Path

from . import guard, seed

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
SPACES_DIR = DATA_DIR / "spaces"
HISTORY_DIR = DATA_DIR / "history"
MAX_ROWS = 500
DEFAULT_SPACE = "demo"
_LOCK = threading.Lock()


def _init_dirs():
    SPACES_DIR.mkdir(parents=True, exist_ok=True)
    HISTORY_DIR.mkdir(parents=True, exist_ok=True)


def valid_space(name: str) -> bool:
    return bool(re.fullmatch(r"[A-Za-z0-9_\-\u4e00-\u9fff]{1,32}", name or ""))


def space_file(space: str) -> Path:
    if not valid_space(space):
        raise ValueError("空间名只能用中英文、数字、下划线和短横线，最长 32 个字符")
    return SPACES_DIR / f"{space}.db"


def history_file(space: str) -> Path:
    if not valid_space(space):
        raise ValueError("空间名不合法")
    return HISTORY_DIR / f"{space}.json"


def scale() -> str:
    return os.environ.get("DATA_SCALE", "small").lower()


def ensure_space(space: str) -> Path:
    """空间 = 一个独立数据库文件；第一次使用时自动建好内置数据。"""
    _init_dirs()
    path = space_file(space)
    with _LOCK:
        if not path.exists():
            conn = sqlite3.connect(path)
            try:
                seed.seed_db(conn, scale())
            finally:
                conn.close()
    return path


def list_spaces():
    _init_dirs()
    ensure_space(DEFAULT_SPACE)
    names = sorted(p.stem for p in SPACES_DIR.glob("*.db"))
    if DEFAULT_SPACE not in names:
        names.insert(0, DEFAULT_SPACE)
    return names


def create_space(name: str):
    if not valid_space(name):
        raise ValueError("空间名只能用中英文、数字、下划线和短横线，最长 32 个字符")
    path = space_file(name)
    if path.exists():
        raise ValueError(f"空间「{name}」已经存在了，换个名字或直接切换过去")
    ensure_space(name)
    return {"space": name, "message": f"已创建新空间「{name}」，里面是全新的一套模拟数据，看不到别人的表。"}


def connect(space: str, readonly: bool = False) -> sqlite3.Connection:
    conn = sqlite3.connect(ensure_space(space), timeout=10)
    conn.row_factory = sqlite3.Row
    if readonly:
        conn.execute("PRAGMA query_only = ON")
    return conn


def _columns_of(conn, table: str):
    meta = seed.TABLE_META.get(table, {}).get("columns", {})
    out = []
    for row in conn.execute(f'PRAGMA table_info("{table}")'):
        out.append({"name": row["name"], "type": row["type"] or "TEXT",
                    "comment": meta.get(row["name"], "")})
    return out


def table_summaries(space: str, search: str = "", with_sample: bool = True):
    conn = connect(space, readonly=True)
    try:
        names = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        result = []
        for name in names:
            if search and search.lower() not in name.lower() and search not in seed.TABLE_META.get(name, {}).get("label", ""):
                continue
            meta = seed.TABLE_META.get(name, {"label": name, "desc": "", "columns": {}})
            cols = _columns_of(conn, name)
            count = conn.execute(f'SELECT COUNT(*) FROM "{name}"').fetchone()[0]
            item = {"name": name, "label": meta["label"], "desc": meta["desc"],
                    "columns": cols, "rowCount": count}
            if with_sample:
                rows = conn.execute(f'SELECT * FROM "{name}" LIMIT 5').fetchall()
                item["sample"] = {"columns": [c["name"] for c in cols],
                                  "rows": [[_jsonable(v) for v in row] for row in rows]}
            result.append(item)
        return result
    finally:
        conn.close()


def schema_for_ai(space: str):
    """给 AI / 修复器用的真实表结构，避免凭猜写字段。"""
    conn = connect(space, readonly=True)
    try:
        out = {}
        names = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        for name in names:
            out[name] = [c["name"] for c in _columns_of(conn, name)]
        return out
    finally:
        conn.close()


def all_columns(schema: dict):
    return sorted({c for cols in schema.values() for c in cols})


def _jsonable(v):
    if isinstance(v, (bytes, bytearray)):
        return v.decode("utf-8", "replace")
    return v


def _append_history(space, record):
    path = history_file(space)
    with _LOCK:
        try:
            data = json.loads(path.read_text("utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            data = []
        data.insert(0, record)
        path.write_text(json.dumps(data[:100], ensure_ascii=False, indent=1), "utf-8")


def get_history(space: str):
    try:
        return json.loads(history_file(space).read_text("utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def clear_history(space: str):
    history_file(space).write_text("[]", "utf-8")
    return {"ok": True, "message": "本空间的执行记录已清空"}


def execute(space: str, sql: str, mode: str = "query", confirm: bool = False):
    """执行一条 SQL：护栏 → 真执行 → 行数/耗时 → 记入执行记录。"""
    schema = schema_for_ai(space)
    ok, need_confirm, message = guard.check(sql, mode, confirm)
    started = time.perf_counter()
    if not ok:
        out = {"ok": False, "blocked": not need_confirm, "needConfirm": need_confirm,
               "message": message, "columns": [], "rows": [], "rowCount": 0,
               "elapsedMs": 0, "sql": sql, "mode": mode}
        _append_history(space, {**out, "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S")})
        return out
    try:
        conn = connect(space, readonly=(mode == "query"))
        try:
            cur = conn.execute(sql.strip().rstrip(";"))
            if cur.description:
                columns = [d[0] for d in cur.description]
                raw = cur.fetchmany(MAX_ROWS + 1)
                truncated = len(raw) > MAX_ROWS
                rows = [[_jsonable(v) for v in row] for row in raw[:MAX_ROWS]]
                row_count = len(rows)
            else:
                conn.commit()
                columns, rows, truncated = [], [], False
                row_count = cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
            elapsed = round((time.perf_counter() - started) * 1000, 2)
            out = {"ok": True, "blocked": False, "needConfirm": False,
                   "message": ("写入成功" if mode == "write" else "查询成功"),
                   "columns": columns, "rows": rows, "rowCount": row_count,
                   "truncated": truncated, "elapsedMs": elapsed, "sql": sql, "mode": mode}
        finally:
            conn.close()
    except sqlite3.Error as e:
        out = {"ok": False, "blocked": False, "needConfirm": False,
               "message": guard.translate_error(str(e), sql, list(schema), all_columns(schema)),
               "rawError": str(e), "columns": [], "rows": [], "rowCount": 0,
               "elapsedMs": round((time.perf_counter() - started) * 1000, 2),
               "sql": sql, "mode": mode}
    _append_history(space, {**out, "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S")})
    return out
