import { renderWithProviders, screen, waitFor } from '@/test/utils'
import type { UserMeResponse } from '@/types/api'
import UserDashboardPage from './DashboardPage'

const { mockMe } = vi.hoisted(() => ({ mockMe: vi.fn() }))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    auth: { ...other.auth, me: (...args: unknown[]) => mockMe(...args) },
  }
})

function createProfile(overrides: Partial<UserMeResponse> = {}): UserMeResponse {
  return {
    username: 'testuser',
    role: 'user',
    displayName: 'Test User',
    email: 'test@example.com',
    apiKeyMasked: 'sk-***',
    createdAt: 1717545600000,
    quota: {
      dailyTokensUsed: 0,
      dailyTokensLimit: null,
      dailyCostUsed: 0,
      dailyCostLimit: null,
      monthlyTokensUsed: 0,
      monthlyTokensLimit: null,
      monthlyCostUsed: 0,
      monthlyCostLimit: null,
      monthlyUnsupported: false,
    },
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
  mockMe.mockReset()
})

describe('UserDashboardPage', () => {
  it('renders loading then shows user info', async () => {
    const deferred = createDeferred<UserMeResponse>()
    mockMe.mockReturnValue(deferred.promise)

    const { container } = renderWithProviders(<UserDashboardPage />)

    expect(container.querySelector('svg.animate-spin')).toBeInTheDocument()

    deferred.resolve(createProfile())

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
      expect(screen.getByText('testuser')).toBeInTheDocument()
      expect(screen.getByText('Test User')).toBeInTheDocument()
      expect(screen.getByText('user')).toBeInTheDocument()
    })
  })

  it('shows username and role when displayName is null', async () => {
    mockMe.mockResolvedValue(createProfile({ displayName: null }))

    renderWithProviders(<UserDashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('testuser')).toBeInTheDocument()
      expect(screen.getByText('user')).toBeInTheDocument()
    })

    expect(screen.queryByText('Test User')).not.toBeInTheDocument()
    expect(screen.queryByText('Display Name')).not.toBeInTheDocument()
  })

  it('shows error state when API fails', async () => {
    mockMe.mockRejectedValue(new Error('Network error'))

    const { container } = renderWithProviders(<UserDashboardPage />)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    })

    expect(container.querySelector('svg.animate-spin')).not.toBeInTheDocument()
    expect(screen.queryByText('Display Name')).not.toBeInTheDocument()
    // 错误信息应被展示（而非空 catch）
    expect(screen.getByText('Network error')).toBeInTheDocument()
  })

  it('renders quota section with usage and limits', async () => {
    mockMe.mockResolvedValue(createProfile({
      quota: {
        dailyTokensUsed: 5000,
        dailyTokensLimit: 10000,
        dailyCostUsed: 2000000,
        dailyCostLimit: 5000000,
        monthlyTokensUsed: 50000,
        monthlyTokensLimit: 300000,
        monthlyCostUsed: 20000000,
        monthlyCostLimit: 100000000,
        monthlyUnsupported: false,
      },
    }))

    renderWithProviders(<UserDashboardPage />)

    await waitFor(() => {
      // 配额标题
      expect(screen.getByText('Quota Usage')).toBeInTheDocument()
      // 4 个配额卡片标签
      expect(screen.getByText('Daily Tokens')).toBeInTheDocument()
      expect(screen.getByText('Monthly Tokens')).toBeInTheDocument()
      expect(screen.getByText('Daily Cost')).toBeInTheDocument()
      expect(screen.getByText('Monthly Cost')).toBeInTheDocument()
    })

    // 检查 daily tokens 使用量显示
    expect(screen.getByText('5,000')).toBeInTheDocument()
    // 检查 daily cost 使用量显示（micros → USD）
    expect(screen.getByText('$2.0000')).toBeInTheDocument()
  })

  it('shows Unlimited when limit is null', async () => {
    mockMe.mockResolvedValue(createProfile({
      quota: {
        dailyTokensUsed: 100,
        dailyTokensLimit: null,
        dailyCostUsed: 0,
        dailyCostLimit: null,
        monthlyTokensUsed: 0,
        monthlyTokensLimit: null,
        monthlyCostUsed: 0,
        monthlyCostLimit: null,
        monthlyUnsupported: false,
      },
    }))

    renderWithProviders(<UserDashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Quota Usage')).toBeInTheDocument()
    })

    // null limit 应显示 "Unlimited"
    const unlimitedTexts = screen.getAllByText('Unlimited')
    expect(unlimitedTexts.length).toBeGreaterThanOrEqual(2)
  })

  it('shows Not Supported for monthly when monthlyUnsupported is true', async () => {
    mockMe.mockResolvedValue(createProfile({
      quota: {
        dailyTokensUsed: 100,
        dailyTokensLimit: 1000,
        dailyCostUsed: 0,
        dailyCostLimit: 1000000,
        monthlyTokensUsed: 0,
        monthlyTokensLimit: 0,
        monthlyCostUsed: 0,
        monthlyCostLimit: 0,
        monthlyUnsupported: true,
      },
    }))

    renderWithProviders(<UserDashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Quota Usage')).toBeInTheDocument()
    })

    // monthlyUnsupported 时应显示 "Not Supported"
    const notSupportedTexts = screen.getAllByText('Not Supported')
    expect(notSupportedTexts.length).toBe(2) // monthly tokens + monthly cost
  })

  it('renders progress bar with correct percentage', async () => {
    mockMe.mockResolvedValue(createProfile({
      quota: {
        dailyTokensUsed: 7500,
        dailyTokensLimit: 10000,
        dailyCostUsed: 0,
        dailyCostLimit: null,
        monthlyTokensUsed: 0,
        monthlyTokensLimit: null,
        monthlyCostUsed: 0,
        monthlyCostLimit: null,
        monthlyUnsupported: false,
      },
    }))

    renderWithProviders(<UserDashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('Quota Usage')).toBeInTheDocument()
    })

    // 7500/10000 = 75%
    expect(screen.getByText('75%')).toBeInTheDocument()
  })

  it('does not render quota section when profile has no quota', async () => {
    // quota 为 undefined 的情况（极端边界）
    mockMe.mockResolvedValue({
      username: 'testuser',
      role: 'user',
      displayName: null,
      email: null,
      apiKeyMasked: null,
      createdAt: 0,
    } as unknown as UserMeResponse)

    renderWithProviders(<UserDashboardPage />)

    await waitFor(() => {
      expect(screen.getByText('testuser')).toBeInTheDocument()
    })

    expect(screen.queryByText('Quota Usage')).not.toBeInTheDocument()
  })
})
