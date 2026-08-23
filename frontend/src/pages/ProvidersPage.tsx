import { useEffect, useState, useCallback } from 'react'
import { useTranslation } from '@/i18n'
import { providers } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { LoadingScreen } from '@/components/ui/loading'
import { toast } from '@/components/ui/toast'
import { ModelSelector } from '@/components/model-selector'
import { Plus, Pencil, Trash2, TestTube, RefreshCw } from 'lucide-react'
import type { ProviderConfig } from '@/types/api'

export default function ProvidersPage() {
  const { t } = useTranslation()
  const [allProviders, setAllProviders] = useState<Record<string, ProviderConfig>>({})
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingName, setEditingName] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<{ name: string; result: string } | null>(null)
  const [fetchingModels, setFetchingModels] = useState<string | null>(null)

  // Dialog form state
  const [formName, setFormName] = useState('')
  const [formType, setFormType] = useState('openai-compatible')
  const [formBaseUrl, setFormBaseUrl] = useState('')
  const [formApiKey, setFormApiKey] = useState('')
  const [formTimeout, setFormTimeout] = useState('30')
  const [formEnabled, setFormEnabled] = useState(true)
  const [selectedModels, setSelectedModels] = useState<string[]>([])
  const [fetchedModels, setFetchedModels] = useState<string[]>([])
  const [fetchingDialogModels, setFetchingDialogModels] = useState(false)
  const [saving, setSaving] = useState(false)

  const loadProviders = useCallback(() => {
    setLoading(true)
    providers.list()
      .then((res) => setAllProviders(res.providers))
      .catch((e) => toast({ title: t.common.error, description: e.message, variant: 'error' }))
      .finally(() => setLoading(false))
  }, [t])

  useEffect(() => {
    let active = true
    void (async () => {
      setLoading(true)
      try {
        const res = await providers.list()
        if (active) {
          setAllProviders(res.providers)
        }
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
    setEditingName(null)
    setFormName('')
    setFormType('openai-compatible')
    setFormBaseUrl('')
    setFormApiKey('')
    setFormTimeout('30')
    setFormEnabled(true)
    setSelectedModels([])
    setFetchedModels([])
    setDialogOpen(true)
  }

  const openEdit = (name: string, config: ProviderConfig) => {
    setEditingName(name)
    setFormName(name)
    setFormType(config.type)
    setFormBaseUrl(config.baseUrl)
    setFormApiKey('') // Don't pre-fill — API key is masked by backend
    setFormTimeout(String(config.timeout ? parseInt(config.timeout) : 30))
    setFormEnabled(config.enabled)
    setSelectedModels(config.models || [])
    setFetchedModels(config.models || [])
    setDialogOpen(true)
    // Try to fetch the upstream models list to populate available models
    loadFetchedModels(name)
  }

  const loadFetchedModels = async (name: string) => {
    try {
      const res = await providers.listModels(name)
      if (res.models && res.models.length > 0) {
        setFetchedModels(res.models)
      }
    } catch {
      // Ignore — fetched models may not be available yet
    }
  }

  const handleFetchModelsInDialog = async () => {
    if (!editingName) return
    setFetchingDialogModels(true)
    try {
      const res = await providers.fetchModels(editingName)
      // Merge fetched models with current selection
      const merged = [...new Set([...selectedModels, ...res.models])]
      setSelectedModels(merged)
      setFetchedModels(res.models)
      toast({ title: t.providers.fetchedModels.replace('{count}', String(res.models.length)).replace('{name}', editingName), variant: 'success' })
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setFetchingDialogModels(false)
    }
  }

  const handleSave = async () => {
    if (!formName.trim()) return
    setSaving(true)
    try {
      await providers.upsert(formName, {
        type: formType,
        baseUrl: formBaseUrl,
        apiKey: formApiKey || undefined,
        timeoutSeconds: parseInt(formTimeout) || 30,
        enabled: formEnabled,
        models: selectedModels.length > 0 ? selectedModels : undefined,
      })
      toast({ title: t.providers.savedSuccess.replace('{name}', formName), variant: 'success' })
      setDialogOpen(false)
      loadProviders()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (name: string) => {
    if (!confirm(t.common.deleteConfirm.replace('{name}', name))) return
    try {
      await providers.remove(name)
      toast({ title: t.providers.deletedSuccess.replace('{name}', name), variant: 'success' })
      loadProviders()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    }
  }

  const handleTest = async (name: string) => {
    setTestResult({ name, result: t.providers.testResult })
    try {
      const res = await providers.test(name)
      const ok = res.status === 'ok'
      setTestResult({
        name,
        result: ok
          ? `${t.providers.connectionOk} (${res.latencyMs}ms)`
          : `${t.providers.connectionFailed}: ${res.error || 'unknown error'}`,
      })
    } catch (error: unknown) {
      setTestResult({ name, result: `${t.common.error}: ${error instanceof Error ? error.message : 'Unknown error'}` })
    }
    setTimeout(() => setTestResult(null), 5000)
  }

  const handleFetchModels = async (name: string) => {
    setFetchingModels(name)
    try {
      const res = await providers.fetchModels(name)
      toast({ title: t.providers.fetchedModels.replace('{count}', String(res.models.length)).replace('{name}', name), variant: 'success' })
      loadProviders()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setFetchingModels(null)
    }
  }

  if (loading) return <LoadingScreen />

  const entries = Object.entries(allProviders)

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{t.providers.title}</h1>
          <p className="text-sm text-muted-foreground">{t.providers.intro}</p>
          <p className="text-sm text-muted-foreground">{t.providers.count.replace('{count}', String(entries.length))}</p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> {t.providers.add}
        </Button>
      </div>

      {entries.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">{t.providers.noData}</CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t.providers.name}</TableHead>
                  <TableHead>{t.providers.type}</TableHead>
                  <TableHead>{t.providers.baseUrl}</TableHead>
                  <TableHead>{t.providers.status}</TableHead>
                  <TableHead>{t.providers.models}</TableHead>
                  <TableHead className="text-right">{t.providers.actions}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map(([name, config]) => (
                  <TableRow key={name}>
                    <TableCell className="font-medium">{name}</TableCell>
                    <TableCell><Badge variant="secondary">{config.type}</Badge></TableCell>
                    <TableCell className="max-w-[250px] truncate text-muted-foreground">{config.baseUrl}</TableCell>
                    <TableCell>
                      <Badge variant={config.enabled ? 'success' : 'secondary'}>
                        {config.enabled ? t.providers.enabled : t.providers.disabled}
                      </Badge>
                    </TableCell>
                    <TableCell>{t.providers.modelsCount.replace('{count}', String((config.models || []).length))}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => handleTest(name)} title={t.providers.testConnection}>
                          <TestTube className="h-4 w-4" />
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => handleFetchModels(name)} loading={fetchingModels === name} title={t.providers.fetchModels}>
                          <RefreshCw className="h-4 w-4" />
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => openEdit(name, config)} title={t.common.edit}>
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => handleDelete(name)} title={t.common.delete}>
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

      {testResult && (
        <Card className={testResult.result.startsWith(t.providers.connectionOk) ? 'border-emerald-500' : 'border-amber-500'}>
          <CardContent className="py-3 text-sm">
            <strong>{testResult.name}:</strong> {testResult.result}
          </CardContent>
        </Card>
      )}

      {/* Create/Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editingName ? t.providers.editTitle : t.providers.addTitle}</DialogTitle>
            <DialogDescription>
              {editingName ? t.providers.editDesc.replace('{name}', editingName) : t.providers.addDesc}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 max-h-[65vh] overflow-y-auto pr-1">
            <div className="space-y-2">
              <Label htmlFor="p-name">{t.providers.fieldName}</Label>
              <Input id="p-name" value={formName} onChange={(e) => setFormName(e.target.value)} placeholder="my-provider" disabled={!!editingName} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="p-type">{t.providers.fieldType}</Label>
              <Select id="p-type" value={formType} onChange={(e) => setFormType(e.target.value)}>
                <option value="openai-compatible">{t.providers.typeOpenai}</option>
                <option value="openai">OpenAI</option>
                <option value="anthropic">{t.providers.typeAnthropic}</option>
                <option value="gemini">{t.providers.typeGemini}</option>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="p-url">{t.providers.fieldBaseUrl}</Label>
              <Input id="p-url" value={formBaseUrl} onChange={(e) => setFormBaseUrl(e.target.value)} placeholder={t.providers.urlPlaceholder} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="p-key">{t.providers.fieldApiKey}</Label>
              <Input id="p-key" type="password" value={formApiKey} onChange={(e) => setFormApiKey(e.target.value)} placeholder={t.providers.keyPlaceholder} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="p-timeout">{t.providers.fieldTimeout}</Label>
                <Input id="p-timeout" type="number" value={formTimeout} onChange={(e) => setFormTimeout(e.target.value)} />
              </div>
              <div className="space-y-2 flex items-end pb-2">
                <Switch checked={formEnabled} onCheckedChange={setFormEnabled} label={t.providers.fieldEnabled} />
              </div>
            </div>

            {/* Model Selector — replaces the old text input */}
            <ModelSelector
              availableModels={fetchedModels}
              selectedModels={selectedModels}
              onChange={setSelectedModels}
              onFetchModels={editingName ? handleFetchModelsInDialog : undefined}
              fetching={fetchingDialogModels}
            />
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
