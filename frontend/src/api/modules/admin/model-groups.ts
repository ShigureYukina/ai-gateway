import type { ModelGroupPutRequest, ModelGroupsResponse } from '@/types/api'

import { request } from '../../http'

export const modelGroups = {
  list: () =>
    request<ModelGroupsResponse>('/admin/model-groups'),
  upsert: (alias: string, data: ModelGroupPutRequest) =>
    request<ModelGroupPutRequest>(`/admin/model-groups/${alias}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  remove: (alias: string) =>
    request<void>(`/admin/model-groups/${alias}`, { method: 'DELETE' }),
}
