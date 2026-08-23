import { useEffect, useState, useCallback } from 'react'
import { useTranslation } from '@/i18n'
import { users } from '@/api/client'
import { Card, CardContent } from '@/components/ui/card'
import { Table, TableBody, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { LoadingScreen } from '@/components/ui/loading'
import { toast } from '@/components/ui/toast'
import type { UserView } from '@/types/api'
import { CreateUserDialog } from '@/components/users/CreateUserDialog'
import { UserRow } from '@/components/users/UserRow'

export default function UsersPage() {
  const { t } = useTranslation()
  const [userList, setUserList] = useState<UserView[]>([])
  const [loading, setLoading] = useState(true)

  const loadUsers = useCallback(() => {
    setLoading(true)
    users.list()
      .then((res) => setUserList(res.users))
      .catch((error: unknown) => toast({ title: t.common.error, description: error instanceof Error ? error.message : 'Unknown error', variant: 'error' }))
      .finally(() => setLoading(false))
  }, [t])

  useEffect(() => {
    let active = true
    void (async () => {
      setLoading(true)
      try {
        const res = await users.list()
        if (active) {
          setUserList(res.users)
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

  if (loading) return <LoadingScreen />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{t.users.title}</h1>
          <p className="text-sm text-muted-foreground">{t.users.count.replace('{count}', String(userList.length))}</p>
        </div>
        <CreateUserDialog onCreated={loadUsers} />
      </div>

      {userList.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">{t.users.noData}</CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t.users.username}</TableHead>
                  <TableHead>{t.users.role}</TableHead>
                  <TableHead>{t.users.apiKey}</TableHead>
                  <TableHead>{t.users.status}</TableHead>
                  <TableHead>{t.users.created}</TableHead>
                  <TableHead className="text-right">{t.common.actions}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {userList.map((user) => (
                  <UserRow key={user.username} user={user} onUpdated={loadUsers} />
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
