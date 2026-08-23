import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen } from '@/test/utils'
import { UserLayout } from './user-layout'
import { useAuthStore } from '@/store/auth'

// Mock the auth store
vi.mock('@/store/auth', () => ({
  useAuthStore: vi.fn(),
}))

const mockLogout = vi.fn()
const mockNavigate = vi.fn()
let mockPathname = '/portal/dashboard'

// Mock react-router-dom hooks and Outlet
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useLocation: () => ({ pathname: mockPathname }),
    Outlet: () => <div data-testid="user-layout-outlet">Outlet Content</div>,
  }
})

describe('UserLayout', () => {
  const mockedUseAuthStore = vi.mocked(useAuthStore)

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('gateway-language', 'en')
    mockPathname = '/portal/dashboard'
    mockedUseAuthStore.mockReturnValue({
      username: 'demo-user',
      logout: mockLogout,
    } as ReturnType<typeof useAuthStore>)
  })

  it('renders top bar with username and language toggle', () => {
    renderWithProviders(<UserLayout />)

    expect(screen.getByText('AI Gateway')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '中文' })).toBeInTheDocument()
    expect(screen.getByText('demo-user')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Logout' })).toBeInTheDocument()
  })

  it('renders navigation items and highlights active', () => {
    mockPathname = '/portal/usage'

    renderWithProviders(<UserLayout />)

    expect(screen.getByRole('button', { name: 'dashboard' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'My API Keys' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Usage & Costs' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connection Guide' })).toBeInTheDocument()

    const activeItem = screen.getByRole('button', { name: 'Usage & Costs' })
    expect(activeItem.className).toContain('border-primary')
    expect(activeItem.className).toContain('text-foreground')
  })

  it('calls logout and navigates to login on logout click', async () => {
    const user = userEvent.setup()

    renderWithProviders(<UserLayout />)

    await user.click(screen.getByRole('button', { name: 'Logout' }))

    expect(mockLogout).toHaveBeenCalledTimes(1)
    expect(mockNavigate).toHaveBeenCalledWith('/login')
  })

  it('renders outlet content area', () => {
    renderWithProviders(<UserLayout />)

    expect(screen.getByTestId('user-layout-outlet')).toBeInTheDocument()
    expect(screen.getByText('Outlet Content')).toBeInTheDocument()
  })
})
