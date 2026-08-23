import type { AlertsResponse, AuditCenterResponse, RecentRequestsQuery, RecentRequestsResponse } from '@/types/api'

import { request } from '../../http'
import { buildQuery } from './shared'

export const alerts = {
  list: () =>
    request<AlertsResponse>('/admin/alerts'),
}

export const requestLogs = {
  recent: (params?: RecentRequestsQuery) =>
    request<RecentRequestsResponse>(`/admin/requests/recent${buildQuery({
      limit: params?.limit,
      model: params?.model,
      client: params?.client,
      status: params?.status,
      offset: params?.offset,
    })}`),
}

export const configAudit = {
  center: (params?: { limit?: number; offset?: number; eventType?: string; configType?: string; operator?: string }) =>
    request<AuditCenterResponse>(`/internal/config/audit-center${buildQuery({
      limit: params?.limit,
      offset: params?.offset,
      eventType: params?.eventType,
      configType: params?.configType,
      operator: params?.operator,
    })}`),
}
