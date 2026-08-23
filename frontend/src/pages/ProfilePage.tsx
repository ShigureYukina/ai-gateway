import { useEffect, useState } from 'react'
import { auth } from '@/api/client'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { toast } from '@/components/ui/toast'
import { useTranslation } from '@/i18n'
import { LoadingScreen } from '@/components/ui/loading'
import type { UserMeResponse } from '@/types/api'

export default function ProfilePage() {
  const { t } = useTranslation()
  const [profile, setProfile] = useState<UserMeResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Profile form
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [savingProfile, setSavingProfile] = useState(false)

  // Password form
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [savingPassword, setSavingPassword] = useState(false)

  useEffect(() => {
    auth.me()
      .then((res) => {
        setProfile(res)
        setDisplayName(res.displayName ?? '')
        setEmail(res.email ?? '')
      })
      .catch((err) => setError(err.message ?? 'Failed to load profile'))
      .finally(() => setLoading(false))
  }, [])

  const handleSaveProfile = async () => {
    setSavingProfile(true)
    try {
      const updated = await auth.updateProfile({ displayName, email })
      setProfile(updated)
      setDisplayName(updated.displayName ?? '')
      setEmail(updated.email ?? '')
      toast({ title: t.profile.profileUpdated, variant: 'success' })
    } catch (err: unknown) {
      toast({ title: t.common.error, description: err instanceof Error ? err.message : 'Failed to update profile', variant: 'error' })
    } finally {
      setSavingProfile(false)
    }
  }

  const handleChangePassword = async () => {
    if (newPassword !== confirmPassword) {
      toast({ title: t.profile.passwordMismatch, variant: 'error' })
      return
    }
    if (newPassword.length < 6) {
      toast({ title: t.profile.passwordMinLength, variant: 'error' })
      return
    }
    setSavingPassword(true)
    try {
      await auth.changePassword({ oldPassword, newPassword })
      toast({ title: t.profile.passwordChanged, variant: 'success' })
      setOldPassword('')
      setNewPassword('')
      setConfirmPassword('')
    } catch (err: unknown) {
      toast({ title: t.profile.passwordChangeFailed, description: err instanceof Error ? err.message : undefined, variant: 'error' })
    } finally {
      setSavingPassword(false)
    }
  }

  if (loading) return <LoadingScreen />
  if (error) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <p className="text-destructive">{t.profile.loadingFailed}: {error}</p>
      </div>
    )
  }

  const formattedDate = profile?.createdAt
    ? new Date(profile.createdAt).toLocaleDateString()
    : '-'

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <h1 className="text-2xl font-bold">{t.profile.title}</h1>

      {/* Basic Information */}
      <Card>
        <CardHeader>
          <CardTitle>{t.profile.basicInfo}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <Label>{t.login.username}</Label>
              <p className="text-sm font-medium mt-1">{profile?.username}</p>
            </div>
            <div>
              <Label>{t.profile.role}</Label>
              <p className="text-sm font-medium mt-1">{profile?.role}</p>
            </div>
            <div>
              <Label>{t.profile.displayName}</Label>
              <Input
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder={profile?.username ?? ''}
              />
            </div>
            <div>
              <Label>{t.profile.email}</Label>
              <Input
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="user@example.com"
              />
            </div>
          </div>
          <div>
            <Label>{t.profile.memberSince}</Label>
            <p className="text-sm font-medium mt-1">{formattedDate}</p>
          </div>
          <Button onClick={handleSaveProfile} loading={savingProfile}>
            {t.profile.saveProfile}
          </Button>
        </CardContent>
      </Card>

      {/* Change Password */}
      <Card>
        <CardHeader>
          <CardTitle>{t.profile.changePassword}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div>
            <Label>{t.profile.oldPassword}</Label>
            <Input
              type="password"
              value={oldPassword}
              onChange={(e) => setOldPassword(e.target.value)}
            />
          </div>
          <div>
            <Label>{t.profile.newPassword}</Label>
            <Input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </div>
          <div>
            <Label>{t.profile.confirmPassword}</Label>
            <Input
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
            />
          </div>
          <Button onClick={handleChangePassword} loading={savingPassword}>
            {t.profile.updatePassword}
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
