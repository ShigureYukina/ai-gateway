import { renderWithProviders, screen, waitFor } from '@/test/utils'
import type { UserView } from '@/types/api'
import UsersPage from './UsersPage'

const { mockList } = vi.hoisted(() => ({ mockList: vi.fn() }))

vi.mock('@/api/client', async () => {
  const { createApiClientMock } = await import('@/test/utils')
  const other = createApiClientMock()
  return {
    ...other,
    users: {
      ...other.users,
      list: (...args: unknown[]) => mockList(...args),
    },
  }
})

vi.mock('@/components/users/CreateUserDialog', () => ({
  CreateUserDialog: ({ onCreated }: { onCreated: () => void }) => (
    <button type="button" onClick={onCreated}>Mock Create User</button>
  ),
}))

vi.mock('@/components/users/UserRow', () => ({
  UserRow: ({ user }: { user: UserView }) => (
    <tr data-testid={`user-row-${user.username}`}>
      <td>{user.username}</td>
      <td>{user.role}</td>
    </tr>
  ),
}))

function createDeferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function createUser(overrides: Partial<UserView> = {}): UserView {
  return {
    username: 'alice',
    role: 'admin',
    displayName: 'Alice Zhang',
    email: 'alice@example.com',
    apiKeyMasked: 'sk-***',
    enabled: true,
    createdAt: 1717545600000,
    ...overrides,
  }
}

beforeEach(() => {
  localStorage.setItem('gateway-language', 'en')
  mockList.mockReset()
})

describe('UsersPage', () => {
  it('renders loading then user list', async () => {
    const deferred = createDeferred<{ users: UserView[] }>()
    mockList.mockReturnValue(deferred.promise)

    const { container } = renderWithProviders(<UsersPage />)

    expect(container.querySelector('svg.animate-spin')).toBeInTheDocument()

    deferred.resolve({
      users: [
        createUser(),
        createUser({ username: 'bob', role: 'user', displayName: 'Bob Li', email: 'bob@example.com' }),
      ],
    })

    await waitFor(() => {
      expect(screen.getByText('Users')).toBeInTheDocument()
      expect(screen.getByText('2 user(s)')).toBeInTheDocument()
      expect(screen.getByTestId('user-row-alice')).toBeInTheDocument()
      expect(screen.getByTestId('user-row-bob')).toBeInTheDocument()
      expect(screen.getByText('alice')).toBeInTheDocument()
      expect(screen.getByText('admin')).toBeInTheDocument()
      expect(screen.getByText('bob')).toBeInTheDocument()
      expect(screen.getByText('user')).toBeInTheDocument()
    })

    expect(mockList).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Mock Create User' })).toBeInTheDocument()
  })

  it('shows empty state', async () => {
    mockList.mockResolvedValue({ users: [] })

    renderWithProviders(<UsersPage />)

    await waitFor(() => {
      expect(screen.getByText('No users found.')).toBeInTheDocument()
      expect(screen.getByText('0 user(s)')).toBeInTheDocument()
    })
  })

  it('shows error toast on list failure', async () => {
    mockList.mockRejectedValue(new Error('Failed to fetch users'))

    renderWithProviders(<UsersPage />)

    await waitFor(() => {
      expect(screen.getByText('Error')).toBeInTheDocument()
      expect(screen.getByText('Failed to fetch users')).toBeInTheDocument()
    })
  })
})
