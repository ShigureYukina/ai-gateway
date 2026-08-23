import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from '@/test/utils'
import SystemConfigPage from './SystemConfigPage'

const {
  mockUseQuery,
  mockExport,
  mockUpdateLimit,
  mockUpdateResilience,
  mockUpdatePricing,
  mockUpdateOperational,
  mockUpdateLoadBalancer,
  mockUpdateConcurrentLimit,
  mockUpdateTracing,
  mockUpdateSync,
  mockUpdateProviderHealth,
  mockUpdateAuth,
} = vi.hoisted(() => ({
  mockUseQuery: vi.fn(),
  mockExport: vi.fn(),
  mockUpdateLimit: vi.fn(),
  mockUpdateResilience: vi.fn(),
  mockUpdatePricing: vi.fn(),
  mockUpdateOperational: vi.fn(),
  mockUpdateLoadBalancer: vi.fn(),
  mockUpdateConcurrentLimit: vi.fn(),
  mockUpdateTracing: vi.fn(),
  mockUpdateSync: vi.fn(),
  mockUpdateProviderHealth: vi.fn(),
  mockUpdateAuth: vi.fn(),
}))

vi.mock('@tanstack/react-query', () => ({
  useQuery: (...args: unknown[]) => mockUseQuery(...args),
}))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    config: {
      ...other.config,
      export: (...args: unknown[]) => mockExport(...args),
    },
    systemConfig: {
      updateLimit: (...args: unknown[]) => mockUpdateLimit(...args),
      updateResilience: (...args: unknown[]) => mockUpdateResilience(...args),
      updatePricing: (...args: unknown[]) => mockUpdatePricing(...args),
      updateOperational: (...args: unknown[]) => mockUpdateOperational(...args),
      updateLoadBalancer: (...args: unknown[]) => mockUpdateLoadBalancer(...args),
      updateConcurrentLimit: (...args: unknown[]) => mockUpdateConcurrentLimit(...args),
      updateTracing: (...args: unknown[]) => mockUpdateTracing(...args),
      updateSync: (...args: unknown[]) => mockUpdateSync(...args),
      updateProviderHealth: (...args: unknown[]) => mockUpdateProviderHealth(...args),
      updateAuth: (...args: unknown[]) => mockUpdateAuth(...args),
    },
  }
})

function mockQueryData(system: Record<string, unknown> = {}) {
  mockUseQuery.mockImplementation(({ queryFn }: { queryFn?: () => unknown }) => {
    queryFn?.()
    return {
      data: { system },
      isLoading: false,
    }
  })
}

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockUseQuery.mockReset()
  mockExport.mockReset()
  mockUpdateLimit.mockReset()
  mockUpdateResilience.mockReset()
  mockUpdatePricing.mockReset()
  mockUpdateOperational.mockReset()
  mockUpdateLoadBalancer.mockReset()
  mockUpdateConcurrentLimit.mockReset()
  mockUpdateTracing.mockReset()
  mockUpdateSync.mockReset()
  mockUpdateProviderHealth.mockReset()
  mockUpdateAuth.mockReset()
  mockExport.mockResolvedValue({ system: {} })
})

describe('SystemConfigPage', () => {
  it('renders all four original config sections', async () => {
    const user = userEvent.setup()
    mockQueryData({})

    renderWithProviders(<SystemConfigPage />)

    expect(screen.getByText('System Configuration')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rate Limits' })).toBeInTheDocument()
    expect(screen.getByText('Rate Limit Configuration')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Resilience' }))
    expect(screen.getByText('Resilience / Circuit Breaker')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Pricing' }))
    expect(screen.getByText('Custom Pricing Overrides')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Operational' }))
    expect(screen.getByText('Operational Settings')).toBeInTheDocument()
  })

  it('renders all new config section tabs', async () => {
    mockQueryData({})

    renderWithProviders(<SystemConfigPage />)

    expect(screen.getByRole('button', { name: 'Load Balancer' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Concurrent Limit' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tracing' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sync (models.dev)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Provider Health' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Auth' })).toBeInTheDocument()
  })

  it('updates limit config on save', async () => {
    const user = userEvent.setup()
    mockQueryData({
      limit: {
        requestsPerWindow: 150,
        window: '5m',
      },
    })
    mockUpdateLimit.mockResolvedValue(undefined)

    renderWithProviders(<SystemConfigPage />)

    const requestsInput = screen.getByDisplayValue('150')
    const windowInput = screen.getByDisplayValue('5m')

    await user.clear(requestsInput)
    await user.type(requestsInput, '250')
    await user.clear(windowInput)
    await user.type(windowInput, '10m')
    await user.click(screen.getByRole('button', { name: 'Save Rate Limits' }))

    await waitFor(() => {
      expect(mockUpdateLimit).toHaveBeenCalledWith({
        requestsPerWindow: 250,
        window: '10m',
      })
    })
  })

  it('shows error toast on save failure', async () => {
    const user = userEvent.setup()
    mockQueryData({
      limit: {
        requestsPerWindow: 100,
        window: '1m',
      },
    })
    mockUpdateLimit.mockRejectedValue(new Error('Limit update failed'))

    renderWithProviders(<SystemConfigPage />, { withToaster: true })

    await user.click(screen.getByRole('button', { name: 'Save Rate Limits' }))

    await waitFor(() => {
      expect(screen.getByText('Failed to update')).toBeInTheDocument()
      expect(screen.getByText('Limit update failed')).toBeInTheDocument()
    })
  })

  /* ── 新增 section 测试 ── */

  it('renders and saves Load Balancer config', async () => {
    const user = userEvent.setup()
    mockQueryData({
      loadBalancer: { enabled: false },
    })
    mockUpdateLoadBalancer.mockResolvedValue(undefined)

    renderWithProviders(<SystemConfigPage />)

    await user.click(screen.getByRole('button', { name: 'Load Balancer' }))
    expect(screen.getByText('Load Balancer Configuration')).toBeInTheDocument()

    // 点击 Switch 启用
    const switchBtn = screen.getByRole('switch', { name: 'Enabled' })
    await user.click(switchBtn)

    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpdateLoadBalancer).toHaveBeenCalledWith({ enabled: true })
    })
  })

  it('renders and saves Concurrent Limit config', async () => {
    const user = userEvent.setup()
    mockQueryData({
      concurrentLimit: { enabled: false, maxPerClient: 10, maxGlobal: 200 },
    })
    mockUpdateConcurrentLimit.mockResolvedValue(undefined)

    renderWithProviders(<SystemConfigPage />)

    await user.click(screen.getByRole('button', { name: 'Concurrent Limit' }))
    expect(screen.getByText('Concurrent Request Limit')).toBeInTheDocument()

    // 修改 maxPerClient
    const input = screen.getByDisplayValue('10')
    await user.clear(input)
    await user.type(input, '20')

    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpdateConcurrentLimit).toHaveBeenCalledWith({
        enabled: false,
        maxPerClient: 20,
        maxGlobal: 200,
      })
    })
  })

  it('renders and saves Tracing config', async () => {
    const user = userEvent.setup()
    mockQueryData({
      tracing: { enabled: false, maxBodySize: 16384, sampleRate: 1.0 },
    })
    mockUpdateTracing.mockResolvedValue(undefined)

    renderWithProviders(<SystemConfigPage />)

    await user.click(screen.getByRole('button', { name: 'Tracing' }))
    expect(screen.getByText('Tracing / Observability')).toBeInTheDocument()

    // 修改 sampleRate
    const rateInput = screen.getByDisplayValue('1')
    await user.clear(rateInput)
    await user.type(rateInput, '0.5')

    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpdateTracing).toHaveBeenCalledWith({
        enabled: false,
        maxBodySize: 16384,
        sampleRate: 0.5,
      })
    })
  })

  it('renders and saves Sync config', async () => {
    const user = userEvent.setup()
    mockQueryData({
      sync: {
        modelsDev: {
          enabled: false,
          endpoint: 'https://models.dev/api.json',
          refreshInterval: 'PT30M',
          timeout: 'PT5S',
          runOnStartup: true,
          preferRemotePricing: true,
        },
      },
    })
    mockUpdateSync.mockResolvedValue(undefined)

    renderWithProviders(<SystemConfigPage />)

    await user.click(screen.getByRole('button', { name: 'Sync (models.dev)' }))
    expect(screen.getByText('Models.dev Sync')).toBeInTheDocument()

    // 修改 endpoint
    const endpointInput = screen.getByDisplayValue('https://models.dev/api.json')
    await user.clear(endpointInput)
    await user.type(endpointInput, 'https://custom.api/json')

    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpdateSync).toHaveBeenCalledWith({
        modelsDev: expect.objectContaining({
          enabled: false,
          endpoint: 'https://custom.api/json',
          refreshInterval: 'PT30M',
          timeout: 'PT5S',
          runOnStartup: true,
          preferRemotePricing: true,
        }),
      })
    })
  })

  it('renders and saves Provider Health config', async () => {
    const user = userEvent.setup()
    mockQueryData({
      providerHealth: {
        enabled: false,
        refreshInterval: 'PT5M',
        runOnStartup: true,
        disableAfterConsecutiveFailures: 3,
        recoverAfterConsecutiveSuccesses: 2,
      },
    })
    mockUpdateProviderHealth.mockResolvedValue(undefined)

    renderWithProviders(<SystemConfigPage />)

    await user.click(screen.getByRole('button', { name: 'Provider Health' }))
    expect(screen.getByText('Provider Health Check')).toBeInTheDocument()

    // 修改 disableAfterConsecutiveFailures
    const input = screen.getByDisplayValue('3')
    await user.clear(input)
    await user.type(input, '5')

    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpdateProviderHealth).toHaveBeenCalledWith({
        enabled: false,
        refreshInterval: 'PT5M',
        runOnStartup: true,
        disableAfterConsecutiveFailures: 5,
        recoverAfterConsecutiveSuccesses: 2,
      })
    })
  })

  it('renders and saves Auth config', async () => {
    const user = userEvent.setup()
    mockQueryData({
      auth: {
        enabled: true,
        registrationMode: 'restricted',
        registration: {
          allowedModels: ['gpt-4o-mini'],
          allowedScenes: ['default-chat'],
        },
      },
    })
    mockUpdateAuth.mockResolvedValue(undefined)

    renderWithProviders(<SystemConfigPage />)

    await user.click(screen.getByRole('button', { name: 'Auth' }))
    expect(screen.getByText('Authentication Settings')).toBeInTheDocument()

    // 修改 registration mode
    const select = screen.getByDisplayValue('restricted')
    await user.selectOptions(select, 'open')

    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpdateAuth).toHaveBeenCalledWith({
        enabled: true,
        registrationMode: 'open',
        registration: {
          allowedModels: ['gpt-4o-mini'],
          allowedScenes: ['default-chat'],
        },
      })
    })
  })

  it('shows error toast when new section save fails', async () => {
    const user = userEvent.setup()
    mockQueryData({
      tracing: { enabled: false, maxBodySize: 16384, sampleRate: 1.0 },
    })
    mockUpdateTracing.mockRejectedValue(new Error('Tracing update failed'))

    renderWithProviders(<SystemConfigPage />, { withToaster: true })

    await user.click(screen.getByRole('button', { name: 'Tracing' }))

    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(screen.getByText('Failed to update')).toBeInTheDocument()
      expect(screen.getByText('Tracing update failed')).toBeInTheDocument()
    })
  })
})
