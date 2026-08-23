import type { WebhookEndpoint } from '@/types/api'

import { request } from '../../http'

interface WebhookListResponse {
  generatedAt: string
  endpoints: WebhookEndpoint[]
}

export const webhooks = {
  list: () =>
    request<WebhookListResponse>('/admin/webhooks'),
  create: (data: WebhookEndpoint) =>
    request<WebhookEndpoint>('/admin/webhooks', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  update: (id: string, data: WebhookEndpoint) =>
    request<WebhookEndpoint>(`/admin/webhooks/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  remove: (id: string) =>
    request<void>(`/admin/webhooks/${id}`, { method: 'DELETE' }),
}
