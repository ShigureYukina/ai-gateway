/**
 * auth 模块：封装登录、注册、个人资料与用户自助接口。
 */
import type {
  AuthCreateKeyRequest,
  AuthCreateKeyResponse,
  AuthKeysResponse,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  ModelCostDistributionResponse,
  RegisterRequest,
  RegisterResponse,
  UpdateProfileRequest,
  UsageRecentResponse,
  UserMeResponse,
} from '@/types/api'

import { request } from '../http'

export const auth = {
  login: (data: LoginRequest) =>
    request<LoginResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  register: (data: RegisterRequest) =>
    request<RegisterResponse>('/auth/register', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  me: () =>
    request<UserMeResponse>('/auth/me'),
  updateProfile: (data: UpdateProfileRequest) =>
    request<UserMeResponse>('/auth/profile', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  changePassword: (data: ChangePasswordRequest) =>
    request<void>('/auth/password', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  listKeys: () =>
    request<AuthKeysResponse>('/auth/keys'),
  createKey: (data: AuthCreateKeyRequest) =>
    request<AuthCreateKeyResponse>('/auth/keys', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  deleteKey: (keyId: string) =>
    request<void>(`/auth/keys/${keyId}`, { method: 'DELETE' }),
  usageRecent: (params?: { limit?: number; model?: string; status?: number }) => {
    const qs = new URLSearchParams()
    if (params?.limit) qs.set('limit', String(params.limit))
    if (params?.model) qs.set('model', params.model)
    if (params?.status) qs.set('status', String(params.status))
    const query = qs.toString()
    return request<UsageRecentResponse>(`/auth/usage/recent${query ? `?${query}` : ''}`)
  },
  usageCosts: (params?: { from?: string; to?: string }) => {
    const qs = new URLSearchParams()
    if (params?.from) qs.set('from', params.from)
    if (params?.to) qs.set('to', params.to)
    const query = qs.toString()
    return request<ModelCostDistributionResponse>(`/auth/usage/costs${query ? `?${query}` : ''}`)
  },
}
