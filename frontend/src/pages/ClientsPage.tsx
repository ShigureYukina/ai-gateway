import { useEffect, useState, useCallback } from 'react'
import { useTranslation } from '@/i18n'
import { clients, providers, routes } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { LoadingScreen } from '@/components/ui/loading'
import { ModelSelector } from '@/components/model-selector'
import { TagSelector } from '@/components/tag-selector'
import { toast } from '@/components/ui/toast'
import { Plus, Pencil, Trash2 } from 'lucide-react'
import type { ClientConfig } from '@/types/api'

export default function ClientsPage() {
  const { t } = useTranslation()
  const [allClients, setAllClients] = useState<Record<string, ClientConfig>>({})
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingKey, setEditingKey] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [availableModels, setAvailableModels] = useState<string[]>([])
  const [availableScenes, setAvailableScenes] = useState<string[]>([])

  const [formKey, setFormKey] = useState('')
  const [formEnabled, setFormEnabled] = useState(true)
  const [formAllowedModels, setFormAllowedModels] = useState<string[]>([])
  const [formAllowedScenes, setFormAllowedScenes] = useState<string[]>([])
  const [formStreaming, setFormStreaming] = useState(true)
  const [formTemp, setFormTemp] = useState('0.7')
  const [formMaxTokens, setFormMaxTokens] = useState('256')
  const [formDailyTokens, setFormDailyTokens] = useState('')
  const [formMonthlyTokens, setFormMonthlyTokens] = useState('')
  const [formDailyCost, setFormDailyCost] = useState('')
  const [formMonthlyCost, setFormMonthlyCost] = useState('')
  const [formTpm, setFormTpm] = useState('')

  const loadAll = useCallback(() => {
    setLoading(true)
    Promise.all([
      clients.list(),
      providers.list(),
      routes.list(),
    ])
      .then(([cRes, pRes, rRes]) => {
        setAllClients(cRes.clients)
        // Collect all models from all providers
        const allModels: string[] = []
        for (const cfg of Object.values(pRes.providers)) {
          if (cfg.models) allModels.push(...cfg.models)
        }
        setAvailableModels([...new Set(allModels)])
        // Collect all scenes from routes
        const allScenes: string[] = []
        for (const cfg of Object.values(rRes.routes)) {
          if (cfg.scene) allScenes.push(cfg.scene)
        }
        setAvailableScenes([...new Set(allScenes)])
      })
      .catch((e) => toast({ title: t.common.error, description: e.message, variant: 'error' }))
      .finally(() => setLoading(false))
  }, [t])

  useEffect(() => {
    let active = true
    void (async () => {
      setLoading(true)
      try {
        const [cRes, pRes, rRes] = await Promise.all([
          clients.list(),
          providers.list(),
          routes.list(),
        ])

        if (!active) return

        setAllClients(cRes.clients)

        const allModels: string[] = []
        for (const cfg of Object.values(pRes.providers)) {
          if (cfg.models) allModels.push(...cfg.models)
        }
        setAvailableModels([...new Set(allModels)])

        const allScenes: string[] = []
        for (const cfg of Object.values(rRes.routes)) {
          if (cfg.scene) allScenes.push(cfg.scene)
        }
        setAvailableScenes([...new Set(allScenes)])
      } catch (error: unknown) {
        if (active) {
          toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
        }
      } finally {
        if (active) {
          setLoading(false)
        }
      }
    })()

    return () => {
      active = false
    }
  }, [t])

  const openCreate = () => {
    setEditingKey(null)
    setFormKey('')
    setFormEnabled(true)
    setFormAllowedModels([])
    setFormAllowedScenes([])
    setFormStreaming(true)
    setFormTemp('0.7')
    setFormMaxTokens('256')
    setFormDailyTokens('')
    setFormMonthlyTokens('')
    setFormDailyCost('')
    setFormMonthlyCost('')
    setFormTpm('')
    setDialogOpen(true)
  }

  const openEdit = (key: string, config: ClientConfig) => {
    setEditingKey(key)
    setFormKey('') // Don't pre-fill — client key is masked by backend
    setFormEnabled(config.enabled)
    setFormAllowedModels(config.allowedModels || [])
    setFormAllowedScenes(config.allowedScenes || [])
    setFormStreaming(config.capabilities?.streaming ?? true)
    setFormTemp(String(config.defaults?.temperature ?? 0.7))
    setFormMaxTokens(String(config.defaults?.maxTokens ?? ''))
    setFormDailyTokens(String(config.limits?.dailyTokens ?? ''))
    setFormMonthlyTokens(String(config.limits?.monthlyTokens ?? ''))
    setFormDailyCost(String(config.limits?.dailyCost ?? ''))
    setFormMonthlyCost(String(config.limits?.monthlyCost ?? ''))
    setFormTpm(String(config.limits?.tokensPerMinute ?? ''))
    setDialogOpen(true)
  }

  const handleSave = async () => {
    if (!formKey.trim()) return
    setSaving(true)
    try {
      const cfg: ClientConfig = {
        enabled: formEnabled,
        allowedModels: formAllowedModels,
        allowedScenes: formAllowedScenes,
        capabilities: { streaming: formStreaming },
        defaults: {
          temperature: parseFloat(formTemp) || undefined,
          maxTokens: parseInt(formMaxTokens) || undefined,
        },
        limits: {
          dailyTokens: parseInt(formDailyTokens) || undefined,
          monthlyTokens: parseInt(formMonthlyTokens) || undefined,
          dailyCost: parseFloat(formDailyCost) || undefined,
          monthlyCost: parseFloat(formMonthlyCost) || undefined,
          tokensPerMinute: parseInt(formTpm) || undefined,
        },
      }
      if (editingKey && editingKey !== formKey) {
        await clients.remove(editingKey)
      }
      await clients.upsert(formKey, cfg)
      toast({ title: t.clients.savedSuccess.replace('{name}', formKey), variant: 'success' })
      setDialogOpen(false)
      loadAll()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (key: string) => {
    if (!confirm(t.common.deleteConfirm.replace('{name}', key))) return
    try {
      await clients.remove(key)
      toast({ title: t.clients.deletedSuccess.replace('{name}', key), variant: 'success' })
      loadAll()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    }
  }

  if (loading) return <LoadingScreen />

  const entries = Object.entries(allClients)

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{t.clients.title}</h1>
          <p className="text-sm text-muted-foreground">{t.clients.count.replace('{count}', String(entries.length))}</p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> {t.clients.add}
        </Button>
      </div>

      {entries.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">{t.clients.noData}</CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t.clients.clientKey}</TableHead>
                  <TableHead>{t.common.status}</TableHead>
                  <TableHead>{t.clients.models}</TableHead>
                  <TableHead>{t.clients.scenes}</TableHead>
                  <TableHead>{t.clients.streaming}</TableHead>
                  <TableHead>{t.clients.dailyTokens}</TableHead>
                  <TableHead>{t.clients.dailyCost}</TableHead>
                  <TableHead className="text-right">{t.common.actions}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map(([key, config]) => (
                  <TableRow key={key}>
                    <TableCell className="font-medium max-w-[150px] truncate" title={key}>{key}</TableCell>
                    <TableCell>
                      <Badge variant={config.enabled ? 'success' : 'secondary'}>
                        {config.enabled ? t.common.enabled : t.common.disabled}
                      </Badge>
                    </TableCell>
                    <TableCell>{(config.allowedModels || []).length}</TableCell>
                    <TableCell>{(config.allowedScenes || []).length}</TableCell>
                    <TableCell>{config.capabilities?.streaming ? '✓' : '—'}</TableCell>
                    <TableCell>{config.limits?.dailyTokens?.toLocaleString() || t.common.unlimited}</TableCell>
                    <TableCell>{config.limits?.dailyCost ? `$${config.limits.dailyCost}` : t.common.unlimited}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => openEdit(key, config)} title={t.common.edit}>
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => handleDelete(key)} title={t.common.delete}>
                          <Trash2 className="h-4 w-4 text-destructive" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editingKey ? t.clients.editTitle : t.clients.addTitle}</DialogTitle>
            <DialogDescription>{t.clients.addTitle}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 max-h-[60vh] overflow-y-auto">
            <div className="space-y-2">
              <Label htmlFor="c-key">{t.clients.fieldClientKey}</Label>
              <Input id="c-key" value={formKey} onChange={(e) => setFormKey(e.target.value)} placeholder={t.clients.keyPlaceholder} />
              {editingKey && <p className="text-xs text-muted-foreground">{t.clients.keyEditHint}</p>}
            </div>
            <Switch checked={formEnabled} onCheckedChange={setFormEnabled} label={t.clients.fieldEnabled} />

            <ModelSelector
              availableModels={availableModels}
              selectedModels={formAllowedModels}
              onChange={setFormAllowedModels}
            />
            <TagSelector
              label={t.clients.fieldAllowedScenes}
              options={availableScenes}
              selected={formAllowedScenes}
              onChange={setFormAllowedScenes}
              inputPlaceholder={t.clients.scenesPlaceholder}
              searchPlaceholder={t.providers.filterModels || 'Filter...'}
            />

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="c-temp">{t.clients.fieldTemp}</Label>
                <Input id="c-temp" type="number" step="0.1" value={formTemp} onChange={(e) => setFormTemp(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="c-maxtokens">{t.clients.fieldMaxTokens}</Label>
                <Input id="c-maxtokens" type="number" value={formMaxTokens} onChange={(e) => setFormMaxTokens(e.target.value)} />
              </div>
            </div>
            <Switch checked={formStreaming} onCheckedChange={setFormStreaming} label={t.clients.fieldStreaming} />

            <div className="border-t pt-4">
              <p className="text-sm font-medium mb-2">{t.clients.rateLimits}</p>
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label>{t.clients.fieldDailyTokens}</Label>
                  <Input type="number" value={formDailyTokens} onChange={(e) => setFormDailyTokens(e.target.value)} placeholder={t.common.unlimited} />
                </div>
                <div className="space-y-2">
                  <Label>{t.clients.fieldMonthlyTokens}</Label>
                  <Input type="number" value={formMonthlyTokens} onChange={(e) => setFormMonthlyTokens(e.target.value)} placeholder={t.common.unlimited} />
                </div>
                <div className="space-y-2">
                  <Label>{t.clients.fieldDailyCost}</Label>
                  <Input type="number" step="0.01" value={formDailyCost} onChange={(e) => setFormDailyCost(e.target.value)} placeholder={t.common.unlimited} />
                </div>
                <div className="space-y-2">
                  <Label>{t.clients.fieldMonthlyCost}</Label>
                  <Input type="number" step="0.01" value={formMonthlyCost} onChange={(e) => setFormMonthlyCost(e.target.value)} placeholder={t.common.unlimited} />
                </div>
                <div className="space-y-2">
                  <Label>{t.clients.fieldTpm}</Label>
                  <Input type="number" value={formTpm} onChange={(e) => setFormTpm(e.target.value)} placeholder={t.common.unlimited} />
                </div>
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>{t.common.cancel}</Button>
            <Button onClick={handleSave} loading={saving}>{t.common.save}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
