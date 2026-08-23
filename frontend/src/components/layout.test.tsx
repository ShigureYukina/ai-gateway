import { screen } from '@/test/utils'
import { render } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { I18nProvider } from '@/i18n'
import { Toaster } from '@/components/ui/toast'
import { AppLayout } from './layout'
import { useAuthStore } from '@/store/auth'

function AllProviders({ children }: { children: React.ReactNode }) {
  return (
    <I18nProvider>
      <Toaster>
        {children}
      </Toaster>
    </I18nProvider>
  )
}

// Helper to render AppLayout with a specific route
function renderAtRoute(path: string) {
  useAuthStore.setState({
    token: 'test-token',
    refreshToken: 'rt',
    username: 'admin-user',
    role: 'admin',
    isAuthenticated: true,
  })

  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<div data-testid="page-dashboard" />} />
          <Route path="/providers" element={<div data-testid="page-providers" />} />
          <Route path="/routes" element={<div data-testid="page-routes" />} />
          <Route path="/clients" element={<div data-testid="page-clients" />} />
          <Route path="/users" element={<div data-testid="page-users" />} />
          <Route path="/model-groups" element={<div data-testid="page-model-groups" />} />
          <Route path="/publications" element={<div data-testid="page-publications" />} />
          <Route path="/webhooks" element={<div data-testid="page-webhooks" />} />
          <Route path="/operations" element={<div data-testid="page-operations" />} />
          <Route path="/settings" element={<div data-testid="page-settings" />} />
        </Route>
      </Routes>
    </MemoryRouter>,
    { wrapper: AllProviders },
  )
}

beforeEach(() => {
  useAuthStore.setState({
    token: null,
    refreshToken: null,
    username: null,
    role: null,
    isAuthenticated: false,
  })
})

describe('AppLayout', () => {
  it('renders all navigation items', () => {
    renderAtRoute('/')
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Channels')).toBeInTheDocument()
    expect(screen.getByText('Model Routes')).toBeInTheDocument()
    expect(screen.getByText('Clients')).toBeInTheDocument()
    expect(screen.getByText('Users')).toBeInTheDocument()
    expect(screen.getByText('Model Groups')).toBeInTheDocument()
    expect(screen.getByText('Publications')).toBeInTheDocument()
    expect(screen.getByText('Webhooks')).toBeInTheDocument()
    expect(screen.getByText('Operations')).toBeInTheDocument()
    expect(screen.getByText('System Config')).toBeInTheDocument()
  })

  it('shows the brand name', () => {
    renderAtRoute('/')
    expect(screen.getByText('AI Gateway')).toBeInTheDocument()
    expect(screen.getByText('Admin Console')).toBeInTheDocument()
  })

  it('renders the child route via Outlet', () => {
    renderAtRoute('/')
    expect(screen.getByTestId('page-dashboard')).toBeInTheDocument()
  })

  it('renders correct child for providers route', () => {
    renderAtRoute('/providers')
    expect(screen.getByTestId('page-providers')).toBeInTheDocument()
  })

  it('shows username', () => {
    renderAtRoute('/')
    expect(screen.getByText('admin-user')).toBeInTheDocument()
  })

  it('highlights the active nav item', () => {
    renderAtRoute('/providers')
    const providersBtn = screen.getByText('Channels')
    // Active item has `bg-primary/10 text-primary` class
    expect(providersBtn.className).toContain('bg-primary/10')
  })

  it('renders correct child for model groups route', () => {
    renderAtRoute('/model-groups')
    expect(screen.getByTestId('page-model-groups')).toBeInTheDocument()
  })

  it('navigates to new shared entry when nav item is clicked', async () => {
    const user = (await import('@testing-library/user-event')).default
    renderAtRoute('/')

    await user.click(screen.getByText('Webhooks'))
    expect(screen.getByTestId('page-webhooks')).toBeInTheDocument()
  })

  it('navigates when a nav item is clicked', async () => {
    const user = (await import('@testing-library/user-event')).default
    renderAtRoute('/')

    await user.click(screen.getByText('System Config'))
    expect(screen.getByTestId('page-settings')).toBeInTheDocument()
  })
})
