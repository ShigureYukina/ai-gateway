import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from '@/test/utils'
import type { AuthKeyItem, AuthCreateKeyResponse } from '@/types/api'
import UserApiKeysPage from './ApiKeysPage'

const { mockListKeys, mockCreateKey, mockDeleteKey } = vi.hoisted(() => ({
  mockListKeys: vi.fn(), mockCreateKey: vi.fn(), mockDeleteKey: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return { ...other, auth: { ...other.auth, listKeys: (...args: unknown[]) => mockListKeys(...args), createKey: (...args: unknown[]) => mockCreateKey(...args), deleteKey: (...args: unknown[]) => mockDeleteKey(...args) } }
})

function createKeyItem(overrides: Partial<AuthKeyItem> = {}): AuthKeyItem {
  return { keyId: 'key-1', name: 'My Key', apiKeyMasked: '****abc', enabled: true, createdAt: 1717545600000, lastUsedAt: null, requestCount: 42, allowedModels: [], ...overrides }
}

function createKeyResponse(overrides: Partial<AuthCreateKeyResponse> = {}): AuthCreateKeyResponse {
  return { keyId: 'new-key-1', name: 'New Key', apiKey: 'gw-new-key-value', apiKeyMasked: '****ew-', enabled: true, createdAt: 1717545600000, allowedModels: [], ...overrides }
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
  mockListKeys.mockReset()
  mockCreateKey.mockReset()
  mockDeleteKey.mockReset()
})

describe('UserApiKeysPage', () => {
  it('renders loading then shows empty state', async () => {
    const deferred = createDeferred<{ keys: AuthKeyItem[] }>()
    mockListKeys.mockReturnValue(deferred.promise)

    const { container } = renderWithProviders(<UserApiKeysPage />)

    expect(container.querySelector('svg.animate-spin')).toBeInTheDocument()

    deferred.resolve({ keys: [] })

    await waitFor(() => {
      expect(screen.getByText('No API keys yet. Create one to get started.')).toBeInTheDocument()
    })
  })

  it('renders key list table', async () => {
    mockListKeys.mockResolvedValue({
      keys: [
        createKeyItem(),
        createKeyItem({ keyId: 'key-2', name: 'Disabled Key', enabled: false, requestCount: 7 }),
      ],
    })

    renderWithProviders(<UserApiKeysPage />)

    expect(await screen.findByText('My Key')).toBeInTheDocument()
    expect(screen.getByText('Disabled Key')).toBeInTheDocument()
    expect(screen.getByText('Enabled')).toBeInTheDocument()
    expect(screen.getByText('Disabled')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
  })

  it('create key dialog flow', async () => {
    const user = userEvent.setup()
    mockListKeys
      .mockResolvedValueOnce({ keys: [] })
      .mockResolvedValueOnce({ keys: [createKeyItem({ keyId: 'new-key-1', name: 'Team Key' })] })
    mockCreateKey.mockResolvedValue(createKeyResponse({ name: 'Team Key' }))

    renderWithProviders(<UserApiKeysPage />)

    await screen.findByText('No API keys yet. Create one to get started.')
    await user.click(screen.getByRole('button', { name: 'Create API Key' }))
    await user.type(screen.getByPlaceholderText('My Key'), 'Team Key')
    await user.click(screen.getAllByRole('button', { name: 'Create API Key' })[1]!)

    await waitFor(() => {
      expect(mockCreateKey).toHaveBeenCalledWith({ name: 'Team Key' })
      expect(screen.getByText('gw-new-key-value')).toBeInTheDocument()
    })

    expect(screen.getAllByText('API Key created successfully!')).toHaveLength(2)
  })

  it('delete key confirmation', async () => {
    const user = userEvent.setup()
    mockListKeys
      .mockResolvedValueOnce({ keys: [createKeyItem()] })
      .mockResolvedValueOnce({ keys: [] })
    mockDeleteKey.mockResolvedValue(undefined)

    renderWithProviders(<UserApiKeysPage />)

    await screen.findByText('My Key')
    await user.click(screen.getAllByRole('button', { name: 'Delete' })[0]!)

    expect(screen.getByText('Are you sure you want to delete "My Key"?')).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Delete' })[1]!)

    await waitFor(() => {
      expect(mockDeleteKey).toHaveBeenCalledWith('key-1')
    })
  })

  it('create key failure shows error', async () => {
    const user = userEvent.setup()
    mockListKeys.mockResolvedValue({ keys: [] })
    mockCreateKey.mockRejectedValue(new Error('Create failed'))

    renderWithProviders(<UserApiKeysPage />)

    await screen.findByText('No API keys yet. Create one to get started.')
    await user.click(screen.getByRole('button', { name: 'Create API Key' }))
    await user.type(screen.getByPlaceholderText('My Key'), 'Broken Key')
    await user.click(screen.getAllByRole('button', { name: 'Create API Key' })[1]!)

    await waitFor(() => {
      expect(screen.getByText('Error')).toBeInTheDocument()
      expect(screen.getByText('Create failed')).toBeInTheDocument()
    })
  })

  it('create key dialog shows key value with copy button', async () => {
    mockListKeys
      .mockResolvedValueOnce({ keys: [] })
      .mockResolvedValueOnce({ keys: [createKeyItem({ keyId: 'new-key-1', name: 'Team Key' })] })
    mockCreateKey.mockResolvedValue(createKeyResponse({ name: 'Team Key' }))

    renderWithProviders(<UserApiKeysPage />)

    await screen.findByText('No API keys yet. Create one to get started.')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Create API Key' }))
    await userEvent.setup().type(screen.getByPlaceholderText('My Key'), 'Team Key')
    await userEvent.setup().click(screen.getAllByRole('button', { name: 'Create API Key' })[1]!)

    await waitFor(() => {
      expect(screen.getByText('gw-new-key-value')).toBeInTheDocument()
    })

    // Copy button with Copy icon is present
    const copyButtons = screen.getAllByRole('button')
    const copyButton = copyButtons.find(b => b.querySelector('svg.lucide-copy'))
    expect(copyButton).toBeTruthy()
  })
})
