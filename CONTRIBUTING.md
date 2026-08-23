# Contributing to Simple AI Gateway

感谢你考虑为 Simple AI Gateway 贡献代码！本文档指导你如何参与项目。

## 行为准则

本项目采用 [Contributor Covenant 行为准则](CODE_OF_CONDUCT.md)。所有参与者都应遵守。不可接受的行为可以向项目维护者报告。

## 如何贡献

### 报告 Bug

1. 使用 [Bug Report 模板](.github/ISSUE_TEMPLATE/bug_report.md) 创建 Issue
2. 提供明确的复现步骤、预期行为和实际行为
3. 附上相关日志、错误信息和环境信息

### 提交功能请求

1. 使用 [Feature Request 模板](.github/ISSUE_TEMPLATE/feature_request.md) 创建 Issue
2. 清晰描述你要解决的问题和预期的解决方案

### 提交 Pull Request

1. Fork 仓库并创建你的分支：`git checkout -b feature/my-feature`
2. 遵循项目的编码规范
3. 确保受影响代码通过最小必要验证：
   - 后端：`./mvnw -q -DskipTests compile`
   - 前端：`cd frontend && npm run build`
   - 全量回归：`./scripts/verify.sh`（36 项黑盒测试，修改核心逻辑时建议执行）
4. 为新功能添加测试
5. 更新相关文档
6. 提交 PR 并使用 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)

## 开发设置

### 前提条件

- JDK 21+
- Maven 3.9+
- Node.js 20+

### 本地构建

```bash
# 后端编译
./mvnw -q compile

# 后端测试
./mvnw test

# 前端构建
cd frontend && npm ci && npm run build
```

### 本地运行

```bash
# 后端（内存模式，无需数据库）
./mvnw spring-boot:run -pl bootstrap \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=8081"

# 前端开发服务器
cd frontend && npm run dev
```

## 编码规范

### Java

- 使用 Java 21 特性（record、sealed class、pattern matching）
- 遵循 Reactive 编程模式（Mono/Flux），不要在 Controller 中 block
- Controller → Service → Repository 分层严格分离
- 使用构造器注入，禁止 `@Autowired` 字段注入
- 异常处理必须指定具体类型，禁止空 catch
- 使用 `AtomicLong` CAS 循环替代 `synchronized` 热路径
- 关键业务逻辑必须添加中文注释

### TypeScript / React

- 使用 Zustand 管理客户端状态
- 使用 TanStack React Query 管理服务端状态
- 使用 react-hook-form + zod 处理表单验证
- UI 组件使用 shadcn/ui（Radix 原语）

### 提交信息规范

```
<type>(<scope>): <简短描述>

<详细说明（可选）>
```

类型：`feat` `fix` `refactor` `test` `docs` `chore`

## 测试要求

- 新功能必须有对应测试
- Bug 修复必须先写复现测试
- 后端测试使用 JUnit 5 + Reactor Test
- 集成测试使用 Testcontainers
- 建议聚焦测试，而非全量运行：
  ```bash
  ./mvnw -pl <module> -Dtest=<ClassName> test
  ```
  例如 `./mvnw -pl gateway-core -Dtest=RouteConfigMutatorTest test`

## 发布流程

当前仓库未提供统一发布脚本；如需发布，请以 CI 流程或 Maven/前端构建命令为准。

## 问题反馈

如有任何问题，请通过 GitHub Issues 或 Discussions 联系我们。
