import { useCallback, useEffect, useState } from 'react'
import { webhooks } from '@/api/client'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { LoadingScreen } from '@/components/ui/loading'
import { Switch } from '@/components/ui/switch'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { toast } from '@/components/ui/toast'
import { useTranslation } from '@/i18n'
import type { WebhookEndpoint } from '@/types/api'
import { Pencil, Plus, Trash2 } from 'lucide-react'

const COPY = {
  en: {
    title: 'Webhooks',
    intro: 'Manage outbound webhook endpoints for event delivery.',
    count: '{count} webhook endpoint(s) configured',
    add: 'Add Webhook',
    addTitle: 'Add Webhook',
    editTitle: 'Edit Webhook',
    addDesc: 'Create a webhook endpoint for gateway events.',
    editDesc: 'Update webhook endpoint settings and subscriptions.',
    noData: 'No webhook endpoints configured. Click "Add Webhook" to get started.',
    name: 'Name',
    url: 'URL',
    events: 'Events',
    secret: 'HMAC Secret',
    status: 'Status',
    actions: 'Actions',
    enabled: 'Enabled',
    disabled: 'Disabled',
    eventsPlaceholder: 'request.completed, provider.failed',
    secretPlaceholder: 'Optional signing secret',
    savedSuccess: 'Webhook saved successfully',
    deletedSuccess: 'Webhook deleted successfully',
  },
  zh: {
    title: 'Webhooks',
    intro: '管理用于事件投递的回调地址。',
    count: '已配置 {count} 个 Webhook 端点',
    add: '新增 Webhook',
    addTitle: '新增 Webhook',
    editTitle: '编辑 Webhook',
    addDesc: '创建一个用于接收网关事件的 Webhook 端点。',
    editDesc: '更新 Webhook 端点配置与订阅事件。',
    noData: '暂无 Webhook 端点，点击“新增 Webhook”开始配置。',
    name: '名称',
    url: 'URL',
    events: '事件',
    secret: 'HMAC 密钥',
    status: '状态',
    actions: '操作',
    enabled: '启用',
    disabled: '停用',
    eventsPlaceholder: 'request.completed, provider.failed',
    secretPlaceholder: '可选签名密钥',
    savedSuccess: 'Webhook 保存成功',
    deletedSuccess: 'Webhook 删除成功',
  },
} as const

function getPageCopy() {
  if (typeof window === 'undefined') return COPY.en
  return localStorage.getItem('gateway-language') === 'zh' ? COPY.zh : COPY.en
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}

export default function WebhooksPage() {
  const { t } = useTranslation()
  const text = getPageCopy()
  const [items, setItems] = useState<WebhookEndpoint[]>([])
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingWebhook, setEditingWebhook] = useState<WebhookEndpoint | null>(null)
  const [saving, setSaving] = useState(false)

  const [formName, setFormName] = useState('')
  const [formUrl, setFormUrl] = useState('')
  const [formEventsInput, setFormEventsInput] = useState('')
  const [formSecret, setFormSecret] = useState('')
  const [formEnabled, setFormEnabled] = useState(true)

  const loadData = useCallback(async () => {
    setLoading(true)
    try {
      const response = await webhooks.list()
      setItems(response.endpoints)
    } catch (error: unknown) {
      toast({ title: t.common.error, description: getErrorMessage(error), variant: 'error' })
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    let active = true
    void (async () => {
      setLoading(true)
      try {
        const response = await webhooks.list()
        if (active) {
          setItems(response.endpoints)
        }
      } catch (error: unknown) {
        if (active) {
          toast({ title: t.common.error, description: getErrorMessage(error), variant: 'error' })
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

  const resetForm = () => {
    setFormName('')
    setFormUrl('')
    setFormEventsInput('')
    setFormSecret('')
    setFormEnabled(true)
  }

  const openCreate = () => {
    setEditingWebhook(null)
    resetForm()
    setDialogOpen(true)
  }

  const openEdit = (webhook: WebhookEndpoint) => {
    setEditingWebhook(webhook)
    setFormName(webhook.name)
    setFormUrl(webhook.url)
    setFormEventsInput(webhook.events.join(', '))
    setFormSecret(webhook.hmacSecret || '')
    setFormEnabled(webhook.enabled)
    setDialogOpen(true)
  }

  const buildPayload = (): WebhookEndpoint => {
    // 将逗号/换行输入规范化为事件数组，保持表单实现最小化。
    const events = formEventsInput
      .split(/[\n,]/)
      .map((item) => item.trim())
      .filter(Boolean)

    return {
      name: formName.trim(),
      url: formUrl.trim(),
      enabled: formEnabled,
      events,
      hmacSecret: formSecret.trim() || undefined,
    }
  }

  const handleSave = async () => {
    if (!formName.trim() || !formUrl.trim()) return
    setSaving(true)
    try {
      const payload = buildPayload()
      if (editingWebhook) {
        await webhooks.update(editingWebhook.id ?? editingWebhook.name, payload)
      } else {
        await webhooks.create(payload)
      }
      toast({ title: text.savedSuccess, variant: 'success' })
      setDialogOpen(false)
      resetForm()
      await loadData()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: getErrorMessage(error), variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (webhook: WebhookEndpoint) => {
    if (!confirm(t.common.deleteConfirm.replace('{name}', webhook.name))) return
    try {
      await webhooks.remove(webhook.id ?? webhook.name)
      toast({ title: text.deletedSuccess, variant: 'success' })
      await loadData()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: getErrorMessage(error), variant: 'error' })
    }
  }

  if (loading) return <LoadingScreen />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{text.title}</h1>
          <p className="text-sm text-muted-foreground">{text.intro}</p>
          <p className="text-sm text-muted-foreground">{text.count.replace('{count}', String(items.length))}</p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> {text.add}
        </Button>
      </div>

      {items.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">{text.noData}</CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{text.name}</TableHead>
                  <TableHead>{text.url}</TableHead>
                  <TableHead>{text.events}</TableHead>
                  <TableHead>{text.status}</TableHead>
                  <TableHead className="text-right">{text.actions}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((webhook) => (
                  <TableRow key={webhook.id ?? webhook.name}>
                    <TableCell className="font-medium">{webhook.name}</TableCell>
                    <TableCell className="max-w-[280px] truncate text-muted-foreground">{webhook.url}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {webhook.events.length > 0 ? webhook.events.map((event) => (
                          <Badge key={event} variant="secondary">{event}</Badge>
                        )) : <span className="text-muted-foreground">—</span>}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={webhook.enabled ? 'success' : 'secondary'}>
                        {webhook.enabled ? text.enabled : text.disabled}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => openEdit(webhook)} title={t.common.edit}>
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => handleDelete(webhook)} title={t.common.delete}>
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
            <DialogTitle>{editingWebhook ? text.editTitle : text.addTitle}</DialogTitle>
            <DialogDescription>{editingWebhook ? text.editDesc : text.addDesc}</DialogDescription>
          </DialogHeader>

          <div className="space-y-4 max-h-[65vh] overflow-y-auto pr-1">
            <div className="space-y-2">
              <Label htmlFor="webhook-name">{text.name}</Label>
              <Input id="webhook-name" value={formName} onChange={(e) => setFormName(e.target.value)} placeholder="alerts-primary" />
            </div>

            <div className="space-y-2">
              <Label htmlFor="webhook-url">{text.url}</Label>
              <Input id="webhook-url" value={formUrl} onChange={(e) => setFormUrl(e.target.value)} placeholder="https://example.com/webhooks/gateway" />
            </div>

            <div className="space-y-2">
              <Label htmlFor="webhook-events">{text.events}</Label>
              <Input
                id="webhook-events"
                value={formEventsInput}
                onChange={(e) => setFormEventsInput(e.target.value)}
                placeholder={text.eventsPlaceholder}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="webhook-secret">{text.secret}</Label>
              <Input
                id="webhook-secret"
                type="password"
                value={formSecret}
                onChange={(e) => setFormSecret(e.target.value)}
                placeholder={text.secretPlaceholder}
              />
            </div>

            <Switch checked={formEnabled} onCheckedChange={setFormEnabled} label={text.enabled} />
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>{t.common.cancel}</Button>
            <Button onClick={handleSave} loading={saving} disabled={!formName.trim() || !formUrl.trim()}>{t.common.save}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
