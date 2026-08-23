import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from '@/test/utils'
import PublicationsPage from './PublicationsPage'

const { mockPublish } = vi.hoisted(() => ({
  mockPublish: vi.fn(),
}))

vi.mock('@/api/modules/admin/publications', () => ({
  publications: {
    publish: (...args: unknown[]) => mockPublish(...args),
  },
}))

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockPublish.mockReset()
})

describe('PublicationsPage', () => {
  it('renders the publication form', () => {
    renderWithProviders(<PublicationsPage />)

    expect(screen.getByText('Publications')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Alias' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Provider' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Upstream Model' })).toBeInTheDocument()
  })

  it('publishes alias and renders result', async () => {
    const user = userEvent.setup()
    mockPublish.mockResolvedValue({
      alias: 'gpt-4o-mini',
      provider: 'openai-main',
      upstreamModel: 'gpt-4o-mini',
      visibleInV1Models: true,
      price: {
        source: 'configured_exact',
        matchedBy: 'exact',
        matchedModel: 'gpt-4o-mini',
        unitPrice: 1,
        inputUnitPrice: 0.5,
        outputUnitPrice: 1.5,
        summary: 'source=configured_exact, matchedBy=exact, matchedModel=gpt-4o-mini',
      },
      warnings: [],
    })

    renderWithProviders(<PublicationsPage />)

    await user.type(screen.getByRole('textbox', { name: 'Alias' }), 'gpt-4o-mini')
    await user.type(screen.getByRole('textbox', { name: 'Provider' }), 'openai-main')
    await user.type(screen.getByRole('textbox', { name: 'Upstream Model' }), 'gpt-4o-mini')
    await user.click(screen.getByRole('button', { name: 'Publish Alias' }))

    await waitFor(() => {
      expect(mockPublish).toHaveBeenCalledWith('gpt-4o-mini', {
        provider: 'openai-main',
        upstreamModel: 'gpt-4o-mini',
      })
      expect(screen.getByText('Last publish result')).toBeInTheDocument()
      expect(screen.getByText('Visible in /v1/models')).toBeInTheDocument()
      expect(screen.getByText('source=configured_exact, matchedBy=exact, matchedModel=gpt-4o-mini')).toBeInTheDocument()
    })
  })

  it('renders warnings from publish response', async () => {
    const user = userEvent.setup()
    mockPublish.mockResolvedValue({
      alias: 'gpt-4o-mini',
      provider: 'openai-main',
      upstreamModel: 'gpt-4o-mini',
      visibleInV1Models: false,
      price: {
        source: 'configured_default',
        matchedBy: 'fallback',
        matchedModel: 'gpt-4o-mini',
        unitPrice: 1,
        inputUnitPrice: 0.5,
        outputUnitPrice: 1.5,
        summary: 'source=configured_default',
      },
      warnings: ['价格未精确解析，请人工确认。'],
    })

    renderWithProviders(<PublicationsPage />)

    await user.type(screen.getByRole('textbox', { name: 'Alias' }), 'gpt-4o-mini')
    await user.type(screen.getByRole('textbox', { name: 'Provider' }), 'openai-main')
    await user.type(screen.getByRole('textbox', { name: 'Upstream Model' }), 'gpt-4o-mini')
    await user.click(screen.getByRole('button', { name: 'Publish Alias' }))

    await waitFor(() => {
      expect(screen.getByText('Warnings')).toBeInTheDocument()
      expect(screen.getByText('价格未精确解析，请人工确认。')).toBeInTheDocument()
      expect(screen.getByText('Not yet visible in /v1/models')).toBeInTheDocument()
    })
  })
})
