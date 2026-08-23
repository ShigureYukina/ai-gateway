import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from '@/test/utils'
import type { UserMeResponse } from '@/types/api'
import ProfilePage from './ProfilePage'

const { mockMe, mockUpdateProfile, mockChangePassword } = vi.hoisted(() => ({
  mockMe: vi.fn(),
  mockUpdateProfile: vi.fn(),
  mockChangePassword: vi.fn(),
}))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    auth: {
      ...other.auth,
      me: (...args: unknown[]) => mockMe(...args),
      updateProfile: (...args: unknown[]) => mockUpdateProfile(...args),
      changePassword: (...args: unknown[]) => mockChangePassword(...args),
    },
  }
})

function createProfile(overrides: Partial<UserMeResponse> = {}): UserMeResponse {
  return {
    username: 'alice',
    role: 'admin',
    displayName: 'Alice Zhang',
    email: 'alice@example.com',
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
  mockUpdateProfile.mockReset()
  mockChangePassword.mockReset()
})

describe('ProfilePage', () => {
  it('renders loading state then profile data after load', async () => {
    const deferred = createDeferred<UserMeResponse>()
    mockMe.mockReturnValue(deferred.promise)

    const { container } = renderWithProviders(<ProfilePage />)

    expect(container.querySelector('svg.animate-spin')).toBeInTheDocument()

    deferred.resolve(createProfile())

    await waitFor(() => {
      expect(screen.getByText('alice')).toBeInTheDocument()
      expect(screen.getByText('admin')).toBeInTheDocument()
      expect(screen.getByDisplayValue('Alice Zhang')).toBeInTheDocument()
      expect(screen.getByDisplayValue('alice@example.com')).toBeInTheDocument()
    })
  })

  it('shows error state when me() fails', async () => {
    mockMe.mockRejectedValue(new Error('Network error'))

    renderWithProviders(<ProfilePage />)

    await waitFor(() => {
      expect(screen.getByText('Failed to load profile: Network error')).toBeInTheDocument()
    })
  })

  it('saves profile update successfully', async () => {
    const user = userEvent.setup()
    mockMe.mockResolvedValue(createProfile())
    mockUpdateProfile.mockResolvedValue(createProfile({ displayName: 'Alice Updated' }))

    renderWithProviders(<ProfilePage />)

    const displayNameInput = await screen.findByDisplayValue('Alice Zhang')
    await user.clear(displayNameInput)
    await user.type(displayNameInput, 'Alice Updated')
    await user.click(screen.getByRole('button', { name: 'Save Profile' }))

    await waitFor(() => {
      expect(mockUpdateProfile).toHaveBeenCalledWith({
        displayName: 'Alice Updated',
        email: 'alice@example.com',
      })
      expect(screen.getByText('Profile updated')).toBeInTheDocument()
    })
  })

  it('changes password successfully', async () => {
    const user = userEvent.setup()
    mockMe.mockResolvedValue(createProfile())
    mockChangePassword.mockResolvedValue(undefined)

    const { container } = renderWithProviders(<ProfilePage />)

    await screen.findByText('Basic Information')

    const passwordInputs = container.querySelectorAll('input[type="password"]')
    await user.type(passwordInputs[0]!, 'old-password')
    await user.type(passwordInputs[1]!, 'new-password')
    await user.type(passwordInputs[2]!, 'new-password')
    await user.click(screen.getByRole('button', { name: 'Update Password' }))

    await waitFor(() => {
      expect(mockChangePassword).toHaveBeenCalledWith({
        oldPassword: 'old-password',
        newPassword: 'new-password',
      })
      expect(screen.getByText('Password changed')).toBeInTheDocument()
      expect(passwordInputs[0]).toHaveValue('')
      expect(passwordInputs[1]).toHaveValue('')
      expect(passwordInputs[2]).toHaveValue('')
    })
  })

  it('shows error when passwords do not match', async () => {
    const user = userEvent.setup()
    mockMe.mockResolvedValue(createProfile())

    const { container } = renderWithProviders(<ProfilePage />)

    await screen.findByText('Change Password')

    const passwordInputs = container.querySelectorAll('input[type="password"]')
    await user.type(passwordInputs[0]!, 'old-password')
    await user.type(passwordInputs[1]!, 'new-password')
    await user.type(passwordInputs[2]!, 'different-password')
    await user.click(screen.getByRole('button', { name: 'Update Password' }))

    await waitFor(() => {
      expect(screen.getByText('Passwords do not match')).toBeInTheDocument()
    })
    expect(mockChangePassword).not.toHaveBeenCalled()
  })

  it('shows error when password too short', async () => {
    const user = userEvent.setup()
    mockMe.mockResolvedValue(createProfile())

    const { container } = renderWithProviders(<ProfilePage />)

    await screen.findByText('Change Password')

    const passwordInputs = container.querySelectorAll('input[type="password"]')
    await user.type(passwordInputs[0]!, 'old-password')
    await user.type(passwordInputs[1]!, '12345')
    await user.type(passwordInputs[2]!, '12345')
    await user.click(screen.getByRole('button', { name: 'Update Password' }))

    await waitFor(() => {
      expect(screen.getByText('Password must be at least 6 characters')).toBeInTheDocument()
    })
    expect(mockChangePassword).not.toHaveBeenCalled()
  })

  it('handles profile update failure', async () => {
    const user = userEvent.setup()
    mockMe.mockResolvedValue(createProfile())
    mockUpdateProfile.mockRejectedValue(new Error('Update failed'))

    renderWithProviders(<ProfilePage />)

    await screen.findByDisplayValue('Alice Zhang')
    await user.click(screen.getByRole('button', { name: 'Save Profile' }))

    await waitFor(() => {
      expect(screen.getByText('Error')).toBeInTheDocument()
      expect(screen.getByText('Update failed')).toBeInTheDocument()
    })
  })
})
