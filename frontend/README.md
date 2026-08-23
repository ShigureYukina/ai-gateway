# Frontend Admin UI

本目录是 **Simple AI Gateway** 的 React 管理后台，供管理员通过浏览器完成 provider / route / client / user / system config 等管理操作。

## 技术栈

- React 19
- TypeScript
- Vite
- React Router
- TanStack React Query
- Zustand

## 本地开发

前置条件：

- Node.js 20+
- 后端已在 `http://localhost:8081` 启动（推荐根目录执行）

```bash
cd frontend
npm ci
npm run dev
```

开发服务器默认地址通常为 `http://localhost:5173`。

`vite.config.ts` 已将以下路径代理到后端：

- `/auth`
- `/admin`
- `/internal`
- `/v1`
- `/healthz`

因此本地联调时通常无需额外配置跨域或网关地址。

## 常用脚本

```bash
npm run dev      # 启动 Vite 开发服务器
npm run build    # 生产构建
npm run lint     # ESLint 检查
npm run test     # 运行 Vitest
```

## 与后端打包关系

根工程执行以下命令时，`bootstrap` 模块会在打包阶段自动构建前端并把产物拷贝到后端静态资源目录：

```bash
./mvnw -pl bootstrap -am package
```

如果当前只想验证后端，可跳过前端构建：

```bash
./mvnw -pl bootstrap -am package -DskipFrontendBuild=true
```

## 启动与排查建议

- 若页面能打开但接口报错，先确认后端 `http://localhost:8081/healthz` 可达。
- 若联调接口 401，优先检查是否已通过 `/auth/login` 获取有效 token。
- 若只想了解完整操作流，优先阅读根目录 [`docs/usage.md`](../docs/usage.md)。
