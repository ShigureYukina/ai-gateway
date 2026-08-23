import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderWithProviders, screen, waitFor } from '@/test/utils'
import type {
  AlertsResponse,
  AuditCenterResponse,
  DashboardOverview,
  RecentRequestsResponse,
} from '@/types/api'
import OperationsPage from './OperationsPage'

const { mockAlertsList, mockLogsRecent, mockAuditCenter, mockDashboardOverview } = vi.hoisted(() => ({
  mockAlertsList: vi.fn(),
  mockLogsRecent: vi.fn(),
  mockAuditCenter: vi.fn(),
  mockDashboardOverview: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    alerts: { ...other.alerts, list: (...args: unknown[]) => mockAlertsList(...args) },
    requestLogs: { ...other.requestLogs, recent: (...args: unknown[]) => mockLogsRecent(...args) },
    configAudit: { ...other.configAudit, center: (...args: unknown[]) => mockAuditCenter(...args) },
    dashboard: { ...other.dashboard, overview: (...args: unknown[]) => mockDashboardOverview(...args) },
  }
})

function createAlertsResponse(overrides: Partial<AlertsResponse> = {}): AlertsResponse {
  return {
    generatedAt: '2026-06-05T10:00:00Z',
    active: [
      {
        id: 'alert-1',
        type: 'provider_timeout',
        severity: 'critical',
        status: 'open',
        message: 'OpenAI upstream latency is elevated',
        source: 'openai-primary',
        detectedAt: '2026-06-05T09:59:00Z',
      },
    ],
    recent: [
      {
        id: 'alert-2',
        type: 'rate_limit',
        severity: 'warning',
        status: 'resolved',
        message: 'Client temporary rate limit reached',
        source: 'demo-client-key',
        detectedAt: '2026-06-05T09:30:00Z',
      },
    ],
    ...overrides,
  }
}

function createLogsResponse(overrides: Partial<RecentRequestsResponse> = {}): RecentRequestsResponse {
  return {
    generatedAt: '2026-06-05T10:00:00Z',
    total: 1,
    offset: 0,
    requests: [
      {
        requestId: 'req-1234567890abcdef',
        clientId: 'demo-client-key',
        model: 'gpt-4o-mini',
        provider: 'openai-primary',
        routeId: 'default-chat',
        scene: 'chat',
        status: 200,
        latencyMs: 245,
        timestamp: '2026-06-05T09:58:00Z',
        streamMode: 'non_stream',
        usageTokens: 128,
        promptTokens: 80,
        completionTokens: 48,
        costUsd: 0.000321,
        errorMessage: null,
      },
    ],
    ...overrides,
  }
}

function createAuditResponse(overrides: Partial<AuditCenterResponse> = {}): AuditCenterResponse {
  return {
    generatedAt: '2026-06-05T10:00:00Z',
    entries: [
      {
        eventType: 'config_change',
        timestamp: '2026-06-05T09:57:00Z',
        actor: 'admin',
        resourceType: 'system',
        resourceId: 'limit',
        action: 'save',
        result: 'success',
        reason: null,
        requestId: null,
        clientId: null,
        model: null,
        provider: null,
        routeId: null,
        scene: null,
        status: null,
        latencyMs: null,
      },
    ],
    ...overrides,
  }
}

function createDashboardOverview(overrides: Partial<DashboardOverview> = {}): DashboardOverview {
  return {
    generatedAt: '2026-06-05T10:00:00Z',
    day: '2026-06-05',
    systemStatus: {
      maintenanceActive: false,
      emergencyRateLimitEnabled: false,
      hasAvailableRoute: true,
    },
    overview: {
      totalRequests: 25,
      totalTokens: 5000,
      totalCost: 1.25,
      successRate: 99,
      activeClients: 2,
      registeredClients: 3,
      success2xx: 24,
      status4xx: 1,
      status5xx: 0,
      topModels: [{ model: 'gpt-4o-mini', requests: 25, tokens: 5000, cost: 1.25 }],
      topClients: [{ client: 'demo-client-key', requests: 25, tokens: 5000, cost: 1.25 }],
    },
    tpmOverview: {
      clients: [],
    },
    ...overrides,
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
    },
  })

  return renderWithProviders(
    <QueryClientProvider client={queryClient}>
      <OperationsPage />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockAlertsList.mockReset()
  mockLogsRecent.mockReset()
  mockAuditCenter.mockReset()
  mockDashboardOverview.mockReset()

  mockAlertsList.mockResolvedValue(createAlertsResponse())
  mockLogsRecent.mockResolvedValue(createLogsResponse())
  mockAuditCenter.mockResolvedValue(createAuditResponse())
  mockDashboardOverview.mockResolvedValue(createDashboardOverview())
})

describe('OperationsPage', () => {
  it('renders alerts tab as default', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Active Alerts')).toBeInTheDocument()
      expect(screen.getByText('OpenAI upstream latency is elevated')).toBeInTheDocument()
      expect(screen.getByText('Recent Alerts')).toBeInTheDocument()
      expect(screen.getByText('Client temporary rate limit reached')).toBeInTheDocument()
    })

    expect(mockAlertsList).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Alerts' })).toBeInTheDocument()
  })

  it('switches to request logs tab', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Request Logs' }))

    await waitFor(() => {
      expect(screen.getByText('Request ID')).toBeInTheDocument()
      expect(screen.getByText('gpt-4o-mini')).toBeInTheDocument()
      expect(screen.getByText('demo-client-key')).toBeInTheDocument()
      expect(screen.getByText('openai-primary')).toBeInTheDocument()
      expect(screen.getByText('245ms')).toBeInTheDocument()
    })

    expect(mockLogsRecent).toHaveBeenCalledWith({ status: undefined, limit: 50, offset: 0 })
  })

  it('shows empty state per section', async () => {
    const user = userEvent.setup()
    mockAlertsList.mockResolvedValue(createAlertsResponse({ active: [], recent: [] }))
    mockLogsRecent.mockResolvedValue(createLogsResponse({ total: 0, requests: [] }))
    mockAuditCenter.mockResolvedValue(createAuditResponse({ entries: [] }))
    mockDashboardOverview.mockResolvedValue(null)

    renderPage()

    await waitFor(() => {
      expect(screen.getByText('No active alerts')).toBeInTheDocument()
      expect(screen.getByText('No recent alerts')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Request Logs' }))
    await waitFor(() => {
      expect(screen.getByText('No request logs yet')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Audit' }))
    await waitFor(() => {
      expect(screen.getByText('No audit events')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Cost Overview' }))
    await waitFor(() => {
      expect(screen.getByText('No data')).toBeInTheDocument()
    })
  })

  it('switches to audit tab', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Audit' }))

    await waitFor(() => {
      expect(screen.getByText('Audit Events')).toBeInTheDocument()
      expect(screen.getByText('config_change')).toBeInTheDocument()
      expect(screen.getByText('admin')).toBeInTheDocument()
      expect(screen.getByText('system/limit')).toBeInTheDocument()
      expect(screen.getByText('save')).toBeInTheDocument()
    })

    expect(mockAuditCenter).toHaveBeenCalledWith({ limit: 50, offset: 0 })
  })
})
