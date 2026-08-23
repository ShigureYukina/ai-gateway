import { useState } from 'react'
import { useTranslation } from '@/i18n'
import { users } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { TableCell, TableRow } from '@/components/ui/table'
import { toast } from '@/components/ui/toast'
import { Pencil, Trash2, Key, ClipboardList } from 'lucide-react'
import type { UserView } from '@/types/api'
import { EditUserDialog } from './EditUserDialog'
import { ManageApiKeysDialog } from './ManageApiKeysDialog'
import { UserRequestLogsDialog } from './UserRequestLogsDialog'

export function UserRow({ user, onUpdated }: { user: UserView; onUpdated: () => void }) {
  const { t } = useTranslation()
  const [editOpen, setEditOpen] = useState(false)
  const [apiKeysOpen, setApiKeysOpen] = useState(false)
  const [requestLogsOpen, setRequestLogsOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const handleDelete = async () => {
    if (!confirm(t.common.deleteConfirm.replace('{name}', user.username))) return
    setDeleting(true)
    try {
      await users.remove(user.username)
      toast({ title: t.users.deletedSuccess.replace('{name}', user.username), variant: 'success' })
      onUpdated()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setDeleting(false)
    }
  }

  return (
    <>
      <TableRow>
        <TableCell className="font-medium">{user.username}</TableCell>
        <TableCell>
          <Badge variant={user.role === 'admin' ? 'default' : 'secondary'}>{user.role}</Badge>
        </TableCell>
        <TableCell className="text-muted-foreground font-mono text-xs">{user.apiKeyMasked}</TableCell>
        <TableCell>
          <Badge variant={user.frozen ? 'destructive' : 'success'}>
            {user.frozen ? t.users.frozen : t.users.active}
          </Badge>
        </TableCell>
        <TableCell className="text-muted-foreground text-sm">
          {new Date(user.createdAt).toLocaleDateString()}
        </TableCell>
        <TableCell className="text-right">
          <div className="flex justify-end gap-1">
            <Button variant="ghost" size="sm" onClick={() => setRequestLogsOpen(true)} title={t.users.viewRequests}>
              <ClipboardList className="h-4 w-4" />
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setApiKeysOpen(true)} title={t.users.apiKeys}>
              <Key className="h-4 w-4" />
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setEditOpen(true)} title={t.common.edit}>
              <Pencil className="h-4 w-4" />
            </Button>
            <Button variant="ghost" size="sm" onClick={handleDelete} loading={deleting} title={t.common.delete}>
              <Trash2 className="h-4 w-4 text-destructive" />
            </Button>
          </div>
        </TableCell>
      </TableRow>

      <EditUserDialog user={user} open={editOpen} onOpenChange={setEditOpen} onUpdated={onUpdated} />
      <ManageApiKeysDialog username={user.username} open={apiKeysOpen} onOpenChange={setApiKeysOpen} />
      <UserRequestLogsDialog username={user.username} clientId={user.clientId} open={requestLogsOpen} onOpenChange={setRequestLogsOpen} />
    </>
  )
}
