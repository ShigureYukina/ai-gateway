import type {
  AdminApiKeyCreateResponse,
  AdminApiKeyListResponse,
  ApiKeyToggleRequest,
  CreateApiKeyRequest,
  CreateUserRequest,
  ResetPasswordResponse,
  UpdateUserAllowedModelsRequest,
  UpdateUserLimitsRequest,
  UpdateUserRequest,
  UserView,
  UsersResponse,
} from '@/types/api'

import { request } from '../../http'

export const users = {
  list: () =>
    request<UsersResponse>('/admin/users'),
  create: (data: CreateUserRequest) =>
    request<UserView>('/admin/users', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  update: (username: string, data: UpdateUserRequest) =>
    request<UserView>(`/admin/users/${username}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateLimits: (username: string, data: UpdateUserLimitsRequest) =>
    request<UserView>(`/admin/users/${username}/limits`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateAllowedModels: (username: string, data: UpdateUserAllowedModelsRequest) =>
    request<UserView>(`/admin/users/${username}/allowed-models`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  remove: (username: string) =>
    request<void>(`/admin/users/${username}`, { method: 'DELETE' }),
  resetPassword: (username: string) =>
    request<ResetPasswordResponse>(`/admin/users/${username}/reset-password`, {
      method: 'POST',
    }),
  listApiKeys: async (username: string): Promise<AdminApiKeyListResponse> => {
    const apiKeys = await request<AdminApiKeyListResponse['apiKeys']>(`/admin/users/${username}/api-keys`)
    return { apiKeys }
  },
  createApiKey: (username: string, data: CreateApiKeyRequest) =>
    request<AdminApiKeyCreateResponse>(`/admin/users/${username}/api-keys`, {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  deleteApiKey: (username: string, keyId: string) =>
    request<void>(`/admin/users/${username}/api-keys/${keyId}`, { method: 'DELETE' }),
  toggleApiKey: (username: string, keyId: string, enabled: boolean) =>
    request<void>(`/admin/users/${username}/api-keys/${keyId}/toggle`, {
      method: 'PUT',
      body: JSON.stringify({ enabled } satisfies ApiKeyToggleRequest),
    }),
  rotateApiKey: (username: string, keyId: string) =>
    request<AdminApiKeyCreateResponse>(`/admin/users/${username}/api-keys/${keyId}/rotate`, {
      method: 'POST',
    }),
}
