import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { useTranslation } from '@/i18n'
import { auth } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { toast } from '@/components/ui/toast'

export default function RegisterPage() {
  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)
  const { t } = useTranslation()

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPw, setConfirmPw] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')

    if (password !== confirmPw) {
      setError(t.register.passwordMismatch)
      return
    }
    if (password.length < 6) {
      setError(t.profile.passwordMinLength)
      return
    }

    setLoading(true)
    try {
      const res = await auth.register({
        username,
        password,
        displayName: displayName || undefined,
        email: email || undefined,
      })
      const payload = JSON.parse(atob(res.accessToken.split('.')[1]))
      const role = payload.role || 'user'
      setAuth(res.accessToken, res.refreshToken, username, role)
      toast({ title: t.register.success, variant: 'success' })
      navigate(role === 'admin' ? '/' : '/portal/dashboard')
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : t.register.failed
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex items-center justify-center min-h-screen bg-muted/30">
      <Card className="w-full max-w-sm">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl">{t.register.title}</CardTitle>
          <CardDescription>{t.register.subtitle}</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="username">{t.register.username}</Label>
              <Input id="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">{t.register.password}</Label>
              <Input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="confirmPw">{t.register.confirmPassword}</Label>
              <Input id="confirmPw" type="password" value={confirmPw} onChange={(e) => setConfirmPw(e.target.value)} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="displayName">{t.register.displayName}</Label>
              <Input id="displayName" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="email">{t.register.email}</Label>
              <Input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
            {error && <p className="text-sm text-destructive">{error}</p>}
            <Button type="submit" className="w-full" loading={loading}>
              {t.register.signUp}
            </Button>
            <p className="text-sm text-center text-muted-foreground">
              <Link to="/login" className="text-primary hover:underline">{t.register.loginLink}</Link>
            </p>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
