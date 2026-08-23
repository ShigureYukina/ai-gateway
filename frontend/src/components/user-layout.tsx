import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { useTranslation } from '@/i18n'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { LogOut, LayoutDashboard, Key, BarChart3, Plug } from 'lucide-react'

const userNavItems = [
  { path: '/portal/dashboard', labelKey: 'dashboard', icon: LayoutDashboard },
  { path: '/portal/keys', labelKey: 'userKeys.title', icon: Key },
  { path: '/portal/usage', labelKey: 'userUsage.title', icon: BarChart3 },
  { path: '/portal/onboarding', labelKey: 'userOnboarding.title', icon: Plug },
]

export function UserLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { username, logout } = useAuthStore()
  const { t, lang, setLang } = useTranslation()

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const tNavLabel = (key: string): string => {
    const parts = key.split('.')
    let obj: Record<string, unknown> = t as unknown as Record<string, unknown>
    for (const p of parts) {
      obj = obj[p] as Record<string, unknown>
    }
    return typeof obj === 'string' ? obj : key
  }

  return (
    <div className="min-h-screen bg-background flex flex-col">
      {/* Top bar */}
      <header className="border-b bg-muted/30">
        <div className="max-w-5xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h1 className="font-bold text-lg">{t.nav.aiGateway}</h1>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={() => setLang(lang === 'en' ? 'zh' : 'en')}
              className="text-sm text-muted-foreground hover:text-foreground transition-colors px-2 py-1 rounded"
            >
              {lang === 'en' ? '中文' : 'English'}
            </button>
            <span className="text-sm text-muted-foreground">{username}</span>
            <Button variant="ghost" size="sm" onClick={handleLogout}>
              <LogOut className="h-4 w-4 mr-1" />
              {t.nav.logout}
            </Button>
          </div>
        </div>
      </header>

      {/* Sub navigation */}
      <nav className="border-b bg-background">
        <div className="max-w-5xl mx-auto px-4 flex gap-1">
          {userNavItems.map((item) => {
            const Icon = item.icon
            const active = location.pathname === item.path
            const label = tNavLabel(item.labelKey)
            return (
              <button
                key={item.path}
                onClick={() => navigate(item.path)}
                className={cn(
                  'flex items-center gap-2 px-3 py-3 text-sm font-medium border-b-2 transition-colors',
                  active
                    ? 'border-primary text-foreground'
                    : 'border-transparent text-muted-foreground hover:text-foreground',
                )}
              >
                <Icon className="h-4 w-4" />
                {label}
              </button>
            )
          })}
        </div>
      </nav>

      {/* Content */}
      <main className="flex-1">
        <div className="max-w-5xl mx-auto px-4 py-6">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
