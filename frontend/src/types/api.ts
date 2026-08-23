/* ===== Auth ===== */
export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
}

export interface RegisterRequest {
  username: string
  password: string
  displayName?: string
  email?: string
}

export interface RegisterResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  apiKey: string
}

export interface UserMeResponse {
  username: string
  role: string
  displayName: string | null
  email: string | null
  apiKeyMasked: string | null
  createdAt: number
  quota: MeQuotaResponse
}

export interface MeQuotaResponse {
  dailyTokensUsed: number
  dailyTokensLimit: number | null
  dailyCostUsed: number
  dailyCostLimit: number | null
  monthlyTokensUsed: number
  monthlyTokensLimit: number | null
  monthlyCostUsed: number
  monthlyCostLimit: number | null
  monthlyUnsupported: boolean
}

export interface UpdateProfileRequest {
  displayName?: string
  email?: string
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

/* ===== User Auth Keys (self-service) ===== */
export interface AuthKeyItem {
  keyId: string
  name: string
  apiKeyMasked: string
  enabled: boolean
  createdAt: number
  lastUsedAt: number | null
  requestCount: number
  allowedModels: string[]
}

export interface AuthKeysResponse {
  keys: AuthKeyItem[]
}

export interface AuthCreateKeyRequest {
  name: string
  allowedModels?: string[]
}

export interface AuthCreateKeyResponse {
  keyId: string
  name: string
  apiKey: string
  apiKeyMasked: string
  enabled: boolean
  createdAt: number
  allowedModels: string[]
}

/* ===== Dashboard ===== */
export interface DashboardOverview {
  generatedAt: string
  day: string
  systemStatus: {
    maintenanceActive: boolean
    emergencyRateLimitEnabled: boolean
    hasAvailableRoute: boolean
  }
  overview: {
    totalRequests: number
    totalTokens: number
    totalCost: number
    successRate: number
    activeClients: number
    registeredClients: number
    success2xx: number
    status4xx: number
    status5xx: number
    topModels: { model: string; requests: number; tokens: number; cost: number }[]
    topClients: { client: string; requests: number; tokens: number; cost: number }[]
  }
  tpmOverview: {
    clients: { client: string; usedTokens: number; limitTokens: number; utilizationPercent: number }[]
  }
}

/* ===== Provider ===== */
export interface ProviderConfig {
  type: string
  baseUrl: string
  apiKey?: string
  keys?: string[]
  keyWeights?: number[]
  timeout?: string
  enabled: boolean
  models?: string[]
}

export interface ProviderUpsertRequest {
  type?: string
  baseUrl?: string
  apiKey?: string
  timeoutSeconds?: number
  enabled?: boolean
  models?: string[]
}

export interface ProviderTestResponse {
  status: string
  latencyMs: number
  httpStatus: number | null
  error: string | null
}

export interface ProviderModelListResponse {
  provider: string
  models: string[]
}

export interface ProvidersResponse {
  generatedAt: string
  providers: Record<string, ProviderConfig>
}

/* ===== Route ===== */
export interface RouteConfig {
  provider: string
  upstreamModel: string
  upstreamModels?: string[]
  scene?: string
  strategy?: string
  fallbackRoutes?: string[]
  weight?: number
  enabled: boolean
}

export interface RoutesResponse {
  generatedAt: string
  routes: Record<string, RouteConfig>
}

/* ===== Model Group ===== */
export interface ModelGroupMember {
  routeId: string
  provider: string
  upstreamModel: string
  weight: number
}

export interface ModelGroupView {
  alias: string
  scene: string
  members: ModelGroupMember[]
  fallbackOrder: string[]
  capabilities?: Record<string, unknown>
  pricing?: Record<string, unknown>
}

export interface ModelGroupsResponse {
  generatedAt: string
  groups: Record<string, ModelGroupView>
}

export interface ModelGroupPutMember {
  provider: string
  upstreamModel: string
  weight: number
}

export interface ModelGroupPutRequest {
  members: ModelGroupPutMember[]
}

/* ===== Model Publication ===== */
export interface PublishModelRequest {
  provider: string
  upstreamModel: string
}

export interface PublicationPriceSummary {
  source: string | null
  matchedBy: string | null
  matchedModel: string | null
  unitPrice: number | null
  inputUnitPrice: number | null
  outputUnitPrice: number | null
  summary: string | null
}

export interface PublicationResponse {
  alias: string
  provider: string
  upstreamModel: string
  visibleInV1Models: boolean
  price: PublicationPriceSummary
  warnings: string[]
}

/* ===== Client ===== */
export interface ClientLimits {
  maxTokens?: number
  dailyTokens?: number
  monthlyTokens?: number
  tokensPerMinute?: number
  dailyCost?: number
  monthlyCost?: number
  requestsPerWindow?: number
}

export interface ClientConfig {
  enabled: boolean
  allowedModels?: string[]
  allowedScenes?: string[]
  modelScenes?: Record<string, string>
  defaults?: {
    scene?: string
    temperature?: number
    maxTokens?: number
  }
  capabilities?: {
    streaming?: boolean
  }
  limits?: ClientLimits
}

export interface ClientsResponse {
  generatedAt: string
  clients: Record<string, ClientConfig>
}

/* ===== User ===== */
export interface UserView {
  username: string
  role: string
  apiKeyMasked: string
  clientId: string
  ownerUserId?: string | null
  clientName?: string | null
  apiKeyId?: string | null
  displayName?: string | null
  email?: string | null
  frozen: boolean
  createdAt: number
  frozenAt?: number | null
  limits?: {
    dailyTokens?: number
    monthlyTokens?: number
    tokensPerMinute?: number
    maxTokens?: number
    dailyCost?: number
    monthlyCost?: number
  }
  allowedModels?: string[]
}

export interface UsersResponse {
  generatedAt: string
  users: UserView[]
}

export interface CreateUserRequest {
  username: string
  password: string
  role?: string
  displayName?: string
  email?: string
}

export interface CreateApiKeyRequest {
  name: string
  allowedModels?: string[]
}

export interface AdminApiKeyView {
  keyId: string
  name: string
  apiKeyMasked: string
  enabled: boolean
  createdAt: number
  lastUsedAt: number | null
  requestCount: number
  allowedModels: string[]
}

export interface AdminApiKeyListResponse {
  apiKeys: AdminApiKeyView[]
}

export interface AdminApiKeyCreateResponse {
  keyId: string
  name: string
  apiKey: string
  enabled: boolean
  createdAt: number
  lastUsedAt: number | null
  requestCount: number
  allowedModels: string[]
}

export interface ApiKeyToggleRequest {
  enabled: boolean
}

export interface ResetPasswordResponse {
  temporaryPassword: string
}

export interface UpdateUserRequest {
  role?: string
  frozen?: boolean
  displayName?: string
  email?: string
}

export interface UpdateUserLimitsRequest {
  dailyTokens?: number
  monthlyTokens?: number
  tokensPerMinute?: number
  maxTokens?: number
  dailyCost?: number
  monthlyCost?: number
}

export interface UpdateUserAllowedModelsRequest {
  allowedModels: string[]
}

/* ===== Load Balancer ===== */
export interface LoadBalancerConfigDto {
  enabled?: boolean
}

/* ===== Concurrent Limit ===== */
export interface ConcurrentLimitConfigDto {
  enabled?: boolean
  maxPerClient?: number
  maxGlobal?: number
}

/* ===== Tracing ===== */
export interface TraceConfigDto {
  enabled?: boolean
  maxBodySize?: number
  sampleRate?: number
}

/* ===== Sync (models.dev) ===== */
export interface ModelsDevConfigDto {
  enabled?: boolean
  endpoint?: string
  refreshInterval?: string
  timeout?: string
  runOnStartup?: boolean
  preferRemotePricing?: boolean
}

export interface SyncConfigDto {
  modelsDev?: ModelsDevConfigDto
}

/* ===== Provider Health ===== */
export interface ProviderHealthConfigDto {
  enabled?: boolean
  refreshInterval?: string
  runOnStartup?: boolean
  disableAfterConsecutiveFailures?: number
  recoverAfterConsecutiveSuccesses?: number
}

/* ===== Auth ===== */
export interface JwtConfigDto {
  accessExpiration?: string
  refreshExpiration?: string
}

export interface AuthRegistrationConfigDto {
  allowedModels?: string[]
  allowedScenes?: string[]
}

export interface AuthConfigDto {
  enabled?: boolean
  registrationMode?: string
  registration?: AuthRegistrationConfigDto
}

/* ===== Pricing Resolve ===== */
export interface PricingResolveResponse {
  model: string
  upstreamModel?: string
  provider?: string
  inputPer1k?: number
  outputPer1k?: number
  cachedInputPer1k?: number
  resolvedBy?: string
}

/* ===== System Config ===== */
export interface LimitConfigDto {
  requestsPerWindow?: number
  window?: string
}

export interface ResilienceConfigDto {
  maxAttempts?: number
  retryableFailureThreshold?: number
  failureWindow?: string
  openDuration?: string
  slowCallRateThreshold?: number
  slowCallDurationThreshold?: string
  slidingWindowType?: string
  slidingWindowSize?: number
  minimumNumberOfCalls?: number
  waitDurationInOpenState?: string
  permittedNumberOfCallsInHalfOpenState?: number
  bulkheadMaxConcurrent?: number
  bulkheadMaxWaitDuration?: string
  retryMaxAttempts?: number
  retryWaitDuration?: string
  retryExponentialBackoffMultiplier?: number
}

export interface ModelPricingDto {
  inputPer1k?: number
  outputPer1k?: number
  cachedInputPer1k?: number
}

export interface PricingConfigDto {
  default?: ModelPricingDto
  models?: Record<string, ModelPricingDto>
  exactMatches?: Record<string, string>
}

export interface OperationalEmergencyRateLimitDto {
  enabled?: boolean
  maxRequestsPerMinute?: number
}

export interface OperationalConfigDto {
  maintenanceMode?: boolean
  emergencyRateLimit?: OperationalEmergencyRateLimitDto
  maintenanceWhitelist?: string[]
}

export interface SystemConfig {
  limit?: LimitConfigDto
  resilience?: ResilienceConfigDto
  pricing?: PricingConfigDto
  operational?: OperationalConfigDto
  loadBalancer?: LoadBalancerConfigDto
  concurrentLimit?: ConcurrentLimitConfigDto
  tracing?: TraceConfigDto
  sync?: SyncConfigDto
  providerHealth?: ProviderHealthConfigDto
  auth?: AuthConfigDto
}

export interface ConfigExportResponse {
  providers: Record<string, ProviderConfig>
  routes: Record<string, RouteConfig>
  scenes: Record<string, {
    primaryRoute?: string | null
    fallbackRoutes?: string[]
  }>
  clients: Record<string, ClientConfig & { apiKeyMasked?: string | null }>
  system: SystemConfig
  pendingRestart: string[]
}

export interface ConfigImportResponse {
  imported?: number
  status?: string
  dryRun?: boolean
  validated?: boolean
  applied?: boolean
  errors?: string[]
  warnings?: string[]
  pendingRestart?: string[]
}

/* ===== Webhook ===== */
export interface WebhookEndpoint {
  id?: string
  name: string
  url: string
  enabled: boolean
  events: string[]
  hmacSecret?: string
}

/* ===== Alert ===== */
export interface AlertsResponse {
  generatedAt: string
  active: AlertView[]
  recent: AlertView[]
}

export interface AlertView {
  id: string
  type: string
  severity: string
  status: string
  message: string
  source: string
  detectedAt: string
  metadata?: Record<string, unknown>
}

/* ===== User Usage (self-service) ===== */
export interface UsageRecentResponse {
  generatedAt: string
  requests: UsageRequestEntry[]
}

export interface UsageRequestEntry {
  requestId: string
  clientId: string
  model: string
  provider: string
  routeId: string
  scene: string
  status: number
  latencyMs: number
  timestamp: string
  streamMode: string
  usageTokens: number | null
  promptTokens: number | null
  completionTokens: number | null
  costUsd: number | null
  errorMessage: string | null
}

export interface ModelCostDistributionResponse {
  client: string
  from: string
  to: string
  models: ModelCostEntry[]
}

export interface ModelCostEntry {
  model: string
  requests: number
  totalTokens: number
  promptTokens: number
  completionTokens: number
  totalCostUsd: number
}

/* ===== Request Log ===== */
export interface RecentRequestsResponse {
  generatedAt: string
  requests: RequestLogEntryView[]
  total: number
  offset: number
}

export interface RecentRequestsQuery {
  limit?: number
  model?: string
  client?: string
  status?: number
  offset?: number
}

export interface RequestLogEntryView {
  requestId: string
  clientId: string
  model: string
  provider: string
  routeId: string
  scene: string
  status: number
  latencyMs: number
  timestamp: string
  streamMode: string
  usageTokens: number | null
  promptTokens: number | null
  completionTokens: number | null
  costUsd: number | null
  errorMessage: string | null
}

/* ===== Config Audit ===== */
export interface AuditCenterResponse {
  generatedAt: string
  entries: AuditCenterEntry[]
}

export interface AuditCenterEntry {
  eventType: string
  timestamp: string
  actor: string
  resourceType: string
  resourceId: string
  action: string
  result: string
  reason: string | null
  requestId: string | null
  clientId: string | null
  model: string | null
  provider: string | null
  routeId: string | null
  scene: string | null
  status: number | null
  latencyMs: number | null
}
