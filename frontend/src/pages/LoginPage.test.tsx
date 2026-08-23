import { renderWithProviders, screen, waitFor } from '@/test/utils'
import userEvent from '@testing-library/user-event'
import { useAuthStore } from '@/store/auth'
import LoginPage from './LoginPage'

// Build a real JWT-like token with base64-encoded JSON payload
function makeToken(payload: Record<string, unknown>) {
  const b64 = btoa(JSON.stringify(payload))
  return `header.${b64}.signature`
}

const mockLogin = vi.fn()

vi.mock('@/api/client', () => ({
  auth: { login: (...args: unknown[]) => mockLogin(...args) },
  setTokenProvider: vi.fn(),
  setRefreshTokenProvider: vi.fn(),
  setTokenUpdater: vi.fn(),
  setOnAuthFailure: vi.fn(),
}))

beforeEach(() => {
  mockLogin.mockReset()
  useAuthStore.setState({
    token: null,
    refreshToken: null,
    username: null,
    role: null,
    isAuthenticated: false,
  })
})

describe('LoginPage', () => {
  it('renders login form with default values', () => {
    renderWithProviders(<LoginPage />)
    expect(screen.getByText('AI Gateway')).toBeInTheDocument()
    expect(screen.getByText('Sign in to admin console')).toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toHaveValue('admin')
    expect(screen.getByLabelText('Password')).toHaveValue('admin123')
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument()
  })

  it('allows editing username and password fields', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LoginPage />)

    const usernameInput = screen.getByLabelText('Username')
    const passwordInput = screen.getByLabelText('Password')

    await user.clear(usernameInput)
    await user.type(usernameInput, 'testuser')
    await user.clear(passwordInput)
    await user.type(passwordInput, 'testpass')

    expect(usernameInput).toHaveValue('testuser')
    expect(passwordInput).toHaveValue('testpass')
  })

  it('calls auth.login on form submit and navigates on success', async () => {
    const token = makeToken({ role: 'admin' })
    mockLogin.mockResolvedValue({
      accessToken: token,
      refreshToken: 'refresh-token',
      tokenType: 'Bearer',
    })

    const user = userEvent.setup()
    renderWithProviders(<LoginPage />)

    await user.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() => {
      expect(mockLogin).toHaveBeenCalledWith({
        username: 'admin',
        password: 'admin123',
      })
    })

    await waitFor(() => {
      expect(useAuthStore.getState().isAuthenticated).toBe(true)
      expect(useAuthStore.getState().username).toBe('admin')
      expect(useAuthStore.getState().role).toBe('admin')
    })
  })

  it('handles non-admin role correctly', async () => {
    const token = makeToken({ role: 'user' })
    mockLogin.mockResolvedValue({
      accessToken: token,
      refreshToken: 'rt',
      tokenType: 'Bearer',
    })

    const user = userEvent.setup()
    renderWithProviders(<LoginPage />)

    await user.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() => {
      expect(useAuthStore.getState().role).toBe('user')
    })
  })

  it('shows error message on login failure', async () => {
    mockLogin.mockRejectedValue(new Error('Invalid credentials'))

    const user = userEvent.setup()
    renderWithProviders(<LoginPage />)

    await user.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() => {
      expect(screen.getByText('Invalid credentials')).toBeInTheDocument()
    })
  })

  it('shows default error message when err has no message', async () => {
    mockLogin.mockRejectedValue(new Error())

    const user = userEvent.setup()
    renderWithProviders(<LoginPage />)

    await user.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() => {
      expect(screen.getByText('Login failed')).toBeInTheDocument()
    })
  })

  it('disables button while loading', async () => {
    mockLogin.mockImplementation(() => new Promise(() => {}))

    const user = userEvent.setup()
    renderWithProviders(<LoginPage />)

    await user.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /sign in/i })).toBeDisabled()
    })
  })
})
