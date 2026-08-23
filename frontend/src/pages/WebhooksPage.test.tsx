import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from '@/test/utils'
import type { WebhookEndpoint } from '@/types/api'
import WebhooksPage from './WebhooksPage'

const { mockList, mockCreate, mockUpdate, mockRemove } = vi.hoisted(() => ({
  mockList: vi.fn(),
  mockCreate: vi.fn(),
  mockUpdate: vi.fn(),
  mockRemove: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    webhooks: {
      ...other.webhooks,
      list: (...args: unknown[]) => mockList(...args),
      create: (...args: unknown[]) => mockCreate(...args),
      update: (...args: unknown[]) => mockUpdate(...args),
      remove: (...args: unknown[]) => mockRemove(...args),
    },
  }
})

function createWebhook(overrides: Partial<WebhookEndpoint> = {}): WebhookEndpoint {
  return {
    id: 'wh_1',
    name: 'alerts-primary',
    url: 'https://example.com/webhooks/alerts',
    enabled: true,
    events: ['request.completed', 'provider.failed'],
    hmacSecret: 'secret-1',
    ...overrides,
  }
}

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockList.mockReset()
  mockCreate.mockReset()
  mockUpdate.mockReset()
  mockRemove.mockReset()
})

describe('WebhooksPage', () => {
  it('loads and renders webhook list', async () => {
    mockList.mockResolvedValue([
      createWebhook(),
      createWebhook({ id: 'wh_2', name: 'audit-fallback', enabled: false, events: ['config.changed'] }),
    ])

    renderWithProviders(<WebhooksPage />)

    await waitFor(() => {
      expect(screen.getByText('alerts-primary')).toBeInTheDocument()
      expect(screen.getByText('audit-fallback')).toBeInTheDocument()
      expect(screen.getByText('2 webhook endpoint(s) configured')).toBeInTheDocument()
    })
  })

  it('creates a webhook successfully', async () => {
    const user = userEvent.setup()
    mockList
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([createWebhook({ hmacSecret: undefined })])
    mockCreate.mockResolvedValue(createWebhook({ hmacSecret: undefined }))

    renderWithProviders(<WebhooksPage />)

    await screen.findByText('No webhook endpoints configured. Click "Add Webhook" to get started.')
    await user.click(screen.getByRole('button', { name: 'Add Webhook' }))

    await user.type(screen.getByLabelText('Name'), 'alerts-primary')
    await user.type(screen.getByLabelText('URL'), 'https://example.com/webhooks/alerts')
    await user.type(screen.getByLabelText('Events'), 'request.completed, provider.failed')
    await user.type(screen.getByLabelText('HMAC Secret'), 'secret-1')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockCreate).toHaveBeenCalledWith({
        name: 'alerts-primary',
        url: 'https://example.com/webhooks/alerts',
        enabled: true,
        events: ['request.completed', 'provider.failed'],
        hmacSecret: 'secret-1',
      })
      expect(mockList).toHaveBeenCalledTimes(2)
    })
  })

  it('updates a webhook successfully', async () => {
    const user = userEvent.setup()
    mockList
      .mockResolvedValueOnce([createWebhook()])
      .mockResolvedValueOnce([createWebhook({ url: 'https://example.com/webhooks/alerts-v2', enabled: false })])
    mockUpdate.mockResolvedValue(createWebhook({ url: 'https://example.com/webhooks/alerts-v2', enabled: false }))

    renderWithProviders(<WebhooksPage />)

    await screen.findByText('alerts-primary')
    await user.click(screen.getByTitle('Edit'))

    const urlInput = screen.getByLabelText('URL')
    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/webhooks/alerts-v2')
    await user.click(screen.getByRole('switch', { name: 'Enabled' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(mockUpdate).toHaveBeenCalledWith('wh_1', {
        name: 'alerts-primary',
        url: 'https://example.com/webhooks/alerts-v2',
        enabled: false,
        events: ['request.completed', 'provider.failed'],
        hmacSecret: 'secret-1',
      })
      expect(mockList).toHaveBeenCalledTimes(2)
    })
  })

  it('deletes a webhook after confirmation', async () => {
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockList
      .mockResolvedValueOnce([createWebhook()])
      .mockResolvedValueOnce([])
    mockRemove.mockResolvedValue(undefined)

    renderWithProviders(<WebhooksPage />)

    await screen.findByText('alerts-primary')
    await user.click(screen.getByTitle('Delete'))

    await waitFor(() => {
      expect(confirmSpy).toHaveBeenCalled()
      expect(mockRemove).toHaveBeenCalledWith('wh_1')
      expect(mockList).toHaveBeenCalledTimes(2)
    })

    confirmSpy.mockRestore()
  })

  it('shows empty state when no webhooks exist', async () => {
    mockList.mockResolvedValue([])

    renderWithProviders(<WebhooksPage />)

    await waitFor(() => {
      expect(screen.getByText('No webhook endpoints configured. Click "Add Webhook" to get started.')).toBeInTheDocument()
    })
  })
})
