import type {
  ProviderConfig,
  ProviderModelListResponse,
  ProviderTestResponse,
  ProvidersResponse,
  ProviderUpsertRequest,
} from '@/types/api'

import { request } from '../../http'

export const providers = {
  list: () =>
    request<ProvidersResponse>('/admin/providers'),
  get: (name: string) =>
    request<{ provider: ProviderConfig }>(`/admin/providers/${name}`),
  upsert: (name: string, data: ProviderUpsertRequest) =>
    request<ProviderConfig>(`/admin/providers/${name}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  remove: (name: string) =>
    request<void>(`/admin/providers/${name}`, { method: 'DELETE' }),
  test: (name: string) =>
    request<ProviderTestResponse>(`/admin/providers/${name}/test`, {
      method: 'POST',
    }),
  listModels: (name: string) =>
    request<ProviderModelListResponse>(`/admin/providers/${name}/models`),
  fetchModels: (name: string) =>
    request<ProviderModelListResponse>(`/admin/providers/${name}/models/fetch`, {
      method: 'POST',
    }),
  updateModels: (name: string, models: string[]) =>
    request<void>(`/admin/providers/${name}/models`, {
      method: 'PUT',
      body: JSON.stringify({ models }),
    }),
}
