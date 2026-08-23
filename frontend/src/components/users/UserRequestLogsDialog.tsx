import { useEffect, useState } from 'react'
import { useTranslation } from '@/i18n'
import { requestLogs } from '@/api/client'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Spinner } from '@/components/ui/loading'
import { toast } from '@/components/ui/toast'
import type { RequestLogEntryView } from '@/types/api'

export function UserRequestLogsDialog({
  username, clientId, open, onOpenChange,
}: {
  username: string; clientId: string; open: boolean; onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  const [requests, setRequests] = useState<RequestLogEntryView[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!open || !clientId) return

    let active = true
    void (async () => {
      setLoading(true)
      try {
        const res = await requestLogs.recent({ client: clientId, limit: 50 })
        if (active) {
          setRequests(res.requests ?? [])
        }
      } catch {
        if (active) {
          toast({ title: t.common.error, variant: 'error' })
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
  }, [clientId, open, t.common.error])

  const totalTokens = requests.reduce((s, r) => s + (r.usageTokens ?? 0), 0)
  const totalCost = requests.reduce((s, r) => s + (r.costUsd ?? 0), 0)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t.users.requestLogsTitle.replace('{name}', username)}</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          {loading ? (
            <div className="flex justify-center py-8"><Spinner /></div>
          ) : requests.length === 0 ? (
            <p className="text-sm text-muted-foreground text-center py-8">{t.users.noRequestLogs}</p>
          ) : (
            <>
              <p className="text-sm text-muted-foreground">
                {t.users.requestLogsSummary
                  .replace('{count}', String(requests.length))
                  .replace('{tokens}', String(totalTokens))
                  .replace('{cost}', `$${totalCost.toFixed(6)}`)}
              </p>
              <div className="max-h-[50vh] overflow-y-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t.operations.model}</TableHead>
                      <TableHead>{t.operations.status}</TableHead>
                      <TableHead>{t.operations.tokens}</TableHead>
                      <TableHead>{t.operations.cost}</TableHead>
                      <TableHead>{t.operations.timestamp}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {requests.map((r) => (
                      <TableRow key={r.requestId}>
                        <TableCell className="font-medium text-sm">{r.model}</TableCell>
                        <TableCell><Badge variant={r.status < 400 ? 'success' : 'destructive'}>{r.status}</Badge></TableCell>
                        <TableCell className="text-sm">{r.usageTokens ?? '-'}</TableCell>
                        <TableCell className="text-sm">${r.costUsd?.toFixed(6) ?? '-'}</TableCell>
                        <TableCell className="text-xs text-muted-foreground">{new Date(r.timestamp).toLocaleString()}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
