import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { useTranslation } from '@/i18n'
import { auth } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

export default function LoginPage() {
  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)
  const { t } = useTranslation()
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('admin123')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const res = await auth.login({ username, password })
      const payload = JSON.parse(atob(res.accessToken.split('.')[1]))
      const role = payload.role || 'user'
      setAuth(res.accessToken, res.refreshToken, username, role)
      navigate(role === 'admin' ? '/' : '/portal/dashboard')
    } catch (error: unknown) {
      setError(error instanceof Error ? error.message : t.login.failed)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex items-center justify-center min-h-screen bg-muted/30">
      <Card className="w-full max-w-sm">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl">{t.login.title}</CardTitle>
          <CardDescription>{t.login.subtitle}</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="username">{t.login.username}</Label>
              <Input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder={t.login.placeholderUsername}
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">{t.login.password}</Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t.login.placeholderPassword}
                required
              />
            </div>
            {error && <p className="text-sm text-destructive">{error}</p>}
            <Button type="submit" className="w-full" loading={loading}>
              {t.login.signIn}
            </Button>
            <p className="text-sm text-center text-muted-foreground">
              {t.login.registerPrompt}{' '}
              <Link to="/register" className="text-primary hover:underline">{t.login.registerLink}</Link>
            </p>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
