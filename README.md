# ai-workbench-backend

AI 工作台 · 后端。Java 17 + Spring Boot 4 + Gradle（Kotlin DSL）。

## 快速开始

```bash
./gradlew bootRun                # 启动，默认 http://localhost:8080
./gradlew build                  # 编译 + 跑测试
./gradlew test                   # 只跑测试
```

首次执行 `./gradlew` 会自动下载对应版本的 Gradle，无需预先安装。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/info` | 返回应用名与版本 |
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/info` | 应用信息 |

## 目录结构

```
src/
├── main/
│   ├── java/com/sharefo/aiworkbench/
│   │   ├── AiWorkbenchBackendApplication.java
│   │   └── controller/WorkbenchController.java
│   └── resources/application.yml
└── test/java/com/sharefo/aiworkbench/
    └── AiWorkbenchBackendApplicationTests.java
```

## 技术栈

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Spring Boot | 4.0.9 |
| 构建工具 | Gradle（Kotlin DSL，带 wrapper） |

> 注意：Spring Boot 4 起 Web starter 更名为 `spring-boot-starter-webmvc`。

## CI

`.github/workflows/gradle.yml` 会在推送到 `main` 或发起 PR 时执行 `./gradlew build`。

## 相关仓库

前端：[Sharefo/ai-workbench-frontend](https://github.com/Sharefo/ai-workbench-frontend)

## 许可证

MIT
