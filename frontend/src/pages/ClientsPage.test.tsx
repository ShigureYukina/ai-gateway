import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from '@/test/utils'
import type { ClientConfig, ClientsResponse, ProvidersResponse, RoutesResponse } from '@/types/api'
import ClientsPage from './ClientsPage'

const { mockList, mockUpsert, mockRemove, mockProvidersList, mockRoutesList } = vi.hoisted(() => ({
  mockList: vi.fn(),
  mockUpsert: vi.fn(),
  mockRemove: vi.fn(),
  mockProvidersList: vi.fn(),
  mockRoutesList: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    clients: {
      ...other.clients,
      list: (...args: unknown[]) => mockList(...args),
      upsert: (...args: unknown[]) => mockUpsert(...args),
      remove: (...args: unknown[]) => mockRemove(...args),
    },
    providers: {
      ...other.providers,
      list: (...args: unknown[]) => mockProvidersList(...args),
    },
    routes: {
      ...other.routes,
      list: (...args: unknown[]) => mockRoutesList(...args),
    },
  }
})

function createDeferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function createClientConfig(overrides: Partial<ClientConfig> = {}): ClientConfig {
  return {
    enabled: true,
    allowedModels: ['gpt-4o-mini'],
    allowedScenes: ['default-chat'],
    capabilities: { streaming: true },
    defaults: {
      temperature: 0.7,
      maxTokens: 256,
    },
    limits: {
      dailyTokens: 1000,
      dailyCost: 12.5,
      monthlyTokens: 30000,
      monthlyCost: 99.9,
      tokensPerMinute: 600,
    },
    ...overrides,
  }
}

function createClientsResponse(clients: Record<string, ClientConfig>): ClientsResponse {
  return {
    generatedAt: '2026-06-05T00:00:00Z',
    clients,
  }
}

function createProvidersResponse(): ProvidersResponse {
  return {
    generatedAt: '2026-06-05T00:00:00Z',
    providers: {
      openai: {
        type: 'openai',
        baseUrl: 'https://api.openai.com',
        enabled: true,
        models: ['gpt-4o-mini', 'gpt-4.1'],
      },
      anthropic: {
        type: 'anthropic',
        baseUrl: 'https://api.anthropic.com',
        enabled: true,
        models: ['claude-3-5-sonnet'],
      },
    },
  }
}

function createRoutesResponse(): RoutesResponse {
  return {
    generatedAt: '2026-06-05T00:00:00Z',
    routes: {
      'default-route': {
        provider: 'openai',
        upstreamModel: 'gpt-4o-mini',
        scene: 'default-chat',
        enabled: true,
      },
      'analysis-route': {
        provider: 'anthropic',
        upstreamModel: 'claude-3-5-sonnet',
        scene: 'analysis',
        enabled: true,
      },
    },
  }
}

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockList.mockReset()
  mockUpsert.mockReset()
  mockRemove.mockReset()
  mockProvidersList.mockReset()
  mockRoutesList.mockReset()
  vi.restoreAllMocks()
})

describe('ClientsPage', () => {
  it('renders loading then client data', async () => {
    const deferred = createDeferred<ClientsResponse>()
    mockList.mockReturnValue(deferred.promise)
    mockProvidersList.mockResolvedValue(createProvidersResponse())
    mockRoutesList.mockResolvedValue(createRoutesResponse())

    const { container } = renderWithProviders(<ClientsPage />)

    expect(container.querySelector('svg.animate-spin')).toBeInTheDocument()

    deferred.resolve(createClientsResponse({
      'alpha-client': createClientConfig(),
    }))

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Clients' })).toBeInTheDocument()
      expect(screen.getByText('alpha-client')).toBeInTheDocument()
      expect(screen.getByText('1 client(s) configured')).toBeInTheDocument()
      expect(screen.getByText('$12.5')).toBeInTheDocument()
      expect(container.querySelector('svg.animate-spin')).not.toBeInTheDocument()
    })
  })

  it('shows empty state', async () => {
    mockList.mockResolvedValue(createClientsResponse({}))
    mockProvidersList.mockResolvedValue(createProvidersResponse())
    mockRoutesList.mockResolvedValue(createRoutesResponse())

    renderWithProviders(<ClientsPage />)

    expect(await screen.findByText('No clients configured. Click "Add Client" to get started.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('opens create dialog and saves', async () => {
    const user = userEvent.setup()
    mockList
      .mockResolvedValueOnce(createClientsResponse({}))
      .mockResolvedValueOnce(createClientsResponse({
        'new-client-key': createClientConfig({
          defaults: { temperature: 0.9, maxTokens: 512 },
          limits: { dailyTokens: 4321 },
        }),
      }))
    mockProvidersList.mockResolvedValue(createProvidersResponse())
    mockRoutesList.mockResolvedValue(createRoutesResponse())
    mockUpsert.mockResolvedValue(undefined)

    renderWithProviders(<ClientsPage />)

    await screen.findByRole('button', { name: 'Add Client' })
    await user.click(screen.getByRole('button', { name: 'Add Client' }))

    const spinbuttons = screen.getAllByRole('spinbutton')
    const dailyTokenInput = spinbuttons[2]

    await user.type(screen.getByLabelText('Client Key'), 'new-client-key')
    await user.click(screen.getByRole('button', { name: 'gpt-4o-mini' }))
    await user.click(screen.getByRole('button', { name: 'default-chat' }))
    await user.clear(screen.getByLabelText('Default Temperature'))
    await user.type(screen.getByLabelText('Default Temperature'), '0.9')
    await user.clear(screen.getByLabelText('Default Max Tokens'))
    await user.type(screen.getByLabelText('Default Max Tokens'), '512')
    await user.type(dailyTokenInput!, '4321')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpsert).toHaveBeenCalledWith('new-client-key', {
        enabled: true,
        allowedModels: ['gpt-4o-mini'],
        allowedScenes: ['default-chat'],
        capabilities: { streaming: true },
        defaults: {
          temperature: 0.9,
          maxTokens: 512,
        },
        limits: {
          dailyTokens: 4321,
          monthlyTokens: undefined,
          dailyCost: undefined,
          monthlyCost: undefined,
          tokensPerMinute: undefined,
        },
      })
      expect(mockList).toHaveBeenCalledTimes(2)
    })
  })

  it('calls remove on delete', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    mockList
      .mockResolvedValueOnce(createClientsResponse({
        'alpha-client': createClientConfig(),
      }))
      .mockResolvedValueOnce(createClientsResponse({}))
    mockProvidersList.mockResolvedValue(createProvidersResponse())
    mockRoutesList.mockResolvedValue(createRoutesResponse())
    mockRemove.mockResolvedValue(undefined)

    renderWithProviders(<ClientsPage />)

    await screen.findByText('alpha-client')
    await user.click(screen.getByTitle('Delete'))

    await waitFor(() => {
      expect(mockRemove).toHaveBeenCalledWith('alpha-client')
      expect(mockList).toHaveBeenCalledTimes(2)
    })

    expect(await screen.findByText('No clients configured. Click "Add Client" to get started.')).toBeInTheDocument()
  })
})
