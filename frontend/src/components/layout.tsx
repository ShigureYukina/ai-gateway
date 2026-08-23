import { useNavigate, useLocation, Outlet } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { useTranslation } from '@/i18n'
import { cn } from '@/lib/utils'
import {
  LayoutDashboard,
  Server,
  Route,
  Key,
  Users,
  Boxes,
  Send,
  Webhook,
  Settings,
  Activity,
  User,
  LogOut,
  Globe,
} from 'lucide-react'

const navItems = [
  { path: '/', labelKey: 'dashboard', icon: LayoutDashboard },
  { path: '/providers', labelKey: 'providers', icon: Server },
  { path: '/routes', labelKey: 'routes', icon: Route },
  { path: '/clients', labelKey: 'clients', icon: Key },
  { path: '/users', labelKey: 'users', icon: Users },
  { path: '/model-groups', labelKey: 'modelGroups', icon: Boxes },
  { path: '/publications', labelKey: 'publications', icon: Send },
  { path: '/webhooks', labelKey: 'webhooks', icon: Webhook },
  { path: '/operations', labelKey: 'operations', icon: Activity },
  { path: '/settings', labelKey: 'settings', icon: Settings },
  { path: '/profile', labelKey: 'profile', icon: User },
]

export function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { username, logout } = useAuthStore()
  const { t, lang, setLang } = useTranslation()

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  return (
    <div className="flex h-screen bg-background">
      {/* Sidebar */}
      <aside className="w-56 border-r bg-muted/30 flex flex-col">
        <div className="p-4 border-b">
          <h1 className="font-bold text-lg">{t.nav.aiGateway}</h1>
          <p className="text-xs text-muted-foreground mt-0.5">{t.nav.adminConsole}</p>
        </div>

        <nav className="flex-1 p-2 space-y-1">
          {navItems.map((item) => {
            const Icon = item.icon
            const active = location.pathname === item.path
            const label = t.nav[item.labelKey as keyof typeof t.nav] as string
            return (
              <button
                key={item.path}
                onClick={() => navigate(item.path)}
                className={cn(
                  'w-full flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-colors',
                  active
                    ? 'bg-primary/10 text-primary'
                    : 'text-muted-foreground hover:bg-accent hover:text-foreground',
                )}
              >
                <Icon className="h-4 w-4" />
                {label}
              </button>
            )
          })}
        </nav>

        <div className="p-3 border-t space-y-2">
          {/* Language switcher */}
          <button
            onClick={() => setLang(lang === 'en' ? 'zh' : 'en')}
            className="w-full flex items-center gap-3 px-3 py-2 rounded-md text-sm text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
          >
            <Globe className="h-4 w-4" />
            {lang === 'en' ? t.language.zh : t.language.en}
          </button>

          <div className="flex items-center justify-between pt-1 border-t">
            <div className="text-sm">
              <p className="font-medium">{username}</p>
              <p className="text-xs text-muted-foreground">admin</p>
            </div>
            <button
              onClick={handleLogout}
              className="p-2 rounded-md hover:bg-accent text-muted-foreground"
              title={t.nav.logout}
            >
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto">
        <div className="p-6">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
