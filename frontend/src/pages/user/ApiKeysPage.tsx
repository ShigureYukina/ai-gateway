import { useEffect, useState } from 'react'
import { auth } from '@/api/client'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { toast } from '@/components/ui/toast'
import { useTranslation } from '@/i18n'
import { LoadingScreen } from '@/components/ui/loading'
import { Copy, Check } from 'lucide-react'
import type { AuthKeyItem, AuthCreateKeyResponse } from '@/types/api'

export default function UserApiKeysPage() {
  const { t } = useTranslation()
  const [keys, setKeys] = useState<AuthKeyItem[]>([])
  const [loading, setLoading] = useState(true)

  // Create key dialog
  const [showCreate, setShowCreate] = useState(false)
  const [newKeyName, setNewKeyName] = useState('')
  const [creating, setCreating] = useState(false)
  const [createdKey, setCreatedKey] = useState<AuthCreateKeyResponse | null>(null)
  const [copied, setCopied] = useState(false)

  // Delete key dialog
  const [showDelete, setShowDelete] = useState(false)
  const [deletingKey, setDeletingKey] = useState<AuthKeyItem | null>(null)
  const [deleting, setDeleting] = useState(false)

  const loadKeys = () => {
    setLoading(true)
    auth.listKeys()
      .then((res) => setKeys(res.keys))
      .catch(() => toast({ title: t.userKeys.loadingFailed, variant: 'error' }))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    let active = true
    void (async () => {
      setLoading(true)
      try {
        const res = await auth.listKeys()
        if (active) {
          setKeys(res.keys)
        }
      } catch {
        if (active) {
          toast({ title: t.userKeys.loadingFailed, variant: 'error' })
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
  }, [t.userKeys.loadingFailed])

  const handleCreate = async () => {
    if (!newKeyName.trim()) return
    setCreating(true)
    try {
      const res = await auth.createKey({ name: newKeyName.trim() })
      setCreatedKey(res)
      toast({ title: t.userKeys.keyCreated, variant: 'success' })
      loadKeys()
    } catch (err: unknown) {
      toast({ title: t.common.error, description: err instanceof Error ? err.message : undefined, variant: 'error' })
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async () => {
    if (!deletingKey) return
    setDeleting(true)
    try {
      await auth.deleteKey(deletingKey.keyId)
      toast({ title: t.userKeys.keyDeleted, variant: 'success' })
      setShowDelete(false)
      setDeletingKey(null)
      loadKeys()
    } catch (err: unknown) {
      toast({ title: t.common.error, description: err instanceof Error ? err.message : undefined, variant: 'error' })
    } finally {
      setDeleting(false)
    }
  }

  const handleCopyKey = async (key: string) => {
    try {
      await navigator.clipboard.writeText(key)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      toast({ title: t.common.error, description: t.userKeys.copyFailed, variant: 'error' })
    }
  }

  const formatDate = (ts: number) => new Date(ts).toLocaleDateString()

  if (loading) return <LoadingScreen />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{t.userKeys.title}</h1>
          <p className="text-muted-foreground text-sm mt-1">{t.userKeys.description}</p>
        </div>
        <Button onClick={() => { setNewKeyName(''); setCreatedKey(null); setShowCreate(true) }}>
          {t.userKeys.createKey}
        </Button>
      </div>

      {keys.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">
            {t.userKeys.noKeys}
          </CardContent>
        </Card>
      ) : (
        <Card>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t.userKeys.keyName}</TableHead>
                <TableHead>{t.userKeys.status}</TableHead>
                <TableHead>{t.userKeys.created}</TableHead>
                <TableHead>{t.userKeys.lastUsed}</TableHead>
                <TableHead>{t.userKeys.requests}</TableHead>
                <TableHead>{t.userKeys.actions}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {keys.map((key) => (
                <TableRow key={key.keyId}>
                  <TableCell className="font-medium">{key.name}</TableCell>
                  <TableCell>
                    <Badge variant={key.enabled ? 'success' : 'secondary'}>
                      {key.enabled ? t.userKeys.enabled : t.userKeys.disabled}
                    </Badge>
                  </TableCell>
                  <TableCell>{formatDate(key.createdAt)}</TableCell>
                  <TableCell>{key.lastUsedAt ? formatDate(key.lastUsedAt) : t.userKeys.never}</TableCell>
                  <TableCell>{key.requestCount}</TableCell>
                  <TableCell>
                    <Button
                      variant="destructive"
                      size="sm"
                      onClick={() => { setDeletingKey(key); setShowDelete(true) }}
                    >
                      {t.common.delete}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      {/* Create Dialog */}
      <Dialog open={showCreate} onOpenChange={(open) => { if (!open) setCreatedKey(null); setShowCreate(open) }}>
        <DialogContent>
          {createdKey ? (
            <>
              <DialogHeader>
                <DialogTitle>{t.userKeys.keyCreated}</DialogTitle>
                <DialogDescription>{t.userKeys.keyCreatedCopy}</DialogDescription>
              </DialogHeader>
              <div className="space-y-3">
                <div className="flex items-center gap-2 p-3 bg-muted rounded-md font-mono text-sm break-all">
                  <span>{createdKey.apiKey}</span>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="shrink-0"
                    onClick={() => handleCopyKey(createdKey.apiKey)}
                  >
                    {copied ? <Check className="h-4 w-4 text-emerald-500" /> : <Copy className="h-4 w-4" />}
                  </Button>
                </div>
                <Button className="w-full" onClick={() => setShowCreate(false)}>
                  {t.common.close}
                </Button>
              </div>
            </>
          ) : (
            <>
              <DialogHeader>
                <DialogTitle>{t.userKeys.createTitle}</DialogTitle>
                <DialogDescription>{t.userKeys.createDescription}</DialogDescription>
              </DialogHeader>
              <div className="space-y-3">
                <div>
                  <Label>{t.userKeys.keyName}</Label>
                  <Input
                    value={newKeyName}
                    onChange={(e) => setNewKeyName(e.target.value)}
                    placeholder={t.userKeys.keyNamePlaceholder}
                  />
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setShowCreate(false)}>{t.common.cancel}</Button>
                <Button onClick={handleCreate} loading={creating}>{t.userKeys.createKey}</Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <Dialog open={showDelete} onOpenChange={setShowDelete}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t.userKeys.deleteKey}</DialogTitle>
            <DialogDescription>
              {t.userKeys.deleteConfirm.replace('{name}', deletingKey?.name ?? '')}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowDelete(false)}>{t.common.cancel}</Button>
            <Button variant="destructive" onClick={handleDelete} loading={deleting}>{t.common.delete}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
