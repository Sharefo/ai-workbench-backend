"""SQL 实验室服务：固定端口 8765，一个进程同时提供网页与全部接口。

启动：python3 server.py [--port 8765]
打开：http://127.0.0.1:8765
"""
import argparse
import json
import os
import sys
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from core import ai, db, generator, guard, quiz

ROOT = Path(__file__).resolve().parent
FRONTEND = ROOT.parent / "frontend"
MIME = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
        ".js": "application/javascript; charset=utf-8", ".svg": "image/svg+xml",
        ".ico": "image/x-icon", ".json": "application/json; charset=utf-8"}


def verify_sql(space, sql):
    """只读自检：确认生成的 SQL 真能跑通。"""
    try:
        conn = db.connect(space, readonly=True)
        try:
            cur = conn.execute(sql.strip().rstrip(";"))
            if cur.description:
                cur.fetchmany(1)
            return True, ""
        finally:
            conn.close()
    except Exception as e:
        return False, str(e)


class Handler(BaseHTTPRequestHandler):
    server_version = "SQLLab/1.0"

    def log_message(self, fmt, *args):
        if os.environ.get("SQL_LAB_QUIET") != "1":
            sys.stderr.write("[%s] %s\n" % (self.log_date_time_string(), fmt % args))

    # ---------- 基础响应 ----------
    def _json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _sse_event(self, obj):
        self.wfile.write(("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8"))
        self.wfile.flush()

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}

    def _space(self, data, query):
        return (data.get("space") or query.get("space", [db.DEFAULT_SPACE])[0] or db.DEFAULT_SPACE)

    # ---------- 路由 ----------
    def do_GET(self):
        parsed = urlparse(self.path)
        path, q = parsed.path, parse_qs(parsed.query)
        try:
            if path == "/api/health":
                return self._json({"ok": True, "service": "SQL 实验室", "version": "v1.0.0",
                                   "port": self.server.server_port,
                                   "scale": db.scale(),
                                   "aiMode": "model" if ai.model_enabled() else "rules",
                                   "spaces": db.list_spaces()})
            if path == "/api/tables":
                return self._json({"ok": True, "tables": db.table_summaries(self._space({}, q), q.get("search", [""])[0])})
            if path == "/api/spaces":
                return self._json({"ok": True, "spaces": db.list_spaces()})
            if path == "/api/history":
                return self._json({"ok": True, "history": db.get_history(self._space({}, q))})
            if path == "/api/quiz":
                return self._json({"ok": True, "quizzes": quiz.list_quizzes()})
            if path == "/api/tables/sample":
                table = q.get("table", [""])[0]
                conn = db.connect(self._space({}, q), readonly=True)
                try:
                    if not any(r[0] == table for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")):
                        return self._json({"ok": False, "message": "表不存在"}, 404)
                    cur = conn.execute(f'SELECT * FROM "{table}" LIMIT 20')
                    return self._json({"ok": True, "columns": [d[0] for d in cur.description],
                                       "rows": [[db._jsonable(v) for v in r] for r in cur.fetchall()]})
                finally:
                    conn.close()
            return self._static(path)
        except Exception as e:
            traceback.print_exc()
            return self._json({"ok": False, "message": f"服务出错：{e}"}, 500)

    def do_POST(self):
        parsed = urlparse(self.path)
        path, q = parsed.path, parse_qs(parsed.query)
        data = self._body()
        try:
            space = self._space(data, q)
            if path == "/api/sql":
                return self._json(db.execute(space, data.get("sql", ""),
                                            data.get("mode", "query"), bool(data.get("confirm"))))
            if path == "/api/spaces":
                try:
                    return self._json({"ok": True, **db.create_space(data.get("name", ""))})
                except ValueError as e:
                    return self._json({"ok": False, "message": str(e)}, 400)
            if path == "/api/history/clear":
                return self._json({"ok": True, **db.clear_history(space)})
            if path == "/api/ai/generate":
                return self._stream_generate(space, data)
            if path == "/api/ai/fix":
                schema = db.schema_for_ai(space)
                sql, why, source = ai.fix(data.get("sql", ""), data.get("error", ""), schema)
                ok, err = verify_sql(space, sql)
                return self._json({"ok": True, "sql": sql, "explanation": why, "source": source,
                                   "verified": ok, "message": "修复完成，已自检可跑通" if ok else f"修复后仍跑不通：{err}"})
            if path == "/api/generate-data/preview":
                return self._json({"ok": True, "plan": generator.build_plan(data.get("prompt", ""), space)})
            if path == "/api/generate-data/execute":
                if not data.get("confirm"):
                    return self._json({"ok": False, "needConfirm": True,
                                       "message": "这一步会真的建表并写入数据，请先在预览里确认，再点一次『确认执行』。"})
                return self._json({"ok": True, **generator.execute_plan(space, data.get("plan") or {})})
            if path == "/api/quiz/submit":
                return self._json({"ok": True, **quiz.judge(space, data.get("id"), data.get("sql", ""))})
            return self._json({"ok": False, "message": "没有这个接口"}, 404)
        except ValueError as e:
            return self._json({"ok": False, "message": str(e)}, 400)
        except Exception as e:
            traceback.print_exc()
            return self._json({"ok": False, "message": f"服务出错：{e}"}, 500)

    def _stream_generate(self, space, data):
        question = data.get("question", "")
        schema = db.schema_for_ai(space)
        sql, why, source = ai.generate(question, schema)
        ok, err = verify_sql(space, sql)
        note = ""
        if not ok:
            sql2, why2, source2 = ai.fix(sql, err, schema)
            ok2, _ = verify_sql(space, sql2)
            if ok2:
                sql, source = sql2, source2
                why += " 生成的语句自检没跑通，已自动按真实表结构修正。"
                ok = True
            else:
                note = f" 自检未通过：{guard.translate_error(err, sql, list(schema), db.all_columns(schema))}"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self._sse_event({"type": "meta", "source": source, "explanation": why + note, "verified": ok})
        text = sql.strip().rstrip(";") + ";"
        for i in range(0, len(text), 4):
            self._sse_event({"type": "delta", "text": text[i:i + 4]})
            time.sleep(0.012)
        self._sse_event({"type": "done", "sql": text, "source": source,
                         "verified": ok, "explanation": why + note})
        self.close_connection = True

    def _static(self, path):
        if path in ("/", "/index.html"):
            rel = "index.html"
        else:
            rel = path.lstrip("/")
            if rel not in ("styles.css", "app.js", "favicon.ico"):
                return self._json({"ok": False, "message": "页面不存在"}, 404)
        file = FRONTEND / rel
        if not file.exists():
            return self._json({"ok": False, "message": f"找不到前端文件 {file}，请确认前端目录存在"}, 500)
        body = file.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", MIME.get(file.suffix, "application/octet-stream"))
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser(description="SQL 实验室")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8765")))
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    if args.quiet:
        os.environ["SQL_LAB_QUIET"] = "1"
    db.ensure_space(db.DEFAULT_SPACE)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print(f"SQL 实验室已启动：http://127.0.0.1:{args.port}", flush=True)
    print(f"数据规模 {db.scale()} · AI 模式 {'model' if ai.model_enabled() else 'rules(免费离线)'} · 空间 {db.list_spaces()}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止", flush=True)
        server.server_close()


if __name__ == "__main__":
    main()
