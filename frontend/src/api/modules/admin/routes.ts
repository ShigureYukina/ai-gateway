import type { RouteConfig, RoutesResponse } from '@/types/api'

import { request } from '../../http'

export const routes = {
  list: () =>
    request<RoutesResponse>('/admin/routes'),
  upsert: (id: string, data: RouteConfig) =>
    request<RouteConfig>(`/admin/routes/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  remove: (id: string) =>
    request<void>(`/admin/routes/${id}`, { method: 'DELETE' }),
}
