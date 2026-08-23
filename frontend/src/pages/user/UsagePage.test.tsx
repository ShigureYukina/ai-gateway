import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from '@/test/utils'
import type { ModelCostEntry, UsageRequestEntry } from '@/types/api'
import UserUsagePage from './UsagePage'

const { mockUsageRecent, mockUsageCosts } = vi.hoisted(() => ({
  mockUsageRecent: vi.fn(),
  mockUsageCosts: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    auth: {
      ...other.auth,
      usageRecent: (...args: unknown[]) => mockUsageRecent(...args),
      usageCosts: (...args: unknown[]) => mockUsageCosts(...args),
    },
  }
})

function createEntry(overrides: Partial<UsageRequestEntry> = {}): UsageRequestEntry {
  return {
    requestId: 'req-1',
    clientId: 'client-1',
    model: 'gpt-4o-mini',
    provider: 'openai',
    routeId: 'gpt-4o-mini',
    scene: 'default-chat',
    status: 200,
    latencyMs: 123,
    timestamp: '2026-06-05T10:00:00Z',
    streamMode: 'non-stream',
    usageTokens: 15,
    promptTokens: 10,
    completionTokens: 5,
    costUsd: 0.001,
    errorMessage: null,
    ...overrides,
  }
}

function createCostEntry(overrides: Partial<ModelCostEntry> = {}): ModelCostEntry {
  return {
    model: 'gpt-4o-mini',
    requests: 3,
    totalTokens: 45,
    promptTokens: 30,
    completionTokens: 15,
    totalCostUsd: 0.003,
    ...overrides,
  }
}

function createDeferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockUsageRecent.mockReset()
  mockUsageCosts.mockReset()
})

describe('UserUsagePage', () => {
  it('renders loading then shows requests and costs tabs', async () => {
    const recentDeferred = createDeferred<{ generatedAt: string; requests: UsageRequestEntry[] }>()
    const costsDeferred = createDeferred<{ client: string; from: string; to: string; models: ModelCostEntry[] }>()
    mockUsageRecent.mockReturnValue(recentDeferred.promise)
    mockUsageCosts.mockReturnValue(costsDeferred.promise)

    const user = userEvent.setup()
    const { container } = renderWithProviders(<UserUsagePage />)

    expect(container.querySelector('svg.animate-spin')).toBeInTheDocument()

    recentDeferred.resolve({
      generatedAt: '2026-06-05T10:00:00Z',
      requests: [createEntry()],
    })
    costsDeferred.resolve({
      client: 'client-1',
      from: '2026-06-05',
      to: '2026-06-05',
      models: [createCostEntry()],
    })

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Usage & Costs' })).toBeInTheDocument()
      expect(screen.getAllByText('Recent Requests').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('Cost Breakdown').length).toBeGreaterThanOrEqual(1)
      expect(screen.getByText('Latency')).toBeInTheDocument()
    })

    await user.click(screen.getAllByText('Cost Breakdown')[0]!)

    await waitFor(() => {
      expect(screen.getByText('Total Cost')).toBeInTheDocument()
      expect(screen.getByText('45')).toBeInTheDocument()
    })
  })

  it('shows empty state for requests', async () => {
    mockUsageRecent.mockResolvedValue({
      generatedAt: '2026-06-05T10:00:00Z',
      requests: [],
    })
    mockUsageCosts.mockResolvedValue({
      client: 'client-1',
      from: '2026-06-05',
      to: '2026-06-05',
      models: [],
    })

    renderWithProviders(<UserUsagePage />)

    expect(await screen.findByText('No requests yet.')).toBeInTheDocument()
  })

  it('requests table renders entries', async () => {
    mockUsageRecent.mockResolvedValue({
      generatedAt: '2026-06-05T10:00:00Z',
      requests: [
        createEntry(),
        createEntry({
          requestId: 'req-2',
          model: 'claude-3-5-sonnet',
          status: 500,
          latencyMs: 456,
          usageTokens: 27,
          costUsd: 0.0042,
        }),
      ],
    })
    mockUsageCosts.mockResolvedValue({
      client: 'client-1',
      from: '2026-06-05',
      to: '2026-06-05',
      models: [],
    })

    renderWithProviders(<UserUsagePage />)

    expect(await screen.findByText('claude-3-5-sonnet')).toBeInTheDocument()

    const tables = screen.getAllByRole('table')
    const requestsTable = tables[0]
    expect(within(requestsTable).getByText('gpt-4o-mini')).toBeInTheDocument()
    expect(within(requestsTable).getByText('claude-3-5-sonnet')).toBeInTheDocument()
    expect(within(requestsTable).getByText('200')).toBeInTheDocument()
    expect(within(requestsTable).getByText('500')).toBeInTheDocument()
    expect(within(requestsTable).getByText('123ms')).toBeInTheDocument()
    expect(within(requestsTable).getByText('456ms')).toBeInTheDocument()
    expect(within(requestsTable).getByText('15')).toBeInTheDocument()
    expect(within(requestsTable).getByText('27')).toBeInTheDocument()
    expect(within(requestsTable).getByText('$0.001000')).toBeInTheDocument()
    expect(within(requestsTable).getByText('$0.004200')).toBeInTheDocument()
  })

  it('costs tab loads and renders', async () => {
    const user = userEvent.setup()
    mockUsageRecent.mockResolvedValue({
      generatedAt: '2026-06-05T10:00:00Z',
      requests: [createEntry({ model: 'request-model' })],
    })
    mockUsageCosts.mockResolvedValue({
      client: 'client-1',
      from: '2026-06-01',
      to: '2026-06-05',
      models: [
        createCostEntry(),
        createCostEntry({
          model: 'claude-3-5-sonnet',
          requests: 7,
          totalTokens: 120,
          totalCostUsd: 0.0145,
        }),
      ],
    })

    renderWithProviders(<UserUsagePage />)

    await screen.findByText('request-model')
    await user.click(screen.getAllByText('Cost Breakdown')[0]!)

    await waitFor(() => {
      expect(screen.getByText('claude-3-5-sonnet')).toBeInTheDocument()
    })

    const tables = screen.getAllByRole('table')
    const costsTable = tables[tables.length - 1]
    expect(within(costsTable).getByText('gpt-4o-mini')).toBeInTheDocument()
    expect(within(costsTable).getByText('claude-3-5-sonnet')).toBeInTheDocument()
    expect(within(costsTable).getByText('3')).toBeInTheDocument()
    expect(within(costsTable).getByText('7')).toBeInTheDocument()
    expect(within(costsTable).getByText('45')).toBeInTheDocument()
    expect(within(costsTable).getByText('120')).toBeInTheDocument()
    expect(within(costsTable).getByText('$0.003000')).toBeInTheDocument()
    expect(within(costsTable).getByText('$0.014500')).toBeInTheDocument()
  })

  it('refresh button re-fetches requests', async () => {
    const user = userEvent.setup()
    mockUsageRecent.mockResolvedValue({
      generatedAt: '2026-06-05T10:00:00Z',
      requests: [createEntry()],
    })
    mockUsageCosts.mockResolvedValue({
      client: 'client-1',
      from: '2026-06-05',
      to: '2026-06-05',
      models: [],
    })

    renderWithProviders(<UserUsagePage />)

    await screen.findByText('gpt-4o-mini')
    expect(mockUsageRecent).toHaveBeenCalledTimes(1)
    expect(mockUsageRecent).toHaveBeenNthCalledWith(1, { limit: 50 })

    await user.click(screen.getAllByRole('button', { name: 'Refresh' })[0]!)

    await waitFor(() => {
      expect(mockUsageRecent).toHaveBeenCalledTimes(2)
      expect(mockUsageRecent).toHaveBeenLastCalledWith({ limit: 50 })
    })
  })

  it('handles API error', async () => {
    const user = userEvent.setup()
    mockUsageRecent.mockRejectedValue(new Error('recent failed'))
    mockUsageCosts.mockResolvedValue({
      client: 'client-1',
      from: '2026-06-05',
      to: '2026-06-05',
      models: [createCostEntry()],
    })

    renderWithProviders(<UserUsagePage />)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Usage & Costs' })).toBeInTheDocument()
      expect(screen.getByText('No requests yet.')).toBeInTheDocument()
    })

    await user.click(screen.getAllByText('Cost Breakdown')[0]!)

    await waitFor(() => {
      expect(screen.getByText('$0.003000')).toBeInTheDocument()
    })
  })
})
