import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from '@/i18n'
import { users } from '@/api/client'
import type { AdminApiKeyView } from '@/types/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Switch } from '@/components/ui/switch'
import { Spinner } from '@/components/ui/loading'
import { toast } from '@/components/ui/toast'
import { Plus, Trash2, RefreshCw } from 'lucide-react'

export function ManageApiKeysDialog({
  username, open, onOpenChange,
}: {
  username: string; open: boolean; onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  const [apiKeys, setApiKeys] = useState<AdminApiKeyView[]>([])
  const [loading, setLoading] = useState(false)
  const [newName, setNewName] = useState('')
  const [creating, setCreating] = useState(false)
  const [newKeyResult, setNewKeyResult] = useState<string | null>(null)
  const [rotateResult, setRotateResult] = useState<string | null>(null)

  const loadKeys = useCallback(async () => {
    setNewKeyResult(null)
    setRotateResult(null)
    setLoading(true)
    try {
      const res = await users.listApiKeys(username)
      setApiKeys(res.apiKeys)
    } catch {
      toast({ title: t.common.error, variant: 'error' })
    } finally {
      setLoading(false)
    }
  }, [t.common.error, username])

  useEffect(() => {
    if (!open) return
    const timer = window.setTimeout(() => {
      void loadKeys()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [loadKeys, open])

  const handleCreateKey = async () => {
    if (!newName.trim()) return
    setCreating(true)
    try {
      const res = await users.createApiKey(username, { name: newName })
      setNewKeyResult(res.apiKey || t.users.keyCreated)
      setNewName('')
      toast({ title: t.users.keyCreated, variant: 'success' })
      void loadKeys()
    } catch (error) {
      const message = error instanceof Error ? error.message : t.common.error
      toast({ title: t.common.error, description: message, variant: 'error' })
    } finally {
      setCreating(false)
    }
  }

  const handleDeleteKey = async (keyId: string) => {
    if (!confirm(t.common.deleteConfirm.replace('{name}', keyId))) return
    try {
      await users.deleteApiKey(username, keyId)
      toast({ title: t.users.keyDeleted, variant: 'success' })
      void loadKeys()
    } catch {
      toast({ title: t.common.error, variant: 'error' })
    }
  }

  const handleToggleKey = async (keyId: string, enabled: boolean) => {
    try {
      await users.toggleApiKey(username, keyId, enabled)
      void loadKeys()
    } catch {
      toast({ title: t.common.error, variant: 'error' })
    }
  }

  const handleRotateKey = async (keyId: string) => {
    try {
      const res = await users.rotateApiKey(username, keyId)
      setRotateResult(res.apiKey)
      toast({ title: t.users.keyRotated, variant: 'success' })
      void loadKeys()
    } catch {
      toast({ title: t.common.error, variant: 'error' })
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t.users.manageApiKeys.replace('{name}', username)}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          {loading ? (
            <div className="flex justify-center py-4"><Spinner /></div>
          ) : (
            <div className="space-y-2">
              {apiKeys.length === 0 && <p className="text-sm text-muted-foreground">{t.users.noApiKeys}</p>}
              {apiKeys.map((key) => (
                <div key={key.keyId} className="flex items-center justify-between p-2 rounded border">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium">{key.name || key.keyId}</p>
                    <p className="text-xs text-muted-foreground font-mono truncate">{key.apiKeyMasked}</p>
                  </div>
                  <div className="flex gap-1">
                    <Switch checked={key.enabled} onCheckedChange={(c) => handleToggleKey(key.keyId, c)} />
                    <Button variant="ghost" size="sm" onClick={() => handleRotateKey(key.keyId)} title="Rotate">
                      <RefreshCw className="h-3 w-3" />
                    </Button>
                    <Button variant="ghost" size="sm" onClick={() => handleDeleteKey(key.keyId)} title={t.common.delete}>
                      <Trash2 className="h-3 w-3 text-destructive" />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {newKeyResult && newKeyResult !== t.users.keyCreated && (
            <div className="p-3 bg-muted rounded text-sm">
              <p className="font-medium mb-1">{t.users.newApiKey}</p>
              <code className="font-mono text-xs break-all">{newKeyResult}</code>
            </div>
          )}

          {rotateResult && (
            <div className="p-3 bg-amber-50 dark:bg-amber-950 rounded text-sm">
              <p className="font-medium mb-1">{t.users.rotatedKey}</p>
              <code className="font-mono text-xs break-all">{rotateResult}</code>
            </div>
          )}

          <div className="h-px bg-border" />

          <div className="flex gap-2">
            <Input
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder={t.users.newKeyName}
              className="flex-1"
            />
            <Button size="sm" onClick={handleCreateKey} loading={creating}>
              <Plus className="h-4 w-4" /> {t.users.createKey}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
