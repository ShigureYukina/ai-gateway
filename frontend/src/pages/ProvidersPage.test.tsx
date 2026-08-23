import { renderWithProviders, screen, waitFor } from '@/test/utils'
import userEvent from '@testing-library/user-event'
import ProvidersPage from './ProvidersPage'

const mockProviders = vi.fn()
const mockUpsert = vi.fn()
const mockRemove = vi.fn()
const mockTest = vi.fn()

vi.mock('@/api/client', () => ({
  providers: {
    list: (...args: unknown[]) => mockProviders(...args),
    upsert: (...args: unknown[]) => mockUpsert(...args),
    remove: (...args: unknown[]) => mockRemove(...args),
    test: (...args: unknown[]) => mockTest(...args),
    get: vi.fn(),
    listModels: vi.fn().mockResolvedValue({ provider: 'test', models: [] }),
    fetchModels: vi.fn().mockResolvedValue({ provider: 'test', models: [] }),
    updateModels: vi.fn(),
  },
  setTokenProvider: vi.fn(),
  setRefreshTokenProvider: vi.fn(),
  setTokenUpdater: vi.fn(),
  setOnAuthFailure: vi.fn(),
}))

const sampleProviders = {
  providers: {
    'openai-main': {
      type: 'openai',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: 'sk-****',
      enabled: true,
      timeout: '30',
      models: ['gpt-4', 'gpt-3.5-turbo'],
    },
    'anthropic-main': {
      type: 'anthropic',
      baseUrl: 'https://api.anthropic.com/v1',
      apiKey: 'sk-ant-****',
      enabled: false,
      timeout: '60',
      models: [],
    },
  },
}

beforeEach(() => {
  mockProviders.mockReset()
  mockUpsert.mockReset()
  mockRemove.mockReset()
  mockTest.mockReset()
  mockProviders.mockResolvedValue(sampleProviders)
})

describe('ProvidersPage', () => {
  it('renders provider table after loading', async () => {
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('openai-main')).toBeInTheDocument()
      expect(screen.getByText('anthropic-main')).toBeInTheDocument()
    })
  })

  it('shows empty state when no providers', async () => {
    mockProviders.mockResolvedValue({ providers: {} })
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText(/No channels configured/i)).toBeInTheDocument()
    })
  })

  it('shows provider count text', async () => {
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('2 channel(s) configured')).toBeInTheDocument()
    })
  })

  it('opens create dialog with empty form', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('openai-main')).toBeInTheDocument()
    })

    await user.click(screen.getByText('Add Channel'))

    await waitFor(() => {
      expect(screen.getByText('Configure a new channel for upstream LLM access')).toBeInTheDocument()
    })

    expect(screen.getByText('OpenAI Compatible')).toBeInTheDocument()
  })

  it('opens edit dialog with pre-filled data', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('openai-main')).toBeInTheDocument()
    })

    const editButtons = screen.getAllByTitle('Edit')
    await user.click(editButtons[0])

    await waitFor(() => {
      expect(screen.getByText(/Update configuration for/)).toBeInTheDocument()
    })

    expect(screen.getByDisplayValue('openai-main')).toBeInTheDocument()
    expect(screen.getByDisplayValue('https://api.openai.com/v1')).toBeInTheDocument()
  })

  it('creates a new provider via API', async () => {
    mockUpsert.mockResolvedValue({})
    const user = userEvent.setup()
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('openai-main')).toBeInTheDocument()
    })

    await user.click(screen.getByText('Add Channel'))

    await waitFor(() => {
      expect(screen.getByText('Configure a new channel for upstream LLM access')).toBeInTheDocument()
    })

    const nameInput = screen.getByRole('textbox', { name: /name/i })
    await user.clear(nameInput)
    await user.type(nameInput, 'new-provider')

    const urlInput = screen.getAllByRole('textbox', { name: /base url/i })[0]
    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/api')

    await user.click(screen.getByText('Save'))

    await waitFor(() => {
      expect(mockUpsert).toHaveBeenCalledWith('new-provider', {
        type: 'openai-compatible',
        baseUrl: 'https://example.com/api',
        apiKey: undefined,
        timeoutSeconds: 30,
        enabled: true,
        models: undefined,
      })
    })
  })

  it('deletes a provider after confirmation', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockRemove.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('openai-main')).toBeInTheDocument()
    })

    const deleteButtons = screen.getAllByTitle('Delete')
    await user.click(deleteButtons[0])

    await waitFor(() => {
      expect(confirmSpy).toHaveBeenCalled()
      expect(mockRemove).toHaveBeenCalledWith('openai-main')
    })

    confirmSpy.mockRestore()
  })

  it('does not delete when confirm is cancelled', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('openai-main')).toBeInTheDocument()
    })

    const deleteButtons = screen.getAllByTitle('Delete')
    await user.click(deleteButtons[0])

    await waitFor(() => {
      expect(confirmSpy).toHaveBeenCalled()
      expect(mockRemove).not.toHaveBeenCalled()
    })

    confirmSpy.mockRestore()
  })

  it('displays test result', async () => {
    mockTest.mockResolvedValue({ status: 'ok', latencyMs: 150, httpStatus: 200, error: null })
    const user = userEvent.setup()
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('openai-main')).toBeInTheDocument()
    })

    const testButtons = screen.getAllByTitle('Test connection')
    await user.click(testButtons[0])

    await waitFor(() => {
      expect(screen.getByText(/OK/i)).toBeInTheDocument()
      expect(screen.getByText(/150ms/i)).toBeInTheDocument()
    })
  })

  it('shows error when provider test fails', async () => {
    mockTest.mockResolvedValue({ status: 'error', latencyMs: 0, httpStatus: null, error: 'Connection refused' })
    const user = userEvent.setup()
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('openai-main')).toBeInTheDocument()
    })

    const testButtons = screen.getAllByTitle('Test connection')
    await user.click(testButtons[0])

    await waitFor(() => {
      expect(screen.getByText(/Failed: Connection refused/i)).toBeInTheDocument()
    })
  })

  it('shows error toast on API failure', async () => {
    mockProviders.mockRejectedValue(new Error('Failed to fetch'))
    renderWithProviders(<ProvidersPage />)

    await waitFor(() => {
      expect(screen.getByText('Error')).toBeInTheDocument()
      expect(screen.getByText('Failed to fetch')).toBeInTheDocument()
    })
  })
})
