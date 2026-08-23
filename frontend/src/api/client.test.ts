/**
 * Tests for the API client network layer.
 * Mocks global fetch to test request building, auth, error handling.
 */
import {
  auth,
  dashboard,
  setTokenProvider,
  setRefreshTokenProvider,
  setTokenUpdater,
  setOnAuthFailure,
} from './client'

const mockFetch = vi.fn()

beforeEach(() => {
  mockFetch.mockReset()
  global.fetch = mockFetch
  // Reset auth state providers
  setTokenProvider(() => null)
  setRefreshTokenProvider(() => null)
  setTokenUpdater(() => {})
  setOnAuthFailure(() => {})
})

function okResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
    headers: new Headers(),
    redirected: false,
    statusText: status === 200 ? 'OK' : 'Error',
    type: 'basic' as ResponseType,
    url: '',
    clone: () => ({} as Response),
    body: null,
    bodyUsed: false,
    arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)),
    blob: () => Promise.resolve(new Blob()),
    formData: () => Promise.resolve(new FormData()),
  } as Response
}

describe('auth.login', () => {
  it('sends POST with JSON body and content-type', async () => {
    mockFetch.mockResolvedValue(
      okResponse({ accessToken: 'at', refreshToken: 'rt', tokenType: 'Bearer' }),
    )

    const result = await auth.login({ username: 'admin', password: 'admin123' })

    expect(result.accessToken).toBe('at')
    expect(mockFetch).toHaveBeenCalledWith('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: 'admin', password: 'admin123' }),
      headers: { 'Content-Type': 'application/json' },
    })
  })

  it('throws ApiError on non-ok response', async () => {
    mockFetch.mockResolvedValue(okResponse('Bad Request', 400))

    await expect(auth.login({ username: '', password: '' })).rejects.toThrow('Bad Request')
  })

  it('throws ApiError with status on 403', async () => {
    mockFetch.mockResolvedValue(okResponse('Forbidden', 403))

    await expect(auth.login({ username: '', password: '' })).rejects.toMatchObject({
      message: 'Forbidden',
      status: 403,
    })
  })
})

describe('dashboard.overview', () => {
  it('sends GET request without body', async () => {
    mockFetch.mockResolvedValue(
      okResponse({ day: '2026-06-04', overview: {}, systemStatus: {}, tpmOverview: { clients: [] } }),
    )

    await dashboard.overview()

    expect(mockFetch).toHaveBeenCalledWith('/admin/dashboard/overview', {
      headers: {},
    })
  })
})

describe('Authorization header', () => {
  it('includes Bearer token when token is set', async () => {
    setTokenProvider(() => 'my-token')
    mockFetch.mockResolvedValue(okResponse({}))

    await dashboard.overview()

    expect(mockFetch).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer my-token' }),
      }),
    )
  })

  it('does not include Authorization header when no token', async () => {
    setTokenProvider(() => null)
    mockFetch.mockResolvedValue(okResponse({}))

    await dashboard.overview()

    const call = mockFetch.mock.calls[0][1] as RequestInit
    const headers = call.headers as Record<string, string>
    expect(headers.Authorization).toBeUndefined()
  })
})

describe('401 handling', () => {
  it('throws 401 when no refresh token available', async () => {
    setTokenProvider(() => 'expired-token')
    setRefreshTokenProvider(() => null)
    const authFailure = vi.fn()
    setOnAuthFailure(authFailure)

    mockFetch.mockResolvedValue(okResponse('Unauthorized', 401))

    await expect(dashboard.overview()).rejects.toMatchObject({
      message: 'Unauthorized',
      status: 401,
    })
    expect(authFailure).toHaveBeenCalled()
  })

  it('attempts token refresh on 401, retries on success', async () => {
    let callCount = 0
    setTokenProvider(() => 'expired-token')
    setRefreshTokenProvider(() => 'valid-rt')
    const tokenUpdater = vi.fn()
    setTokenUpdater(tokenUpdater)

    mockFetch.mockImplementation(async (url: string) => {
      callCount++
      if (url === '/auth/refresh') {
        return okResponse({ accessToken: 'new-token' })
      }
      if (callCount === 1) {
        return okResponse('Unauthorized', 401)
      }
      return okResponse({ day: '2026-06-04', overview: {}, systemStatus: {}, tpmOverview: { clients: [] } })
    })

    const result = await dashboard.overview()

    expect(tokenUpdater).toHaveBeenCalledWith('new-token')
    expect(result).toBeDefined()
    expect(callCount).toBe(3) // original request + refresh + retry
  })

  it('calls onAuthFailure when refresh also fails', async () => {
    setTokenProvider(() => 'expired-token')
    setRefreshTokenProvider(() => 'invalid-rt')
    const authFailure = vi.fn()
    setOnAuthFailure(authFailure)

    let callCount = 0
    mockFetch.mockImplementation(async (url: string) => {
      callCount++
      if (url === '/auth/refresh') {
        return okResponse('Bad Request', 400)
      }
      return okResponse('Unauthorized', 401)
    })

    await expect(dashboard.overview()).rejects.toMatchObject({
      message: 'Unauthorized',
      status: 401,
    })
    expect(authFailure).toHaveBeenCalled()
  })
})

describe('204 No Content', () => {
  it('returns undefined for 204', async () => {
    mockFetch.mockResolvedValue(okResponse(undefined, 204))

    const result = await dashboard.overview()
    expect(result).toBeUndefined()
  })
})
