# AGENTS.md

> 跨 session 状态先读 `CONTEXT.md`；当前阶段实施计划、范围与验收标准见 `CONTEXT-plan.md`。

## 文档入口（按任务类型阅读）

- **先读 `CONTEXT.md`**：了解项目状态总览、已知问题、当前主线、关键文件索引。
- **涉及当前迭代范围时读 `CONTEXT-plan.md`**：确认本轮纳入范围、阶段顺序、依赖与验收标准，避免擅自扩展。
- **涉及启动、联调、验证入口时读 `README.md`**：以 README 中的本地启动、联调、构建和文档导航为准。
- **涉及业务流程或接口使用时读 `docs/usage.md`**；需要稳定接口语义时读 `docs/api-reference.md`；需要机器契约时读 `docs/openapi.json`。
- **涉及测试覆盖判断时读 `docs/testing/endpoint-blackbox-coverage.md`**：判断已有黑盒覆盖与补测入口。
- 若文档与实际配置、脚本冲突，以 `pom.xml`、`package.json`、`application.yml`、实际脚本和代码实现为准。

## 项目结构与模块边界

- 根目录是 Maven 聚合工程；真正的后端模块是 `gateway-core`、`gateway-admin`、`bootstrap`；前端位于 `frontend/`，是独立的 Vite + React 工程。
- 可执行入口是 `bootstrap`：运行、打包、排查启动问题时优先看 `bootstrap/src/main/java/io/gateway/oss/bootstrap/GatewayApplication.java` 与 `bootstrap/src/main/resources/application.yml`。
- 后端职责边界保持单向依赖：`bootstrap → gateway-admin → gateway-core`。
- `bootstrap` 只做装配与启动，不要把业务逻辑塞进 `bootstrap`。
- 搜索时排除 `frontend/node_modules/`，避免噪音。

## 开发约束

### 模块与装配
- 禁止在 `gateway-core` 中引入 `gateway-admin` 的类；如出现该需求，应重新调整职责归属。
- 新增模块依赖时，检查 `pom.xml`，避免循环依赖或反向依赖。
- 不要重新引入根包 `@ComponentScan`；保持当前定向装配/显式 `@Import` 模式。

### 测试归属
- **测试必须放在被测试代码所在模块内**。
- 跑测试时优先使用模块聚焦命令：`./mvnw -pl <module> -Dtest=<ClassName> test`。

### 代码实现
- 优先复用现有实现、依赖和组件，禁止随意引入新依赖。
- 使用 `@RequiredArgsConstructor` + `private final` 构造器注入；禁止新增 `@Autowired` 字段注入。
- 使用 `GatewayException`，禁止直接 `throw new RuntimeException()`。
- 禁止空 `catch`、禁止生吞异常。
- Controller 只做参数接收、调用 Service、返回结果；不要在 Controller 中写业务逻辑。
- 日志使用 `{}` 占位符，关键异常必须记录堆栈，禁止输出敏感信息。
- 禁止硬编码账号、密码、Token、密钥等配置。

### 数据与事务
- 禁止循环查库（N+1）；批量数据优先 `IN` 查询 + 内存关联。
- 涉及多表写操作时评估事务边界，使用 `@Transactional(rollbackFor = Exception.class)`。
- 注意同类内部调用 `@Transactional` 会失效，必要时拆分或通过代理调用。

## 最小验证规则

- **Java 改动默认验证：** `./mvnw -q -DskipTests compile`
- **前端改动默认验证：** `cd frontend && npm run build`
- 必要时补充：`cd frontend && npm run lint`
- 需要跑单测时优先聚焦到模块与测试类，不做无差别全量测试。
- 涉及模块边界调整时，额外执行：
  ```bash
  ./mvnw -pl gateway-core -q -DskipTests compile
  ./mvnw -pl gateway-admin -q -DskipTests compile
  ```
- 涉及启动装配、自动配置或前后端联调时，详细启动/黑盒入口以 `README.md` 和 `docs/testing/endpoint-blackbox-coverage.md` 为准。

## 文档维护要求

- 改动若影响项目状态、当前阶段计划、关键入口或验证方式，需同步维护对应文档：
  - `CONTEXT.md`：项目状态总览
  - `CONTEXT-plan.md`：当前阶段实施计划
  - `README.md`：启动、联调、构建、文档入口
  - `docs/api-reference.md` / `docs/usage.md`：对外使用与接口语义
- 不要把阶段性历史治理记录继续堆到 `AGENTS.md`；`AGENTS.md` 只保留长期有效的 agent 工作规则与文档入口。

## gstack 技能系统

gstack (v1.57.9) 已安装在 `~/.config/opencode/skills/gstack-*`，所有会话自动可用。按开发阶段选用：

| 阶段 | 技能 | 用途 |
|------|------|------|
| **构思** | `/office-hours` | YC式产品拷问，写设计文档 |
| **规划** | `/plan-ceo-review` `/plan-eng-review` `/plan-design-review` `/plan-devex-review` | 产品/架构/设计/DX 分维度审查 |
| **自动规划** | `/autoplan` | CEO→设计→工程→DX 一站式审查 |
| **规格** | `/spec` | 模糊意图→精确可执行规格 |
| **设计** | `/design-consultation` `/design-shotgun` `/design-html` `/design-review` | 设计系统 / 多方案探索 / 生产级 HTML |
| **审查** | `/review` `/codex` | 代码审查 + 跨模型二次意见 |
| **调试** | `/investigate` | 根因排查（3次修复失败自动停止） |
| **测试** | `/qa` `/qa-only` `/browse` | 真实浏览器 E2E 测试 |
| **安全** | `/cso` | OWASP Top 10 + STRIDE |
| **发布** | `/ship` `/land-and-deploy` `/canary` | 测试→PR→部署→生产验证 |
| **文档** | `/document-release` `/document-generate` | 自动同步/生成项目文档 |
| **反思** | `/retro` `/learn` `/health` | 周报沉淀 / 跨会话记忆 / 代码健康 |
| **安全防护** | `/careful` `/freeze` `/guard` | 防误删、编辑锁定、全防护 |

使用 tips：
- **优先 `/autoplan`**：代替逐一跑多个 review，自动判断哪些维度适用
- **仅后端改动**：无需跑设计审查，跳过 `/plan-design-review`
- **仅前端改样式**：无需工程审查，跳过 `/plan-eng-review`
- **Playwright 未就绪时** `/browse` `/qa` 不可用；运行 `cd ~/.config/opencode/skills/gstack && bunx playwright install chromium` 补装
