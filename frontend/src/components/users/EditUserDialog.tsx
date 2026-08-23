import { useEffect, useState } from 'react'
import { useTranslation } from '@/i18n'
import { users, providers } from '@/api/client'
import { ModelSelector } from '@/components/model-selector'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { toast } from '@/components/ui/toast'
import { RefreshCw } from 'lucide-react'
import type { UserView } from '@/types/api'

export function EditUserDialog({
  user, open, onOpenChange, onUpdated,
}: {
  user: UserView; open: boolean; onOpenChange: (open: boolean) => void; onUpdated: () => void
}) {
  const { t } = useTranslation()
  const [role, setRole] = useState(user.role)
  const [frozen, setFrozen] = useState(user.frozen)
  const [dailyTokens, setDailyTokens] = useState(String(user.limits?.dailyTokens ?? ''))
  const [monthlyTokens, setMonthlyTokens] = useState(String(user.limits?.monthlyTokens ?? ''))
  const [dailyCost, setDailyCost] = useState(String(user.limits?.dailyCost ?? ''))
  const [monthlyCost, setMonthlyCost] = useState(String(user.limits?.monthlyCost ?? ''))
  const [tpm, setTpm] = useState(String(user.limits?.tokensPerMinute ?? ''))
  const [allowedModels, setAllowedModels] = useState<string[]>(user.allowedModels || [])
  const [availableModels, setAvailableModels] = useState<string[]>([])
  const [saving, setSaving] = useState(false)
  const [resetPwResult, setResetPwResult] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return;
    (async () => {
      setRole(user.role)
      setFrozen(user.frozen)
      setDailyTokens(String(user.limits?.dailyTokens ?? ''))
      setMonthlyTokens(String(user.limits?.monthlyTokens ?? ''))
      setDailyCost(String(user.limits?.dailyCost ?? ''))
      setMonthlyCost(String(user.limits?.monthlyCost ?? ''))
      setTpm(String(user.limits?.tokensPerMinute ?? ''))
      setAllowedModels(user.allowedModels || [])
      setResetPwResult(null)
      try {
        const res = await providers.list()
        const allModels: string[] = []
        for (const cfg of Object.values(res.providers)) {
          if (cfg.models) allModels.push(...cfg.models)
        }
        setAvailableModels([...new Set(allModels)])
      } catch { /* ignore */ }
    })()
  }, [open, user])

  const handleSave = async () => {
    setSaving(true)
    try {
      await users.update(user.username, { role, frozen })
      await users.updateLimits(user.username, {
        dailyTokens: parseInt(dailyTokens) || undefined,
        monthlyTokens: parseInt(monthlyTokens) || undefined,
        dailyCost: parseFloat(dailyCost) || undefined,
        monthlyCost: parseFloat(monthlyCost) || undefined,
        tokensPerMinute: parseInt(tpm) || undefined,
      })
      await users.updateAllowedModels(user.username, { allowedModels })
      toast({ title: t.users.updatedSuccess.replace('{name}', user.username), variant: 'success' })
      onOpenChange(false)
      onUpdated()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  const handleResetPassword = async () => {
    try {
      const res = await users.resetPassword(user.username)
      setResetPwResult(res.temporaryPassword)
      toast({ title: t.users.resetPwSuccess, variant: 'success' })
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t.users.editTitle.replace('{name}', user.username)}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4 max-h-[60vh] overflow-y-auto">
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>{t.users.fieldRole}</Label>
              <Select value={role} onChange={(e) => setRole(e.target.value)}>
                <option value="user">User</option>
                <option value="admin">Admin</option>
              </Select>
            </div>
            <div className="space-y-2 flex items-end pb-2">
              <Switch checked={frozen} onCheckedChange={setFrozen} label={t.users.fieldFrozen} />
            </div>
          </div>

          <div className="border-t pt-4">
            <p className="text-sm font-medium mb-2">{t.users.limitsSection}</p>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>{t.users.fieldDailyTokens}</Label>
                <Input type="number" value={dailyTokens} onChange={(e) => setDailyTokens(e.target.value)} placeholder={t.common.unlimited} />
              </div>
              <div className="space-y-2">
                <Label>{t.users.fieldMonthlyTokens}</Label>
                <Input type="number" value={monthlyTokens} onChange={(e) => setMonthlyTokens(e.target.value)} placeholder={t.common.unlimited} />
              </div>
              <div className="space-y-2">
                <Label>{t.users.fieldDailyCost}</Label>
                <Input type="number" step="0.01" value={dailyCost} onChange={(e) => setDailyCost(e.target.value)} placeholder={t.common.unlimited} />
              </div>
              <div className="space-y-2">
                <Label>{t.users.fieldMonthlyCost}</Label>
                <Input type="number" step="0.01" value={monthlyCost} onChange={(e) => setMonthlyCost(e.target.value)} placeholder={t.common.unlimited} />
              </div>
              <div className="space-y-2">
                <Label>{t.users.fieldTpm}</Label>
                <Input type="number" value={tpm} onChange={(e) => setTpm(e.target.value)} placeholder={t.common.unlimited} />
              </div>
            </div>
          </div>

          <ModelSelector
            availableModels={availableModels}
            selectedModels={allowedModels}
            onChange={setAllowedModels}
          />

          <div className="border-t pt-4">
            <p className="text-sm font-medium mb-2">{t.users.passwordSection}</p>
            <Button variant="outline" size="sm" onClick={handleResetPassword}>
              <RefreshCw className="h-4 w-4" /> {t.users.resetPassword}
            </Button>
            {resetPwResult && (
              <div className="mt-2 p-2 bg-muted rounded text-sm">
                {t.users.tempPassword}: <code className="font-mono">{resetPwResult}</code>
              </div>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t.common.cancel}</Button>
          <Button onClick={handleSave} loading={saving}>{t.common.save}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
