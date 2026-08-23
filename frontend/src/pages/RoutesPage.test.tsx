import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from '@/test/utils'
import type { RouteConfig, RoutesResponse } from '@/types/api'
import RoutesPage from './RoutesPage'

const { mockList, mockRemove } = vi.hoisted(() => ({
  mockList: vi.fn(),
  mockRemove: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    routes: {
      ...other.routes,
      list: (...args: unknown[]) => mockList(...args),
      remove: (...args: unknown[]) => mockRemove(...args),
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

function createRouteConfig(overrides: Partial<RouteConfig> = {}): RouteConfig {
  return {
    provider: 'openai-main',
    upstreamModel: 'gpt-4o-mini',
    upstreamModels: ['gpt-4o-mini'],
    scene: 'default-chat',
    strategy: 'round-robin',
    weight: 1,
    enabled: true,
    ...overrides,
  }
}

function createRoutesResponse(routesMap: Record<string, RouteConfig>): RoutesResponse {
  return {
    generatedAt: '2026-06-05T00:00:00Z',
    routes: routesMap,
  }
}

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockList.mockReset()
  mockRemove.mockReset()
})

describe('RoutesPage', () => {
  it('renders loading state then routes after load', async () => {
    const deferred = createDeferred<RoutesResponse>()
    mockList.mockReturnValue(deferred.promise)

    const { container } = renderWithProviders(<RoutesPage />)

    expect(container.querySelector('svg.animate-spin')).toBeInTheDocument()

    deferred.resolve(createRoutesResponse({
      'route-alpha': createRouteConfig(),
    }))

    await waitFor(() => {
      expect(screen.getByText('route-alpha')).toBeInTheDocument()
      expect(screen.getByText('openai-main')).toBeInTheDocument()
      expect(screen.getByText('gpt-4o-mini')).toBeInTheDocument()
    })
  })

  it('shows empty state when no routes', async () => {
    mockList.mockResolvedValue(createRoutesResponse({}))

    renderWithProviders(<RoutesPage />)

    await waitFor(() => {
      expect(screen.getByText('No model routes configured. Click "Add Model Route" to get started.')).toBeInTheDocument()
    })
  })

  it('renders route entries in table', async () => {
    mockList.mockResolvedValue(createRoutesResponse({
      'route-openai': createRouteConfig({
        provider: 'openai-main',
        upstreamModel: 'gpt-4o-mini',
        upstreamModels: ['gpt-4o-mini', 'gpt-4o'],
        scene: 'default-chat',
        strategy: 'weighted',
        weight: 3,
        enabled: true,
      }),
      'route-anthropic': createRouteConfig({
        provider: 'anthropic-main',
        upstreamModel: 'claude-3-5-sonnet',
        upstreamModels: undefined,
        scene: undefined,
        strategy: undefined,
        weight: undefined,
        enabled: false,
      }),
    }))

    renderWithProviders(<RoutesPage />)

    const openaiRow = await screen.findByRole('row', { name: /route-openai/i })
    expect(within(openaiRow).getByText('openai-main')).toBeInTheDocument()
    expect(within(openaiRow).getByText('gpt-4o-mini')).toBeInTheDocument()
    expect(within(openaiRow).getByText('gpt-4o')).toBeInTheDocument()
    expect(within(openaiRow).getByText('weighted')).toBeInTheDocument()
    expect(within(openaiRow).getByText('default-chat')).toBeInTheDocument()
    expect(within(openaiRow).getByText('3')).toBeInTheDocument()
    expect(within(openaiRow).getByText('Enabled')).toBeInTheDocument()

    const anthropicRow = screen.getByRole('row', { name: /route-anthropic/i })
    expect(within(anthropicRow).getByText('anthropic-main')).toBeInTheDocument()
    expect(within(anthropicRow).getByText('claude-3-5-sonnet')).toBeInTheDocument()
    expect(within(anthropicRow).getByText('round-robin')).toBeInTheDocument()
    expect(within(anthropicRow).getByText('—')).toBeInTheDocument()
    expect(within(anthropicRow).getByText('1')).toBeInTheDocument()
    expect(within(anthropicRow).getByText('Disabled')).toBeInTheDocument()
  })

  it('opens create dialog on add button', async () => {
    const user = userEvent.setup()
    mockList.mockResolvedValue(createRoutesResponse({}))

    renderWithProviders(<RoutesPage />)

    await screen.findByText('Add Model Route')
    await user.click(screen.getByRole('button', { name: /add model route/i }))

    await waitFor(() => {
      expect(screen.getAllByText('Add Model Route')[0]).toBeInTheDocument()
      expect(screen.getByText('A model route maps published model requests to upstream channel models')).toBeInTheDocument()
    })
  })

  it('calls remove and reloads on delete', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    mockList
      .mockResolvedValueOnce(createRoutesResponse({
        'route-alpha': createRouteConfig(),
      }))
      .mockResolvedValueOnce(createRoutesResponse({}))
    mockRemove.mockResolvedValue(undefined)

    renderWithProviders(<RoutesPage />)

    await screen.findByText('route-alpha')
    const deleteButtons = screen.getAllByTitle('Delete')
    await user.click(deleteButtons[0]!)

    await waitFor(() => {
      expect(confirmSpy).toHaveBeenCalled()
      expect(mockRemove).toHaveBeenCalledWith('route-alpha')
      expect(mockList).toHaveBeenCalledTimes(2)
    })

    confirmSpy.mockRestore()
  })

  it('shows error toast on list failure', async () => {
    mockList.mockRejectedValue(new Error('Failed to fetch routes'))

    renderWithProviders(<RoutesPage />)

    await waitFor(() => {
      expect(screen.getByText('Error')).toBeInTheDocument()
      expect(screen.getByText('Failed to fetch routes')).toBeInTheDocument()
    })
  })
})
