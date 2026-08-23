/**
 * admin 模块聚合出口：保持 `@/api/client` 兼容。
 */

export { dashboard } from './admin/dashboard'
export { providers } from './admin/providers'
export { routes } from './admin/routes'
export { clients } from './admin/clients'
export { users } from './admin/users'
export { modelGroups } from './admin/model-groups'
export { publications } from './admin/publications'
export { systemConfig } from './admin/system'
export { alerts, requestLogs, configAudit } from './admin/observability'
export { webhooks } from './admin/webhooks'
export { config } from './admin/config'
