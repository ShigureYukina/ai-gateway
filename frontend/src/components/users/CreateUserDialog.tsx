import { useState } from 'react'
import { useTranslation } from '@/i18n'
import { users } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { toast } from '@/components/ui/toast'
import { Plus } from 'lucide-react'

export function CreateUserDialog({ onCreated }: { onCreated: () => void }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('user')
  const [saving, setSaving] = useState(false)

  const handleCreate = async () => {
    if (!username.trim() || !password.trim()) return
    setSaving(true)
    try {
      await users.create({ username, password, role })
      toast({ title: t.users.createdSuccess.replace('{name}', username), variant: 'success' })
      setOpen(false)
      setUsername('')
      setPassword('')
      setRole('user')
      onCreated()
    } catch (error: unknown) {
      toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>
        <Plus className="h-4 w-4" /> {t.users.add}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t.users.createTitle}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>{t.users.fieldUsername}</Label>
              <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="newuser" />
            </div>
            <div className="space-y-2">
              <Label>{t.users.fieldPassword}</Label>
              <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </div>
            <div className="space-y-2">
              <Label>{t.users.fieldRole}</Label>
              <Select value={role} onChange={(e) => setRole(e.target.value)}>
                <option value="user">User</option>
                <option value="admin">Admin</option>
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>{t.common.cancel}</Button>
            <Button onClick={handleCreate} loading={saving}>{t.users.create}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
