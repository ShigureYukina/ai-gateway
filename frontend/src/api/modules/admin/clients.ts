import type { ClientConfig, ClientsResponse } from '@/types/api'

import { request } from '../../http'

export const clients = {
  list: () =>
    request<ClientsResponse>('/admin/clients'),
  upsert: (key: string, data: ClientConfig) =>
    request<ClientConfig>(`/admin/clients/${key}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  remove: (key: string) =>
    request<void>(`/admin/clients/${key}`, { method: 'DELETE' }),
}
