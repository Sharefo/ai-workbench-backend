# ai-workbench-backend

AI 工作台 · 后端。Java 17 + Spring Boot 4 + Gradle（Kotlin DSL）+ 内嵌 H2。

## 快速开始

```bash
./gradlew bootRun                # 启动，默认 http://localhost:8080
./gradlew build                  # 编译 + 跑测试
./gradlew test                   # 只跑测试
```

首次执行 `./gradlew` 会自动下载对应版本的 Gradle，无需预先安装。

启动后会在项目根目录生成 `./data/sqllab.mv.db`，这是内置训练库的数据文件（已 gitignore，删掉即彻底重置）。

## 接口

### 通用

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/info` | 返回应用名与版本 |
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/info` | 应用信息 |

### SQL 实验室（`/api/sql`）

所有请求都建议带上 `X-Workbench-Session: <空间id>` 头；不传则使用 `default` 空间。
每个空间独占一个 H2 schema（`s_<id>`），数据互不可见。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/config` | 前端用的运行时配置（模型是否已接入、行数上限等） |
| GET | `/schema` | 当前空间的库表结构（表/字段/类型/注释/行数） |
| GET | `/sample?table=&n=` | 某张表的样例数据 |
| POST | `/execute` | 执行 SQL，`mode` 为 `QUERY`（默认，只放行 SELECT）或 `ADMIN`（可写，危险操作需二次确认） |
| GET | `/sessions` | 列出所有数据空间 |
| POST | `/sessions?name=` | 新建数据空间 |
| DELETE | `/sessions/{id}` | 删除数据空间 |
| POST | `/dataset/reset` | 丢弃本空间所有表，恢复内置电商数据集 |
| POST | `/ai/generate` | **SSE 流式**：自然语言 → SQL + 解释 |
| POST | `/ai/fix` | **SSE 流式**：原 SQL + 报错 → 修正后的 SQL |
| POST | `/ai/explain` | **SSE 流式**：SQL → 中文讲解 |
| POST | `/ai/seed-plan` | 自然语言 → 造数方案 JSON（只生成不执行） |
| POST | `/seed/apply` | 执行造数方案（建表 + 批量灌数据） |
| GET | `/exercises` | 15 道闯关练习题 |
| POST | `/exercises/grade` | 提交答案，与参考答案结果集比对判分 |

统一响应包裹：`{ "ok": true, "data": ... }` 或 `{ "ok": false, "error": { "code": "...", "message": "..." } }`。
SSE 接口直接输出 `data: {...}\n\n` 事件，`type` 取值为 `delta` / `done` / `error`。

## 安全护栏

- 双模式：`QUERY` 只放行 SELECT / WITH…SELECT；`ADMIN` 才允许 DDL / DML
- `DROP` / `TRUNCATE` / 无 WHERE 的 `DELETE`、`UPDATE` 返回 `needConfirm`，需前端确认后带 `confirmed: true` 重放
- 黑名单：`SHUTDOWN`、`RUNSCRIPT`、`SCRIPT TO`、`LINK_SCHEMA`、`CREATE ALIAS/TRIGGER`、`CREATE/ALTER USER` 等
- `Statement.setQueryTimeout`（默认 10s）、`setMaxRows`（默认 1000，硬上限 5000）、单元格总数上限 10 万
- 便利命令翻译：`SHOW TABLES` / `DESC 表名` / `SHOW COLUMNS FROM 表名` → `INFORMATION_SCHEMA` 查询

## 大模型配置

默认走 DeepSeek（OpenAI 兼容协议），密钥**不入库**，写在被 gitignore 的 `src/main/resources/application-local.yml`：

```yaml
llm:
  api-key: sk-xxx
```

也可以直接用环境变量：`LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`。
换厂商（OpenAI / 通义 / Kimi / 本地 vLLM）只需改这三个值。

**没有密钥也能用**：SQL 执行、内置数据集、造数（手动编辑 JSON 方案）、闯关练习全部照常，只有 AI 相关面板会灰掉。

## 目录结构

```
src/main/java/com/sharefo/aiworkbench/
├── AiWorkbenchBackendApplication.java
├── controller/WorkbenchController.java
└── sqllab/
    ├── controller/SqlLabController.java   REST 入口
    ├── config/                             配置项 + CORS
    ├── dto/                                请求响应对象
    ├── guard/SqlSafetyGuard.java           语句拆分 / 模式判定 / 黑名单 / 便利命令翻译
    ├── exec/                               SQL 执行器 + 报错中文化
    ├── meta/SchemaService.java             元数据读取（带缓存）
    ├── session/SessionService.java         多用户隔离：一个空间一个 H2 schema
    ├── dataset/EcommerceDataset.java       内置电商数据集（固定随机种子，可复现）
    ├── mock/MockDataEngine.java            按字段规则批量造数
    ├── llm/                                OpenAI 兼容客户端 / Prompt / 输出提取
    └── exercise/                           题库 + 自动判题
src/main/resources/
├── application.yml
├── application-local.yml                   本地密钥（gitignore）
└── sqllab/exercises.json                   15 道练习题
```

## 技术栈

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Spring Boot | 4.0.9（Spring Framework 7、Jackson 3、Tomcat 11） |
| 数据库 | H2 2.4（文件库，`MODE=MySQL`） |
| 构建工具 | Gradle（Kotlin DSL，带 wrapper） |

> 注意：Spring Boot 4 起 Web starter 更名为 `spring-boot-starter-webmvc`；Jackson 3 的包名是 `tools.jackson.*`。

## 内置数据集

电商运营场景 10 张表，默认 `small` 规模（约 1.8 万订单 / 4 万行为事件），可在 `application.yml` 用 `sqllab.seed.scale` 切换 `small | medium | large`。
数据间保证业务自洽（明细挂在真实订单下、支付金额与订单金额一致、金额呈长尾分布），并用固定随机种子生成，重置后完全一致。

## CI

`.github/workflows/gradle.yml` 会在推送到 `main` 或发起 PR 时执行 `./gradlew build`。

## 相关仓库

前端：[Sharefo/ai-workbench-frontend](https://github.com/Sharefo/ai-workbench-frontend)

## 许可证

MIT
