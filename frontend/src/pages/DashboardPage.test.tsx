import { renderWithProviders, screen, waitFor } from '@/test/utils'
import DashboardPage from './DashboardPage'

const mockOverview = vi.fn()

vi.mock('@/api/client', () => ({
  dashboard: { overview: (...args: unknown[]) => mockOverview(...args) },
  setTokenProvider: vi.fn(),
  setRefreshTokenProvider: vi.fn(),
  setTokenUpdater: vi.fn(),
  setOnAuthFailure: vi.fn(),
}))

const sampleData = {
  generatedAt: '2026-06-04T12:00:00Z',
  day: '2026-06-04',
  systemStatus: {
    maintenanceActive: false,
    emergencyRateLimitEnabled: false,
    hasAvailableRoute: true,
  },
  overview: {
    totalRequests: 15000,
    totalTokens: 500000,
    totalCost: 12.5,
    successRate: 98.7,
    activeClients: 5,
    registeredClients: 10,
    success2xx: 14000,
    status4xx: 800,
    status5xx: 200,
    topModels: [
      { model: 'gpt-4', requests: 8000, tokens: 300000, cost: 8.0 },
      { model: 'gpt-3.5', requests: 7000, tokens: 200000, cost: 4.5 },
    ],
    topClients: [
      { client: 'client-a', requests: 10000, tokens: 350000, cost: 9.0 },
      { client: 'client-b', requests: 5000, tokens: 150000, cost: 3.5 },
    ],
  },
  tpmOverview: {
    clients: [
      { client: 'client-a', usedTokens: 8000, limitTokens: 10000, utilizationPercent: 80.0 },
    ],
  },
}

beforeEach(() => {
  mockOverview.mockReset()
})

describe('DashboardPage', () => {
  it('renders stat cards with formatted data', async () => {
    mockOverview.mockResolvedValue(sampleData)
    renderWithProviders(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('15,000')).toBeInTheDocument() // totalRequests
      expect(screen.getByText('500,000')).toBeInTheDocument() // totalTokens
      expect(screen.getByText('$12.5000')).toBeInTheDocument() // totalCost
      expect(screen.getByText('98.7%')).toBeInTheDocument() // successRate
      expect(screen.getByText('14,000')).toBeInTheDocument() // 2xx
      expect(screen.getByText('800')).toBeInTheDocument() // 4xx
      expect(screen.getByText('200')).toBeInTheDocument() // 5xx
      expect(screen.getByText('5')).toBeInTheDocument() // activeClients
    })
  })

  it('renders top models table', async () => {
    mockOverview.mockResolvedValue(sampleData)
    renderWithProviders(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('gpt-4')).toBeInTheDocument()
      expect(screen.getByText('gpt-3.5')).toBeInTheDocument()
    })
  })

  it('renders top clients table', async () => {
    mockOverview.mockResolvedValue(sampleData)
    renderWithProviders(<DashboardPage />)

    await waitFor(() => {
      // client-a appears in both Top Clients and TPM tables
      const clients = screen.getAllByText('client-a')
      expect(clients.length).toBeGreaterThanOrEqual(1)
      expect(screen.getByText('client-b')).toBeInTheDocument()
    })
  })

  it('renders TPM utilization section', async () => {
    mockOverview.mockResolvedValue(sampleData)
    renderWithProviders(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Tokens Per Minute')).toBeInTheDocument()
      expect(screen.getByText('80.0%')).toBeInTheDocument()
    })
  })

  it('hides TPM section when no data', async () => {
    const noTpm = { ...sampleData, tpmOverview: { clients: [] } }
    mockOverview.mockResolvedValue(noTpm)
    renderWithProviders(<DashboardPage />)

    await waitFor(() => {
      expect(screen.queryByText('Tokens Per Minute')).not.toBeInTheDocument()
    })
  })

  it('renders system status badges', async () => {
    mockOverview.mockResolvedValue(sampleData)
    renderWithProviders(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Active')).toBeInTheDocument()
      expect(screen.getByText('Routes Available')).toBeInTheDocument()
    })
  })

  it('shows error text on API failure', async () => {
    mockOverview.mockRejectedValue(new Error('Network error'))
    renderWithProviders(<DashboardPage />)

    await waitFor(() => {
      expect(screen.getByText(/Network error/)).toBeInTheDocument()
    })
  })

  it('shows empty table state when top models list is empty', async () => {
    const emptyModels = {
      ...sampleData,
      overview: { ...sampleData.overview, topModels: [] },
    }
    mockOverview.mockResolvedValue(emptyModels)
    renderWithProviders(<DashboardPage />)

    await waitFor(() => {
      const noDataCells = screen.getAllByText('No data')
      expect(noDataCells.length).toBeGreaterThanOrEqual(1)
    })
  })
})
