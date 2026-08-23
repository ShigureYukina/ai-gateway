import { useEffect, useState } from 'react'
import { useTranslation } from '@/i18n'
import { routes } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { toast } from '@/components/ui/toast'
import { Plus, X, ArrowUp, ArrowDown } from 'lucide-react'
import type { RouteConfig } from '@/types/api'

const STRATEGIES = ['round-robin', 'random', 'failover', 'weighted'] as const

export function RouteEditDialog({
  open, onOpenChange, editingRoute, onSaved,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  editingRoute: { id: string; config: RouteConfig } | null
  onSaved: () => void
}) {
  const { t } = useTranslation()

  const [formId, setFormId] = useState(editingRoute?.id ?? '')
  const [formProvider, setFormProvider] = useState(editingRoute?.config.provider ?? '')
  const [formUpstreamModels, setFormUpstreamModels] = useState<string[]>(
    editingRoute?.config.upstreamModels?.length
      ? editingRoute.config.upstreamModels
      : editingRoute?.config.upstreamModel
        ? [editingRoute.config.upstreamModel]
        : [],
  )
  const [formStrategy, setFormStrategy] = useState(editingRoute?.config.strategy || 'round-robin')
  const [formScene, setFormScene] = useState(editingRoute?.config.scene || '')
  const [formWeight, setFormWeight] = useState(String(editingRoute?.config.weight || 1))
  const [formEnabled, setFormEnabled] = useState(editingRoute?.config.enabled ?? true)
  const [newModelInput, setNewModelInput] = useState('')
  const [saving, setSaving] = useState(false)

  // Reset form when dialog opens or editing target changes
  useEffect(() => {
    if (!open) return;
    (() => {
      setFormId(editingRoute?.id ?? '')
      setFormProvider(editingRoute?.config.provider ?? '')
      setFormUpstreamModels(
        editingRoute?.config.upstreamModels?.length
          ? editingRoute.config.upstreamModels
          : editingRoute?.config.upstreamModel
            ? [editingRoute.config.upstreamModel]
            : [],
      )
      setFormStrategy(editingRoute?.config.strategy || 'round-robin')
      setFormScene(editingRoute?.config.scene || '')
      setFormWeight(String(editingRoute?.config.weight || 1))
      setFormEnabled(editingRoute?.config.enabled ?? true)
      setNewModelInput('')
    })();
  }, [open, editingRoute])

  const addUpstreamModel = () => {
    const name = newModelInput.trim()
    if (!name || formUpstreamModels.includes(name)) return
    setFormUpstreamModels([...formUpstreamModels, name])
    setNewModelInput('')
  }

  const removeUpstreamModel = (model: string) => {
    setFormUpstreamModels(formUpstreamModels.filter((m) => m !== model))
  }

  const moveUpstreamModel = (index: number, direction: -1 | 1) => {
    const target = index + direction
    if (target < 0 || target >= formUpstreamModels.length) return
    const next = [...formUpstreamModels]
    ;[next[index], next[target]] = [next[target], next[index]]
    setFormUpstreamModels(next)
  }

  const handleSave = async () => {
    if (!formId.trim()) return
    setSaving(true)
    try {
      const routeConfig: RouteConfig = {
        provider: formProvider,
        upstreamModel: formUpstreamModels[0] || '',
        upstreamModels: formUpstreamModels,
        strategy: formStrategy,
        scene: formScene || undefined,
        weight: parseInt(formWeight) || 1,
        enabled: formEnabled,
      }
      const editingId = editingRoute?.id
      if (editingId && editingId !== formId) {
        await routes.remove(editingId)
      }
      await routes.upsert(formId, routeConfig)
      toast({ title: t.routes.savedSuccess.replace('{name}', formId), variant: 'success' })
      onOpenChange(false)
      onSaved()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{editingRoute ? t.routes.editTitle : t.routes.addTitle}</DialogTitle>
          <DialogDescription>{t.routes.addDesc}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 max-h-[65vh] overflow-y-auto pr-1">
          <div className="space-y-2">
            <Label htmlFor="r-id">{t.routes.fieldId}</Label>
            <Input id="r-id" value={formId} onChange={(e) => setFormId(e.target.value)} placeholder={t.routes.idPlaceholder} />
          </div>

          <div className="space-y-2">
            <Label htmlFor="r-provider">{t.routes.fieldProvider}</Label>
            <Input
              id="r-provider"
              value={formProvider}
              onChange={(e) => {
                setFormProvider(e.target.value)
                setFormUpstreamModels([])
              }}
              placeholder={t.routes.providerPlaceholder}
            />
          </div>

          <div className="space-y-2">
            <Label>{t.routes.fieldUpstreamModels}</Label>
            <p className="text-xs text-muted-foreground">{t.routes.upstreamModelsHint}</p>

            {formUpstreamModels.length > 0 && (
              <div className="rounded-md border divide-y">
                {formUpstreamModels.map((model, idx) => (
                  <div key={model} className="flex items-center gap-2 px-3 py-1.5 text-sm">
                    <span className="w-5 h-5 rounded-full bg-primary/10 text-primary text-xs font-medium flex items-center justify-center shrink-0">
                      {idx + 1}
                    </span>
                    <span className="flex-1 truncate">{model}</span>
                    <div className="flex gap-0.5 shrink-0">
                      <button
                        type="button"
                        disabled={idx === 0}
                        onClick={() => moveUpstreamModel(idx, -1)}
                        className="p-0.5 rounded hover:bg-accent disabled:opacity-20"
                      >
                        <ArrowUp className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        disabled={idx === formUpstreamModels.length - 1}
                        onClick={() => moveUpstreamModel(idx, 1)}
                        className="p-0.5 rounded hover:bg-accent disabled:opacity-20"
                      >
                        <ArrowDown className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => removeUpstreamModel(model)}
                        className="p-0.5 rounded hover:bg-destructive/10 text-destructive"
                      >
                        <X className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}

            <div className="flex gap-2">
              <Input
                value={newModelInput}
                onChange={(e) => setNewModelInput(e.target.value)}
                placeholder={t.routes.modelPlaceholder}
                className="h-8 text-xs flex-1"
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    addUpstreamModel()
                  }
                }}
              />
              <Button type="button" variant="outline" size="sm" onClick={addUpstreamModel} disabled={!newModelInput.trim()}>
                <Plus className="h-3.5 w-3.5" />
              </Button>
            </div>
          </div>

          <div className="space-y-2">
            <Label htmlFor="r-strategy">{t.routes.strategyLabel}</Label>
            <Select
              id="r-strategy"
              value={formStrategy}
              onChange={(e) => setFormStrategy(e.target.value)}
            >
              {STRATEGIES.map((s) => (
                <option key={s} value={s}>{t.routes[s as keyof typeof t.routes] || s}</option>
              ))}
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="r-scene">{t.routes.fieldScene}</Label>
            <Input
              id="r-scene"
              value={formScene}
              onChange={(e) => setFormScene(e.target.value)}
              placeholder={t.routes.scenePlaceholder}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="r-weight">{t.routes.fieldWeight}</Label>
            <Input id="r-weight" type="number" value={formWeight} onChange={(e) => setFormWeight(e.target.value)} min="1" />
          </div>

          <Switch checked={formEnabled} onCheckedChange={setFormEnabled} label={t.routes.fieldEnabled} />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t.common.cancel}</Button>
          <Button onClick={handleSave} loading={saving}>{t.common.save}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
