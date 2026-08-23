import { useEffect, useRef } from 'react'
import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from '@/components/ui/toast'
import { I18nProvider } from '@/i18n'
import { ErrorBoundary } from '@/components/error-boundary'

const queryClient = new QueryClient()
import { AppLayout } from '@/components/layout'
import { UserLayout } from '@/components/user-layout'
import { useAuthStore } from '@/store/auth'
import LoginPage from '@/pages/LoginPage'
import RegisterPage from '@/pages/RegisterPage'
import DashboardPage from '@/pages/DashboardPage'
import ProvidersPage from '@/pages/ProvidersPage'
import RoutesPage from '@/pages/RoutesPage'
import ClientsPage from '@/pages/ClientsPage'
import UsersPage from '@/pages/UsersPage'
import SystemConfigPage from '@/pages/SystemConfigPage'
import OperationsPage from '@/pages/OperationsPage'
import ProfilePage from '@/pages/ProfilePage'
import ModelGroupsPage from '@/pages/ModelGroupsPage'
import PublicationsPage from '@/pages/PublicationsPage'
import WebhooksPage from '@/pages/WebhooksPage'
import UserDashboardPage from '@/pages/user/DashboardPage'
import UserApiKeysPage from '@/pages/user/ApiKeysPage'
import UserUsagePage from '@/pages/user/UsagePage'
import UserOnboardingPage from '@/pages/user/OnboardingPage'

/** Require authentication; optionally require a specific role */
function RequireAuth({ requiredRole, children }: { requiredRole?: string; children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const role = useAuthStore((s) => s.role)
  if (!isAuthenticated) return <Navigate to="/login" replace />
  if (requiredRole && role !== requiredRole) {
    // Non-admin user trying to access admin page — redirect to user portal
    if (requiredRole === 'admin') {
      return <Navigate to="/portal/dashboard" replace />
    }
    return <Navigate to="/" replace />
  }
  return <>{children}</>
}

/** Redirect to login when auth state changes to unauthenticated */
function useLogoutRedirect() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const navigate = useNavigate()
  const wasAuthenticated = useRef(isAuthenticated)
  useEffect(() => {
    if (wasAuthenticated.current && !isAuthenticated) {
      navigate('/login', { replace: true })
    }
    wasAuthenticated.current = isAuthenticated
  }, [isAuthenticated, navigate])
}

function AppRoutes() {
  useLogoutRedirect()
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      {/* Admin management console */}
      <Route
        element={
          <RequireAuth requiredRole="admin">
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="providers" element={<ProvidersPage />} />
        <Route path="routes" element={<RoutesPage />} />
        <Route path="clients" element={<ClientsPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="model-groups" element={<ModelGroupsPage />} />
        <Route path="publications" element={<PublicationsPage />} />
        <Route path="webhooks" element={<WebhooksPage />} />
        <Route path="operations" element={<OperationsPage />} />
        <Route path="settings" element={<SystemConfigPage />} />
        <Route path="profile" element={<ProfilePage />} />
      </Route>

      {/* User portal */}
      <Route
        element={
          <RequireAuth>
            <UserLayout />
          </RequireAuth>
        }
      >
        <Route path="portal/dashboard" element={<UserDashboardPage />} />
        <Route path="portal/keys" element={<UserApiKeysPage />} />
        <Route path="portal/usage" element={<UserUsagePage />} />
        <Route path="portal/onboarding" element={<UserOnboardingPage />} />
        <Route path="portal" element={<Navigate to="/portal/dashboard" replace />} />
      </Route>
    </Routes>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <I18nProvider>
          <Toaster>
            <ErrorBoundary>
              <AppRoutes />
            </ErrorBoundary>
          </Toaster>
        </I18nProvider>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
