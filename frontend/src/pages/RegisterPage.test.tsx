import { renderWithProviders, screen, waitFor } from '@/test/utils'
import userEvent from '@testing-library/user-event'
import { useAuthStore } from '@/store/auth'
import RegisterPage from './RegisterPage'

function makeToken(payload: Record<string, unknown>) {
  const b64 = btoa(JSON.stringify(payload))
  return `header.${b64}.signature`
}

const mockNavigate = vi.fn()
const mockRegister = vi.fn()
const mockSetAuth = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => mockNavigate }
})

vi.mock('@/api/client', () => ({
  auth: { register: (...args: unknown[]) => mockRegister(...args) },
  setTokenProvider: vi.fn(),
  setRefreshTokenProvider: vi.fn(),
  setTokenUpdater: vi.fn(),
  setOnAuthFailure: vi.fn(),
}))

beforeEach(() => {
  mockNavigate.mockReset()
  mockRegister.mockReset()
  mockSetAuth.mockReset()
  useAuthStore.setState({
    token: null,
    refreshToken: null,
    username: null,
    role: null,
    isAuthenticated: false,
    setAuth: mockSetAuth,
    logout: vi.fn(),
  })
})

describe('RegisterPage', () => {
  it('renders registration form with all fields', () => {
    renderWithProviders(<RegisterPage />)

    expect(screen.getByLabelText('Username')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
    expect(screen.getByLabelText('Confirm Password')).toBeInTheDocument()
    expect(screen.getByLabelText('Display Name (optional)')).toBeInTheDocument()
    expect(screen.getByLabelText('Email (optional)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Already have an account? Sign in' })).toBeInTheDocument()
  })

  it('registers and redirects user to portal', async () => {
    const token = makeToken({ role: 'user' })
    mockRegister.mockResolvedValue({
      accessToken: token,
      refreshToken: 'refresh-token',
      tokenType: 'Bearer',
      apiKey: 'test-key',
    })

    const user = userEvent.setup()
    renderWithProviders(<RegisterPage />)

    await user.type(screen.getByLabelText('Username'), 'newuser')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.type(screen.getByLabelText('Confirm Password'), 'secret123')
    await user.type(screen.getByLabelText('Display Name (optional)'), 'New User')
    await user.type(screen.getByLabelText('Email (optional)'), 'newuser@example.com')
    await user.click(screen.getByRole('button', { name: /sign up/i }))

    await waitFor(() => {
      expect(mockRegister).toHaveBeenCalledWith({
        username: 'newuser',
        password: 'secret123',
        displayName: 'New User',
        email: 'newuser@example.com',
      })
    })

    await waitFor(() => {
      expect(mockSetAuth).toHaveBeenCalledWith(token, 'refresh-token', 'newuser', 'user')
      expect(mockNavigate).toHaveBeenCalledWith('/portal/dashboard')
    })
  })

  it('shows error when passwords do not match', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RegisterPage />)

    await user.type(screen.getByLabelText('Username'), 'newuser')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.type(screen.getByLabelText('Confirm Password'), 'different123')
    await user.click(screen.getByRole('button', { name: /sign up/i }))

    expect(screen.getByText('Passwords do not match')).toBeInTheDocument()
    expect(mockRegister).not.toHaveBeenCalled()
  })

  it('shows error when password too short', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RegisterPage />)

    await user.type(screen.getByLabelText('Username'), 'newuser')
    await user.type(screen.getByLabelText('Password'), '12345')
    await user.type(screen.getByLabelText('Confirm Password'), '12345')
    await user.click(screen.getByRole('button', { name: /sign up/i }))

    expect(screen.getByText('Password must be at least 6 characters')).toBeInTheDocument()
    expect(mockRegister).not.toHaveBeenCalled()
  })

  it('shows error on registration failure', async () => {
    mockRegister.mockRejectedValue(new Error('Registration failed from API'))

    const user = userEvent.setup()
    renderWithProviders(<RegisterPage />)

    await user.type(screen.getByLabelText('Username'), 'newuser')
    await user.type(screen.getByLabelText('Password'), 'secret123')
    await user.type(screen.getByLabelText('Confirm Password'), 'secret123')
    await user.click(screen.getByRole('button', { name: /sign up/i }))

    await waitFor(() => {
      expect(screen.getByText('Registration failed from API')).toBeInTheDocument()
    })
  })
})
