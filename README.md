# SQL 实验室 · 后端（后仓账本）

用大白话：这一层在服务器/你电脑后台跑，负责**真正执行 SQL、保管数据、挡住危险操作、调用 AI、批量造数、给练习题判分**。你在网页上看不到它，但每个按钮最后都由它干活。

## 目录里放什么

| 文件 | 作用 |
|---|---|
| `server.py` | 服务入口。固定端口 8765，既提供接口，也把 `../frontend` 的网页送出去 |
| `core/db.py` | 每人一个独立空间（一个独立数据库文件），建表、执行 SQL、读表结构 |
| `core/guard.py` | 安全护栏：拦删除/清空/改库语句，把英文报错翻成中文 |
| `core/seed.py` | 内置 10 张模拟电商表和样例数据 |
| `core/ai.py` | 中文→SQL、报错→修复；有 key 用模型，没 key 用本地规则 |
| `core/generator.py` | 一句话造数：只产出「表结构 + 字段规则」，由程序批量灌数 |
| `core/quiz.py` | 15 道闯关题 + 自动判分 |
| `tests/test_all.py` | 自动化测试：参考答案跑通、危险语句被拦、判分、隔离等 |
| `data/` | 运行期生成的空间数据库文件，**不进云端仓库** |

## 启动（不用装任何东西，Python 3.9+ 即可）

```bash
cd sql-lab/backend
python3 server.py
# 打开 http://127.0.0.1:8765
```

## 安全规矩（阶段 0.2 立下，必须遵守）

1. 密钥 / 口令、本地数据库文件 (`data/`, `*.db`)、依赖包、打包产物：**一律不提交到云端**。见 `.gitignore`。
2. 需要密钥的功能一律从**环境变量**读取（`AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL`），代码里不写死。参考 `.env.example`。
3. 所有改动提交到 `feature/v1.0.0` 分支，**不动 main**。

## 接口一览

`GET /api/health`、`GET /api/tables`、`POST /api/sql`、`POST /api/ai/generate`（流式）、
`POST /api/ai/fix`、`POST /api/generate-data/preview`、`POST /api/generate-data/execute`、
`GET /api/quiz`、`POST /api/quiz/submit`、`GET/POST /api/spaces`、`GET /api/history`。
