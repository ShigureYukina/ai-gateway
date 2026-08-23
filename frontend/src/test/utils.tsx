import { type ReactElement } from 'react'
import { render, type RenderOptions } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import { I18nProvider } from '@/i18n'
import { Toaster } from '@/components/ui/toast'

function AllTheProviders({ children }: { children: React.ReactNode }) {
  return (
    <Toaster>
      <BrowserRouter>
        <I18nProvider>{children}</I18nProvider>
      </BrowserRouter>
    </Toaster>
  )
}

function renderWithProviders(
  ui: ReactElement,
  options?: Omit<RenderOptions, 'wrapper'>,
) {
  return render(ui, { wrapper: AllTheProviders, ...options })
}

/**
 * Creates a factory function that generates complete mock API module.
 * Each test can override specific exports via factory parameter.
 */
export function createApiClientMock(
  overrides?: Partial<Record<keyof typeof import('@/api/client'), unknown>>,
) {
  return {
    auth: {
      login: vi.fn(),
      register: vi.fn(),
      me: vi.fn(),
      updateProfile: vi.fn(),
      changePassword: vi.fn(),
      listKeys: vi.fn(),
      createKey: vi.fn(),
      deleteKey: vi.fn(),
      usageRecent: vi.fn(),
      usageCosts: vi.fn(),
    },
    dashboard: { overview: vi.fn() },
    providers: {
      list: vi.fn(),
      get: vi.fn(),
      upsert: vi.fn(),
      remove: vi.fn(),
      test: vi.fn(),
      listModels: vi.fn(),
      fetchModels: vi.fn(),
      updateModels: vi.fn(),
    },
    routes: { list: vi.fn(), upsert: vi.fn(), remove: vi.fn() },
    modelGroups: { list: vi.fn(), upsert: vi.fn(), remove: vi.fn() },
    publications: { publish: vi.fn() },
    clients: {
      list: vi.fn(),
      upsert: vi.fn(),
      remove: vi.fn(),
    },
    users: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      updateLimits: vi.fn(),
      updateAllowedModels: vi.fn(),
      remove: vi.fn(),
      resetPassword: vi.fn(),
      listApiKeys: vi.fn(),
      createApiKey: vi.fn(),
      deleteApiKey: vi.fn(),
      toggleApiKey: vi.fn(),
      rotateApiKey: vi.fn(),
    },
    systemConfig: {
      updateLimit: vi.fn(),
      updateResilience: vi.fn(),
      updatePricing: vi.fn(),
      updateOperational: vi.fn(),
      resolvePricing: vi.fn(),
      updateLoadBalancer: vi.fn(),
      updateConcurrentLimit: vi.fn(),
      updateTracing: vi.fn(),
      updateSync: vi.fn(),
      updateProviderHealth: vi.fn(),
      updateAuth: vi.fn(),
    },
    alerts: { list: vi.fn() },
    requestLogs: { recent: vi.fn() },
    configAudit: { center: vi.fn() },
    webhooks: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
    },
    config: { export: vi.fn(), import_: vi.fn() },
    setTokenProvider: vi.fn(),
    setRefreshTokenProvider: vi.fn(),
    setTokenUpdater: vi.fn(),
    setOnAuthFailure: vi.fn(),
    ...overrides,
  }
}

export * from '@testing-library/react'
export { renderWithProviders }
