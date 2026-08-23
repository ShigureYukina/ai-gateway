import type {
  AuthConfigDto,
  ConcurrentLimitConfigDto,
  LimitConfigDto,
  LoadBalancerConfigDto,
  OperationalConfigDto,
  PricingConfigDto,
  PricingResolveResponse,
  ProviderHealthConfigDto,
  ResilienceConfigDto,
  SyncConfigDto,
  TraceConfigDto,
} from '@/types/api'

import { request } from '../../http'

export const systemConfig = {
  updateLimit: (data: LimitConfigDto) =>
    request<LimitConfigDto>('/admin/system/limit', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateResilience: (data: ResilienceConfigDto) =>
    request<ResilienceConfigDto>('/admin/system/resilience', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updatePricing: (data: PricingConfigDto) =>
    request<PricingConfigDto>('/admin/system/pricing', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateOperational: (data: OperationalConfigDto) =>
    request<OperationalConfigDto>('/admin/system/operational', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  /** 解析指定模型/上游/渠道的定价规则 */
  resolvePricing: (params: { model: string; upstreamModel?: string; provider?: string }) => {
    const qs = new URLSearchParams()
    qs.set('model', params.model)
    if (params.upstreamModel) qs.set('upstreamModel', params.upstreamModel)
    if (params.provider) qs.set('provider', params.provider)
    return request<PricingResolveResponse>(`/admin/system/pricing/resolve?${qs.toString()}`)
  },
  updateLoadBalancer: (data: LoadBalancerConfigDto) =>
    request<LoadBalancerConfigDto>('/admin/system/load-balancer', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateConcurrentLimit: (data: ConcurrentLimitConfigDto) =>
    request<ConcurrentLimitConfigDto>('/admin/system/concurrent-limit', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateTracing: (data: TraceConfigDto) =>
    request<TraceConfigDto>('/admin/system/tracing', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateSync: (data: SyncConfigDto) =>
    request<SyncConfigDto>('/admin/system/sync', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateProviderHealth: (data: ProviderHealthConfigDto) =>
    request<ProviderHealthConfigDto>('/admin/system/provider-health', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  updateAuth: (data: AuthConfigDto) =>
    request<AuthConfigDto>('/admin/system/auth', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
}
